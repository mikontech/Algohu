package com.example.algohud.execution

import org.junit.Assert.*
import org.junit.Test

class TicketProtectionResetTest {
    private val ticket = TicketProtectionReset.Context(
        "GOLD", "GOLD Rynek otwarty", true, true, true, false)

    @Test fun recognizedTicketCanBeReset() {
        assertTrue(TicketProtectionReset.canReset(ticket))
    }
    @Test fun positionEditorCannotBeReset() {
        assertFalse(TicketProtectionReset.canReset(ticket.copy(hasModifyPosition = true)))
    }
    @Test fun wrongOrMissingInstrumentCannotBeReset() {
        assertFalse(TicketProtectionReset.canReset(ticket.copy(title = "US100")))
        assertFalse(TicketProtectionReset.canReset(ticket.copy(title = null)))
        assertFalse(TicketProtectionReset.canReset(ticket.copy(title = "Nowe zlecenie Market")))
    }
    @Test fun missingTicketControlsPreventReset() {
        assertFalse(TicketProtectionReset.canReset(ticket.copy(hasVolume = false)))
        assertFalse(TicketProtectionReset.canReset(ticket.copy(hasBuy = false)))
        assertFalse(TicketProtectionReset.canReset(ticket.copy(hasSell = false)))
    }
}
