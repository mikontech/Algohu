package com.example.algohud.bot

import com.example.algohud.bot.TestBars.M1
import com.example.algohud.bot.TestBars.T0
import com.example.algohud.core.Bar
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TrailingManagerTest {
    private val flat = TestBars.series(100) { i -> 4000.0 + 0.2 * Math.sin(i / 3.0) }
    private val openedAt = flat.last().start + M1

    /** Long @ 4000, SL 3993 (1R = 7), TP 4017,5. */
    private fun input(price: Double, best: Double = price, stop: Double = 3993.0, tp: Double? = 4017.5, m1: List<Bar> = flat, current: Bar? = null,
                      tick: Double = 1.0, liq: List<Double> = emptyList()) = TrailingManager.Input(
        dir = 1, entry = 4000.0, initialStop = 3993.0, stop = stop, takeProfit = tp, price = price, best = best, spread = 0.3,
        atrM1 = 1.0, atrM5 = 2.5, m1 = m1, current = current, openedAt = openedAt, tickRatio = tick, liquidity = liq)

    @Test fun `below 1R nothing moves`() {
        val p = TrailingManager.plan(input(price = 4006.0))
        assertTrue(p.isEmpty)
    }

    @Test fun `at 1R stop goes to break-even plus spread`() {
        val p = TrailingManager.plan(input(price = 4007.5))
        assertEquals(4000.3, p.stop!!, 1e-9)
        assertTrue(p.stopWhy.contains("break-even"))
        assertNull(p.takeProfit)
    }

    @Test fun `from 1,5R stop trails behind the last swing low after entry`() {
        // po wejściu: wzrost, korekta z dołkiem 4008, dalszy wzrost do 4014
        val path = listOf(4002.0, 4005.0, 4009.0, 4010.0, 4009.0, 4008.4, 4010.0, 4012.0, 4013.0, 4014.0)
        val after = TestBars.series(path.size, start = openedAt, wick = 0.1) { path[it] }
        val p = TrailingManager.plan(input(price = 4014.0, m1 = flat + after))
        val swingLow = after.minOf { if (it.start >= openedAt + 4 * M1) it.low else Double.MAX_VALUE }
        assertEquals(swingLow - BotConfig.Trailing.SWING_BUFFER_ATR * 1.0, p.stop!!, 1e-9)
        assertTrue(p.stopWhy.contains("swing"))
    }

    @Test fun `without a swing the stop trails by k x ATR behind the best price`() {
        val after = TestBars.series(8, start = openedAt, wick = 0.05) { i -> 4001.0 + 1.6 * i }
        val p = TrailingManager.plan(input(price = 4012.2, m1 = flat + after))
        assertEquals(4012.2 - BotConfig.Trailing.ATR_K * 1.0, p.stop!!, 1e-9)
    }

    @Test fun `stop never moves back toward a loss`() {
        // SL już na 4009; cena cofnęła się z 4014 do 4010 – żadnej propozycji gorszego SL
        val p = TrailingManager.plan(input(price = 4010.0, best = 4014.0, stop = 4009.6))
        assertTrue("SL ${p.stop}", p.stop == null || p.stop!! > 4009.6)
        // wiele kroków: SL z planu nigdy nie maleje
        var stop = 3993.0
        var best = 4000.0
        for (px in listOf(4003.0, 4008.0, 4006.0, 4011.0, 4007.0, 4015.0, 4009.0, 4004.0)) {
            best = maxOf(best, px)
            TrailingManager.plan(input(price = px, best = best, stop = stop)).stop?.let { assertTrue(it > stop); stop = it }
        }
        assertTrue(stop >= 4000.3)
    }

    @Test fun `stop keeps a minimum gap from the current price`() {
        val p = TrailingManager.plan(input(price = 4000.5, best = 4007.5))
        // BE (4000,3) byłby 0,2 od ceny – minimalny odstęp to max(2×spread, 0,5×ATR) = 0,6
        assertEquals(4000.5 - 0.6, p.stop!!, 1e-9)
    }

    @Test fun `strong impulse extends take profit, no impulse leaves it`() {
        val calm = TrailingManager.plan(input(price = 4010.0))
        assertNull(calm.takeProfit)
        assertFalse(calm.impulse)
        // świeca 2×ATR w górę, pełny korpus, wybija szczyt ostatnich świec
        val impulse = Bar(openedAt, 4008.0, 4010.1, 4007.95, 4010.0)
        val p = TrailingManager.plan(input(price = 4010.0, current = impulse))
        assertTrue(p.impulse)
        assertEquals(4017.5 + BotConfig.Trailing.TP_EXTEND_ATR * 2.5, p.takeProfit!!, 1e-9)
    }

    @Test fun `impulse moves take profit to the next liquidity level when it is close enough`() {
        val impulse = Bar(openedAt, 4008.0, 4010.1, 4007.95, 4010.0)
        val p = TrailingManager.plan(input(price = 4010.0, current = impulse, liq = listOf(4005.0, 4022.0, 4060.0)))
        assertEquals(4022.0 - 0.25, p.takeProfit!!, 1e-9)
        assertTrue(p.tpWhy.contains("płynności"))
    }

    @Test fun `impulse before 1R does not move take profit, a candle against the position is not an impulse`() {
        val impulse = Bar(openedAt, 4003.0, 4005.1, 4002.95, 4005.0)
        assertNull(TrailingManager.plan(input(price = 4005.0, current = impulse)).takeProfit)
        val against = Bar(openedAt, 4012.0, 4012.05, 4009.9, 4010.0)
        assertFalse(TrailingManager.plan(input(price = 4010.0, best = 4012.0, current = against)).impulse)
    }

    @Test fun `short position - mirrored`() {
        val i = TrailingManager.Input(dir = -1, entry = 4000.0, initialStop = 4007.0, stop = 4007.0, takeProfit = 3982.5, price = 3992.0,
            best = 3992.0, spread = 0.3, atrM1 = 1.0, atrM5 = 2.5, m1 = flat, current = Bar(openedAt, 3994.0, 3994.05, 3991.9, 3992.0), openedAt = openedAt)
        val p = TrailingManager.plan(i)
        assertEquals(3999.7, p.stop!!, 1e-9)
        assertEquals(3982.5 - BotConfig.Trailing.TP_EXTEND_ATR * 2.5, p.takeProfit!!, 1e-9)
    }

    // ── synchronizacja z XTB ──

    @Test fun `modification is rate limited and only sent for a real step`() {
        val s = ProtectionSync(dir = 1, brokerStop = 3993.0, brokerTp = 4017.5, minIntervalMs = 45_000, retryMs = 15_000, maxFailures = 2)
        assertNull(s.next(T0, 3993.4, 4017.5, minStep = 0.8)) // za mały krok
        val r1 = s.next(T0, 4000.3, 4017.5, minStep = 0.8)!!
        assertEquals(4000.3, r1.setStop!!, 1e-9)
        assertNull(r1.setTp) // TP bez zmian – nie dotykamy
        assertEquals(4017.5, r1.expectTp!!, 1e-9)
        s.busy = true
        assertNull(s.next(T0 + 1, 4005.0, 4017.5, 0.8)) // trwa edycja
        s.onResult(T0 + 5_000, r1, ok = true)
        assertEquals(4000.3, s.brokerStop, 1e-9)
        assertNull(s.next(T0 + 30_000, 4005.0, 4021.0, 0.8)) // < 45 s od poprzedniej
        val r2 = s.next(T0 + 51_000, 4005.0, 4021.0, 0.8)!!
        assertEquals(4005.0, r2.setStop!!, 1e-9)
        assertEquals(4021.0, r2.setTp!!, 1e-9)
        s.onResult(T0 + 55_000, r2, ok = true)
        assertEquals(listOf("SL", "SL", "TP"), s.history.map { it.what })
        assertEquals(4017.5, s.history.last().from!!, 1e-9)
    }

    @Test fun `stop is never sent toward a loss and take profit never closer`() {
        val s = ProtectionSync(dir = 1, brokerStop = 4005.0, brokerTp = 4021.0)
        assertNull(s.next(T0, 4001.0, 4018.0, minStep = 0.5))
        val short = ProtectionSync(dir = -1, brokerStop = 3995.0, brokerTp = 3979.0)
        assertNull(short.next(T0, 3999.0, 3982.0, minStep = 0.5))
        assertNotNull(short.next(T0, 3993.0, 3979.0, minStep = 0.5))
    }

    @Test fun `failed modification is retried and raises an alert after repeated failures`() {
        val s = ProtectionSync(dir = 1, brokerStop = 3993.0, brokerTp = 4017.5, minIntervalMs = 45_000, retryMs = 15_000, maxFailures = 2)
        val r = s.next(T0, 4000.3, null, 0.8)!!
        s.onResult(T0, r, ok = false, error = "XTB pokazuje SL 3993.00 zamiast 4000.30")
        assertEquals(3993.0, s.brokerStop, 1e-9) // nic nie potwierdzone
        assertFalse(s.alert)
        assertNull(s.next(T0 + 5_000, 4000.3, null, 0.8)) // ponowienie dopiero po 15 s
        val again = s.next(T0 + 16_000, 4000.3, null, 0.8)!!
        s.onResult(T0 + 16_000, again, ok = false, error = "ekran pozycji się nie otworzył")
        assertTrue(s.alert)
        assertEquals("ekran pozycji się nie otworzył", s.lastError)
        assertNull(s.next(T0 + 32_000, 4000.3, null, 0.8)) // po drugim błędzie przerwa rośnie do 30 s
        val third = s.next(T0 + 47_000, 4000.3, null, 0.8)!!
        s.onResult(T0 + 48_000, third, ok = true)
        assertFalse(s.alert)
        assertEquals(1, s.history.size)
    }
}
