package com.example.algohud.council

import com.example.algohud.core.OrderRequest
import com.example.algohud.core.Side
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RiskGateTest {
    private val t0 = 1_000_000L
    // SHORT GOLD: E 3900, SL 3905 (5,0), TP 3887,5 (2,5R), lot 0.008
    private val cand = OrderRequest("GOLD", Side.SELL, 0.008, 3900.0, 3905.0, "SMC test", pointValue = 400.0, takeProfit = 3887.5)
    private val obsOk = ObserverReport("GOLD", 3899.8, 3900.2, true, emptyList(), "", emptyList(), "")
    private val approve = SkepticView(SkepticVerdict.APPROVE, 1.0, emptyList(), "")
    private val cmdOk = CommanderOrder(true, Side.SELL, 0.008, 3905.0, 3887.5, "ok", "")

    private fun run(
        obs: ObserverReport? = obsOk, sk: SkepticView = approve, cmd: CommanderOrder = cmdOk,
        px: Double = 3900.3, now: Long = t0 + 15_000
    ) = RiskGate.check(RiskGate.Inputs(cand, obs, sk, cmd, px, 0.0, "GOLD", t0, now))

    @Test fun approvesCleanCandidate() {
        val r = run()
        assertTrue(r.isSuccess)
        assertEquals(0.008, r.getOrThrow().quantity, 1e-9)
    }

    @Test fun vetoWhenCommanderFlipsSide() = assertTrue(run(cmd = cmdOk.copy(side = Side.BUY)).isFailure)
    @Test fun vetoWhenCommanderMovesSl() = assertTrue(run(cmd = cmdOk.copy(sl = 3910.0)).isFailure)
    @Test fun vetoWhenCommanderIncreasesVolume() = assertTrue(run(cmd = cmdOk.copy(volume = 0.02)).isFailure)
    @Test fun vetoOnSkepticVeto() = assertTrue(run(sk = approve.copy(verdict = SkepticVerdict.VETO)).isFailure)
    @Test fun vetoWhenScreenShowsOtherInstrument() = assertTrue(run(obs = obsOk.copy(instrument = "EURUSD")).isFailure)
    @Test fun goldAliasesMatch() = assertTrue(run(obs = obsOk.copy(instrument = "XAU/USD")).isSuccess)
    @Test fun vetoWhenScreenPriceDiffers() = assertTrue(run(obs = obsOk.copy(bid = 3920.0, ask = 3920.4)).isFailure)
    @Test fun vetoWhenPriceRanAway() = assertTrue(run(px = 3898.0).isFailure)  // 2,0 > 0,25 × 5,0
    @Test fun vetoWhenDebateTooLong() = assertTrue(run(now = t0 + 60_000).isFailure)

    @Test fun vetoWhenUntrackedXtbPositionOnInstrument() {
        val r = RiskGate.check(RiskGate.Inputs(cand, obsOk, approve, cmdOk, 3900.3, 0.0, "GOLD", t0, t0 + 1000, untrackedXtbRows = 1))
        assertTrue(r.isFailure)
    }

    @Test fun adjustReducesButNotBelowMinLot() {
        val half = run(sk = approve.copy(verdict = SkepticVerdict.ADJUST, volumeFactor = 0.8))
        assertEquals(0.006, half.getOrThrow().quantity, 1e-9)   // 0.0064 → 0.006
        val tooSmall = run(sk = approve.copy(verdict = SkepticVerdict.ADJUST, volumeFactor = 0.5))
        assertTrue(tooSmall.isFailure)                           // 0.004 < 0.006
    }

    @Test fun vetoWhenRrBelowMin() {
        val bad = cand.copy(takeProfit = 3892.0) // 1,6R
        val r = RiskGate.check(RiskGate.Inputs(bad, obsOk, approve, cmdOk.copy(tp = 3892.0), 3900.3, 0.0, "GOLD", t0, t0 + 1000))
        assertTrue(r.isFailure)
    }
}
