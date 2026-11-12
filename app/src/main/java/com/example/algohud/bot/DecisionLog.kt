package com.example.algohud.bot

import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Dziennik decyzji bota: każda decyzja (czas, instrument, cena, score long/short, typ, powód) jako wiersz CSV.
 * Jeden plik na dzień: decisions-RRRR-MM-DD.csv w katalogu podanym w [attach]. Błędy zapisu nie zatrzymują bota.
 */
object DecisionLog {
    private val zone: ZoneId = ZoneId.of("Europe/Warsaw")
    private val dayFmt = DateTimeFormatter.ofPattern("yyyy-MM-dd")
    private val timeFmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
    @Volatile private var dir: File? = null
    private var lastKey: String? = null

    const val HEADER = "czas;tryb;instrument;typ;cena;score_long;score_short;trend_htf;powod"

    @Synchronized
    fun attach(directory: File, keepDays: Int = BotConfig.Log.KEEP_DAYS) {
        runCatching {
            directory.mkdirs()
            dir = directory
            val cutoff = System.currentTimeMillis() - keepDays * 86_400_000L
            directory.listFiles { f -> f.name.startsWith("decisions-") && f.lastModified() < cutoff }?.forEach { it.delete() }
        }
    }

    fun path(): String? = dir?.absolutePath

    /**
     * @param type np. SCORE, WEJŚCIE, ODMOWA, WYJŚCIE, ODWRÓCENIE, TRAILING, US_OPEN
     * @param mode tryb wykonania (DRY_RUN = papier, AUTO = prawdziwe zlecenia)
     */
    @Synchronized
    fun log(now: Long, mode: String, instrument: String, type: String, price: Double?, long: Double?, short: Double?, htf: Int?, reason: String) {
        if (!BotConfig.Log.ENABLED) return
        val d = dir ?: return
        // Ten sam wpis co poprzedni (np. ta sama odmowa co sekundę) zapisujemy raz.
        val key = "$instrument|$type|$reason"
        if (key == lastKey && type != "SCORE") return
        lastKey = key
        runCatching {
            val t = Instant.ofEpochMilli(now).atZone(zone)
            val f = File(d, "decisions-${t.format(dayFmt)}.csv")
            val fresh = !f.exists()
            f.appendText(buildString {
                if (fresh) append(HEADER).append('\n')
                append(format(t.format(timeFmt), mode, instrument, type, price, long, short, htf, reason)).append('\n')
            })
        }
    }

    fun format(time: String, mode: String, instrument: String, type: String, price: Double?, long: Double?, short: Double?, htf: Int?, reason: String): String {
        fun n(v: Double?, p: String) = v?.let { String.format(Locale.ROOT, p, it) } ?: ""
        val trend = when (htf) { null -> ""; 1 -> "UP"; -1 -> "DOWN"; else -> "FLAT" }
        return listOf(time, mode, instrument, type, n(price, "%.2f"), n(long, "%.1f"), n(short, "%.1f"), trend,
            reason.replace(';', ',').replace('\n', ' ')).joinToString(";")
    }
}
