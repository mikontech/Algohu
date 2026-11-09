package com.example.algohud.bot

import android.content.SharedPreferences
import com.example.algohud.core.Bar
import com.example.algohud.core.ExecutionMode
import com.example.algohud.core.HudState
import com.example.algohud.core.OrderRequest
import com.example.algohud.core.ScreenQuote
import com.example.algohud.core.Side
import com.example.algohud.core.Tick
import com.example.algohud.data.MarketNewsEngine
import com.example.algohud.execution.AutoExecutionService
import com.example.algohud.execution.ExecutionEngine
import com.example.algohud.risk.RiskManager
import com.example.algohud.risk.TradeJournal
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.math.BigDecimal
import kotlin.math.abs

data class Signal(val side: Side, val stopPrice: Double, val takeProfit: Double, val reason: String)

interface Strategy {
    /** Wywoływane po zamknięciu każdej świecy. */
    fun onBar(bars: List<Bar>, sentiment: Double): Signal?
}

/**
 * Wybicie z kanału Donchiana zgodne z trendem (EMA20 vs EMA50), z filtrem RSI i "wetem" newsów.
 * Stop = 1,5×ATR, TP = 2R. To punkt startowy do POMIARU w dzienniku papierowym, nie gwarancja zysku.
 */
class TrendBreakoutStrategy(
    private val donchian: Int = 20,
    private val fast: Int = 20,
    private val slow: Int = 50,
    private val atrPeriod: Int = 14,
    private val stopAtr: Double = 1.5,
    private val rewardR: Double = 2.0,
    private val newsVeto: Double = 0.3,
    private val cooldownBars: Int = 3,
    private val barMs: Long = 5 * 60_000L
) : Strategy {
    private var lastSignalBar = 0L

    override fun onBar(bars: List<Bar>, sentiment: Double): Signal? {
        if (bars.size < slow + 2) return null
        val last = bars.last()
        if (last.start - lastSignalBar < cooldownBars * barMs) return null
        val closes = bars.map { it.close }
        val emaFast = Indicators.ema(closes, fast) ?: return null
        val emaSlow = Indicators.ema(closes, slow) ?: return null
        val atr = Indicators.atr(bars, atrPeriod)?.takeIf { it > 0 } ?: return null
        val rsi = Indicators.rsi(closes) ?: return null
        val channel = bars.subList(bars.size - 1 - donchian, bars.size - 1)
        val hi = channel.maxOf { it.high }
        val lo = channel.minOf { it.low }
        val side = when {
            last.close > hi && emaFast > emaSlow && rsi < 75 && sentiment > -newsVeto -> Side.BUY
            last.close < lo && emaFast < emaSlow && rsi > 25 && sentiment < newsVeto -> Side.SELL
            else -> return null
        }
        lastSignalBar = last.start
        val dist = stopAtr * atr
        val long = side == Side.BUY
        return Signal(
            side = side,
            stopPrice = if (long) last.close - dist else last.close + dist,
            takeProfit = if (long) last.close + rewardR * dist else last.close - rewardR * dist,
            reason = "wybicie %s z %d św., ATR %.2f, RSI %.0f, news %+.2f"
                .format(if (long) "w górę" else "w dół", donchian, atr, rsi, sentiment)
        )
    }
}

/**
 * Spina dane → świece → strategię → ryzyko → wykonanie.
 * Instrumenty z FEEDS liczone w tle z Binance; pozostałe – tylko gdy są widoczne w XTB.
 */
class BotOrchestrator(
    private val scope: CoroutineScope,
    private val news: MarketNewsEngine,
    private val risk: RiskManager,
    private val engine: ExecutionEngine,
    private val journal: TradeJournal,
    private val prefs: SharedPreferences,
    /** (kapitał, źródło) – np. (631.27, "XTB:PLN"); null = brak danych. */
    private val equityProvider: suspend () -> Pair<Double, String>?,
    private val strategyFactory: () -> Strategy = { TrendBreakoutStrategy() },
    private val manualStopPct: Double = 0.005,
    private val barMs: Long = 5 * 60_000L
) {
    private val _hud = MutableStateFlow(HudState())
    val hud: StateFlow<HudState> = _hud.asStateFlow()

    private val lock = Mutex()
    private val aggregators = HashMap<String, BarAggregator>()
    private val strategies = HashMap<String, Strategy>()
    private val lastPx = HashMap<String, Pair<Double, Long>>()
    private val warned = HashSet<String>()
    @Volatile private var lastTick: Tick? = null
    private var lastFeedHud = 0L
    private var started = false

    fun start() {
        if (started) return
        started = true
        news.start()

        // Rozgrzewka strategii historią świec.
        for ((inst, feed) in FEEDS) {
            scope.launch {
                runCatching { news.history(feed, "5m", 200) }
                    .onSuccess { h -> lock.withLock { agg(inst).seed(h) } }
                    .onFailure { msg("Historia $inst: ${it.message}") }
            }
        }
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
        scope.launch { journal.stats.collect { s -> _hud.update { it.copy(paperStats = s.summary()) } } }
        scope.launch {
            news.news.collect { n ->
                if (abs(n.sentiment) >= 0.5) msg("📰 %+.2f %s".format(n.sentiment, n.title.take(90)))
            }
        }
        scope.launch { engine.events.collect { msg(it) } }
        scope.launch { AutoExecutionService.screenQuote.filterNotNull().collect { onScreen(it) } }
        scope.launch { risk.killSwitch.filter { it }.collect { engine.flattenAll(risk.killReason ?: "KILL") } }
    }

    suspend fun manualOrder(side: Side) {
        val q = AutoExecutionService.screenQuote.value?.takeIf { it.isFresh() && it.instrument != null }
        if (q != null) {
            val inst = q.instrument!!
            val px = refPrice(inst, side) ?: (if (side == Side.BUY) q.ask else q.bid) ?: q.mid
                ?: return msg("Brak ceny dla $inst")
            val stop = if (side == Side.BUY) px * (1 - manualStopPct) else px * (1 + manualStopPct)
            buildOrder(inst, side, px, stop, null, "MANUAL", q.pointValuePerLot ?: pointValue(inst))
                ?.let { engine.submit(it, bypassConfirmation = true) }
            return
        }
        val t = lastTick ?: return msg("Brak notowań – nie mogę wycenić zlecenia")
        val stop = if (side == Side.BUY) t.price * (1 - manualStopPct) else t.price * (1 + manualStopPct)
        buildOrder(t.symbol, side, t.price, stop, null, "MANUAL", 1.0)?.let { engine.submit(it, bypassConfirmation = true) }
    }

    /** Na ekranie pozycji w XTB: wpisz SL/TP z księgi bota (przeliczone na ceny XTB) i zatwierdź. */
    suspend fun applyProtectionOnScreen() {
        val q = AutoExecutionService.screenQuote.value?.takeIf { it.isFresh() && it.instrument != null }
            ?: return msg("Otwórz w XTB ekran pozycji")
        val inst = q.instrument!!
        val pos = risk.position(inst) ?: return msg("Bot nie ma w księdze pozycji $inst")
        val svc = AutoExecutionService.instance ?: return msg("Usługa ułatwień dostępu wyłączona")
        val basis = basis(inst, q)
        val r = svc.setProtection(inst, pos.stopPrice + basis, pos.takeProfit?.plus(basis))
        msg(
            when (r) {
                is com.example.algohud.core.ExecutionResult.Submitted -> "🛡 ${r.note}"
                is com.example.algohud.core.ExecutionResult.Rejected -> "🛡 ${r.reason}"
                is com.example.algohud.core.ExecutionResult.Failed -> "🛡 błąd: ${r.error.message}"
                is com.example.algohud.core.ExecutionResult.Filled -> "🛡 OK"
            }
        )
    }

    /** Zamyka pozycję instrumentu widocznego w XTB (przez ekran pozycji). */
    suspend fun closeOnScreen() {
        val inst = AutoExecutionService.screenQuote.value?.takeIf { it.isFresh() }?.instrument
            ?: return msg("Otwórz w XTB ekran pozycji")
        val pos = risk.position(inst) ?: return msg("Bot nie ma w księdze pozycji $inst")
        val px = lastPx[inst]?.first ?: pos.entryPrice
        risk.closingOrder(inst, pos.side.opposite(), px)?.let {
            engine.submit(it.copy(reason = "ZAMKNIĘCIE RĘCZNE Z HUD"), bypassConfirmation = true)
        }
    }

    /** Różnica XTB − feed w tle (PAXG vs złoto XTB); dla instrumentów bez feedu 0. */
    private fun basis(inst: String, q: ScreenQuote): Double {
        val mid = q.mid ?: return 0.0
        val (px, ts) = lastPx[inst] ?: return 0.0
        if (inst !in FEEDS || System.currentTimeMillis() - ts > 30_000) return 0.0
        return mid - px
    }

    // ───────────── Źródła cen ─────────────

    private suspend fun onFeedTick(tick: Tick) {
        lastTick = tick
        if (tick.symbol == HUD_FEED && tick.timestamp - lastFeedHud >= 1_000) {
            lastFeedHud = tick.timestamp
            _hud.update { it.copy(lastPrice = tick.price, symbol = tick.symbol) }
        }
        val inst = FEED_TO_INSTRUMENT[tick.symbol] ?: return
        lock.withLock { processPrice(inst, tick.price, tick.timestamp) }
    }

    private suspend fun onScreen(q: ScreenQuote) {
        val inst = q.instrument
        val (s, n) = if (inst != null) news.sentimentFor(aliasesFor(inst)) else 0.0 to 0
        _hud.update { it.copy(screen = q, screenSentiment = s, screenNews = n) }
        if (inst == null) return
        q.pointValuePerLot?.let { rememberPointValue(inst, it) }
        if (q.bid != null && q.ask != null && q.ask >= q.bid) rememberSpread(inst, q.ask - q.bid)
        // Instrumenty bez feedu w tle – liczone z ekranu XTB (tylko gdy jest widoczny).
        if (inst !in FEEDS) q.mid?.let { px -> lock.withLock { processPrice(inst, px, q.timestamp) } }
    }

    // ───────────── Rdzeń: wyjścia, świece, sygnały ─────────────

    private suspend fun processPrice(inst: String, px: Double, ts: Long) {
        lastPx[inst] = px to ts
        risk.onMark(inst, px)?.let { exit ->
            msg("🏁 $inst: ${exit.reason} @ %.2f".format(px))
            engine.submit(withSpread(exit, inst), bypassConfirmation = true)
        }
        agg(inst).onTick(px, ts) ?: return
        val bars = agg(inst).closed()
        val (sent, _) = news.sentimentFor(aliasesFor(inst))
        val sig = strategy(inst).onBar(bars, sent) ?: return
        msg("📈 $inst ${sig.side}: ${sig.reason}")
        // Sygnał przeciwny do otwartej pozycji = wyjście (bez natychmiastowego odwracania).
        risk.closingOrder(inst, sig.side, px)?.let { close ->
            engine.submit(withSpread(close.copy(reason = "SYGNAŁ PRZECIWNY"), inst), bypassConfirmation = true)
            return
        }
        val entry = px + spreadOf(inst) / 2 * sig.side.sign
        buildOrder(inst, sig.side, entry, sig.stopPrice, sig.takeProfit, "STRAT ${sig.reason}", pointValue(inst))
            ?.let { engine.submit(it) }
    }

    private suspend fun buildOrder(
        symbol: String, side: Side, price: Double, stop: Double, takeProfit: Double?, reason: String, pointValue: Double?
    ): OrderRequest? {
        risk.closingOrder(symbol, side, price)?.let { return it.copy(reason = "$reason (zamknięcie)") }
        val pv = pointValue ?: run {
            if (warned.add("pv:$symbol")) msg("Otwórz raz bilet $symbol w XTB – bot musi poznać wartość kontraktu")
            return null
        }
        val qty = risk.sizeFor(price, stop, pv)
        if (qty <= 0.0) {
            val step = risk.config.lotStep
            msg(
                "✋ $symbol: najmniejsza pozycja (%s) = ryzyko %.2f, limit %.2f".format(
                    BigDecimal.valueOf(step).toPlainString(), risk.riskOf(step, price, stop, pv), risk.maxTradeRisk()
                )
            )
            return null
        }
        return OrderRequest(symbol, side, qty, price, stop, reason, pointValue = pv, takeProfit = takeProfit)
    }

    /** Symulacja spreadu XTB w handlu papierowym: kupno po ask, sprzedaż po bid. */
    private fun withSpread(o: OrderRequest, inst: String): OrderRequest =
        if (engine.mode.value != ExecutionMode.DRY_RUN) o
        else o.copy(referencePrice = o.referencePrice + spreadOf(inst) / 2 * o.side.sign)

    private fun refPrice(inst: String, side: Side): Double? {
        if (inst !in FEEDS) return null
        val (px, ts) = lastPx[inst] ?: return null
        if (System.currentTimeMillis() - ts > 30_000) return null
        return px + spreadOf(inst) / 2 * side.sign
    }

    private fun agg(inst: String) = aggregators.getOrPut(inst) { BarAggregator(barMs) }
    private fun strategy(inst: String) = strategies.getOrPut(inst) { strategyFactory() }

    private fun pointValue(inst: String): Double? = prefs.getString("pv_$inst", null)?.toDoubleOrNull()
    private fun spreadOf(inst: String): Double = prefs.getString("sp_$inst", null)?.toDoubleOrNull() ?: 0.0

    private fun rememberPointValue(inst: String, v: Double) {
        val old = pointValue(inst)
        if (old == null || abs(old - v) / v > 0.01) prefs.edit().putString("pv_$inst", v.toString()).apply()
    }

    private fun rememberSpread(inst: String, v: Double) {
        val old = spreadOf(inst)
        if (abs(old - v) > old * 0.1) prefs.edit().putString("sp_$inst", v.toString()).apply()
    }

    private fun msg(text: String) = _hud.update { it.copy(lastMessage = text) }

    private fun aliasesFor(instrument: String): List<String> {
        val base = instrument.substringBefore('.')
        val extra = ALIASES.entries.firstOrNull { base.startsWith(it.key) }?.value.orEmpty()
        return extra + base.lowercase()
    }

    private companion object {
        const val HUD_FEED = "BTCUSDT"

        /** Instrument XTB → strumień Binance używany w tle (PAXG = token oparty na złocie, ~cena złota). */
        val FEEDS = mapOf("GOLD" to "PAXGUSDT", "BITCOIN" to "BTCUSDT", "ETHEREUM" to "ETHUSDT")
        val FEED_TO_INSTRUMENT = FEEDS.entries.associate { (k, v) -> v to k }

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
