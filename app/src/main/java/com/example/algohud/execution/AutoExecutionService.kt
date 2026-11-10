package com.example.algohud.execution

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.example.algohud.core.ExecutionMode
import com.example.algohud.core.ExecutionResult
import com.example.algohud.core.OrderRequest
import com.example.algohud.core.ScreenQuote
import com.example.algohud.core.Side
import com.example.algohud.risk.RiskManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.math.BigDecimal
import java.math.RoundingMode
import java.net.URLEncoder
import java.util.concurrent.TimeoutException
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.coroutines.resume
import kotlin.math.abs

// ═════════════════════════ Kontrakt wykonawcy ═════════════════════════

interface OrderExecutor {
    suspend fun submit(order: OrderRequest): ExecutionResult
    suspend fun cancelAll()
    /** null = gotowy do prawdziwych zleceń, w przeciwnym razie powód blokady. */
    fun readiness(): String? = null
    /** Dodatkowy warunek dla trybu AUTO. */
    fun autoReadiness(): String? = readiness()
}

private fun formatQty(q: Double): String = BigDecimal.valueOf(q).stripTrailingZeros().toPlainString()

// ═════════════════════════ Silnik wykonawczy ═════════════════════════

/**
 * Jedyne miejsce, które wysyła zlecenia. Kolejność: RiskManager → tryb → wykonawca → księgowanie.
 * Każdy stan nieznany (timeout, błąd po kliknięciu) = natychmiastowy KILL-SWITCH.
 */
class ExecutionEngine(
    private val risk: RiskManager,
    private val executor: OrderExecutor,
    initialMode: ExecutionMode = ExecutionMode.DRY_RUN,
    private val executorTimeoutMs: Long = 20_000,
    /** Dodatkowa bramka AUTO (np. wyniki papierowe); null = OK. */
    private val autoGate: () -> String? = { null }
) {
    private var pendingAt = 0L
    private val _mode = MutableStateFlow(initialMode)
    val mode: StateFlow<ExecutionMode> = _mode.asStateFlow()

    private val _events = MutableSharedFlow<String>(extraBufferCapacity = 64)
    val events: SharedFlow<String> = _events.asSharedFlow()

    private val _pending = MutableStateFlow<OrderRequest?>(null)
    val pendingConfirmation: StateFlow<OrderRequest?> = _pending.asStateFlow()

    private val execMutex = Mutex()
    private val flattenMutex = Mutex()

    /**
     * Zmienia tryb. CONFIRM i AUTO wymagają gotowego wykonawcy (API z kluczem albo prawdziwy profil UI).
     * @return null przy sukcesie, w przeciwnym razie powód blokady.
     */
    /** true, gdy AUTO blokuje WYŁĄCZNIE bramka wyników papierowych (technicznie wszystko gotowe). */
    fun autoBlockedOnlyByGate(): Boolean = executor.autoReadiness() == null && autoGate() != null

    /** @param overrideGate świadome włączenie AUTO mimo braku wyników papierowych (decyzja użytkownika). */
    suspend fun setMode(target: ExecutionMode, overrideGate: Boolean = false): String? {
        val blocked = when (target) {
            ExecutionMode.DRY_RUN -> null
            ExecutionMode.CONFIRM -> executor.readiness()
            ExecutionMode.AUTO -> executor.autoReadiness() ?: if (overrideGate) null else autoGate()
        }
        if (target == ExecutionMode.AUTO && overrideGate && blocked == null) {
            log("⚠️ AUTO włączone przez użytkownika BEZ potwierdzonych wyników papierowych")
        }
        if (blocked != null) {
            log("🔒 $target zablokowany: $blocked")
            return blocked
        }
        if (_mode.value == ExecutionMode.DRY_RUN && target != ExecutionMode.DRY_RUN) {
            risk.clearPaperPositions() // pozycje papierowe nie istnieją u brokera
        }
        _mode.value = target
        log("Tryb: $target")
        return null
    }

    /** @param bypassConfirmation true dla akcji człowieka i stopów ochronnych. */
    suspend fun submit(order: OrderRequest, bypassConfirmation: Boolean = false): ExecutionResult {
        when (val d = risk.evaluate(order)) {
            is RiskManager.Decision.Rejected -> {
                log("✋ ${d.reason}")
                return ExecutionResult.Rejected(d.reason)
            }
            is RiskManager.Decision.Approved -> Unit
        }
        if (_mode.value == ExecutionMode.CONFIRM && !bypassConfirmation) {
            _pending.value = order
            pendingAt = System.currentTimeMillis()
            log("❓ Potwierdź: ${order.side} ${formatQty(order.quantity)} ${order.symbol}")
            return ExecutionResult.Rejected("Oczekuje na potwierdzenie")
        }
        return execMutex.withLock { execute(order) }
    }

    suspend fun confirmPending() {
        val o = _pending.value ?: return
        _pending.value = null
        if (System.currentTimeMillis() - pendingAt > 60_000) {
            log("⌛ Propozycja wygasła (starsza niż 60 s) – cena mogła się zmienić")
            return
        }
        submit(o, bypassConfirmation = true)
    }

    fun rejectPending() { _pending.value = null }

    /** KILL: blokada nowych wejść, anulowanie zleceń oczekujących, zamknięcie pozycji. */
    suspend fun flattenAll(reason: String) = flattenMutex.withLock {
        risk.engageKillSwitch(reason)
        _pending.value = null
        if (_mode.value != ExecutionMode.DRY_RUN) runCatching { executor.cancelAll() }
        for (o in risk.flattenOrders()) submit(o, bypassConfirmation = true)
        log("⛔ KILL: $reason")
    }

    private suspend fun execute(order: OrderRequest): ExecutionResult {
        if (_mode.value == ExecutionMode.DRY_RUN) {
            risk.onFill(order, order.referencePrice, order.quantity, paper = true)
            log("🧪 PAPER ${order.side} ${formatQty(order.quantity)} ${order.symbol} @ %.2f".format(order.referencePrice))
            return ExecutionResult.Filled("PAPER-${order.clientOrderId}", order.referencePrice, order.quantity)
        }
        (if (_mode.value == ExecutionMode.AUTO) executor.autoReadiness() else executor.readiness())?.let { reason ->
            _mode.value = ExecutionMode.DRY_RUN
            log("🔒 Wykonawca niegotowy ($reason) – powrót do DRY_RUN")
            return ExecutionResult.Rejected(reason)
        }
        val result = withTimeoutOrNull(executorTimeoutMs) { executor.submit(order) }
            ?: ExecutionResult.Failed(TimeoutException("Brak odpowiedzi wykonawcy – stan NIEZNANY"))
        when (result) {
            is ExecutionResult.Filled -> {
                if (result.quantity > 0) risk.onFill(order, result.avgPrice, result.quantity)
                log("✅ ${order.side} ${formatQty(result.quantity)} @ %.2f (${order.reason})".format(result.avgPrice))
            }
            is ExecutionResult.Submitted -> {
                // Zakładamy wykonanie po cenie referencyjnej – UZGODNIJ z aplikacją brokera!
                risk.onFill(order, order.referencePrice, order.quantity)
                log("⚠️ ${result.note}")
            }
            is ExecutionResult.Rejected ->
                if (order.reduceOnly) log("🚨 ${order.reason} ${order.symbol}: ${result.reason}")
                else log("❌ Odrzucone przez wykonawcę: ${result.reason}")
            is ExecutionResult.Failed -> {
                log("💥 ${result.error.message}")
                risk.engageKillSwitch("Błąd wykonania: ${result.error.message}")
            }
        }
        return result
    }

    private fun log(msg: String) {
        Log.i("ExecutionEngine", msg)
        _events.tryEmit(msg)
    }
}

// ═════════════════════════ Wariant A: REST API ═════════════════════════

/**
 * Przykład: Binance Spot (domyślnie TESTNET). Podpis HMAC-SHA256 parametrów.
 * Klucze trzymaj w Android Keystore / EncryptedSharedPreferences – nigdy w kodzie.
 * Klucz API: tylko uprawnienie "trade", BEZ wypłat, z białą listą IP jeśli to możliwe.
 */
class BinanceSpotExecutor(
    private val http: OkHttpClient,
    private val apiKey: String,
    private val apiSecret: String,
    private val symbols: Set<String>,
    private val baseUrl: String = "https://testnet.binance.vision"
) : OrderExecutor {

    override fun readiness(): String? =
        if (apiKey.isBlank() || apiSecret.isBlank()) "brak klucza API" else null

    override suspend fun submit(order: OrderRequest): ExecutionResult = withContext(Dispatchers.IO) {
        try {
            val body = signed(
                linkedMapOf(
                    "symbol" to order.symbol,
                    "side" to order.side.name,
                    "type" to "MARKET",
                    "quantity" to formatQty(order.quantity),
                    "newClientOrderId" to order.clientOrderId, // idempotencja przy retry
                    "newOrderRespType" to "FULL",
                    "recvWindow" to "5000",
                    "timestamp" to System.currentTimeMillis().toString()
                )
            )
            val req = Request.Builder()
                .url("$baseUrl/api/v3/order")
                .header("X-MBX-APIKEY", apiKey)
                .post(body.toRequestBody("application/x-www-form-urlencoded".toMediaType()))
                .build()
            http.newCall(req).execute().use { r ->
                val text = r.body?.string().orEmpty()
                if (!r.isSuccessful) return@withContext ExecutionResult.Rejected("HTTP ${r.code}: $text")
                val o = JSONObject(text)
                val qty = o.getString("executedQty").toDouble()
                val quote = o.getString("cummulativeQuoteQty").toDouble()
                ExecutionResult.Filled(
                    orderId = o.getLong("orderId").toString(),
                    avgPrice = if (qty > 0) quote / qty else order.referencePrice,
                    quantity = qty
                )
            }
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            ExecutionResult.Failed(t)
        }
    }

    override suspend fun cancelAll() = withContext(Dispatchers.IO) {
        for (s in symbols) {
            runCatching {
                val q = signed(linkedMapOf("symbol" to s, "timestamp" to System.currentTimeMillis().toString()))
                http.newCall(
                    Request.Builder().url("$baseUrl/api/v3/openOrders?$q")
                        .header("X-MBX-APIKEY", apiKey).delete().build()
                ).execute().close()
            }
        }
    }

    private fun signed(params: LinkedHashMap<String, String>): String {
        val query = params.entries.joinToString("&") { "${it.key}=${URLEncoder.encode(it.value, "UTF-8")}" }
        val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(apiSecret.toByteArray(), "HmacSHA256")) }
        val sig = mac.doFinal(query.toByteArray()).joinToString("") { "%02x".format(it) }
        return "$query&signature=$sig"
    }
}

// ═════════════════════════ Wariant B: AccessibilityService ═════════════════════════

data class NodeSelector(val viewId: String? = null, val text: String? = null, val contentDesc: String? = null)

data class UiTargetProfile(
    val packageName: String,
    val buyButton: NodeSelector,
    val sellButton: NodeSelector,
    val quantityField: NodeSelector? = null,
    val confirmButton: NodeSelector? = null,
    val priceLabel: NodeSelector? = null,
    /** Odrzuć, jeśli cena na ekranie odbiega od feedu bardziej niż o ten procent. */
    val maxPriceDriftPct: Double = 0.005,
    /** false = przycisk przeciwny otwiera nową pozycję (XTB), więc zamykanie robi człowiek. */
    val canClosePositions: Boolean = true,
    /** Przed kliknięciem sprawdź, czy na ekranie jest instrument ze zlecenia. */
    val verifyInstrument: Boolean = false,
    val allowAuto: Boolean = true
)

object UiProfiles {
    /**
     * XTB – identyfikatory z prawdziwych zrzutów. Wejście: wolumen + SL + TP w bilecie, potem Buy/Sell.
     * Zamknięcie: Otwarte pozycje → PositionRow0 → Zamknij pozycję (Sell przy Buy otwiera NOWĄ pozycję!).
     * AUTO dodatkowo wymaga dodatniego wyniku papierowego (bramka w ExecutionEngine).
     */
    val XTB = UiTargetProfile(
        packageName = ScreenScanner.XTB_PACKAGE,
        buyButton = NodeSelector(viewId = "TradingBuyButton"),
        sellButton = NodeSelector(viewId = "TradingSellButton"),
        quantityField = NodeSelector(viewId = "StepperTextField"),
        confirmButton = null,
        priceLabel = null,
        maxPriceDriftPct = 0.02, // ceny w tle z PAXG różnią się lekko od XTB
        canClosePositions = false,
        verifyInstrument = true,
        allowAuto = true
    )

    /**
     * PRZYKŁAD z wymyślonymi ID. Prawdziwe odczytasz poleceniem
     * `adb shell uiautomator dump` lub w Layout Inspectorze, z otwartą aplikacją brokera.
     * Aplikacje w Compose/Flutter często nie mają resource-id – wtedy selektor po tekście/opisie.
     */
    val EXAMPLE_BROKER = UiTargetProfile(
        packageName = "com.example.brokerapp",
        buyButton = NodeSelector(viewId = "com.example.brokerapp:id/btn_buy", text = "Buy"),
        sellButton = NodeSelector(viewId = "com.example.brokerapp:id/btn_sell", text = "Sell"),
        quantityField = NodeSelector(viewId = "com.example.brokerapp:id/et_volume"),
        confirmButton = NodeSelector(text = "Confirm"),
        priceLabel = NodeSelector(viewId = "com.example.brokerapp:id/tv_bid")
    )
}

class AccessibilityExecutor(private val profileProvider: () -> UiTargetProfile?) : OrderExecutor {
    override fun readiness(): String? = when {
        AutoExecutionService.instance == null -> "usługa ułatwień dostępu wyłączona"
        profileProvider()?.packageName?.startsWith("com.example.") != false -> "brak prawdziwego profilu UI brokera"
        else -> null
    }

    override fun autoReadiness(): String? = readiness()
        ?: if (profileProvider()?.allowAuto == false) "AUTO wyłączone dla tej aplikacji do czasu testów" else null

    override suspend fun submit(order: OrderRequest): ExecutionResult {
        val svc = AutoExecutionService.instance
            ?: return ExecutionResult.Rejected("Usługa ułatwień dostępu jest wyłączona")
        val profile = profileProvider() ?: return ExecutionResult.Rejected("Brak profilu UI aplikacji brokera")
        return svc.executeOnScreen(order, profile)
    }

    /** Anulowanie zleceń przez UI jest specyficzne dla aplikacji – do zaimplementowania per profil. */
    override suspend fun cancelAll() = Unit
}

class AutoExecutionService : AccessibilityService() {

    companion object {
        @Volatile var instance: AutoExecutionService? = null
            private set
        @Volatile var activeProfile: UiTargetProfile? = null
        /** Aplikacje, których ekran HUD odczytuje (dopasowanie po nazwie widocznej w launcherze). */
        @Volatile var targetAppLabels: Set<String> = setOf("XTB")
        /** Ostatnio widziane "Dostępne środki" w aplikacji brokera. */
        @Volatile var lastAccountFunds: Double? = null
        @Volatile var lastAccountCurrency: String? = null

        private val _screenQuote = MutableStateFlow<ScreenQuote?>(null)
        /** Instrument i ceny widoczne aktualnie w aplikacji brokera. */
        val screenQuote: StateFlow<ScreenQuote?> = _screenQuote.asStateFlow()

        private val _screenPrice = MutableStateFlow<Double?>(null)
        /** Cena odczytana z ekranu brokera – do weryfikacji spójności z feedem. */
        val screenPrice: StateFlow<Double?> = _screenPrice.asStateFlow()

        private val _volumeTriple = MutableSharedFlow<Unit>(extraBufferCapacity = 4)
        /** 3× "głośność w dół" w ciągu 2 s – przełącza tarczę dotyku. */
        val volumeTriple: SharedFlow<Unit> = _volumeTriple.asSharedFlow()

        private val _peek = MutableStateFlow(false)
        /** true = usługa nie widzi XTB i prosi nakładkę o chwilowe uchylenie tarczy dotyku. */
        val peek: StateFlow<Boolean> = _peek.asStateFlow()
        private val _actionActive = MutableStateFlow(false)
        /** true = trwa sekwencja kliknięć w XTB (tarcza musi być uchylona). */
        val actionActive: StateFlow<Boolean> = _actionActive.asStateFlow()
        /** Diagnoza odczytu ekranu (do HUD). */
        @Volatile var diag: String = "usługa jeszcze nie odczytała ekranu"
        @Volatile private var lastTargetEventAt = 0L

        private val _gestureActive = MutableStateFlow(false)
        /** HUD obserwuje to i chowa się na czas gestu, żeby nie przechwycić dotyku. */
        val gestureActive: StateFlow<Boolean> = _gestureActive.asStateFlow()

        private const val TAG = "AutoExecutionService"

        /** Diagnostyka: ostatnio widziane okno nagrywania (do skopiowania z aplikacji, gdy auto-akceptacja nie trafi). */
        @Volatile var lastCaptureDump: String? = null
        /** Do kiedy uznajemy, że trwa sesja zdalna (tarcza dotyku jest wtedy wstrzymana). */
        @Volatile var remoteSessionUntil: Long = 0L
        private val CAPTURE_HINTS = listOf("nagrywa", "przesyła", "udostępni", "record", "cast", "share")
        private val CAPTURE_SINGLE = listOf("jedna aplikacj", "jedną aplikacj", "pojedyncz", "single app", "one app")
        private val CAPTURE_ENTIRE = listOf("cały ekran", "caly ekran", "entire screen")
        private val CAPTURE_START = listOf(
            "Rozpocznij", "Rozpocznij teraz", "Zacznij teraz", "Udostępnij ekran", "Udostępnij", "Dalej",
            "Start now", "Start", "Share screen", "Share", "Next"
        )
        private val numberRegex = Regex("[0-9]+([.,][0-9]+)?")
    }

    private var lastScan = 0L
    private val labelCache = HashMap<String, String>()

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        startPolling()
        Log.i(TAG, "Połączono")
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        instance = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        pollJob?.cancel()
        instance = null
        super.onDestroy()
    }

    override fun onInterrupt() = Unit

    // ───────────── Auto-akceptacja systemowego okna nagrywania ekranu – TYLKO dla AnyDesk ─────────────
    private var lastCaptureAttempt = 0L

    /**
     * Android przy każdym połączeniu zdalnym pyta "Rozpocząć nagrywanie lub przesyłanie przez AnyDesk?".
     * Na telefonie z uszkodzonym dotykiem nie da się tego kliknąć, więc (po włączeniu opcji w aplikacji)
     * robimy to za użytkownika: wybór "Cały ekran" → "Rozpocznij". Okna innych aplikacji są ignorowane.
     */
    private fun maybeAcceptCapture(pkg: String) {
        if (!getSharedPreferences("ui", MODE_PRIVATE).getBoolean("auto_capture", false)) return
        val now = SystemClock.uptimeMillis()
        if (now - lastCaptureAttempt < 700) return
        lastCaptureAttempt = now
        val roots = runCatching { windows.mapNotNull { it.root }.filter { it.packageName?.toString() == pkg } }.getOrDefault(emptyList())
        for (root in roots) {
            val nodes = textNodes(root)
            val texts = nodes.map { it.second }
            if (texts.none { it.contains("AnyDesk", ignoreCase = true) }) continue
            if (texts.none { t -> CAPTURE_HINTS.any { t.contains(it, ignoreCase = true) } }) continue
            lastCaptureDump = ScreenScanner.dump(root)
            fun find(options: List<String>, exact: Boolean) = nodes.firstOrNull { (_, t) ->
                options.any { o -> if (exact) t.equals(o, ignoreCase = true) else t.contains(o, ignoreCase = true) }
            }?.first
            val single = find(CAPTURE_SINGLE, exact = false)
            val entire = find(CAPTURE_ENTIRE, exact = false)
            val target = when {
                single != null && entire == null -> single      // rozwiń wybór zakresu
                single != null && entire != null -> entire      // lista otwarta → "Cały ekran"
                else -> find(CAPTURE_START, exact = true)        // zatwierdź
            } ?: return
            var n: AccessibilityNodeInfo? = target
            while (n != null && !n.isClickable) n = n.parent
            (n ?: target).performAction(AccessibilityNodeInfo.ACTION_CLICK)
            // Trwa sesja zdalna: tarcza dotyku blokowałaby też sterowanie z AnyDesk → pauza tarczy na 30 min.
            remoteSessionUntil = System.currentTimeMillis() + 30 * 60_000
            Log.i(TAG, "Okno nagrywania AnyDesk: kliknięto element")
            return
        }
    }

    private fun textNodes(root: AccessibilityNodeInfo, limit: Int = 400): List<Pair<AccessibilityNodeInfo, String>> {
        val out = ArrayList<Pair<AccessibilityNodeInfo, String>>()
        val queue = ArrayDeque<AccessibilityNodeInfo>().apply { add(root) }
        var visited = 0
        while (queue.isNotEmpty() && visited < limit) {
            val n = queue.removeFirst()
            visited++
            val t = (n.text ?: n.contentDescription)?.toString()?.trim()
            if (!t.isNullOrEmpty()) out += n to t
            for (i in 0 until n.childCount) n.getChild(i)?.let { queue.add(it) }
        }
        return out
    }

    private val volPresses = ArrayDeque<Long>()

    override fun onKeyEvent(event: android.view.KeyEvent): Boolean {
        if (event.keyCode == android.view.KeyEvent.KEYCODE_VOLUME_DOWN && event.action == android.view.KeyEvent.ACTION_DOWN) {
            val now = SystemClock.uptimeMillis()
            volPresses.addLast(now)
            while (volPresses.isNotEmpty() && now - volPresses.first() > 2_000) volPresses.removeFirst()
            if (volPresses.size >= 3) { volPresses.clear(); _volumeTriple.tryEmit(Unit) }
        }
        return false // głośność działa normalnie
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val pkg = event?.packageName?.toString() ?: return
        if (pkg == "com.android.systemui" || pkg == "android") { maybeAcceptCapture(pkg); return }
        targetLabel(pkg) ?: return // inne aplikacje odrzucamy od razu – nic z nich nie czytamy
        lastTargetEventAt = SystemClock.uptimeMillis()
        scanNow()
    }

    /** Odczyt okna brokera. Wywoływany przy zdarzeniach XTB ORAZ co sekundę (nie polegamy tylko na zdarzeniach). */
    private fun scanNow(): Boolean {
        val now = SystemClock.uptimeMillis()
        if (now - lastScan < 300) return true // throttling – drzewo widoków jest drogie
        lastScan = now
        val root = appRoot()
        val pkg = root?.packageName?.toString()
        val label = pkg?.let { targetLabel(it) }
        if (root == null || pkg == null || label == null) {
            diag = "nie widzę okna XTB" + (if (root != null) " (na wierzchu: $pkg)" else "") +
                (if (lastTargetEventAt > 0) ", ostatni sygnał z XTB ${(now - lastTargetEventAt) / 1000} s temu" else ", brak sygnałów z XTB")
            return false
        }
        val profile = activeProfile?.takeIf { it.packageName == pkg }
        val profilePrice = profile?.priceLabel?.let { sel -> find(root, sel)?.text?.toString()?.let(::parsePrice) }
        val ts = System.currentTimeMillis()
        val quote = runCatching {
            if (pkg == ScreenScanner.XTB_PACKAGE) {
                // XTB: odczyt po identyfikatorach; poza ekranem instrumentu brak instrumentu – bez zgadywania.
                ScreenScanner.scanXtb(root, pkg, label, ts) ?: ScreenQuote(pkg, label, null, null, null, ts)
            } else {
                ScreenScanner.scan(root, pkg, label, ts)
            }
        }.getOrNull()
        if (quote != null) {
            _screenQuote.value = quote
            quote.availableFunds?.let { lastAccountFunds = it; lastAccountCurrency = quote.accountCurrency }
        }
        (profilePrice ?: quote?.mid)?.let { _screenPrice.value = it }
        diag = when {
            quote == null -> "widzę XTB, ale odczyt się nie powiódł"
            quote.instrument == null -> "widzę XTB, ale to nie ekran instrumentu (brak tytułu instrumentu)"
            quote.bid == null || quote.ask == null -> "widzę ${quote.instrument}, ale bez cen Sell/Buy – przewiń bilet, żeby przyciski były widoczne"
            else -> "OK"
        }
        return true
    }

    /** Pętla: odczyt co 1 s; gdy okno XTB jest niedostępne (np. zasłonięte tarczą), prosi o chwilowe uchylenie tarczy. */
    private fun startPolling() {
        pollJob?.cancel()
        pollJob = serviceScope.launch {
            var misses = 0
            while (isActive) {
                val ok = runCatching { scanNow() }.getOrDefault(false)
                if (ok) { misses = 0; _peek.value = false } else if (++misses >= 3) { _peek.value = true; misses = 0 }
                delay(1_000)
            }
        }
    }

    private val serviceScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.Main)
    private var pollJob: kotlinx.coroutines.Job? = null

    /** Na czas klikania w XTB tarcza musi być uchylona. */
    private suspend fun <T> withAction(block: suspend () -> T): T {
        _actionActive.value = true
        try {
            delay(350) // czas na uchylenie tarczy
            return block()
        } finally {
            _actionActive.value = false
        }
    }


    /**
     * Okno aplikacji brokera. NIE polegamy na "aktywnym oknie": gdy tarcza dotyku albo HUD przechwyci dotknięcie,
     * aktywnym oknem staje się nasza nakładka i bot przestawał widzieć XTB.
     */
    private fun appRoot(): AccessibilityNodeInfo? {
        val active = getRootInActiveWindow()
        if (active != null && active.packageName?.toString()?.let { targetLabel(it) } != null) return active
        val other = runCatching {
            windows.mapNotNull { it.root }.firstOrNull { r -> r.packageName?.toString()?.let { targetLabel(it) } != null }
        }.getOrNull()
        return other ?: active
    }

    /** Etykieta aplikacji, jeśli to aplikacja docelowa (np. "XTB"); dla pozostałych null. */
    private fun targetLabel(pkg: String): String? {
        if (pkg == packageName) return null
        return labelCache.getOrPut(pkg) {
            val label = runCatching {
                packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
            }.getOrNull()
            val isTarget = activeProfile?.packageName == pkg ||
                (label != null && targetAppLabels.any { label.contains(it, ignoreCase = true) })
            if (isTarget) label ?: pkg else ""
        }.ifEmpty { null }
    }

    /**
     * Zrzut drzewa widoków aplikacji brokera. Szuka jej okna wśród widocznych okien
     * (nie tylko aktywnego), a gdy XTB nie jest na ekranie – zwraca null zamiast zrzutu innej aplikacji.
     */
    fun dumpTargetWindow(): String? {
        val roots = buildList {
            appRoot()?.let { add(it) }
            runCatching { windows }.getOrNull()?.forEach { w -> w.root?.let { add(it) } }
        }
        val target = roots.firstOrNull { r -> r.packageName?.toString()?.let { targetLabel(it) } != null }
            ?: return null
        return ScreenScanner.dump(target)
    }

    suspend fun executeOnScreen(order: OrderRequest, profile: UiTargetProfile): ExecutionResult =
        withContext(Dispatchers.Main.immediate) {
            try {
                withAction { performOrder(order, profile) }
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                ExecutionResult.Failed(t)
            }
        }

    private suspend fun performOrder(order: OrderRequest, p: UiTargetProfile): ExecutionResult {
        val root = appRoot() ?: return ExecutionResult.Rejected("Brak dostępu do aktywnego okna")
        val pkg = root.packageName?.toString()
        if (pkg != p.packageName) return ExecutionResult.Rejected("Na ekranie jest $pkg zamiast ${p.packageName}")
        if (order.reduceOnly && !p.canClosePositions) {
            // Przycisk przeciwny otworzyłby NOWĄ pozycję. Zamykamy przez ekran pozycji, jeśli jest otwarty.
            if (pkg == ScreenScanner.XTB_PACKAGE) {
                openPositionScreen(order.symbol)?.let { return ExecutionResult.Rejected("ZAMKNIJ ${order.symbol}: $it") }
                return doClosePosition(order.symbol)
            }
            return ExecutionResult.Rejected("ZAMKNIJ POZYCJĘ ${order.symbol} ręcznie w aplikacji brokera")
        }
        if (p.verifyInstrument) {
            val q = screenQuote.value
            if (q == null || !q.isFresh() || q.instrument != order.symbol) {
                return ExecutionResult.Rejected("Na ekranie jest ${q?.instrument ?: "?"}, a zlecenie dotyczy ${order.symbol}")
            }
        }

        if (pkg == ScreenScanner.XTB_PACKAGE) return performXtbEntry(order)

        screenPrice.value?.let { sp ->
            val drift = abs(sp - order.referencePrice) / order.referencePrice
            if (drift > p.maxPriceDriftPct) {
                return ExecutionResult.Rejected("Cena w aplikacji (%.4f) odbiega od feedu o %.2f%%".format(sp, drift * 100))
            }
        }

        p.quantityField?.let { sel ->
            val field = awaitNode(sel, 2_000) ?: return ExecutionResult.Rejected("Nie znaleziono pola ilości")
            val args = Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, formatQty(order.quantity))
            }
            if (!field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) {
                return ExecutionResult.Rejected("Nie udało się wpisać ilości")
            }
            delay(250)
            // Sprawdź, czy aplikacja przyjęła wolumen – inaczej NIE klikamy.
            val typed = awaitNode(sel, 1_000)?.text?.toString()?.let { ScreenScanner.parseNumber(it) }
            if (typed == null || abs(typed - order.quantity) > 1e-9 + order.quantity * 1e-6) {
                return ExecutionResult.Rejected("Aplikacja nie przyjęła wolumenu (jest $typed, miało być ${formatQty(order.quantity)})")
            }
        }

        val btnSel = if (order.side == Side.BUY) p.buyButton else p.sellButton
        val btn = awaitNode(btnSel, 2_000) ?: return ExecutionResult.Rejected("Nie znaleziono przycisku ${order.side}")
        if (!click(btn)) return ExecutionResult.Rejected("Kliknięcie ${order.side} nie powiodło się")

        // Od tej chwili zlecenie MOGŁO zostać wysłane – każdy błąd to stan nieznany (Failed → KILL).
        p.confirmButton?.let { sel ->
            val confirm = awaitNode(sel, 3_000)
                ?: return ExecutionResult.Failed(IllegalStateException("Brak dialogu potwierdzenia – stan zlecenia NIEZNANY"))
            if (!click(confirm)) {
                return ExecutionResult.Failed(IllegalStateException("Nie kliknięto potwierdzenia – stan NIEZNANY"))
            }
        }
        return ExecutionResult.Submitted("Kliknięto ${order.side} ${formatQty(order.quantity)} ${order.symbol} – sprawdź w aplikacji i ustaw tam SL")
    }


    // ───────────── Ekran pozycji XTB: SL/TP i zamykanie ─────────────

    /** Wpisuje SL/TP na ekranie pozycji XTB i dotyka "Modyfikuj pozycję". */
    suspend fun setProtection(instrument: String, sl: Double?, tp: Double?): ExecutionResult =
        withContext(Dispatchers.Main.immediate) {
            try {
                withAction { doSetProtection(instrument, sl, tp) }
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                ExecutionResult.Failed(t)
            }
        }

    /** Dotyka "Zamknij pozycję" na ekranie pozycji XTB. */
    suspend fun closeXtbPosition(instrument: String): ExecutionResult =
        withContext(Dispatchers.Main.immediate) {
            try {
                withAction { doClosePosition(instrument) }
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                ExecutionResult.Failed(t)
            }
        }

    /** null = na ekranie jest pozycja [instrument] z przyciskami Zamknij/Modyfikuj. */
    private fun positionScreenProblem(root: AccessibilityNodeInfo?, instrument: String): String? {
        if (root == null) return "brak dostępu do okna"
        if (root.packageName?.toString() != ScreenScanner.XTB_PACKAGE) return "XTB nie jest na ekranie"
        val title = ScreenScanner.findById(root, "ElementTitleHorizontalText").firstOrNull()
            ?.text?.toString()?.trim()?.substringBefore(' ')
        if (title != instrument) return "na ekranie jest ${title ?: "?"}, a pozycja dotyczy $instrument"
        if (ScreenScanner.findById(root, "ModifyPositionButton").isEmpty()) {
            return "otwórz w XTB ekran pozycji $instrument (Take Profit / Stop Loss / Zamknij pozycję)"
        }
        return null
    }

    private suspend fun doSetProtection(instrument: String, sl: Double?, tp: Double?): ExecutionResult {
        openPositionScreen(instrument)?.let { return ExecutionResult.Rejected(it) }
        tp?.let { price -> setPriceIn("TakeProfitInput", "TakeProfitToggle", price)?.let { return ExecutionResult.Rejected("TP: $it") } }
        sl?.let { price -> setPriceIn("StopLossInput", "StopLossToggle", price)?.let { return ExecutionResult.Rejected("SL: $it") } }
        val modify = appRoot()?.let { ScreenScanner.findById(it, "ModifyPositionButton").firstOrNull() }
            ?: return ExecutionResult.Rejected("brak przycisku Modyfikuj pozycję")
        if (!click(modify)) return ExecutionResult.Rejected("nie udało się dotknąć Modyfikuj pozycję")
        delay(1_200)
        return ExecutionResult.Submitted(
            "Ustawiono w XTB" + (sl?.let { " SL %.2f".format(it) } ?: "") + (tp?.let { " TP %.2f".format(it) } ?: "") +
                " – sprawdź na wykresie"
        )
    }

    private suspend fun doClosePosition(instrument: String): ExecutionResult {
        openPositionScreen(instrument)?.let { return ExecutionResult.Rejected(it) }
        val close = appRoot()?.let { ScreenScanner.findById(it, "CloseVolumeButton").firstOrNull() }
            ?: return ExecutionResult.Rejected("brak przycisku Zamknij pozycję")
        if (!click(close)) return ExecutionResult.Rejected("nie udało się dotknąć Zamknij pozycję")

        // XTB otwiera arkusz "Zamknij pozycję" z wolumenem i drugim przyciskiem (CloseButton).
        val sheetBtn = awaitById("CloseButton", 3_000)
            ?: return ExecutionResult.Failed(IllegalStateException("po 'Zamknij pozycję' nie pojawił się arkusz – sprawdź pozycję w XTB"))
        val root = appRoot() ?: return ExecutionResult.Failed(IllegalStateException("brak okna arkusza"))
        val title = ScreenScanner.findById(root, "BottomSheetHeaderTitle").firstOrNull()?.text?.toString()
        if (title?.contains("Zamknij", ignoreCase = true) != true) {
            return ExecutionResult.Failed(IllegalStateException("nieoczekiwany arkusz: ${title ?: "?"} – dokończ ręcznie"))
        }
        // Zamykamy CAŁY wolumen: "Pozostały wolumen" musi wynosić 0.
        val remaining = valueInside(root, "RemainingVolumeValue")?.let { ScreenScanner.parseNumber(it) ?: if (it.trim() == "0") 0.0 else null }
        if (remaining == null || remaining > 1e-9) {
            return ExecutionResult.Failed(IllegalStateException("arkusz zamyka tylko część pozycji (pozostało $remaining) – dokończ ręcznie"))
        }
        if (!click(sheetBtn)) return ExecutionResult.Failed(IllegalStateException("nie udało się potwierdzić zamknięcia w arkuszu"))

        // Sukces dopiero, gdy arkusz zniknie.
        val gone = withTimeoutOrNull(5_000) {
            while (appRoot()?.let { ScreenScanner.findById(it, "CloseButton").isNotEmpty() } != false) delay(200)
            true
        } ?: false
        return if (gone) ExecutionResult.Submitted("Zamknięto pozycję $instrument w XTB")
        else ExecutionResult.Failed(IllegalStateException("arkusz zamknięcia nadal otwarty – sprawdź XTB"))
    }

    private fun valueInside(root: AccessibilityNodeInfo, containerId: String): String? {
        val area = ScreenScanner.findById(root, containerId).firstOrNull()?.let { n -> Rect().also { n.getBoundsInScreen(it) } }
            ?: return null
        val r = Rect()
        return ScreenScanner.findById(root, "ValueTextText").firstOrNull { n ->
            n.getBoundsInScreen(r); area.contains(r.centerX(), r.centerY())
        }?.text?.toString()
    }

    /** Włącza (jeśli trzeba) sekcję SL/TP, wpisuje cenę i sprawdza, czy XTB ją przyjęło. null = OK. */
    private suspend fun setPriceIn(containerId: String, toggleId: String, price: Double): String? {
        revealById(containerId) ?: return "nie znaleziono sekcji"
        var field = fieldIn(containerId)
        if (field == null) {
            val root = appRoot() ?: return "brak okna"
            val toggleArea = ScreenScanner.findById(root, toggleId).firstOrNull()?.let { n -> Rect().also { n.getBoundsInScreen(it) } }
                ?: return "nie znaleziono przełącznika"
            val r = Rect()
            val sw = ScreenScanner.findById(root, "ToggleRowSwitch").firstOrNull { n ->
                n.getBoundsInScreen(r); toggleArea.contains(r.centerX(), r.centerY())
            } ?: return "nie znaleziono przełącznika"
            if (!click(sw)) return "nie udało się włączyć przełącznika"
            delay(500)
            field = fieldIn(containerId) ?: return "pole ceny nie pojawiło się"
        }
        val decimals = field.text?.toString()?.substringAfter('.', "")?.length?.takeIf { it in 1..6 } ?: 2
        val txt = BigDecimal(price).setScale(decimals, RoundingMode.HALF_UP).toPlainString()
        val args = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, txt) }
        if (!field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) return "nie udało się wpisać ceny"
        delay(300)
        val typed = fieldIn(containerId)?.text?.toString()?.let { ScreenScanner.parseNumber(it) }
        val tolerance = BigDecimal.ONE.movePointLeft(decimals).toDouble()
        if (typed == null || abs(typed - txt.toDouble()) > tolerance / 2) return "XTB nie przyjęło ceny (jest $typed, miało być $txt)"
        return null
    }

    private fun fieldIn(containerId: String): AccessibilityNodeInfo? {
        val root = appRoot() ?: return null
        val area = ScreenScanner.findById(root, containerId).firstOrNull()?.let { n -> Rect().also { n.getBoundsInScreen(it) } }
            ?: return null
        val r = Rect()
        return ScreenScanner.findById(root, "StepperTextField").firstOrNull { n ->
            n.getBoundsInScreen(r); area.contains(r.centerX(), r.centerY())
        }
    }


    // ───────────── Nawigacja po XTB: przewijanie, wejście w pozycję, bilet z SL/TP ─────────────

    /** Największy przewijalny element ekranu. */
    private fun findScrollable(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val queue = ArrayDeque<AccessibilityNodeInfo>().apply { add(root) }
        var best: AccessibilityNodeInfo? = null
        var bestArea = 0
        val r = Rect()
        var visited = 0
        while (queue.isNotEmpty() && visited < 3000) {
            val n = queue.removeFirst()
            visited++
            if (n.isScrollable) {
                n.getBoundsInScreen(r)
                val area = r.width() * r.height()
                if (area > bestArea) { best = n; bestArea = area }
            }
            for (i in 0 until n.childCount) n.getChild(i)?.let { queue.add(it) }
        }
        return best
    }

    /** Doprowadza element o danym ID na ekran (SHOW_ON_SCREEN albo przewijanie). */
    private suspend fun revealById(id: String): AccessibilityNodeInfo? {
        val plan = listOf(0, 1, 1, 1, 1, -1, -1, -1, -1, -1, -1, -1, -1) // 0 = sprawdź, 1 = w dół, -1 = w górę
        for (step in plan) {
            val root = appRoot() ?: return null
            ScreenScanner.findById(root, id).firstOrNull()?.let { return it }
            val hidden = ScreenScanner.findById(root, id, visibleOnly = false).firstOrNull()
            if (hidden != null &&
                hidden.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SHOW_ON_SCREEN.id)
            ) {
                delay(400)
                ScreenScanner.findById(appRoot() ?: return null, id).firstOrNull()?.let { return it }
            }
            if (step == 0) continue
            val scroller = findScrollable(root) ?: return null
            scroller.performAction(
                if (step > 0) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
            )
            delay(450)
        }
        return null
    }

    private suspend fun awaitById(id: String, timeoutMs: Long): AccessibilityNodeInfo? = withTimeoutOrNull(timeoutMs) {
        var found: AccessibilityNodeInfo? = null
        while (found == null) {
            found = appRoot()?.let { ScreenScanner.findById(it, id).firstOrNull() }
            if (found == null) delay(150)
        }
        found
    }

    /** Przycisk "Potwierdź zlecenie …" na ekranie potwierdzenia XTB. */
    private fun findOrderConfirm(root: AccessibilityNodeInfo): AccessibilityNodeInfo? =
        root.findAccessibilityNodeInfosByText("Potwierdź zlecenie").firstOrNull { n ->
            n.isVisibleToUser && (n.text?.toString() ?: n.contentDescription?.toString() ?: "")
                .startsWith("Potwierdź zlecenie", ignoreCase = true)
        }

    private fun xtbTitle(root: AccessibilityNodeInfo?): String? =
        root?.let { ScreenScanner.findById(it, "ElementTitleHorizontalText").firstOrNull() }
            ?.text?.toString()?.trim()?.substringBefore(' ')

    /** Z ekranu instrumentu wchodzi w (jedyną) otwartą pozycję. null = jesteśmy na ekranie pozycji. */
    private suspend fun openPositionScreen(instrument: String): String? {
        val root = appRoot() ?: return "brak dostępu do okna"
        if (root.packageName?.toString() != ScreenScanner.XTB_PACKAGE) return "XTB nie jest na ekranie"
        if (xtbTitle(root) != instrument) return "otwórz w XTB instrument $instrument"
        if (ScreenScanner.findById(root, "ModifyPositionButton").isNotEmpty()) return null
        if (ScreenScanner.findById(root, "PositionRow1", visibleOnly = false).isNotEmpty()) {
            return "masz kilka pozycji $instrument – bot nie wie, którą wybrać; zamknij ręcznie"
        }
        val row = revealById("PositionRow0") ?: return "nie znaleziono otwartej pozycji $instrument w XTB"
        if (!click(row)) return "nie udało się otworzyć pozycji"
        awaitById("ModifyPositionButton", 3_000) ?: return "ekran pozycji się nie otworzył"
        return null
    }

    /**
     * Wejście w XTB: wolumen → Stop Loss → Take Profit (w bilecie, PRZED kliknięciem) → Buy/Sell.
     * Bez poprawnie ustawionego SL zlecenie NIE jest wysyłane.
     */
    private suspend fun performXtbEntry(order: OrderRequest): ExecutionResult {
        val q = screenQuote.value
        val sidePx = q?.let { if (order.side == Side.BUY) it.ask else it.bid }
            ?: return ExecutionResult.Rejected("brak ceny Buy/Sell na ekranie XTB")
        // Ceny bota mogą pochodzić z feedu w tle (PAXG) – przesuwamy SL/TP o różnicę do ceny XTB.
        val shift = sidePx - order.referencePrice
        val sl = order.stopPrice + shift
        val tp = order.takeProfit?.plus(shift)

        revealById("MarketSizeInput") ?: return ExecutionResult.Rejected("nie znaleziono pola wolumenu")
        val vol = fieldIn("MarketSizeInput") ?: return ExecutionResult.Rejected("nie znaleziono pola wolumenu")
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, formatQty(order.quantity))
        }
        if (!vol.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) return ExecutionResult.Rejected("nie udało się wpisać wolumenu")
        delay(300)
        val typed = fieldIn("MarketSizeInput")?.text?.toString()?.let { ScreenScanner.parseNumber(it) }
        if (typed == null || abs(typed - order.quantity) > 1e-9 + order.quantity * 1e-6) {
            return ExecutionResult.Rejected("XTB nie przyjęło wolumenu (jest $typed, miało być ${formatQty(order.quantity)})")
        }

        revealById("StopLossContainer") ?: return ExecutionResult.Rejected("nie znaleziono Stop Loss w bilecie")
        setPriceIn("StopLossContainer", "StopLossToggle", sl)?.let { return ExecutionResult.Rejected("SL: $it – nie otwieram bez stopu") }
        if (tp != null) {
            revealById("TakeProfitContainer") ?: return ExecutionResult.Rejected("nie znaleziono Take Profit w bilecie")
            setPriceIn("TakeProfitContainer", "TakeProfitToggle", tp)?.let { return ExecutionResult.Rejected("TP: $it") }
        } else {
            // Zlecenie bez TP: wyłącz ewentualnie pozostawiony (ręcznie) Take Profit ze starą ceną.
            revealById("TakeProfitContainer")
            if (fieldIn("TakeProfitContainer") != null) {
                val root = appRoot() ?: return ExecutionResult.Rejected("brak okna")
                val area = ScreenScanner.findById(root, "TakeProfitToggle").firstOrNull()?.let { n -> Rect().also { n.getBoundsInScreen(it) } }
                val r = Rect()
                val sw = area?.let { a ->
                    ScreenScanner.findById(root, "ToggleRowSwitch").firstOrNull { n -> n.getBoundsInScreen(r); a.contains(r.centerX(), r.centerY()) }
                }
                if (sw == null || !click(sw)) return ExecutionResult.Rejected("nie udało się wyłączyć starego Take Profit")
                delay(400)
                if (fieldIn("TakeProfitContainer") != null) return ExecutionResult.Rejected("Take Profit nadal włączony – nie wysyłam zlecenia")
            }
        }

        val btn = revealById(if (order.side == Side.BUY) "TradingBuyButton" else "TradingSellButton")
            ?: return ExecutionResult.Rejected("nie znaleziono przycisku ${order.side}")
        if (!click(btn)) return ExecutionResult.Rejected("kliknięcie ${order.side} nie powiodło się")

        // XTB może pokazać ekran "Nowe zlecenie Market" z przyciskiem "Potwierdź zlecenie Market" (np. przy SL/TP).
        val confirm = withTimeoutOrNull(3_000) {
            var n: AccessibilityNodeInfo? = null
            while (n == null) {
                n = appRoot()?.let { findOrderConfirm(it) }
                if (n == null) delay(150)
            }
            n
        }
        if (confirm != null) {
            // Zabezpieczenie: na ekranie potwierdzenia musi być nasz wolumen.
            val screenText = appRoot()?.let { ScreenScanner.collect(it).joinToString(" ") { t -> t.text } }.orEmpty()
            if (!screenText.contains(formatQty(order.quantity))) {
                return ExecutionResult.Failed(IllegalStateException("ekran potwierdzenia XTB pokazuje inny wolumen – NIE potwierdzam, sprawdź ręcznie"))
            }
            if (!click(confirm)) {
                return ExecutionResult.Failed(IllegalStateException("nie udało się dotknąć 'Potwierdź zlecenie' – stan NIEZNANY, sprawdź XTB"))
            }
            val gone = withTimeoutOrNull(6_000) {
                while (appRoot()?.let { findOrderConfirm(it) } != null) delay(200)
                true
            } ?: false
            if (!gone) return ExecutionResult.Failed(IllegalStateException("ekran potwierdzenia nadal otwarty – stan NIEZNANY, sprawdź XTB"))
        } else {
            delay(1_500)
        }
        return ExecutionResult.Submitted(
            "XTB: ${order.side} ${formatQty(order.quantity)} ${order.symbol}, SL %.2f".format(sl) +
                (tp?.let { ", TP %.2f".format(it) } ?: "") + " – sprawdź pozycję"
        )
    }

    private suspend fun awaitNode(sel: NodeSelector, timeoutMs: Long): AccessibilityNodeInfo? =
        withTimeoutOrNull(timeoutMs) {
            var found: AccessibilityNodeInfo? = null
            while (found == null) {
                found = appRoot()?.let { find(it, sel) }
                if (found == null) delay(100)
            }
            found
        }

    private fun find(root: AccessibilityNodeInfo, sel: NodeSelector): AccessibilityNodeInfo? {
        sel.viewId?.let { id ->
            val byId = if (':' in id) root.findAccessibilityNodeInfosByViewId(id) else ScreenScanner.findById(root, id)
            byId.firstOrNull { it.isVisibleToUser }?.let { return it }
        }
        sel.text?.let { t ->
            root.findAccessibilityNodeInfosByText(t).firstOrNull {
                it.isVisibleToUser && (it.text?.toString().equals(t, ignoreCase = true) ||
                    it.contentDescription?.toString().equals(t, ignoreCase = true))
            }?.let { return it }
        }
        sel.contentDesc?.let { d ->
            return bfs(root) { it.contentDescription?.toString().equals(d, ignoreCase = true) }
        }
        return null
    }

    private fun bfs(root: AccessibilityNodeInfo, predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        val queue = ArrayDeque<AccessibilityNodeInfo>().apply { add(root) }
        while (queue.isNotEmpty()) {
            val n = queue.removeFirst()
            if (n.isVisibleToUser && predicate(n)) return n
            for (i in 0 until n.childCount) n.getChild(i)?.let(queue::add)
        }
        return null
    }

    /** Najpierw ACTION_CLICK (nie zależy od nakładek), w ostateczności gest w środek węzła. */
    private suspend fun click(node: AccessibilityNodeInfo): Boolean {
        var n: AccessibilityNodeInfo? = node
        while (n != null && !n.isClickable) n = n.parent
        if (n != null && n.isEnabled && n.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
        val r = Rect().also(node::getBoundsInScreen)
        if (r.isEmpty) return false
        return tap(r.exactCenterX(), r.exactCenterY())
    }

    private suspend fun tap(x: Float, y: Float): Boolean {
        _gestureActive.value = true
        try {
            delay(80) // czas na schowanie HUD-a
            return suspendCancellableCoroutine { cont ->
                val gesture = GestureDescription.Builder()
                    .addStroke(GestureDescription.StrokeDescription(Path().apply { moveTo(x, y) }, 0, 60))
                    .build()
                val accepted = dispatchGesture(gesture, object : GestureResultCallback() {
                    override fun onCompleted(gestureDescription: GestureDescription?) { if (cont.isActive) cont.resume(true) }
                    override fun onCancelled(gestureDescription: GestureDescription?) { if (cont.isActive) cont.resume(false) }
                }, null)
                if (!accepted && cont.isActive) cont.resume(false)
            }
        } finally {
            _gestureActive.value = false
        }
    }

    /** Uwaga na formaty: "1 234,56" działa, "1,234.56" nie – dostosuj do aplikacji. */
    private fun parsePrice(text: String): Double? =
        numberRegex.find(text.replace("\u00A0", "").replace(" ", ""))
            ?.value?.replace(',', '.')?.toDoubleOrNull()
}
