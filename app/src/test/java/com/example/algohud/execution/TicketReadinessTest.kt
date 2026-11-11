package com.example.algohud.execution

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class TicketReadinessTest {
    private fun ready() = TicketReadiness.Snapshot(true, listOf("GOLD CFD"), 1, false,
        TicketReadiness.Side(1, true, listOf("4 055,27")),
        TicketReadiness.Side(1, true, listOf("4\u202F056.09")))
    @Test fun validLocalizedPrices() {
        assertEquals(4055.27, TicketReadiness.parsePrice("4\u00A0055,27")!!, 0.00001)
        assertNull(ready().failure("GOLD"))
    }
    @Test fun rejectsAmbiguousOrNonFinitePrice() {
        listOf("Buy 4056.09", "4,056.09", "NaN", "Infinity", "0", "-1", "1 2 3 USD")
            .forEach { assertNull(TicketReadiness.parsePrice(it)) }
    }
    @Test fun rejectsWrongInstrumentAndPositionEditing() {
        assertNotNull(ready().failure("SILVER"))
        assertNotNull(ready().copy(modifiesPosition = true).failure("GOLD"))
    }
    @Test fun rejectsMissingButtonsAndDuplicatePrices() {
        val s = ready()
        assertTrue(s.copy(sell = s.sell.copy(count = 0)).failure("GOLD")!!.contains("Sell"))
        assertNotNull(s.copy(buy = s.buy.copy(prices = listOf("1", "2"))).failure("GOLD"))
    }
    @Test fun rejectsDisabledButtonsAndInvertedPrices() {
        val s = ready()
        assertNotNull(s.copy(buy = s.buy.copy(enabled = false)).failure("GOLD"))
        assertTrue(s.copy(buy = s.buy.copy(prices = listOf("4000"))).failure("GOLD")!!.contains("Buy < Sell"))
    }
    @Test fun retriesReadsUntilTwoFreshSnapshots() = runBlocking {
        var reads = 0
        val result = TicketReadiness.awaitReady("GOLD", {
            reads++
            if (reads == 1) ready().copy(volumeCount = 0) else ready()
        }, timeoutMs = 1_000, intervalMs = 1)
        assertEquals(3, reads)
        assertNull(result.failure("GOLD"))
    }
    @Test fun neverAcceptsRepeatedCachedSnapshot() = runBlocking {
        val cached = ready()
        try {
            TicketReadiness.awaitReady("GOLD", { cached }, timeoutMs = 30, intervalMs = 1)
            fail("Cached snapshot must not pass")
        } catch (error: IllegalStateException) { assertTrue(error.message!!.contains("zapamiętany")) }
    }
    @Test fun timeoutIncludesSpecificReason() = runBlocking {
        try {
            TicketReadiness.awaitReady("GOLD", { ready().copy(volumeCount = 0) }, timeoutMs = 30, intervalMs = 1)
            fail("Expected timeout")
        } catch (error: IllegalStateException) { assertTrue(error.message!!.contains("wolumenu")) }
    }
}
