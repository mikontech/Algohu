package com.example.algohud.bot

import org.junit.Assert.*
import org.junit.Test

class TradeSimulatorTest {
    private val tf = 60_000L
    private fun bar(i: Int, o: Double = 100.0, h: Double = 101.0,
                    l: Double = 99.0, c: Double = 100.0) =
        CandleLabeler.Candle((i + 1) * tf, o, h, l, c, 10)
    private fun signal(i: Int = 0, buy: Boolean = true) = TradeSimulator.Signal(
        (i + 1) * tf, 0L, 1.0,
        if (buy) 0.8 else 0.1, if (buy) 0.1 else 0.8, 0.1)
    private fun config() = TradeSimulator.Config(
        timeframeMs = tf, initialEquity = 1000.0, spread = 0.0,
        slippage = 0.0, pointValuePerLot = 1.0, commissionPerLotPerSide = 0.0,
        minLot = 1.0, lotStep = 1.0, maxLot = 1000.0, marginRate = 0.01,
        riskFraction = 0.01, maxExposureMultiple = 100.0, maxMarginFraction = 1.0,
        stopAtr = 2.0, rewardRatio = 2.0, maxHoldingBars = 2,
        minProbability = 0.6, minEdge = 0.1, confirmedMidPrices = true)

    @Test fun longAndShortTargetsProduceExpectedNetPnl() {
        val buy = TradeSimulator.run(listOf(bar(0, h = 105.0)),
            listOf(signal()), config()).trades.single()
        val sell = TradeSimulator.run(listOf(bar(0, l = 95.0)),
            listOf(signal(buy = false)), config()).trades.single()
        assertEquals(TradeSimulator.ExitReason.TARGET, buy.reason)
        assertEquals(TradeSimulator.ExitReason.TARGET, sell.reason)
        assertEquals(20.0, buy.netPnl, 1e-9)
        assertEquals(20.0, sell.netPnl, 1e-9)
    }

    @Test fun bothBarriersUseConservativeStop() {
        val t = TradeSimulator.run(listOf(bar(0, h = 105.0, l = 97.0)),
            listOf(signal()), config()).trades.single()
        assertEquals(TradeSimulator.ExitReason.BOTH_TOUCHED_STOP_ASSUMED, t.reason)
        assertEquals(-10.0, t.netPnl, 1e-9)
    }

    @Test fun stopGapExecutesAtWorseOpeningPrice() {
        val t = TradeSimulator.run(listOf(bar(0),
            bar(1, o = 90.0, h = 91.0, l = 89.0, c = 90.0)),
            listOf(signal()), config()).trades.single()
        assertEquals(90.0, t.exitPrice, 0.0)
        assertEquals(-50.0, t.netPnl, 1e-9)
    }

    @Test fun spreadSlippageAndBothCommissionsAreCharged() {
        val cfg = config().copy(spread = 2.0, slippage = 0.5,
            commissionPerLotPerSide = 0.2, stopAtr = 10.0, riskFraction = 0.02)
        val t = TradeSimulator.run(listOf(bar(0)), listOf(signal()), cfg).trades.single()
        assertEquals(101.5, t.entryPrice, 1e-9)
        assertEquals(98.5, t.exitPrice, 1e-9)
        assertEquals(0.4 * t.lots, t.commission, 1e-9)
        assertEquals(-3.4 * t.lots, t.netPnl, 1e-9)
        assertEquals(TradeSimulator.ExitReason.END_OF_DATA, t.reason)
    }

    @Test fun volumeBelowMinimumIsSkipped() {
        val r = TradeSimulator.run(listOf(bar(0)), listOf(signal()),
            config().copy(minLot = 10.0))
        assertTrue(r.trades.isEmpty())
        assertEquals(1, r.ignoredSignals)
        assertEquals(1000.0, r.endingEquity, 0.0)
    }

    @Test fun overlappingSignalsAreNotExecutedLater() {
        val r = TradeSimulator.run(listOf(bar(0), bar(1), bar(2)),
            listOf(signal(0), signal(1)), config())
        assertEquals(1, r.trades.size)
        assertEquals(1, r.ignoredSignals)
        assertEquals(TradeSimulator.ExitReason.TIMEOUT, r.trades.single().reason)
    }

    @Test(expected = IllegalArgumentException::class)
    fun gapsInRecordedHistoryAreRejected() {
        TradeSimulator.run(listOf(bar(0), bar(2)), listOf(signal()), config())
    }

    @Test(expected = IllegalStateException::class)
    fun cancellationIsPropagated() {
        TradeSimulator.run(listOf(bar(0)), listOf(signal()), config()) {
            throw IllegalStateException("cancelled")
        }
    }
}
