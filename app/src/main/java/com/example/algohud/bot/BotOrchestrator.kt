package com.example.algohud.bot

import com.example.algohud.core.HudState
import com.example.algohud.core.OrderRequest
import com.example.algohud.core.ScreenQuote
import com.example.algohud.core.Side
import com.example.algohud.core.Tick
import com.example.algohud.data.MarketNewsEngine
import com.example.algohud.execution.AutoExecutionService
import com.example.algohud.execution.ExecutionEngine
import com.example.algohud.risk.RiskManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.math.BigDecimal
import kotlin.math.abs

data class Signal(val side: Side, val stopPrice: Double, val reason: String)

interface Strategy {
    fun onTick(tick: Tick, sentiment: Double): Signal?
}

/**
 * DEMO, nie strategia z przewagą: wejście, gdy sentyment newsów o instrumencie i krótkoterminowy trend są zgodne.
 * Przed użyciem z prawdziwymi pieniędzmi bez potwierdzania: backtest + forward test.
 */
class SentimentMomentumStrategy(
    private val window: Int = 120,          // 120 próbek × 1 s = SMA z 2 minut
    private val threshold: Double = 0.35,
    private val stopPct: Double = 0.005,
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

/**
 * Spina dane → strategię → ryzyko → wykonanie i wystawia stan dla HUD-a.
 * Strategia działa na instrumencie WIDOCZNYM w XTB (tylko tam bot może złożyć zlecenie).
 */
class BotOrchestrator(
    private val scope: CoroutineScope,
    private val news: MarketNewsEngine,
    private val risk: RiskManager,
    private val engine: ExecutionEngine,
    /** (kapitał, źródło) – np. (631.27, "XTB:PLN"); null = brak danych. */
    private val equityProvider: suspend () -> Pair<Double, String>?,
    private val strategyFactory: () -> Strategy = { SentimentMomentumStrategy() },
    private val manualStopPct: Double = 0.005
) {
    private val _hud = MutableStateFlow(HudState())
    val hud: StateFlow<HudState> = _hud.asStateFlow()

    @Volatile private var lastTick: Tick? = null
    private var lastFeedHud = 0L
    private var lastScreenEval = 0L
    private var strategy: Strategy = strategyFactory()
    private var strategySymbol: String? = null
    private var started = false

    fun start() {
        if (started) return
        started = true
        news.start()

        scope.launch {
            while (isActive) {
                runCatching { equityProvider()?.let { (eq, src) -> risk.updateEquity(eq, src) } }
                    .onFailure { msg("Nie pobrano kapitału: ${it.message}") }
                delay(5_000)
            }
        }
        scope.launch { news.ticks.collect { onFeedTick(it) } }
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
        scope.launch { AutoExecutionService.screenQuote.filterNotNull().collect { onScreen(it) } }
        // Kill-switch (ręczny, dzienny limit, błąd wykonania) → zamknij, co się da.
        scope.launch { risk.killSwitch.filter { it }.collect { engine.flattenAll(risk.killReason ?: "KILL") } }
    }

    suspend fun manualOrder(side: Side) {
        val q = AutoExecutionService.screenQuote.value?.takeIf { it.isFresh() && it.instrument != null }
        if (q != null) {
            val px = (if (side == Side.BUY) q.ask else q.bid) ?: q.mid ?: return msg("Brak ceny na ekranie XTB")
            val stop = if (side == Side.BUY) px * (1 - manualStopPct) else px * (1 + manualStopPct)
            buildOrder(q.instrument!!, side, px, stop, "MANUAL", q.pointValuePerLot)
                ?.let { engine.submit(it, bypassConfirmation = true) }
            return
        }
        val t = lastTick ?: return msg("Brak notowań – nie mogę wycenić zlecenia")
        val stop = if (side == Side.BUY) t.price * (1 - manualStopPct) else t.price * (1 + manualStopPct)
        buildOrder(t.symbol, side, t.price, stop, "MANUAL", 1.0)?.let { engine.submit(it, bypassConfirmation = true) }
    }

    private suspend fun onFeedTick(tick: Tick) {
        lastTick = tick
        risk.onMark(tick.symbol, tick.price)?.let { engine.submit(it, bypassConfirmation = true) }
        if (tick.timestamp - lastFeedHud < 1_000) return
        lastFeedHud = tick.timestamp
        _hud.update { it.copy(lastPrice = tick.price, symbol = tick.symbol) }
    }

    private suspend fun onScreen(q: ScreenQuote) {
        val inst = q.instrument
        val (s, n) = if (inst != null) news.sentimentFor(aliasesFor(inst)) else 0.0 to 0
        _hud.update { it.copy(screen = q, screenSentiment = s, screenNews = n) }
        if (inst == null) return
        val px = q.mid ?: return
        // Soft-stop na instrumencie z ekranu.
        risk.onMark(inst, px)?.let { engine.submit(it, bypassConfirmation = true) }

        if (q.timestamp - lastScreenEval < 1_000) return
        lastScreenEval = q.timestamp
        if (inst != strategySymbol) { strategy = strategyFactory(); strategySymbol = inst }
        val signal = strategy.onTick(Tick(inst, px, q.timestamp), s) ?: return
        msg("📈 Sygnał ${signal.side} $inst: ${signal.reason}")
        val entry = (if (signal.side == Side.BUY) q.ask else q.bid) ?: px
        buildOrder(inst, signal.side, entry, signal.stopPrice, "STRAT ${signal.reason}", q.pointValuePerLot)
            ?.let { engine.submit(it) }
    }

    private suspend fun buildOrder(
        symbol: String, side: Side, price: Double, stop: Double, reason: String, pointValue: Double?
    ): OrderRequest? {
        risk.closingOrder(symbol, side, price)?.let { return it.copy(reason = "$reason (zamknięcie)") }
        val pv = pointValue ?: run {
            msg("Brak 'Wartości kontraktu' na ekranie XTB – przewiń bilet zlecenia, żeby była widoczna")
            return null
        }
        val qty = risk.sizeFor(price, stop, pv)
        if (qty <= 0.0) {
            val step = risk.config.lotStep
            msg(
                "✋ Najmniejsza pozycja (%s) = ryzyko %.2f, a limit to %.2f – pozycja za duża dla konta".format(
                    BigDecimal.valueOf(step).toPlainString(), risk.riskOf(step, price, stop, pv), risk.maxTradeRisk()
                )
            )
            return null
        }
        return OrderRequest(symbol, side, qty, price, stop, reason, pointValue = pv)
    }

    private fun msg(text: String) = _hud.update { it.copy(lastMessage = text) }

    private fun aliasesFor(instrument: String): List<String> {
        val base = instrument.substringBefore('.')
        val extra = ALIASES.entries.firstOrNull { base.startsWith(it.key) }?.value.orEmpty()
        return extra + base.lowercase()
    }

    private companion object {
        /** Słowa w nagłówkach newsów powiązane z instrumentem (EN + PL). */
        val ALIASES = mapOf(
            "BITCOIN" to listOf("bitcoin", "btc", "crypto", "kryptowalut"),
            "ETHEREUM" to listOf("ethereum", "ether", "eth ", "crypto"),
            "GOLD" to listOf("gold", "złoto", "zloto", "bullion"),
            "SILVER" to listOf("silver", "srebro"),
            "OIL" to listOf("oil", "crude", "brent", "wti", "opec", "ropa"),
            "NATGAS" to listOf("natural gas", "lng", "gaz"),
            "US500" to listOf("s&p", "wall street", "stocks", "equities"),
            "US100" to listOf("nasdaq", "big tech"),
            "US30" to listOf("dow"),
            "DE40" to listOf("dax", "germany", "german", "niemcy"),
            "W20" to listOf("wig20", "gpw", "warsaw"),
            "EURUSD" to listOf("euro", "ecb", "fed", "dollar"),
            "GBPUSD" to listOf("pound", "sterling", "boe", "fed", "dollar"),
            "USDJPY" to listOf("yen", "boj", "fed", "dollar"),
            "EURPLN" to listOf("złot", "zlot", "nbp", "rpp", "ecb"),
            "USDPLN" to listOf("złot", "zlot", "nbp", "rpp", "fed", "dollar")
        )
    }
}
