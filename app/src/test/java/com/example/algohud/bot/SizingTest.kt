package com.example.algohud.bot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SizingTest {
    // GOLD: 1 lot = 100 uncji, USD/PLN ≈ 4,0 → ruch o 1 USD = 400 zł na lot; depozyt 5%.
    private val pv = 400.0
    private val price = 3880.0
    private val rate = 0.05
    private val dist = 7.0 // SL ok. 7 USD od ceny

    @Test fun picksLargestLotThatFits() {
        // 0.008: depozyt 620,8 + SL 22,4 + zapas 20 = 663,2 ≤ 690 → 0.008; 0.009 = 744 > 690
        assertEquals(0.008, Sizing.lot(690.0, price, pv, rate, dist)!!, 1e-9)
    }

    @Test fun stepsDownWhenFundsDrop() {
        assertEquals(0.007, Sizing.lot(600.0, price, pv, rate, dist)!!, 1e-9)
        assertEquals(0.006, Sizing.lot(520.0, price, pv, rate, dist)!!, 1e-9)
        assertNull(Sizing.lot(450.0, price, pv, rate, dist))
    }

    @Test fun noUpperLimit() {
        assertEquals(0.012, Sizing.lot(1000.0, price, pv, rate, dist)!!, 1e-9)
        assertEquals(0.025, Sizing.lot(2050.0, price, pv, rate, dist)!!, 1e-9)
    }

    @Test fun unknownFundsGivesMinLot() {
        assertEquals(Sizing.MIN_LOT, Sizing.lot(null, price, pv, rate, dist)!!, 1e-9)
    }

    @Test fun lockStopLadderEvery8Pln() {
        val perPrice = 0.008 * pv // 3,2 zł za 1 USD
        val spread = 0.7
        assertNull(Sizing.lockStop(3880.0, -1, 7.9, perPrice, spread, 8.0))
        // +8 zł → SHORT: SL = wejście − spread
        assertEquals(3880.0 - 0.7, Sizing.lockStop(3880.0, -1, 8.0, perPrice, spread, 8.0)!!, 1e-9)
        // +16 zł → zablokowane 8 zł = 2,5 USD
        assertEquals(3880.0 - 0.7 - 2.5, Sizing.lockStop(3880.0, -1, 17.0, perPrice, spread, 8.0)!!, 1e-9)
        // LONG +24 zł → zablokowane 16 zł = 5 USD
        assertEquals(3880.0 + 0.7 + 5.0, Sizing.lockStop(3880.0, 1, 24.5, perPrice, spread, 8.0)!!, 1e-9)
        assertEquals(16.0, Sizing.lockedPln(24.5, 8.0)!!, 1e-9)
    }
}
