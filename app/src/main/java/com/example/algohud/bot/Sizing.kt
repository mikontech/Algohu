package com.example.algohud.bot

import kotlin.math.floor
import kotlin.math.round

/**
 * Dobór wolumenu i schodkowe blokowanie zysku (czysta logika – testowalna).
 *
 * Wolumen: największy (krok [LOT_STEP], od [MIN_LOT] w górę, bez górnego limitu), przy którym
 *   depozyt + strata na SL + zapas ≤ wolne środki. SL stoi zawsze tak samo daleko od ceny
 *   (limity 15–20 zł liczone dla [REF_LOT]), więc strata w złotówkach rośnie razem z wolumenem.
 *
 * Schodki SL: co [stepPln] zysku SL idzie wyżej – +8 zł → wejście + spread, +16 zł → +8 zł zablokowane, +24 zł → +16 zł…
 */
object Sizing {
    const val MIN_LOT = 0.006
    const val REF_LOT = 0.006
    const val LOT_STEP = 0.001
    const val FUNDS_RESERVE_PLN = 20.0

    /**
     * @param funds wolne środki w XTB (PLN); null = nieznane → [MIN_LOT] (jak dotąd).
     * @param pv wartość ruchu ceny o 1,0 dla 1 lota (PLN).
     * @param stopDist odległość SL od ceny wejścia.
     * @return wolumen albo null, gdy nie mieści się nawet [MIN_LOT].
     */
    fun lot(funds: Double?, price: Double, pv: Double, marginRate: Double, stopDist: Double,
            minLot: Double = MIN_LOT, step: Double = LOT_STEP, reserve: Double = FUNDS_RESERVE_PLN): Double? {
        if (funds == null) return minLot
        val perLot = pv * (price * marginRate + stopDist)
        if (perLot <= 0 || price <= 0) return minLot
        val raw = (funds - reserve) / perLot
        val lots = round(floor(raw / step + 1e-9) * step * 1000) / 1000
        return if (lots + 1e-9 < minLot) null else lots
    }

    /** Depozyt + strata na SL dla [lot] – do komunikatu „za mało środków”. */
    fun needed(lot: Double, price: Double, pv: Double, marginRate: Double, stopDist: Double, reserve: Double = FUNDS_RESERVE_PLN): Double =
        lot * pv * (price * marginRate + stopDist) + reserve

    /**
     * Schodkowy SL: zwraca cenę SL dla najlepszego zysku [bestPln] albo null (zysk < [stepPln]).
     * @param perPrice PLN za ruch ceny o 1,0 przy bieżącym wolumenie.
     * @param spread spread w cenie (próg „bez straty” = wejście + spread).
     */
    fun lockStop(entry: Double, dir: Int, bestPln: Double, perPrice: Double, spread: Double, stepPln: Double): Double? {
        if (perPrice <= 0 || stepPln <= 0) return null
        val steps = floor(bestPln / stepPln + 1e-9).toInt()
        if (steps < 1) return null
        val lockPln = (steps - 1) * stepPln
        return entry + dir * (spread + lockPln / perPrice)
    }

    fun lockedPln(bestPln: Double, stepPln: Double): Double? {
        val steps = floor(bestPln / stepPln + 1e-9).toInt()
        return if (steps < 1) null else (steps - 1) * stepPln
    }
}
