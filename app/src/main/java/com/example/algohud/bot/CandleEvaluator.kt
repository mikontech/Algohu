package com.example.algohud.bot

import com.example.algohud.core.Bar
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** Siła impulsu świecy (zastępstwo RVol – liczona wyłącznie z geometrii świecy względem ATR14). */
enum class Impulse { NOISE, WEAK, NORMAL, STRONG }

data class CandleEval(
    val direction: Int,          // +1 wzrostowa, -1 spadkowa, 0 doji
    val bodyToRange: Double,     // korpus / (High − Low)
    val bodyToAtr: Double,       // korpus / ATR14
    val rangeToAtr: Double,      // zasięg / ATR14
    val upperWickToBody: Double, // górny knot / korpus
    val lowerWickToBody: Double, // dolny knot / korpus
    val rejectsLow: Boolean,     // długi dolny knot = absorpcja podaży (kupujący odrzucili dołek)
    val rejectsHigh: Boolean,    // długi górny knot = odrzucenie szczytu
    val impulse: Impulse,
    val score: Double            // 0..1 – siła impulsu do HUD i wyboru poziomu agresji
)

object CandleEvaluator {
    /** Korpus poniżej 25% ATR = lokalny szum / niska płynność → formacje są odrzucane. */
    const val MIN_BODY_ATR = 0.25

    fun evaluate(b: Bar, atr: Double): CandleEval {
        val range = b.high - b.low
        val body = abs(b.close - b.open)
        val upper = b.high - max(b.open, b.close)
        val lower = min(b.open, b.close) - b.low
        val dir = when { b.close > b.open -> 1; b.close < b.open -> -1; else -> 0 }
        val safeBody = max(body, 1e-12)
        val bodyToRange = if (range > 0) body / range else 0.0
        val bodyToAtr = if (atr > 0) body / atr else 0.0
        val rangeToAtr = if (atr > 0) range / atr else 0.0
        val oppositeWick = if (dir >= 0) upper else lower

        val impulse = when {
            bodyToAtr < MIN_BODY_ATR -> Impulse.NOISE
            bodyToAtr >= 1.0 && bodyToRange >= 0.7 && oppositeWick / safeBody <= 0.2 -> Impulse.STRONG
            bodyToAtr >= 0.5 && bodyToRange >= 0.5 -> Impulse.NORMAL
            else -> Impulse.WEAK
        }
        val score = (0.5 * min(bodyToAtr / 1.5, 1.0) + 0.3 * bodyToRange +
            0.2 * (1 - min(oppositeWick / safeBody, 1.0))).coerceIn(0.0, 1.0)
        return CandleEval(
            direction = dir,
            bodyToRange = bodyToRange,
            bodyToAtr = bodyToAtr,
            rangeToAtr = rangeToAtr,
            upperWickToBody = upper / safeBody,
            lowerWickToBody = lower / safeBody,
            rejectsLow = lower >= 2 * body && lower >= 0.5 * atr && upper <= 0.25 * range,
            rejectsHigh = upper >= 2 * body && upper >= 0.5 * atr && lower <= 0.25 * range,
            impulse = impulse,
            score = score
        )
    }
}
