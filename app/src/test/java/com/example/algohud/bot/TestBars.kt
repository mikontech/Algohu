package com.example.algohud.bot

import com.example.algohud.core.Bar
import kotlin.math.max
import kotlin.math.min

/** Pomocnicze świece syntetyczne do testów logiki (bez telefonu, bez sieci). */
object TestBars {
    const val M1 = 60_000L
    const val M5 = 300_000L
    /** Poniedziałek 2026-11-09 00:00 UTC. */
    const val T0 = 1_794_182_400_000L

    /** Świece z ceną zamknięcia [close](i); knoty po [wick] z każdej strony. */
    fun series(n: Int, periodMs: Long = M1, start: Long = T0, wick: Double = 0.1, close: (Int) -> Double): List<Bar> {
        val out = ArrayList<Bar>(n)
        var prev = close(0)
        for (i in 0 until n) {
            val c = close(i)
            out.add(Bar(start + i * periodMs, prev, max(prev, c) + wick, min(prev, c) - wick, c))
            prev = c
        }
        return out
    }

    /** Trend z falowaniem (żeby powstawały swingi): nachylenie [drift] na świecę, fala o amplitudzie [amp]. */
    fun wave(n: Int, base: Double = 4000.0, drift: Double, amp: Double = 1.5, len: Double = 9.0, periodMs: Long = M1, start: Long = T0): List<Bar> =
        series(n, periodMs, start) { i -> base + drift * i + amp * Math.sin(i / len * 2 * Math.PI) }

    fun m5Of(m1: List<Bar>): List<Bar> = Indicators.resample(m1, M5)

    fun bar(start: Long, o: Double, h: Double, l: Double, c: Double) = Bar(start, o, h, l, c)
}
