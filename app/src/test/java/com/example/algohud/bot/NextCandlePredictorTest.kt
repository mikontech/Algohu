package com.example.algohud.bot

import com.example.algohud.core.Bar
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class NextCandlePredictorTest {

    /** Rynek z „pamięcią”: kolejna świeca w 75% przypadków idzie w stronę poprzedniej. */
    private fun momentumBars(n: Int, seed: Int = 7): List<Bar> {
        val rnd = Random(seed)
        var px = 2000.0
        var dir = 1
        return List(n) { i ->
            if (rnd.nextDouble() > 0.75) dir = -dir
            val body = (0.2 + rnd.nextDouble()) * dir
            val open = px
            val close = open + body
            px = close
            Bar(i * 60_000L, open, maxOf(open, close) + rnd.nextDouble() * 0.2, minOf(open, close) - rnd.nextDouble() * 0.2, close)
        }
    }

    @Test fun learnsMomentumAndGradesLive() {
        val bars = momentumBars(3000)
        val p = NextCandlePredictor()
        p.warmUp(bars.take(2000))
        assertTrue(p.samples > 1900)
        var hits = 0; var total = 0
        for (i in 2000 until 2999) {
            val pr = p.predict(bars.subList(0, i), bars[i], 1.0, 60_000L, bars[i].start)!!
            assertTrue(pr.probUp in 0.0..1.0)
            assertEquals(bars[i + 1].start, pr.forBarStart)
            if (pr.up == (bars[i + 1].close > bars[i + 1].open)) hits++
            total++
            p.onBarClosed(bars.subList(0, i + 2))
        }
        assertTrue("trafność ${hits * 100 / total}%", hits.toDouble() / total > 0.65)
        val (h, g) = p.hitStats()
        assertEquals(NextCandlePredictor.WINDOW, g)
        assertTrue(h in 0..g)
        assertTrue(p.last!!.expBodyAtr >= 0.0)
    }

    @Test fun exportImportRoundTrip() {
        val bars = momentumBars(800, seed = 3)
        val a = NextCandlePredictor().apply { warmUp(bars.take(700)) }
        val b = NextCandlePredictor()
        assertTrue(b.import(a.export()))
        assertEquals(a.samples, b.samples)
        val pa = a.predict(bars.subList(0, 750), bars[750], 1.3, 60_000L, 0L)!!
        val pb = b.predict(bars.subList(0, 750), bars[750], 1.3, 60_000L, 0L)!!
        assertEquals(pa.probUp, pb.probUp, 1e-12)
        assertEquals(pa.expBodyAtr, pb.expBodyAtr, 1e-12)
    }

    @Test fun fewSamplesStayNearFiftyPercent() {
        val bars = momentumBars(120, seed = 11)
        val p = NextCandlePredictor().apply { warmUp(bars.take(40)) }
        val pr = p.predict(bars.subList(0, 100), bars[100], 1.0, 60_000L, 0L)!!
        assertTrue(kotlin.math.abs(pr.probUp - 0.5) < 0.1)
    }

    @Test fun badImportIsRejected() {
        assertTrue(!NextCandlePredictor().import("garbage"))
    }
}
