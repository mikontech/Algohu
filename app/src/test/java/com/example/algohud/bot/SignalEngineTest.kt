package com.example.algohud.bot

import com.example.algohud.bot.TestBars.M1
import com.example.algohud.bot.TestBars.M5
import com.example.algohud.bot.TestBars.T0
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SignalEngineTest {
    /** 4000 świec M1 (~66 h) w trendzie z falowaniem; M5 złożone z M1. */
    private fun trend(drift: Double) = TestBars.wave(4000, drift = drift, amp = 2.0, len = 40.0)

    @Test fun `uptrend - higher timeframe up, long score dominates, never SHORT`() {
        val m1 = trend(+0.05)
        val r = SignalEngine.evaluate(m1, TestBars.m5Of(m1), spread = 0.3)
        assertNull(r.blocked)
        assertEquals(1, r.htfTrend)
        assertTrue("long ${r.longScore} > short ${r.shortScore}", r.longScore > r.shortScore)
        assertTrue(r.decision != SignalEngine.Decision.SHORT)
        assertNotNull(r.confirms(-1)) // short pod prąd trendu H1 bez CHoCH – odmowa
    }

    @Test fun `downtrend - mirror`() {
        val m1 = trend(-0.05)
        val r = SignalEngine.evaluate(m1, TestBars.m5Of(m1), spread = 0.3)
        assertEquals(-1, r.htfTrend)
        assertTrue(r.shortScore > r.longScore)
        assertTrue(r.decision != SignalEngine.Decision.LONG)
        assertNotNull(r.confirms(+1))
    }

    /** Trend z dużą falą (swingi na H1) i małą falą (swingi na M1); [sign] = +1 wzrostowy, −1 spadkowy. */
    private fun swinging(n: Int, sign: Int) = TestBars.series(n) { i ->
        4000.0 + sign * (0.02 * i + 12 * Math.sin(2 * Math.PI * i / 720) + 1.0 * Math.sin(2 * Math.PI * i / 25))
    }

    @Test fun `aligned trend, momentum and fresh BOS give a LONG decision`() {
        val m1 = swinging(3654, +1)
        val r = SignalEngine.evaluate(m1, TestBars.m5Of(m1), spread = 0.3)
        assertEquals(r.reason, SignalEngine.Decision.LONG, r.decision)
        assertEquals(1, r.htfTrend)
        assertTrue(r.longScore >= BotConfig.Signal.ENTRY_SCORE)
        assertTrue(r.longScore - r.shortScore >= BotConfig.Signal.ENTRY_LEAD)
        assertNull(r.confirms(+1))
        assertTrue(r.reason.contains("BOS"))
    }

    @Test fun `mirrored market gives a SHORT decision`() {
        val m1 = swinging(3654, -1)
        val r = SignalEngine.evaluate(m1, TestBars.m5Of(m1), spread = 0.3)
        assertEquals(r.reason, SignalEngine.Decision.SHORT, r.decision)
        assertEquals(-1, r.htfTrend)
    }

    @Test fun `in an uptrend the engine never decides SHORT without a CHoCH`() {
        for (n in 3600..3900 step 7) {
            val m1 = swinging(n, +1)
            val r = SignalEngine.evaluate(m1, TestBars.m5Of(m1), spread = 0.3)
            if (r.decision == SignalEngine.Decision.SHORT) assertEquals("n=$n ${r.reason}", -1, r.chochDir)
        }
    }

    @Test fun `short history still gives a higher timeframe reading`() {
        // 12 h danych: za mało na EMA50 z H1, ale trend ma być odczytany z krótszej średniej
        val m1 = TestBars.series(720) { i -> 4000.0 + 0.03 * i + 1.0 * Math.sin(2 * Math.PI * i / 25) }
        val r = SignalEngine.evaluate(m1, TestBars.m5Of(m1), spread = 0.3)
        assertEquals(r.reason, 1, r.htfTrend)
    }

    @Test fun `too wide spread blocks every entry`() {
        val m1 = trend(+0.05)
        val r = SignalEngine.evaluate(m1, TestBars.m5Of(m1), spread = 50.0)
        assertNotNull(r.blocked)
        assertEquals(SignalEngine.Decision.NONE, r.decision)
        assertNotNull(r.confirms(+1))
    }

    @Test fun `ATR far above normal blocks entries`() {
        val base = trend(+0.05)
        val last = base.last().close
        // 14 świec o zasięgu wielokrotnie większym niż norma
        val spike = TestBars.series(14, start = base.last().start + M1, wick = 6.0) { i -> last + (if (i % 2 == 0) 8.0 else -8.0) }
        val m1 = base + spike
        val ratio = SignalEngine.atrRatio(m1)!!
        assertTrue("ratio $ratio", ratio > BotConfig.Signal.ATR_RATIO_MAX)
        val r = SignalEngine.evaluate(m1, TestBars.m5Of(m1), spread = 0.3)
        assertTrue(r.blocked!!.contains("zmienność za wysoka"))
        assertEquals(SignalEngine.Decision.NONE, r.decision)
    }

    @Test fun `not enough data - no decision`() {
        val m1 = TestBars.wave(30, drift = 0.05)
        val r = SignalEngine.evaluate(m1, TestBars.m5Of(m1), spread = 0.3)
        assertEquals(SignalEngine.Decision.NONE, r.decision)
        assertNotNull(r.blocked)
    }

    @Test fun `counter-trend entry allowed only after a fresh CHoCH`() {
        val up = SignalEngine.Result(SignalEngine.Decision.NONE, 2.0, 6.0, htfTrend = 1, htfScore = 3.0, chochDir = 0,
            structure = null, atrM1 = 1.0, atrM5 = 2.0, blocked = null, layers = emptyList(), reason = "")
        assertNotNull(up.confirms(-1))
        assertNull(up.copy(chochDir = -1).confirms(-1))
        // za niski wynik albo brak przewagi – odmowa nawet zgodnie z trendem
        assertNotNull(up.copy(longScore = 3.0, shortScore = 0.0).confirms(+1))
        assertNotNull(up.copy(longScore = 5.0, shortScore = 4.5).confirms(+1))
        assertNull(up.copy(longScore = 5.0, shortScore = 1.0).confirms(+1))
    }

    @Test fun `resample builds H1 from M5`() {
        val m5 = TestBars.series(24, periodMs = M5) { i -> 100.0 + i }
        val h1 = Indicators.resample(m5, 3_600_000L)
        assertEquals(2, h1.size)
        assertEquals(T0, h1[0].start)
        assertEquals(111.0, h1[0].close, 1e-9)
        assertEquals(123.0 + 0.1, h1[1].high, 1e-9)
    }

    @Test fun `MACD histogram is positive in a rising market and negative in a falling one`() {
        val up = (0 until 120).map { 100.0 + it * it * 0.01 }
        val down = (0 until 120).map { 100.0 - it * it * 0.01 }
        assertTrue(Indicators.macdHist(up).last() > 0)
        assertTrue(Indicators.macdHist(down).last() < 0)
        assertTrue(Indicators.macdHist(up.take(20)).isEmpty())
    }

    @Test fun `structure - break of swing high is BOS, first break against it is CHoCH`() {
        // góra–dół–góra (BOS↑), potem mocny spadek pod ostatni dołek (CHoCH↓)
        val path = listOf(100.0, 101.0, 102.0, 103.0, 102.0, 101.0, 100.5, 101.5, 102.5, 103.5, 104.5, 104.0, 103.0, 103.5, 104.0, 102.5, 101.0, 100.0)
        val bars = TestBars.series(path.size) { path[it] }
        val upTo = Structure.analyze(bars.take(11))
        assertEquals(1, upTo.lastEvent!!.dir)
        assertTrue(!upTo.lastEvent!!.choch)
        val all = Structure.analyze(bars)
        assertEquals(-1, all.lastEvent!!.dir)
        assertTrue(all.lastEvent!!.choch)
        assertEquals(-1, all.trend)
    }
}
