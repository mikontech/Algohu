package com.example.algohud.execution

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Scenariusz: ustawienie SL/TP → otwarcie → potwierdzenie SL/TP → 5 s → przywrócenie suwaków.
 * Ekran XTB symuluje tu prosty obiekt formularza – tylko w testach; kod produkcyjny czyta prawdziwy ekran.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SlTpFormCoordinatorTest {
    private val userForm = TicketSlTpState(slEnabled = false, slValue = null, tpEnabled = true, tpValue = "4 090.00")

    private class Form(var state: TicketSlTpState)

    private fun position(op: SlTpFormCoordinator.Operation) =
        SlTpFormCoordinator.ConfirmedPosition(op.opId, op.instrument, "BUY|0.001|@ 4 072.50", 4069.25, 4076.40, 0L)

    private fun TestScope.coordinator() = SlTpFormCoordinator(this, restoreDelayMs = 5_000, retryDelayMs = 1_000, maxDeferrals = 3, clock = { testScheduler.currentTime })

    @Test fun restoresFiveSecondsAfterConfirmation() = runTest {
        val c = coordinator()
        val form = Form(userForm)
        val reports = mutableListOf<String>()
        val op = c.beginEntry("op1", "GOLD", form.state)
        form.state = TicketSlTpState(true, "4 069.25", true, "4 076.40") // bot wypełnił bilet i otworzył pozycję
        advanceTimeBy(30_000) // potwierdzenie przychodzi później – timer jeszcze NIE biegnie
        assertEquals("4 069.25", form.state.slValue)
        c.onConfirmed(op, position(op), restore = { form.state = it.baseline; SlTpFormCoordinator.RestoreOutcome.Done }, report = { reports += it })
        advanceTimeBy(4_999); runCurrent()
        assertTrue("za wcześnie", form.state.slEnabled)
        advanceTimeBy(2); runCurrent()
        assertEquals(userForm, form.state)
        assertEquals(1, reports.size)
        assertFalse(c.hasPendingRestore("GOLD"))
    }

    @Test fun failedOpeningDoesNotRestore() = runTest {
        val c = coordinator()
        var restored = false
        val op = c.beginEntry("op1", "GOLD", userForm)
        c.onFailed(op) // otwarcie nieudane / SL-TP niepotwierdzone → onConfirmed nigdy nie jest wołane
        advanceTimeBy(60_000); runCurrent()
        assertFalse(restored)
        assertFalse(c.hasPendingRestore("GOLD"))
    }

    @Test fun newEntryCancelsPendingRestoreAndKeepsOriginalBaseline() = runTest {
        val c = coordinator()
        val restoredTo = mutableListOf<Pair<String, TicketSlTpState>>()
        val restore: suspend (SlTpFormCoordinator.Operation) -> SlTpFormCoordinator.RestoreOutcome = {
            restoredTo += it.opId to it.baseline; SlTpFormCoordinator.RestoreOutcome.Done
        }
        val op1 = c.beginEntry("op1", "GOLD", userForm)
        c.onConfirmed(op1, position(op1), restore) {}
        advanceTimeBy(2_000)
        // Szybkie drugie wejście: bilet zawiera jeszcze wartości bota – nie mogą stać się "stanem poprzednim".
        val op2 = c.beginEntry("op2", "GOLD", TicketSlTpState(true, "4 069.25", true, "4 076.40"))
        assertEquals(userForm, op2.baseline)
        advanceTimeBy(10_000); runCurrent()
        assertTrue("reset op1 anulowany", restoredTo.isEmpty())
        c.onConfirmed(op2, position(op2), restore) {}
        advanceTimeBy(5_001); runCurrent()
        assertEquals(listOf("op2" to userForm), restoredTo)
    }

    @Test fun staleConfirmationOfOlderOperationIsIgnored() = runTest {
        val c = coordinator()
        var calls = 0
        val op1 = c.beginEntry("op1", "GOLD", userForm)
        c.beginEntry("op2", "GOLD", userForm)
        c.onConfirmed(op1, position(op1), restore = { calls++; SlTpFormCoordinator.RestoreOutcome.Done }) {}
        advanceTimeBy(20_000); runCurrent()
        assertEquals(0, calls)
    }

    @Test fun busyScreenDefersRestore() = runTest {
        val c = coordinator()
        var attempts = 0
        val op = c.beginEntry("op1", "GOLD", userForm)
        c.onConfirmed(op, position(op), restore = {
            attempts++
            if (attempts < 3) SlTpFormCoordinator.RestoreOutcome.Busy else SlTpFormCoordinator.RestoreOutcome.Done
        }) {}
        advanceTimeBy(5_001); runCurrent()
        assertEquals(1, attempts)
        advanceTimeBy(2_001); runCurrent()
        assertEquals(3, attempts)
        assertFalse(c.hasPendingRestore("GOLD"))
    }

    @Test fun permanentlyBusyGivesUpAndReports() = runTest {
        val c = coordinator()
        val reports = mutableListOf<String>()
        val op = c.beginEntry("op1", "GOLD", userForm)
        c.onConfirmed(op, position(op), restore = { SlTpFormCoordinator.RestoreOutcome.Busy }) { reports += it }
        advanceTimeBy(30_000); runCurrent()
        assertTrue(reports.single().contains("nie przywrócono"))
        assertFalse(c.hasPendingRestore("GOLD"))
    }

    @Test fun differentInstrumentsAreIndependent() = runTest {
        val c = coordinator()
        val restored = mutableListOf<String>()
        val gold = c.beginEntry("g1", "GOLD", userForm)
        val oil = c.beginEntry("o1", "OIL", userForm)
        c.onConfirmed(gold, position(gold), restore = { restored += it.instrument; SlTpFormCoordinator.RestoreOutcome.Done }) {}
        c.onConfirmed(oil, position(oil), restore = { restored += it.instrument; SlTpFormCoordinator.RestoreOutcome.Done }) {}
        advanceTimeBy(5_001); runCurrent()
        assertEquals(setOf("GOLD", "OIL"), restored.toSet())
        assertEquals(2, c.confirmedPositions().size)
    }
}
