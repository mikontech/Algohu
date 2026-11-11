package com.example.algohud.execution

import org.junit.Assert.*
import org.junit.Test

class TicketProtectionResetTest {
    private val ticket = TicketProtectionReset.Context(
        "GOLD", "GOLD Rynek otwarty", true, true, true, false)

    @Test fun fullCurrentTicketCanBeReset() {
        assertTrue(TicketProtectionReset.canReset(ticket.copy(protectionCount = 0)))
    }
    @Test fun postEntryTicketWithOnlyProtectionCanBeReset() {
        assertFalse(TicketProtectionReset.canReset(ticket.copy(protectionCount = 0)))
        assertTrue(TicketProtectionReset.canReset(ticket.copy(protectionCount = 2)))
    }
    @Test fun positionEditorStillCannotBeReset() {
        assertFalse(TicketProtectionReset.canReset(ticket.copy(hasModifyPosition = true, protectionCount = 2)))
    }
    @Test fun wrongOrMissingInstrumentCannotBeReset() {
        assertFalse(TicketProtectionReset.canReset(ticket.copy(title = "US100", protectionCount = 2)))
        assertFalse(TicketProtectionReset.canReset(ticket.copy(title = null, protectionCount = 2)))
        assertFalse(TicketProtectionReset.canReset(ticket.copy(title = "Nowe zlecenie Market", protectionCount = 2)))
    }
    @Test fun missingVolumeStillPreventsReset() {
        assertFalse(TicketProtectionReset.canReset(ticket.copy(hasVolume = false, protectionCount = 2)))
    }
    @Test fun ambiguousProtectionCountDoesNotReset() {
        assertFalse(TicketProtectionReset.canReset(ticket.copy(protectionCount = 1)))
        assertFalse(TicketProtectionReset.canReset(ticket.copy(protectionCount = 3)))
    }
    @Test fun bothBuyAndSellCanOverrideProtectionCount() {
        assertTrue(TicketProtectionReset.canReset(ticket.copy(hasBuy = true, hasSell = true, protectionCount = 0)))
    }
}
