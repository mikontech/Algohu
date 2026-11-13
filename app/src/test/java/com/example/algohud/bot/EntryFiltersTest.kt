package com.example.algohud.bot

import com.example.algohud.core.Bar
import com.example.algohud.core.Side
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EntryFiltersTest {
    private fun bars(steps: List<Double>, barMs: Long = 60_000L, start: Double = 2000.0, wick: Double = 0.2): List<Bar> {
        var px = start
        return steps.mapIndexed { i, d ->
            val o = px; px += d
            Bar(i * barMs, o, maxOf(o, px) + wick, minOf(o, px) - wick, px)
        }
    }

    /** 5m: długi spadek, który na końcu wyhamowuje (EMA20 przestaje spadać). */
    private val m5Weakening = bars(List(50) { -1.0 } + List(8) { 0.9 }, barMs = 300_000L)
    private val m5StillFalling = bars(List(58) { -1.0 }, barMs = 300_000L)

    /** 1m: spadek, potem wyraźne odbicie z przebiciem szczytu. */
    private val m1Rebound = bars(List(50) { -0.3 } + List(12) { 0.6 })

    @Test fun reversalLongConfirmedWhen5mWeakens() {
        assertNotNull(EntryFilters.reversalConfirmed(m1Rebound, m5Weakening, 1))
    }

    @Test fun noReversalWhen5mStillFalling() {
        assertNull(EntryFilters.reversalConfirmed(m1Rebound, m5StillFalling, 1))
    }

    @Test fun noReversalOnSingleGreenCandle() {
        val m1 = bars(List(60) { -0.3 } + listOf(1.5))
        assertNull(EntryFilters.reversalConfirmed(m1, m5Weakening, 1))
    }

    @Test fun rangeShortBlockedIntoStrongRally() {
        val m1 = bars(List(40) { if (it % 2 == 0) 0.3 else -0.3 } + List(8) { 0.8 })
        val why = EntryFilters.rangeFadeBlock(m1, -1)
        assertNotNull(why); assertTrue(why!!, why.contains("impuls"))
        assertNull(EntryFilters.rangeFadeBlock(m1, 1)) // long z impulsem – bez blokady
    }

    @Test fun rangeShortAllowedAfterRejectionCandle() {
        val base = bars(List(40) { if (it % 2 == 0) 0.3 else -0.3 } + List(8) { 0.8 })
        val l = base.last()
        // świeca odrzucenia: wybicie w górę o 3, zamknięcie poniżej otwarcia (długi górny knot)
        val rej = Bar(l.start + 60_000L, l.close, l.close + 3.0, l.close - 0.3, l.close - 0.2)
        assertNull(EntryFilters.rangeFadeBlock(base + rej, -1))
    }

    @Test fun rangeTradeInQuietMarketNotBlocked() {
        val m1 = bars(List(60) { if (it % 2 == 0) 0.3 else -0.3 })
        assertNull(EntryFilters.rangeFadeBlock(m1, -1))
        assertNull(EntryFilters.rangeFadeBlock(m1, 1))
    }

    @Test fun consolidationDetection() {
        assertTrue(EntryFilters.isConsolidation(bars(List(60) { if (it % 2 == 0) 0.5 else -0.5 }, barMs = 300_000L)))
        assertFalse(EntryFilters.isConsolidation(bars(List(60) { 1.5 }, barMs = 300_000L)))
    }

    @Test fun reversalGuardDoesNotExitOnImpulseCandleAndHoldsIfItRetraces() {
        // SHORT: spokojnie, potem wystrzał w górę o 5 (impuls), a następna świeca cofa go o > 50%.
        val calm = bars(List(70) { if (it % 2 == 0) 0.4 else -0.45 })
        val p = calm.last().close
        val up1 = Bar(calm.last().start + 60_000L, p, p + 0.5, p - 0.1, p + 0.4)
        val up2 = Bar(up1.start + 60_000L, up1.close, up1.close + 0.5, up1.close - 0.1, up1.close + 0.4)
        val spike = Bar(up2.start + 60_000L, up2.close, up2.close + 5.2, up2.close - 0.1, up2.close + 5.0)
        val back = Bar(spike.start + 60_000L, spike.close, spike.close + 0.2, spike.close - 3.6, spike.close - 3.4)
        val g = ReversalGuard()
        val opened = calm[66].start + 1_000L
        val best = calm.drop(66).minOf { it.low }
        val seq = listOf(up1, up2, spike, back)
        var all = calm
        val verdicts = seq.map { b -> all = all + b; g.evaluate("s", Side.SELL, opened, best, all, TrendScore.compute(all, emptyList())) }
        assertFalse("bez wyjścia przed impulsem: ${verdicts.map { it?.reason }}", verdicts[0]!!.exit || verdicts[1]!!.exit)
        assertTrue(verdicts[2]!!.reason, verdicts[2]!!.reason.contains("impulsowa"))
        assertFalse("nie zamyka na świecy impulsowej: ${verdicts.map { it?.reason }}", verdicts[2]!!.exit)
        assertFalse("po cofnięciu impulsu trzyma: ${verdicts.map { it?.reason }}", verdicts[3]!!.exit)
    }
}
