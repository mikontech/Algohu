package com.example.algohud.bot

import com.example.algohud.core.Position
import com.example.algohud.core.Side
import org.junit.Assert.*
import org.junit.Test

class ProfitReversalGuardTest {
    private val base = 1_000_000L
    private fun position(side: Side = Side.BUY, openedAt: Long = 900_000L) =
        Position("GOLD", side, 0.006, 100.0, if (side == Side.BUY) 98.0 else 102.0,
            best = 100.0, openedAt = openedAt)
    private fun run(prices: List<Double>, side: Side = Side.BUY, spread: Double = 0.1,
                    step: Long = 2_000): List<Boolean> {
        val guard = ProfitReversalGuard()
        return prices.mapIndexed { i, price ->
            guard.shouldClose(position(side), price, spread, 1.0, base + i * step, base + i * step)
        }
    }
    @Test fun closesLongOnSustainedProfitableFall() =
        assertEquals(listOf(false, false, true), run(listOf(101.0, 100.8, 100.5)))
    @Test fun closesShortOnSustainedProfitableRise() =
        assertEquals(listOf(false, false, true), run(listOf(99.0, 99.2, 99.5), Side.SELL))
    @Test fun fallingMarketDoesNotCloseWinningShort() =
        assertFalse(run(listOf(99.5, 99.2, 99.0), Side.SELL).any { it })
    @Test fun favorableLongTrendStaysOpen() =
        assertFalse(run(listOf(100.5, 100.8, 101.0)).any { it })
    @Test fun singleDowntickIsNotReversal() = assertFalse(run(listOf(101.0, 100.5)).any { it })
    @Test fun tinyPullbackIsNoise() = assertFalse(run(listOf(101.0, 100.98, 100.95)).any { it })
    @Test fun insufficientProfitAndLossDoNotTrigger() {
        assertFalse(run(listOf(100.5, 100.3, 100.1)).last())
        assertFalse(run(listOf(101.0, 100.5, 99.9)).last())
    }
    @Test fun spreadBufferPreventsMarginalProfitExit() =
        assertFalse(run(listOf(101.0, 100.8, 100.5), spread = 0.3).last())
    @Test fun fastTicksStillRequireFourSeconds() {
        val results = run(listOf(101.5, 101.4, 101.3, 101.2, 101.0), step = 1_000)
        assertFalse(results.take(4).any { it })
        assertTrue(results.last())
    }
    @Test fun duplicateAndOutOfOrderTimesDoNotConfirm() {
        val g = ProfitReversalGuard()
        assertFalse(g.shouldClose(position(), 101.0, 0.1, 1.0, base, base))
        assertFalse(g.shouldClose(position(), 100.7, 0.1, 1.0, base, base + 2_000))
        assertFalse(g.shouldClose(position(), 100.5, 0.1, 1.0, base - 1, base + 4_000))
    }
    @Test fun staleFutureAndInvalidPricesResetEvidence() {
        for ((price, at) in listOf(100.5 to base - 20_000, 100.5 to base + 60_000,
                Double.NaN to base, Double.POSITIVE_INFINITY to base, -1.0 to base)) {
            val g = ProfitReversalGuard()
            g.shouldClose(position(), 101.0, 0.1, 1.0, base, base)
            assertFalse(g.shouldClose(position(), price, 0.1, 1.0, at, base + 2_000))
            assertFalse(g.shouldClose(position(), 100.5, 0.1, 1.0, base + 4_000, base + 4_000))
        }
    }
    @Test fun newPositionCannotInheritPreviousPeak() {
        val g = ProfitReversalGuard()
        g.shouldClose(position(), 101.0, 0.1, 1.0, base, base)
        g.shouldClose(position(), 100.8, 0.1, 1.0, base + 2_000, base + 2_000)
        assertFalse(g.shouldClose(position(openedAt = base + 3_000), 100.5, 0.1, 1.0, base + 4_000, base + 4_000))
    }
    @Test fun feedGapRequiresNewConfirmation() =
        assertFalse(run(listOf(101.0, 100.8, 100.5), step = 10_000).any { it })
}
