package com.example.algohud.bot

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class CandleLabelerTest {
    private val tf = 60_000L
    private fun bar(index: Int, h: Double = 101.0, l: Double = 99.0,
                    o: Double = 100.0, c: Double = 100.0) =
        CandleLabeler.Candle((index + 1) * tf, o, h, l, c, 10)

    private fun config(horizon: Int = 1) = CandleLabeler.Config(
        timeframeMs = tf, atrPeriod = 1, horizonBars = horizon,
        barrierAtr = 1.0, roundTripCost = 0.0
    )

    @Test fun bothBarriersInsideOneBarAreAmbiguous() {
        val data = listOf(bar(0), bar(1), bar(2, h = 103.0, l = 97.0))
        val sample = CandleLabeler.label(data, config()).samples.single()
        assertEquals(CandleLabeler.Outcome.AMBIGUOUS, sample.outcome)
        assertEquals(2.0, sample.atr, 0.0)
        assertEquals(3 * tf, sample.signalAtMs)
        assertEquals(4 * tf, sample.labelEndMs)
    }

    @Test fun openingGapIsResolvedBeforeIntrabarRange() {
        val data = listOf(bar(0), bar(1),
            bar(2, h = 104.0, l = 97.0, o = 103.0))
        assertEquals(CandleLabeler.Outcome.UP,
            CandleLabeler.label(data, config()).samples.single().outcome)
    }

    @Test fun incompleteHorizonsAreExcluded() {
        val data = listOf(bar(0), bar(1), bar(2))
        assertTrue(CandleLabeler.label(data, config(2)).samples.isEmpty())
    }

    @Test fun gapInsideHorizonExcludesSample() {
        val data = listOf(bar(0), bar(1), bar(3))
        assertTrue(CandleLabeler.label(data, config()).samples.isEmpty())
    }

    @Test fun atrWarmupRestartsAfterGap() {
        val data = listOf(bar(0), bar(1), bar(3), bar(4), bar(5))
        val samples = CandleLabeler.label(data, config()).samples
        assertEquals(1, samples.size)
        assertEquals(6 * tf, samples.single().signalAtMs)
    }

    @Test fun futureChangesDoNotChangeSignalFeatures() {
        val a = CandleLabeler.label(
            listOf(bar(0), bar(1), bar(2)), config()).samples.single()
        val b = CandleLabeler.label(
            listOf(bar(0), bar(1), bar(2, h = 110.0)), config()).samples.single()
        assertEquals(a.atr, b.atr, 0.0)
        assertEquals(a.bodyAtr, b.bodyAtr, 0.0)
        assertEquals(a.rangeAtr, b.rangeAtr, 0.0)
        assertEquals(a.return1, b.return1, 0.0)
        assertEquals(CandleLabeler.Outcome.NEUTRAL, a.outcome)
        assertEquals(CandleLabeler.Outcome.UP, b.outcome)
    }

    @Test fun labelCostWidensBarrier() {
        val data = listOf(bar(0), bar(1), bar(2, h = 102.5))
        val cfg = config().copy(roundTripCost = 1.0)
        assertEquals(CandleLabeler.Outcome.NEUTRAL,
            CandleLabeler.label(data, cfg).samples.single().outcome)
    }

    @Test fun loaderRejectsInvalidRowsAndCountsDuplicates() {
        val file = File.createTempFile("candles", ".csv")
        try {
            file.writeText("60000,100,101,99,100,10\n" +
                "60000,100,102,98,100,10\n" +
                "120000,100,99,101,100,10\n" +
                "180000,NaN,101,99,100,10\n" +
                "broken\n")
            val result = CandleLabeler.load(file)
            assertEquals(1, result.candles.size)
            assertEquals(1, result.duplicateRows)
            assertEquals(3, result.invalidRows)
            assertEquals(101.0, result.candles.single().high, 0.0)
        } finally {
            file.delete()
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun unsortedInputIsRejected() {
        CandleLabeler.label(listOf(bar(1), bar(0)), config())
    }

    @Test(expected = IllegalStateException::class)
    fun cancellationHookStopsProcessing() {
        CandleLabeler.label(listOf(bar(0), bar(1), bar(2)), config()) {
            throw IllegalStateException("cancelled")
        }
    }
}
