package com.example.algohud.bot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DataQualityTest {
    private val now = 1_000_000_000L
    private fun ok() = DataQuality.GateInput(
        now = now, expectedInstrument = "GOLD", screenInstrument = "GOLD", screenQuoteAt = now - 1_000,
        screenBid = 4045.2, screenAsk = 4045.9, feedAt = now - 2_000, feedPrice = 4036.0, typicalBasis = 9.5,
        atr1 = 1.5, pointValueKnown = true, needsReconcile = false, realTrading = true
    )

    @Test fun freshConsistentDataPasses() = assertNull(DataQuality.entryBlock(ok()))
    @Test fun staleFeedBlocks() = assertNotNull(DataQuality.entryBlock(ok().copy(feedAt = now - 60_000)))
    @Test fun missingFeedBlocks() = assertNotNull(DataQuality.entryBlock(ok().copy(feedPrice = null)))
    @Test fun otherInstrumentOnScreenBlocks() =
        assertTrue(DataQuality.entryBlock(ok().copy(screenInstrument = "SILVER"))!!.contains("SILVER"))
    @Test fun staleScreenBlocksOnlyRealTrading() {
        assertNotNull(DataQuality.entryBlock(ok().copy(screenQuoteAt = now - 30_000)))
        assertNull(DataQuality.entryBlock(ok().copy(screenQuoteAt = now - 30_000, realTrading = false)))
    }
    @Test fun reconcilePendingBlocks() = assertNotNull(DataQuality.entryBlock(ok().copy(needsReconcile = true)))
    @Test fun basisJumpBlocks() = assertNotNull(DataQuality.entryBlock(ok().copy(screenBid = 4075.2, screenAsk = 4075.9)))
    @Test fun invertedQuoteBlocks() = assertNotNull(DataQuality.entryBlock(ok().copy(screenBid = 4046.0, screenAsk = 4045.0)))
    @Test fun missingFieldIsNeverZero() {
        val f = DataQuality.Field("Depozyt", null, "XTB ekran", null, "PLN", 8_000)
        assertEquals(DataQuality.Status.MISSING, f.status(now))
        assertTrue(f.line(now).contains("brak"))
    }
    @Test fun medianIsRobust() = assertEquals(2.0, DataQuality.median(listOf(1.0, 2.0, 100.0))!!, 1e-9)
}
