package com.example.algohud.bot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StrategyProofTest {
    private val spread = 0.70

    /** Kupno po 4000,35 (ask), SL 6 USD niżej, TP 15 USD wyżej (2,5R). */
    private fun long(p: StrategyProof, strategy: String = "RT") =
        p.open(strategy, "GOLD", 1, 4000.35, 3994.35, 4015.35)

    @Test fun stopLossIsMinusOneR() {
        val p = StrategyProof()
        assertTrue(long(p))
        assertTrue(p.onPrice("GOLD", 3996.0, spread, 1).isEmpty())
        val c = p.onPrice("GOLD", 3994.6, spread, 2).single() // bid 3994,25 ≤ SL
        assertEquals(-1.0, c.r, 1e-9)
        assertEquals("SL", c.reason)
    }

    @Test fun takeProfitNeedsBidAtTarget() {
        val p = StrategyProof(minTrades = 1)
        long(p)
        assertTrue(p.onPrice("GOLD", 4015.5, spread, 1).isEmpty()) // bid 4015,15 < TP
        val c = p.onPrice("GOLD", 4015.8, spread, 2).single()      // bid 4015,45 ≥ TP
        assertEquals(2.5, c.r, 1e-9)
        assertEquals("TP", c.reason)
    }

    @Test fun afterOneRStopMovesToEntryPlusSpread() {
        val p = StrategyProof()
        long(p)
        p.onPrice("GOLD", 4006.5, spread, 1)                       // +1R → stop na 4001,05
        val c = p.onPrice("GOLD", 4001.0, spread, 2).single()
        assertEquals(spread / 6.0, c.r, 1e-9)
        assertEquals("SL za zyskiem", c.reason)
    }

    @Test fun shortIsMirrored() {
        val p = StrategyProof()
        assertTrue(p.open("SMC", "GOLD", -1, 3999.65, 4003.65, 3989.65))
        val c = p.onPrice("GOLD", 4003.4, spread, 1).single()      // ask 4003,75 ≥ SL
        assertEquals(-1.0, c.r, 1e-9)
    }

    @Test fun oneOpenTrialPerStrategyAndBadLevelsRejected() {
        val p = StrategyProof()
        assertTrue(long(p))
        assertFalse(long(p))
        assertTrue(long(p, "ZAKRES"))
        assertFalse(p.open("ORB", "GOLD", 1, 4000.0, 4001.0, 4010.0))
        assertFalse(p.open("ORB", "GOLD", 1, 4000.0, 3995.0, 3999.0))
    }

    @Test fun otherSymbolPricesAreIgnored() {
        val p = StrategyProof()
        long(p)
        assertTrue(p.onPrice("SILVER", 10.0, 0.02, 1).isEmpty())
        assertEquals(0, p.verdict("RT").trades)
    }

    private fun feed(p: StrategyProof, strategy: String, wins: Int, losses: Int) {
        var ts = 0L
        repeat(maxOf(wins, losses)) { i ->
            if (i < wins) { long(p, strategy); p.onPrice("GOLD", 4016.0, spread, ++ts) }
            if (i < losses) { long(p, strategy); p.onPrice("GOLD", 3994.0, spread, ++ts) }
        }
    }

    @Test fun notProvenBeforeMinimumTrades() {
        val p = StrategyProof()
        feed(p, "RT", wins = 20, losses = 0)
        val v = p.verdict("RT")
        assertEquals(20, v.trades)
        assertFalse(v.proven)
    }

    @Test fun breakEvenWinRateIsNotProven() {
        val p = StrategyProof()
        feed(p, "RT", wins = 10, losses = 25) // 28,6% trafień przy 2,5R = zero
        val v = p.verdict("RT")
        assertEquals(0.0, v.avgR, 1e-9)
        assertFalse(v.proven)
    }

    @Test fun clearEdgeIsProven() {
        val p = StrategyProof()
        feed(p, "RT", wins = 20, losses = 20) // 50% trafień przy 2,5R
        val v = p.verdict("RT")
        assertEquals(0.75, v.avgR, 1e-9)
        assertTrue(v.tStat >= 2.0)
        assertTrue(v.proven)
        assertFalse(p.proven("SMC"))
    }

    @Test fun onlyLastWindowCounts() {
        val p = StrategyProof(window = 30)
        feed(p, "RT", wins = 30, losses = 0)
        assertTrue(p.proven("RT"))
        feed(p, "RT", wins = 0, losses = 30)
        assertFalse(p.proven("RT"))
        assertEquals(30, p.verdict("RT").trades)
    }

    @Test fun resultsSurviveRestart() {
        var stored: String? = null
        val a = StrategyProof(load = { stored }, save = { stored = it })
        feed(a, "ZAKRES", wins = 3, losses = 2)
        val b = StrategyProof(load = { stored }, save = { stored = it })
        assertEquals(5, b.verdict("ZAKRES").trades)
        assertEquals(a.verdict("ZAKRES").avgR, b.verdict("ZAKRES").avgR, 1e-9)
    }

    @Test fun strategyKeyFromEntryReason() {
        assertEquals("RT", StrategyProof.keyOf("RT impuls 1.4×ATR, korpus 80%"))
        assertEquals("ORB", StrategyProof.keyOf("ORB wybicie zasięgu otwarcia"))
        assertEquals("ZAKRES", StrategyProof.keyOf("ZAKRES odbicie od dołka"))
        assertEquals("SMC", StrategyProof.keyOf("SMC SHORT: sweep PDH"))
        assertNull(StrategyProof.keyOf("STRAT ema-cross"))
        assertNull(StrategyProof.keyOf("MANUAL"))
    }
}
