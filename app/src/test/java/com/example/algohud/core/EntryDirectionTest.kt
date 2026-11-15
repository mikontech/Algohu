package com.example.algohud.core
import org.junit.Assert.*
import org.junit.Test
class EntryDirectionTest {
    private fun order(side: Side) = OrderRequest("GOLD", side, 0.007, 100.0,
        100.0 - side.sign * 5, "ZAKRES", takeProfit = 100.0 + side.sign * 10)
    @Test fun mirrorsBothDirectionsAndProtection() {
        for (side in Side.values()) {
            val original = order(side)
            val actual = EntryDirection.prepare(original, true)
            assertEquals(side.opposite(), actual.side)
            assertEquals(100.0 + side.sign * 5, actual.stopPrice, 0.0)
            assertEquals(100.0 - side.sign * 10, actual.takeProfit!!, 0.0)
            assertEquals(original.quantity, actual.quantity, 0.0)
            assertEquals(actual, EntryDirection.prepare(actual, true))
        }
    }
    @Test fun closingOrdersUnchanged() {
        val close = order(Side.SELL).copy(reduceOnly = true)
        assertEquals(close, EntryDirection.prepare(close, true))
    }
    @Test fun normalPreparedOrderDoesNotFlipAfterSettingsChange() {
        val normal = EntryDirection.prepare(order(Side.BUY), false)
        assertEquals(normal, EntryDirection.prepare(normal, true))
    }
}
