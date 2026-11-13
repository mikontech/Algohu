package com.example.algohud.council

import android.content.SharedPreferences
import com.example.algohud.bot.Ntfy
import com.example.algohud.core.Bar
import com.example.algohud.execution.AutoExecutionService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withTimeout
import java.util.Locale
import kotlin.math.abs

/**
 * Rada 4 agentów AI – bramka PRZED wejściem w pozycję (nie dotyczy wyjść, SL-schodków ani kill-switcha).
 *
 * Przebieg (2 rundy, żeby trzymać opóźnienie ~10–20 s):
 *   R0  Obserwator (wizja/tekst) – czy ekran XTB zgadza się z danymi bota
 *   R1  Strateg ‖ Sceptyk – równolegle, niezależne opinie
 *   R2  Strateg odpowiada na zarzuty ‖ Sceptyk wydaje werdykt po argumentach Stratega
 *   R3  Dowódca – synteza w ustrukturyzowane zlecenie
 *   ⟶  RiskGate (Kotlin, bez AI) – ostateczna weryfikacja; dopiero potem engine.submit()
 *
 * Bezpieczeństwo: tryb GATE przy błędzie/timeoucie API = WETO (fail-closed). Kill-switch przerywa debatę.
 */
class AgentCouncil(
    private val prefs: SharedPreferences,
    private val client: ClaudeClient = ClaudeClient({ prefs.getString(PREF_KEY, null) }),
    private val log: (String) -> Unit,
    private val isKilled: () -> Boolean,
    /** Aktualna cena bota (po debacie) – do kontroli poślizgu. */
    private val priceNow: suspend (String) -> Double?
) {
    val mode: CouncilMode
        get() = runCatching { CouncilMode.valueOf(prefs.getString(PREF_MODE, CouncilMode.ADVISORY.name)!!) }.getOrDefault(CouncilMode.ADVISORY)

    fun setMode(m: CouncilMode) = prefs.edit().putString(PREF_MODE, m.name).apply()
    fun hasKey() = !prefs.getString(PREF_KEY, null).isNullOrBlank()

    private val fastModel get() = prefs.getString(PREF_FAST_MODEL, null) ?: DEFAULT_FAST_MODEL
    private val deepModel get() = prefs.getString(PREF_DEEP_MODEL, null) ?: DEFAULT_DEEP_MODEL

    private val _transcript = MutableStateFlow<List<String>>(emptyList())
    /** Ostatnia debata linia po linii – do rozwijanego okna w HUD (krok 3). */
    val transcript: StateFlow<List<String>> = _transcript.asStateFlow()

    @Volatile private var running: Job? = null

    private val _status = MutableStateFlow("🧠 Rada: czeka na kandydata")
    /** Jedna linia do HUD: tryb rady + ostatni werdykt. */
    val status: StateFlow<String> = _status.asStateFlow()
    /** true = trwa debata (orkiestrator nie startuje drugiej dla tego samego instrumentu). */
    val busy: Boolean get() = running?.isActive == true

    /** Wywoływane z kill-switcha HUD: natychmiast przerywa trwającą debatę. */
    fun cancelAll() { running?.cancel(CancellationException("KILL-SWITCH")) }

    /**
     * Zwraca decyzję rady. W trybie ADVISORY zawsze [CouncilDecision.Approve] z kandydatem bez zmian
     * (werdykt rady trafia tylko do logu), w GATE – to, co przepuści [RiskGate].
     */
    suspend fun review(ctx: CouncilContext): CouncilDecision {
        val m = mode
        if (m == CouncilMode.OFF) return CouncilDecision.Approve(ctx.order, emptyList())
        if (!hasKey()) {
            val why = "brak klucza API rady AI"
            note(if (m == CouncilMode.GATE) "⛔ Rada AI: $why – wejście zablokowane" else "🧠 Rada AI: $why – pomijam debatę")
            return if (m == CouncilMode.GATE) CouncilDecision.Veto(why, listOf(why))
                else CouncilDecision.Approve(ctx.order, emptyList())
        }
        running?.cancel()
        val lines = mutableListOf<String>()
        fun say(s: String) { lines += s; _transcript.value = lines.toList(); log(s) }

        // Debata w osobnym zadaniu – kill-switch anuluje tylko ją, nie pętlę bota.
        val result = runCatching {
            coroutineScope {
                val d = async { withTimeout(TOTAL_TIMEOUT_MS) { debate(ctx, ::say) } }
                running = d
                d.await()
            }
        }
        result.exceptionOrNull()?.let { if (it is CancellationException && !currentCoroutineContext().isActive) throw it }
        val decision: CouncilDecision = result.fold(
            onSuccess = { it },
            onFailure = { e ->
                if (e is CancellationException && isKilled()) CouncilDecision.Veto("przerwane kill-switchem", lines)
                else {
                    val why = "rada AI niedostępna: ${e.message?.take(90) ?: e.javaClass.simpleName}"
                    say("⚠️ $why")
                    Ntfy.send("🧠 ${ctx.instrument}: $why", if (m == CouncilMode.GATE) "wejście zablokowane (fail-closed)" else "tryb doradczy – bot działa dalej",
                        Ntfy.HIGH, "warning", key = "council-err", minGapMs = 30 * 60_000L)
                    CouncilDecision.Veto(why, lines)
                }
            }
        )
        running = null

        val side = RiskGate.sideName(ctx.order.side)
        when (decision) {
            is CouncilDecision.Approve -> {
                say("✅ RADA: $side zatwierdzony (lot %s)".format(decision.order.quantity))
                _status.value = "🧠 Rada [${m.name}] ${hhmm()}: ✅ $side ${ctx.instrument}"
                Ntfy.send("🧠 ${ctx.instrument}: rada ZA – $side", lines.takeLast(3).joinToString("\n"), Ntfy.QUIET, "brain",
                    key = "council:${ctx.instrument}", minGapMs = 60_000L)
            }
            is CouncilDecision.Veto -> {
                say("⛔ RADA: $side odrzucony – ${decision.reason}")
                _status.value = "🧠 Rada [${m.name}] ${hhmm()}: ⛔ $side – ${decision.reason.take(70)}" +
                    if (m == CouncilMode.ADVISORY) " (doradczo)" else ""
                Ntfy.send("🧠 ${ctx.instrument}: rada PRZECIW – $side", decision.reason + if (m == CouncilMode.ADVISORY) "\n(tryb doradczy – bot i tak wchodzi)" else "",
                    Ntfy.QUIET, "no_entry_sign", key = "council:${ctx.instrument}", minGapMs = 60_000L)
            }
        }
        return if (m == CouncilMode.ADVISORY) CouncilDecision.Approve(ctx.order, decision.log) else decision
    }

    private suspend fun debate(ctx: CouncilContext, say: (String) -> Unit): CouncilDecision = coroutineScope {
        val o = ctx.order
        val side = RiskGate.sideName(o.side)
        say("🧠 Rada AI: kandydat ${ctx.strategy} $side ${ctx.instrument} – debata…")
        val facts = facts(ctx)
        guard()

        // ── R0: Obserwator ──
        val img = AutoExecutionService.instance?.withHudHidden { ScreenGrabber.jpegBase64() }
        val tree = if (img == null) AutoExecutionService.instance?.dumpTargetWindow()?.take(6_000) else null
        val obsText = buildString {
            appendLine(facts)
            if (img == null) {
                appendLine("ZRZUT EKRANU NIEDOSTĘPNY (${ScreenGrabber.lastError ?: "?"}). Tekst drzewa widoków XTB:")
                appendLine(tree ?: "(brak – XTB nie jest widoczne)")
            } else appendLine("Zrzut ekranu XTB w załączniku (HUD bota może zasłaniać fragment).")
        }
        val observer = CouncilParsing.observer(client.askJson(fastModel, CouncilPrompts.OBSERVER, obsText, img, 500))
        say("👁️ Obserwator: ${observer.instrument ?: "?"} bid ${observer.bid ?: "?"} ask ${observer.ask ?: "?"}" +
            (if (observer.screenOk) " ✓" else " ⚠️ ${observer.warnings.firstOrNull() ?: "ekran niepewny"}"))
        if (!observer.screenOk) return@coroutineScope CouncilDecision.Veto("obserwator: ${observer.warnings.firstOrNull() ?: "ekran niepewny"}", emptyList())
        guard()

        val base = facts + "\nRAPORT OBSERWATORA: " + observer.raw

        // ── R1: równolegle ──
        val s1 = async { CouncilParsing.strategist(client.askJson(deepModel, CouncilPrompts.STRATEGIST, "$base\nRUNDA 1.")) }
        val k1 = async { CouncilParsing.skeptic(client.askJson(deepModel, CouncilPrompts.SKEPTIC, "$base\nRUNDA 1.")) }
        val strat1 = s1.await(); val skep1 = k1.await()
        say("🌍 Strateg: ${strat1.stance} (%.0f%%, %s, pułapka: %s) – %s".format(strat1.confidence * 100, strat1.regime, strat1.trapRisk, strat1.arguments.firstOrNull() ?: ""))
        say("🛡️ Sceptyk: ${skep1.verdict} – ${skep1.reasons.firstOrNull() ?: ""}")
        guard()

        // ── R2: wymiana argumentów ──
        val s2 = async {
            CouncilParsing.strategist(client.askJson(deepModel, CouncilPrompts.STRATEGIST,
                "$base\nRUNDA 2. Twoja opinia z rundy 1: ${strat1.raw}\nZarzuty Sceptyka: ${skep1.raw}"))
        }
        val k2 = async {
            CouncilParsing.skeptic(client.askJson(deepModel, CouncilPrompts.SKEPTIC,
                "$base\nRUNDA 2 (werdykt końcowy). Twoja opinia z rundy 1: ${skep1.raw}\nArgumenty Stratega: ${strat1.raw}"))
        }
        val strat2 = s2.await(); val skep2 = k2.await()
        say("🌍 Strateg (R2): ${strat2.stance} – ${strat2.arguments.lastOrNull() ?: ""}")
        say("🛡️ Sceptyk (R2): ${skep2.verdict}" + (if (skep2.verdict == SkepticVerdict.ADJUST) " ×%.2f lota".format(skep2.volumeFactor) else "") +
            " – ${skep2.reasons.firstOrNull() ?: ""}")
        guard()

        if (skep2.verdict == SkepticVerdict.VETO)
            return@coroutineScope CouncilDecision.Veto("WETO Sceptyka: ${skep2.reasons.firstOrNull() ?: "bez powodu"}", emptyList())

        // ── R3: Dowódca ──
        val cmdText = "$base\nKANDYDAT DO PRZEPISANIA: side=${o.side} volume=${o.quantity} sl=${us(o.stopPrice)} " +
            "tp=${o.takeProfit?.let { us(it) } ?: "null"}\nSTRATEG: ${strat2.raw}\nSCEPTYK: ${skep2.raw}"
        val cmd = CouncilParsing.commander(client.askJson(fastModel, CouncilPrompts.COMMANDER, cmdText, maxTokens = 300))
        say("🎖️ Dowódca: ${cmd.summaryPl}")
        guard()

        val now = System.currentTimeMillis()
        val px = priceNow(ctx.instrument) ?: ctx.priceNow
        RiskGate.check(RiskGate.Inputs(o, observer, skep2, cmd, px, ctx.xtbShift, ctx.instrument, ctx.createdAt, now, ctx.untrackedXtbRows)).fold(
            onSuccess = { CouncilDecision.Approve(it, emptyList()) },
            onFailure = { CouncilDecision.Veto("bramka: ${it.message}", emptyList()) }
        )
    }

    private fun hhmm() = java.time.LocalTime.now().let { "%02d:%02d".format(it.hour, it.minute) }

    private fun guard() { if (isKilled()) throw CancellationException("KILL-SWITCH") }

    private fun note(s: String) { log(s); _status.value = s }

    /** Liczby z kropką (Locale.US) – polski przecinek psułby JSON i przepisywanie SL/TP przez Dowódcę. */
    private fun us(v: Double, d: Int = 2) = String.format(Locale.US, "%.${d}f", v)

    /** Fakty dla agentów: kompaktowo, żeby nie przepalać tokenów. Ceny w skali bota + przesunięcie XTB. */
    private fun facts(c: CouncilContext): String {
        val o = c.order
        val sl = abs(o.referencePrice - o.stopPrice)
        val rr = o.takeProfit?.let { abs(it - o.referencePrice) / sl }
        val lossPln = sl * o.quantity * o.pointValue
        fun bars(b: List<Bar>, n: Int) = b.takeLast(n).joinToString(";") { "${us(it.open)},${us(it.high)},${us(it.low)},${us(it.close)}" }
        return buildString {
            appendLine("INSTRUMENT: ${c.instrument}  STRATEGIA: ${c.strategy}")
            appendLine("KANDYDAT: ${RiskGate.sideName(o.side)} side=${o.side} lot=${o.quantity} E=${us(o.referencePrice)} SL=${us(o.stopPrice)} " +
                "TP=${o.takeProfit?.let { us(it) } ?: "trailing"} R:R=${rr?.let { "1:" + us(it) } ?: "?"} strata_na_SL≈${us(lossPln)} zł")
            appendLine("POWÓD BOTA: ${o.reason}")
            appendLine("CENA TERAZ: ${us(c.priceNow)}  SPREAD: ${us(c.spread)}  PRZESUNIĘCIE XTB: ${us(c.xtbShift)} (cena w XTB = cena bota + przesunięcie)")
            appendLine("WOLNE ŚRODKI: ${c.freeFunds?.let { us(it) + " zł" } ?: "?"}  WYNIK DNIA: ${us(c.dailyPnl)} zł  SENTYMENT NEWSÓW: ${us(c.newsSentiment)}")
            if (c.botNotes.isNotEmpty()) appendLine("NOTATKI BOTA: " + c.botNotes.joinToString(" | "))
            appendLine("M5 ostatnie 24 (O,H,L,C): ${bars(c.m5, 24)}")
            appendLine("M1 ostatnie 30 (O,H,L,C): ${bars(c.m1, 30)}")
            if (c.awareness.isNotBlank()) { appendLine("STAN APLIKACJI I EKRANU:"); appendLine(c.awareness) }
        }
    }

    companion object {
        const val PREF_KEY = "council_api_key"
        const val PREF_MODE = "council_mode"
        const val PREF_FAST_MODEL = "council_fast_model"
        const val PREF_DEEP_MODEL = "council_deep_model"
        const val DEFAULT_FAST_MODEL = "claude-haiku-5-5"   // Obserwator + Dowódca: szybko i tanio
        const val DEFAULT_DEEP_MODEL = "claude-sonnet-5-5"  // Strateg + Sceptyk: rozumowanie
        const val TOTAL_TIMEOUT_MS = 40_000L
    }
}
