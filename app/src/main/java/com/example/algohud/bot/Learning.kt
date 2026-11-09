package com.example.algohud.bot

import com.example.algohud.core.Bar
import com.example.algohud.core.Side
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
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
}

/**
 * Wirtualny trader: gra jednym wariantem strategii na świecach (bez pieniędzy, wynik w R).
 * Wyjścia jak w prawdziwym bocie: SL, TP, stop na wejście po +1R, potem trailing 1R; sygnał przeciwny = odwrócenie.
 * Gdy w jednej świecy padłby i SL, i TP – zakładamy SL (ostrożnie).
 */
class ShadowTrader(val name: String, private val strategy: Strategy, private val maxResults: Int = 300) {
    private data class Sim(val side: Side, val entry: Double, var stop: Double, val initStop: Double, val tp: Double, var best: Double)

    private var pos: Sim? = null
    private val results = ArrayDeque<Double>()

    val trades: Int get() = results.size
    val avgR: Double get() = if (results.isEmpty()) 0.0 else results.average()
    val winRate: Double get() = if (results.isEmpty()) 0.0 else results.count { it > 0 }.toDouble() / results.size

    /** @param cost koszt transakcji w jednostkach ceny (spread). Zwraca sygnał, jeśli otworzył pozycję. */
    fun onBar(bars: List<Bar>, sentiment: Double, cost: Double): Signal? {
        val bar = bars.last()
        pos?.let { p -> exitOnBar(p, bar, cost)?.let { r -> record(r); pos = null } }
        val sig = strategy.onBar(bars, sentiment) ?: return null
        val p = pos
        if (p != null && p.side == sig.side) return null
        if (p != null) { record(rAt(p, bar.close, cost)); pos = null }
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

    private fun record(r: Double) {
        results.addLast(r)
        while (results.size > maxResults) results.removeFirst()
    }
}

/**
 * "Uczenie" = turniej wariantów: każdy gra wirtualnie na historii i na żywo, bot handluje wariantem
 * z najlepszym wynikiem (min. [minTrades] transakcji i E > [minEdge]). Brak zwycięzcy = bot nie handluje.
 */
class Learner(
    val instrument: String,
    variants: List<Pair<String, () -> Strategy>>,
    private val minTrades: Int = 15,
    private val minEdge: Double = 0.05
) {
    private val shadows = variants.map { (name, factory) -> ShadowTrader(name, factory()) }
    var champion: ShadowTrader? = null
        private set
    var learnedBars = 0
        private set

    /** Nauka na historii (sentyment historyczny nieznany = 0). */
    fun backtest(history: List<Bar>, cost: Double) {
        for (i in history.indices) {
            val window = history.subList(max(0, i - 299), i + 1)
            shadows.forEach { it.onBar(window, 0.0, cost) }
        }
        learnedBars += history.size
        pick()
    }

    /** Zwraca sygnał aktualnego zwycięzcy (wybranego przed tą świecą). */
    fun onBar(bars: List<Bar>, sentiment: Double, cost: Double): Signal? {
        val current = champion
        var out: Signal? = null
        for (s in shadows) {
            val sig = s.onBar(bars, sentiment, cost)
            if (s === current) out = sig
        }
        learnedBars++
        pick()
        return out
    }

    private fun pick() {
        champion = shadows.filter { it.trades >= minTrades && it.avgR > minEdge }.maxByOrNull { it.avgR }
    }

    fun summary(): String {
        val c = champion
        if (c != null) return "🧠 $instrument: ${c.name} • %d tr. • %.0f%% • E=%+.2fR".format(c.trades, c.winRate * 100, c.avgR)
        val best = shadows.maxByOrNull { if (it.trades > 0) it.avgR else -9.0 }
        return if (best == null || best.trades == 0) "🧠 $instrument: uczę się… ($learnedBars świec)"
        else "🧠 $instrument: brak przewagi – najlepszy ${best.name} E=%+.2fR (%d tr.), nie handluję".format(best.avgR, best.trades)
    }

    companion object {
        fun defaultVariants(barMs: Long): List<Pair<String, () -> Strategy>> = listOf(
            "Wybicie 20" to { TrendBreakoutStrategy(donchian = 20, barMs = barMs) },
            "Wybicie 55" to { TrendBreakoutStrategy(donchian = 55, barMs = barMs) },
            "Odbicie BB2.0" to { ReversalStrategy(k = 2.0, barMs = barMs) },
            "Odbicie BB2.5" to { ReversalStrategy(k = 2.5, rsiLow = 25.0, rsiHigh = 75.0, barMs = barMs) }
        )
    }
}
