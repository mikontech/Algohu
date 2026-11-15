package com.example.algohud.bot

import kotlin.math.floor
import kotlin.math.ceil

/** Ceny brokera, PLN konta; SL zawsze ponad kosztem spreadu + minimum 1 PLN. */
object ProfitTrail {
    fun target(entry: Double, dir: Int, net: Double, perPrice: Double, spread: Double, close: Double, oldSl: Double?): Double? {
        if (listOf(entry, net, perPrice, spread, close).any { !it.isFinite() } ||
            dir !in listOf(-1, 1) || perPrice <= 0 || spread < 0 || net < 6) return null
        val locked = 1 + floor((net - 6) / 3) * 3
        val raw = entry + dir * (spread + locked / perPrice)
        val target = if (dir > 0) ceil(raw * 100) / 100 else floor(raw * 100) / 100
        if ((close - target) * dir <= 0.02 || target <= 0) return null
        if (oldSl != null && (target - oldSl) * dir < 0.01) return null
        return target
    }
}
