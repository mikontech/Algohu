package com.example.algohud.bot

import com.example.algohud.core.Bar
import com.example.algohud.core.Side
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReversalGuardTest {
    private val t0 = 1_800_000_000_000L - 1_800_000_000_000L % 60_000L

    /** 80 świec wzrostu (+0,3 na świecę), potem [tail] (zmiany zamknięcia kolejnych świec). */
    private fun series(tail: List<Double>): List<Bar> {
        val out = ArrayList<Bar>()
        var px = 2000.0
        for (i in 0 until 80) {
            val o = px; px += 0.3
            out += Bar(t0 + i * 60_000L, o, px + 0.2, o - 0.2, px)
        }
        for ((j, d) in tail.withIndex()) {
            val o = px; px += d
            out += Bar(t0 + (80 + j) * 60_000L, o, maxOf(o, px) + 0.15, minOf(o, px) - 0.15, px)
        }
        return out
    }

    private fun run(tail: List<Double>, openedAtBar: Int = 78): List<ReversalGuard.Verdict> {
        val g = ReversalGuard()
        val bars = series(tail)
        val opened = t0 + openedAtBar * 60_000L + 10_000L
        val out = ArrayList<ReversalGuard.Verdict>()
        for (n in 82..bars.size) {
            val m1 = bars.take(n)
            val best = m1.filter { it.start >= opened - opened % 60_000L }.maxOf { it.high }
            g.evaluate("pos", Side.BUY, opened, best, m1, TrendScore.compute(m1, emptyList()))?.let { out += it }
        }
        return out
    }

    @Test fun confirmedDowntrendClosesLong() {
        val v = run(List(12) { -1.2 })
        assertTrue("oczekiwano wyjścia: ${v.map { it.reason }}", v.any { it.exit })
    }

    @Test fun singleRedCandleDoesNotClose() {
        val v = run(listOf(-1.5, 0.6, 0.5, 0.4, 0.4, 0.3))
        assertFalse("jedna świeca przeciw nie może zamknąć: ${v.filter { it.exit }.map { it.reason }}", v.any { it.exit })
    }

    @Test fun shallowPullbackInUptrendDoesNotClose() {
        val v = run(listOf(-0.2, -0.15, -0.1, 0.3, 0.3, 0.3, 0.3))
        assertFalse(v.any { it.exit })
    }

    @Test fun noExitRightAfterEntry() {
        // Pozycja otwarta na ostatniej świecy wzrostu – pierwsza świeca spadku to za wcześnie, nawet gdy jest duża.
        val g = ReversalGuard()
        val bars = series(listOf(-3.0))
        val opened = t0 + 80 * 60_000L + 5_000L
        val v = g.evaluate("p", Side.BUY, opened, bars.maxOf { it.high }, bars, TrendScore.compute(bars, emptyList()))
        assertFalse(v!!.exit)
    }

    @Test fun newPositionResetsConfirmation() {
        val g = ReversalGuard()
        val bars = series(List(12) { -1.2 })
        val opened = t0 + 78 * 60_000L
        var exitFirst = false
        for (n in 82..bars.size) {
            val m1 = bars.take(n)
            if (g.evaluate("A", Side.BUY, opened, 2024.4, m1, TrendScore.compute(m1, emptyList()))?.exit == true) exitFirst = true
        }
        assertTrue(exitFirst)
        // Ta sama świeca, inna pozycja (świeżo otwarta) → bez wyjścia.
        val v = g.evaluate("B", Side.BUY, bars.last().start - 1, bars.last().close, bars, TrendScore.compute(bars, emptyList()))
        assertFalse(v!!.exit)
    }
    @Test fun skippedEvaluationCannotReuseConfirmation() {
        val g = ReversalGuard()
        val bars = series(List(12) { -1.2 })
        val opened = t0 + 78 * 60_000L
        for (n in 82..90) {
            val sample = bars.take(n)
            g.evaluate("p", Side.BUY, opened, 2024.4, sample, TrendScore.compute(sample, emptyList()))
        }
        val v = g.evaluate("p", Side.BUY, opened, 2024.4, bars, TrendScore.compute(bars, emptyList()))!!
        assertFalse(v.exit)
        assertTrue(v.streak <= 1)
    }

    @Test fun missingCandleBlocksReversal() {
        val g = ReversalGuard()
        val bars = series(List(12) { -1.2 }).filterIndexed { i, _ -> i != 86 }
        val v = g.evaluate("p", Side.BUY, t0, 2024.4, bars, TrendScore.compute(bars, emptyList()))!!
        assertFalse(v.exit)
        assertTrue(v.reason.contains("niepełne"))
    }

    @Test fun oldCandleCannotRepeatExit() {
        val g = ReversalGuard()
        val bars = series(List(12) { -1.2 })
        for (n in 82..bars.size) {
            val sample = bars.take(n)
            g.evaluate("p", Side.BUY, t0, 2024.4, sample, TrendScore.compute(sample, emptyList()))
        }
        val old = bars.dropLast(1)
        val v = g.evaluate("p", Side.BUY, t0, 2024.4, old, TrendScore.compute(old, emptyList()))!!
        assertFalse(v.exit)
        assertTrue(v.streak == 0)
    }

}
