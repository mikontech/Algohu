package com.example.algohud.bot

import com.example.algohud.core.Bar
import org.junit.Assert.*
import org.junit.Test
import java.util.Locale

class CandleWatchTest {
    private fun bar(t: Long, o: Double = 100.0, h: Double = 104.0, l: Double = 98.0, c: Double = 103.0) = Bar(t, o, h, l, c)

    @Test fun morphologySumsToRange() {
        val s = CandleWatch.shape(bar(0))
        assertEquals(3.0, s.body, 0.0)
        assertEquals(1.0, s.upper, 0.0)
        assertEquals(2.0, s.lower, 0.0)
        assertEquals(s.range, s.body + s.upper + s.lower, 0.0)
        assertEquals(5.0 / 6, s.closePosition!!, 1e-9)
    }

    @Test fun flatCandleHasNoDivisionByZero() {
        val b = bar(0, 100.0, 100.0, 100.0, 100.0)
        assertNull(CandleWatch.shape(b).closePosition)
        val text = CandleWatch.snapshot(listOf(b), emptyList(), null, null, 60_000, 60_000).report()
        assertFalse(text.contains("NaN"))
        assertFalse(text.contains("Infinity"))
    }

    @Test fun engulfingNeedsAdjacentOppositeBody() {
        val a = bar(0, 103.0, 104.0, 98.0, 100.0)
        val b = bar(60_000, 99.0, 105.0, 98.0, 104.0)
        assertTrue(CandleWatch.patterns(listOf(a, b), 60_000).any { it.startsWith("objęcie") })
        assertFalse(CandleWatch.patterns(listOf(a, b.copy(start = 120_000)), 60_000).any { it.startsWith("objęcie") })
    }

    @Test fun distinguishesBreakoutFromReturn() {
        val a = bar(0)
        val back = bar(60_000, 103.0, 106.0, 100.0, 102.0)
        assertTrue(CandleWatch.patterns(listOf(a, back), 60_000).contains("powrót pod poprzednie H po naruszeniu"))
        assertTrue(CandleWatch.patterns(listOf(a, back.copy(close = 105.0)), 60_000).contains("C ponad poprzednim H"))
    }

    @Test fun liveCandleIsSeparateAndUnconfirmed() {
        val f = CandleWatch.snapshot(listOf(bar(0)), emptyList(), bar(60_000), null, 90_000, 89_000)
        assertEquals(1, f.m1.closed.size)
        assertEquals(60_000L, f.m1.live!!.start)
        assertTrue(f.report().contains("W BUDOWIE – NIEPOTWIERDZONA"))
        assertTrue(f.report().contains("czas=50%"))
        assertEquals(1000L, f.tickAgeMs)
    }

    @Test fun expiredCurrentIsNotInventedAsClosedCandle() {
        val f = CandleWatch.frame("M1", 60_000, emptyList(), bar(0), 60_000)
        assertNull(f.live)
        assertTrue(f.closed.isEmpty())
        assertTrue(f.warnings.any { it.contains("bieżąca świeca nieaktualna") })
    }

    @Test fun rejectsMalformedFutureAndIncompleteBars() {
        val f = CandleWatch.frame("M1", 60_000, listOf(bar(0, h = Double.NaN), bar(60_000, h = 99.0), bar(120_000)), null, 150_000)
        assertTrue(f.closed.isEmpty())
        assertTrue(f.warnings.isNotEmpty())
    }

    @Test fun reportsGapsWithoutFillingThem() {
        val f = CandleWatch.frame("M1", 60_000, listOf(bar(0), bar(120_000)), null, 180_000)
        assertEquals(2, f.closed.size)
        assertTrue(f.warnings.any { it.contains("luki") })
    }

    @Test fun stalePriceIsVisibleEvenWithRecentCandle() {
        val f = CandleWatch.snapshot(listOf(bar(0)), emptyList(), bar(60_000), null, 90_000, 65_000)
        assertTrue(f.summary().contains("brak świeżej ceny"))
        assertTrue(f.report().contains("cena nieświeża"))
    }

    @Test fun futureTickIsNotPresentedAsFresh() {
        assertNull(CandleWatch.snapshot(emptyList(), emptyList(), null, null, 90_000, 100_000).tickAgeMs)
    }

    @Test fun snapshotIsBoundedAndLocaleIndependent() {
        val old = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("pl-PL"))
            val bars = (0..100).map { bar(it * 60_000L) }
            val f = CandleWatch.snapshot(bars, emptyList(), null, null, 101 * 60_000L, null)
            assertEquals(30, f.m1.closed.size)
            assertTrue(f.report().contains("100.00"))
            assertTrue(f.report().length < 15_000)
        } finally { Locale.setDefault(old) }
    }
}
