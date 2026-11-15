package com.example.algohud.bot
import org.junit.Assert.*
import org.junit.Test
class TickBurstTest {
    @Test fun fastTicksRetainExtremaRegardlessOfHudRate() {
        val a = BarAggregator(60000)
        a.onTick(100.0, 60000)
        for (i in 1..10000) a.onTick(if (i == 1) 130.0 else if (i == 2) 70.0 else 101.0, 60000L + i)
        val closed = a.onTick(102.0, 120000)!!
        assertEquals(100.0, closed.open, 0.0); assertEquals(130.0, closed.high, 0.0)
        assertEquals(70.0, closed.low, 0.0); assertEquals(101.0, closed.close, 0.0)
    }
}
