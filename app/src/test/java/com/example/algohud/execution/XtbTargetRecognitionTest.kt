package com.example.algohud.execution
import org.junit.Assert.*
import org.junit.Test
class XtbTargetRecognitionTest {
    @Test fun closedMarketTicketDoesNotNeedBuySellLabelsOrPrices() {
        val markers = XtbTargetRecognition.hasOrderControls(emptyList(), listOf(
            "GOLD", "Zlecenie Market", "Wolumen", "Rynek przed otwarciem"
        ))
        assertTrue(XtbTargetRecognition.matches("com.xtb.xmobile2", "GOLD", "GOLD", markers))
        assertTrue(XtbTargetRecognition.hasOrderControls(listOf("MarketSizeInput"), emptyList()))
    }
    @Test fun recentGoldTileIsNotAnOpenTicket() {
        val markers = XtbTargetRecognition.hasOrderControls(emptyList(), listOf(
            "Instrumenty", "Ostatnio wyszukiwane", "GOLD", "CFD", "Gold & Silver"
        ))
        assertFalse(XtbTargetRecognition.matches("com.xtb.xmobile2", "GOLD", "GOLD", markers))
    }
    @Test fun acceptsTicketWithoutVisibleBuySellPrices() {
        for (title in listOf("GOLD", "GOLD\nCFD", " GOLD\tRynek otwarty "))
            assertTrue(XtbTargetRecognition.matches("com.xtb.xmobile2", title, "GOLD", true))
    }
    @Test fun searchResultsAndOtherAppsAreNotTickets() {
        assertFalse(XtbTargetRecognition.matches("com.xtb.xmobile2", "GOLD", "GOLD", false))
        assertFalse(XtbTargetRecognition.matches("com.example.algohud", "GOLD", "GOLD", true))
        for (title in listOf("GOLD 24/7", "Gold.com", "SILVER"))
            assertFalse(XtbTargetRecognition.matches("com.xtb.xmobile2", title, "GOLD", true))
    }
}
