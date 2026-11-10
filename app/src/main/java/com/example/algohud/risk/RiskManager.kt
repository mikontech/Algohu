package com.example.algohud.risk

import android.content.SharedPreferences
import android.util.Log
import com.example.algohud.core.OrderRequest
import com.example.algohud.core.Position
import com.example.algohud.core.Side
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

class RiskManager(
    private val prefs: SharedPreferences,
    val config: Config = Config(),
    private val marketBlackout: () -> Boolean = { false },
    private val clock: () -> Long = System::currentTimeMillis,
    private val zone: ZoneId = ZoneId.systemDefault(),
    private val journal: TradeJournal? = null,
    private val ioScope: CoroutineScope = CoroutineScope(Dispatchers.IO)
) {
    data class Config(
        val maxDailyLossPct: Double = Double.POSITIVE_INFINITY,
        val riskPerTradePct: Double = 0.02,
        val maxNotionalToEquity: Double = 5.0,
        val tierRiskPct: List<Double> = listOf(0.005, 0.01, 0.025),
        val tierLeverage: List<Double> = listOf(3.0, 5.0, 8.0),
        val maxOpenPositions: Int = 1,
        val maxOrdersPerMinute: Int = Int.MAX_VALUE,
        val minStopDistancePct: Double = 0.0005,
        val cooldownAfterLossMs: Long = 0L,
        val allowShort: Boolean = true,
        val allowPyramiding: Boolean = false,
        val allowedSymbols: Set<String> = emptySet(),
        val lotStep: Double = 0.001,
        val rolloverHour: Int = 23,
        val noEntryBeforeRolloverMin: Int = 0,
        val closeBeforeRolloverMin: Int = -1
    )

    sealed interface Decision {
        data class Approved(val order: OrderRequest) : Decision
        data class Rejected(val reason: String) : Decision
    }

    private val mutex = Mutex()
    @Volatile var cooldownAfterLossMs: Long = config.cooldownAfterLossMs

    private val _killSwitch = MutableStateFlow(prefs.getBoolean(K_KILL, false))
    val killSwitch: StateFlow<Boolean> = _killSwitch.asStateFlow()

    var killReason: String? = prefs.getString(K_KILL_REASON, null)
        private set

    private val _dailyPnl = MutableStateFlow(0.0)
    val dailyPnl: StateFlow<Double> = _dailyPnl.asStateFlow()

    private var tradingDay: LocalDate = prefs.getString(K_DAY, null)?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: today()
    private var dayStartEquity = prefs.getString(K_DAY_EQUITY, null)?.toDoubleOrNull() ?: 0.0
    private var realizedToday = prefs.getString(K_REALIZED, null)?.toDoubleOrNull() ?: 0.0
    private var realizedPaperToday = prefs.getString(K_REALIZED_PAPER, null)?.toDoubleOrNull() ?: 0.0

    private val _dailyPnlPaper = MutableStateFlow(0.0)
    val dailyPnlPaper: StateFlow<Double> = _dailyPnlPaper.asStateFlow()
    private var equity = 0.0
    private var lastLossAt = 0L
    private var equitySource: String? = prefs.getString(K_SOURCE, null)

    private val positions = mutableMapOf<String, Position>()
    private val marks = mutableMapOf<String, Double>()
    private val pendingStops = mutableMapOf<String, Long>()
    private val orderTimes = ArrayDeque<Long>()

    suspend fun updateEquity(value: Double, source: String) = mutex.withLock {
        equity = value
        rolloverLocked()
        if (source != equitySource) {
            equitySource = source
            dayStartEquity = value
            persistLocked()
        }
        if (dayStartEquity <= 0.0 && value > 0.0) {
            dayStartEquity = value
            persistLocked()
        }
        recomputePnlLocked()
    }

    private fun riskPct(tier: Int) = config.tierRiskPct.getOrElse(tier) { config.riskPerTradePct }
    private fun leverage(tier: Int) = config.tierLeverage.getOrElse(tier) { config.maxNotionalToEquity }

    fun sizing(equity: Double, entry: Double, stop: Double, pointValue: Double = 1.0, tier: Int = 1): PositionSizer.Result {
        if (abs(entry - stop) < entry * config.minStopDistancePct) {
            return PositionSizer.Result.Rejected("Stop-Loss za blisko (min. %.2f)".format(entry * config.minStopDistancePct))
        }
        return PositionSizer.size(
            PositionSizer.Input(equity, entry, stop, pointValue, riskPct(tier), leverage(tier), config.lotStep)
        )
    }

    suspend fun evaluate(order: OrderRequest): Decision = mutex.withLock {
        rolloverLocked()
        val now = clock()

        fun reject(reason: String): Decision {
            Log.w(TAG, "Odrzucono ${order.side} ${order.quantity} ${order.symbol}: $reason")
            return Decision.Rejected(reason)
        }

        if (!order.quantity.isFinite() || order.quantity <= 0.0) return@withLock reject("Nieprawidłowy wolumen")
        if (config.allowedSymbols.isNotEmpty() && order.symbol !in config.allowedSymbols) {
            return@withLock reject("Symbol nieobsługiwany")
        }

        val existing = positions[order.symbol]
        val reducing = existing != null && existing.side != order.side && order.quantity <= existing.quantity + 1e-12
        if (reducing) return@withLock Decision.Approved(order)

        if (_killSwitch.value) return@withLock reject("Aktywny KILL-SWITCH: $killReason")
        if (equity <= 0.0 || dayStartEquity <= 0.0) return@withLock reject("Brak zweryfikowanego kapitału")
        if (order.side == Side.SELL && existing == null && !config.allowShort) return@withLock reject("Krótka sprzedaż wyłączona")
        if (existing == null && positions.size >= config.maxOpenPositions) return@withLock reject("Osiągnięto limit pozycji")
        if (existing != null && !config.allowPyramiding) return@withLock reject("Pozycja już istnieje")
        if (marketBlackout()) return@withLock reject("Blokada publikacji makroekonomicznych")

        val ref = order.referencePrice
        val stopOnCorrectSide = if (order.side == Side.BUY) order.stopPrice < ref else order.stopPrice > ref
        if (!stopOnCorrectSide) return@withLock reject("Stop-Loss po błędnej stronie ceny")

        val dist = abs(ref - order.stopPrice)
        if (dist < ref * config.minStopDistancePct) return@withLock reject("Stop-Loss zbyt blisko ceny")

        val tradeRisk = dist * order.quantity * order.pointValue
        val maxTradeRisk = equity * riskPct(order.tier)
        if (tradeRisk > maxTradeRisk * 1.05) {
            return@withLock reject("Ryzyko przekracza limit (%.2f > %.2f)".format(tradeRisk, maxTradeRisk))
        }

        orderTimes.addLast(now)
        Decision.Approved(order)
    }

    suspend fun onFill(order: OrderRequest, price: Double, qty: Double, paper: Boolean = false) = mutex.withLock {
        val existing = positions[order.symbol]
        when {
            existing != null && existing.side != order.side -> {
                val closedQty = min(qty, existing.quantity)
                val pnl = (price - existing.entryPrice) * closedQty * existing.side.sign * existing.pointValue
                if (existing.paper) realizedPaperToday += pnl else realizedToday += pnl
                journal?.record(closedTradeLocked(existing, closedQty, price, pnl, order.reason))
                if (pnl < 0) lastLossAt = clock()
                val remaining = existing.quantity - closedQty
                if (remaining <= 1e-12) {
                    positions.remove(order.symbol)
                    pendingStops.remove(order.symbol)
                } else {
                    positions[order.symbol] = existing.copy(quantity = remaining)
                }
            }
            existing != null -> {
                val newQty = existing.quantity + qty
                positions[order.symbol] = existing.copy(
                    quantity = newQty,
                    entryPrice = (existing.entryPrice * existing.quantity + price * qty) / newQty
                )
            }
            else -> positions[order.symbol] = Position(
                order.symbol, order.side, qty, price, order.stopPrice, order.pointValue, paper,
                takeProfit = order.takeProfit, entryReason = order.reason, openedAt = clock()
            )
        }
        recomputePnlLocked()
        persistLocked()
    }

    suspend fun engageKillSwitch(reason: String) = mutex.withLock {
        if (!_killSwitch.value) setKillLocked(true, reason)
    }

    suspend fun resetKillSwitch(): Boolean = mutex.withLock {
        rolloverLocked()
        if (killReason == REASON_DAILY_LOSS) return@withLock false
        setKillLocked(false, null)
        true
    }

    suspend fun clearPaperPositions() = mutex.withLock {
        positions.values.removeAll { it.paper }
        recomputePnlLocked()
    }

    suspend fun flattenOrders(): List<OrderRequest> = mutex.withLock {
        positions.values.map { p ->
            val px = marks[p.symbol] ?: p.entryPrice
            OrderRequest(p.symbol, p.side.opposite(), p.quantity, px, stopPrice = px, reason = "FLATTEN",
                pointValue = p.pointValue, reduceOnly = true)
        }
    }

    private fun closedTradeLocked(p: Position, qty: Double, exit: Double, pnl: Double, reason: String): ClosedTrade {
        val riskAtEntry = abs(p.entryPrice - p.initialStop) * qty * p.pointValue
        return ClosedTrade(
            symbol = p.symbol, side = p.side, quantity = qty, entry = p.entryPrice, exit = exit, pnl = pnl,
            r = if (riskAtEntry > 0) pnl / riskAtEntry else 0.0,
            reason = if (p.entryReason.isNotEmpty()) "${p.entryReason} → $reason" else reason,
            paper = p.paper, closedAt = clock()
        )
    }

    private fun today(): LocalDate = Instant.ofEpochMilli(clock()).atZone(zone).toLocalDate()

    private fun rolloverLocked() {
        val d = today()
        if (d == tradingDay) return
        tradingDay = d
        dayStartEquity = if (equity > 0) equity else 0.0
        realizedToday = 0.0
        realizedPaperToday = 0.0
        if (killReason == REASON_DAILY_LOSS) setKillLocked(false, null)
        persistLocked()
    }

    private fun recomputePnlLocked() {
        fun unreal(paper: Boolean) = positions.values.filter { it.paper == paper }.sumOf { p ->
            val m = marks[p.symbol] ?: p.entryPrice
            (m - p.entryPrice) * p.quantity * p.side.sign * p.pointValue
        }
        _dailyPnl.value = realizedToday + unreal(false)
        _dailyPnlPaper.value = realizedPaperToday + unreal(true)
    }

    private fun setKillLocked(on: Boolean, reason: String?) {
        _killSwitch.value = on
        killReason = reason
        ioScope.launch {
            prefs.edit().putBoolean(K_KILL, on).putString(K_KILL_REASON, reason).apply()
        }
        Log.w(TAG, "Stan KILL-SWITCH: $on ($reason)")
    }

    private fun persistLocked() {
        val dayStr = tradingDay.toString()
        val eqStr = dayStartEquity.toString()
        val rStr = realizedToday.toString()
        val rpStr = realizedPaperToday.toString()
        val src = equitySource
        ioScope.launch {
            prefs.edit()
                .putString(K_DAY, dayStr)
                .putString(K_DAY_EQUITY, eqStr)
                .putString(K_REALIZED, rStr)
                .putString(K_REALIZED_PAPER, rpStr)
                .putString(K_SOURCE, src)
                .apply()
        }
    }

    companion object {
        const val REASON_DAILY_LOSS = "Przekroczony dzienny limit straty"
        private const val TAG = "RiskManager"
        private const val K_KILL = "kill"
        private const val K_KILL_REASON = "kill_reason"
        private const val K_DAY = "day"
        private const val K_DAY_EQUITY = "day_equity"
        private const val K_REALIZED = "realized"
        private const val K_REALIZED_PAPER = "realized_paper"
        private const val K_SOURCE = "equity_source"
    }
}
