package com.example.algohud.execution

import org.junit.Assert.*
import org.junit.Test

class TicketProtectionResetTest {
    private val fullTicket = TicketProtectionReset.Context(
        "GOLD", "GOLD Rynek otwarty", true, true, true, false)
    private val postEntry = fullTicket.copy(hasBuy = false, hasSell = false, protectionCount = 2)

    @Test fun fullCurrentTicketCanBeReset() {
        assertTrue(TicketProtectionReset.canReset(fullTicket))
    }
    @Test fun postEntryTicketWithBothProtectionSectionsCanBeReset() {
        assertTrue(TicketProtectionReset.canReset(postEntry))
    }
    @Test fun postEntryTicketWithoutBothSectionsCannotBeReset() {
        assertFalse(TicketProtectionReset.canReset(postEntry.copy(protectionCount = 0)))
    }
    @Test fun positionEditorStillCannotBeReset() {
        assertFalse(TicketProtectionReset.canReset(postEntry.copy(hasModifyPosition = true)))
        assertFalse(TicketProtectionReset.canReset(fullTicket.copy(hasModifyPosition = true)))
    }
    @Test fun wrongOrMissingInstrumentCannotBeReset() {
        assertFalse(TicketProtectionReset.canReset(postEntry.copy(title = "US100")))
        assertFalse(TicketProtectionReset.canReset(postEntry.copy(title = null)))
        assertFalse(TicketProtectionReset.canReset(postEntry.copy(title = "Nowe zlecenie Market")))
    }
    @Test fun missingVolumeStillPreventsReset() {
        assertFalse(TicketProtectionReset.canReset(postEntry.copy(hasVolume = false)))
        assertFalse(TicketProtectionReset.canReset(fullTicket.copy(hasVolume = false)))
    }
    @Test fun ambiguousProtectionCountDoesNotResetPostEntryScreen() {
        assertFalse(TicketProtectionReset.canReset(postEntry.copy(protectionCount = 1)))
        assertFalse(TicketProtectionReset.canReset(postEntry.copy(protectionCount = 3)))
    }
    @Test fun onlyOneTradeButtonDoesNotCountAsFullTicket() {
        assertFalse(TicketProtectionReset.canReset(postEntry.copy(hasBuy = true, protectionCount = 0)))
        assertFalse(TicketProtectionReset.canReset(postEntry.copy(hasSell = true, protectionCount = 0)))
    }
}
