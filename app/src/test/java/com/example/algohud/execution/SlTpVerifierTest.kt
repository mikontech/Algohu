package com.example.algohud.execution

import com.example.algohud.core.Side
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SlTpVerifierTest {
    private fun row(side: Side = Side.BUY, vol: Double = 0.001, entry: String = "@ 4 072.50", sl: Double? = null, tp: Double? = null) =
        PositionRowInfo(side, vol, entry, sl, tp)

    @Test fun okWhenSlAndTpMatch() {
        assertEquals(SlTpCheck.Ok, SlTpVerifier.check(row(sl = 4069.25, tp = 4076.40), 4069.253, 4076.398))
    }

    @Test fun mismatchWhenSlMissing() {
        val r = SlTpVerifier.check(row(sl = null, tp = 4076.40), 4069.25, 4076.40)
        assertTrue(r is SlTpCheck.Mismatch && r.problems.single().contains("brak SL"))
    }

    @Test fun mismatchWhenTpMissingOrWrong() {
        assertTrue(SlTpVerifier.check(row(sl = 4069.25, tp = null), 4069.25, 4076.40) is SlTpCheck.Mismatch)
        assertTrue(SlTpVerifier.check(row(sl = 4069.25, tp = 4080.00), 4069.25, 4076.40) is SlTpCheck.Mismatch)
    }

    @Test fun tpMustBeAbsentWhenNotOrdered() {
        assertEquals(SlTpCheck.Ok, SlTpVerifier.check(row(sl = 4069.25, tp = null), 4069.25, null))
        assertTrue(SlTpVerifier.check(row(sl = 4069.25, tp = 4076.40), 4069.25, null) is SlTpCheck.Mismatch)
    }

    @Test fun newRowsIgnoresPositionsThatExistedBefore() {
        val old = row(entry = "@ 4 060.00", sl = 4055.0)
        val fresh = row(entry = "@ 4 072.50", sl = 4069.25)
        val found = SlTpVerifier.newRows(before = listOf(old), after = listOf(old, fresh), side = Side.BUY, volume = 0.001)
        assertEquals(listOf(fresh), found)
    }

    @Test fun newRowsFiltersSideAndVolume() {
        val after = listOf(row(side = Side.SELL), row(vol = 0.002), row())
        assertEquals(1, SlTpVerifier.newRows(emptyList(), after, Side.BUY, 0.001).size)
    }

    @Test fun duplicateSignatureCountsAsNewOnlyOnce() {
        val same = row(sl = 4069.25)
        assertEquals(1, SlTpVerifier.newRows(listOf(same), listOf(same, same), Side.BUY, 0.001).size)
    }
}
