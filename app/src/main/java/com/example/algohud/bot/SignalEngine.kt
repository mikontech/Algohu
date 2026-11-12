package com.example.algohud.bot

import com.example.algohud.core.Bar
import kotlin.math.abs

/**
 * Jeden moduł decyzyjny: łączy trzy warstwy w wynik punktowy LONG i SHORT i wydaje decyzję LONG / SHORT / BRAK.
 *  1. Trend wyższego interwału (H1 i M15 składane z M5): struktura HH/HL vs LH/LL, EMA 50/200, nachylenie EMA.
 *  2. Momentum na interwale wejścia (M1 i M5): RSI, histogram MACD.
 *  3. Price action / SMC na M1: BOS/CHoCH, liquidity sweep, odrzucenie od strefy popytu/podaży.
 * Wejście tylko gdy wynik strony ≥ próg, ma przewagę nad stroną przeciwną ORAZ kierunek zgadza się z trendem wyższego
 * interwału (wyjątek: świeży, wyraźny CHoCH). Filtry: spread i ATR poza normalnym zakresem.
 * Czysta logika – bez Androida, bez stanu. Wszystkie wagi i progi: [BotConfig.Signal].
 */
object SignalEngine {
    enum class Decision { LONG, SHORT, NONE }

    data class Layer(val name: String, val long: Double, val short: Double, val notes: List<String>)

    data class Result(
        val decision: Decision,
        val longScore: Double,
        val shortScore: Double,
        /** Trend wyższego interwału: +1 ↑, −1 ↓, 0 brak. */
        val htfTrend: Int,
        /** Wynik warstwy trendu (long − short). */
        val htfScore: Double,
        /** Kierunek świeżego CHoCH na M1 (0 = brak). */
        val chochDir: Int,
        /** Ostatnie wybicie struktury M1 (także starsze) – do wykrywania odwrócenia. */
        val structure: Structure.State?,
        val atrM1: Double?,
        val atrM5: Double?,
        /** Filtr blokujący wszystkie wejścia (spread, ATR, brak danych); null = brak blokady. */
        val blocked: String?,
        val layers: List<Layer>,
        /** Dlaczego taka decyzja – jedna linia do HUD i dziennika. */
        val reason: String
    ) {
        fun score(dir: Int) = if (dir > 0) longScore else shortScore
        val htfLabel: String get() = when (htfTrend) { 1 -> "↑ wzrostowy"; -1 -> "↓ spadkowy"; else -> "↔ brak trendu" }

        /** Czy trend wyższego interwału pozwala na wejście w kierunku [dir] (zgodny albo wyraźny CHoCH). */
        fun htfAllows(dir: Int): Boolean =
            htfTrend == dir || chochDir == dir || (htfTrend == 0 && BotConfig.Signal.ALLOW_NEUTRAL_HTF)

        /**
         * Potwierdzenie wejścia innej strategii w kierunku [dir]: null = wolno, inaczej powód odmowy.
         * Niższy próg niż dla własnego wejścia SCORE, ale te same filtry i ta sama zgodność z trendem.
         */
        fun confirms(dir: Int, minScore: Double = BotConfig.Signal.CONFIRM_SCORE, minLead: Double = BotConfig.Signal.CONFIRM_LEAD): String? {
            blocked?.let { return it }
            val mine = score(dir)
            val other = score(-dir)
            val name = if (dir > 0) "LONG" else "SHORT"
            return when {
                mine < minScore -> "score $name %.1f < %.1f".format(mine, minScore)
                mine - other < minLead -> "score $name %.1f bez przewagi nad przeciwnym %.1f".format(mine, other)
                !htfAllows(dir) -> "trend wyższego interwału $htfLabel – $name tylko po wyraźnym CHoCH"
                else -> null
            }
        }

        fun line(): String = "LONG %.1f • SHORT %.1f • H1/M15 %s".format(longScore, shortScore, htfLabel)
    }

    fun evaluate(m1: List<Bar>, m5: List<Bar>, spread: Double): Result {
        val c = BotConfig.Signal
        val atr1 = Indicators.atr(m1.takeLast(60))
        val atr5 = Indicators.atr(m5.takeLast(60))
        if (m1.size < 60 || m5.size < 60 || atr1 == null || atr5 == null || atr1 <= 0 || atr5 <= 0) {
            val why = "zbieram dane (M1 ${m1.size}/60, M5 ${m5.size}/60)"
            return Result(Decision.NONE, 0.0, 0.0, 0, 0.0, 0, null, atr1, atr5, why, emptyList(), why)
        }
        val trend = trendLayer(m5)
        val mom = momentumLayer(m1, m5)
        val st = Structure.analyze(m1.takeLast(150))
        val n = minOf(m1.size, 150)
        val pa = priceActionLayer(m1, st, n)
        val long = trend.long + mom.long + pa.long
        val short = trend.short + mom.short + pa.short
        val htfScore = trend.long - trend.short
        val htfTrend = when { htfScore >= c.HTF_TREND_MIN -> 1; htfScore <= -c.HTF_TREND_MIN -> -1; else -> 0 }
        val ev = st.lastEvent
        val choch = if (ev != null && ev.choch && n - 1 - ev.index <= c.STRUCTURE_FRESH_BARS) ev.dir else 0

        val blocked = filter(m1, spread, atr5)
        val layers = listOf(trend, mom, pa)
        val draft = Result(Decision.NONE, long, short, htfTrend, htfScore, choch, st, atr1, atr5, blocked, layers, "")
        val dir = if (long >= short) 1 else -1
        val name = if (dir > 0) "LONG" else "SHORT"
        val refuse = draft.confirms(dir, c.ENTRY_SCORE, c.ENTRY_LEAD)
        val detail = layers.joinToString(" | ") { l -> "${l.name} %+.1f".format(l.long - l.short) + if (l.notes.isEmpty()) "" else " (${l.notes.joinToString(", ")})" }
        return if (refuse == null) draft.copy(
            decision = if (dir > 0) Decision.LONG else Decision.SHORT,
            reason = "$name %.1f vs %.1f, trend %s%s • $detail".format(draft.score(dir), draft.score(-dir), draft.htfLabel, if (htfTrend != dir) " (wejście po CHoCH)" else "")
        ) else draft.copy(reason = "BRAK: $refuse • $detail")
    }

    // ── Warstwa 1: trend wyższego interwału ──
    private fun trendLayer(m5: List<Bar>): Layer {
        val c = BotConfig.Signal
        val notes = ArrayList<String>()
        var long = 0.0
        var short = 0.0
        fun add(v: Int, w: Double, note: String) {
            if (v > 0) { long += w; notes += "$note↑" } else if (v < 0) { short += w; notes += "$note↓" }
        }
        val h1 = Indicators.resample(m5, 3_600_000L, minParts = 6)
        val m15 = Indicators.resample(m5, 900_000L, minParts = 2)
        add(swingStructure(h1), c.W_H1_STRUCTURE, "H1 struktura")
        emaTrend(h1)?.let { (v, full) -> add(v, if (full) c.W_H1_EMA else c.W_H1_EMA / 2, if (full) "H1 EMA50/200" else "H1 cena/EMA50") }
        add(emaSlope(h1), c.W_H1_SLOPE, "H1 nachylenie")
        add(swingStructure(m15), c.W_M15_STRUCTURE, "M15 struktura")
        emaTrend(m15)?.let { (v, full) -> add(v, if (full) c.W_M15_EMA else c.W_M15_EMA / 2, if (full) "M15 EMA50/200" else "M15 cena/EMA50") }
        return Layer("trend", long, short, notes)
    }

    /** +1 = wyższy szczyt i wyższy dołek (HH/HL), −1 = LH/LL, 0 = brak czytelnej struktury. */
    fun swingStructure(bars: List<Bar>): Int {
        if (bars.size < 8) return 0
        val sw = Structure.swings(bars)
        val highs = sw.filter { it.high }
        val lows = sw.filter { !it.high }
        if (highs.size < 2 || lows.size < 2) return 0
        val hh = highs[highs.size - 1].price > highs[highs.size - 2].price
        val hl = lows[lows.size - 1].price > lows[lows.size - 2].price
        return when { hh && hl -> 1; !hh && !hl -> -1; else -> 0 }
    }

    /**
     * (kierunek, czy pełne EMA50/200). Przy krótkiej historii zastępczo: cena względem EMA50,
     * a gdy świec jest mniej niż 50 – względem EMA z połowy dostępnych świec (min. 10 świec), żeby bot
     * nie czekał dwóch dni na pierwszy odczyt trendu.
     */
    private fun emaTrend(bars: List<Bar>): Pair<Int, Boolean>? {
        val c = BotConfig.Signal
        val closes = bars.map { it.close }
        val slow = Indicators.ema(closes, c.EMA_SLOW)
        val fast = Indicators.ema(closes, c.EMA_FAST)
        if (fast != null && slow != null) return sign(fast - slow) to true
        if (fast != null) return sign(closes.last() - fast) to false
        if (closes.size < 10) return null
        val short = Indicators.ema(closes, closes.size / 2) ?: return null
        return sign(closes.last() - short) to false
    }

    private fun emaSlope(bars: List<Bar>): Int {
        val c = BotConfig.Signal
        // przy krótkiej historii – krótsza średnia (połowa dostępnych świec), jak w emaTrend
        val period = if (bars.size > c.EMA_FAST + c.SLOPE_BARS) c.EMA_FAST else bars.size / 2
        if (period < 5) return 0
        val e = Indicators.emaSeries(bars.map { it.close }, period)
        val atr = Indicators.atr(bars.takeLast(60), minOf(14, bars.size - 1)) ?: return 0
        if (e.size <= c.SLOPE_BARS || atr <= 0) return 0
        val d = e.last() - e[e.size - 1 - c.SLOPE_BARS]
        return if (abs(d) >= c.SLOPE_MIN_ATR * atr) sign(d) else 0
    }

    // ── Warstwa 2: momentum ──
    private fun momentumLayer(m1: List<Bar>, m5: List<Bar>): Layer {
        val c = BotConfig.Signal
        val notes = ArrayList<String>()
        var long = 0.0
        var short = 0.0
        fun rsi(bars: List<Bar>, w: Double, tf: String) {
            val r = Indicators.rsi(bars.takeLast(250).map { it.close }, c.RSI_PERIOD) ?: return
            if (r >= c.RSI_BULL && r < c.RSI_OVERBOUGHT) { long += w; notes += "$tf RSI %.0f".format(r) }
            else if (r <= c.RSI_BEAR && r > c.RSI_OVERSOLD) { short += w; notes += "$tf RSI %.0f".format(r) }
        }
        fun macd(bars: List<Bar>, w: Double, tf: String) {
            val h = Indicators.macdHist(bars.takeLast(250).map { it.close }, c.MACD_FAST, c.MACD_SLOW, c.MACD_SIGNAL)
            if (h.size < 2) return
            val now = h.last()
            val growing = abs(now) > abs(h[h.size - 2])
            val pts = if (growing) w else w / 2 // histogram po dobrej stronie, ale słabnie = połowa punktów
            if (now > 0) { long += pts; notes += "$tf MACD+" + if (growing) "↑" else "" }
            else if (now < 0) { short += pts; notes += "$tf MACD−" + if (growing) "↓" else "" }
        }
        rsi(m5, c.W_M5_RSI, "M5"); macd(m5, c.W_M5_MACD, "M5")
        macd(m1, c.W_M1_MACD, "M1"); rsi(m1, c.W_M1_RSI, "M1")
        return Layer("momentum", long, short, notes)
    }

    // ── Warstwa 3: price action / SMC ──
    private fun priceActionLayer(m1: List<Bar>, st: Structure.State, n: Int): Layer {
        val c = BotConfig.Signal
        val notes = ArrayList<String>()
        var long = 0.0
        var short = 0.0
        st.lastEvent?.takeIf { n - 1 - it.index <= c.STRUCTURE_FRESH_BARS }?.let { ev ->
            val w = if (ev.choch) c.W_CHOCH else c.W_BOS
            val tag = (if (ev.choch) "CHoCH" else "BOS") + if (ev.dir > 0) "↑" else "↓"
            if (ev.dir > 0) long += w else short += w
            notes += tag
        }
        sweep(m1).let { s -> if (s > 0) { long += c.W_SWEEP; notes += "sweep dołka" } else if (s < 0) { short += c.W_SWEEP; notes += "sweep szczytu" } }
        rejection(m1).let { r -> if (r > 0) { long += c.W_REJECTION; notes += "odrzucenie popytu" } else if (r < 0) { short += c.W_REJECTION; notes += "odrzucenie podaży" } }
        return Layer("price action", long, short, notes)
    }

    /** +1 = zebrana płynność pod dołkiem i powrót nad niego (byczo), −1 = lustrzanie nad szczytem. */
    fun sweep(m1: List<Bar>): Int {
        val c = BotConfig.Signal
        if (m1.size < c.SWEEP_RECENT + c.SWEEP_LOOKBACK) return 0
        val recent = m1.takeLast(c.SWEEP_RECENT)
        val prior = m1.subList(m1.size - c.SWEEP_RECENT - c.SWEEP_LOOKBACK, m1.size - c.SWEEP_RECENT)
        val lo = prior.minOf { it.low }
        val hi = prior.maxOf { it.high }
        val close = m1.last().close
        val down = recent.minOf { it.low } < lo && close > lo
        val up = recent.maxOf { it.high } > hi && close < hi
        return when { down && !up -> 1; up && !down -> -1; else -> 0 }
    }

    /** +1 = ostatnia świeca odrzuciła strefę popytu długim dolnym knotem, −1 = strefę podaży górnym. */
    fun rejection(m1: List<Bar>): Int {
        val c = BotConfig.Signal
        if (m1.size < c.ZONE_LOOKBACK) return 0
        val zone = m1.takeLast(c.ZONE_LOOKBACK)
        val lo = zone.minOf { it.low }
        val hi = zone.maxOf { it.high }
        val b = m1.last()
        val range = b.high - b.low
        if (range <= 0 || hi <= lo) return 0
        val lower = minOf(b.open, b.close) - b.low
        val upper = b.high - maxOf(b.open, b.close)
        val mid = (b.high + b.low) / 2
        return when {
            b.low <= lo + c.ZONE_FRACTION * (hi - lo) && lower >= c.REJECTION_WICK * range && b.close > mid -> 1
            b.high >= hi - c.ZONE_FRACTION * (hi - lo) && upper >= c.REJECTION_WICK * range && b.close < mid -> -1
            else -> 0
        }
    }

    // ── Filtry ──
    private fun filter(m1: List<Bar>, spread: Double, atr5: Double): String? {
        val c = BotConfig.Signal
        if (c.MAX_SPREAD_ABS > 0 && spread > c.MAX_SPREAD_ABS) return "spread %.2f > limit %.2f".format(spread, c.MAX_SPREAD_ABS)
        if (spread > c.MAX_SPREAD_ATR_M5 * atr5) return "spread %.2f za szeroki (> %.0f%% ATR M5)".format(spread, c.MAX_SPREAD_ATR_M5 * 100)
        val ratio = atrRatio(m1) ?: return null
        return when {
            ratio < c.ATR_RATIO_MIN -> "zmienność za niska (ATR %.1f× normy)".format(ratio)
            ratio > c.ATR_RATIO_MAX -> "zmienność za wysoka (ATR %.1f× normy)".format(ratio)
            else -> null
        }
    }

    /** Średni true range ostatnich 14 świec M1 względem średniej z [BotConfig.Signal.ATR_BASE_BARS]. */
    fun atrRatio(m1: List<Bar>): Double? {
        val bars = m1.takeLast(BotConfig.Signal.ATR_BASE_BARS + 1)
        if (bars.size < 60) return null
        val trs = (1 until bars.size).map { i ->
            val pc = bars[i - 1].close
            maxOf(bars[i].high - bars[i].low, abs(bars[i].high - pc), abs(bars[i].low - pc))
        }
        val base = trs.average()
        return if (base > 0) trs.takeLast(14).average() / base else null
    }

    private fun sign(v: Double) = if (v > 0) 1 else if (v < 0) -1 else 0
}
