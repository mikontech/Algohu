package com.example.algohud.bot

import com.example.algohud.bot.TestBars.M1
import com.example.algohud.core.Bar
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReversalDetectorTest {
    private val up = TestBars.wave(600, drift = +0.08, amp = 1.0, len = 30.0)
    private val entryIdx = 520
    private val openedAt = up[entryIdx].start
    private val entry = up[entryIdx].close

    private fun signal(long: Double, short: Double) = SignalEngine.Result(
        SignalEngine.Decision.NONE, long, short, htfTrend = 0, htfScore = 0.0, chochDir = 0, structure = null,
        atrM1 = 1.0, atrM5 = 2.0, blocked = null, layers = emptyList(), reason = "")

    /** Przechodzi świeca po świecy jak bot; zwraca indeks świecy, na której padło „zamknij” (albo −1). */
    private fun run(
        bars: List<Bar>, dir: Int = 1, from: Int = entryIdx + 1, noiseAtr: Double = 2.0,
        signal: (Int) -> SignalEngine.Result? = { null }, det: ReversalDetector = ReversalDetector()
    ): Pair<Int, ReversalDetector.Verdict?> {
        var best = entry
        var last: ReversalDetector.Verdict? = null
        for (i in from until bars.size) {
            val seen = bars.subList(0, i + 1)
            best = if (dir > 0) maxOf(best, bars[i].high) else minOf(best, bars[i].low)
            last = det.onBarClosed(dir, openedAt, best, bars[i].close, seen, M1, signal(i), noiseAtr)
            if (last.close) return i to last
        }
        return -1 to last
    }

    /** Po trendzie wzrostowym trwały spadek o [drop] na świecę przez [n] świec. */
    private fun thenFall(n: Int, drop: Double): List<Bar> {
        val last = up.last().close
        return up + TestBars.series(n, start = up.last().start + M1, wick = 0.05) { i -> last - drop * (i + 1) }
    }

    @Test fun `long in a healthy uptrend is never closed`() {
        val (idx, v) = run(up)
        assertEquals(-1, idx)
        assertFalse(v!!.close)
    }

    @Test fun `single pullback candle does NOT close the position`() {
        val last = up.last().close
        // jedna duża czerwona świeca, potem powrót do trendu
        val bars = up + TestBars.series(12, start = up.last().start + M1, wick = 0.05) { i -> if (i == 0) last - 3.0 else last - 3.0 + 0.5 * i }
        val (idx, _) = run(bars, signal = { i -> if (i == up.size) signal(1.0, 7.0) else signal(5.0, 1.0) })
        assertEquals("zamknięto na świecy $idx", -1, idx)
    }

    @Test fun `conditions met for one candle only do NOT close`() {
        val det = ReversalDetector()
        val bars = thenFall(1, 4.0)
        val (idx, v) = run(bars, signal = { signal(1.0, 7.0) }, det = det)
        assertEquals(-1, idx)
        assertTrue("streak ${v!!.streak}", v.streak <= 1)
    }

    @Test fun `noise smaller than half ATR does NOT close even with all conditions`() {
        // Spadek 0,05 na świecę: struktura i EMA się odwracają, ale przy dużym ATR to wciąż szum.
        val bars = thenFall(10, 0.6)
        val (idx, v) = run(bars, noiseAtr = 1000.0, signal = { i -> if (i >= up.size) signal(1.0, 7.0) else null })
        assertEquals(-1, idx)
        assertTrue(v!!.note, v.streak >= BotConfig.Reversal.CONFIRM_BARS)
        assertTrue(v.note.contains("szum"))
    }

    @Test fun `too early after entry - no evaluation`() {
        val bars = thenFall(10, 0.6)
        val det = ReversalDetector()
        val v = det.onBarClosed(1, bars.last().start, bars.last().close, bars.last().close, bars, M1, signal(1.0, 7.0), 2.0)
        assertFalse(v.close)
        assertEquals(0, v.streak)
    }

    @Test fun `confirmed reversal closes a long - at least two conditions held for two candles`() {
        val bars = thenFall(25, 0.6)
        val (idx, v) = run(bars, signal = { i -> if (i >= up.size + 3) signal(1.0, 6.5) else signal(4.0, 2.0) })
        assertTrue("brak zamknięcia: ${v?.note}", idx > 0)
        assertTrue("za wcześnie: świeca ${idx - up.size}", idx >= up.size + 1) // nie na pierwszej świecy spadku
        assertTrue(v!!.met.size >= BotConfig.Reversal.MIN_CONDITIONS)
        assertTrue(v.streak >= BotConfig.Reversal.CONFIRM_BARS)
        assertTrue(v.reason.startsWith("ODWRÓCENIE TENDENCJI"))
    }

    @Test fun `reversal is detected from price alone - without the score condition`() {
        val bars = thenFall(30, 0.6)
        val (idx, v) = run(bars)
        assertTrue("brak zamknięcia: ${v?.note}", idx > up.size)
        assertEquals(2, v!!.met.size) // struktura + EMA/momentum
    }

    @Test fun `score flip alone - one condition of three - does NOT close`() {
        val (idx, _) = run(up, signal = { signal(0.0, 9.0) })
        assertEquals(-1, idx)
    }

    @Test fun `short is closed by a confirmed upward reversal and kept in a downtrend`() {
        val down = TestBars.wave(600, drift = -0.08, amp = 1.0, len = 30.0)
        val o = down[entryIdx].start
        val e = down[entryIdx].close
        fun runShort(bars: List<Bar>): Int {
            val det = ReversalDetector()
            var best = e
            for (i in entryIdx + 1 until bars.size) {
                best = minOf(best, bars[i].low)
                if (det.onBarClosed(-1, o, best, bars[i].close, bars.subList(0, i + 1), M1, null, 2.0).close) return i
            }
            return -1
        }
        assertEquals(-1, runShort(down))
        val last = down.last().close
        val rally = down + TestBars.series(30, start = down.last().start + M1, wick = 0.05) { i -> last + 0.6 * (i + 1) }
        assertTrue(runShort(rally) > down.size)
    }

    @Test fun `calling twice for the same candle does not count it twice`() {
        val bars = thenFall(6, 0.8)
        val det = ReversalDetector()
        val seen = bars.subList(0, up.size + 3)
        val a = det.onBarClosed(1, openedAt, up.last().high, seen.last().close, seen, M1, signal(1.0, 7.0), 1000.0)
        val b = det.onBarClosed(1, openedAt, up.last().high, seen.last().close, seen, M1, signal(1.0, 7.0), 1000.0)
        assertEquals(a.streak, b.streak)
    }
}
