package com.example.algohud.data

import com.example.algohud.core.Tick
import org.junit.Assert.*
import org.junit.Test

class LivePriceGuardTest {
    private fun trade(time: Long, id: Long, price: Double = 3900.0) = LivePriceGuard.Trade(Tick("PAXGUSDT", price, time), id)
    @Test fun repeatedRestDoesNotRefreshOldPrice() {
        val g = LivePriceGuard()
        assertTrue(g.accept(trade(100_000, 1), 101_000))
        assertFalse(g.accept(trade(100_000, 1), 115_000))
        assertEquals(100_000L, g.lastAt("PAXGUSDT"))
    }
    @Test fun rejectsStaleFutureAndInvalidPrices() {
        val g = LivePriceGuard()
        assertFalse(g.accept(trade(70_000, 1), 100_000))
        assertFalse(g.accept(trade(103_000, 2), 100_000))
        assertFalse(g.accept(trade(100_000, 3, Double.NaN), 100_000))
        assertFalse(g.accept(trade(100_000, 4, 0.0), 100_000))
        assertNull(g.lastAt("PAXGUSDT"))
    }
    @Test fun websocketAndRestDeduplicateWithoutLosingSameMillisecondTrades() {
        val g = LivePriceGuard()
        assertTrue(g.accept(trade(100_000, 10), 100_000))
        assertFalse(g.accept(trade(100_000, 10), 100_000))
        assertTrue(g.accept(trade(100_000, 11, 3901.0), 100_000))
        assertFalse(g.accept(trade(99_000, 12), 100_000))
    }
    @Test fun websocketMustContainExchangeTimeAndTradeId() {
        assertNull(LivePriceGuard.websocket("""{"s":"PAXGUSDT","p":"3900"}"""))
        val trade = LivePriceGuard.websocket("""{"data":{"s":"PAXGUSDT","p":"3900","T":100000,"t":42}}""")!!
        assertEquals(100_000L, trade.tick.timestamp)
        assertEquals(42L, trade.id)
    }
    @Test fun restSortsAndSkipsMalformedRows() {
        val trades = LivePriceGuard.rest("PAXGUSDT", """[{"time":100001,"id":2,"price":"3901"},{"price":"3900"},{"time":100000,"id":1,"price":"3900"}]""")
        assertEquals(listOf(1L, 2L), trades.map { it.id })
        assertEquals(listOf(100_000L, 100_001L), trades.map { it.tick.timestamp })
    }
    @Test fun symbolsHaveIndependentWatermarks() {
        val g = LivePriceGuard()
        assertTrue(g.accept(trade(100_000, 42), 100_000))
        assertTrue(g.accept(LivePriceGuard.Trade(Tick("BTCUSDT", 60000.0, 100_000), 1), 100_000))
    }
}
