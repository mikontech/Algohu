package com.example.algohud.bot

import android.content.SharedPreferences
import com.example.algohud.core.Bar
import com.example.algohud.core.ExecutionMode
import com.example.algohud.core.ExecutionResult
import com.example.algohud.core.HudState
import com.example.algohud.core.OrderRequest
import com.example.algohud.core.ScreenQuote
import com.example.algohud.core.Side
import com.example.algohud.core.Tick
import com.example.algohud.data.MarketNewsEngine
import com.example.algohud.data.PythFeed
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
    /** Na co strategia teraz czeka (do HUD). */
    fun describeWait(bars: List<Bar>): String? = null
    /** Dlaczego ostatnio pominęła okazję (np. "RSI 82 ≥ 75") – do HUD; null = brak. */
    fun lastNote(): String? = null
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
    private var note: String? = null

    override fun lastNote(): String? = note

    override fun onBar(bars: List<Bar>, sentiment: Double): Signal? {
        if (bars.size < maxOf(slow, donchian, atrPeriod) + 2) return null // BŁĄD BYŁ TU: Wybicie 55 przy 52 świecach
        val last = bars.last()
        val closes = bars.map { it.close }
        val emaFast = Indicators.ema(closes, fast) ?: return null
        val emaSlow = Indicators.ema(closes, slow) ?: return null
        val atr = Indicators.atr(bars, atrPeriod)?.takeIf { it > 0 } ?: return null
        val rsi = Indicators.rsi(closes) ?: return null
        val channel = bars.subList(bars.size - 1 - donchian, bars.size - 1)
        val hi = channel.maxOf { it.high }
        val lo = channel.minOf { it.low }
        val brokeUp = last.close > hi
        val brokeDown = last.close < lo
        if (!brokeUp && !brokeDown) return null
        val dir = if (brokeUp) "w górę" else "w dół"
        // Wybicie było – sprawdzamy filtry i zapisujemy, dlaczego ewentualnie pomijamy.
        val skip = when {
            last.start - lastSignalBar < cooldownBars * barMs -> "przerwa po poprzednim sygnale"
            brokeUp && emaFast <= emaSlow -> "trend ↓ (EMA$fast < EMA$slow) – nie kupuję przeciw trendowi"
            brokeDown && emaFast >= emaSlow -> "trend ↑ (EMA$fast > EMA$slow) – nie sprzedaję przeciw trendowi"
            brokeUp && rsi >= 75 -> "RSI %.0f ≥ 75 – rynek przegrzany, nie gonię szczytu".format(rsi)
            brokeDown && rsi <= 25 -> "RSI %.0f ≤ 25 – rynek wyprzedany, nie gonię dołka".format(rsi)
            brokeUp && sentiment <= -newsVeto -> "newsy negatywne (%+.2f)".format(sentiment)
            brokeDown && sentiment >= newsVeto -> "newsy pozytywne (%+.2f)".format(sentiment)
            else -> null
        }
        if (skip != null) {
            note = "wybicie $dir na ${tfLabel(barMs)} pominięte: $skip"
            return null
        }
        note = null
        val side = if (brokeUp) Side.BUY else Side.SELL
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

    override fun describeWait(bars: List<Bar>): String? {
        if (bars.size < maxOf(slow, donchian) + 1) return null
        val closes = bars.map { it.close }
        val up = (Indicators.ema(closes, fast) ?: return null) > (Indicators.ema(closes, slow) ?: return null)
        val channel = bars.takeLast(donchian)
        val tf = tfLabel(barMs)
        val rsi = Indicators.rsi(closes)?.let { " • RSI %.0f (limit %s)".format(it, if (up) "< 75" else "> 25") } ?: ""
        return if (up) "LONG, gdy świeca $tf zamknie się > %.2f$rsi • trend ↑, shorty po odwróceniu".format(channel.maxOf { it.high })
        else "SHORT, gdy świeca $tf zamknie się < %.2f$rsi • trend ↓, longi po odwróceniu".format(channel.minOf { it.low })
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
    private val pyth: PythFeed,
    private val barStore: BarStore,
    /** (kapitał, źródło) – np. (631.27, "XTB:PLN"); null = brak danych. */
    private val equityProvider: suspend () -> Pair<Double, String>?,
    private val manualStopPct: Double = 0.005,
    private val barMs: Long = 5 * 60_000L
) {
    private val _hud = MutableStateFlow(HudState())
    val hud: StateFlow<HudState> = _hud.asStateFlow()

    private val lock = Mutex()
    private val aggregators = HashMap<String, BarAggregator>()
    private val learners = HashMap<String, Learner>()
    private val binanceFallback = java.util.Collections.synchronizedSet(HashSet<String>())
    /** Postęp ładowania historii (do HUD); null = gotowe. */
    private val loading = java.util.concurrent.ConcurrentHashMap<String, String>()

    private fun setLoading(inst: String, text: String?) {
        if (text == null) loading.remove(inst) else loading[inst] = text
        updateLearningHud()
    }
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
        for ((inst, feed) in FEEDS) for (tf in Learner.TIMEFRAMES) {
            if (inst in PYTH_FEEDS) continue // np. GOLD: najpierw Pyth XAU/USD, Binance tylko jako zapas
            scope.launch {
                runCatching { news.historyPaged(feed, tfLabel(tf), Learner.historyBars(tf)) } // 5m ≈ 30 dni, 1m ≈ 7 dni
                    .onSuccess { h ->
                        lock.withLock {
                            agg(inst, tf).seed(h)
                            learner(inst).backtest(tf, h, spreadOf(inst))
                        }
                        updateLearningHud()
                    }
                    .onFailure { e ->
                        msg("Historia $inst: ${e.message}")
                        if (inst == hudLearningInstrument) {
                            _hud.update { it.copy(learning = "🧠 $inst: brak historii (${e.message}) – uczę się na żywo") }
                        }
                    }
            }
        }
        // Towary z Pyth (ropa, kawa, kakao…): dynamiczne wyszukanie feedu, historia do nauki, ceny co 2 s.
        pyth.start(PYTH_FEEDS.map { (inst, q) -> PythFeed.Spec(inst, q) }) { r ->
            val inst = r.instrument
            val errors = mutableListOf<String>()
            for (tf in Learner.TIMEFRAMES) {
                setLoading(inst, "pobieram historię ${tfLabel(tf)} z Pyth…")
                runCatching { pyth.history(r, (tf / 60_000).toInt(), Learner.historyBars(tf)) }
                    .onSuccess { h ->
                        setLoading(inst, "uczę się na ${h.size} świecach ${tfLabel(tf)}…")
                        lock.withLock {
                            agg(inst, tf).seed(h)
                            learner(inst).backtest(tf, h, spreadOf(inst))
                        }
                    }
                    .onFailure { errors += "${tfLabel(tf)}: ${it.message}" }
            }
            setLoading(inst, if (errors.isEmpty()) null else "historia niedostępna (${errors.joinToString("; ")}) – uczę się na żywo")
        }
        scope.launch { pyth.ticks.collect { t -> lock.withLock { processPrice(t.symbol, t.price, t.timestamp) } } }
        scope.launch {
            pyth.status.collect { st ->
                // Zapas: jeśli Pyth nie ma instrumentu, który ma też Binance (GOLD → PAXG), wczytaj historię z Binance.
                for ((inst, feed) in FEEDS) {
                    val state = st[inst] ?: continue
                    if (inst !in PYTH_FEEDS || state == "ok" || !binanceFallback.add(inst)) continue
                    msg("$inst: Pyth niedostępny ($state) – zapasowo ceny z Binance $feed")
                    for (tf in Learner.TIMEFRAMES) scope.launch {
                        runCatching { news.historyPaged(feed, tfLabel(tf), Learner.historyBars(tf)) }
                            .onSuccess { h -> lock.withLock { agg(inst, tf).seed(h); learner(inst).backtest(tf, h, spreadOf(inst)) } }
                    }
                }
                updateLearningHud()
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
        if (!hasBackground(inst) || System.currentTimeMillis() - ts > 30_000) return 0.0
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
        if (inst in PYTH_FEEDS && inst !in binanceFallback) return // np. GOLD liczony z Pyth XAU/USD
        lock.withLock { processPrice(inst, tick.price, tick.timestamp) }
    }

    private suspend fun onScreen(q: ScreenQuote) {
        val inst = q.instrument
        val (s, n) = if (inst != null) news.sentimentFor(aliasesFor(inst)) else 0.0 to 0
        _hud.update { it.copy(screen = q, screenSentiment = s, screenNews = n) }
        if (inst != null && inst != hudLearningInstrument) { hudLearningInstrument = inst; updateLearningHud() }
        if (inst == null) return
        q.pointValuePerLot?.let { rememberPointValue(inst, it) }
        if (q.bid != null && q.ask != null && q.ask >= q.bid) rememberSpread(inst, q.ask - q.bid)
        // Instrumenty bez feedu w tle – liczone z ekranu XTB (tylko gdy jest widoczny).
        if (!hasBackground(inst)) q.mid?.let { px -> lock.withLock { processPrice(inst, px, q.timestamp) } }
    }

    // ───────────── Rdzeń: wyjścia, świece, sygnały ─────────────

    private suspend fun processPrice(inst: String, px: Double, ts: Long) {
        lastPx[inst] = px to ts
        risk.onMark(inst, px)?.let { exit ->
            msg("🏁 $inst: ${exit.reason} @ %.2f".format(px))
            engine.submit(withSpread(exit, inst), bypassConfirmation = true)
        }
        for (tf in Learner.TIMEFRAMES) {
            val a = agg(inst, tf)
            val closed = a.onTick(px, ts) ?: continue
            // Instrumenty bez cen w tle: zapisujemy świece, żeby nauka rosła z każdym dniem i przetrwała restart.
            if (!hasBackground(inst)) runCatching { barStore.append(inst, tf, closed) }
            val (sent, _) = news.sentimentFor(aliasesFor(inst))
            val l = learner(inst)
            val sig = l.onBar(tf, a.closed(), sent, spreadOf(inst))
            if (l.championBarMs == tf) l.championNote()?.let { msg("ℹ️ $inst: $it") }
            updateLearningHud()
            if (sig != null) handleSignal(inst, sig, px)
        }
    }

    private suspend fun handleSignal(inst: String, sig: Signal, px: Double) {
        val learner = learner(inst)
        msg("📈 $inst ${sig.side} [${learner.champion?.name}]: ${sig.reason}")
        // Sygnał przeciwny do otwartej pozycji = zamknięcie i odwrócenie (long → short i odwrotnie).
        risk.closingOrder(inst, sig.side, px)?.let { close ->
            val r = engine.submit(withSpread(close.copy(reason = "ODWRÓCENIE"), inst), bypassConfirmation = true)
            if (r !is ExecutionResult.Filled && r !is ExecutionResult.Submitted) return
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
        val minDist = price * risk.config.minStopDistancePct
        if (abs(price - stop) < minDist) {
            msg("✋ $symbol: stop %.2f od ceny – za blisko (min. %.2f)".format(abs(price - stop), minDist))
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
        if (!hasBackground(inst)) return null
        val (px, ts) = lastPx[inst] ?: return null
        if (System.currentTimeMillis() - ts > 30_000) return null
        return px + spreadOf(inst) / 2 * side.sign
    }

    private fun hasBackground(inst: String) = inst in FEEDS || pyth.resolvedFor(inst) != null

    private fun agg(inst: String, tf: Long) = aggregators.getOrPut("$inst@$tf") { BarAggregator(tf) }
    private fun learner(inst: String) = learners.getOrPut(inst) {
        // Nauka używa tego samego minimalnego stopu co RiskManager – uczy się tylko transakcji, które wolno zawrzeć.
        Learner(inst, Learner.defaultVariants(), minStopPct = risk.config.minStopDistancePct).also { l ->
            if (!hasBackground(inst)) {
                // Wczytaj świece zebrane wcześniej z ekranu XTB i naucz się na nich od razu.
                for (tf in Learner.TIMEFRAMES) {
                    val saved = runCatching { barStore.load(inst, tf) }.getOrDefault(emptyList())
                    if (saved.isNotEmpty()) {
                        agg(inst, tf).seed(saved)
                        l.backtest(tf, saved, spreadOf(inst))
                    }
                }
            }
        }
    }

    @Volatile private var hudLearningInstrument: String = "GOLD"

    private fun updateLearningHud() {
        val inst = hudLearningInstrument
        val l = learners[inst]
        val load = loading[inst]
        if (l != null && load != null) {
            _hud.update { it.copy(learning = "🧠 $inst: $load\n" + l.summary().substringAfter('\n', "")) }
            return
        }
        if (l == null) {
            // Nie pokazuj wyników innego instrumentu (wcześniej zostawała linijka GOLD).
            _hud.update {
                it.copy(
                    learning = "🧠 $inst: " + when {
                        load != null -> load
                        inst in PYTH_FEEDS && pyth.status.value[inst] == null -> "szukam źródła cen w Pyth…"
                        hasBackground(inst) -> "wczytuję historię…"
                        inst in PYTH_FEEDS -> "${pyth.status.value[inst] ?: "szukam źródła cen…"}; uczę się tylko, gdy $inst jest otwarty w XTB"
                        else -> "brak cen w tle; uczę się tylko, gdy $inst jest otwarty w XTB (potrzeba ~50 świec)"
                    }
                )
            }
            return
        }
        // Poziomy liczone z feedu w tle przeliczamy na ceny XTB (np. PAXG → złoto XTB), żeby zgadzały się z wykresem.
        val q = AutoExecutionService.screenQuote.value?.takeIf { it.isFresh() && it.instrument == inst }
        val shift = q?.let { basis(inst, it) } ?: 0.0
        val wait = l.describeWait { tf ->
            aggregators["$inst@$tf"]?.closed()?.map { b ->
                b.copy(open = b.open + shift, high = b.high + shift, low = b.low + shift, close = b.close + shift)
            }
        }
        _hud.update { it.copy(learning = l.summary() + (wait?.let { "\n⏳ $it" } ?: "")) }
    }

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

        /** Instrument XTB → fraza symbolu w Pyth (darmowy Hermes). OIL w XTB = Brent, OIL.WTI = WTI. */
        val PYTH_FEEDS = mapOf(
            "GOLD" to "XAU/USD", // spot złota – zbliżony do GOLD w XTB (PAXG z Binance tylko jako zapas)
            "OIL" to "UKOILSPOT",
            "OIL.WTI" to "USOILSPOT",
            "SILVER" to "XAG/USD",
            "COFFEE" to "COFFEE",
            "COCOA" to "COCOA"
        )

        /** Słowa w nagłówkach newsów powiązane z instrumentem (EN + PL). */
        val ALIASES = mapOf(
            "BITCOIN" to listOf("bitcoin", "btc", "crypto", "kryptowalut"),
            "ETHEREUM" to listOf("ethereum", "ether", "eth ", "crypto"),
            "GOLD" to listOf("gold", "złoto", "zloto", "bullion"),
            "SILVER" to listOf("silver", "srebro"),
            "OIL" to listOf("oil", "crude", "brent", "wti", "opec", "ropa"),
            "COFFEE" to listOf("coffee", "arabica", "robusta", "kawa", "kawy"),
            "COCOA" to listOf("cocoa", "kakao", "chocolate"),
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
