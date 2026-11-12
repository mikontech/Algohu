package com.example.algohud.bot

import com.example.algohud.core.Position

/** Detects a sustained move against a profitable position, using executable-side prices.
 * Profit is an estimate after spread, before unknown fees, swap and execution slippage.
 * One instance per instrument; call under the orchestrator lock.
 */
class ProfitReversalGuard {
    private data class Sample(val at: Long, val favorable: Double)
    private val samples = ArrayDeque<Sample>()
    private var positionKey: Triple<Long, Double, Int>? = null
    private var peak = 0.0

    fun shouldClose(position: Position, exitPrice: Double, spread: Double, atr: Double,
                    quoteAt: Long, now: Long): Boolean {
        val key = Triple(position.openedAt, position.entryPrice, position.side.sign)
        if (positionKey != key) {
            positionKey = key
            samples.clear()
            peak = 0.0
        }
        if (listOf(exitPrice, spread, atr, position.entryPrice).any { !it.isFinite() || it <= 0 } ||
            quoteAt <= 0 || quoteAt > now + 2_000 || now - quoteAt > 8_000) {
            samples.clear()
            peak = 0.0
            return false
        }
        val lastAt = samples.lastOrNull()?.at
        if (lastAt != null && quoteAt <= lastAt) return false // repeated/stale snapshots are not confirmation
        if (lastAt != null && quoteAt - lastAt > 8_000) {
            samples.clear()
            peak = 0.0
        }
        val favorable = (exitPrice - position.entryPrice) * position.side.sign
        peak = maxOf(peak, favorable)
        samples.addLast(Sample(quoteAt, favorable))
        while (samples.size > 32 || (samples.isNotEmpty() && quoteAt - samples.first().at > 12_000)) samples.removeFirst()

        // Do not take a tiny apparent profit that can disappear in ordinary quote noise.
        if (favorable < maxOf(2 * spread, 0.15 * atr)) return false
        if (peak < maxOf(3 * spread, 0.40 * atr)) return false
        if (peak - favorable < maxOf(2 * spread, 0.25 * atr)) return false
        val recent = samples.toList()
        var first = recent.lastIndex
        while (first > 0 && recent[first].favorable < recent[first - 1].favorable) first--
        return recent.size - first >= 3 && recent.last().at - recent[first].at >= 4_000
    }
}
