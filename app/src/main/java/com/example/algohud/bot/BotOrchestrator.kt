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
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.math.BigDecimal
import kotlin.math.abs

/** Profil działania bota. ZAKRES = kup przy dole zakresu po odbiciu, sprzedaj przy górze (i lustrzanie). */
enum class BotProfile(val label: String) {
    RANGE("📈 ZAKRES (dół → góra)"),
    AGGRESSIVE("⚡ AGRESYWNY (RT+ORB)"),
    STANDARD("STANDARD (turniej)")
}

data class Signal(val side: Side, val stopPrice: Double, val takeProfit: Double, val reason: String, val tier: Int = 1)

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
        val sh = Candles.shape(last)
        val strong = sh.range >= 1.3 * atr && sh.body >= 0.7 * sh.range
        return Signal(
            side = side,
            stopPrice = if (long) last.close - dist else last.close + dist,
            takeProfit = if (long) last.close + rewardR * dist else last.close - rewardR * dist,
            reason = "wybicie %s z %d św.%s, RSI %.0f, news %+.2f"
                .format(if (long) "w górę" else "w dół", donchian, if (strong) ", silny impet" else "", rsi, sentiment),
            tier = if (strong) Tier.AGGRESSIVE else Tier.MODERATE
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
    private val aggregators = java.util.concurrent.ConcurrentHashMap<String, BarAggregator>()
    private val learners = java.util.concurrent.ConcurrentHashMap<String, Learner>()

    // ───────────── Profil agresywny: impuls w czasie rzeczywistym (RT) + Open Range Breakout (ORB) ─────────────
    private val profile = MutableStateFlow(
        runCatching { BotProfile.valueOf(prefs.getString("profile", BotProfile.RANGE.name)!!) }.getOrDefault(BotProfile.RANGE)
    )
    private val rtLastBar = HashMap<String, Long>()
    private val rtNotes = object : LinkedHashSet<String>() {
        override fun add(element: String): Boolean {
            if (size > 200) remove(first())
            return super.add(element)
        }
    }
    private data class Orb(val eventMs: Long, var hi: Double = Double.MIN_VALUE, var lo: Double = Double.MAX_VALUE, var fired: Boolean = false)
    private val orbs = HashMap<String, Orb>()

    private fun applyProfile() {
        risk.cooldownAfterLossMs = if (profile.value == BotProfile.STANDARD) risk.config.cooldownAfterLossMs else AGGRESSIVE_COOLDOWN_MS
    }

    fun setProfile(p: BotProfile) {
        profile.value = p
        prefs.edit().putString("profile", p.name).apply()
        applyProfile()
        _hud.update { it.copy(profile = profileLabel()) }
        msg("Profil: ${p.label}")
    }

    private fun profileLabel() = profile.value.label

    // ───────────── ZAKRES: wejście przy dole zakresu po odbiciu, wyjście przy górze ─────────────
    private class RangeState(var lowSeen: Double = Double.MAX_VALUE, var highSeen: Double = -Double.MAX_VALUE, var lastEntryBar: Long = 0L)
    private val rangeStates = HashMap<String, RangeState>()
    private var lastBlindNote = 0L
    private val rangeInfo = java.util.concurrent.ConcurrentHashMap<String, String>() // instrument → opis do HUD

    /**
     * on_tick: zakres = High/Low ostatnich [RANGE_LOOKBACK] świec 1m.
     *  LONG: cena zeszła do dolnych 20% zakresu, potem odbiła o ≥ 0,3×ATR od najniższego punktu (potwierdzenie dołka)
     *        → wejście; SL pod dołkiem (−0,3×ATR); TP przy górze zakresu (górne 20%).
     *  SHORT: lustrzanie przy górze. Silny trend (|EMA20−EMA50| > 1×ATR) = brak gry w zakresie (zakres się "rozjeżdża").
     */
    private suspend fun rangeCheck(inst: String, px: Double, ts: Long) {
        val a = aggregators["$inst@${Learner.M1}"] ?: return
        val bars = a.closed()
        if (bars.size < 60) return
        val atr = Indicators.atr(bars)?.takeIf { it > 0 } ?: return
        val look = bars.takeLast(RANGE_LOOKBACK)
        val hi = look.maxOf { it.high }
        val lo = look.minOf { it.low }
        val width = hi - lo
        val spread = spreadOf(inst)
        val closes = bars.map { it.close }
        val e20 = Indicators.ema(closes, 20) ?: return
        val e50 = Indicators.ema(closes, 50) ?: return
        val st = rangeStates.getOrPut(inst) { RangeState() }
        val bottom = lo + RANGE_ZONE * width
        val top = hi - RANGE_ZONE * width
        val q = AutoExecutionService.screenQuote.value?.takeIf { it.isFresh() && it.instrument == inst }
        val sh = q?.let { basis(inst, it) } ?: 0.0
        val trendStrong = abs(e20 - e50) > RANGE_MAX_TREND_ATR * atr
        val tooNarrow = width < maxOf(RANGE_MIN_WIDTH_ATR * atr, 8 * spread)
        rangeInfo[inst] = when {
            trendStrong -> "ZAKRES: silny trend ${if (e20 > e50) "↑" else "↓"} – czekam, aż rynek się uspokoi"
            tooNarrow -> "ZAKRES: zakres za wąski (%.2f) – zysk nie pokryłby spreadu".format(width)
            else -> "ZAKRES %.2f–%.2f: KUPIĘ po odbiciu < %.2f • SPRZEDAM przy > %.2f".format(lo + sh, hi + sh, bottom + sh, top + sh)
        }
        if (trendStrong || tooNarrow) { st.lowSeen = Double.MAX_VALUE; st.highSeen = -Double.MAX_VALUE; return }
        if (risk.position(inst) != null) return // jedna pozycja; wyjście: TP przy przeciwnej krawędzi, SL, BE/trailing
        val curBar = a.current()?.start ?: 0L
        if (st.lastEntryBar == curBar) return

        // Śledzenie dołka/szczytu w strefach
        if (px <= bottom) st.lowSeen = minOf(st.lowSeen, px)
        if (px >= top) st.highSeen = maxOf(st.highSeen, px)
        if (px > lo + 0.5 * width) st.lowSeen = Double.MAX_VALUE   // wyszło z dołu bez sygnału – reset
        if (px < hi - 0.5 * width) st.highSeen = -Double.MAX_VALUE

        val bounceUp = st.lowSeen != Double.MAX_VALUE && px - st.lowSeen >= RANGE_CONFIRM_ATR * atr && px <= lo + 0.4 * width
        val bounceDown = st.highSeen != -Double.MAX_VALUE && st.highSeen - px >= RANGE_CONFIRM_ATR * atr && px >= hi - 0.4 * width
        if (!bounceUp && !bounceDown) return
        val dir = if (bounceUp) 1 else -1
        val stop = if (dir > 0) minOf(st.lowSeen, lo) - 0.3 * atr else maxOf(st.highSeen, hi) + 0.3 * atr
        val target = if (dir > 0) top else bottom
        val entry = px + spread / 2 * dir
        if ((target - entry) * dir < RANGE_MIN_REWARD_R * abs(entry - stop)) {
            msg("📈 $inst: odbicie od ${if (dir > 0) "dołu" else "góry"}, ale do celu za blisko względem stopu – pomijam")
            st.lowSeen = Double.MAX_VALUE; st.highSeen = -Double.MAX_VALUE
            return
        }
        st.lastEntryBar = curBar
        st.lowSeen = Double.MAX_VALUE; st.highSeen = -Double.MAX_VALUE
        val why = if (dir > 0) "ZAKRES odbicie od dołka %.2f → cel góra %.2f".format(lo + sh, target + sh)
        else "ZAKRES odbicie od szczytu %.2f → cel dół %.2f".format(hi + sh, target + sh)
        enterAggressive(inst, dir, px, stop, why, ts, tp = target, tier = Tier.MODERATE)
    }

    /** Reguły sesji: null = wolno otwierać; inaczej powód blokady. */
    private fun sessionBlock(inst: String, ts: Long): String? {
        val st = MarketHours.status(inst, ts)
        return when {
            !st.open -> "rynek $inst zamknięty"
            st.minutesToClose != null && st.minutesToClose < SESSION_NO_ENTRY_MIN ->
                "$SESSION_NO_ENTRY_MIN min przed zamknięciem sesji (zostało ${st.minutesToClose} min)"
            else -> null
        }
    }

    /**
     * on_candle_update dla świecy 1m: wejście W TRAKCIE świecy, gdy
     *  zasięg ≥ 1,2×ATR14, korpus ≥ 70% zasięgu, knot po stronie ruchu ≤ 15% zasięgu
     *  i cena wybija High/Low ostatnich [RT_LOOKBACK] zamkniętych świec. Jedno wejście na świecę.
     */
    private suspend fun realtimeCheck(inst: String, px: Double, ts: Long) {
        val a = aggregators["$inst@${Learner.M1}"] ?: return
        val cur = a.current() ?: return
        if (rtLastBar[inst] == cur.start) return
        val closed = a.closed()
        if (closed.size < RT_LOOKBACK + 15) return
        val atr = Indicators.atr(closed)?.takeIf { it > 0 } ?: return
        val range = cur.high - cur.low
        if (range < RT_RANGE_ATR * atr) return // to jeszcze nie jest kandydat na impuls
        val body = abs(cur.close - cur.open)
        val dir = if (cur.close > cur.open) 1 else -1
        // Long: cena przy szczycie (mały górny knot); short: przy dołku (mały dolny knot).
        val wickAgainst = if (dir > 0) cur.high - cur.close else cur.close - cur.low
        val look = closed.takeLast(RT_LOOKBACK)
        val level = if (dir > 0) look.maxOf { it.high } else look.minOf { it.low }
        val breaks = if (dir > 0) cur.close > level else cur.close < level
        val head = "⚡ RT $inst: świeca %.1f×ATR %s".format(range / atr, if (dir > 0) "↑" else "↓")
        val why = when {
            body < RT_BODY_RATIO * range -> "korpus %.0f%% < %.0f%% (warunek może się jeszcze spełnić)".format(body / range * 100, RT_BODY_RATIO * 100)
            wickAgainst > RT_MAX_WICK * range -> "cena cofnęła się od ekstremum (knot %.0f%%)".format(wickAgainst / range * 100)
            !breaks -> "nie wybija %s z %d świec (%.2f)".format(if (dir > 0) "szczytu" else "dołka", RT_LOOKBACK, level)
            risk.cooldownLeftMs() > 0 -> "przerwa po stracie – jeszcze %d s".format(risk.cooldownLeftMs() / 1000)
            else -> null
        }
        if (why != null) {
            // Raz na świecę i powód (żeby HUD nie migał przy każdym ticku).
            val key = "$inst@${cur.start}:${why.substringBefore(' ')}"
            if (rtNotes.add(key) && inst == AutoExecutionService.screenQuote.value?.instrument) msg("$head – pominięta: $why")
            return
        }
        rtLastBar[inst] = cur.start
        if (inst == AutoExecutionService.screenQuote.value?.instrument) msg("$head – WCHODZĘ")
        // Stop: tuż za bazą świecy impulsu albo 0,8×ATR – ciaśniejszy z dwóch, ale nie bliżej niż 3× spread / min. dystans.
        val base = if (dir > 0) cur.low - 0.1 * atr else cur.high + 0.1 * atr
        val atrStop = px - dir * 0.8 * atr
        val stop = if (dir > 0) maxOf(base, atrStop) else minOf(base, atrStop)
        enterAggressive(inst, dir, px, stop, "RT impuls %.1f×ATR, korpus %.0f%%".format(range / atr, body / range * 100), ts)
    }

    /** Open Breakout: zasięg pierwszych [ORB_RANGE_MIN] min po otwarciu, wybicie w kolejnych minutach (do [ORB_ACTIVE_MIN]). */
    private suspend fun orbCheck(inst: String, px: Double, ts: Long) {
        val ev = MarketHours.status(inst, ts).lastOpenEventMs ?: return
        val minutes = (ts - ev) / 60_000
        if (minutes < 0 || minutes >= ORB_ACTIVE_MIN) return
        val o = orbs[inst]?.takeIf { it.eventMs == ev } ?: Orb(ev).also { orbs[inst] = it }
        if (minutes < ORB_RANGE_MIN) { o.hi = maxOf(o.hi, px); o.lo = minOf(o.lo, px); return }
        if (o.fired || o.hi <= o.lo) return
        val dir = when { px > o.hi -> 1; px < o.lo -> -1; else -> return }
        o.fired = true
        val atr = aggregators["$inst@${Learner.M1}"]?.closed()?.let { Indicators.atr(it) } ?: (o.hi - o.lo)
        // Stop po drugiej stronie zasięgu otwarcia, ale nie dalej niż 1,5×ATR.
        val opposite = if (dir > 0) o.lo else o.hi
        val capped = px - dir * 1.5 * atr
        val stop = if (dir > 0) maxOf(opposite, capped) else minOf(opposite, capped)
        rtLastBar[inst] = aggregators["$inst@${Learner.M1}"]?.current()?.start ?: 0L // priorytet ORB nad RT w tej minucie
        enterAggressive(inst, dir, px, stop, "ORB wybicie zasięgu otwarcia %.2f–%.2f".format(o.lo, o.hi), ts)
    }

    private suspend fun enterAggressive(
        inst: String, dir: Int, px: Double, rawStop: Double, why: String, ts: Long,
        tp: Double? = null, tier: Int = Tier.AGGRESSIVE
    ) {
        // Bot może złożyć zlecenie tylko na instrumencie otwartym w XTB – pozostałe liczy w tle bez prób wejścia.
        val onScreen = AutoExecutionService.screenQuote.value?.takeIf { it.isFresh() }?.instrument
        if (onScreen != inst && engine.mode.value != ExecutionMode.DRY_RUN) {
            // Nie po cichu: dla instrumentu głównego powiedz, dlaczego sygnał przepadł (maks. raz na minutę).
            if (inst == hudLearningInstrument && ts - lastBlindNote > 60_000) {
                lastBlindNote = ts
                msg("✋ $inst: sygnał ($why), ale nie widzę $inst w XTB – ${AutoExecutionService.diag}")
            }
            return
        }
        if (onScreen != inst && inst != hudLearningInstrument) return // papier: tylko instrument główny i ten na ekranie
        sessionBlock(inst, ts)?.let { msg("✋ $inst: $why – pominięte: $it"); return }
        val spread = spreadOf(inst)
        val entry = px + spread / 2 * dir
        val minDist = maxOf(3 * spread, entry * risk.config.minStopDistancePct)
        val stop = if (abs(entry - rawStop) < minDist) entry - dir * minDist else rawStop
        val takeProfit = tp ?: (entry + dir * 2.0 * abs(entry - stop))
        val side = if (dir > 0) Side.BUY else Side.SELL
        risk.closingOrder(inst, side, px)?.let { close ->
            val r = engine.submit(withSpread(close.copy(reason = "ODWRÓCENIE (impuls)"), inst), bypassConfirmation = true)
            if (r !is ExecutionResult.Filled && r !is ExecutionResult.Submitted) return
        }
        val order = buildOrder(inst, side, entry, stop, takeProfit, why, pointValue(inst), tier) ?: return
        decisionCard(inst, order, Signal(side, stop, takeProfit, why, tier), ev = Double.NaN)
        engine.submit(order)
    }
    private val binanceFallback = java.util.Collections.synchronizedSet(HashSet<String>())
    private val noHistory = java.util.Collections.synchronizedSet(HashSet<String>())

    /** Historia z Binance (np. PAXG) przesunięta do poziomu ceny z Pyth (spot) – kształt ruchów prawie identyczny. */
    private suspend fun loadShiftedBinanceHistory(inst: String) {
        val feed = FEEDS[inst] ?: return
        setLoading(inst, "czekam na cenę z Pyth do wyrównania historii…")
        val live = withTimeoutOrNull(30_000) {
            var p: Double? = null
            while (p == null) { p = lastPx[inst]?.first; if (p == null) delay(500) }
            p
        }
        for (tf in Learner.TIMEFRAMES) {
            setLoading(inst, "historia zastępcza ${tfLabel(tf)} (Binance $feed)…")
            runCatching { news.historyPaged(feed, tfLabel(tf), Learner.historyBars(tf)) }
                .onSuccess { h ->
                    val shift = if (live != null && h.isNotEmpty()) live - h.last().close else 0.0
                    val adj = h.map { b -> b.copy(open = b.open + shift, high = b.high + shift, low = b.low + shift, close = b.close + shift) }
                    setLoading(inst, "uczę się na ${adj.size} świecach ${tfLabel(tf)}…")
                    lock.withLock {
                        agg(inst, tf).seed(adj)
                        learner(inst).backtest(tf, adj, spreadOf(inst))
                    }
                }
                .onFailure { msg("Historia zastępcza $inst (${tfLabel(tf)}): ${it.message}") }
        }
        setLoading(inst, null)
    }

    /** Świece zebrane wcześniej na żywo i zapisane w telefonie. */
    private suspend fun loadStoredBars(inst: String) {
        var total = 0
        for (tf in Learner.TIMEFRAMES) {
            val saved = runCatching { barStore.load(inst, tf) }.getOrDefault(emptyList())
            if (saved.isEmpty()) continue
            total += saved.size
            lock.withLock {
                agg(inst, tf).seed(saved)
                learner(inst).backtest(tf, saved, spreadOf(inst))
            }
        }
        setLoading(inst, if (total == 0) "brak darmowej historii – zbieram świece na żywo (zapisywane w telefonie)" else null)
        if (total > 0) msg("$inst: wczytano $total zapisanych świec do nauki")
    }

    /** Postęp ładowania historii (do HUD); null = gotowe. */
    private val loading = java.util.concurrent.ConcurrentHashMap<String, String>()

    private fun setLoading(inst: String, text: String?) {
        if (text == null) loading.remove(inst) else loading[inst] = text
        updateLearningHud()
    }
    private val lastPx = java.util.concurrent.ConcurrentHashMap<String, Pair<Double, Long>>()
    private val warned = HashSet<String>()
    @Volatile private var lastTick: Tick? = null
    private var lastFeedHud = 0L
    private var started = false

    fun start() {
        if (started) return
        started = true
        news.start()
        scope.launch { reconcileLoop() }

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
            if (errors.isEmpty()) {
                setLoading(inst, null)
            } else {
                // Darmowa historia Pyth została wyłączona (HTTP 404) – zastępstwo:
                noHistory.add(inst)
                if (inst in FEEDS) loadShiftedBinanceHistory(inst) else loadStoredBars(inst)
            }
        }
        // Strażnik: jeśli Pyth nie podaje cen na żywo, złoto przechodzi na Binance, reszta informuje w HUD.
        scope.launch {
            delay(90_000)
            for (inst in PYTH_FEEDS.keys) {
                if (pyth.status.value[inst] != "ok" || lastPx.containsKey(inst)) continue
                if (inst in FEEDS && binanceFallback.add(inst)) msg("$inst: Pyth nie podaje cen na żywo – przełączam na Binance ${FEEDS[inst]}")
                else setLoading(inst, "Pyth nie podaje cen na żywo – uczę się tylko przy otwartym XTB")
            }
        }
        scope.launch { pyth.ticks.collect { t -> guarded("cena ${t.symbol}") { lock.withLock { processPrice(t.symbol, t.price, t.timestamp) } } } }
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
        scope.launch { news.ticks.collect { t -> guarded("tick ${t.symbol}") { onFeedTick(t) } } }
        scope.launch {
            while (isActive) {
                runCatching { buildStatus() }.onSuccess { st -> _hud.update { it.copy(status = st) } }
                delay(1_000)
            }
        }
        scope.launch {
            combine(news.sentiment, news.connected, risk.killSwitch, risk.dailyPnl, engine.mode) { s, c, k, pnl, m ->
                _hud.update { it.copy(sentiment = s, feedConnected = c, killSwitch = k, dailyPnl = pnl, mode = m) }
            }.collect()
        }
        scope.launch {
            journal.stats.collect { s ->
                fun line(label: String, pred: (com.example.algohud.risk.ClosedTrade) -> Boolean): String {
                    val st = journal.statsWhere(pred)
                    return if (st.trades == 0) "$label: brak zamkniętych transakcji"
                    else "$label: %d tr. • %.0f%% • E=%+.2fR • %+.2f".format(st.trades, st.winRate * 100, st.avgR, st.pnl)
                }
                val rtLine = line("⚡ RT/ORB") { it.reason.startsWith("RT") || it.reason.startsWith("ORB") }
                val rangeLine = line("📈 ZAKRES") { it.reason.startsWith("ZAKRES") }
                _hud.update { it.copy(paperStats = s.summary() + "\n" + rangeLine + "\n" + rtLine) }
            }
        }
        scope.launch {
            news.news.collect { n ->
                if (abs(n.sentiment) >= 0.5) msg("📰 %+.2f %s".format(n.sentiment, n.title.take(90)))
            }
        }
        scope.launch { engine.events.collect { msg(it) } }
        scope.launch { AutoExecutionService.uiEvents.collect { msg(it) } }
        scope.launch { AutoExecutionService.screenQuote.filterNotNull().collect { q -> guarded("odczyt ekranu") { onScreen(q) } } }
        scope.launch { risk.killSwitch.filter { it }.collect { guarded("KILL") { engine.flattenAll(risk.killReason ?: "KILL") } } }
        // Koniec sesji / weekend: zamknięcie pozycji intraday [SESSION_CLOSE_MIN] min przed zamknięciem rynku.
        scope.launch {
            while (isActive) {
                val now = System.currentTimeMillis()
                for (sym in risk.openSymbols()) {
                    val left = MarketHours.status(sym, now).minutesToClose ?: continue
                    if (left > SESSION_CLOSE_MIN) continue
                    val px = lastPx[sym]?.first ?: continue
                    val pos = risk.position(sym) ?: continue
                    risk.closingOrder(sym, pos.side.opposite(), px)?.let {
                        msg("🌙 $sym: $left min do zamknięcia sesji – zamykam pozycję intraday")
                        engine.submit(withSpread(it.copy(reason = "KONIEC SESJI"), sym), bypassConfirmation = true)
                    }
                }
                delay(30_000)
            }
        }
        applyProfile()
        _hud.update { it.copy(profile = profileLabel()) }
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
            val real = engine.mode.value != ExecutionMode.DRY_RUN && risk.position(inst)?.paper == false
            if (real && AutoExecutionService.instance?.xtbPositionExists(inst) == false) {
                // XTB zamknęło pozycję własnym SL/TP szybciej niż bot – tylko księgujemy.
                risk.bookExternalClose(inst, px, "${exit.reason} (zamknięte przez XTB)")
                msg("🏁 $inst: ${exit.reason} @ %.2f – pozycję zamknęło XTB, szukam kolejnego wejścia".format(px))
                return@let
            }
            msg("🏁 $inst: ${exit.reason} @ %.2f".format(px))
            val r = engine.submit(withSpread(exit, inst), bypassConfirmation = true)
            if (r !is ExecutionResult.Filled && r !is ExecutionResult.Submitted) {
                msg("⚠️ $inst: zamknięcie (${exit.reason}) nieudane – ponowię za 20 s; pozycję chroni SL w XTB")
            }
        }
        for (tf in Learner.TIMEFRAMES) {
            val a = agg(inst, tf)
            val closed = a.onTick(px, ts) ?: continue
            // Instrumenty bez historii (lub bez cen w tle): zapisujemy świece – nauka rośnie z każdym dniem i przetrwa restart.
            if (!hasBackground(inst) || inst in noHistory) runCatching { barStore.append(inst, tf, closed) }
            val (sent, _) = news.sentimentFor(aliasesFor(inst))
            val l = learner(inst)
            val sig = l.onBar(tf, a.closed(), sent, spreadOf(inst))
            if (l.championBarMs == tf) l.championNote()?.let { msg("ℹ️ $inst: $it") }
            updateLearningHud()
            if (sig != null) handleSignal(inst, sig, px)
        }
        when (profile.value) {
            BotProfile.AGGRESSIVE -> { orbCheck(inst, px, ts); realtimeCheck(inst, px, ts) }
            BotProfile.RANGE -> rangeCheck(inst, px, ts)
            BotProfile.STANDARD -> Unit
        }
    }

    private suspend fun handleSignal(inst: String, sig: Signal, px: Double) {
        if (profile.value != BotProfile.STANDARD) return // turniej się uczy, ale handluje tylko w profilu STANDARD
        val learner = learner(inst)
        sessionBlock(inst, System.currentTimeMillis())?.let { msg("✋ $inst: sygnał pominięty – $it"); return }
        msg("📈 $inst ${sig.side} [${learner.champion?.name}]: ${sig.reason}")
        // Sygnał przeciwny do otwartej pozycji = zamknięcie i odwrócenie (long → short i odwrotnie).
        risk.closingOrder(inst, sig.side, px)?.let { close ->
            val r = engine.submit(withSpread(close.copy(reason = "ODWRÓCENIE"), inst), bypassConfirmation = true)
            if (r !is ExecutionResult.Filled && r !is ExecutionResult.Submitted) return
        }
        val entry = px + spreadOf(inst) / 2 * sig.side.sign
        // 1) Agresja tylko przy udowodnionej przewadze (rolling, po kosztach) tego instrumentu i interwału.
        val st = learner.championStats
        val allowed = when {
            st == null -> Tier.REDUCED
            st.tStat >= 2.0 && st.avgR >= 0.20 -> Tier.AGGRESSIVE
            st.tStat >= 1.5 -> Tier.MODERATE
            else -> Tier.REDUCED
        }
        val tier = minOf(sig.tier, allowed)
        // 2) EV sygnału w R: wynik zwycięzcy (już po spreadzie z nauki) minus dodatkowy koszt bieżącego spreadu.
        val dist = abs(entry - sig.stopPrice)
        val spreadNow = spreadOf(inst)
        val extraCostR = if (dist > 0) maxOf(0.0, spreadNow - learner.learnedCost) / dist else 0.0
        val spreadR = if (dist > 0) spreadNow / dist else 1.0
        val ev = (st?.avgR ?: 0.0) - extraCostR
        if (spreadR > 0.30 || ev <= 0.05) {
            msg("✋ $inst: EV %+.2fR po spreadzie (spread = %.0f%% ryzyka) – pomijam".format(ev, spreadR * 100))
            return
        }
        val order = buildOrder(inst, sig.side, entry, sig.stopPrice, sig.takeProfit, "STRAT ${sig.reason}", pointValue(inst), tier)
            ?: return
        decisionCard(inst, order, sig.copy(tier = tier), ev)
        engine.submit(order)
    }

    /** Decyzja w formacie: kondycja • kierunek • poziom agresji i wielkość • wejście/SL/TP (w cenach XTB). */
    private fun decisionCard(inst: String, o: OrderRequest, sig: Signal, ev: Double) {
        val evText = if (ev.isNaN()) "EV: mierzone na żywo" else "EV %+.2fR".format(ev)
        val q = AutoExecutionService.screenQuote.value?.takeIf { it.isFresh() && it.instrument == inst }
        val sh = q?.let { basis(inst, it) } ?: 0.0
        val tf = learners[inst]?.championBarMs ?: Learner.M5
        val cond = aggregators["$inst@$tf"]?.closed()?.let { MarketRead.describe(it) } ?: "?"
        msg(
            "📊 $cond\n➡️ ${if (o.side == Side.BUY) "LONG" else "SHORT"} • ${Tier.name(sig.tier)} " +
                "(%.1f%% ryzyka, %s lota, %s)\n🎯 E %.2f • SL %.2f • TP %s".format(
                    risk.tierRiskPct(sig.tier) * 100, BigDecimal.valueOf(o.quantity).toPlainString(), evText,
                    o.referencePrice + sh, o.stopPrice + sh, o.takeProfit?.let { "%.2f".format(it + sh) } ?: "trailing"
                )
        )
    }

    private suspend fun buildOrder(
        symbol: String, side: Side, price: Double, stop: Double, takeProfit: Double?, reason: String, pointValue: Double?,
        tier: Int = 1
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
        val qty = when (val r = risk.sizingFor(price, stop, pv, tier)) {
            is com.example.algohud.risk.PositionSizer.Result.Ok -> r.lots
            is com.example.algohud.risk.PositionSizer.Result.Rejected -> {
                msg("✋ $symbol (${Tier.name(tier)}): ${r.reason}")
                return null
            }
        }
        return OrderRequest(symbol, side, qty, price, stop, reason, pointValue = pv, takeProfit = takeProfit, tier = tier)
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

    private fun updateLearningHud() { runCatching { updateLearningHudUnsafe() } }

    private fun updateLearningHudUnsafe() {
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
        if (profile.value == BotProfile.RANGE) {
            val info = rangeInfo[inst] ?: "ZAKRES: zbieram świece 1m (potrzeba 60)…"
            _hud.update { it.copy(learning = "📈 $info\n" + l.summary().substringBefore('\n')) }
            return
        }
        val readTf = l.championBarMs ?: Learner.M5
        val read = aggregators["$inst@$readTf"]?.closed()?.let { MarketRead.describe(it) }
        _hud.update {
            it.copy(learning = l.summary() + (read?.let { r -> "\n📊 ${tfLabel(readTf)}: $r" } ?: "") + (wait?.let { w -> "\n⏳ $w" } ?: ""))
        }
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

    private val clockFmt = java.time.format.DateTimeFormatter.ofPattern("HH:mm")
    private fun msg(text: String) = _hud.update {
        it.copy(lastMessage = java.time.LocalTime.now(MarketHours.ZONE).format(clockFmt) + " " + text)
    }

    private fun f2(v: Double?) = v?.let { "%.2f".format(it) } ?: "—"

    /** Stan do HUD – wyłącznie prawdziwe dane: ekran XTB, konto, księga bota. */
    private suspend fun buildStatus(): String {
        val q = AutoExecutionService.screenQuote.value?.takeIf { it.isFresh() && it.instrument != null }
        val inst = q?.instrument ?: hudLearningInstrument
        val line1 = if (q != null) {
            val spr = if (q.bid != null && q.ask != null) " • spread ${f2(q.ask - q.bid)}" else ""
            "$inst: Sell ${f2(q.bid)} / Buy ${f2(q.ask)}$spr"
        } else "XTB: ${AutoExecutionService.diag}" +
            (if (AutoExecutionService.instance == null) " • USŁUGA UŁATWIEŃ DOSTĘPU WYŁĄCZONA" else "")
        val kill = if (risk.killSwitch.value) "\n⛔ KILL: ${risk.killReason ?: "aktywny"} – nowe zlecenia zablokowane" else ""
        val line2 = "Tryb ${engine.mode.value.name} • ${profile.value.label}$kill"
        val funds = AutoExecutionService.lastAccountFunds?.let { "%.2f %s".format(it, AutoExecutionService.lastAccountCurrency ?: "") } ?: "nieznane (otwórz bilet)"
        val pv = if (pointValue(inst) != null) "znana ✓" else "NIEZNANA – przewiń bilet do „Wartość kontraktu”"
        val pos = risk.position(inst)?.let { p ->
            val sh = q?.let { basis(inst, it) } ?: 0.0
            "%s %s @ %.2f • SL %.2f%s%s".format(
                if (p.side == Side.BUY) "LONG" else "SHORT", BigDecimal.valueOf(p.quantity).toPlainString(),
                p.entryPrice + sh, p.stopPrice + sh, p.takeProfit?.let { " • TP %.2f".format(it + sh) } ?: "",
                if (p.paper) " (papier)" else " (XTB)"
            )
        } ?: "brak"
        val pnl = "Wynik dnia – XTB: %+.2f • papier: %+.2f".format(risk.dailyPnl.value, risk.dailyPnlPaper.value)
        return listOf(line1, readinessLine(inst, q), line2, "Środki: $funds • wartość lota: $pv", "Pozycja bota: $pos", pnl,
            "(pozycje otwarte ręcznie w XTB nie są w księdze bota) • wersja ${com.example.algohud.BuildConfig.GIT_SHA}").joinToString("\n")
    }

    /** Jedna linijka: czy bot może teraz złożyć prawdziwe zlecenie, a jeśli nie – dokładnie dlaczego. */
    private suspend fun readinessLine(inst: String, q: ScreenQuote?): String {
        val now = System.currentTimeMillis()
        val why = ArrayList<String>()
        if (AutoExecutionService.instance == null) why += "włącz usługę ułatwień dostępu"
        if (engine.mode.value != ExecutionMode.AUTO) why += "tryb ${engine.mode.value.name} – wybierz AUTO w aplikacji"
        if (risk.killSwitch.value) why += "KILL aktywny"
        if (q == null || q.instrument != inst) why += "XTB nie pokazuje biletu $inst"
        else if (q.bid == null || q.ask == null) why += "brak cen Sell/Buy na ekranie"
        if (pointValue(inst) == null) why += "nieznana wartość lota"
        if (AutoExecutionService.lastAccountFunds == null) why += "nieznane środki"
        val lp = lastPx[inst]
        if (lp == null || now - lp.second > 20_000) why += "brak świeżej ceny w tle"
        val bars = aggregators["$inst@${Learner.M1}"]?.closed()?.size ?: 0
        if (bars < 60) why += "za mało świec 1m ($bars/60)"
        sessionBlock(inst, now)?.let { why += it }
        if (risk.cooldownLeftMs() > 0) why += "przerwa po stracie ${risk.cooldownLeftMs() / 1000} s"
        return when {
            why.isNotEmpty() -> "⛔ NIE HANDLUJĘ: ${why.joinToString("; ")}"
            risk.position(inst) != null -> "✅ W POZYCJI – pilnuję wyjścia"
            else -> "✅ GOTOWY – czekam na sygnał"
        }
    }

    // ───────────── Odporność: żaden pojedynczy błąd nie może zatrzymać bota ─────────────
    private var lastGuardNote = 0L

    private suspend fun guarded(what: String, block: suspend () -> Unit) {
        try {
            block()
        } catch (ce: kotlinx.coroutines.CancellationException) {
            throw ce
        } catch (t: Throwable) {
            android.util.Log.e("BotOrchestrator", "błąd: $what", t)
            val now = System.currentTimeMillis()
            if (now - lastGuardNote > 30_000) {
                lastGuardNote = now
                msg("⚠️ błąd wewnętrzny ($what): ${t.javaClass.simpleName} ${t.message ?: ""} – bot działa dalej")
            }
        }
    }

    // ───────────── Uzgadnianie księgi bota z XTB ─────────────
    private val missCount = java.util.concurrent.ConcurrentHashMap<String, Int>()

    /** Co 30 s sprawdza w XTB, czy pozycje z księgi bota nadal istnieją (SL/TP w XTB mogły je zamknąć). */
    private suspend fun reconcileLoop() {
        while (scope.isActive) {
            delay(30_000)
            guarded("uzgadnianie pozycji") { reconcileOnce() }
        }
    }

    private suspend fun reconcileOnce() {
        if (engine.mode.value == ExecutionMode.DRY_RUN) return
        val svc = AutoExecutionService.instance ?: return
        val now = System.currentTimeMillis()
        for (sym in risk.openSymbols()) {
            val pos = risk.position(sym) ?: continue
            if (pos.paper || now - pos.openedAt < 25_000) continue
            when (lock.withLock { svc.xtbPositionExists(sym) }) {
                true -> missCount[sym] = 0
                null -> Unit
                false -> {
                    val n = (missCount[sym] ?: 0) + 1
                    missCount[sym] = n
                    if (n < 2) continue // dwa kolejne potwierdzenia, żeby nie zaksięgować zamknięcia przez pomyłkę
                    missCount[sym] = 0
                    val last = lastPx[sym]?.first ?: pos.entryPrice
                    val long = pos.side == Side.BUY
                    val tp = pos.takeProfit
                    val exit = when {
                        long && last <= pos.stopPrice -> pos.stopPrice
                        !long && last >= pos.stopPrice -> pos.stopPrice
                        tp != null && ((long && last >= tp) || (!long && last <= tp)) -> tp
                        else -> last
                    }
                    if (risk.bookExternalClose(sym, exit, "ZAMKNIĘTE W XTB (SL/TP)")) {
                        msg("🏁 $sym: pozycji nie ma już w XTB (zamknął ją SL/TP) – księga zaktualizowana, szukam kolejnego wejścia")
                    }
                }
            }
        }
    }

    private fun aliasesFor(instrument: String): List<String> {
        val base = instrument.substringBefore('.')
        val extra = ALIASES.entries.firstOrNull { base.startsWith(it.key) }?.value.orEmpty()
        return extra + base.lowercase()
    }

    private companion object {
        const val HUD_FEED = "BTCUSDT"

        // Profil agresywny – progi wejść w czasie rzeczywistym
        const val RT_RANGE_ATR = 1.2      // zasięg bieżącej świecy ≥ 1,2 × ATR14
        const val AGGRESSIVE_COOLDOWN_MS = 3 * 60_000L // przerwa po stracie w profilu agresywnym / zakres
        // ZAKRES (dół → góra)
        const val RANGE_LOOKBACK = 30          // zakres = High/Low ostatnich 30 świec 1m
        const val RANGE_ZONE = 0.20            // dolne/górne 20% zakresu
        const val RANGE_CONFIRM_ATR = 0.3      // odbicie o ≥ 0,3×ATR od dołka/szczytu = potwierdzenie
        const val RANGE_MAX_TREND_ATR = 1.0    // silny trend → nie gramy zakresu
        const val RANGE_MIN_WIDTH_ATR = 2.0    // zakres musi mieć ≥ 2×ATR
        const val RANGE_MIN_REWARD_R = 1.0     // cel ≥ 1× ryzyko
        const val RT_BODY_RATIO = 0.70    // korpus ≥ 70% zasięgu
        const val RT_MAX_WICK = 0.15      // knot po stronie ruchu ≤ 15% zasięgu
        const val RT_LOOKBACK = 10        // wybicie High/Low ostatnich 10 świec 1m
        const val ORB_RANGE_MIN = 5L      // zasięg otwarcia = pierwsze 5 min
        const val ORB_ACTIVE_MIN = 15L    // tryb Open Breakout przez 15 min od otwarcia
        const val SESSION_NO_ENTRY_MIN = 30L // brak nowych wejść 30 min przed zamknięciem sesji
        const val SESSION_CLOSE_MIN = 10L    // zamknięcie pozycji 10 min przed zamknięciem sesji

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
