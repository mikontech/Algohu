package com.example.algohud.bot

import com.example.algohud.core.Bar
import com.example.algohud.core.Side
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * "Dołki i szczyty": odbicie od wstęgi Bollingera po wyprzedaniu/wykupieniu (RSI).
 * Long: poprzednia świeca zamknęła się POD dolną wstęgą przy RSI < rsiLow, bieżąca wraca nad wstęgę i jest wzrostowa
 *       (potwierdzenie odbicia). Stop pod lokalnym dołkiem, cel – górna wstęga ("sprzedaj na górze").
 * Short: lustrzanie przy górnej wstędze.
 */
class ReversalStrategy(
    private val period: Int = 20,
    private val k: Double = 2.0,
    private val rsiLow: Double = 30.0,
    private val rsiHigh: Double = 70.0,
    private val stopAtr: Double = 0.5,
    private val minRewardR: Double = 1.0,
    private val newsVeto: Double = 0.5,
    private val cooldownBars: Int = 3,
    private val barMs: Long = 5 * 60_000L
) : Strategy {
    private var lastSignalBar = 0L

    override fun onBar(bars: List<Bar>, sentiment: Double): Signal? {
        if (bars.size < period + 16) return null
        val last = bars.last()
        val prev = bars[bars.size - 2]
        if (last.start - lastSignalBar < cooldownBars * barMs) return null
        val closes = bars.map { it.close }
        val window = closes.subList(closes.size - 1 - period, closes.size - 1) // do poprzedniej świecy
        val sma = window.average()
        val sd = sqrt(window.sumOf { (it - sma) * (it - sma) } / window.size)
        if (sd <= 0) return null
        val lower = sma - k * sd
        val upper = sma + k * sd
        val rsiPrev = Indicators.rsi(closes.dropLast(1)) ?: return null
        val atr = Indicators.atr(bars)?.takeIf { it > 0 } ?: return null
        val recent = bars.takeLast(5)

        val long = prev.close < lower && rsiPrev < rsiLow && last.close > lower && last.close > last.open &&
            sentiment > -newsVeto
        val short = prev.close > upper && rsiPrev > rsiHigh && last.close < upper && last.close < last.open &&
            sentiment < newsVeto
        if (!long && !short) return null

        val entry = last.close
        val signal = if (long) {
            val stop = recent.minOf { it.low } - stopAtr * atr
            val risk = entry - stop
            if (risk <= 0 || upper - entry < minRewardR * risk) return null
            Signal(Side.BUY, stop, upper, "odbicie od dołka (BB %.1f, RSI %.0f)".format(k, rsiPrev))
        } else {
            val stop = recent.maxOf { it.high } + stopAtr * atr
            val risk = stop - entry
            if (risk <= 0 || entry - lower < minRewardR * risk) return null
            Signal(Side.SELL, stop, lower, "odbicie od szczytu (BB %.1f, RSI %.0f)".format(k, rsiPrev))
        }
        lastSignalBar = last.start
        return signal
    }

    override fun describeWait(bars: List<Bar>): String? {
        if (bars.size < period) return null
        val closes = bars.takeLast(period).map { it.close }
        val sma = closes.average()
        val sd = sqrt(closes.sumOf { (it - sma) * (it - sma) } / closes.size)
        return "LONG po spadku < %.2f i odbiciu • SHORT po wzroście > %.2f i zawróceniu".format(sma - k * sd, sma + k * sd)
    }
}

/**
 * "Cofnięcie w trendzie": w trendzie (EMA20 > EMA50, EMA50 rośnie) czeka na cofnięcie do EMA20
 * i wzrostową świecę odbicia – wejście z trendem po lepszej cenie. Short lustrzanie.
 */
class PullbackStrategy(
    private val fast: Int = 20,
    private val slow: Int = 50,
    private val stopAtr: Double = 0.5,
    private val rewardR: Double = 2.0,
    private val newsVeto: Double = 0.3,
    private val cooldownBars: Int = 3,
    private val barMs: Long = 5 * 60_000L
) : Strategy {
    private var lastSignalBar = 0L

    private fun trend(closes: List<Double>): Int? {
        val f = Indicators.ema(closes, fast) ?: return null
        val sNow = Indicators.ema(closes, slow) ?: return null
        val sPrev = Indicators.ema(closes.dropLast(5), slow) ?: return null
        val last = closes.last()
        return when {
            f > sNow && sNow > sPrev && last > sNow -> 1
            f < sNow && sNow < sPrev && last < sNow -> -1
            else -> 0
        }
    }

    override fun onBar(bars: List<Bar>, sentiment: Double): Signal? {
        if (bars.size < slow + 12) return null
        val last = bars.last()
        if (last.start - lastSignalBar < cooldownBars * barMs) return null
        val closes = bars.map { it.close }
        val t = trend(closes) ?: return null
        val emaF = Indicators.ema(closes, fast) ?: return null
        val atr = Indicators.atr(bars)?.takeIf { it > 0 } ?: return null
        val recent = bars.takeLast(5)
        val entry = last.close
        val sig = when {
            t == 1 && last.low <= emaF && last.close > emaF && last.close > last.open && sentiment > -newsVeto -> {
                val stop = recent.minOf { it.low } - stopAtr * atr
                Signal(Side.BUY, stop, entry + rewardR * (entry - stop), "cofnięcie do EMA%d w trendzie ↑".format(fast))
            }
            t == -1 && last.high >= emaF && last.close < emaF && last.close < last.open && sentiment < newsVeto -> {
                val stop = recent.maxOf { it.high } + stopAtr * atr
                Signal(Side.SELL, stop, entry - rewardR * (stop - entry), "cofnięcie do EMA%d w trendzie ↓".format(fast))
            }
            else -> null
        } ?: return null
        if (abs(entry - sig.stopPrice) <= 0) return null
        lastSignalBar = last.start
        return sig
    }

    override fun describeWait(bars: List<Bar>): String? {
        if (bars.size < slow + 12) return null
        val closes = bars.map { it.close }
        val emaF = Indicators.ema(closes, fast) ?: return null
        return when (trend(closes)) {
            1 -> "LONG po cofnięciu do EMA%d (%.2f) i świecy odbicia".format(fast, emaF)
            -1 -> "SHORT po podejściu do EMA%d (%.2f) i świecy zawrócenia".format(fast, emaF)
            else -> "brak wyraźnego trendu – czekam"
        }
    }
}

/**
 * "Zawężenie → wybicie": gdy zmienność (szerokość wstęg Bollingera) jest najniższa od [lookback] świec,
 * rynek "sprężynuje"; wejście w kierunku wybicia poza wstęgę. Stop na środku wstęg.
 */
class SqueezeStrategy(
    private val period: Int = 20,
    private val k: Double = 2.0,
    private val lookback: Int = 100,
    private val rewardR: Double = 2.0,
    private val cooldownBars: Int = 3,
    private val barMs: Long = 5 * 60_000L
) : Strategy {
    private var lastSignalBar = 0L

    private data class Bands(val mid: Double, val up: Double, val low: Double) {
        val width get() = if (mid != 0.0) (up - low) / mid else 0.0
    }

    private fun bands(closes: List<Double>, end: Int): Bands? {
        if (end + 1 < period) return null
        val w = closes.subList(end + 1 - period, end + 1)
        val m = w.average()
        val sd = sqrt(w.sumOf { (it - m) * (it - m) } / w.size)
        return Bands(m, m + k * sd, m - k * sd)
    }

    /** Czy świeca [end] była w zawężeniu (szerokość ≤ 110% minimum z [lookback] świec). */
    private fun squeezed(closes: List<Double>, end: Int): Boolean? {
        val now = bands(closes, end)?.width ?: return null
        var min = Double.MAX_VALUE
        for (j in end - lookback + 1..end) min = min(min, bands(closes, j)?.width ?: return null)
        return now <= min * 1.1
    }

    override fun onBar(bars: List<Bar>, sentiment: Double): Signal? {
        if (bars.size < lookback + period + 2) return null
        val last = bars.last()
        if (last.start - lastSignalBar < cooldownBars * barMs) return null
        val closes = bars.map { it.close }
        val prev = closes.size - 2
        if (squeezed(closes, prev) != true) return null
        val b = bands(closes, prev) ?: return null
        val entry = last.close
        val sig = when {
            entry > b.up -> Signal(Side.BUY, b.mid, entry + rewardR * (entry - b.mid), "wybicie po zawężeniu ↑")
            entry < b.low -> Signal(Side.SELL, b.mid, entry - rewardR * (b.mid - entry), "wybicie po zawężeniu ↓")
            else -> null
        } ?: return null
        lastSignalBar = last.start
        return sig
    }

    override fun describeWait(bars: List<Bar>): String? {
        if (bars.size < lookback + period + 2) return null
        val closes = bars.map { it.close }
        val end = closes.size - 1
        val b = bands(closes, end) ?: return null
        return if (squeezed(closes, end) == true)
            "zawężenie! LONG > %.2f • SHORT < %.2f".format(b.up, b.low)
        else "czekam na zawężenie zmienności"
    }
}

/** Statystyki z wagą świeżości: nowsze transakcje liczą się bardziej. */
data class LearnStats(val trades: Int, val nEff: Double, val avgR: Double, val winRate: Double, val tStat: Double)

/**
 * Wirtualny trader: gra jednym wariantem strategii na świecach (bez pieniędzy, wynik w R, ze spreadem).
 * Wyjścia jak w prawdziwym bocie: SL, TP, stop na wejście po +1R, potem trailing 1R; sygnał przeciwny = odwrócenie.
 * Gdy w jednej świecy padłby i SL, i TP – zakładamy SL (ostrożnie).
 */
class ShadowTrader(
    val name: String,
    private val strategy: Strategy,
    private val maxResults: Int = 3000,
    /** Jak w RiskManager: zbyt ciasne stopy są odrzucane, więc wirtualnie też ich nie gramy. */
    private val minStopPct: Double = 0.001,
    /** Okres półtrwania wagi wyniku (ms): transakcja sprzed 2 tygodni waży połowę świeżej. */
    private val halfLifeMs: Long = 14L * 24 * 3_600_000
) {
    private data class Sim(val side: Side, val entry: Double, var stop: Double, val initStop: Double, val tp: Double, var best: Double)

    private var pos: Sim? = null
    private val results = ArrayDeque<Pair<Long, Double>>() // (czas wyjścia, R)
    private var lastBarTime = 0L
    var errors = 0
        private set

    val openSide: Side? get() = pos?.side

    fun stats(): LearnStats {
        if (results.isEmpty()) return LearnStats(0, 0.0, 0.0, 0.0, 0.0)
        var sw = 0.0; var sw2 = 0.0; var swr = 0.0; var swWin = 0.0
        for ((t, r) in results) {
            val w = 0.5.pow((lastBarTime - t).coerceAtLeast(0L).toDouble() / halfLifeMs)
            sw += w; sw2 += w * w; swr += w * r
            if (r > 0) swWin += w
        }
        val mean = swr / sw
        var sv = 0.0
        for ((t, r) in results) {
            val w = 0.5.pow((lastBarTime - t).coerceAtLeast(0L).toDouble() / halfLifeMs)
            sv += w * (r - mean) * (r - mean)
        }
        val variance = sv / sw
        val nEff = sw * sw / sw2
        val t = if (variance > 0) mean / sqrt(variance / nEff) else 0.0
        return LearnStats(results.size, nEff, mean, swWin / sw, t)
    }

    fun describeWait(bars: List<Bar>): String? = runCatching { strategy.describeWait(bars) }.getOrNull()
    fun note(): String? = runCatching { strategy.lastNote() }.getOrNull()

    /** @param cost koszt transakcji w jednostkach ceny (spread). Zwraca sygnał, jeśli otworzył pozycję. */
    fun onBar(bars: List<Bar>, sentiment: Double, cost: Double): Signal? {
        val bar = bars.last()
        lastBarTime = bar.start
        pos?.let { p -> exitOnBar(p, bar, cost)?.let { r -> record(bar.start, r); pos = null } }
        // Błąd jednego wariantu nie może zatrzymać nauki pozostałych.
        val sig = try {
            strategy.onBar(bars, sentiment)
        } catch (t: Throwable) {
            errors++
            null
        } ?: return null
        if (abs(bar.close - sig.stopPrice) < bar.close * minStopPct) return null
        val p = pos
        if (p != null && p.side == sig.side) return null
        if (p != null) { record(bar.start, rAt(p, bar.close, cost)); pos = null }
        pos = Sim(sig.side, bar.close, sig.stopPrice, sig.stopPrice, sig.takeProfit, bar.close)
        return sig
    }

    private fun exitOnBar(p: Sim, bar: Bar, cost: Double): Double? {
        val r = abs(p.entry - p.initStop)
        if (r <= 0) return 0.0
        if (p.side == Side.BUY) {
            if (bar.low <= p.stop) return rAt(p, p.stop, cost)
            if (bar.high >= p.tp) return rAt(p, p.tp, cost)
            p.best = max(p.best, bar.high)
            if (p.best - p.entry >= r) p.stop = max(p.stop, max(p.entry, p.best - r))
        } else {
            if (bar.high >= p.stop) return rAt(p, p.stop, cost)
            if (bar.low <= p.tp) return rAt(p, p.tp, cost)
            p.best = min(p.best, bar.low)
            if (p.entry - p.best >= r) p.stop = min(p.stop, min(p.entry, p.best + r))
        }
        return null
    }

    private fun rAt(p: Sim, exit: Double, cost: Double): Double {
        val risk = abs(p.entry - p.initStop)
        return if (risk <= 0) 0.0 else ((exit - p.entry) * p.side.sign - cost) / risk
    }

    private fun record(time: Long, r: Double) {
        results.addLast(time to r)
        while (results.size > maxResults) results.removeFirst()
    }
}

data class Variant(val name: String, val barMs: Long, val factory: () -> Strategy)

fun tfLabel(barMs: Long) = "${barMs / 60_000}m"

/**
 * "Uczenie" = turniej wariantów (6 rodzin strategii × interwały 5m i 1m) na długiej historii i na żywo.
 * Zwycięzca musi mieć: ≥ [minTrades] transakcji, dodatni wynik ze świeżą wagą (E > [minEdge])
 * i "pewność" t ≥ [minT] – czyli wynik, który trudno wytłumaczyć przypadkiem. Brak zwycięzcy = brak handlu.
 */
class Learner(
    val instrument: String,
    variants: List<Variant>,
    private val minTrades: Int = 30,
    private val minEdge: Double = 0.05,
    private val minT: Double = 1.0,
    private val minStopPct: Double = 0.001
) {
    private val shadows = variants.map { v -> v to ShadowTrader("${v.name} • ${tfLabel(v.barMs)}", v.factory(), minStopPct = minStopPct) }
    var champion: ShadowTrader? = null
        private set
    var championStats: LearnStats? = null
        private set
    /** Spread (w jednostkach ceny) użyty w nauce – do korekty EV o bieżący spread. */
    var learnedCost: Double = 0.0
        private set
    val championBarMs: Long? get() = shadows.firstOrNull { it.second === champion }?.first?.barMs
    private val learned = HashMap<Long, Int>()

    /** Nauka na historii danego interwału (sentyment historyczny nieznany = 0). */
    fun backtest(barMs: Long, history: List<Bar>, cost: Double) {
        learnedCost = cost
        val group = shadows.filter { it.first.barMs == barMs }.map { it.second }
        for (i in history.indices) {
            val window = history.subList(max(0, i - 299), i + 1)
            group.forEach { it.onBar(window, 0.0, cost) }
        }
        learned[barMs] = (learned[barMs] ?: 0) + history.size
        pick()
    }

    /** Nowa zamknięta świeca interwału [barMs]. Zwraca sygnał zwycięzcy, jeśli gra na tym interwale. */
    fun onBar(barMs: Long, bars: List<Bar>, sentiment: Double, cost: Double): Signal? {
        val current = champion
        var out: Signal? = null
        for ((v, s) in shadows) {
            if (v.barMs != barMs) continue
            val sig = s.onBar(bars, sentiment, cost)
            if (s === current) out = sig
        }
        learned[barMs] = (learned[barMs] ?: 0) + 1
        pick()
        return out
    }

    private fun pick() {
        val scored = shadows.map { it.second to it.second.stats() }
        val best = scored.filter { (_, st) -> st.trades >= minTrades && st.avgR > minEdge && st.tStat >= minT }
            .maxByOrNull { (_, st) -> st.avgR }
        champion = best?.first
        championStats = best?.second
    }

    /** Dlaczego zwycięzca pominął ostatnią okazję (null = nie pominął). */
    fun championNote(): String? = champion?.note()

    fun describeWait(barsFor: (Long) -> List<Bar>?): String? {
        val c = champion ?: return null
        c.openSide?.let { return "${c.name}: w pozycji ${if (it == Side.BUY) "LONG" else "SHORT"} – czeka na wyjście" }
        val bars = championBarMs?.let(barsFor) ?: return null
        return c.describeWait(bars)
    }

    fun summary(): String {
        val n = learned.entries.sortedByDescending { it.key }.joinToString(" + ") { (tf, k) -> "$k×${tfLabel(tf)}" }
        val c = champion
        val cs = championStats
        if (c != null && cs != null) {
            return "🧠 $instrument: ${c.name} • %d tr. • %.0f%% • E=%+.2fR • pewność t=%.1f\n📚 %s".format(
                cs.trades, cs.winRate * 100, cs.avgR, cs.tStat, n
            )
        }
        val best = shadows.map { it.second to it.second.stats() }.filter { it.second.trades > 0 }.maxByOrNull { it.second.avgR }
        return if (best == null) "🧠 $instrument: uczę się… ($n)"
        else "🧠 $instrument: brak pewnej przewagi – najlepszy ${best.first.name} E=%+.2fR, t=%.1f (%d tr.), nie handluję\n📚 %s"
            .format(best.second.avgR, best.second.tStat, best.second.trades, n)
    }

    companion object {
        const val M5 = 5 * 60_000L
        const val M1 = 60_000L
        val TIMEFRAMES = listOf(M5, M1)

        /** Ile świec historii pobierać: 5m ≈ 30 dni, 1m ≈ 7 dni. */
        fun historyBars(tf: Long) = if (tf == M5) 8_640 else 10_080

        fun defaultVariants(): List<Variant> = TIMEFRAMES.flatMap { tf ->
            listOf(
                Variant("Wybicie 20", tf) { TrendBreakoutStrategy(donchian = 20, barMs = tf) },
                Variant("Wybicie 55", tf) { TrendBreakoutStrategy(donchian = 55, barMs = tf) },
                Variant("Odbicie BB2.0", tf) { ReversalStrategy(k = 2.0, barMs = tf) },
                Variant("Odbicie BB2.5", tf) { ReversalStrategy(k = 2.5, rsiLow = 25.0, rsiHigh = 75.0, barMs = tf) },
                Variant("Cofnięcie w trendzie", tf) { PullbackStrategy(barMs = tf) },
                Variant("Zawężenie→wybicie", tf) { SqueezeStrategy(barMs = tf) },
                Variant("Price action", tf) { PriceActionStrategy(withTrend = false, barMs = tf) },
                Variant("Price action z trendem", tf) { PriceActionStrategy(withTrend = true, barMs = tf) },
                Variant("Impuls 2×ATR", tf) { MomentumBurstStrategy(minRangeAtr = 2.0, barMs = tf) },
                Variant("Impuls 3×ATR", tf) { MomentumBurstStrategy(minRangeAtr = 3.0, barMs = tf) }
            )
        }
    }
}

// ───────────── Price action: formacje świecowe, kondycja rynku, poziomy agresji ─────────────

/** Poziom agresji pozycji: 0 = zredukowany, 1 = umiarkowany, 2 = agresywny. */
object Tier {
    const val REDUCED = 0
    const val MODERATE = 1
    const val AGGRESSIVE = 2
    fun name(t: Int) = when (t) { REDUCED -> "Zredukowany"; AGGRESSIVE -> "Agresywny"; else -> "Umiarkowany" }
}

object Candles {
    data class Shape(val body: Double, val range: Double, val upper: Double, val lower: Double, val bull: Boolean)

    fun shape(b: Bar) = Shape(
        body = abs(b.close - b.open),
        range = b.high - b.low,
        upper = b.high - max(b.open, b.close),
        lower = min(b.open, b.close) - b.low,
        bull = b.close > b.open
    )

    /**
     * Formacja na ostatniej świecy: (nazwa, kierunek +1/-1) albo null.
     * Każda formacja przechodzi przez [CandleEvaluator]: korpus < 25% ATR (szum) → brak sygnału.
     */
    fun pattern(bars: List<Bar>, atr: Double): Pair<String, Int>? {
        if (bars.size < 6 || atr <= 0) return null
        val last = bars.last()
        val prev = bars[bars.size - 2]
        val e = CandleEvaluator.evaluate(last, atr)
        if (e.impulse == Impulse.NOISE) return null
        val p = shape(prev)
        val fell = bars[bars.size - 6].close > prev.close
        val rose = bars[bars.size - 6].close < prev.close
        val lows = bars.takeLast(4).map { it.low }
        val highs = bars.takeLast(4).map { it.high }
        val atLeastNormal = e.impulse == Impulse.NORMAL || e.impulse == Impulse.STRONG
        return when {
            !p.bull && e.direction > 0 && last.close >= prev.open && last.open <= prev.close && atLeastNormal -> "objęcie hossy" to 1
            p.bull && e.direction < 0 && last.close <= prev.open && last.open >= prev.close && atLeastNormal -> "objęcie bessy" to -1
            e.direction > 0 && e.impulse == Impulse.STRONG && e.rangeToAtr >= 1.2 -> "marubozu ↑ (silny impet)" to 1
            e.direction < 0 && e.impulse == Impulse.STRONG && e.rangeToAtr >= 1.2 -> "marubozu ↓ (silny impet)" to -1
            fell && e.rejectsLow -> "młot (absorpcja podaży)" to 1
            rose && e.rejectsHigh -> "spadająca gwiazda (odrzucenie szczytu)" to -1
            lows.zipWithNext().all { (a, b) -> b > a } && last.close > prev.high && e.direction > 0 && atLeastNormal -> "seria wyższych dołków" to 1
            highs.zipWithNext().all { (a, b) -> b < a } && last.close < prev.low && e.direction < 0 && atLeastNormal -> "seria niższych szczytów" to -1
            else -> null
        }
    }

    fun describeLast(bars: List<Bar>, atr: Double): String {
        pattern(bars, atr)?.let { return it.first }
        val s = shape(bars.last())
        return when {
            s.range <= 0 -> "brak ruchu"
            s.upper >= 0.3 * s.range && s.lower >= 0.3 * s.range -> "niepewność (knoty z obu stron)"
            s.body >= 0.6 * s.range -> if (s.bull) "wzrostowa, pełny korpus" else "spadkowa, pełny korpus"
            else -> "neutralna"
        }
    }
}

/** Zwięzła "kondycja rynku" do HUD: trend, zmienność, faza świecy. */
object MarketRead {
    fun describe(bars: List<Bar>): String? {
        if (bars.size < 60) return null
        val closes = bars.map { it.close }
        val e20 = Indicators.ema(closes, 20) ?: return null
        val e50 = Indicators.ema(closes, 50) ?: return null
        val atr = Indicators.atr(bars) ?: return null
        val avgRange = bars.takeLast(50).map { it.high - it.low }.average()
        val trend = when {
            e20 > e50 && abs(e20 - e50) > 0.3 * atr -> "trend ↑"
            e20 < e50 && abs(e20 - e50) > 0.3 * atr -> "trend ↓"
            else -> "bez trendu"
        }
        val vol = when {
            avgRange <= 0 -> "?"
            atr / avgRange > 1.5 -> "zmienność wysoka"
            atr / avgRange < 0.7 -> "zmienność niska"
            else -> "zmienność normalna"
        }
        return "$trend • $vol • świeca: ${Candles.describeLast(bars, atr)}"
    }
}

/**
 * Price action (profil agresywny): wejście na formacjach świecowych, wielkość wg siły setupu.
 * Agresywny: silny impet (szeroka świeca, mały przeciwny knot) + wybicie poziomu 20 świec.
 * Umiarkowany: formacja przy EMA20 w trendzie (pullback) lub zwykłe potwierdzenie.
 * Zredukowany: wysoka zmienność bez kierunku.
 */
class PriceActionStrategy(
    private val withTrend: Boolean,
    private val rewardR: Double = 2.0,
    private val newsVeto: Double = 0.4,
    private val cooldownBars: Int = 3,
    private val barMs: Long = 5 * 60_000L
) : Strategy {
    private var lastSignalBar = 0L
    private var note: String? = null
    override fun lastNote(): String? = note

    override fun onBar(bars: List<Bar>, sentiment: Double): Signal? {
        if (bars.size < 60) return null
        val last = bars.last()
        val prev = bars[bars.size - 2]
        val closes = bars.map { it.close }
        val atr = Indicators.atr(bars)?.takeIf { it > 0 } ?: return null
        val (name, dir) = Candles.pattern(bars, atr) ?: return null
        val e20 = Indicators.ema(closes, 20) ?: return null
        val e50 = Indicators.ema(closes, 50) ?: return null
        val skip = when {
            last.start - lastSignalBar < cooldownBars * barMs -> "przerwa po poprzednim sygnale"
            withTrend && dir > 0 && e20 <= e50 -> "trend ↓ – nie kupuję przeciw trendowi"
            withTrend && dir < 0 && e20 >= e50 -> "trend ↑ – nie sprzedaję przeciw trendowi"
            dir > 0 && sentiment <= -newsVeto -> "newsy negatywne (%+.2f)".format(sentiment)
            dir < 0 && sentiment >= newsVeto -> "newsy pozytywne (%+.2f)".format(sentiment)
            else -> null
        }
        if (skip != null) { note = "$name na ${tfLabel(barMs)} pominięte: $skip"; return null }
        note = null

        val ev = CandleEvaluator.evaluate(last, atr)
        val channel = bars.subList(bars.size - 21, bars.size - 1)
        val breaks = if (dir > 0) last.close > channel.maxOf { it.high } else last.close < channel.minOf { it.low }
        val strong = ev.impulse == Impulse.STRONG
        val avgRange = bars.takeLast(50).map { it.high - it.low }.average()
        val choppy = avgRange > 0 && atr / avgRange > 1.5 && abs(e20 - e50) < 0.3 * atr
        val pullback = abs(last.close - e20) <= 0.5 * atr && ((dir > 0 && e20 > e50) || (dir < 0 && e20 < e50))
        val tier = when {
            choppy -> Tier.REDUCED
            strong && breaks -> Tier.AGGRESSIVE
            else -> Tier.MODERATE
        }
        val entry = last.close
        val stop = if (dir > 0) min(last.low, prev.low) - 0.2 * atr else max(last.high, prev.high) + 0.2 * atr
        val risk = abs(entry - stop)
        if (risk <= 0) return null
        lastSignalBar = last.start
        val why = buildList {
            add(name)
            if (breaks) add("wybicie poziomu 20 świec")
            add("impuls %s (korpus %.1f×ATR, %.0f%% świecy)".format(ev.impulse.name.lowercase(), ev.bodyToAtr, ev.bodyToRange * 100))
            if (pullback) add("przy EMA20 w trendzie")
            if (choppy) add("chaotyczna zmienność")
        }.joinToString(", ")
        return Signal(
            side = if (dir > 0) Side.BUY else Side.SELL,
            stopPrice = stop,
            takeProfit = entry + dir * rewardR * risk,
            reason = why,
            tier = tier
        )
    }

    override fun describeWait(bars: List<Bar>): String? {
        if (bars.size < 60) return null
        val atr = Indicators.atr(bars) ?: return null
        return "ODCZEKAJ – czekam na formację (objęcie, młot/gwiazda, marubozu, wyższe dołki/niższe szczyty); " +
            "ostatnia świeca: ${Candles.describeLast(bars, atr)}"
    }
}

/**
 * "Impuls": pojedyncza świeca o sile ≥ [minRangeAtr]×ATR z pełnym korpusem (Impulse.STRONG) – wejście w jej kierunku
 * NIEZALEŻNIE od trendu i RSI (gonienie momentum). Stop w połowie świecy impulsu, TP = [rewardR]×ryzyko.
 * Turniej sprawdzi, czy takie wejścia na danym instrumencie się opłacają.
 */
class MomentumBurstStrategy(
    private val minRangeAtr: Double = 2.0,
    private val rewardR: Double = 2.0,
    private val newsVeto: Double = 0.5,
    private val cooldownBars: Int = 3,
    private val barMs: Long = 5 * 60_000L
) : Strategy {
    private var lastSignalBar = 0L

    override fun onBar(bars: List<Bar>, sentiment: Double): Signal? {
        if (bars.size < 30) return null
        val last = bars.last()
        if (last.start - lastSignalBar < cooldownBars * barMs) return null
        val atr = Indicators.atr(bars.dropLast(1))?.takeIf { it > 0 } ?: return null // ATR sprzed impulsu
        val ev = CandleEvaluator.evaluate(last, atr)
        if (ev.impulse != Impulse.STRONG || ev.rangeToAtr < minRangeAtr) return null
        val dir = ev.direction
        if (dir > 0 && sentiment <= -newsVeto || dir < 0 && sentiment >= newsVeto) return null
        val entry = last.close
        val stop = (last.high + last.low) / 2 // połowa świecy impulsu – głębsze cofnięcie = impuls się nie udał
        val risk = abs(entry - stop)
        if (risk <= 0) return null
        lastSignalBar = last.start
        return Signal(
            side = if (dir > 0) Side.BUY else Side.SELL,
            stopPrice = stop,
            takeProfit = entry + dir * rewardR * risk,
            reason = "impuls %.1f×ATR (korpus %.0f%% świecy)".format(ev.rangeToAtr, ev.bodyToRange * 100),
            tier = Tier.AGGRESSIVE
        )
    }

    override fun describeWait(bars: List<Bar>): String? {
        val atr = Indicators.atr(bars)?.takeIf { it > 0 } ?: return null
        return "czekam na świecę-impuls ≥ %.1f×ATR (zasięg ≥ %.2f) z pełnym korpusem".format(minRangeAtr, minRangeAtr * atr)
    }
}
