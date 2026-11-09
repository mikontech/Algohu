package com.example.algohud.bot

import com.example.algohud.core.HudState
import com.example.algohud.core.OrderRequest
import com.example.algohud.core.Side
import com.example.algohud.core.Tick
import com.example.algohud.data.MarketNewsEngine
import com.example.algohud.execution.ExecutionEngine
import com.example.algohud.risk.RiskManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.abs

data class Signal(val side: Side, val stopPrice: Double, val reason: String)

interface Strategy {
    fun onTick(tick: Tick, sentiment: Double): Signal?
}

/**
 * DEMO, nie strategia z przewagą: wejście, gdy sentyment i krótkoterminowy trend są zgodne.
 * Przed jakimkolwiek użyciem z prawdziwymi pieniędzmi: backtest + forward test na paper.
 */
class SentimentMomentumStrategy(
    private val window: Int = 120,          // 120 próbek × 1 s = SMA z 2 minut
    private val threshold: Double = 0.35,
    private val stopPct: Double = 0.01,
    private val cooldownMs: Long = 15 * 60_000L
) : Strategy {
    private val prices = ArrayDeque<Double>()
    private var lastSignalAt = 0L

    override fun onTick(tick: Tick, sentiment: Double): Signal? {
        prices.addLast(tick.price)
        if (prices.size > window) prices.removeFirst()
        if (prices.size < window || tick.timestamp - lastSignalAt < cooldownMs) return null
        val sma = prices.average()
        val side = when {
            sentiment > threshold && tick.price > sma -> Side.BUY
            sentiment < -threshold && tick.price < sma -> Side.SELL
            else -> return null
        }
        lastSignalAt = tick.timestamp
        val stop = if (side == Side.BUY) tick.price * (1 - stopPct) else tick.price * (1 + stopPct)
        return Signal(side, stop, "sent=%+.2f, cena %s SMA".format(sentiment, if (side == Side.BUY) ">" else "<"))
    }
}

/** Spina dane → strategię → ryzyko → wykonanie oraz wystawia stan dla HUD-a. */
class BotOrchestrator(
    private val scope: CoroutineScope,
    private val news: MarketNewsEngine,
    private val risk: RiskManager,
    private val engine: ExecutionEngine,
    private val equityProvider: suspend () -> Double,
    private val strategy: Strategy = SentimentMomentumStrategy(),
    private val manualStopPct: Double = 0.01
) {
    private val _hud = MutableStateFlow(HudState())
    val hud: StateFlow<HudState> = _hud.asStateFlow()

    @Volatile private var lastTick: Tick? = null
    private var lastEval = 0L
    private var started = false

    fun start() {
        if (started) return
        started = true
        news.start()

        scope.launch {
            while (isActive) {
                runCatching { risk.updateEquity(equityProvider()) }
                    .onFailure { msg("Nie pobrano kapitału: ${it.message}") }
                delay(30_000)
            }
        }
        scope.launch { news.ticks.collect { onTick(it) } }
        scope.launch {
            combine(news.sentiment, news.connected, risk.killSwitch, risk.dailyPnl, engine.mode) { s, c, k, pnl, m ->
                _hud.update { it.copy(sentiment = s, feedConnected = c, killSwitch = k, dailyPnl = pnl, mode = m) }
            }.collect()
        }
        scope.launch {
            news.news.collect { n ->
                if (abs(n.sentiment) >= 0.5) msg("📰 %+.2f %s".format(n.sentiment, n.title.take(90)))
            }
        }
        scope.launch { engine.events.collect { msg(it) } }
        // Kill-switch (ręczny, dzienny limit, błąd wykonania) → zamknij wszystko.
        scope.launch { risk.killSwitch.filter { it }.collect { engine.flattenAll(risk.killReason ?: "KILL") } }
    }

    suspend fun manualOrder(side: Side) {
        val t = lastTick ?: return msg("Brak notowań – nie mogę wycenić zlecenia")
        val stop = if (side == Side.BUY) t.price * (1 - manualStopPct) else t.price * (1 + manualStopPct)
        buildOrder(t, side, stop, "MANUAL")?.let { engine.submit(it, bypassConfirmation = true) }
    }

    private suspend fun onTick(tick: Tick) {
        lastTick = tick
        // Soft-stop sprawdzany na każdym ticku.
        risk.onMark(tick.symbol, tick.price)?.let { engine.submit(it, bypassConfirmation = true) }

        if (tick.timestamp - lastEval < 1_000) return
        lastEval = tick.timestamp
        _hud.update { it.copy(lastPrice = tick.price, symbol = tick.symbol) }

        val signal = strategy.onTick(tick, news.sentiment.value) ?: return
        msg("📈 Sygnał ${signal.side}: ${signal.reason}")
        buildOrder(tick, signal.side, signal.stopPrice, "STRAT ${signal.reason}")?.let { engine.submit(it) }
    }

    private suspend fun buildOrder(tick: Tick, side: Side, stop: Double, reason: String): OrderRequest? {
        risk.closingOrder(tick.symbol, side, tick.price)?.let { return it.copy(reason = "$reason (zamknięcie)") }
        val qty = risk.sizeFor(tick.price, stop)
        if (qty <= 0.0) {
            msg("Wielkość pozycji = 0 (brak kapitału lub stop zbyt blisko)")
            return null
        }
        return OrderRequest(tick.symbol, side, qty, tick.price, stop, reason)
    }

    private fun msg(text: String) = _hud.update { it.copy(lastMessage = text) }
}
