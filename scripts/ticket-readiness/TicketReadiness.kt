package com.example.algohud.execution

import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

/** Immutable observations; retries never execute trading actions. */
object TicketReadiness {
    data class Side(val count: Int, val enabled: Boolean, val prices: List<String>)
    data class Snapshot(
        val packageMatches: Boolean,
        val titles: List<String>,
        val volumeCount: Int,
        val modifiesPosition: Boolean,
        val sell: Side,
        val buy: Side,
        val capturedAtNanos: Long = System.nanoTime()
    ) {
        fun failure(expected: String): String? = when {
            expected.isBlank() -> "Nie określono instrumentu"
            !packageMatches -> "Brak okna XTB"
            titles.size != 1 -> "Brak lub niejednoznaczny tytuł instrumentu"
            titles.single().trim().split(Regex("\\s+")).firstOrNull() != expected -> "Inny instrument lub inny ekran"
            modifiesPosition -> "Ekran modyfikacji pozycji; nie resetuję jej ochrony"
            volumeCount != 1 -> "Brak lub niejednoznaczne pole wolumenu nowego biletu"
            sell.count != 1 -> "Brak lub niejednoznaczny przycisk Sell"
            buy.count != 1 -> "Brak lub niejednoznaczny przycisk Buy"
            !sell.enabled || !buy.enabled -> "Przyciski Sell/Buy są nieaktywne"
            sell.prices.size != 1 -> "Brak lub niejednoznaczna etykieta ceny Sell"
            buy.prices.size != 1 -> "Brak lub niejednoznaczna etykieta ceny Buy"
            parsePrice(sell.prices.single()) == null -> "Nieprawidłowy format ceny Sell"
            parsePrice(buy.prices.single()) == null -> "Nieprawidłowy format ceny Buy"
            parsePrice(buy.prices.single())!! < parsePrice(sell.prices.single())!! -> "Nieprawidłowa para cen: Buy < Sell"
            else -> null
        }
    }

    fun parsePrice(raw: String): Double? {
        val value = raw.trim().replace(" ", "").replace("\u00A0", "").replace("\u202F", "")
        if (!Regex("[0-9]+(?:[.,][0-9]+)?").matches(value)) return null
        return value.replace(',', '.').toDoubleOrNull()?.takeIf { it.isFinite() && it > 0.0 }
    }

    suspend fun awaitReady(
        instrument: String,
        read: () -> Snapshot,
        timeoutMs: Long = 5_000L,
        intervalMs: Long = 350L,
        nowNanos: () -> Long = System::nanoTime
    ): Snapshot {
        require(timeoutMs > 0 && intervalMs > 0)
        var reason = "Nie wykonano odczytu"
        val ready = withTimeoutOrNull(timeoutMs) {
            var consecutive = 0
            var previousCapture = Long.MIN_VALUE
            var result: Snapshot? = null
            while (result == null) {
                val snapshot = read()
                val age = nowNanos() - snapshot.capturedAtNanos
                val problem = when {
                    age < 0L || age > 1_000_000_000L -> "Nieaktualny odczyt formularza"
                    snapshot.capturedAtNanos <= previousCapture -> "Powtórzono zapamiętany odczyt"
                    else -> snapshot.failure(instrument)
                }
                previousCapture = snapshot.capturedAtNanos
                if (problem == null) {
                    consecutive++
                    if (consecutive >= 2) result = snapshot
                } else {
                    consecutive = 0
                    reason = problem
                }
                if (result == null) delay(intervalMs)
            }
            result
        }
        return ready ?: error("Nie potwierdzono gotowości biletu w ${timeoutMs} ms: $reason")
    }
}
