package com.example.algohud.bot

import java.time.Instant

/**
 * RĘCZNA lista publikacji, wokół których bot nie otwiera nowych pozycji.
 * Uzupełniaj ją z kalendarza makro (czasy w UTC). Wyjścia z pozycji nie są blokowane.
 */
object MacroWindows {
    private class Ev(val name: String, val atUtc: String, val beforeMin: Long, val afterMin: Long)

    private val events = listOf(
        Ev("PPI (USA)", "2026-11-13T13:30:00Z", 15, 30),
        Ev("Decyzja FOMC", "2026-12-09T19:00:00Z", 15, 60)
    )

    fun activeReason(nowMs: Long): String? {
        for (e in events) {
            val t = runCatching { Instant.parse(e.atUtc).toEpochMilli() }.getOrNull() ?: continue
            if (nowMs >= t - e.beforeMin * 60_000L && nowMs <= t + e.afterMin * 60_000L) {
                return "okno publikacji: ${e.name}"
            }
        }
        return null
    }
}
