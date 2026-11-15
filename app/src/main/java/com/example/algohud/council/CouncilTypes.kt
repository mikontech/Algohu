package com.example.algohud.council

import com.example.algohud.core.Bar
import com.example.algohud.core.OrderRequest
import com.example.algohud.core.Side

/**
 * OFF – rada nie działa.
 * ADVISORY – rada debatuje i loguje werdykt, ale NIE blokuje wejść (domyślnie: najpierw porównujemy z botem).
 * GATE – rada może zawetować wejście albo zmniejszyć wolumen. Nigdy nie poszerza ryzyka.
 */
enum class CouncilMode { OFF, ADVISORY, GATE }

/** Wszystko, co rada dostaje o kandydacie na wejście. Ceny w skali bota (bez przesunięcia XTB). */
data class CouncilContext(
    val instrument: String,
    val order: OrderRequest,
    /** Która strategia wygenerowała kandydata (SMC / RT / ORB / ZAKRES / TURNIEJ / US OPEN). */
    val strategy: String,
    val priceNow: Double,
    val spread: Double,
    val m1: List<Bar>,
    val m5: List<Bar>,
    val newsSentiment: Double,
    val freeFunds: Double?,
    val dailyPnl: Double,
    /** Krótkie notatki bota: kompas trendu, sesja, blokady makro itp. */
    val botNotes: List<String> = emptyList(),
    /** Przesunięcie ceny XTB względem feedu bota (cena XTB = cena bota + shift). */
    val xtbShift: Double = 0.0,
    /** Pełny stan aplikacji (omni-awareness): pozycje, saldo, okna, usługi, nawigacja, ostatnie zdarzenia bota. */
    val awareness: String = "",
    /** Wiersze otwartych pozycji widoczne na bilecie XTB tego instrumentu, których bot NIE ma w księdze. */
    val untrackedXtbRows: Int = 0,
    val createdAt: Long = System.currentTimeMillis(),
    /** Odczyt świec zamkniętych i bieżących z tego samego momentu. */
    val candleReport: String = ""
)

data class ObserverReport(
    val instrument: String?,
    val bid: Double?,
    val ask: Double?,
    val screenOk: Boolean,
    val openPositions: List<String>,
    val priceAction: String,
    val warnings: List<String>,
    val raw: String
)

enum class Stance { ZA, PRZECIW, NEUTRALNIE }

data class StrategistView(
    val stance: Stance,
    val confidence: Double,
    val regime: String,
    val trapRisk: String,
    val arguments: List<String>,
    val raw: String
)

enum class SkepticVerdict { APPROVE, ADJUST, VETO }

data class SkepticView(
    val verdict: SkepticVerdict,
    /** 0.5–1.0: o ile zmniejszyć wolumen. 1.0 = bez zmian. */
    val volumeFactor: Double,
    val reasons: List<String>,
    val raw: String
)

data class CommanderOrder(
    val execute: Boolean,
    val side: Side?,
    val volume: Double?,
    val sl: Double?,
    val tp: Double?,
    val summaryPl: String,
    val raw: String
)

sealed interface CouncilDecision {
    val log: List<String>
    /** Wejście zatwierdzone; [order] może mieć tylko MNIEJSZY wolumen niż kandydat. */
    data class Approve(val order: OrderRequest, override val log: List<String>) : CouncilDecision
    data class Veto(val reason: String, override val log: List<String>) : CouncilDecision
}
