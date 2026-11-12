package com.example.algohud.bot

import com.example.algohud.core.Bar
import org.junit.Assert.*
import org.junit.Test

class BarAggregatorTest {
    @Test fun lateTickCannotRewindCurrentCandle() {
        val a = BarAggregator(60_000)
        a.onTick(100.0, 600_000)
        a.onTick(120.0, 300_000)
        assertEquals(600_000L, a.current()!!.start)
        assertEquals(100.0, a.current()!!.close, 0.0)
    }
    @Test fun lateTickWithinSameCandleCannotReplaceClose() {
        val a = BarAggregator(60_000)
        a.onTick(100.0, 610_000)
        a.onTick(90.0, 605_000)
        assertEquals(100.0, a.current()!!.close, 0.0)
    }
    @Test fun invalidPriceDoesNotPoisonFollowingTick() {
        val a = BarAggregator(60_000)
        for (price in listOf(Double.NaN, Double.POSITIVE_INFINITY, 0.0, -1.0)) a.onTick(price, 900_000)
        a.onTick(100.0, 600_000)
        assertEquals(100.0, a.current()!!.close, 0.0)
    }
    @Test fun rollClosesOnlyOnceAndDoesNotReopenOldBucket() {
        val a = BarAggregator(60_000)
        a.onTick(100.0, 600_000)
        assertNotNull(a.roll(663_000))
        assertNull(a.roll(664_000))
        a.onTick(101.0, 650_000)
        assertNull(a.current())
        a.onTick(102.0, 670_000)
        assertEquals(660_000L, a.current()!!.start)
        assertEquals(1, a.closed().size)
    }
    @Test fun seedKeepsLiveCandleAndDeduplicatesHistory() {
        val a = BarAggregator(60_000)
        a.onTick(100.0, 600_000)
        a.onTick(101.0, 660_000)
        val old = Bar(540_000, 90.0, 92.0, 89.0, 91.0)
        a.seed(listOf(old, old, Bar(600_000, 1.0, 1.0, 1.0, 1.0),
            Bar(660_000, 1.0, 1.0, 1.0, 1.0), Bar(480_000, Double.NaN, 1.0, 1.0, 1.0)))
        assertEquals(listOf(540_000L, 600_000L), a.closed().map { it.start })
        assertEquals(100.0, a.closed().last().close, 0.0)
    }
    @Test(expected = IllegalArgumentException::class)
    fun zeroPeriodRejected() { BarAggregator(0) }
}
