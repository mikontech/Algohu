package com.example.algohud.execution

/**
 * Wyjście z ekranu błędu XTB („Coś poszło nie tak – Wystąpił nieoczekiwany błąd… [Odśwież]”).
 *
 * Kolejność (czysta logika, bez Androida – testowalna):
 *  1. błąd widoczny ≥ [FIRST_TAP_MS] → dotknij „Odśwież”,
 *  2. nadal błąd po [TAP_EVERY_MS] → dotknij ponownie (maks. [MAX_TAPS] razy),
 *  3. nadal błąd → RESTART XTB (aplikacja otwierana od nowa, jak ręczne wyłącz/włącz), maks. raz na [RESTART_EVERY_MS],
 *  4. alarm dla użytkownika przy restarcie i potem co [ALERT_EVERY_MS], dopóki błąd trwa,
 *  5. błąd zniknął → [Action.RECOVERED] (raz) i licznik od zera.
 */
class XtbErrorRecovery {

    enum class Action { NONE, TAP_REFRESH, RESTART_APP, ALERT, RECOVERED }

    private var inError = false
    private var errorSince = 0L
    private var taps = 0
    private var lastTap = 0L
    private var lastRestart = 0L
    private var lastAlert = 0L
    private var restarts = 0

    val active: Boolean get() = inError
    val restartCount: Int get() = restarts

    /** Wywoływane co ~1 s. [now] = zegar monotoniczny (ms). */
    fun onScan(errorVisible: Boolean, now: Long): Action {
        if (!errorVisible) {
            if (!inError) return Action.NONE
            inError = false; errorSince = 0L; taps = 0; lastTap = 0L; restarts = 0; lastAlert = 0L
            return Action.RECOVERED
        }
        if (!inError) { inError = true; errorSince = now; return Action.NONE }
        val age = now - errorSince
        if (taps < MAX_TAPS) {
            val due = if (taps == 0) age >= FIRST_TAP_MS else now - lastTap >= TAP_EVERY_MS
            if (due) { taps++; lastTap = now; return Action.TAP_REFRESH }
            return Action.NONE
        }
        if (now - lastTap >= TAP_EVERY_MS && (restarts == 0 || now - lastRestart >= RESTART_EVERY_MS)) {
            lastRestart = now; restarts++; lastAlert = now
            // Po restarcie dajemy XTB znów szansę na „Odśwież”, zanim spróbujemy kolejnego restartu.
            taps = 0; lastTap = now; errorSince = now
            return Action.RESTART_APP
        }
        if (restarts > 0 && now - lastAlert >= ALERT_EVERY_MS) { lastAlert = now; return Action.ALERT }
        return Action.NONE
    }

    companion object {
        const val FIRST_TAP_MS = 2_000L
        const val TAP_EVERY_MS = 6_000L
        const val MAX_TAPS = 2
        const val RESTART_EVERY_MS = 60_000L
        const val ALERT_EVERY_MS = 60_000L

        /** Teksty ekranu błędu XTB (PL i EN). */
        val ERROR_TEXTS = listOf("Coś poszło nie tak", "Wystąpił nieoczekiwany błąd", "Something went wrong", "unexpected error")
        val REFRESH_TEXTS = listOf("Odśwież", "Spróbuj ponownie", "Refresh", "Try again", "Retry")

        fun isErrorText(texts: List<String>): Boolean =
            texts.any { t -> ERROR_TEXTS.any { t.contains(it, ignoreCase = true) } }
    }
}
