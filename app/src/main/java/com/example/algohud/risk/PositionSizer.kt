package com.example.algohud.risk

import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.min

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
        if (i.equity <= 0.0) return Result.Rejected("Brak środków na koncie")
        if (i.pointValue <= 0.0) return Result.Rejected("Nieznana wartość kontraktu")
        val slDistance = abs(i.entry - i.stop)
        if (slDistance <= 1e-6) return Result.Rejected("Zbyt mały dystans do Stop-Loss")

        val slPerLot = slDistance * i.pointValue
        val riskBudget = i.equity * i.riskPct
        val byRisk = riskBudget / slPerLot
        val byLeverage = (i.equity * i.maxLeverage) / (i.entry * i.pointValue)

        val raw = min(byRisk, byLeverage)
        val steps = floor((raw / i.lotStep) + 1e-9).toLong()
        val lots = BigDecimal.valueOf(i.lotStep).multiply(BigDecimal.valueOf(steps)).setScale(8, RoundingMode.DOWN).toDouble()

        if (lots < i.minLot - 1e-12) {
            val minRisk = i.minLot * slPerLot
            return Result.Rejected(
                if (byRisk < byLeverage) "Minimalny lot ${i.minLot} generuje ryzyko %.2f PLN > limit %.2f PLN".format(minRisk, riskBudget)
                else "Minimalny lot przekracza dopuszczalną dźwignię %.0f×".format(i.maxLeverage)
            )
        }

        return Result.Ok(
            lots = lots,
            riskAmount = lots * slPerLot,
            slPerLot = slPerLot,
            leverage = (lots * i.entry * i.pointValue) / i.equity,
            limitedByLeverage = byLeverage < byRisk
        )
    }
}
