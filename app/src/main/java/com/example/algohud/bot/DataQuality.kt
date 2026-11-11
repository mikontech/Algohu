package com.example.algohud.bot

import kotlin.math.abs

/**
 * Pochodzenie i świeżość danych, z których bot korzysta. Każde pole ma źródło, czas odczytu i jednostkę;
 * brak danych to null („brak”), nigdy zero. Moduł tylko czyta i ocenia – niczego nie klika.
 */
object DataQuality {

    enum class Status(val label: String) { OK("✓"), STALE("⏳ nieaktualne"), MISSING("— brak") }

    data class Field(
        val name: String,
        val value: String?,
        /** Np. „XTB ekran”, „feed Binance PAXGUSDT”, „obliczone”. */
        val source: String,
        val readAt: Long?,
        val unit: String,
        val maxAgeMs: Long
    ) {
        fun status(now: Long): Status = when {
            value == null || readAt == null -> Status.MISSING
            now - readAt > maxAgeMs -> Status.STALE
            else -> Status.OK
        }
        fun line(now: Long): String {
            val st = status(now)
            val age = readAt?.let { " (${(now - it) / 1000} s)" } ?: ""
            return "$name: ${value ?: "brak"}${if (value != null && unit.isNotEmpty()) " $unit" else ""} • $source$age ${st.label}"
        }
    }

    /** Wejście bramki: wszystko, co musi się zgadzać, zanim bot otworzy NOWĄ pozycję. */
    data class GateInput(
        val now: Long,
        val expectedInstrument: String,
        /** Instrument widoczny w XTB (null = nie odczytano). */
        val screenInstrument: String?,
        val screenQuoteAt: Long?,
        val screenBid: Double?,
        val screenAsk: Double?,
        val feedAt: Long?,
        val feedPrice: Double?,
        /** Typowa różnica XTB − feed z ostatnich minut (mediana); null = jeszcze nie znana. */
        val typicalBasis: Double?,
        val atr1: Double?,
        val pointValueKnown: Boolean,
        /** true = po przerwie w danych pozycje nie zostały jeszcze uzgodnione z XTB. */
        val needsReconcile: Boolean,
        val realTrading: Boolean
    )

    /** Ostatni pełny raport pól (kopiowany z aplikacji). */
    @Volatile var lastReport: String? = null

    const val SCREEN_MAX_AGE_MS = 8_000L
    const val FEED_MAX_AGE_MS = 20_000L

    /** Null = dane wiarygodne; inaczej konkretna przyczyna blokady nowych wejść. */
    fun entryBlock(g: GateInput): String? {
        val feedAge = g.feedAt?.let { g.now - it }
        if (g.feedPrice == null || feedAge == null) return "brak ceny z feedu – nie widzę rynku"
        if (feedAge > FEED_MAX_AGE_MS) return "cena z feedu nieaktualna (${feedAge / 1000} s)"
        if (!g.pointValueKnown) return "nieznana wartość kontraktu – otwórz bilet ${g.expectedInstrument} w XTB"
        if (g.needsReconcile) return "po przerwie w danych uzgadniam pozycje z XTB – wejścia wstrzymane"
        if (!g.realTrading) return null // papier: wystarczy feed
        val screenAge = g.screenQuoteAt?.let { g.now - it }
        if (screenAge == null || screenAge > SCREEN_MAX_AGE_MS) return "bilet XTB nieodczytany lub nieaktualny (${screenAge?.div(1000) ?: "?"} s)"
        if (g.screenInstrument != g.expectedInstrument) return "w XTB widać ${g.screenInstrument ?: "?"}, a analiza dotyczy ${g.expectedInstrument} – nie handluję"
        val bid = g.screenBid ?: return "brak ceny Sell w XTB"
        val ask = g.screenAsk ?: return "brak ceny Buy w XTB"
        if (ask < bid) return "XTB: Buy < Sell – błędny odczyt"
        val atr = g.atr1
        if (atr != null && atr > 0 && g.typicalBasis != null) {
            val basis = (bid + ask) / 2 - g.feedPrice
            val jump = abs(basis - g.typicalBasis)
            // Nagły skok różnicy XTB–feed = inny instrument, zła jednostka albo zamrożony odczyt.
            if (jump > maxOf(3 * atr, 0.002 * g.feedPrice)) {
                return "ceny XTB i feedu rozjechały się o %.2f (zwykle %.2f) – sprawdzam instrument/jednostki".format(basis, g.typicalBasis)
            }
        }
        return null
    }

    /** Mediana – odporna na pojedyncze błędne odczyty. */
    fun median(values: List<Double>): Double? {
        if (values.isEmpty()) return null
        val s = values.sorted()
        return if (s.size % 2 == 1) s[s.size / 2] else (s[s.size / 2 - 1] + s[s.size / 2]) / 2
    }
}
