package com.example.algohud.execution

/** Wyłącznie odczyt brokera. Żadnego przeliczenia przez zmienną różnicę feed–XTB. */
object ProtectionDisplay {
    fun line(row: PositionRowInfo?): String = if (row == null) "SL XTB: brak świeżego potwierdzenia"
        else "SL XTB: " + (row.sl?.takeIf { it.isFinite() && it > 0 }?.let { "%.2f".format(it) } ?: "brak ustawionego SL")
}
