package com.example.algohud.bot

import com.example.algohud.core.Bar
import com.example.algohud.core.Side
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TrendScoreTest {
    private fun trend(step: Double, n: Int = 120, barMs: Long = 60_000L): List<Bar> {
        var px = 2000.0
        return (0 until n).map { i ->
            val o = px; px += step + if (i % 3 == 0) -step * 0.5 else 0.0
            Bar(i * barMs, o, maxOf(o, px) + 0.2, minOf(o, px) - 0.2, px)
        }
    }

    @Test fun uptrendIsLong() {
        val s = TrendScore.compute(trend(0.4), trend(1.5, barMs = 300_000L))
        assertNotNull(s)
        assertTrue(s!!.describe(), s.value >= TrendScore.ENTRY_THRESHOLD)
        assertTrue(s.side == Side.BUY)
    }

    @Test fun downtrendIsShortAndBlocksLong() {
        val s = TrendScore.compute(trend(-0.4), trend(-1.5, barMs = 300_000L))!!
        assertTrue(s.describe(), s.value <= -TrendScore.ENTRY_THRESHOLD)
        assertNotNull(TrendScore.entryBlock(s, Side.BUY))
        assertNull(TrendScore.entryBlock(s, Side.SELL))
    }

    @Test fun notEnoughDataGivesNullAndNoBlock() {
        assertNull(TrendScore.compute(trend(0.4, n = 20), emptyList()))
        assertNull(TrendScore.entryBlock(null, Side.BUY))
    }
}
