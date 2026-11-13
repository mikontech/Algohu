package com.example.algohud.core

/** Jednorazowa transformacja przed radą AI i kontrolą ryzyka. Wyjścia nigdy nie są odwracane. */
object EntryDirection {
    fun prepare(order: OrderRequest, reverse: Boolean): OrderRequest {
        if (order.reduceOnly || order.directionPrepared) return order
        if (!reverse) return order.copy(directionPrepared = true)
        return order.copy(side = order.side.opposite(),
            stopPrice = 2 * order.referencePrice - order.stopPrice,
            takeProfit = order.takeProfit?.let { 2 * order.referencePrice - it },
            reason = order.reason + " [ODWRÓCONE: ${order.side} → ${order.side.opposite()}]",
            directionPrepared = true)
    }
}
