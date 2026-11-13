package com.example.algohud.bot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZonedDateTime

class MarketHoursTest {
    private fun pl(y: Int, mo: Int, d: Int, h: Int, mi: Int) =
        ZonedDateTime.of(y, mo, d, h, mi, 0, 0, UsSession.PL).toInstant().toEpochMilli()

    @Test fun goldDailyBreakInPolishTimeNormally() {
        assertTrue(MarketHours.status("GOLD", pl(2026, 11, 12, 22, 59)).open)
        assertFalse(MarketHours.status("GOLD", pl(2026, 11, 12, 23, 30)).open)
        assertFalse(MarketHours.status("GOLD", pl(2026, 11, 13, 0, 2)).open)   // zapas 5 min po otwarciu
        assertTrue(MarketHours.status("GOLD", pl(2026, 11, 13, 0, 6)).open)
    }

    @Test fun goldBreakShiftsInDstMismatchWeek() {
        // 28.10.2026: Europa już na czasie zimowym, USA jeszcze na letnim → przerwa 22:00–23:05 PL
        assertFalse(MarketHours.status("GOLD", pl(2026, 10, 28, 22, 30)).open)
        assertTrue(MarketHours.status("GOLD", pl(2026, 10, 28, 23, 10)).open)
        assertTrue(MarketHours.status("GOLD", pl(2026, 10, 28, 21, 50)).open)
    }

    @Test fun goldWeekend() {
        assertFalse(MarketHours.status("GOLD", pl(2026, 11, 13, 23, 30)).open)  // piątek po 17:00 ET
        assertFalse(MarketHours.status("GOLD", pl(2026, 11, 14, 12, 0)).open)  // sobota
        assertFalse(MarketHours.status("GOLD", pl(2026, 11, 15, 23, 30)).open)  // niedziela przed 18:05 ET
        assertTrue(MarketHours.status("GOLD", pl(2026, 11, 16, 0, 10)).open)   // niedziela 18:10 ET
    }

    @Test fun minutesToCloseCountsToFivePmNewYork() {
        // 12.11.2026 22:30 PL = 16:30 ET → 30 min do przerwy
        assertEquals(30L, MarketHours.status("GOLD", pl(2026, 11, 12, 22, 30)).minutesToClose)
    }
}
