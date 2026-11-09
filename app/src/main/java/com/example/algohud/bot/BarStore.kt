package com.example.algohud.bot

import com.example.algohud.core.Bar
import java.io.File

/** Trwały zapis świec instrumentów bez cen w tle (zbieranych z ekranu XTB) – nauka nie ginie po restarcie. */
class BarStore(private val dir: File, private val maxBars: Int = 20_000) {
    private fun file(inst: String, tf: Long) = File(dir, "bars_${inst.replace(Regex("[^A-Za-z0-9._-]"), "_")}_${tf / 60_000}m.csv")

    @Synchronized
    fun load(inst: String, tf: Long): List<Bar> {
        val f = file(inst, tf)
        if (!f.exists()) return emptyList()
        return f.readLines().mapNotNull { line ->
            val p = line.split(',')
            if (p.size != 5) null
            else runCatching { Bar(p[0].toLong(), p[1].toDouble(), p[2].toDouble(), p[3].toDouble(), p[4].toDouble()) }.getOrNull()
        }.distinctBy { it.start }.sortedBy { it.start }.takeLast(maxBars)
    }

    @Synchronized
    fun append(inst: String, tf: Long, b: Bar) {
        val f = file(inst, tf)
        f.appendText("${b.start},${b.open},${b.high},${b.low},${b.close}\n")
        // Co jakiś czas przycinamy plik do [maxBars] ostatnich świec.
        if (f.length() > maxBars * 80L) {
            val keep = load(inst, tf)
            f.writeText(keep.joinToString("") { "${it.start},${it.open},${it.high},${it.low},${it.close}\n" })
        }
    }
}
