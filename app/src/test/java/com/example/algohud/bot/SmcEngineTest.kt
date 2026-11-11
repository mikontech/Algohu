package com.example.algohud.bot

import com.example.algohud.core.Bar
import com.example.algohud.core.Side
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class SmcEngineTest {
    private val zone = ZoneId.of("Europe/Warsaw")
    private val engine = SmcEngine(zone)
    private fun at(h: Int, m: Int) = ZonedDateTime.of(2026, 11, 11, h, m, 0, 0, zone).toInstant().toEpochMilli()

    private fun bar(i: Int, t0: Long, o: Double, h: Double, l: Double, c: Double) = Bar(t0 + i * 60_000L, o, h, l, c)

    /** Zakres 1975–2004, potem sweep nad 2004,5 (knot 2005,2, zamknięcie 2004,0) i CHoCH pod 2001,0. */
    private fun sweepShort(t0: Long): List<Bar> {
        val out = ArrayList<Bar>()
        for (i in 0 until 90) {
            val mid = if (i < 45) 1975.0 + i * (29.0 / 45) else 2004.0 - (i - 45) * (4.0 / 45)
            out += bar(i, t0, mid - 0.2, mid + 0.5, mid - 0.5, mid + 0.2)
        }
        var px = 2000.0
        for (i in 90 until 105) {
            val o = px; px += 0.25
            val low = if (i == 102) 2001.0 else o - 0.4
            out += bar(i, t0, o, px + 0.3, low, px)
        }
        out += bar(105, t0, 2003.5, 2005.2, 2003.2, 2004.0)
        out += bar(106, t0, 2004.0, 2004.2, 2002.9, 2003.0)
        out += bar(107, t0, 2003.0, 2003.1, 2001.7, 2001.8)
        out += bar(108, t0, 2001.8, 2001.9, 2000.5, 2000.6)
        return out
    }

    @Test fun sessionsAndBlackout() {
        assertEquals(SmcEngine.Session.ASIA, engine.session(at(3, 0)))
        assertEquals(SmcEngine.Session.LONDON_KZ, engine.session(at(10, 0)))
        assertEquals(SmcEngine.Session.NY_KZ, engine.session(at(15, 0)))
        assertNotNull(engine.newsBlackout(at(14, 30)))
        assertNull(engine.newsBlackout(at(10, 0)))
    }

    @Test fun sweepPlusChochGivesShortWithMinRr() {
        val now = at(10, 50)
        val m1 = sweepShort(now - 109 * 60_000L)
        val r = engine.evaluate(m1, emptyList(), 2000.6, 0.1, now)
        val s = r.setup
        assertNotNull(r.note, s)
        assertEquals(Side.SELL, s!!.side)
        assertTrue(s.stop > 2005.2)
        assertTrue(s.target < 2000.6)
        assertTrue(s.rr >= SmcEngine.MIN_RR)
        assertEquals("swing H 1h", s.swept.name)
    }

    @Test fun noTradeOutsideKillzone() {
        val now = at(13, 0)
        val m1 = sweepShort(now - 109 * 60_000L)
        val r = engine.evaluate(m1, emptyList(), 2000.6, 0.1, now)
        assertNull(r.setup)
        assertTrue(r.note.contains("killzone"))
    }

    @Test fun noChochNoTrade() {
        val now = at(10, 50)
        val m1 = sweepShort(now - 109 * 60_000L).dropLast(2)
        val r = engine.evaluate(m1, emptyList(), 2003.0, 0.1, now)
        assertNull(r.setup)
    }
}
