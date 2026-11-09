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
    private val executorTimeoutMs: Long = 20_000
) {
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
    suspend fun setMode(target: ExecutionMode): String? {
        val blocked = when (target) {
            ExecutionMode.DRY_RUN -> null
            ExecutionMode.CONFIRM -> executor.readiness()
            ExecutionMode.AUTO -> executor.autoReadiness()
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
            log("❓ Potwierdź: ${order.side} ${formatQty(order.quantity)} ${order.symbol}")
            return ExecutionResult.Rejected("Oczekuje na potwierdzenie")
        }
        return execMutex.withLock { execute(order) }
    }

    suspend fun confirmPending() {
        val o = _pending.value ?: return
        _pending.value = null
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
     * XTB – identyfikatory z prawdziwego zrzutu. Wielkość pozycji w lotach liczona z "Wartości kontraktu".
     * Tylko CONFIRM: AUTO zablokowane, dopóki nie sprawdzimy, jak XTB reaguje na kliknięcie.
     * Zamykanie pozycji – ręcznie w XTB (Sell przy pozycji Buy otwiera NOWĄ pozycję).
     */
    val XTB = UiTargetProfile(
        packageName = ScreenScanner.XTB_PACKAGE,
        buyButton = NodeSelector(viewId = "TradingBuyButton"),
        sellButton = NodeSelector(viewId = "TradingSellButton"),
        quantityField = NodeSelector(viewId = "StepperTextField"),
        confirmButton = null,
        priceLabel = null,
        canClosePositions = false,
        verifyInstrument = true,
        allowAuto = false
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

        private val _gestureActive = MutableStateFlow(false)
        /** HUD obserwuje to i chowa się na czas gestu, żeby nie przechwycić dotyku. */
        val gestureActive: StateFlow<Boolean> = _gestureActive.asStateFlow()

        private const val TAG = "AutoExecutionService"
        private val numberRegex = Regex("[0-9]+([.,][0-9]+)?")
    }

    private var lastScan = 0L
    private val labelCache = HashMap<String, String>()

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.i(TAG, "Połączono")
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        instance = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    override fun onInterrupt() = Unit

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val pkg = event?.packageName?.toString() ?: return
        val label = targetLabel(pkg) ?: return // inne aplikacje odrzucamy od razu – nic z nich nie czytamy
        val now = SystemClock.uptimeMillis()
        if (now - lastScan < 300) return // throttling – drzewo widoków jest drogie
        lastScan = now
        val root = rootInActiveWindow ?: return
        if (root.packageName?.toString() != pkg) return

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
            rootInActiveWindow?.let { add(it) }
            runCatching { windows }.getOrNull()?.forEach { w -> w.root?.let { add(it) } }
        }
        val target = roots.firstOrNull { r -> r.packageName?.toString()?.let { targetLabel(it) } != null }
            ?: return null
        return ScreenScanner.dump(target)
    }

    suspend fun executeOnScreen(order: OrderRequest, profile: UiTargetProfile): ExecutionResult =
        withContext(Dispatchers.Main.immediate) {
            try {
                performOrder(order, profile)
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                ExecutionResult.Failed(t)
            }
        }

    private suspend fun performOrder(order: OrderRequest, p: UiTargetProfile): ExecutionResult {
        val root = rootInActiveWindow ?: return ExecutionResult.Rejected("Brak dostępu do aktywnego okna")
        val pkg = root.packageName?.toString()
        if (pkg != p.packageName) return ExecutionResult.Rejected("Na ekranie jest $pkg zamiast ${p.packageName}")
        if (order.reduceOnly && !p.canClosePositions) {
            return ExecutionResult.Rejected("ZAMKNIJ POZYCJĘ RĘCZNIE w aplikacji brokera (tu przycisk przeciwny otwiera nową pozycję)")
        }
        if (p.verifyInstrument) {
            val q = screenQuote.value
            if (q == null || !q.isFresh() || q.instrument != order.symbol) {
                return ExecutionResult.Rejected("Na ekranie jest ${q?.instrument ?: "?"}, a zlecenie dotyczy ${order.symbol}")
            }
        }

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

    private suspend fun awaitNode(sel: NodeSelector, timeoutMs: Long): AccessibilityNodeInfo? =
        withTimeoutOrNull(timeoutMs) {
            var found: AccessibilityNodeInfo? = null
            while (found == null) {
                found = rootInActiveWindow?.let { find(it, sel) }
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
