package com.example.algohud.bot
import org.junit.Assert.*
import org.junit.Test
class ProfitTrailTest {
    @Test fun startsAtSixAndCoversSpreadPlusProfit() {
        assertNull(ProfitTrail.target(100.0, 1, 5.99, 2.0, 0.7, 104.0, 95.0))
        assertEquals(101.2, ProfitTrail.target(100.0, 1, 6.0, 2.0, 0.7, 104.0, 95.0)!!, 0.011)
        assertEquals(98.8, ProfitTrail.target(100.0, -1, 6.0, 2.0, 0.7, 96.0, 105.0)!!, 0.011)
    }
    @Test fun followsAndNeverLoosensOrCrossesMarket() {
        assertEquals(102.7, ProfitTrail.target(100.0, 1, 9.0, 2.0, 0.7, 105.0, 101.2)!!, 0.011)
        assertNull(ProfitTrail.target(100.0, 1, 6.0, 2.0, 0.7, 104.0, 102.0))
        assertNull(ProfitTrail.target(100.0, -1, 6.0, 2.0, 0.7, 99.0, 105.0))
        assertNull(ProfitTrail.target(100.0, 1, Double.NaN, 2.0, 0.7, 104.0, 95.0))
    }
}
