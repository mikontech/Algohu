package com.example.algohud.execution

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class UiActionRecoveryTest {
    @Before fun clearState() {
        val block = UiActionRecovery.blocked
        UiActionRecovery.observeReadyScreen(block?.instrument ?: "TEST", 100.0, 101.0)
    }

    @Test fun cleanupFailureDoesNotReplaceSuccessfulResult() = runBlocking {
        var actions = 0
        var logged = false
        val result = UiActionRecovery.run("TEST", action = { actions++; "submitted" },
            restore = { error("missing buttons") }, onRecoveryError = { logged = true })
        assertEquals("submitted", result)
        assertEquals(1, actions)
        assertTrue(logged)
        assertNotNull(UiActionRecovery.blocked)
    }

    @Test fun actionErrorIsPreservedAndCleanupStillRuns() = runBlocking {
        val original = IllegalArgumentException("action failed")
        var restored = false
        try {
            UiActionRecovery.run<Unit>("TEST", action = { throw original },
                restore = { restored = true }, onRecoveryError = {})
            fail("Expected original exception")
        } catch (actual: IllegalArgumentException) {
            assertSame(original, actual)
        }
        assertTrue(restored)
    }

    @Test fun cancellationIsPreservedAndCleanupRuns() = runBlocking {
        val original = CancellationException("cancelled")
        var restored = false
        try {
            UiActionRecovery.run<Unit>("TEST", action = { throw original },
                restore = { delay(1); restored = true }, onRecoveryError = {})
            fail("Expected cancellation")
        } catch (actual: CancellationException) {
            assertSame(original, actual)
        }
        assertTrue(restored)
    }

    @Test fun cleanupTimeoutBlocksEntriesButReturnsResult() = runBlocking {
        val result = UiActionRecovery.run("TEST", timeoutMs = 20L,
            action = { 7 }, restore = { delay(10_000) }, onRecoveryError = {})
        assertEquals(7, result)
        assertNotNull(UiActionRecovery.blocked)
    }

    @Test fun onlyValidMatchingQuoteClearsBlock() = runBlocking {
        UiActionRecovery.run("TEST", action = { Unit },
            restore = { error("not ready") }, onRecoveryError = {})
        UiActionRecovery.observeReadyScreen("OTHER", 100.0, 101.0)
        assertNotNull(UiActionRecovery.blocked)
        UiActionRecovery.observeReadyScreen("TEST", null, 101.0)
        assertNotNull(UiActionRecovery.blocked)
        UiActionRecovery.observeReadyScreen("TEST", 100.0, 101.0)
        assertNull(UiActionRecovery.blocked)
    }
}
