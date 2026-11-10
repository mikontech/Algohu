package com.example.algohud.bot

import java.io.File

/**
 * Ciągły zapis świec 1m i 5m dla każdego instrumentu, który ma ceny w tle.
 * Pliki: rec_<INSTRUMENT>_<1|5>m.csv, wiersz: start_ms,open,high,low,close,liczba_tickow.
 * Dane służą do późniejszej nauki; źródła cen mają osobne pliki (np. GOLD i GOLD.BIN).
 */
object CandleRecorder {
    private const val MAX_LINES = 60_000

    private class Cur(val start: Long, val o: Double, var h: Double, var l: Double, var c: Double, var n: Int)

    @Volatile private var dir: File? = null
    private val current = HashMap<String, Cur>()
    private val lineCount = HashMap<String, Int>()

    fun attach(d: File) {
        dir = d
    }

    fun record(inst: String, price: Double, ts: Long) {
        val d = dir ?: return
        if (!price.isFinite() || price <= 0.0 || ts <= 0L) return
        for (tfMin in intArrayOf(1, 5)) {
            val tf = tfMin * 60_000L
            val start = ts - ts % tf
            val key = "$inst@$tfMin"
            var closed: Cur? = null
            synchronized(this) {
                val c = current[key]
                if (c == null) {
                    current[key] = Cur(start, price, price, price, price, 1)
                } else if (start < c.start) {
                    // spóźnione tyknięcie z poprzedniej świecy – pomijamy
                } else if (start == c.start) {
                    if (price > c.h) c.h = price
                    if (price < c.l) c.l = price
                    c.c = price
                    c.n++
                } else {
                    closed = c
                    current[key] = Cur(start, price, price, price, price, 1)
                }
                Unit
            }
            closed?.let { append(d, inst, tfMin, it) }
        }
    }

    private fun fileFor(d: File, inst: String, tfMin: Int) =
        File(d, "rec_${inst.replace(Regex("[^A-Za-z0-9._-]"), "_")}_${tfMin}m.csv")

    private fun append(d: File, inst: String, tfMin: Int, c: Cur) {
        runCatching {
            synchronized(this) {
                val f = fileFor(d, inst, tfMin)
                var n = lineCount[f.name] ?: (if (f.exists()) f.useLines { it.count() } else 0)
                f.appendText("${c.start},${c.o},${c.h},${c.l},${c.c},${c.n}\n")
                n++
                if (n > MAX_LINES + 5_000) {
                    val keep = f.readLines().takeLast(MAX_LINES)
                    f.writeText(keep.joinToString("\n", postfix = "\n"))
                    n = keep.size
                }
                lineCount[f.name] = n
            }
        }
    }

    /** Podsumowanie zapisanych plików (do podglądu w aplikacji). */
    fun stats(d: File): String {
        val files = d.listFiles { f -> f.name.startsWith("rec_") && f.name.endsWith(".csv") }
            ?.sortedBy { it.name }.orEmpty()
        if (files.isEmpty()) return "Brak zapisanych świec – uruchom HUD i poczekaj kilka minut."
        return files.joinToString("\n") { f ->
            val lines = f.useLines { s -> s.count() }
            val last = f.useLines { s -> s.lastOrNull() }?.substringBefore(',')?.toLongOrNull()
            val age = last?.let { (System.currentTimeMillis() - it) / 60_000 }
            val name = f.name.removePrefix("rec_").removeSuffix(".csv")
            "$name: $lines świec" + (age?.let { ", ostatnia $it min temu" } ?: "")
        }
    }
}
