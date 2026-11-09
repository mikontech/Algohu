package com.example.algohud.bot

import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId

/**
 * Godziny sesji instrumentów (czas polski). WARTOŚCI DO WERYFIKACJI w XTB:
 * ekran instrumentu → ⓘ Informacje o instrumencie → Godziny handlu.
 */
object MarketHours {
    val ZONE: ZoneId = ZoneId.of("Europe/Warsaw")
    private val WEEKDAYS = setOf(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY)

    data class Session(val days: Set<DayOfWeek>, val open: LocalTime, val close: LocalTime)
    data class Hours(val sessions: List<Session>, val openEvents: List<LocalTime>)

    data class Status(
        val open: Boolean,
        /** Minuty do końca bieżącej sesji (null = rynek 24/7). */
        val minutesToClose: Long?,
        /** Ostatnie "otwarcie" (start trybu Open Breakout) dziś, ms; null = brak. */
        val lastOpenEventMs: Long?
    )

    private fun t(h: Int, m: Int) = LocalTime.of(h, m)
    private val COMMODITY = Hours(listOf(Session(WEEKDAYS, t(0, 5), t(23, 0))), listOf(t(9, 0), t(15, 30)))
    private val ALWAYS = Hours(emptyList(), emptyList())

    val TABLE: Map<String, Hours> = mapOf(
        "GOLD" to COMMODITY,
        "SILVER" to COMMODITY,
        "OIL" to COMMODITY,
        "OIL.WTI" to COMMODITY,
        "COFFEE" to Hours(listOf(Session(WEEKDAYS, t(10, 15), t(19, 30))), listOf(t(10, 15))),
        "COCOA" to Hours(listOf(Session(WEEKDAYS, t(10, 45), t(19, 30))), listOf(t(10, 45))),
        "DE40" to Hours(listOf(Session(WEEKDAYS, t(8, 0), t(22, 0))), listOf(t(9, 0), t(15, 30))),
        "W20" to Hours(listOf(Session(WEEKDAYS, t(9, 0), t(16, 50))), listOf(t(9, 0))),
        "US100" to Hours(listOf(Session(WEEKDAYS, t(0, 5), t(23, 0))), listOf(t(15, 30))),
        "US500" to Hours(listOf(Session(WEEKDAYS, t(0, 5), t(23, 0))), listOf(t(15, 30))),
        "US30" to Hours(listOf(Session(WEEKDAYS, t(0, 5), t(23, 0))), listOf(t(15, 30))),
        "BITCOIN" to ALWAYS,
        "ETHEREUM" to ALWAYS
    )

    fun status(inst: String, nowMs: Long): Status {
        val h = TABLE[inst] ?: COMMODITY
        val now = Instant.ofEpochMilli(nowMs).atZone(ZONE)
        val time = now.toLocalTime()
        val lastEvent = if (now.dayOfWeek !in WEEKDAYS) null else h.openEvents
            .map { now.toLocalDate().atTime(it).atZone(ZONE) }
            .filter { !it.isAfter(now) }
            .maxOrNull()?.toInstant()?.toEpochMilli()
        if (h.sessions.isEmpty()) return Status(true, null, lastEvent)
        val s = h.sessions.firstOrNull { now.dayOfWeek in it.days && !time.isBefore(it.open) && time.isBefore(it.close) }
        return Status(s != null, s?.let { Duration.between(time, it.close).toMinutes() }, lastEvent)
    }
}
