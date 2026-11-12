package com.example.algohud.bot

import com.example.algohud.core.Bar
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import kotlin.math.abs

/**
 * Sesja „US Open”: otwarcie giełdy w Nowym Jorku o 9:30 czasu NY. Czas liczony przez strefę America/New_York,
 * więc w Polsce wypada o 15:30, a w tygodniach, gdy USA i Europa zmieniają czas w różnych terminach – o 14:30.
 * Strategia Opening Range Breakout: zakres z pierwszych minut, wejście po zamknięciu świecy M1 poza zakresem.
 * Czysta logika – bez Androida. Ustawienia: [BotConfig.UsOpen].
 */
object UsOpenSession {
    val ZONE: ZoneId = ZoneId.of(BotConfig.UsOpen.ZONE)

    enum class Phase(val label: String) {
        /** Poza oknem (albo weekend / święto). */
        OFF("nieaktywny"),
        PRE_OPEN("przed otwarciem"),
        /** Pierwsze minuty po otwarciu – żadnych wejść. */
        NO_ENTRY("pierwsze minuty – bez wejść"),
        BUILDING_RANGE("buduję zakres otwarcia"),
        BREAKOUT("czekam na wybicie zakresu")
    }

    data class Status(
        val phase: Phase,
        /** Chwila otwarcia dzisiejszej sesji (ms); null = dziś nie ma sesji. */
        val openMs: Long?,
        /** Minuty od otwarcia (ujemne przed). */
        val minutesFromOpen: Long?,
        /** Dlaczego dziś nie ma sesji (weekend / święto); null = jest. */
        val closedWhy: String?
    ) {
        val active: Boolean get() = phase != Phase.OFF
        /** Żadna strategia nie powinna teraz otwierać pozycji (tuż po otwarciu). */
        val blocksEntries: Boolean get() = phase == Phase.NO_ENTRY
    }

    data class Range(val high: Double, val low: Double, val bars: Int)

    data class Setup(val dir: Int, val stop: Double, val range: Range, val barStart: Long, val reason: String)

    sealed interface Orb {
        /** Jeszcze nic – [note] mówi, na co czekamy. */
        data class Wait(val note: String) : Orb
        /** Wybicie było, ale setup pominięty (np. za szeroki zakres). */
        data class Skip(val barStart: Long, val note: String) : Orb
        data class Enter(val setup: Setup) : Orb
    }

    fun isTradingDay(date: LocalDate, holidays: Set<LocalDate> = BotConfig.UsOpen.HOLIDAYS): Boolean =
        date.dayOfWeek != DayOfWeek.SATURDAY && date.dayOfWeek != DayOfWeek.SUNDAY && date !in holidays

    /** Chwila otwarcia sesji danego dnia (9:30 w Nowym Jorku) w ms UTC. */
    fun openMs(date: LocalDate): Long =
        date.atTime(LocalTime.of(BotConfig.UsOpen.OPEN_HOUR, BotConfig.UsOpen.OPEN_MINUTE)).atZone(ZONE).toInstant().toEpochMilli()

    /** Godzina otwarcia w innej strefie (np. Europe/Warsaw) – do HUD. */
    fun openLocalTime(date: LocalDate, zone: ZoneId): LocalTime = Instant.ofEpochMilli(openMs(date)).atZone(zone).toLocalTime()

    fun status(
        nowMs: Long,
        holidays: Set<LocalDate> = BotConfig.UsOpen.HOLIDAYS,
        beforeMin: Long = BotConfig.UsOpen.WINDOW_BEFORE_MIN,
        afterMin: Long = BotConfig.UsOpen.WINDOW_AFTER_MIN,
        noEntryMin: Long = BotConfig.UsOpen.NO_ENTRY_MIN,
        rangeMin: Long = BotConfig.UsOpen.RANGE_MIN
    ): Status {
        val date = Instant.ofEpochMilli(nowMs).atZone(ZONE).toLocalDate()
        if (!BotConfig.UsOpen.ENABLED) return Status(Phase.OFF, null, null, "tryb wyłączony w konfiguracji")
        if (date.dayOfWeek == DayOfWeek.SATURDAY || date.dayOfWeek == DayOfWeek.SUNDAY) return Status(Phase.OFF, null, null, "weekend")
        if (date in holidays) return Status(Phase.OFF, null, null, "święto giełdowe w USA")
        val open = openMs(date)
        val diffMs = nowMs - open
        val min = Math.floorDiv(diffMs, 60_000L)
        val phase = when {
            diffMs < -beforeMin * 60_000L || diffMs >= afterMin * 60_000L -> Phase.OFF
            diffMs < 0 -> Phase.PRE_OPEN
            min < noEntryMin -> Phase.NO_ENTRY
            min < rangeMin -> Phase.BUILDING_RANGE
            else -> Phase.BREAKOUT
        }
        return Status(phase, open, min, null)
    }

    /** Zakres otwarcia z zamkniętych świec M1 z pierwszych [rangeMin] minut sesji; null = zakres jeszcze niepełny. */
    fun range(m1: List<Bar>, openMs: Long, rangeMin: Long = BotConfig.UsOpen.RANGE_MIN): Range? {
        val end = openMs + rangeMin * 60_000L
        val bars = m1.filter { it.start >= openMs && it.start < end }
        // Zakres jest gotowy, gdy mamy świecę, która zaczęła się po nim (czyli ostatnia świeca zakresu jest zamknięta).
        if (bars.isEmpty() || m1.none { it.start >= end - 60_000L }) return null
        if (bars.size < (rangeMin + 1) / 2) return null // za dużo dziur w danych
        return Range(bars.maxOf { it.high }, bars.minOf { it.low }, bars.size)
    }

    /**
     * Opening Range Breakout po zamknięciu świecy M1.
     * @param m1 zamknięte świece M1 (ostatnia = właśnie zamknięta)
     * @param perPricePln ile PLN zmienia się wynik pozycji przy ruchu ceny o 1,0 (lot × wartość punktu)
     * @param confirm zgoda SignalEngine na kierunek: null = wolno, inaczej powód odmowy
     */
    fun orb(
        m1: List<Bar>, openMs: Long, spread: Double, atrM1: Double, perPricePln: Double,
        confirm: (dir: Int) -> String?,
        rangeMin: Long = BotConfig.UsOpen.RANGE_MIN,
        afterMin: Long = BotConfig.UsOpen.WINDOW_AFTER_MIN,
        noEntryMin: Long = BotConfig.UsOpen.NO_ENTRY_MIN,
        slMinPln: Double = BotConfig.Risk.SL_MIN_PLN,
        slMaxPln: Double = BotConfig.Risk.SL_MAX_PLN
    ): Orb {
        val rng = range(m1, openMs, rangeMin) ?: return Orb.Wait("buduję zakres z pierwszych $rangeMin min")
        val bar = m1.last()
        val firstAllowed = openMs + maxOf(rangeMin, noEntryMin) * 60_000L
        if (bar.start < firstAllowed || bar.start >= openMs + afterMin * 60_000L) {
            return Orb.Wait("zakres %.2f–%.2f gotowy".format(rng.low, rng.high))
        }
        val dir = when { bar.close > rng.high -> 1; bar.close < rng.low -> -1; else -> 0 }
        if (dir == 0) return Orb.Wait("zakres %.2f–%.2f: LONG po zamknięciu M1 > %.2f, SHORT po zamknięciu < %.2f".format(rng.low, rng.high, rng.high, rng.low))
        val name = if (dir > 0) "LONG" else "SHORT"
        confirm(dir)?.let { return Orb.Skip(bar.start, "wybicie $name zakresu – bez zgody score: $it") }
        val entry = bar.close + dir * spread / 2
        val buffer = maxOf(BotConfig.UsOpen.STOP_BUFFER_ATR * atrM1, spread)
        var stop = (if (dir > 0) rng.low else rng.high) - dir * buffer
        if (perPricePln > 0) {
            val loss = abs(entry - stop) * perPricePln
            if (loss > slMaxPln) {
                return Orb.Skip(bar.start, "wybicie $name, ale zakres za szeroki: SL za krawędzią = %.0f zł > %.0f zł – pomijam".format(loss, slMaxPln))
            }
            if (loss < slMinPln) stop = entry - dir * slMinPln / perPricePln
        }
        return Orb.Enter(Setup(dir, stop, rng, bar.start,
            "ORB US Open $name: zamknięcie M1 %.2f poza zakresem %.2f–%.2f".format(bar.close, rng.low, rng.high)))
    }
}
