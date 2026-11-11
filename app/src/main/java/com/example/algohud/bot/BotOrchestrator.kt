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
    STANDARD("STANDARD (turniej)"),
    AI("🕯️ ŚWIECE + PREDYKCJA AI")
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
        risk.cooldownAfterLossMs = AGGRESSIVE_COOLDOWN_MS
    }

    fun setProfile(p: BotProfile) {
        profile.value = p
        prefs.edit().putString("profile", p.name).apply()
        applyProfile()
        _hud.update { it.copy(profile = profileLabel()) }
        msg("Profil: ${p.label}")
    }

    private fun profileLabel() = ALL_LABEL

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
        if (range < RT_RANGE_ATR * atr) {
            rtInfo[inst] = "czekam na świecę 1m ≥ %.1f×ATR (teraz %.1f×) wybijającą szczyt/dołek %d świec".format(RT_RANGE_ATR, range / atr, RT_LOOKBACK)
            return // to jeszcze nie jest kandydat na impuls
        }
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
        rtInfo[inst] = "świeca %.1f×ATR %s – %s".format(range / atr, if (dir > 0) "↑" else "↓", why ?: "WCHODZĘ")
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
        tp: Double? = null, tier: Int = Tier.AGGRESSIVE, tpR: Double = 2.0
    ): ExecutionResult? {
        // Bot może złożyć zlecenie tylko na instrumencie otwartym w XTB – pozostałe liczy w tle bez prób wejścia.
        val onScreen = AutoExecutionService.screenQuote.value?.takeIf { it.isFresh() }?.instrument
        if (onScreen != inst && engine.mode.value != ExecutionMode.DRY_RUN) {
            // Nie po cichu: dla instrumentu głównego powiedz, dlaczego sygnał przepadł (maks. raz na minutę).
            if (inst == hudLearningInstrument && ts - lastBlindNote > 60_000) {
                lastBlindNote = ts
                msg("✋ $inst: sygnał ($why), ale nie widzę $inst w XTB – ${AutoExecutionService.diag}")
            }
            return null
        }
        if (onScreen != inst && inst != hudLearningInstrument) return null // papier: tylko instrument główny i ten na ekranie
        sessionBlock(inst, ts)?.let { msg("✋ $inst: $why – pominięte: $it"); return null }
        val spread = spreadOf(inst)
        val entry = px + spread / 2 * dir
        val atr1 = aggregators["$inst@${Learner.M1}"]?.closed()?.takeLast(60)?.let { Indicators.atr(it) } ?: 0.0
        val minDist = maxOf(3 * spread, entry * risk.config.minStopDistancePct, MIN_STOP_ATR * atr1)
        val stop = if (abs(entry - rawStop) < minDist) entry - dir * minDist else rawStop
        val takeProfit = tp ?: (entry + dir * tpR * abs(entry - stop))
        val side = if (dir > 0) Side.BUY else Side.SELL
        risk.position(inst)?.let { pos ->
            if (rtNotes.add("inpos:$inst:${ts / Learner.M1}")) msg("✋ $inst: $why – pominięte, już w pozycji (${stratName(pos.entryReason)})")
            return null
        }
        val order = buildOrder(inst, side, entry, stop, takeProfit, why, pointValue(inst), tier) ?: return null
        decisionCard(inst, order, Signal(side, stop, takeProfit, why, tier), ev = Double.NaN)
        return engine.submit(order)
    }

    // ───────────── ŚWIECE + PREDYKCJA AI: model online, wejście przed zamknięciem świecy, trailing za impulsem ─────────────

    private val predictors = java.util.concurrent.ConcurrentHashMap<String, NextCandlePredictor>()
    private val predictedBar = HashMap<String, Long>()
    private val aiNote = HashMap<String, String>()
    private val aiEnteredBar = HashMap<String, Long>()
    private val rtInfo = java.util.concurrent.ConcurrentHashMap<String, String>()
    private val earlyExitChecked = HashMap<String, Long>()
    private val _plan = HashMap<String, String>()

    private fun stratName(reason: String) = when {
        reason.startsWith(AI_TAG) -> "🕯️ ŚWIECE+AI"
        reason.startsWith("SMC") -> "🏦 SMC"
        reason.startsWith("RT") -> "⚡ RT impuls"
        reason.startsWith("ORB") -> "⚡ ORB otwarcie"
        reason.startsWith("ZAKRES") -> "📈 ZAKRES"
        reason.startsWith("STRAT") -> "🧠 TURNIEJ"
        reason.startsWith("MANUAL") -> "ręczna"
        else -> reason.take(20)
    }

    /**
     * Wcześniejsze wyjście dla pozycji spoza AI (AI ma własne reguły): dwie kolejne świece 1m po wejściu
     * zamknięte przeciwko pozycji (korpus ≥ 0,3×ATR każda) przy stracie = świece nie idą tak, jak powinny.
     */
    private suspend fun candleEarlyExit(inst: String, now: Long) {
        if (!EARLY_LOSS_EXITS) return
        val pos = risk.position(inst) ?: return
        if (pos.entryReason.startsWith(AI_TAG)) return
        val a = aggregators["$inst@${Learner.M1}"] ?: return
        val closed = a.closed()
        val last = closed.lastOrNull() ?: return
        if (earlyExitChecked[inst] == last.start) return
        earlyExitChecked[inst] = last.start
        val after = closed.filter { it.start >= pos.openedAt - pos.openedAt % Learner.M1 + Learner.M1 }.takeLast(3)
        if (after.size < 3) return
        val atr = Indicators.atr(closed.takeLast(60))?.takeIf { it > 0 } ?: return
        val dir = pos.side.sign
        val px = lastPx[inst]?.first ?: return
        val against = after.all { (it.close - it.open) * dir <= -EARLY_EXIT_BODY_ATR * atr }
        val r0 = abs(pos.entryPrice - pos.initialStop).takeIf { it > 0 } ?: return
        if (!against || (px - pos.entryPrice) * dir / r0 > -0.5) return
        val close = risk.closingOrder(inst, pos.side.opposite(), px) ?: return
        msg("🏁 $inst: 3 świece 1m przeciwko pozycji – zamykam wcześniej, przed SL @ %.2f".format(px))
        engine.submit(withSpread(close.copy(reason = "ŚWIECE PRZECIW (wcześniejsze wyjście)"), inst), bypassConfirmation = true)
    }

    /** Plan bota po ludzku: co robi teraz, na co czeka, kiedy zamknie. */
    private suspend fun updatePlan(inst: String, now: Long) {
        val q = AutoExecutionService.screenQuote.value?.takeIf { it.isFresh() && it.instrument == inst }
        val sh = q?.let { basis(inst, it) } ?: 0.0
        fun f(v: Double) = "%.2f".format(v + sh)
        val pos = risk.position(inst)
        val text = if (pos != null) {
            val dir = pos.side.sign
            val px = lastPx[inst]?.first ?: pos.entryPrice
            val r = abs(pos.entryPrice - pos.initialStop)
            val pnlR = if (r > 0) (px - pos.entryPrice) * dir / r else 0.0
            val head = "🧭 PLAN: W POZYCJI ${if (dir > 0) "LONG" else "SHORT"} ${BigDecimal.valueOf(pos.quantity).toPlainString()} @ ${f(pos.entryPrice)} " +
                "(${stratName(pos.entryReason)}) • %+.2fR".format(pnlR)
            val sl = "SL ${f(pos.stopPrice)}" + if (pos.stopPrice != pos.initialStop) " (przesunięty)" else ""
            val exit = if (BROKER_EXITS_ONLY) {
                "Zamknę WYŁĄCZNIE w XTB: TP ${pos.takeProfit?.let { f(it) } ?: "—"} (zysk) albo $sl (strata). +1R → SL na BE, od +1,5R SL 1R za ceną; zmiany SL w otwartej pozycji XTB. Zero ręcznego przesuwania SL na stratę."
            } else if (pos.entryReason.startsWith(AI_TAG)) {
                val t = aiTrades[inst]
                val vtp = pos.entryPrice + dir * maxOf(AI_VTP_ATR * (aggregators["$inst@${Learner.M1}"]?.closed()?.takeLast(60)?.let { Indicators.atr(it) } ?: r), t?.expBody ?: 0.0)
                if (t?.trailing == true) "🚀 jadę za impulsem: trailing $sl – zamknę przy pierwszym wyhamowaniu (knot/zawrócenie świecy)"
                else "Cel ${f(vtp)}: przy celu z silnym impulsem NIE zamykam (trailing), bez impulsu zamykam. " +
                    "Po 4 świecach bez ruchu zamknę tylko na zero/plus; na minusie czekam – stratę zamyka tylko SL. $sl"
            } else {
                "TP ${pos.takeProfit?.let { f(it) } ?: "—"} • $sl (od +1R stop na wejście, dalej 1R za ceną). " +
                    "Przy wystrzale świecy (od +0,7×ATR zysku): TP dalej, SL za ceną. Na minusie nie zamykam – stratę zamyka tylko SL."
            }
            "$head\n$exit"
        } else {
            val block = sessionBlock(inst, now) ?: if (risk.cooldownLeftMs() > 0) "przerwa po stracie ${risk.cooldownLeftMs() / 1000} s" else null
            val l = learners[inst]
            val orb = MarketHours.status(inst, now).lastOpenEventMs?.let { ev ->
                val m = (now - ev) / 60_000
                if (m in 0 until ORB_ACTIVE_MIN) "zasięg otwarcia sesji – wejdę przy wybiciu (jeszcze ${ORB_ACTIVE_MIN - m} min)" else null
            }
            listOfNotNull(
                "🧭 PLAN: CZEKAM" + (block?.let { " – $it" } ?: " – żadna z 4 strategii nie daje jeszcze sygnału"),
                smcPlan(inst, sh),
                "• ⚡ RT: " + (rtInfo[inst] ?: "zbieram świece 1m…") + (orb?.let { "\n• ⚡ ORB: $it" } ?: ""),
                "• 📈 " + (rangeInfo[inst] ?: "ZAKRES: zbieram świece 1m…"),
                traderNote[inst]?.let { "• 🎩 Trader: ostatni sygnał odpuszczony – $it" },
                "• 🧠 TURNIEJ: " + (l?.summary()?.substringBefore('\n')?.substringAfter(": ")?.take(90) ?: "uczę się…")
            ).joinToString("\n")
        }
        if (_plan[inst] != text) { _plan[inst] = text; _hud.update { it.copy(plan = text) } }
    }

    private fun predictor(inst: String) = predictors.getOrPut(inst) {
        NextCandlePredictor().also { p -> prefs.getString("ncp_$inst", null)?.let { p.import(it) } }
    }

    private fun savePredictor(inst: String) {
        predictors[inst]?.let { prefs.edit().putString("ncp_$inst", it.export()).apply() }
    }

    /** „Wolumen” = liczba ticków w świecy 1m (feed złota nie podaje wolumenu). */
    private class TickStat(var barStart: Long = 0L, var count: Int = 0, val past: ArrayDeque<Int> = ArrayDeque())
    private val ticks = HashMap<String, TickStat>()

    private fun countTick(inst: String, ts: Long) {
        val t = ticks.getOrPut(inst) { TickStat() }
        val start = ts - ts % Learner.M1
        if (start != t.barStart) {
            if (t.barStart != 0L) { t.past.addLast(t.count); while (t.past.size > 30) t.past.removeFirst() }
            t.barStart = start; t.count = 0
        }
        t.count++
    }

    /** Aktywność ticków bieżącej świecy względem średniej (tempo na sekundę); 1.0 = normalnie. */
    private fun tickRatio(inst: String, ts: Long): Double {
        val t = ticks[inst] ?: return 1.0
        if (t.past.size < 5) return 1.0
        val avg = t.past.average().takeIf { it > 0 } ?: return 1.0
        val frac = ((ts - t.barStart).toDouble() / Learner.M1).coerceIn(0.15, 1.0)
        return (t.count / frac) / avg
    }

    private val lastM1Close = HashMap<String, Long>()

    /** Co sekundę: domyka świecę 1m po czasie i robi prognozę ~10 s przed końcem – niezależnie od gęstości ticków. */
    private suspend fun candleClock(now: Long) {
        for (inst in predictors.keys + hudLearningInstrument) {
            val a = aggregators["$inst@${Learner.M1}"] ?: continue
            a.roll(now)?.let { onM1Closed(inst, a.closed()) }
            val px = lastPx[inst]?.first ?: continue
            aiPredictTick(inst, px, now)
            candleEarlyExit(inst, now)
            watchDataOutage(inst, now)
            smcTick(inst, px, now)
            if (inst == hudLearningInstrument) updatePlan(inst, now)
            if (inst == hudLearningInstrument) {
                val lc = lastM1Close[inst] ?: now.also { lastM1Close[inst] = it }
                if (now - lc > 3 * Learner.M1 && now - lastStallNote > Learner.M1) {
                    lastStallNote = now
                    val cur = a.current()
                    msg("⚠️ $inst: świece 1m stoją od %d min (ostatni tick %s s temu, bieżąca świeca od %s s)".format(
                        (now - lc) / Learner.M1, lastPx[inst]?.second?.let { (now - it) / 1000 } ?: "?",
                        cur?.let { (now - it.start) / 1000 } ?: "—"))
                }
            }
        }
    }
    private var lastStallNote = 0L

    private fun onM1Closed(inst: String, closed: List<Bar>) {
        lastM1Close[inst] = System.currentTimeMillis()
        val p = predictor(inst)
        if (p.samples == 0L && closed.size >= 100) p.warmUp(closed) else p.onBarClosed(closed)
        if (p.samples % 5 == 0L) savePredictor(inst)
        if (inst == hudLearningInstrument) updatePredictionHud(inst)
    }

    /** ~10 s przed zamknięciem świecy 1m: prognoza następnej (raz na świecę), w profilu AI – ewentualne wejście. */
    private suspend fun aiPredictTick(inst: String, px: Double, ts: Long) {
        val a = aggregators["$inst@${Learner.M1}"] ?: return
        val cur = a.current() ?: return
        val elapsed = ts - cur.start
        if (elapsed < AI_EARLY_AT_MS) return
        val slot = cur.start + elapsed / AI_EARLY_STEP_MS // od 30 s co 5 s, od 50 s jedna „późna”
        val late = elapsed >= AI_PREDICT_AT_MS
        val key = if (late) cur.start * 10 + 9 else slot * 10
        if (predictedBar[inst] == key) return
        val closed = a.closed()
        if (closed.size < 60) return
        predictedBar[inst] = key
        val p = predictor(inst).predict(closed, cur, tickRatio(inst, ts), Learner.M1, ts, early = !late) ?: return
        if (AI_PREDICTOR_ENTRIES) aiEntry(inst, p, px, ts)
        if (inst == hudLearningInstrument) updatePredictionHud(inst)
    }

    private suspend fun aiEntry(inst: String, p: NextCandlePredictor.Prediction, px: Double, ts: Long) {
        val curBar = p.forBarStart - Learner.M1
        if (aiEnteredBar[inst] == curBar) return
        val (hit, graded) = predictor(inst).hitStats(early = p.early)
        val acc = if (graded > 0) hit.toDouble() / graded else 0.0
        val minProb = if (p.early) AI_EARLY_MIN_PROB else AI_MIN_PROB
        val label = if (p.early) "wczesna prognoza" else "prognoza"
        val dir = if (p.up) 1 else -1
        val side = if (dir > 0) Side.BUY else Side.SELL
        val spread = spreadOf(inst)
        val pos = risk.position(inst)
        val why = when {
            graded < AI_MIN_GRADED -> "$label: model się rozgrzewa (ocenione $graded/$AI_MIN_GRADED)"
            acc < AI_MIN_ACC -> "$label: trafność %.0f%% < %.0f%% – brak przewagi".format(acc * 100, AI_MIN_ACC * 100)
            p.prob < minProb -> "$label: szansa %.0f%% < %.0f%%".format(p.prob * 100, minProb * 100)
            p.expBody < AI_MIN_BODY_SPREADS * spread -> "oczekiwany ruch %.2f < %.0f× spread".format(p.expBody, AI_MIN_BODY_SPREADS)
            pos?.side == side -> "już w pozycji w tę stronę"
            pos != null && !pos.entryReason.startsWith(AI_TAG) -> "otwarta pozycja innej strategii"
            risk.cooldownLeftMs() > 0 -> "przerwa po stracie ${risk.cooldownLeftMs() / 1000} s"
            else -> null
        }
        aiNote[inst] = why?.let { "⏸ $it" } ?: "▶️ WCHODZĘ ${if (dir > 0) "LONG" else "SHORT"}"
        if (why != null) return
        val stop = px - dir * AI_STOP_ATR * p.atr
        val reason = "$AI_TAG ${if (p.early) "wcześnie " else ""}${if (dir > 0) "wzrostowa" else "spadkowa"} %.0f%%, korpus ~%.2f".format(p.prob * 100, p.expBody)
        aiEnteredBar[inst] = curBar
        val r = enterAggressive(inst, dir, px, stop, reason, ts, tpR = AI_SAFETY_TP_R)
        if (r is ExecutionResult.Filled || r is ExecutionResult.Submitted) aiTrades[inst] = AiTrade(expBody = p.expBody)
    }

    // ── zarządzanie pozycją AI: TP wirtualny, przesuwanie TP za impulsem, ciasny trailing, wyjście przy wyhamowaniu ──

    private class AiTrade(
        val expBody: Double,
        var trailing: Boolean = false,
        var predChecked: Boolean = false,
        var brokerSl: Double = Double.NaN,
        var brokerAt: Long = 0L,
        var brokerBusy: Boolean = false,
        var closing: Boolean = false,
        var beNoted: Boolean = false
    )
    private val aiTrades = HashMap<String, AiTrade>()

    private suspend fun aiManage(inst: String, px: Double, ts: Long) {
        val pos = risk.position(inst) ?: run { aiTrades.remove(inst); return }
        val isAi = pos.entryReason.startsWith(AI_TAG) && !BROKER_EXITS_ONLY // w trybie „tylko SL” AI nie ma własnych wyjść
        val t = aiTrades.getOrPut(inst) { AiTrade(expBody = 0.0) } // też pozycje innych strategii / po restarcie
        if (t.closing) return
        val dir = pos.side.sign
        val r = abs(pos.entryPrice - pos.initialStop).takeIf { it > 0 } ?: return
        val a = aggregators["$inst@${Learner.M1}"] ?: return
        val closed = a.closed()
        val cur = a.current()
        val atr = Indicators.atr(closed.takeLast(60))?.takeIf { it > 0 } ?: r
        val virtualTp = pos.entryPrice + dir * maxOf(AI_VTP_ATR * atr, t.expBody)
        val profitAtr = (px - pos.entryPrice) * dir / atr

        val pnlR = (px - pos.entryPrice) * dir / r
        if (BROKER_EXITS_ONLY) {
            // Tylko SL: stop prowadzony w złotówkach i przesuwany W OTWARTEJ POZYCJI w XTB (ekran edycji pozycji).
            val best = if (dir > 0) maxOf(pos.best, px) else minOf(pos.best, px)
            val perPrice = pos.quantity * pos.pointValue // PLN za ruch ceny o 1,0
            if (perPrice > 0) {
                val bestPln = (best - pos.entryPrice) * dir * perPrice
                val rPln = r * perPrice
                if (bestPln >= maxOf(BE_AT_PLN, rPln)) {
                    // BE dopiero po +1R (wcześniej to tylko oddawanie pozycji szumowi), od +1,5R SL 1R za najlepszą ceną.
                    val be = pos.entryPrice + dir * maxOf(spreadOf(inst), 0.05)
                    val trail = best - dir * r
                    val target = if (bestPln >= 1.5 * rPln && (trail - be) * dir > 0) trail else be
                    if (risk.tightenStop(inst, target) && !t.beNoted) {
                        t.beNoted = true
                        msg("🔒 $inst: +1R (%.0f zł) – SL na BE; od +1,5R SL 1R za ceną, TP bez zmian".format(bestPln))
                    }
                }
            }
            if (!t.trailing && pnlR >= 2.0 && cur != null && impulseStrong(inst, cur, dir, atr, ts)) {
                t.trailing = true
                msg("🚀 $inst: +2R i wystrzał świecy – SL ciasno za impulsem")
            }
            if (t.trailing) risk.tightenStop(inst, best - dir * maxOf(AI_TRAIL_ATR * atr, 2 * spreadOf(inst)))
            brokerSync(inst, pos, t, ts, perPrice)
            return
        }
        if (!t.trailing && !isAi) {
            // Pozostałe strategie: wystrzał świecy w stronę pozycji przy zysku → TP dalej, SL za ceną.
            if (profitAtr >= IMPULSE_MIN_ATR && cur != null && impulseStrong(inst, cur, dir, atr, ts)) {
                t.trailing = true
                risk.moveTakeProfit(inst, null)
                msg("🚀 $inst: wystrzał świecy przy %+.1fR – przesuwam TP dalej i SL za ceną".format(pnlR))
            } else return
        }
        if (!t.trailing) {
            if (profitAtr >= IMPULSE_MIN_ATR && (px - virtualTp) * dir < 0 && cur != null && impulseStrong(inst, cur, dir, atr, ts)) {
                // wystrzał jeszcze przed celem – też jedziemy za nim
                t.trailing = true
                risk.moveTakeProfit(inst, null)
                msg("🚀 $inst: wystrzał świecy przed celem – TP dalej, ciasny trailing")
            } else if ((px - virtualTp) * dir >= 0) {
                if (cur != null && impulseStrong(inst, cur, dir, atr, ts)) {
                    t.trailing = true
                    risk.moveTakeProfit(inst, null) // TP prowadzi teraz bot (trailing), w XTB odsuwamy go dalej
                    msg("🚀 $inst: silny impuls przy TP – nie zamykam, TP przesuwam, ciasny trailing")
                } else {
                    aiClose(inst, pos, px, t, "TP (AI) – brak impulsu do dalszej jazdy"); return
                }
            } else {
                val predEnd = pos.openedAt - pos.openedAt % Learner.M1 + 2 * Learner.M1
                if (ts >= predEnd && !t.predChecked) {
                    val pb = closed.lastOrNull { it.start == predEnd - Learner.M1 }
                    if (pb != null) {
                        t.predChecked = true
                        if (EARLY_LOSS_EXITS && (pb.close - pb.open) * dir < 0 && pnlR <= -AI_WRONG_CANDLE_MIN_LOSS_R) { aiClose(inst, pos, px, t, "prognoza chybiona – świeca przeciwnie, strata %.1fR".format(pnlR)); return }
                    }
                }
                // Wyjście czasowe tylko „na zero lub plus” – stratę zamyka wyłącznie SL (15–20 PLN), cena ma czas odbić.
                if (ts >= predEnd + AI_MAX_EXTRA_BARS * Learner.M1 && pnlR >= 0.0 && pnlR < 0.3 && px != pos.entryPrice) {
                    aiClose(inst, pos, px, t, "czas – ruch nie doszedł do celu, zamykam bez straty"); return
                }
            }
        }
        if (!t.trailing) return
        val best = if (dir > 0) maxOf(pos.best, px) else minOf(pos.best, px)
        val trailStop = best - dir * maxOf(AI_TRAIL_ATR * atr, 2 * spreadOf(inst))
        risk.tightenStop(inst, trailStop) // przecięcie = TRAILING-STOP w risk.onMark (natychmiast, bez czekania na XTB)
        if (!BROKER_EXITS_ONLY && cur != null && slowdown(cur, dir, atr)) { aiClose(inst, pos, px, t, "wyhamowanie impetu"); return }
        // Zabezpieczenie w XTB (gdyby aplikacja padła): SL za zyskiem + TP odsunięty. Rzadko – to operacje na ekranie.
        // SL przesuwany za impulsem NIGDY po stronie straty: min. wejście + spread (w XTB = próg rentowności).
        val beStop = pos.entryPrice + dir * maxOf(spreadOf(inst), 0.05)
        val ledgerStop = risk.position(inst)?.stopPrice ?: return
        if ((ledgerStop - beStop) * dir < 0) return // trailing jeszcze nie wyszedł nad próg – w XTB zostaje SL 15–20 PLN
        val stopNow = ledgerStop
        if (!pos.paper && engine.mode.value != ExecutionMode.DRY_RUN && !t.brokerBusy &&
            ts - t.brokerAt >= AI_BROKER_EVERY_MS && (t.brokerSl.isNaN() || abs(stopNow - t.brokerSl) >= AI_BROKER_STEP_ATR * atr)
        ) {
            t.brokerBusy = true; t.brokerAt = ts
            val farTp = best + dir * AI_SAFETY_TP_R * r
            scope.launch {
                try {
                    val q = AutoExecutionService.screenQuote.value?.takeIf { it.isFresh() && it.instrument == inst }
                    val sh = q?.let { basis(inst, it) } ?: 0.0
                    val res = AutoExecutionService.instance?.setProtection(inst, stopNow + sh, if (BROKER_EXITS_ONLY) null else farTp + sh)
                    if (res is ExecutionResult.Submitted || res is ExecutionResult.Filled) {
                        t.brokerSl = stopNow
                        msg(if (BROKER_EXITS_ONLY) "🛡 $inst: w XTB SL → %.2f (za zyskiem)".format(stopNow + sh) else "🛡 $inst: w XTB SL → %.2f, TP → %.2f (za impulsem)".format(stopNow + sh, farTp + sh))
                    } else if (res != null) msg("⚠️ $inst: nie przesunąłem SL/TP w XTB (${(res as? ExecutionResult.Rejected)?.reason ?: res}) – trailing pilnuje bot")
                } finally { t.brokerBusy = false }
            }
        }
    }

    /**
     * Przenosi SL z księgi bota do OTWARTEJ POZYCJI w XTB (ekran pozycji → edycja → Stop Loss → zapisz).
     * Tylko po stronie zysku (≥ wejście + spread), maks. co [BROKER_SYNC_EVERY_MS] i o ≥ [BROKER_STEP_PLN] zł.
     */
    private fun brokerSync(inst: String, pos: com.example.algohud.core.Position, t: AiTrade, ts: Long, perPrice: Double) {
        if (pos.paper || engine.mode.value == ExecutionMode.DRY_RUN || t.brokerBusy) return
        if (ts - t.brokerAt < BROKER_SYNC_EVERY_MS) return
        scope.launch {
            val stopNow = risk.position(inst)?.stopPrice ?: return@launch
            val dir = pos.side.sign
            val be = pos.entryPrice + dir * maxOf(spreadOf(inst), 0.05)
            if ((stopNow - be) * dir < 0) return@launch // SL jeszcze startowy (15–20 zł) – w XTB bez zmian
            val step = maxOf(2 * spreadOf(inst), if (perPrice > 0) BROKER_STEP_PLN / perPrice else 0.3)
            if (!t.brokerSl.isNaN() && abs(stopNow - t.brokerSl) < step) return@launch
            if (t.brokerBusy) return@launch
            t.brokerBusy = true; t.brokerAt = ts
            try {
                val q = AutoExecutionService.screenQuote.value?.takeIf { it.isFresh() && it.instrument == inst }
                val sh = q?.let { basis(inst, it) } ?: 0.0
                val res = AutoExecutionService.instance?.setProtection(inst, stopNow + sh, null)
                if (res is ExecutionResult.Submitted || res is ExecutionResult.Filled) {
                    t.brokerSl = stopNow
                    msg("🛡 $inst: SL w otwartej pozycji XTB → %.2f".format(stopNow + sh))
                } else if (res != null) {
                    msg("⚠️ $inst: nie zmieniłem SL w pozycji XTB (${(res as? ExecutionResult.Rejected)?.reason ?: res}) – ponowię")
                }
            } finally { t.brokerBusy = false }
        }
    }

    /** Silny impuls: świeca w stronę pozycji, duży korpus, brak knota po stronie ruchu, rosnąca aktywność ticków. */
    private fun impulseStrong(inst: String, cur: Bar, dir: Int, atr: Double, ts: Long): Boolean {
        val range = cur.high - cur.low
        if (range < AI_IMPULSE_ATR * atr) return false
        val body = (cur.close - cur.open) * dir
        val wick = if (dir > 0) cur.high - cur.close else cur.close - cur.low
        return body >= 0.6 * range && wick <= 0.15 * range && tickRatio(inst, ts) >= 1.1
    }

    /** Pierwszy sygnał wyhamowania: duży knot przeciwko pozycji albo świeca zawraca. */
    private fun slowdown(cur: Bar, dir: Int, atr: Double): Boolean {
        val range = cur.high - cur.low
        val wick = if (dir > 0) cur.high - cur.close else cur.close - cur.low
        return (range >= 0.3 * atr && wick >= 0.4 * range) || (cur.close - cur.open) * dir <= -0.25 * atr
    }

    private suspend fun aiClose(inst: String, pos: com.example.algohud.core.Position, px: Double, t: AiTrade, why: String) {
        val close = risk.closingOrder(inst, pos.side.opposite(), px) ?: return
        t.closing = true
        msg("🏁 $inst: $why @ %.2f".format(px))
        val r = engine.submit(withSpread(close.copy(reason = "AI: $why"), inst), bypassConfirmation = true)
        if (r !is ExecutionResult.Filled && r !is ExecutionResult.Submitted) {
            t.closing = false // spróbuje ponownie przy kolejnym ticku; pozycję chroni SL w XTB
            msg("⚠️ $inst: zamknięcie nieudane – ponowię; SL w XTB chroni pozycję")
        }
    }

    private fun updatePredictionHud(inst: String) = runCatching {
        val pr = predictors[inst] ?: return@runCatching
        val (hit, graded) = pr.hitStats()
        val acc = if (graded > 0) "%d/%d (%.0f%%)".format(hit, graded, hit * 100.0 / graded) else "brak ocen"
        val p = pr.last?.takeIf { System.currentTimeMillis() - it.madeAt < 2 * Learner.M1 }
        val cal = pr.calibration()
        val cur = aggregators["$inst@${Learner.M1}"]?.current()
        val line1 = p?.let {
            val sideways = it.prob < 0.55 || it.expBody < 2 * spreadOf(inst)
            val scenario = if (sideways) "BOCZNY (brak przewagi po kosztach)" else if (it.up) "WZROSTOWY" else "SPADKOWY"
            val chance = if (cal.ok) " | P≈%.0f%% (skalibrowane)".format(it.prob * 100) else ""
            val inval = cur?.let { c -> if (it.up) " | unieważnia: zamknięcie M1 < %.2f".format(c.low) else " | unieważnia: zamknięcie M1 > %.2f".format(c.high) } ?: ""
            "🔮 Horyzont 1 świeca (1m), ${if (it.early) "wstępna – świeca w trakcie" else "późna – świeca prawie zamknięta"}: $scenario$chance | zakres ±%.2f$inval".format(it.expBody)
        } ?: "🔮 Prognoza 1m powstaje od 30 s świecy"
        val (eh, eg) = pr.hitStats(early = true)
        val eacc = if (eg > 0) "%d/%d (%.0f%%)".format(eh, eg, eh * 100.0 / eg) else "brak ocen"
        val line2 = "🎓 Model: trafność z ost. 20: $acc • wcześnie (30–50 s): $eacc • ${cal.why} • tylko informacja, nie otwiera pozycji"
        val line3: String? = null
        val ctx = smcResult[inst]?.context?.takeIf { it.isNotBlank() }?.let { "🧭 Kontekst: $it" }
        _hud.update { it.copy(prediction = listOfNotNull(ctx, line1, line2, line3).joinToString("\n"), predictionUp = p?.takeIf { pp -> pp.prob >= 0.55 }?.up) }
    }

    // ───────────── 📡 Dane: pochodzenie, świeżość, bramka wejść, uzgodnienie po przerwie ─────────────

    private val basisHist = java.util.concurrent.ConcurrentHashMap<String, ArrayDeque<Double>>()
    private val basisAt = HashMap<String, Long>()
    private val needsReconcile = java.util.Collections.synchronizedSet(HashSet<String>())
    private val outageSince = HashMap<String, Long>()
    @Volatile private var reconcileRunning = false

    private fun sampleBasis(inst: String, q: ScreenQuote) {
        val mid = q.mid ?: return
        val (px, ts) = lastPx[inst] ?: return
        if (!hasBackground(inst) || !q.isFresh() || System.currentTimeMillis() - ts > 10_000) return
        val now = System.currentTimeMillis()
        if (now - (basisAt[inst] ?: 0L) < 5_000) return
        basisAt[inst] = now
        val d = basisHist.getOrPut(inst) { ArrayDeque() }
        synchronized(d) { d.addLast(mid - px); while (d.size > 120) d.removeFirst() } // ~10 min próbek
    }

    private fun gateInput(inst: String): DataQuality.GateInput {
        val now = System.currentTimeMillis()
        val q = AutoExecutionService.screenQuote.value
        val (fp, fts) = lastPx[inst] ?: (null to null)
        val typical = basisHist[inst]?.let { d -> synchronized(d) { d.toList() } }?.takeIf { it.size >= 6 }?.let { DataQuality.median(it) }
        return DataQuality.GateInput(
            now = now, expectedInstrument = inst,
            screenInstrument = q?.instrument, screenQuoteAt = q?.timestamp, screenBid = q?.bid, screenAsk = q?.ask,
            feedAt = fts, feedPrice = fp,
            typicalBasis = if (hasBackground(inst)) typical else 0.0,
            atr1 = aggregators["$inst@${Learner.M1}"]?.closed()?.takeLast(60)?.let { Indicators.atr(it) },
            pointValueKnown = pointValue(inst) != null,
            needsReconcile = inst in needsReconcile,
            realTrading = engine.mode.value != ExecutionMode.DRY_RUN
        )
    }

    private fun dataBlock(inst: String): String? = DataQuality.entryBlock(gateInput(inst)).also { dataNote[inst] = it }
    private val dataNote = java.util.concurrent.ConcurrentHashMap<String, String?>()

    /** Przerwa w danych > 30 s → po powrocie najpierw uzgodnienie pozycji z XTB, dopiero potem nowe wejścia. */
    private suspend fun watchDataOutage(inst: String, now: Long) {
        val g = gateInput(inst)
        val feedStale = g.feedAt == null || now - g.feedAt > DataQuality.FEED_MAX_AGE_MS
        val screenStale = g.realTrading && (g.screenQuoteAt == null || now - g.screenQuoteAt > 30_000 || g.screenInstrument != inst)
        if (feedStale || screenStale) {
            val since = outageSince.getOrPut(inst) { now }
            if (now - since > 30_000 && needsReconcile.add(inst)) msg("📡 $inst: przerwa w danych > 30 s – nowe wejścia wstrzymane do uzgodnienia z XTB")
            return
        }
        outageSince.remove(inst)
        if (inst in needsReconcile && !reconcileRunning) {
            reconcileRunning = true
            scope.launch {
                try {
                    guarded("uzgadnianie po przerwie") { reconcileOnce() }
                    needsReconcile.remove(inst)
                    msg("✅ $inst: dane wróciły, pozycje uzgodnione z XTB – wejścia odblokowane")
                } finally { reconcileRunning = false }
            }
        }
        updateDataReport(inst, g)
    }

    private var lastReportAt = 0L

    /** Raport pól: wartość, źródło, czas, jednostka, status. Brak = „brak”, nigdy zero. */
    private suspend fun updateDataReport(inst: String, g: DataQuality.GateInput) {
        val now = g.now
        if (now - lastReportAt < 2_000 || inst != hudLearningInstrument) return
        lastReportAt = now
        val q = AutoExecutionService.screenQuote.value?.takeIf { it.instrument == inst }
        val feedSrc = when { inst in binanceFallback -> "feed Binance ${FEEDS[inst] ?: "?"}"; hasBackground(inst) -> "feed w tle (Pyth/Binance)"; else -> "XTB ekran" }
        val m1 = aggregators["$inst@${Learner.M1}"]
        val m5 = aggregators["$inst@${Learner.M5}"]
        val pos = risk.position(inst)
        val pv = pointValue(inst)
        val fields = listOf(
            DataQuality.Field("Instrument w XTB", q?.instrument, "XTB ekran (tytuł)", q?.timestamp, "", DataQuality.SCREEN_MAX_AGE_MS),
            DataQuality.Field("Sell/Buy", if (q?.bid != null && q.ask != null) "%.2f / %.2f".format(q.bid, q.ask) else null, "XTB ekran", q?.timestamp, "USD", DataQuality.SCREEN_MAX_AGE_MS),
            DataQuality.Field("Spread", if (q?.bid != null && q.ask != null) "%.2f".format(q.ask - q.bid) else null, "obliczone z XTB", q?.timestamp, "USD", DataQuality.SCREEN_MAX_AGE_MS),
            DataQuality.Field("Cena do analizy", g.feedPrice?.let { "%.2f".format(it) }, feedSrc, g.feedAt, "USD", DataQuality.FEED_MAX_AGE_MS),
            DataQuality.Field("Różnica XTB−feed", g.typicalBasis?.let { "%.2f (mediana 10 min)".format(it) }, "obliczone", basisAt[inst], "USD", 60_000),
            DataQuality.Field("Świeca 1m", m1?.current()?.let { "otwarta od ${(now - it.start) / 1000} s, zamkniętych ${m1.closed().size}" }, "$feedSrc (wykres XTB to canvas – nieczytelny)", lastPx[inst]?.second, "", DataQuality.FEED_MAX_AGE_MS),
            DataQuality.Field("Świece 5m", m5?.closed()?.size?.toString(), feedSrc, lastPx[inst]?.second, "szt.", DataQuality.FEED_MAX_AGE_MS),
            DataQuality.Field("Wartość kontraktu", q?.contractValueAcct?.let { "%.2f za %s lota".format(it, q.volume?.let { v -> BigDecimal.valueOf(v).toPlainString() } ?: "?") }, "XTB ekran (bilet)", q?.timestamp, q?.accountCurrency ?: "PLN", DataQuality.SCREEN_MAX_AGE_MS),
            DataQuality.Field("Wartość 1 lota / 1 USD", pv?.let { "%.2f".format(it) }, "obliczone z biletu XTB (zapamiętane)", if (pv != null) now else null, q?.accountCurrency ?: "PLN", Long.MAX_VALUE),
            DataQuality.Field("Wolne środki", q?.availableFunds?.let { "%.2f".format(it) }, "XTB ekran (bilet)", q?.timestamp, q?.accountCurrency ?: "PLN", DataQuality.SCREEN_MAX_AGE_MS),
            DataQuality.Field("Saldo / equity", null, "XTB bilet tego nie pokazuje", null, "PLN", 0),
            DataQuality.Field("Depozyt pozycji", null, "nieodczytywany (pole „Depozyt” bez stałego ID)", null, "PLN", 0),
            DataQuality.Field("Krok / min. wolumen", null, "XTB nie pokazuje na bilecie; bot używa 0.006 (przyjmowane przez XTB)", null, "lot", 0),
            DataQuality.Field("Pozycja bota", pos?.let { "${it.side} ${BigDecimal.valueOf(it.quantity).toPlainString()} @ %.2f SL %.2f TP %s".format(it.entryPrice, it.stopPrice, it.takeProfit?.let { t -> "%.2f".format(t) } ?: "—") } ?: "brak pozycji",
                "księga bota (uzgadniana z XTB co 30 s)", now, "", Long.MAX_VALUE),
            DataQuality.Field("Godziny handlu", MarketHours.status(inst, now).let { st -> if (st.open) "otwarte, do zamknięcia ${st.minutesToClose ?: "?"} min" else "zamknięte" }, "wbudowany harmonogram (nie z XTB)", now, "", Long.MAX_VALUE),
            DataQuality.Field("Kalendarz / wiadomości", null, "nie zintegrowane z XTB; stałe okna danych USA", null, "", 0)
        )
        val block = dataNote[inst] ?: DataQuality.entryBlock(g)
        DataQuality.lastReport = buildString {
            appendLine("📡 Dane $inst – ${java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.ROOT).format(java.util.Date(now))}")
            appendLine("Wejścia: " + (block?.let { "⛔ $it" } ?: "✓ dane wiarygodne"))
            fields.forEach { appendLine(it.line(now)) }
        }
        val headline = "📡 Dane: " + (block?.let { "⛔ $it" } ?: "✓ XTB ${q?.timestamp?.let { (now - it) / 1000 } ?: "?"} s • feed ${g.feedAt?.let { (now - it) / 1000 } ?: "?"} s")
        _hud.update { it.copy(dataStatus = headline) }
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
        scope.launch {
            pyth.ticks.collect { t ->
                // Po przełączeniu na Binance nie mieszamy dwóch źródeł w jednych świecach.
                if (t.symbol in binanceFallback) return@collect
                guarded("cena ${t.symbol}") { lock.withLock { processPrice(t.symbol, t.price, t.timestamp) } }
            }
        }
        // Zegar świec 1m: prognoza i ocena co minutę nawet przy rzadkich tickach.
        scope.launch {
            while (isActive) {
                delay(1_000)
                guarded("zegar świec") { lock.withLock { candleClock(System.currentTimeMillis()) } }
            }
        }
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
                    if (BROKER_EXITS_ONLY || left > SESSION_CLOSE_MIN) continue
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
        sampleBasis(inst, q)
        if (q.bid != null && q.ask != null && q.ask >= q.bid) rememberSpread(inst, q.ask - q.bid)
        // Instrumenty bez feedu w tle – liczone z ekranu XTB (tylko gdy jest widoczny).
        if (!hasBackground(inst)) q.mid?.let { px -> lock.withLock { processPrice(inst, px, q.timestamp) } }
    }

    // ───────────── Rdzeń: wyjścia, świece, sygnały ─────────────

    private suspend fun processPrice(inst: String, px: Double, tsRaw: Long) {
        // Znacznik z przyszłości (zły zegar źródła) zablokowałby świece – przycinamy do teraz.
        val ts = minOf(tsRaw, System.currentTimeMillis() + 2_000)
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
        aiManage(inst, px, ts)
        countTick(inst, ts)
        for (tf in Learner.TIMEFRAMES) {
            val a = agg(inst, tf)
            val closed = a.onTick(px, ts) ?: continue
            if (tf == Learner.M1) onM1Closed(inst, a.closed())
            // Instrumenty bez historii (lub bez cen w tle): zapisujemy świece – nauka rośnie z każdym dniem i przetrwa restart.
            if (!hasBackground(inst) || inst in noHistory) runCatching { barStore.append(inst, tf, closed) }
            val (sent, _) = news.sentimentFor(aliasesFor(inst))
            val l = learner(inst)
            val sig = l.onBar(tf, a.closed(), sent, spreadOf(inst))
            if (l.championBarMs == tf) l.championNote()?.let { msg("ℹ️ $inst: $it") }
            updateLearningHud()
            if (sig != null) handleSignal(inst, sig, px)
        }
        // Wszystkie strategie działają razem; pierwsza z sygnałem otwiera pozycję, pozostałe czekają aż się zamknie.
        orbCheck(inst, px, ts)
        realtimeCheck(inst, px, ts)
        rangeCheck(inst, px, ts)
        aiPredictTick(inst, px, ts)
    }

    private suspend fun handleSignal(inst: String, sig: Signal, px: Double) {
        val learner = learner(inst)
        sessionBlock(inst, System.currentTimeMillis())?.let { msg("✋ $inst: sygnał pominięty – $it"); return }
        msg("📈 $inst ${sig.side} [${learner.champion?.name}]: ${sig.reason}")
        // Jedna pozycja naraz: gdy jakaś strategia już jest w rynku, sygnał innej czeka (bez odwracania).
        risk.position(inst)?.let { msg("✋ $inst: sygnał TURNIEJ ${sig.side} pominięty – już w pozycji (${stratName(it.entryReason)})"); return }
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
                "(stały lot %s, strata przy SL ≈%.2f, %s)\n🎯 E %.2f • SL %.2f • TP %s".format(
                    BigDecimal.valueOf(o.quantity).toPlainString(), abs(o.referencePrice - o.stopPrice) * o.quantity * o.pointValue, evText,
                    o.referencePrice + sh, o.stopPrice + sh, o.takeProfit?.let { "%.2f".format(it + sh) } ?: "trailing"
                )
        )
    }

    private suspend fun buildOrder(
        symbol: String, side: Side, price: Double, stop: Double, takeProfit: Double?, reason: String, pointValue: Double?,
        tier: Int = 1, structural: Boolean = false
    ): OrderRequest? {
        risk.closingOrder(symbol, side, price)?.let { return it.copy(reason = "$reason (zamknięcie)") }
        dataBlock(symbol)?.let { why ->
            if (rtNotes.add("data:$symbol:$why:${System.currentTimeMillis() / 60_000}")) msg("📡 $symbol: $reason – wstrzymane: $why")
            return null
        }
        val pv = pointValue ?: run {
            if (warned.add("pv:$symbol")) msg("Otwórz raz bilet $symbol w XTB – bot musi poznać wartość kontraktu")
            return null
        }
        // Szerszy SL: min. MIN_STOP_ATR×ATR(1m), żeby zwykłe szarpnięcie świecy nie wybijało pozycji tuż przed odbiciem.
        val atr1 = aggregators["$symbol@${Learner.M1}"]?.closed()?.takeLast(60)?.let { Indicators.atr(it) }
        var stop = stop
        var takeProfit = takeProfit
        // SL w złotówkach: strata przy SL zawsze 15–20 PLN (stały lot) – miejsce na szarpnięcie i odbicie świecy.
        run {
            val d = if (side == Side.BUY) 1 else -1
            val perPrice = FIXED_LOT * pv // PLN za ruch ceny o 1,0
            if (perPrice > 0) {
                val loss = abs(price - stop) * perPrice
                if (structural && loss > SL_MAX_PLN) {
                    // SL strukturalny (za sweepem) – NIE przyciągamy go bliżej, bo wtedy staje się płynnością dla rynku.
                    smcNote[symbol] = "punkt zanegowania za daleko: SL %.0f zł > %.0f zł – odpuszczam".format(loss, SL_MAX_PLN)
                    return null
                }
                val target = loss.coerceIn(SL_MIN_PLN, SL_MAX_PLN)
                if (abs(loss - target) > 0.01) stop = price - d * target / perPrice
            }
        }
        val minDist = price * risk.config.minStopDistancePct
        if (abs(price - stop) < minDist) {
            msg("✋ $symbol: stop %.2f od ceny – za blisko (min. %.2f)".format(abs(price - stop), minDist))
            return null
        }
        // Stały wolumen dla KAŻDEJ transakcji (zamiast skalowania wg % ryzyka). Najpierw: czy XTB w ogóle przyjmie depozyt.
        val qty = FIXED_LOT
        val funds = AutoExecutionService.screenQuote.value?.takeIf { it.isFresh() && it.instrument == symbol }?.availableFunds
            ?: AutoExecutionService.lastAccountFunds
        val margin = qty * price * pv * MARGIN_RATE_EST
        if (funds != null && margin > funds * 0.97) {
            if (rtNotes.add("margin:$symbol:${System.currentTimeMillis() / 60_000}")) {
                msg("✋ $symbol: na %s lota potrzeba ≈%.0f depozytu, dostępne %.0f – pomijam".format(
                    BigDecimal.valueOf(qty).toPlainString(), margin, funds))
            }
            return null
        }
        // TP zamyka z zyskiem, SL ze stratą: TP = TP_R × odległość SL (SL 15–20 zł → TP ok. 22–30 zł).
        val d = if (side == Side.BUY) 1 else -1
        val tp = if (structural && takeProfit != null) takeProfit else price + d * TP_R * abs(price - stop)
        if (structural) {
            val rr = abs(tp - price) / abs(price - stop)
            if (rr < SmcEngine.MIN_RR) {
                smcNote[symbol] = "po dociągnięciu SL do 15 zł R:R 1:%.1f < 1:%.1f – odpuszczam".format(rr, SmcEngine.MIN_RR)
                return null
            }
            return OrderRequest(symbol, side, qty, price, stop, reason, pointValue = pv, takeProfit = tp, tier = tier, fixedSize = true)
        }
        traderCheck(symbol, side, price, stop, tp)?.let { why ->
            if (rtNotes.add("trader:$symbol:$why:${System.currentTimeMillis() / 60_000}")) msg("🎩 $symbol: $reason – odpuszczam: $why")
            traderNote[symbol] = why
            return null
        }
        traderNote.remove(symbol)
        return OrderRequest(symbol, side, qty, price, stop, reason, pointValue = pv, takeProfit = if (BROKER_EXITS_ONLY) tp else takeProfit, tier = tier, fixedSize = true)
    }

    private val traderNote = java.util.concurrent.ConcurrentHashMap<String, String>()

    // ───────────── 🏦 SMC: sweep płynności → CHoCH → wejście, SL za ekstremum, TP na przeciwnej płynności ─────────────

    private val smc = SmcEngine(allSessions = TRADE_ALL_SESSIONS)
    private val smcResult = java.util.concurrent.ConcurrentHashMap<String, SmcEngine.Result>()
    private val smcNote = java.util.concurrent.ConcurrentHashMap<String, String>()
    private val smcTaken = java.util.Collections.synchronizedSet(LinkedHashSet<String>())
    private val smcSessionCount = HashMap<String, Int>()

    private fun smcEligible(inst: String) = inst.contains("GOLD", ignoreCase = true) || inst.contains("XAU", ignoreCase = true)

    /** Co sekundę (zegar świec): ocena SMC i ewentualne wejście. */
    private suspend fun smcTick(inst: String, px: Double, now: Long) {
        if (!smcEligible(inst)) return
        val m1 = aggregators["$inst@${Learner.M1}"]?.closed() ?: return
        val m5 = aggregators["$inst@${Learner.M5}"]?.closed().orEmpty()
        val q = AutoExecutionService.screenQuote.value?.takeIf { it.isFresh() && it.instrument == inst }
        val sh = q?.let { basis(inst, it) } ?: 0.0
        val res = smc.evaluate(m1, m5, px, spreadOf(inst), now, sh)
        smcResult[inst] = res
        val s = res.setup ?: return
        if (s.key in smcTaken) return
        val sessKey = "$inst:${res.session}:${now / 86_400_000L}"
        val n = smcSessionCount[sessKey] ?: 0
        if (n >= SMC_MAX_PER_SESSION) { smcNote[inst] = "limit $SMC_MAX_PER_SESSION wejść w tej sesji – bez overtradingu"; return }
        risk.position(inst)?.let { smcNote[inst] = "setup jest, ale już w pozycji (${stratName(it.entryReason)})"; return }
        sessionBlock(inst, now)?.let { smcNote[inst] = it; return }
        if (risk.cooldownLeftMs() > 0) { smcNote[inst] = "przerwa po stracie ${risk.cooldownLeftMs() / 1000} s"; return }
        smcTaken.add(s.key); while (smcTaken.size > 100) smcTaken.remove(smcTaken.first())
        smcNote.remove(inst)
        val order = buildOrder(inst, s.side, px, s.stop, s.target, s.reason, pointValue(inst), Tier.AGGRESSIVE, structural = true) ?: run {
            msg("🏦 $inst: ${s.reason} – ${smcNote[inst] ?: "odrzucone"}"); return
        }
        smcSessionCount[sessKey] = n + 1
        msg("🏦 $inst: ${s.reason} – WCHODZĘ")
        decisionCard(inst, order, Signal(s.side, order.stopPrice, order.takeProfit ?: s.target, s.reason, Tier.AGGRESSIVE), ev = Double.NaN)
        engine.submit(order)
    }

    private fun smcPlan(inst: String, sh: Double): String {
        if (!smcEligible(inst)) return "• 🏦 SMC: tylko złoto (XAU/GOLD)"
        val r = smcResult[inst] ?: return "• 🏦 SMC: zbieram dane…"
        fun f(v: Double?) = v?.let { "%.2f".format(it + sh) } ?: "—"
        val lv = r.levels
        val head = "• 🏦 SMC: ${r.session.label} • H1 ${when (r.bias) { 1 -> "↑ (HH/HL)"; -1 -> "↓ (LH/LL)"; else -> "↔ brak struktury" }}"
        val levels = lv?.let { "   Asia H/L ${f(it.asiaHigh)} / ${f(it.asiaLow)} • PDH/PDL ${f(it.pdh)} / ${f(it.pdl)} • swing 1h ${f(it.swingHigh)} / ${f(it.swingLow)}" }
        val note = "   ↳ " + (smcNote[inst] ?: r.note)
        return listOfNotNull(head, levels, note).plus(r.scenarios.map { "   ↳ $it" }).joinToString("\n")
    }

    /**
     * „Doświadczony trader” – filtr każdego wejścia (wszystkie strategie). Null = wolno wejść, inaczej powód.
     *  1. Nie gra pod prąd silnego trendu 5m (EMA20 vs EMA50 > 0,5×ATR5).
     *  2. Nie kupuje pod oporem / nie sprzedaje nad wsparciem: TP musi się zmieścić przed ekstremum ostatnich 60 świec 1m.
     *  3. Nie goni: świeca 1m już > 2×ATR w stronę wejścia albo cena > 2,5×ATR od EMA20(1m).
     *  4. Rynek ma sens: spread ≤ 25% ATR(1m), zmienność nie martwa (ATR ≥ 3× spread).
     */
    private fun traderCheck(symbol: String, side: Side, price: Double, stop: Double, tp: Double): String? {
        val dir = if (side == Side.BUY) 1 else -1
        val m1 = aggregators["$symbol@${Learner.M1}"]?.closed().orEmpty()
        val m5 = aggregators["$symbol@${Learner.M5}"]?.closed().orEmpty()
        val now = System.currentTimeMillis()
        smc.newsBlackout(now)?.let { return it }
        if (!TRADE_ALL_SESSIONS) smc.session(now).let { if (!it.tradable) return "${it.label} – handluję tylko w killzone (Londyn 09–12, NY 14:30–17)" }
        val atr1 = Indicators.atr(m1.takeLast(60)) ?: return null // za mało danych – nie blokujemy
        val spread = spreadOf(symbol)
        if (spread > 0 && spread > 0.25 * atr1) return "spread %.2f to > 25%% ruchu świecy – za drogo".format(spread)
        if (spread > 0 && atr1 < 3 * spread) return "rynek martwy (ATR %.2f < 3× spread)".format(atr1)
        if (m5.size >= 60) {
            val c5 = m5.map { it.close }
            val e20 = Indicators.ema(c5, 20); val e50 = Indicators.ema(c5, 50); val atr5 = Indicators.atr(m5.takeLast(60))
            if (e20 != null && e50 != null && atr5 != null && atr5 > 0) {
                val trend = (e20 - e50) / atr5
                if (trend * dir < -0.5) return "pod prąd trendu 5m (%s) – nie walczę z rynkiem".format(if (trend > 0) "wzrostowy" else "spadkowy")
            }
        }
        val cur = aggregators["$symbol@${Learner.M1}"]?.current()
        if (cur != null && (cur.close - cur.open) * dir > 2 * atr1) return "świeca już poszła %.1f×ATR – nie gonię".format(abs(cur.close - cur.open) / atr1)
        Indicators.ema(m1.map { it.close }.takeLast(60), 20)?.let { e ->
            if ((price - e) * dir > 2.5 * atr1) return "cena %.1f×ATR od średniej – za daleko, czekam na cofnięcie".format(abs(price - e) / atr1)
        }
        if (m1.size >= 30) {
            val last = m1.takeLast(60)
            val wall = if (dir > 0) last.maxOf { it.high } else last.minOf { it.low }
            val room = (wall - price) * dir
            val need = abs(tp - price)
            // Tylko gdy ekstremum jest przed nami (nie wybicie) – TP musi zmieścić się choć w 60% drogi.
            if (room > 0 && room < 0.6 * need) return "%s %.2f za blisko (miejsce %.2f, TP potrzebuje %.2f)".format(
                if (dir > 0) "opór" else "wsparcie", wall, room, need)
        }
        return null
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
        val line2 = "Tryb ${engine.mode.value.name} • $ALL_LABEL$kill"
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

        /** Stały wolumen każdej transakcji (loty XTB). */
        const val FIXED_LOT = 0.006
        /** Szacowany depozyt XTB dla złota/CFD (≈5% wartości kontraktu, 1:20) – do sprawdzenia środków przed wejściem. */
        const val MARGIN_RATE_EST = 0.05

        // ŚWIECE + PREDYKCJA AI
        const val AI_TAG = "AI"
        const val AI_PREDICT_AT_MS = 50_000L     // prognoza „późna” ~10 s przed zamknięciem świecy 1m
        const val AI_EARLY_AT_MS = 30_000L       // wczesne prognozy od 30 s świecy…
        const val AI_EARLY_STEP_MS = 5_000L      // …co 5 s
        const val AI_EARLY_MIN_PROB = 0.60       // wcześniejsze wejście wymaga większej pewności
        const val EARLY_EXIT_BODY_ATR = 0.30     // wcześniejsze wyjście: 2 świece przeciw, korpus ≥ 0,3×ATR
        const val ALL_LABEL = "🏦 SMC XAU + RT/ORB + ZAKRES + TURNIEJ (całą dobę, R:R ≥ 1:2,5)"
        const val AI_MIN_GRADED = 20             // min. ocenionych prognoz na żywo
        const val AI_MIN_ACC = 0.55              // min. trafność z ostatnich 20
        const val AI_MIN_PROB = 0.58             // min. szansa kierunku
        const val AI_MIN_BODY_SPREADS = 2.0      // oczekiwany ruch ≥ 2× spread
        const val AI_STOP_ATR = 1.6              // twardy SL 1,6×ATR (min. 3× spread) – miejsce na odbicie
        const val MIN_STOP_ATR = 1.5             // każda strategia: SL min. 1,5×ATR(1m)
        const val IMPULSE_MIN_ATR = 0.7          // przesuwanie TP/SL za impulsem od zysku 0,7×ATR
        const val SL_MIN_PLN = 15.0              // strata przy SL: min. 15 PLN…
        const val SL_MAX_PLN = 20.0              // …maks. 20 PLN (przy stałym locie)
        /** false = bot sam nie zamyka pozycji na minusie (stratę zamyka tylko SL 15–20 PLN / koniec sesji). */
        const val EARLY_LOSS_EXITS = false
        /** Pozycję zamyka WYŁĄCZNIE Stop Loss (przesuwany za zyskiem); bez TP, bez wyjść bota, bez zamykania na koniec sesji. */
        const val BROKER_EXITS_ONLY = true       // zamyka wyłącznie XTB: TP (zysk) albo SL (strata)
        const val TP_R = 2.5                     // TP = 2,5 × odległość SL (min. R:R 1:2,5)
        const val AI_PREDICTOR_ENTRIES = false   // model świec tylko jako informacja – wejścia daje SMC
        const val SMC_MAX_PER_SESSION = 2        // bez overtradingu: maks. 2 wejścia SMC na sesję
        const val TRADE_ALL_SESSIONS = true      // handel cały czas (Azja, Londyn, NY); blackout tylko wokół danych USA
        const val BE_AT_PLN = 8.0                // od 8 zł zysku SL na wejście (+spread)…
        const val TRAIL_PLN = 10.0               // …i dalej 10 zł za najlepszą ceną
        const val BROKER_STEP_PLN = 2.0          // SL w XTB przesuwany o ≥ 2 zł…
        const val BROKER_SYNC_EVERY_MS = 20_000L // …maks. co 20 s (edycja pozycji to kilka sekund na ekranie)
        const val AI_WRONG_CANDLE_MIN_LOSS_R = 0.5 // „świeca przeciw” zamyka dopiero przy stracie ≥ 0,5R
        const val AI_SAFETY_TP_R = 3.0           // TP w XTB daleko (3R) – właściwe wyjście prowadzi bot
        const val AI_VTP_ATR = 1.0               // TP wirtualny ≥ 1×ATR (lub prognozowany korpus, jeśli większy)
        const val AI_MAX_EXTRA_BARS = 3          // świeca prognozy + 3 – potem wyjście czasowe
        const val AI_IMPULSE_ATR = 0.6           // impuls: zasięg świecy ≥ 0,6×ATR
        const val AI_TRAIL_ATR = 0.35            // ciasny trailing 0,35×ATR za najlepszą ceną
        const val AI_BROKER_EVERY_MS = 30_000L   // przesuwanie SL/TP w XTB maks. co 30 s
        const val AI_BROKER_STEP_ATR = 0.5       // …i o ≥ 0,5×ATR

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
