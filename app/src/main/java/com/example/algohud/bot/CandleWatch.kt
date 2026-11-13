package com.example.algohud.bot

import com.example.algohud.core.Bar
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs

/** Opis obserwowanych świec. Nie generuje zleceń ani nie przewiduje wyniku formacji. */
object CandleWatch {
    data class Shape(val body: Double, val upper: Double, val lower: Double, val range: Double,
                     val closePosition: Double?, val direction: Int)
    data class Frame(val label: String, val periodMs: Long, val closed: List<Bar>, val live: Bar?,
                     val warnings: List<String>, val now: Long) {
        val last get() = closed.lastOrNull()
        val direction get() = last?.let { shape(it).direction } ?: 0
    }
    data class Snapshot(val m1: Frame, val m5: Frame, val tickAgeMs: Long?) {
        fun summary(): String = buildString {
            append("🕯 Świece z feedu: ")
            append(listOf(m1, m5).joinToString(" • ") { f ->
                val b = f.last
                "${f.label} zamk.: " + (if (b == null) "brak" else "${directionName(shape(b).direction)} ${patterns(f.closed, f.periodMs).joinToString(", ").ifBlank { "bez formacji" }}")
            })
            append("\nM1 w budowie: ")
            val live = m1.live
            append(if (live == null) "brak" else "${directionName(shape(live).direction)}, ${progress(m1)}% czasu; bez potwierdzenia")
            append(" • wiek ceny: ${tickAgeMs?.let { "${it / 1000}s" } ?: "brak"}")
            val warnings = m1.warnings + m5.warnings
            if (warnings.isNotEmpty()) append("\n⚠ " + warnings.distinct().joinToString("; "))
            if (tickAgeMs == null || tickAgeMs > 10_000) append("\n⚠ brak świeżej ceny – nie zakładaj ciągłej obserwacji")
        }
        fun report(): String = buildString {
            appendLine("ŚWIECE Z FEEDU BOTA, nie bezpośredni OHLC brokera XTB. Brak danych o wolumenie; nie wnioskuj o nim.")
            appendLine("Stan na ${time(m1.now)} UTC; wiek ostatniej ceny=${tickAgeMs?.let { "${it}ms" } ?: "BRAK"}.")
            if (tickAgeMs == null || tickAgeMs > 10_000) appendLine("UWAGA: cena nieświeża/nieznana, obraz rynku może być nieaktualny.")
            for (f in listOf(m1, m5)) {
                appendLine("${f.label}: ${f.closed.size} poprawnych zamkniętych świec; ${f.warnings.joinToString("; ").ifBlank { "bez wykrytych luk" }}")
                appendLine("Historia zamknięta, od najstarszej, start UTC|O|H|L|C:")
                f.closed.forEach { appendLine("${time(it.start)}|${n(it.open)}|${n(it.high)}|${n(it.low)}|${n(it.close)}") }
                appendLine("Szczegóły ostatnich 6 zamkniętych (proporcje względem H-L):")
                f.closed.takeLast(6).forEach { b -> appendLine(describe(b)) }
                appendLine("Ostatni układ ${f.label}: ${patterns(f.closed, f.periodMs).joinToString(", ").ifBlank { "brak" }}")
                val recent = f.closed.takeLast(3)
                if (recent.size == 3 && contiguous(recent, f.periodMs)) {
                    appendLine("Ruch zamknięć przez 2 interwały=${n(recent.last().close - recent.first().close)}; " +
                        "struktura=" + when {
                            recent.zipWithNext().all { (a, b) -> b.high > a.high && b.low > a.low } -> "wyższe szczyty i dołki"
                            recent.zipWithNext().all { (a, b) -> b.high < a.high && b.low < a.low } -> "niższe szczyty i dołki"
                            else -> "mieszana"
                        })
                }
                val live = f.live
                if (live == null) appendLine("W BUDOWIE: brak aktualnej świecy.") else {
                    appendLine("W BUDOWIE – NIEPOTWIERDZONA: ${describe(live)}; czas=${progress(f)}%; do końca=${(live.start + f.periodMs - f.now) / 1000}s")
                    val elapsed = f.now - live.start
                    if (elapsed >= 5_000) appendLine("Średnie przesunięcie od O na minutę=${n((live.close - live.open) * 60_000 / elapsed)}; nie prognoza zamknięcia.")
                    f.last?.let { prev ->
                        if (live.start - prev.start == f.periodMs)
                            appendLine("Względem poprzedniej: ${relation(live, prev).joinToString(", ").ifBlank { "w zakresie" }} (wstępne)")
                    }
                }
            }
            appendLine("Kierunek ostatnich zamkniętych M1/M5: " + when {
                m1.last == null || m5.last == null -> "za mało danych"
                m1.direction == 0 || m5.direction == 0 -> "co najmniej jedna neutralna"
                m1.direction == m5.direction -> "zgodny – to nie jest samodzielny sygnał"
                else -> "rozbieżny – wymagany kontekst"
            })
        }
    }

    fun snapshot(m1: List<Bar>, m5: List<Bar>, currentM1: Bar?, currentM5: Bar?,
                 now: Long, lastTickAt: Long?): Snapshot = Snapshot(
        frame("M1", 60_000, m1.takeLast(30), currentM1, now),
        frame("M5", 300_000, m5.takeLast(24), currentM5, now),
        lastTickAt?.takeIf { it <= now + 2_000 }?.let { (now - it).coerceAtLeast(0) })

    private fun valid(b: Bar) = listOf(b.open, b.high, b.low, b.close).all { it.isFinite() && it > 0 } &&
        b.high >= maxOf(b.open, b.close) && b.low <= minOf(b.open, b.close) && b.high >= b.low

    fun frame(label: String, period: Long, bars: List<Bar>, current: Bar?, now: Long): Frame {
        val warnings = mutableListOf<String>()
        val good = bars.filter { valid(it) && it.start >= 0 && it.start % period == 0L && it.start + period <= now }
        if (good.size != bars.size) warnings += "$label: pominięto błędne/niezamknięte świece"
        if (good.zipWithNext().any { (a, b) -> a.start >= b.start }) warnings += "$label: nieuporządkowana lub powielona historia"
        val ordered = good.distinctBy { it.start }.sortedBy { it.start }
        if (!contiguous(ordered, period)) warnings += "$label: luki w historii"
        if (ordered.isEmpty()) warnings += "$label: brak historii"
        else if (now - (ordered.last().start + period) >= period) warnings += "$label: ostatnia zamknięta nieaktualna"
        val live = current?.takeIf { valid(it) && it.start >= 0 && it.start % period == 0L && now >= it.start && now < it.start + period }
        if (current != null && live == null) warnings += "$label: bieżąca świeca nieaktualna lub błędna"
        return Frame(label, period, ordered, live, warnings, now)
    }

    fun shape(b: Bar): Shape {
        val range = b.high - b.low
        return Shape(abs(b.close - b.open), b.high - maxOf(b.open, b.close), minOf(b.open, b.close) - b.low,
            range, if (range > 0) (b.close - b.low) / range else null,
            when { b.close > b.open -> 1; b.close < b.open -> -1; else -> 0 })
    }

    /** Układy są opisem geometrii, a nie gwarancją wybicia/odwrócenia. */
    fun patterns(bars: List<Bar>, period: Long): List<String> {
        val b = bars.lastOrNull() ?: return emptyList()
        val s = shape(b)
        val out = mutableListOf<String>()
        if (s.range <= 0) return listOf("brak zakresu")
        if (s.body / s.range <= 0.1) out += "doji"
        if (s.lower / s.range >= 0.6 && s.upper / s.range <= 0.2) out += "długi dolny knot"
        if (s.upper / s.range >= 0.6 && s.lower / s.range <= 0.2) out += "długi górny knot"
        if (s.body / s.range >= 0.8) out += "dominujący korpus"
        val prev = bars.getOrNull(bars.lastIndex - 1)
        if (prev != null && b.start - prev.start == period) {
            val p = shape(prev)
            if (s.direction != 0 && p.direction == -s.direction &&
                minOf(b.open, b.close) <= minOf(prev.open, prev.close) &&
                maxOf(b.open, b.close) >= maxOf(prev.open, prev.close) && s.body > p.body)
                out += "objęcie korpusu ${directionName(s.direction)}"
            if (b.high <= prev.high && b.low >= prev.low) out += "inside bar"
            if (b.high > prev.high && b.low < prev.low) out += "outside bar"
            out += relation(b, prev)
        }
        return out
    }

    private fun relation(b: Bar, prev: Bar): List<String> = buildList {
        if (b.close > prev.high) add("C ponad poprzednim H")
        else if (b.high > prev.high && b.close < prev.high) add("powrót pod poprzednie H po naruszeniu")
        if (b.close < prev.low) add("C poniżej poprzedniego L")
        else if (b.low < prev.low && b.close > prev.low) add("powrót nad poprzednie L po naruszeniu")
    }
    private fun contiguous(bars: List<Bar>, period: Long) = bars.zipWithNext().all { (a, b) -> b.start - a.start == period }
    private fun describe(b: Bar): String {
        val s = shape(b)
        fun pct(v: Double) = if (s.range > 0) n(100 * v / s.range) + "%" else "n/d"
        return "${time(b.start)} ${directionName(s.direction)} O=${n(b.open)} H=${n(b.high)} L=${n(b.low)} C=${n(b.close)} " +
            "zakres=${n(s.range)}, korpus=${n(s.body)} (${pct(s.body)}), knot górny=${n(s.upper)} (${pct(s.upper)}), " +
            "dolny=${n(s.lower)} (${pct(s.lower)}), położenie C=${s.closePosition?.let { n(it * 100) + "%" } ?: "n/d"}"
    }
    private fun progress(f: Frame): Long = f.live?.let { (100 * (f.now - it.start) / f.periodMs).coerceIn(0, 99) } ?: 0
    private fun directionName(d: Int) = when { d > 0 -> "wzrostowa"; d < 0 -> "spadkowa"; else -> "neutralna" }
    private val clock = DateTimeFormatter.ofPattern("MM-dd HH:mm:ss").withZone(ZoneOffset.UTC)
    private fun time(ms: Long) = clock.format(Instant.ofEpochMilli(ms))
    private fun n(v: Double) = String.format(Locale.US, "%.2f", v)
}
