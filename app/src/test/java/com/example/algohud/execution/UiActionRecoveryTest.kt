package com.example.algohud.execution

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class UiActionRecoveryTest {
    private var clock = 1_000L
    private lateinit var state: TicketRecoveryState
    @Before fun setUp() { state = TicketRecoveryState { clock } }
    private fun ready(instrument: String = "TEST") = TicketReadiness.Snapshot(
        true, listOf(instrument), 1, false,
        TicketReadiness.Side(1, true, listOf("100.0")),
        TicketReadiness.Side(1, true, listOf("101.0")), ++clock)

    @Test fun cleanupFailureDoesNotReplaceSuccessfulResult() = runBlocking {
        var actions = 0
        var logged = false
        val result = state.run("TEST", action = { actions++; "submitted" },
            restore = { error("missing buttons") }, onRecoveryError = { logged = true })
        assertEquals("submitted", result)
        assertEquals(1, actions)
        assertTrue(logged)
        assertNotNull(state.blocked)
    }
    @Test fun actionErrorIsPreservedAndCleanupStillRuns() = runBlocking {
        val original = IllegalArgumentException("action failed")
        var restored = false
        try {
            state.run<Unit>("TEST", action = { throw original },
                restore = { restored = true }, onRecoveryError = {})
            fail("Expected original exception")
        } catch (actual: IllegalArgumentException) { assertSame(original, actual) }
        assertTrue(restored)
    }
    @Test fun cancellationIsPreservedAndCleanupRuns() = runBlocking {
        val original = CancellationException("cancelled")
        var restored = false
        try {
            state.run<Unit>("TEST", action = { throw original },
                restore = { delay(1); restored = true }, onRecoveryError = {})
            fail("Expected cancellation")
        } catch (actual: CancellationException) { assertSame(original, actual) }
        assertTrue(restored)
    }
    @Test fun cleanupTimeoutBlocksEntriesButReturnsResult() = runBlocking {
        val result = state.run("TEST", timeoutMs = 20,
            action = { 7 }, restore = { delay(10_000) }, onRecoveryError = {})
        assertEquals(7, result)
        assertNotNull(state.blocked)
    }
    @Test fun completeMatchingSnapshotClearsQuoteOnlyFailure() = runBlocking {
        state.run("TEST", action = { Unit }, restore = { error("not ready") }, onRecoveryError = {})
        state.observeReadyScreen(ready("OTHER"))
        assertNotNull(state.blocked)
        state.observeReadyScreen(ready().copy(volumeCount = 0))
        assertNotNull(state.blocked)
        state.observeReadyScreen(ready())
        assertNull(state.blocked)
    }
    @Test fun pricesAloneNeverClearPendingReset() {
        val token = state.beginTicketReset("TEST")!!
        state.observeReadyScreen(ready())
        assertNotNull(state.blocked)
        assertNull(state.beginTicketReset("TEST"))
        val beforeReset = ready()
        assertTrue(state.markTicketResetVerified(token))
        state.observeReadyScreen(beforeReset)
        assertNotNull(state.blocked)
        state.observeReadyScreen(ready())
        assertNull(state.blocked)
    }
    @Test fun staleResetTokenCannotClearAnotherEntry() {
        val first = state.beginTicketReset("TEST")!!
        assertTrue(state.markTicketResetVerified(first))
        state.observeReadyScreen(ready())
        val second = state.beginTicketReset("TEST")!!
        assertFalse(state.markTicketResetVerified(first))
        state.observeReadyScreen(ready())
        assertNotNull(state.blocked)
        assertTrue(state.markTicketResetVerified(second))
    }
    @Test fun resetFailureKeepsEntryBlockedEvenWithValidPrices() = runBlocking {
        state.beginTicketReset("TEST")
        state.run("TEST", action = { "submitted" },
            restore = { error("SL nadal aktywny") }, onRecoveryError = {})
        state.observeReadyScreen(ready())
        assertTrue(state.blocked!!.reason.contains("SL nadal aktywny"))
    }
    @Test fun oldSnapshotCannotClearFailure() = runBlocking {
        state.run("TEST", action = { Unit }, restore = { error("bad") }, onRecoveryError = {})
        val snapshot = ready()
        clock += 2_000_000_000L
        state.observeReadyScreen(snapshot)
        assertNotNull(state.blocked)
    }
}
