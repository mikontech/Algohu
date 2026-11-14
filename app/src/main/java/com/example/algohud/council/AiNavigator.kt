package com.example.algohud.council

import com.example.algohud.execution.AutoExecutionService
import kotlinx.coroutines.async
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Nawigator wizyjny – zapas, gdy zwykłe szukanie biletu w XTB zawiedzie (np. zmieniony układ ekranu).
 * Pętla max [MAX_STEPS]: zrzut ekranu → model mówi „dotknij x,y / wstecz / przewiń / gotowe” → wykonanie → sprawdzenie biletu.
 * Bezpieczeństwo: dotknięcia przechodzą przez [AutoExecutionService.navTapNormalized], który odmawia poza XTB
 * i w miejscach przycisków zleceń (Sell/Buy/Potwierdź/Zamknij…). Kill-switch przerywa przed każdym krokiem.
 * Wołany wyłącznie z findWantedInstrument (pod uiMutex) – nigdy równolegle z wykonaniem zlecenia.
 */
class AiNavigator(
    private val client: ClaudeClient,
    private val model: () -> String,
    private val hasKey: () -> Boolean,
    private val log: (String) -> Unit
) {
    @Volatile private var job: kotlinx.coroutines.Job? = null

    /** Kill-switch: natychmiast przerywa nawigację (także zapytanie HTTP w locie) i zwalnia blokadę UI. */
    fun cancel() { job?.cancel() }

    suspend fun open(target: String): Boolean {
        if (!hasKey()) return false
        val svc = AutoExecutionService.instance ?: return false
        log("👁️ Nawigator AI: szukam biletu $target na ekranie…")
        return try {
            kotlinx.coroutines.coroutineScope {
                val d = async { run(svc, target) }
                job = d
                d.await()
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            if (!kotlinx.coroutines.currentCoroutineContext().isActive) throw e
            log("👁️ Nawigator AI: przerwany"); false
        } finally { job = null }
    }

    private suspend fun run(svc: AutoExecutionService, target: String): Boolean {
        val selectedModel = model()
        return withTimeoutOrNull(TOTAL_MS) {
            val history = mutableListOf<String>()
            repeat(MAX_STEPS) { step ->
                if (AutoExecutionService.navAbort()) { log("👁️ Nawigator AI: przerwany (kill-switch)"); return@withTimeoutOrNull false }
                if (svc.navIsOnTarget(target)) return@withTimeoutOrNull true
                val img = svc.withHudHidden(lockHeld = true) { ScreenGrabber.jpegBase64(maxWidth = 640) }
                val text = buildString {
                    appendLine("CEL: otwórz bilet instrumentu $target (${SUBTITLE[target] ?: target}) – ekran z przyciskami Sell i Buy.")
                    appendLine("KROK ${step + 1}/$MAX_STEPS. Dotychczas: ${history.joinToString(" → ").ifBlank { "nic" }}")
                    if (img == null) appendLine("ZRZUT NIEDOSTĘPNY (${ScreenGrabber.lastError}). Teksty na ekranie: ${svc.navScreenText()}")
                }
                val j = runCatching { client.askJson(selectedModel, PROMPT, text, img, 250) }.getOrElse {
                    log("👁️ Nawigator AI: błąd API – ${it.message?.take(60)}"); return@withTimeoutOrNull false
                }
                val action = j.optString("action")
                val why = j.optString("why").take(80)
                when (action) {
                    "tap" -> {
                        val x = j.optDouble("x", -1.0).toFloat(); val y = j.optDouble("y", -1.0).toFloat()
                        val refused = svc.navTapNormalized(x, y)
                        history += if (refused == null) "dotyk(%.2f,%.2f) $why".format(x, y) else "ODMOWA: $refused"
                        log("👁️ Krok ${step + 1}: " + (refused?.let { "odmowa – $it" } ?: "dotykam – $why"))
                    }
                    "back" -> { svc.navBack(); history += "wstecz"; log("👁️ Krok ${step + 1}: wstecz – $why") }
                    "scroll_down", "scroll_up" -> { svc.navScroll(action == "scroll_down"); history += action; log("👁️ Krok ${step + 1}: przewijam – $why") }
                    "done" -> return@withTimeoutOrNull svc.navIsOnTarget(target)
                    else -> { log("👁️ Nawigator AI: poddaję się – $why"); return@withTimeoutOrNull false }
                }
                kotlinx.coroutines.delay(1_200)
            }
            svc.navIsOnTarget(target)
        } ?: false
    }

    companion object {
        const val MAX_STEPS = 5
        const val TOTAL_MS = 45_000L
        private val SUBTITLE = mapOf("GOLD" to "Troy Ounce of Gold (CFD), żółta ikona „Au”")
        private const val PROMPT = """
Jesteś nawigatorem w aplikacji XTB na Androidzie. Na zrzucie ekranu (lub w tekstach) znajdź drogę do biletu instrumentu.
Wskazówki dla XTB: GOLD jest zwykle pierwszy na liście Rynki → CFD, drugi w Popularnych, i pierwszy w wynikach wyszukiwania
(lupa u góry; wiersz „Troy Ounce of Gold (CFD)”, żółta ikona „Au”). Pierwszy kafelek „Ostatnio wyszukiwane” też często jest GOLD.
Zasady:
- NIGDY nie wskazuj przycisków Sell/Buy/Kup/Sprzedaj, Potwierdź, Zamknij, Modyfikuj, Wpłać/Wypłać.
- Wskazuj środek elementu. Współrzędne x,y jako ułamek szerokości/wysokości CAŁEGO ekranu (0..1).
- Jeśli bilet celu jest już otwarty (tytuł instrumentu + przyciski Sell i Buy) → "done".
- Jeśli to okno dialogowe/reklama – zamknij je ("back" albo dotknij krzyżyka).
Odpowiedz tylko JSON:
{"action": "tap"|"back"|"scroll_down"|"scroll_up"|"done"|"give_up", "x": number|null, "y": number|null, "why": string}
"""
    }
}
