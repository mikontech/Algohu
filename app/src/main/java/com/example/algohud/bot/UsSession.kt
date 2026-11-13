package com.example.algohud.bot

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/**
 * Zegar sesji kasowej w USA liczony w czasie NOWEGO JORKU (America/New_York), a nie na sztywno w czasie polskim.
 * Otwarcie NYSE 9:30 ET to zwykle 15:30 w Polsce, ale w tygodniach, gdy zmiana czasu w USA i w Europie
 * nie pokrywa się (marzec, przełom października/listopada), wypada o 14:30 – stała godzina by to przegapiła.
 */
object UsSession {
    val NY: ZoneId = ZoneId.of("America/New_York")
    val PL: ZoneId = ZoneId.of("Europe/Warsaw")
    val OPEN: LocalTime = LocalTime.of(9, 30)

    /**
     * Dni bez sesji NYSE (pełne święta). UZUPEŁNIAJ co rok z kalendarza NYSE.
     * 2026: Nowy Rok, MLK, Presidents, Wielki Piątek, Memorial, Juneteenth, Independence (obs.), Labor, Thanksgiving, Boże Narodzenie.
     */
    val HOLIDAYS: Set<LocalDate> = listOf(
        "2026-01-01", "2026-01-19", "2026-02-16", "2026-04-03", "2026-05-25", "2026-06-19",
        "2026-07-03", "2026-09-07", "2026-11-26", "2026-12-25",
        "2027-01-01", "2027-01-18", "2027-02-15", "2027-03-26", "2027-05-31", "2027-06-18",
        "2027-07-05", "2027-09-06", "2027-11-25", "2027-12-24"
    ).map(LocalDate::parse).toSet()

    enum class Phase(val label: String) {
        OFF("poza oknem otwarcia USA"),
        PRE("tuż przed otwarciem USA – nie wchodzę"),
        SPIKE("pierwsze minuty po otwarciu USA – spread/poślizg, nie wchodzę"),
        RANGE("buduję zakres otwarcia USA"),
        ACTIVE("otwarcie USA – gram wybicie zakresu"),
        HOLIDAY("święto w USA – brak sesji")
    }

    data class Status(val phase: Phase, val openMs: Long?, val minutesFromOpen: Long?)

    fun isTradingDay(date: LocalDate): Boolean =
        date.dayOfWeek != DayOfWeek.SATURDAY && date.dayOfWeek != DayOfWeek.SUNDAY && date !in HOLIDAYS

    /** Dzisiejsze otwarcie 9:30 ET (ms) albo null, gdy dziś w USA nie ma sesji. */
    fun openMs(nowMs: Long): Long? {
        val date = Instant.ofEpochMilli(nowMs).atZone(NY).toLocalDate()
        if (!isTradingDay(date)) return null
        return date.atTime(OPEN).atZone(NY).toInstant().toEpochMilli()
    }

    fun status(
        nowMs: Long,
        preMin: Long = UsOpenOrb.PRE_MIN,
        spikeMin: Long = UsOpenOrb.SPIKE_MIN,
        rangeMin: Long = UsOpenOrb.RANGE_MIN,
        activeMin: Long = UsOpenOrb.ACTIVE_MIN
    ): Status {
        val date = Instant.ofEpochMilli(nowMs).atZone(NY).toLocalDate()
        if (date.dayOfWeek == DayOfWeek.SATURDAY || date.dayOfWeek == DayOfWeek.SUNDAY) return Status(Phase.OFF, null, null)
        if (date in HOLIDAYS) return Status(Phase.HOLIDAY, null, null)
        val open = openMs(nowMs) ?: return Status(Phase.OFF, null, null)
        val diffMs = nowMs - open
        val m = Math.floorDiv(diffMs, 60_000L)
        val phase = when {
            diffMs < -preMin * 60_000L -> Phase.OFF
            diffMs < 0 -> Phase.PRE
            m < spikeMin -> Phase.SPIKE
            m < rangeMin -> Phase.RANGE
            m < activeMin -> Phase.ACTIVE
            else -> Phase.OFF
        }
        return Status(phase, open, m)
    }

    /** Godzina otwarcia USA w czasie polskim (do HUD-a), np. "15:30" albo "14:30" w tygodniu przesunięcia czasu. */
    fun openInPoland(nowMs: Long): String? = openMs(nowMs)?.let {
        val t = Instant.ofEpochMilli(it).atZone(PL).toLocalTime()
        "%02d:%02d".format(t.hour, t.minute)
    }

    /**
     * Krótka przerwa na NOWE wejścia w samym momencie publikacji danych z USA (czas ET):
     * 8:30 (NFP/CPI/PPI, zasiłki…), 10:00 (ISM, JOLTS…), 14:00 (FOMC) – przez [DATA_PAUSE_MIN] min.
     * Bot omija tylko pierwszy wystrzał i szeroki spread, a zaraz potem może grać ruch po danych.
     * Null = można otwierać pozycje. Wyjść z pozycji nie blokuje.
     */
    fun dataBlackout(nowMs: Long): String? {
        val t = Instant.ofEpochMilli(nowMs).atZone(NY)
        if (t.dayOfWeek == DayOfWeek.SATURDAY || t.dayOfWeek == DayOfWeek.SUNDAY) return null
        val m = t.hour * 60 + t.minute
        for ((h, min, name) in DATA_TIMES) {
            val start = h * 60 + min
            if (m in start until start + DATA_PAUSE_MIN) {
                val z = t.toLocalDate().atTime(h, min).atZone(NY).withZoneSameInstant(PL)
                val end = z.plusMinutes(DATA_PAUSE_MIN.toLong())
                return "%s %02d:%02d – przerwa na wejścia do %02d:%02d (spajk i szeroki spread)".format(name, z.hour, z.minute, end.hour, end.minute)
            }
        }
        return null
    }

    const val DATA_PAUSE_MIN = 2
    private val DATA_TIMES = listOf(Triple(8, 30, "dane USA"), Triple(10, 0, "dane USA"), Triple(14, 0, "decyzja FOMC"))
}
