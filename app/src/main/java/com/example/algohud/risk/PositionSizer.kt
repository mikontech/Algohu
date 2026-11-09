package com.example.algohud.risk

import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.min

/**
 * Lot = (Kapitał × Ryzyko%) / (Dystans do SL w walucie konta na 1 lot).
 * Dystans w walucie konta = |Entry − SL| × pointValue (wartość 1,0 ruchu ceny dla 1 lota, z "Wartości kontraktu" XTB).
 * Wynik zaokrąglany W DÓŁ do kroku lota; dodatkowo limit dźwigni. Poniżej minimalnego lota → odrzucenie
 * (NIE zaokrąglamy w górę – to przekroczyłoby zaplanowane ryzyko).
 */
object PositionSizer {
    data class Input(
        val equity: Double,
        val entry: Double,
        val stop: Double,
        val pointValue: Double,
        val riskPct: Double,
        val maxLeverage: Double,
        val lotStep: Double,
        val minLot: Double = lotStep
    )

    sealed interface Result {
        data class Ok(val lots: Double, val riskAmount: Double, val slPerLot: Double, val leverage: Double, val limitedByLeverage: Boolean) : Result
        data class Rejected(val reason: String) : Result
    }

    fun size(i: Input): Result {
        if (i.equity <= 0) return Result.Rejected("brak danych o kapitale")
        if (i.pointValue <= 0) return Result.Rejected("brak wartości kontraktu")
        val slDistance = abs(i.entry - i.stop)
        if (slDistance <= 0) return Result.Rejected("brak dystansu do SL")

        val slPerLot = slDistance * i.pointValue                 // strata przy SL dla 1 lota (waluta konta)
        val riskBudget = i.equity * i.riskPct                    // np. 1% kapitału
        val byRisk = riskBudget / slPerLot
        val byLeverage = i.equity * i.maxLeverage / (i.entry * i.pointValue)
        val raw = min(byRisk, byLeverage)
        val steps = floor(raw / i.lotStep + 1e-9).toLong()
        val lots = BigDecimal.valueOf(i.lotStep).multiply(BigDecimal.valueOf(steps)).setScale(8, RoundingMode.DOWN).toDouble()

        if (lots < i.minLot - 1e-12) {
            val minRisk = i.minLot * slPerLot
            return Result.Rejected(
                if (byRisk < byLeverage) "min. lot %s = ryzyko %.2f > budżet %.2f".format(i.minLot.toString(), minRisk, riskBudget)
                else "min. lot przekracza limit dźwigni %.0f×".format(i.maxLeverage)
            )
        }
        return Result.Ok(
            lots = lots,
            riskAmount = lots * slPerLot,
            slPerLot = slPerLot,
            leverage = lots * i.entry * i.pointValue / i.equity,
            limitedByLeverage = byLeverage < byRisk
        )
    }
}
