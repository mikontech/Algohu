package com.example.algohud.execution

import com.example.algohud.core.Side
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.math.abs

// Czysta logika (bez Androida) przywracania "suwaków" SL/TP w bilecie XTB po potwierdzonym otwarciu pozycji.
// Dostęp do ekranu XTB (odczyt/klikanie) jest w AutoExecutionService – tu tylko decyzje, więc kod jest testowalny.

/** Stan przełączników SL/TP w bilecie XTB. Wartości jako tekst – dokładnie tak, jak stały w polu. */
data class TicketSlTpState(
    val slEnabled: Boolean,
    val slValue: String?,
    val tpEnabled: Boolean,
    val tpValue: String?
)

/** Element ekranu z drzewa ułatwień dostępu (id widoku, tekst, położenie). */
data class UiItem(val id: String?, val text: String, val left: Int, val top: Int, val right: Int, val bottom: Int)

/** Jeden wiersz z sekcji "Otwarte pozycje" w XTB. */
data class PositionRowInfo(
    val side: Side?,
    val volume: Double?,
    /** Tekst ceny otwarcia, np. "@ 4 106.51" – część sygnatury wiersza. */
    val entryText: String?,
    val sl: Double?,
    val tp: Double?
) {
    /** XTB nie pokazuje w aplikacji numeru pozycji – wiersz rozpoznajemy po stronie, wolumenie i cenie otwarcia. */
    val signature: String get() = "$side|$volume|$entryText"
}

object PositionRowParser {
    private val numberRe = Regex("-?\\d[\\d \\u00A0]*(?:[.,]\\d+)?")

    /** "-" albo pusty tekst = brak wartości (np. SL nieustawiony). */
    fun number(text: String): Double? {
        val t = text.trim()
        if (t.isEmpty() || t == "-") return null
        return numberRe.find(t)?.value?.replace(" ", "")?.replace("\u00A0", "")?.replace(',', '.')?.toDoubleOrNull()
    }

    /**
     * Wartość pod etykietą: element o danym id, leżący poniżej etykiety i nachodzący na nią w poziomie.
     * Tak XTB układa pary "Stop Loss" → "4 069.25" w wierszu pozycji.
     */
    private fun valueUnder(items: List<UiItem>, label: String, valueId: String): String? {
        val l = items.firstOrNull { it.id == "ValueTextLabel" && it.text.trim().equals(label, ignoreCase = true) } ?: return null
        return items.filter { it.id == valueId && it.top >= l.bottom - 4 && it.left < l.right && it.right > l.left }
            .minByOrNull { it.top }?.text
    }

    /** @param items elementy leżące wewnątrz prostokąta jednego wiersza PositionRowN. */
    fun parse(items: List<UiItem>): PositionRowInfo {
        val side = items.firstOrNull { it.id == "Badge" }?.text?.trim()?.let {
            when {
                it.equals("Buy", ignoreCase = true) -> Side.BUY
                it.equals("Sell", ignoreCase = true) -> Side.SELL
                else -> null
            }
        }
        return PositionRowInfo(
            side = side,
            volume = valueUnder(items, "Wolumen", "ValueTextText")?.let(::number),
            entryText = valueUnder(items, "Wolumen", "ValueTextSubtitle")?.trim(),
            sl = valueUnder(items, "Stop Loss", "ValueTextText")?.let(::number),
            tp = valueUnder(items, "Take Profit", "ValueTextText")?.let(::number)
        )
    }
}

sealed interface SlTpCheck {
    object Ok : SlTpCheck
    data class Mismatch(val problems: List<String>) : SlTpCheck
}

object SlTpVerifier {
    /** Tolerancja: zaokrąglenie do 0,01 przy wpisywaniu ceny (złoto ma 2 miejsca po przecinku). */
    const val DEFAULT_TOLERANCE = 0.011

    /**
     * Po modyfikacji otwartej pozycji: czy wiersz pozycji pokazuje nowe wartości. null = tej wartości nie zmienialiśmy
     * (nie sprawdzamy jej).
     */
    fun checkModified(row: PositionRowInfo, expectedSl: Double?, expectedTp: Double?, tolerance: Double = DEFAULT_TOLERANCE): SlTpCheck {
        val problems = ArrayList<String>()
        fun one(name: String, shown: Double?, expected: Double?) {
            if (expected == null) return
            when {
                shown == null -> problems += "XTB nie pokazuje $name przy pozycji (oczekiwano %.2f)".format(expected)
                abs(shown - expected) > tolerance -> problems += "XTB pokazuje $name %.2f zamiast %.2f".format(shown, expected)
            }
        }
        one("SL", row.sl, expectedSl)
        one("TP", row.tp, expectedTp)
        return if (problems.isEmpty()) SlTpCheck.Ok else SlTpCheck.Mismatch(problems)
    }

    /** Czy przy tej konkretnej pozycji zapisano oczekiwane SL i TP. expectedTp == null → TP nie może być ustawiony. */
    fun check(row: PositionRowInfo, expectedSl: Double, expectedTp: Double?, tolerance: Double = DEFAULT_TOLERANCE): SlTpCheck {
        val problems = ArrayList<String>()
        val sl = row.sl
        val tp = row.tp
        when {
            sl == null -> problems += "brak SL przy pozycji (oczekiwano %.2f)".format(expectedSl)
            abs(sl - expectedSl) > tolerance -> problems += "SL przy pozycji %.2f zamiast %.2f".format(sl, expectedSl)
        }
        when {
            expectedTp == null && tp != null -> problems += "TP %.2f przy pozycji, choć nie zlecono".format(tp)
            expectedTp != null && tp == null -> problems += "brak TP przy pozycji (oczekiwano %.2f)".format(expectedTp)
            expectedTp != null && tp != null && abs(tp - expectedTp) > tolerance ->
                problems += "TP przy pozycji %.2f zamiast %.2f".format(tp, expectedTp)
        }
        return if (problems.isEmpty()) SlTpCheck.Ok else SlTpCheck.Mismatch(problems)
    }

    /**
     * Wiersze, których nie było przed zleceniem (porównanie multizbiorów sygnatur), o zgodnej stronie i wolumenie.
     * Dzięki temu nie pomylimy nowej pozycji z wcześniej otwartą o tych samych parametrach.
     */
    fun newRows(before: List<PositionRowInfo>, after: List<PositionRowInfo>, side: Side, volume: Double): List<PositionRowInfo> {
        val remaining = before.groupingBy { it.signature }.eachCount().toMutableMap()
        return after.filter { r ->
            val c = remaining[r.signature] ?: 0
            if (c > 0) { remaining[r.signature] = c - 1; false } else true
        }.filter { r ->
            val v = r.volume
            r.side == side && v != null && abs(v - volume) <= 1e-9 + volume * 1e-6
        }
    }
}

/**
 * Koordynuje przywracanie przełączników SL/TP w bilecie:
 *  - stan formularza zapisywany PRZED wejściem ([beginEntry]),
 *  - timer (~5 s) startuje DOPIERO po potwierdzeniu pozycji i jej SL/TP ([onConfirmed]),
 *  - jeden timer na instrument; nowe wejście anuluje oczekujący reset i dziedziczy stan sprzed pierwszego wejścia
 *    serii (szybkie otwieranie kilku pozycji nie "zapamiętuje" wartości wpisanych przez bota),
 *  - reset wykonuje się tylko, jeśli operacja jest nadal najnowsza dla instrumentu, a ekran nie jest zajęty
 *    (edycja pozycji, inna akcja bota) – wtedy jest odkładany, a po [maxDeferrals] próbach porzucany.
 * Nigdy nie dotyka SL/TP zapisanych przy otwartej pozycji – [restore] zmienia tylko formularz biletu.
 */
class SlTpFormCoordinator(
    private val scope: CoroutineScope,
    private val restoreDelayMs: Long = 5_000,
    private val retryDelayMs: Long = 1_000,
    private val maxDeferrals: Int = 15,
    private val baselineTtlMs: Long = 10 * 60_000,
    private val clock: () -> Long = System::currentTimeMillis
) {
    data class Operation(val opId: String, val instrument: String, val baseline: TicketSlTpState)

    /** Pozycja potwierdzona dla danej operacji (osobno SL, TP i identyfikator operacji). */
    data class ConfirmedPosition(val opId: String, val instrument: String, val rowSignature: String, val sl: Double, val tp: Double?, val at: Long)

    sealed interface RestoreOutcome {
        object Done : RestoreOutcome
        /** Ekran zajęty (edycja pozycji, inna akcja, XTB nie na bilecie) – spróbuj później. */
        object Busy : RestoreOutcome
        data class Failed(val reason: String) : RestoreOutcome
    }

    private data class Baseline(val state: TicketSlTpState, val at: Long)

    private val mutex = Mutex()
    private val baselines = HashMap<String, Baseline>()
    private val latestOp = HashMap<String, String>()
    private val timers = HashMap<String, Job>()
    private val confirmed = ArrayDeque<ConfirmedPosition>()

    /** Wywołać przed wpisaniem czegokolwiek do biletu. */
    suspend fun beginEntry(opId: String, instrument: String, current: TicketSlTpState): Operation = mutex.withLock {
        timers.remove(instrument)?.cancel() // nowe wejście przerywa oczekujący reset poprzedniego
        val now = clock()
        val existing = baselines[instrument]?.takeIf { now - it.at <= baselineTtlMs }
        val baseline = existing?.state ?: current
        baselines[instrument] = existing ?: Baseline(current, now)
        latestOp[instrument] = opId
        Operation(opId, instrument, baseline)
    }

    /** Wejście nieudane albo SL/TP niepotwierdzone: bez resetu, formularz zostaje bez zmian. */
    suspend fun onFailed(op: Operation) = mutex.withLock {
        if (latestOp[op.instrument] == op.opId) timers.remove(op.instrument)?.cancel()
    }

    /** Pozycja i jej SL/TP potwierdzone na ekranie XTB → dopiero teraz start timera przywracania. */
    suspend fun onConfirmed(
        op: Operation,
        position: ConfirmedPosition,
        restore: suspend (Operation) -> RestoreOutcome,
        report: (String) -> Unit
    ) {
        mutex.withLock {
            confirmed.addLast(position)
            while (confirmed.size > 50) confirmed.removeFirst()
            if (latestOp[op.instrument] != op.opId) return // jest już nowsza operacja – ona decyduje o resecie
            timers.remove(op.instrument)?.cancel()
            lateinit var job: Job
            job = scope.launch {
                delay(restoreDelayMs)
                var deferrals = 0
                while (true) {
                    val owner = mutex.withLock { latestOp[op.instrument] == op.opId && timers[op.instrument] === job }
                    if (!owner) return@launch
                    when (val r = restore(op)) {
                        RestoreOutcome.Done -> {
                            mutex.withLock {
                                if (timers[op.instrument] === job) {
                                    timers.remove(op.instrument)
                                    baselines.remove(op.instrument) // formularz wrócił do stanu bazowego
                                }
                            }
                            report("↩️ ${op.instrument}: suwaki SL/TP w bilecie przywrócone (pozycja zachowuje swoje SL/TP)")
                            return@launch
                        }
                        RestoreOutcome.Busy -> {
                            if (++deferrals > maxDeferrals) {
                                mutex.withLock { if (timers[op.instrument] === job) timers.remove(op.instrument) }
                                report("⚠️ ${op.instrument}: nie przywrócono suwaków SL/TP – ekran był zajęty")
                                return@launch
                            }
                            delay(retryDelayMs)
                        }
                        is RestoreOutcome.Failed -> {
                            mutex.withLock { if (timers[op.instrument] === job) timers.remove(op.instrument) }
                            report("⚠️ ${op.instrument}: przywracanie suwaków SL/TP nie powiodło się: ${r.reason}")
                            return@launch
                        }
                    }
                }
            }
            timers[op.instrument] = job
        }
    }

    suspend fun hasPendingRestore(instrument: String): Boolean = mutex.withLock { timers[instrument]?.isActive == true }

    suspend fun confirmedPositions(): List<ConfirmedPosition> = mutex.withLock { confirmed.toList() }
}
