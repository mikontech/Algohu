package com.example.algohud.bot

import com.example.algohud.bot.TestBars.M1
import com.example.algohud.core.Bar
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime

class UsOpenSessionTest {
    private val warsaw = ZoneId.of("Europe/Warsaw")
    private fun d(s: String) = LocalDate.parse(s)
    private fun utc(date: String, h: Int, m: Int, s: Int = 0) =
        ZonedDateTime.of(d(date), LocalTime.of(h, m, s), ZoneOffset.UTC).toInstant().toEpochMilli()
    private fun pl(date: String, h: Int, m: Int) = ZonedDateTime.of(d(date), LocalTime.of(h, m), warsaw).toInstant().toEpochMilli()

    @Test fun `open is 15-30 in Poland in normal weeks - winter and summer`() {
        assertEquals(LocalTime.of(15, 30), UsOpenSession.openLocalTime(d("2026-11-12"), warsaw))
        assertEquals(LocalTime.of(15, 30), UsOpenSession.openLocalTime(d("2026-07-01"), warsaw))
        assertEquals(utc("2026-11-12", 14, 30), UsOpenSession.openMs(d("2026-11-12"))) // EST = UTC−5
        assertEquals(utc("2026-07-01", 13, 30), UsOpenSession.openMs(d("2026-07-01"))) // EDT = UTC−4
    }

    @Test fun `open is 14-30 in Poland in the weeks when only the US has switched clocks`() {
        // Wiosna 2026: USA 8 marca, Europa 29 marca. Jesień 2026: Europa 25 października, USA 1 listopada.
        assertEquals(LocalTime.of(15, 30), UsOpenSession.openLocalTime(d("2026-03-06"), warsaw))
        assertEquals(LocalTime.of(14, 30), UsOpenSession.openLocalTime(d("2026-03-09"), warsaw))
        assertEquals(LocalTime.of(14, 30), UsOpenSession.openLocalTime(d("2026-03-27"), warsaw))
        assertEquals(LocalTime.of(15, 30), UsOpenSession.openLocalTime(d("2026-03-30"), warsaw))
        assertEquals(LocalTime.of(15, 30), UsOpenSession.openLocalTime(d("2026-10-23"), warsaw))
        assertEquals(LocalTime.of(14, 30), UsOpenSession.openLocalTime(d("2026-10-26"), warsaw))
        assertEquals(LocalTime.of(14, 30), UsOpenSession.openLocalTime(d("2026-10-30"), warsaw))
        assertEquals(LocalTime.of(15, 30), UsOpenSession.openLocalTime(d("2026-11-02"), warsaw))
        // 2027: USA 14 marca, Europa 28 marca
        assertEquals(LocalTime.of(14, 30), UsOpenSession.openLocalTime(d("2027-03-16"), warsaw))
    }

    @Test fun `window in a clock-change week follows New York, not Polish time`() {
        // 10 marca 2026: otwarcie o 14:30 czasu polskiego
        assertTrue(UsOpenSession.status(pl("2026-03-10", 14, 40)).active)
        assertFalse(UsOpenSession.status(pl("2026-03-10", 15, 35)).active) // 65 min po otwarciu – już poza oknem
        // zwykły tydzień: 14:40 to jeszcze przed oknem
        assertFalse(UsOpenSession.status(pl("2026-11-12", 14, 40)).active)
        assertTrue(UsOpenSession.status(pl("2026-11-12", 15, 40)).active)
    }

    @Test fun `phases of the window`() {
        val day = "2026-11-12" // otwarcie 14:30 UTC
        fun phase(h: Int, m: Int, s: Int = 0) = UsOpenSession.status(utc(day, h, m, s)).phase
        assertEquals(UsOpenSession.Phase.OFF, phase(14, 24, 59))
        assertEquals(UsOpenSession.Phase.PRE_OPEN, phase(14, 25))
        assertEquals(UsOpenSession.Phase.PRE_OPEN, phase(14, 29, 59))
        assertEquals(UsOpenSession.Phase.NO_ENTRY, phase(14, 30))
        assertEquals(UsOpenSession.Phase.NO_ENTRY, phase(14, 31, 59))
        assertEquals(UsOpenSession.Phase.BUILDING_RANGE, phase(14, 32))
        assertEquals(UsOpenSession.Phase.BUILDING_RANGE, phase(14, 34, 59))
        assertEquals(UsOpenSession.Phase.BREAKOUT, phase(14, 35))
        assertEquals(UsOpenSession.Phase.BREAKOUT, phase(15, 29, 59))
        assertEquals(UsOpenSession.Phase.OFF, phase(15, 30))
        assertTrue(UsOpenSession.status(utc(day, 14, 31)).blocksEntries)
        assertFalse(UsOpenSession.status(utc(day, 14, 40)).blocksEntries)
        assertEquals(-3L, UsOpenSession.status(utc(day, 14, 27)).minutesFromOpen)
    }

    @Test fun `no session on NYSE holidays and weekends`() {
        val thanksgiving = UsOpenSession.status(utc("2026-11-26", 14, 40))
        assertFalse(thanksgiving.active)
        assertEquals("święto giełdowe w USA", thanksgiving.closedWhy)
        assertNull(thanksgiving.openMs)
        assertFalse(UsOpenSession.status(utc("2026-07-03", 13, 40)).active)  // Dzień Niepodległości (obchodzony)
        assertFalse(UsOpenSession.status(utc("2027-06-18", 13, 40)).active)  // Juneteenth (obchodzony)
        assertFalse(UsOpenSession.status(utc("2026-11-14", 14, 40)).active)  // sobota
        assertEquals("weekend", UsOpenSession.status(utc("2026-11-15", 14, 40)).closedWhy)
        assertTrue(UsOpenSession.status(utc("2026-11-27", 14, 40)).active)   // dzień po Święcie Dziękczynienia – sesja jest
        assertFalse(UsOpenSession.isTradingDay(d("2026-12-25")))
        assertTrue(UsOpenSession.isTradingDay(d("2026-12-24")))
        // własna lista dat z konfiguracji
        assertFalse(UsOpenSession.status(utc("2026-11-12", 14, 40), holidays = setOf(d("2026-11-12"))).active)
    }

    // ── Opening Range Breakout ──

    private val open = UsOpenSession.openMs(d("2026-11-12"))
    private fun bar(min: Int, o: Double, h: Double, l: Double, c: Double) = Bar(open + min * M1, o, h, l, c)
    /** Zakres 4000–4004 z pierwszych 5 minut. */
    private val rangeBars = listOf(
        bar(-2, 4001.0, 4001.5, 4000.5, 4001.0), bar(-1, 4001.0, 4001.8, 4000.8, 4001.5),
        bar(0, 4001.5, 4003.0, 4000.0, 4002.0), bar(1, 4002.0, 4004.0, 4001.0, 4003.0), bar(2, 4003.0, 4003.5, 4001.5, 4002.0),
        bar(3, 4002.0, 4003.0, 4001.0, 4002.5), bar(4, 4002.5, 4003.8, 4002.0, 4003.0)
    )
    private val perPrice = 2.5 // PLN za 1,0 ruchu ceny przy 0,007 lota
    private fun orb(bars: List<Bar>, confirm: (Int) -> String? = { null }, pp: Double = perPrice) =
        UsOpenSession.orb(bars, open, spread = 0.3, atrM1 = 1.0, perPricePln = pp, confirm = confirm)

    @Test fun `range is built from the first five minutes only`() {
        assertNull(UsOpenSession.range(rangeBars.dropLast(1), open))
        val r = UsOpenSession.range(rangeBars, open)!!
        assertEquals(4004.0, r.high, 1e-9)
        assertEquals(4000.0, r.low, 1e-9)
        assertEquals(5, r.bars)
    }

    @Test fun `no entry while the range is being built, even on a breakout candle`() {
        val early = rangeBars.take(4) + bar(2, 4003.0, 4010.0, 4003.0, 4009.0)
        assertTrue(orb(early) is UsOpenSession.Orb.Wait)
    }

    @Test fun `candle closing inside the range waits, a wick outside is not a breakout`() {
        val r = orb(rangeBars + bar(5, 4003.0, 4005.5, 4002.5, 4003.5))
        assertTrue(r is UsOpenSession.Orb.Wait)
    }

    @Test fun `close above the range enters long with the stop behind the opposite edge`() {
        val r = orb(rangeBars + bar(5, 4003.0, 4005.2, 4002.8, 4005.0), pp = 3.0) as UsOpenSession.Orb.Enter
        assertEquals(1, r.setup.dir)
        assertEquals(4000.0 - 0.3, r.setup.stop, 1e-9) // dolna krawędź − zapas max(0,2×ATR, spread)
        val loss = (4005.0 + 0.15 - r.setup.stop) * 3.0
        assertTrue("strata $loss", loss in 15.0..20.0)
        assertTrue(r.setup.reason.startsWith("ORB US Open LONG"))
    }

    @Test fun `close below the range enters short`() {
        val r = orb(rangeBars + bar(5, 4001.0, 4001.2, 3998.8, 3999.0), pp = 3.0) as UsOpenSession.Orb.Enter
        assertEquals(-1, r.setup.dir)
        assertEquals(4004.0 + 0.3, r.setup.stop, 1e-9)
    }

    @Test fun `too wide range - stop would exceed 20 PLN - setup skipped`() {
        val wide = rangeBars.map { if (it.start == open) it.copy(low = 3990.0) else it }
        val r = orb(wide + bar(5, 4003.0, 4005.2, 4002.8, 4005.0))
        assertTrue(r is UsOpenSession.Orb.Skip)
        assertTrue((r as UsOpenSession.Orb.Skip).note.contains("za szeroki"))
    }

    @Test fun `narrow range - stop widened to the 15 PLN minimum`() {
        val r = orb(rangeBars + bar(5, 4003.0, 4004.6, 4002.8, 4004.5), pp = 2.5) as UsOpenSession.Orb.Enter
        val entry = 4004.5 + 0.15
        assertEquals(15.0, (entry - r.setup.stop) * 2.5, 1e-6)
    }

    @Test fun `breakout against the score is skipped`() {
        val r = orb(rangeBars + bar(5, 4003.0, 4005.2, 4002.8, 4005.0), confirm = { dir -> if (dir > 0) "score LONG 2.0 < 4.0" else null })
        assertTrue(r is UsOpenSession.Orb.Skip)
    }

    @Test fun `breakout after the window is ignored`() {
        val r = orb(rangeBars + bar(60, 4003.0, 4005.2, 4002.8, 4005.0))
        assertTrue(r is UsOpenSession.Orb.Wait)
    }
}
