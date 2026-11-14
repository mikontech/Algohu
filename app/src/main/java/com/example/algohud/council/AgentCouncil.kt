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
 * Przebieg oszczędny: najwyżej 4 wywołania; 10 minut przerwy, 12 debat/dobę UTC.
 *   R0  Obserwator (wizja/tekst) – czy ekran XTB zgadza się z danymi bota
 *   R1  Strateg → Sceptyk wydaje werdykt po argumentach Stratega
 *   R3  Dowódca – synteza w ustrukturyzowane zlecenie
 *   ⟶  RiskGate (Kotlin, bez AI) – ostateczna weryfikacja; dopiero potem engine.submit()
 *
 * Bezpieczeństwo: tryb GATE przy błędzie/timeoucie API = WETO (fail-closed). Kill-switch przerywa debatę.
 */
class AgentCouncil(
    private val prefs: SharedPreferences,
    private val client: ClaudeClient = ClaudeClient.configured(prefs),
    private val log: (String) -> Unit,
    private val isKilled: () -> Boolean,
    /** Aktualna cena bota (po debacie) – do kontroli poślizgu. */
    private val priceNow: suspend (String) -> Double?
) {
    val mode: CouncilMode
        get() = runCatching { CouncilMode.valueOf(prefs.getString(PREF_MODE, CouncilMode.ADVISORY.name)!!) }.getOrDefault(CouncilMode.ADVISORY)

    fun setMode(m: CouncilMode) = prefs.edit().putString(PREF_MODE, m.name).apply()
    fun hasKey() = AiSettings.hasKeys(prefs)
    private val limits = AiLimits(prefs)
    private val reviewMutex = kotlinx.coroutines.sync.Mutex()


    private val _transcript = MutableStateFlow<List<String>>(emptyList())
    /** Ostatnia debata linia po linii – do rozwijanego okna w HUD (krok 3). */
    val transcript: StateFlow<List<String>> = _transcript.asStateFlow()

    @Volatile private var running: Job? = null

    private val _status = MutableStateFlow("🧠 Rada: czeka na kandydata")
    /** Jedna linia do HUD: tryb rady + ostatni werdykt. */
    val status: StateFlow<String> = _status.asStateFlow()
    /** true = trwa debata (orkiestrator nie startuje drugiej dla tego samego instrumentu). */
    val busy: Boolean get() = running?.isActive == true

    private data class Observation(val at: Long, val text: String)
    private val observations = java.util.concurrent.ConcurrentHashMap<String, Observation>()
    private val observationNotes = java.util.concurrent.ConcurrentHashMap<String, String>()
    private var observationAttempt = 0L

    fun observationLine(instrument: String, now: Long): String {
        if (mode == CouncilMode.OFF) return "🧠 Analiza niezależna: rada OFF"
        val item = observations[instrument]
        val note = observationNotes[instrument]
        return if (item == null) "🧠 Analiza niezależna: ${note ?: "czekam na świece M1/M5"}"
        else "🧠 Scenariusz AI ${hhmmAt(item.at)} (ceny feedu, bez zlecenia" +
            (if (now - item.at > 120_000) ", nieaktualizowany od ${(now - item.at) / 60_000} min" else "") +
            "): ${item.text}" + (note?.let { "\n   $it" } ?: "")
    }
    private fun hhmmAt(at: Long) = java.text.SimpleDateFormat("HH:mm", Locale.getDefault()).format(java.util.Date(at))

    /** Jeden odczyt analityczny niezależny od sygnału strategii; nigdy nie tworzy zlecenia. */
    suspend fun observeCandles(instrument: String, m1: List<Bar>, m5: List<Bar>, price: Double, priceAt: Long, now: Long) {
        if (mode == CouncilMode.OFF || isKilled() || !reviewMutex.tryLock()) return
        try {
            if (now - observationAttempt < 10 * 60_000L) return
            if (now - priceAt !in 0..20_000 || !price.isFinite() || price <= 0 || m1.size < 30 || m5.size < 12 ||
                !CandleAdvice.validBars(m1.takeLast(30), 60_000, now) || !CandleAdvice.validBars(m5.takeLast(12), 300_000, now)) {
                observationNotes[instrument] = "czekam na świeże, ciągłe świece: 30 M1 i 12 M5"
                return
            }
            val model = AiSettings.plan(prefs).deep
            if (AiSettings.key(prefs, AiSettings.provider(model)).isNullOrBlank()) {
                observationNotes[instrument] = "brak klucza ${AiSettings.provider(model)}"; return
            }
            observationAttempt = now
            limits.reserveObservation()?.let { observationNotes[instrument] = it; return }
            observationNotes[instrument] = "analizuję świece ($model)…"
            fun candles(bars: List<Bar>) = bars.joinToString("\n") { "${it.start},${it.open},${it.high},${it.low},${it.close}" }
            try {
                val answer = coroutineScope {
                    val task = async { withTimeout(TOTAL_TIMEOUT_MS) {
                        client.askJson(model,
                            "Jesteś analitykiem świec M1/M5. Tworzysz wyłącznie hipotetyczny scenariusz, bez zlecenia. " +
                            "Nie wymagasz sygnału strategii bota ani wybicia 10 świec. Nie wymuszaj transakcji. " +
                            "Wszystkie poziomy w skali dostarczonego feedu, nie XTB. LONG/SHORT to faktyczny kierunek opinii, " +
                            "nie podlega odwracaniu wejść bota. Oceniaj tylko podane zamknięte świece, nie dopisuj danych. " +
                            "Zwróć JSON: side LONG/SHORT/WAIT, entry liczba lub null, invalidation liczba lub null, " +
                            "condition konkretny warunek wejścia po polsku (np. zamknięcie M1 ponad poziomem i retest), " +
                            "reason krótkie uzasadnienie po polsku. WAIT też musi mieć warunek ponownej oceny.",
                            "Instrument: $instrument; czas ms: $now; cena feedu: $price. Kolumny: czas,open,high,low,close. " +
                                "M1:\n${candles(m1.takeLast(30))}\nM5:\n${candles(m5.takeLast(12))}", maxTokens = 500)
                    } }
                    running = task
                    task.await()
                }
                if (mode != CouncilMode.OFF && !isKilled()) {
                    observations[instrument] = Observation(now, CandleAdvice.parse(answer).display())
                    observationNotes.remove(instrument)
                }
            } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
                observationNotes[instrument] = "analiza przekroczyła 40 s; brak nowej opinii"
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { observationNotes[instrument] = "brak nowej opinii: ${e.message?.take(140)}" }
            finally { running = null }
        } finally { reviewMutex.unlock() }
    }

    /** Wywoływane z kill-switcha HUD: natychmiast przerywa trwającą debatę. */
    fun cancelAll() { running?.cancel(CancellationException("KILL-SWITCH")) }

    /**
     * Zwraca decyzję rady. W trybie ADVISORY zawsze [CouncilDecision.Approve] z kandydatem bez zmian
     * (werdykt rady trafia tylko do logu), w GATE – to, co przepuści [RiskGate].
     */
    suspend fun review(ctx: CouncilContext): CouncilDecision {
        if (!reviewMutex.tryLock()) return unavailable(ctx, mode, "rada już pracuje")
        return try { reviewLocked(ctx) } finally { running = null; reviewMutex.unlock() }
    }

    private fun unavailable(ctx: CouncilContext, m: CouncilMode, why: String): CouncilDecision {
        note("🧠 $why" + if (m == CouncilMode.GATE) " – wejście zablokowane" else " – pomijam opinię")
        return if (m == CouncilMode.GATE) CouncilDecision.Veto(why, listOf(why))
            else CouncilDecision.Approve(ctx.order, listOf(why))
    }

    private suspend fun reviewLocked(ctx: CouncilContext): CouncilDecision {
        val m = mode
        val plan = AiSettings.plan(prefs)
        if (m == CouncilMode.OFF) return CouncilDecision.Approve(ctx.order, emptyList())
        if (!AiSettings.hasKeys(prefs, plan)) {
            val why = "brak klucza API rady AI"
            note(if (m == CouncilMode.GATE) "⛔ Rada AI: $why – wejście zablokowane" else "🧠 Rada AI: $why – pomijam debatę")
            return if (m == CouncilMode.GATE) CouncilDecision.Veto(why, listOf(why))
                else CouncilDecision.Approve(ctx.order, emptyList())
        }
        limits.reserveDebate()?.let { return unavailable(ctx, m, it) }
        val lines = mutableListOf<String>()
        fun say(s: String) { lines += s; _transcript.value = lines.toList(); log(s) }

        // Debata w osobnym zadaniu – kill-switch anuluje tylko ją, nie pętlę bota.
        val result = runCatching {
            coroutineScope {
                val d = async { withTimeout(TOTAL_TIMEOUT_MS) { debate(ctx, plan, ::say) } }
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
                    val why = if (e is kotlinx.coroutines.TimeoutCancellationException) "rada AI: przekroczony czas całej debaty (40 s)"
                        else "rada AI niedostępna: ${e.message?.take(140) ?: e.javaClass.simpleName}"
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

    private suspend fun debate(ctx: CouncilContext, plan: AiPlan, say: (String) -> Unit): CouncilDecision = coroutineScope {
        val fastModel = plan.fast
        val deepModel = plan.deep
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

        // Tryb oszczędny: Strateg → Sceptyk (widzi argumenty Stratega), bez drugiej rundy.
        val strat2 = CouncilParsing.strategist(client.askJson(deepModel, CouncilPrompts.STRATEGIST, "$base\nJEDNA RUNDA: oceń kandydata."))
        say("🌍 Strateg: ${strat2.stance} – ${strat2.arguments.firstOrNull() ?: ""}")
        guard()
        val skep2 = CouncilParsing.skeptic(client.askJson(deepModel, CouncilPrompts.SKEPTIC,
            "$base\nWERDYKT KOŃCOWY. Argumenty Stratega: ${strat2.raw}"))
        say("🛡️ Sceptyk: ${skep2.verdict} – ${skep2.reasons.firstOrNull() ?: ""}")
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
            if (c.candleReport.isNotBlank()) appendLine(c.candleReport) else {
                appendLine("M5 ostatnie 24 ZAMKNIĘTE (O,H,L,C): ${bars(c.m5, 24)}")
                appendLine("M1 ostatnie 30 ZAMKNIĘTE (O,H,L,C): ${bars(c.m1, 30)}")
                appendLine("Brak szczegółowego raportu świec i świecy w budowie.")
            }
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
