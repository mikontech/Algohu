package com.example.algohud.risk

import android.content.SharedPreferences
import android.util.Log
import com.example.algohud.core.OrderRequest
import com.example.algohud.core.Position
import com.example.algohud.core.Side
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import java.math.BigDecimal

/**
 * Bezpiecznik. KAŻDE zlecenie (bot, ręczne, stop) przechodzi przez evaluate().
 * Zasada: zlecenia ZMNIEJSZAJĄCE ekspozycję są zawsze dozwolone (także przy KILL),
 * zlecenia ZWIĘKSZAJĄCE muszą przejść wszystkie testy.
 */
class RiskManager(
    private val prefs: SharedPreferences,
    val config: Config = Config(),
    private val marketBlackout: () -> Boolean = { false },
    private val clock: () -> Long = System::currentTimeMillis,
    private val zone: ZoneId = ZoneId.systemDefault(),
    private val journal: TradeJournal? = null
) {
    data class Config(
        val maxDailyLossPct: Double = Double.POSITIVE_INFINITY,          // twardy limit dziennej straty: 5% kapitału z początku dnia
        val riskPerTradePct: Double = 0.02,          // 2% kapitału ryzyka na transakcję (do stop-lossa)
        val maxNotionalToEquity: Double = 5.0,       // wartość kontraktu maks. 5× kapitał (dźwignia CFD) – poziom umiarkowany
        /** Ryzyko na transakcję wg poziomu agresji: zredukowany / umiarkowany / agresywny. */
        val tierRiskPct: List<Double> = listOf(0.005, 0.01, 0.025), // agresywny = 2,5% (profil momentum / RT / ORB)
        /** Maks. dźwignia wg poziomu agresji. */
        val tierLeverage: List<Double> = listOf(3.0, 5.0, 8.0),
        /** false = stop prowadzi wyłącznie TrailingManager (bot), bez wbudowanego „1R za ceną”. */
        val autoTrail: Boolean = true,
        val maxOpenPositions: Int = 1,
        val maxOrdersPerMinute: Int = Int.MAX_VALUE,
        val minStopDistancePct: Double = 0.0005,     // min. 0,05% ceny (złoto ≈ 2 USD); RT dodatkowo ≥ 3× spread
        val cooldownAfterLossMs: Long = 0L,
        val allowShort: Boolean = true,              // CFD: krótka sprzedaż dozwolona
        val allowPyramiding: Boolean = false,
        val allowedSymbols: Set<String> = emptySet(),   // pusta = każdy symbol
        val lotStep: Double = 0.001,                 // krok wolumenu (XTB: loty)
        /** Godzina rozliczenia swapu (czas lokalny telefonu). Sprawdź w specyfikacji instrumentu w XTB. */
        val rolloverHour: Int = 23,
        /** Brak nowych wejść tyle minut przed rozliczeniem swapu (pozycje intraday – bez swapu). */
        val noEntryBeforeRolloverMin: Int = 0, // wyłączone – zastąpione regułami sesji (MarketHours)
        /** Zamknięcie otwartych pozycji tyle minut przed rozliczeniem swapu. */
        val closeBeforeRolloverMin: Int = -1
    )

    sealed interface Decision {
        data class Approved(val order: OrderRequest) : Decision
        data class Rejected(val reason: String) : Decision
    }

    private val mutex = Mutex()

    /** Przerwa po stratnej transakcji – profil agresywny skraca ją (ustawiane przez orkiestrator). */
    @Volatile var cooldownAfterLossMs: Long = config.cooldownAfterLossMs

    /** Ile ms przerwy po stracie jeszcze zostało (0 = brak). */
    fun cooldownLeftMs(): Long = if (config.cooldownAfterLossMs == 0L) 0L else (cooldownAfterLossMs - (clock() - lastLossAt)).coerceAtLeast(0L)

    private val _killSwitch = MutableStateFlow(prefs.getBoolean(K_KILL, false))
    val killSwitch: StateFlow<Boolean> = _killSwitch.asStateFlow()

    var killReason: String? = prefs.getString(K_KILL_REASON, null)
        private set

    private val _dailyPnl = MutableStateFlow(0.0)
    val dailyPnl: StateFlow<Double> = _dailyPnl.asStateFlow()

    private var tradingDay: LocalDate = prefs.getString(K_DAY, null)?.let(LocalDate::parse) ?: today()
    private var dayStartEquity = prefs.getString(K_DAY_EQUITY, null)?.toDoubleOrNull() ?: 0.0
    private var realizedToday = prefs.getString(K_REALIZED, null)?.toDoubleOrNull() ?: 0.0 // tylko prawdziwe (XTB)
    private var realizedPaperToday = prefs.getString(K_REALIZED_PAPER, null)?.toDoubleOrNull() ?: 0.0

    private val _dailyPnlPaper = MutableStateFlow(0.0)
    /** Wynik dnia transakcji papierowych (DRY_RUN) – nie wpływa na KILL. */
    val dailyPnlPaper: StateFlow<Double> = _dailyPnlPaper.asStateFlow()
    private var equity = 0.0
    private var lastLossAt = 0L

    // TODO: po starcie zsynchronizuj pozycje z brokerem (API) – tu trzymane są tylko w pamięci.
    private val positions = mutableMapOf<String, Position>()
    private val marks = mutableMapOf<String, Double>()
    /** symbol → kiedy zlecono zamknięcie (soft-stop); po 20 s bez skutku próba jest ponawiana. */
    private val pendingStops = mutableMapOf<String, Long>()
    private val orderTimes = ArrayDeque<Long>()

    // ── Kapitał i wielkość pozycji ──

    /** @param source np. "XTB:PLN" albo "PAPER" – zmiana źródła = nowy punkt odniesienia dla limitu dziennego. */
    suspend fun updateEquity(value: Double, source: String) = mutex.withLock {
        equity = value
        rolloverLocked()
        if (source != equitySource) {
            equitySource = source
            dayStartEquity = value
            prefs.edit().putString(K_SOURCE, source).apply()
            persistLocked()
        }
        if (dayStartEquity <= 0.0 && value > 0.0) { dayStartEquity = value; persistLocked() }
        recomputePnlLocked()
    }

    private var equitySource: String? = prefs.getString(K_SOURCE, null)

    fun riskOf(qty: Double, entry: Double, stop: Double, pointValue: Double) = abs(entry - stop) * qty * pointValue

    suspend fun maxTradeRisk(tier: Int = 1): Double = mutex.withLock { equity * riskPct(tier) }

    /**
     * Fixed-fractional: qty = (kapitał × ryzyko%) / |wejście − stop|,
     * przycięte limitem ekspozycji i zaokrąglone W DÓŁ do kroku lota.
     */
    private fun riskPct(tier: Int) = config.tierRiskPct.getOrElse(tier) { config.riskPerTradePct }
    private fun leverage(tier: Int) = config.tierLeverage.getOrElse(tier) { config.maxNotionalToEquity }

    fun sizing(equity: Double, entry: Double, stop: Double, pointValue: Double = 1.0, tier: Int = 1): PositionSizer.Result {
        if (abs(entry - stop) < entry * config.minStopDistancePct) {
            return PositionSizer.Result.Rejected("stop za blisko (min. %.2f)".format(entry * config.minStopDistancePct))
        }
        return PositionSizer.size(
            PositionSizer.Input(equity, entry, stop, pointValue, riskPct(tier), leverage(tier), config.lotStep)
        )
    }

    fun positionSize(equity: Double, entry: Double, stop: Double, pointValue: Double = 1.0, tier: Int = 1): Double =
        (sizing(equity, entry, stop, pointValue, tier) as? PositionSizer.Result.Ok)?.lots ?: 0.0

    suspend fun sizingFor(entry: Double, stop: Double, pointValue: Double, tier: Int): PositionSizer.Result =
        mutex.withLock { sizing(equity, entry, stop, pointValue, tier) }

    /** Minuty do najbliższego rozliczenia swapu. */
    fun minutesToRollover(): Long {
        val now = Instant.ofEpochMilli(clock()).atZone(zone)
        var roll = now.withHour(config.rolloverHour).withMinute(0).withSecond(0).withNano(0)
        if (!roll.isAfter(now)) roll = roll.plusDays(1)
        return java.time.Duration.between(now, roll).toMinutes()
    }

    suspend fun hasRealPositions(): Boolean = mutex.withLock { positions.values.any { !it.paper } }

    suspend fun openSymbols(): List<String> = mutex.withLock { positions.keys.toList() }

    /** Pozycję zamknął broker (SL/TP w XTB) – księgujemy wynik po podanej cenie i zwalniamy miejsce na kolejne wejście. */
    suspend fun bookExternalClose(symbol: String, price: Double, reason: String): Boolean = mutex.withLock {
        val p = positions[symbol] ?: return@withLock false
        val pnl = (price - p.entryPrice) * p.quantity * p.side.sign * p.pointValue
        if (p.paper) realizedPaperToday += pnl else realizedToday += pnl
        journal?.record(closedTradeLocked(p, p.quantity, price, pnl, reason))
        if (pnl < 0) lastLossAt = clock()
        positions.remove(symbol)
        pendingStops.remove(symbol)
        recomputePnlLocked()
        persistLocked()
        true
    }

    suspend fun sizeFor(entry: Double, stop: Double, pointValue: Double = 1.0, tier: Int = 1): Double =
        mutex.withLock { positionSize(equity, entry, stop, pointValue, tier) }

    fun tierRiskPct(tier: Int) = riskPct(tier)

    // ── Bramka zleceń ──

    suspend fun evaluate(order: OrderRequest): Decision = mutex.withLock {
        rolloverLocked()
        val now = clock()
        fun reject(reason: String): Decision {
            Log.w(TAG, "REJECT ${order.side} ${order.quantity} ${order.symbol}: $reason")
            return Decision.Rejected(reason)
        }

        if (!order.quantity.isFinite() || order.quantity <= 0.0) return@withLock reject("Nieprawidłowa ilość")
        if (config.allowedSymbols.isNotEmpty() && order.symbol !in config.allowedSymbols) {
            return@withLock reject("Symbol spoza białej listy")
        }

        val existing = positions[order.symbol]
        val reducing = existing != null && existing.side != order.side &&
            order.quantity <= existing.quantity + 1e-12
        if (reducing) return@withLock Decision.Approved(order)

        if (_killSwitch.value) return@withLock reject("KILL-SWITCH aktywny: $killReason")
        if (equity <= 0.0 || dayStartEquity <= 0.0) return@withLock reject("Brak danych o kapitale")
        if (order.side == Side.SELL && existing == null && !config.allowShort) return@withLock reject("Short wyłączony")
        if (existing == null && positions.size >= config.maxOpenPositions) return@withLock reject("Limit otwartych pozycji")
        if (existing != null && !config.allowPyramiding) return@withLock reject("Pozycja już otwarta")
        if (marketBlackout()) return@withLock reject("Okno publikacji makro (blackout)")
        if (config.cooldownAfterLossMs > 0L && now - lastLossAt < cooldownAfterLossMs) {
            return@withLock reject("Przerwa po stracie – jeszcze %d s".format((cooldownAfterLossMs - (now - lastLossAt)) / 1000))
        }
        if (minutesToRollover() < config.noEntryBeforeRolloverMin) {
            return@withLock reject("Za blisko rozliczenia swapu (polityka intraday)")
        }

        while (orderTimes.isNotEmpty() && now - orderTimes.first() > 60_000) orderTimes.removeFirst()
        if (orderTimes.size >= config.maxOrdersPerMinute) return@withLock reject("Limit zleceń na minutę")

        val ref = order.referencePrice
        val stopOnCorrectSide = if (order.side == Side.BUY) order.stopPrice < ref else order.stopPrice > ref
        if (!stopOnCorrectSide) return@withLock reject("Stop-loss po złej stronie ceny")
        val dist = abs(ref - order.stopPrice)
        if (dist < ref * config.minStopDistancePct) return@withLock reject("Stop-loss zbyt blisko ceny")

        val tradeRisk = dist * order.quantity * order.pointValue
        if (order.fixedSize) {
            // Stały wolumen: bez limitu % ryzyka poziomu i dźwigni – zostaje bezpiecznik „SL nie może zjeść > 10% kapitału”.
            val cap = equity * FIXED_SIZE_MAX_RISK_PCT
            if (tradeRisk > cap) return@withLock reject("Stały wolumen: strata przy SL %.2f > %.0f%% kapitału (%.2f)".format(tradeRisk, FIXED_SIZE_MAX_RISK_PCT * 100, cap))
        } else {
            val maxTradeRisk = equity * riskPct(order.tier)
            if (tradeRisk > maxTradeRisk * 1.05) {
                return@withLock reject("Ryzyko %.2f > limit %.2f".format(tradeRisk, maxTradeRisk))
            }
            if (order.quantity * ref * order.pointValue > equity * leverage(order.tier) * 1.01) {
                return@withLock reject("Zbyt duża ekspozycja (dźwignia > %.0f×)".format(leverage(order.tier)))
            }
        }
        val dailyLimit = dayStartEquity * config.maxDailyLossPct
        val currentLoss = (-_dailyPnl.value).coerceAtLeast(0.0)
        if (currentLoss + tradeRisk > dailyLimit) {
            return@withLock reject("Trafienie SL przekroczyłoby dzienny limit straty")
        }

        orderTimes.addLast(now)
        Decision.Approved(order)
    }

    // ── Księgowanie ──

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

    /**
     * Aktualizuje wycenę; jeśli cena przebiła stop pozycji, zwraca zlecenie zamykające (soft-stop).
     * Soft-stop to ZAPASOWY bezpiecznik – podstawowy stop powinien siedzieć u brokera.
     */
    suspend fun onMark(symbol: String, price: Double): OrderRequest? = mutex.withLock {
        marks[symbol] = price
        val p = positions[symbol]?.let { trailLocked(it, price) }?.also { positions[symbol] = it }
        recomputePnlLocked()
        if (p == null) return@withLock null
        pendingStops[symbol]?.let { at -> if (clock() - at < 20_000) return@withLock null }
        val long = p.side == Side.BUY
        val stopHit = if (long) price <= p.stopPrice else price >= p.stopPrice
        val tpHit = p.takeProfit?.let { if (long) price >= it else price <= it } == true
        if (!stopHit && !tpHit) return@withLock null
        pendingStops[symbol] = clock()
        val reason = when {
            tpHit -> "TP"
            p.stopPrice != p.initialStop -> "TRAILING-STOP"
            else -> "SL"
        }
        OrderRequest(symbol, p.side.opposite(), p.quantity, price, stopPrice = price, reason = reason,
            pointValue = p.pointValue, reduceOnly = true)
    }

    /** Po zysku 1R stop idzie na cenę wejścia, dalej podąża w odległości 1R za najlepszą ceną. */
    private fun trailLocked(p: Position, price: Double): Position {
        val r = abs(p.entryPrice - p.initialStop)
        if (r <= 0) return p
        return if (p.side == Side.BUY) {
            val best = max(p.best, price)
            val stop = if (config.autoTrail && best - p.entryPrice >= r) max(p.stopPrice, max(p.entryPrice, best - r)) else p.stopPrice
            p.copy(best = best, stopPrice = stop)
        } else {
            val best = min(p.best, price)
            val stop = if (config.autoTrail && p.entryPrice - best >= r) min(p.stopPrice, min(p.entryPrice, best + r)) else p.stopPrice
            p.copy(best = best, stopPrice = stop)
        }
    }

    suspend fun position(symbol: String): Position? = mutex.withLock { positions[symbol] }

    /** Kapitał z początku dnia (podstawa dziennego limitu straty); 0 = jeszcze nieznany. */
    fun dayStartEquityValue(): Double = dayStartEquity

    /** Dociąga stop pozycji (trailing z zarządzania transakcją) – wyłącznie w stronę zysku. true = przesunięto. */
    suspend fun tightenStop(symbol: String, stop: Double): Boolean = mutex.withLock {
        val p = positions[symbol] ?: return@withLock false
        val better = if (p.side == Side.BUY) stop > p.stopPrice else stop < p.stopPrice
        if (!better) return@withLock false
        positions[symbol] = p.copy(stopPrice = stop)
        persistLocked()
        true
    }

    /** Odsuwa TP w księdze (TP przesuwany za impulsem); null = bez TP. */
    suspend fun moveTakeProfit(symbol: String, tp: Double?) = mutex.withLock {
        val p = positions[symbol] ?: return@withLock
        positions[symbol] = p.copy(takeProfit = tp)
        persistLocked()
    }

    suspend fun closingOrder(symbol: String, side: Side, price: Double): OrderRequest? = mutex.withLock {
        val p = positions[symbol] ?: return@withLock null
        if (p.side == side) null
        else OrderRequest(symbol, side, p.quantity, price, stopPrice = price, reason = "CLOSE",
            pointValue = p.pointValue, reduceOnly = true)
    }

    suspend fun flattenOrders(): List<OrderRequest> = mutex.withLock {
        positions.values.map { p ->
            val px = marks[p.symbol] ?: p.entryPrice
            OrderRequest(p.symbol, p.side.opposite(), p.quantity, px, stopPrice = px, reason = "FLATTEN",
                pointValue = p.pointValue, reduceOnly = true)
        }
    }

    /** Usuwa pozycje papierowe (przy przejściu z DRY_RUN na prawdziwe zlecenia). */
    suspend fun clearPaperPositions() = mutex.withLock {
        positions.values.removeAll { it.paper }
        recomputePnlLocked()
    }

    /** Użytkownik zamknął pozycje ręcznie u brokera – księgujemy wynik po bieżącej cenie i czyścimy. */
    suspend fun markClosedManually(): Int = mutex.withLock {
        val real = positions.values.filter { !it.paper }
        for (p in real) {
            val px = marks[p.symbol] ?: p.entryPrice
            val pnl = (px - p.entryPrice) * p.quantity * p.side.sign * p.pointValue
            realizedToday += pnl
            journal?.record(closedTradeLocked(p, p.quantity, px, pnl, "ZAMKNIĘTE RĘCZNIE"))
            if (pnl < 0) lastLossAt = clock()
            positions.remove(p.symbol)
            pendingStops.remove(p.symbol)
        }
        recomputePnlLocked()
        persistLocked()
        real.size
    }

    // ── Kill-switch ──

    suspend fun engageKillSwitch(reason: String) = mutex.withLock {
        if (!_killSwitch.value) setKillLocked(true, reason)
    }

    /** Ręczne odblokowanie. Blokady z dziennego limitu straty nie da się zdjąć przed nowym dniem. */
    suspend fun resetKillSwitch(): Boolean = mutex.withLock {
        rolloverLocked()
        if (config.maxDailyLossPct.isFinite() && killReason == REASON_DAILY_LOSS) return@withLock false
        setKillLocked(false, null)
        true
    }

    // ── Wewnętrzne (wywoływać pod mutexem) ──

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
        _dailyPnl.value = realizedToday + unreal(false)          // prawdziwe konto → limit dzienny / KILL
        _dailyPnlPaper.value = realizedPaperToday + unreal(true)
        if (dayStartEquity > 0 && !_killSwitch.value &&
            _dailyPnl.value <= -dayStartEquity * config.maxDailyLossPct
        ) {
            setKillLocked(true, REASON_DAILY_LOSS)
        }
    }

    private fun setKillLocked(on: Boolean, reason: String?) {
        _killSwitch.value = on
        killReason = reason
        prefs.edit().putBoolean(K_KILL, on).putString(K_KILL_REASON, reason).apply()
        Log.w(TAG, if (on) "KILL-SWITCH ON: $reason" else "KILL-SWITCH OFF")
    }

    private fun persistLocked() {
        prefs.edit()
            .putString(K_DAY, tradingDay.toString())
            .putString(K_DAY_EQUITY, dayStartEquity.toString())
            .putString(K_REALIZED, realizedToday.toString())
            .putString(K_REALIZED_PAPER, realizedPaperToday.toString())
            .apply()
    }

    companion object {
        const val REASON_DAILY_LOSS = "Przekroczony dzienny limit straty"
        private const val TAG = "RiskManager"
        /** Stały wolumen: strata przy SL nie może przekroczyć 10% kapitału (bezpiecznik). */
        const val FIXED_SIZE_MAX_RISK_PCT = 0.10
        private const val K_KILL = "kill"
        private const val K_KILL_REASON = "kill_reason"
        private const val K_DAY = "day"
        private const val K_DAY_EQUITY = "day_equity"
        private const val K_REALIZED = "realized"
        private const val K_SOURCE = "equity_source"
        private const val K_REALIZED_PAPER = "realized_paper"
    }
}
