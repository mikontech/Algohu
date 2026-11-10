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
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.concurrent.TimeoutException
import kotlin.coroutines.resume
import kotlin.math.abs

interface OrderExecutor {
    suspend fun submit(order: OrderRequest): ExecutionResult
    suspend fun cancelAll()
    fun readiness(): String? = null
    fun autoReadiness(): String? = readiness()
}

private fun formatQty(q: Double): String = BigDecimal.valueOf(q).stripTrailingZeros().toPlainString()

class ExecutionEngine(
    private val risk: RiskManager,
    private val executor: OrderExecutor,
    initialMode: ExecutionMode = ExecutionMode.DRY_RUN,
    private val executorTimeoutMs: Long = 75_000,
    private val autoGate: () -> String? = { null },
    private val positionProbe: suspend (String) -> Boolean? = { null }
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

    fun autoBlockedOnlyByGate(): Boolean = executor.autoReadiness() == null && autoGate() != null

    suspend fun setMode(target: ExecutionMode, overrideGate: Boolean = false): String? {
        val blocked = when (target) {
            ExecutionMode.DRY_RUN -> null
            ExecutionMode.CONFIRM -> executor.readiness()
            ExecutionMode.AUTO -> executor.autoReadiness() ?: if (overrideGate) null else autoGate()
        }
        if (target == ExecutionMode.AUTO && overrideGate && blocked == null) {
            log("⚠️ AUTO włączone ręcznie")
        }
        if (blocked != null) {
            log("🔒 $target zablokowany: $blocked")
            return blocked
        }
        if (_mode.value == ExecutionMode.DRY_RUN && target != ExecutionMode.DRY_RUN) {
            risk.clearPaperPositions()
        }
        _mode.value = target
        log("Tryb: $target")
        return null
    }

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
            log("⌛ Propozycja wygasła (starsza niż 60 s)")
            return
        }
        submit(o, bypassConfirmation = true)
    }

    fun rejectPending() { _pending.value = null }

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
                risk.onFill(order, order.referencePrice, order.quantity)
                log("⚠️ ${result.note}")
            }
            is ExecutionResult.Rejected ->
                if (order.reduceOnly) log("🚨 ${order.reason} ${order.symbol}: ${result.reason}")
                else log("❌ Odrzucone przez wykonawcę: ${result.reason}")
            is ExecutionResult.Failed -> {
                log("💥 ${result.error.message}")
                return recoverFromFailure(order, result)
            }
        }
        return result
    }

    private suspend fun recoverFromFailure(order: OrderRequest, failed: ExecutionResult.Failed): ExecutionResult {
        val exists = runCatching { positionProbe(order.symbol) }.getOrNull()
        return when {
            !order.reduceOnly && exists == true -> {
                risk.onFill(order, order.referencePrice, order.quantity)
                log("🔎 Po błędzie: pozycja ${order.symbol} jest w XTB – zaksięgowana")
                ExecutionResult.Submitted("zaksięgowano po weryfikacji")
            }
            !order.reduceOnly && exists == false -> {
                log("🔎 Po błędzie: pozycji ${order.symbol} w XTB nie ma – zlecenie anulowane")
                ExecutionResult.Rejected("nie otwarto (zweryfikowano)")
            }
            order.reduceOnly && exists == false -> {
                risk.onFill(order, order.referencePrice, order.quantity)
                log("🔎 Po błędzie: pozycja ${order.symbol} jest już zamknięta")
                ExecutionResult.Submitted("zamknięcie zweryfikowane")
            }
            order.reduceOnly && exists == true -> {
                log("⚠️ Zamknięcie ${order.symbol} nie powiodło się; ponawiam")
                ExecutionResult.Rejected("pozycja nadal otwarta – ponawiam")
            }
            else -> {
                risk.engageKillSwitch("Błąd wykonania, stan nieznany: ${failed.error.message}")
                failed
            }
        }
    }

    private fun log(msg: String) {
        Log.i("ExecutionEngine", msg)
        _events.tryEmit(msg)
    }
}

data class NodeSelector(val viewId: String? = null, val text: String? = null, val contentDesc: String? = null)

data class UiTargetProfile(
    val packageName: String,
    val buyButton: NodeSelector,
    val sellButton: NodeSelector,
    val quantityField: NodeSelector? = null,
    val confirmButton: NodeSelector? = null,
    val priceLabel: NodeSelector? = null,
    val maxPriceDriftPct: Double = 0.02,
    val canClosePositions: Boolean = false,
    val verifyInstrument: Boolean = true,
    val allowAuto: Boolean = true
)

object UiProfiles {
    val XTB = UiTargetProfile(
        packageName = ScreenScanner.XTB_PACKAGE,
        buyButton = NodeSelector(viewId = "TradingBuyButton"),
        sellButton = NodeSelector(viewId = "TradingSellButton"),
        quantityField = NodeSelector(viewId = "StepperTextField"),
        confirmButton = null,
        priceLabel = null,
        maxPriceDriftPct = 0.02,
        canClosePositions = false,
        verifyInstrument = true,
        allowAuto = true
    )
}

class AccessibilityExecutor(private val profileProvider: () -> UiTargetProfile?) : OrderExecutor {
    override fun readiness(): String? = when {
        AutoExecutionService.instance == null -> "usługa ułatwień dostępu wyłączona"
        profileProvider()?.packageName?.startsWith("com.example.") != false -> "brak prawdziwego profilu UI brokera"
        else -> null
    }

    override fun autoReadiness(): String? = readiness()
        ?: if (profileProvider()?.allowAuto == false) "AUTO wyłączone dla profilu" else null

    override suspend fun submit(order: OrderRequest): ExecutionResult {
        val svc = AutoExecutionService.instance
            ?: return ExecutionResult.Rejected("Usługa ułatwień dostępu jest wyłączona")
        val profile = profileProvider() ?: return ExecutionResult.Rejected("Brak profilu UI")
        return svc.executeOnScreen(order, profile)
    }

    override suspend fun cancelAll() = Unit
}

class AutoExecutionService : AccessibilityService() {

    companion object {
        @Volatile var instance: AutoExecutionService? = null
            private set
        @Volatile var activeProfile: UiTargetProfile? = null
        @Volatile var targetAppLabels: Set<String> = setOf("XTB")
        @Volatile var lastAccountFunds: Double? = null
        @Volatile var lastAccountCurrency: String? = null

        private val _screenQuote = MutableStateFlow<ScreenQuote?>(null)
        val screenQuote: StateFlow<ScreenQuote?> = _screenQuote.asStateFlow()

        private val _screenPrice = MutableStateFlow<Double?>(null)
        val screenPrice: StateFlow<Double?> = _screenPrice.asStateFlow()

        private val _volumeTriple = MutableSharedFlow<Unit>(extraBufferCapacity = 4)
        val volumeTriple: SharedFlow<Unit> = _volumeTriple.asSharedFlow()

        private val _peek = MutableStateFlow(false)
        val peek: StateFlow<Boolean> = _peek.asStateFlow()
        private val _actionActive = MutableStateFlow(false)
        val actionActive: StateFlow<Boolean> = _actionActive.asStateFlow()
        @Volatile var diag: String = "usługa jeszcze nie odczytała ekranu"
        @Volatile private var lastTargetEventAt = 0L
        @Volatile var selfHealEnabled: Boolean = false

        private val _gestureActive = MutableStateFlow(false)
        val gestureActive: StateFlow<Boolean> = _gestureActive.asStateFlow()

        private const val TAG = "AutoExecutionService"
        @Volatile var lastCaptureDump: String? = null
        @Volatile var remoteSessionUntil: Long = 0L

        private val CAPTURE_HINTS = listOf("nagrywa", "przesyła", "udostępni", "record", "cast", "share")
        private val CAPTURE_SINGLE = listOf("jedna aplikacj", "jedną aplikacj", "pojedyncz", "single app", "one app")
        private val CAPTURE_ENTIRE = listOf("cały ekran", "caly ekran", "entire screen")
        private val CAPTURE_START = listOf(
            "Rozpocznij", "Rozpocznij teraz", "Zacznij teraz", "Udostępnij ekran", "Udostępnij", "Dalej",
            "Start now", "Start", "Share screen", "Share", "Next"
        )
    }

    private var lastScan = 0L
    private val labelCache = HashMap<String, String>()
    private val serviceScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.Main)
    private var pollJob: kotlinx.coroutines.Job? = null
    private val uiMutex = Mutex()
    private var lastCaptureAttempt = 0L
    private val volPresses = ArrayDeque<Long>()
    private var staleSince = 0L
    private var notVisibleSince = 0L
    private var lastFrontAttempt = 0L

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        startPolling()
        Log.i(TAG, "Usługa połączona")
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
                single != null && entire == null -> single
                single != null && entire != null -> entire
                else -> find(CAPTURE_START, exact = true)
            } ?: return
            var n: AccessibilityNodeInfo? = target
            while (n != null && !n.isClickable) n = n.parent
            (n ?: target).performAction(AccessibilityNodeInfo.ACTION_CLICK)
            remoteSessionUntil = System.currentTimeMillis() + 30 * 60_000
            Log.i(TAG, "Zaakceptowano okno nagrywania AnyDesk")
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

    override fun onKeyEvent(event: android.view.KeyEvent): Boolean {
        if (event.keyCode == android.view.KeyEvent.KEYCODE_VOLUME_DOWN && event.action == android.view.KeyEvent.ACTION_DOWN) {
            val now = SystemClock.uptimeMillis()
            volPresses.addLast(now)
            while (volPresses.isNotEmpty() && now - volPresses.first() > 2_000) volPresses.removeFirst()
            if (volPresses.size >= 3) {
                volPresses.clear()
                _volumeTriple.tryEmit(Unit)
            }
        }
        return false
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val pkg = event?.packageName?.toString() ?: return
        if (pkg == "com.android.systemui" || pkg == "android") {
            maybeAcceptCapture(pkg)
            return
        }
        targetLabel(pkg) ?: return
        lastTargetEventAt = SystemClock.uptimeMillis()
        scanNow()
    }

    private fun scanNow(): Boolean {
        val now = SystemClock.uptimeMillis()
        if (now - lastScan < 300) return true
        lastScan = now
        val root = appRoot()
        val pkg = root?.packageName?.toString()
        val label = pkg?.let { targetLabel(it) }
        if (root == null || pkg == null || label == null) {
            if (notVisibleSince == 0L) notVisibleSince = now
            diag = "brak widoku okna XTB"
            return false
        }
        notVisibleSince = 0L
        val ts = System.currentTimeMillis()
        val quote = runCatching {
            if (pkg == ScreenScanner.XTB_PACKAGE) {
                ScreenScanner.scanXtb(root, pkg, label, ts) ?: ScreenQuote(pkg, label, null, null, null, ts)
            } else {
                ScreenScanner.scan(root, pkg, label, ts)
            }
        }.getOrNull()
        if (quote != null) {
            _screenQuote.value = quote
            quote.availableFunds?.let {
                lastAccountFunds = it
                lastAccountCurrency = quote.accountCurrency
            }
            quote.mid?.let { _screenPrice.value = it }
        }
        diag = when {
            quote == null -> "błąd odczytu XTB"
            quote.instrument == null -> "widoczne XTB bez otwartego instrumentu"
            quote.bid == null || quote.ask == null -> "brak cen Sell/Buy na ekranie"
            else -> "OK"
        }
        return true
    }

    private fun startPolling() {
        pollJob?.cancel()
        pollJob = serviceScope.launch {
            var misses = 0
            while (isActive) {
                val ok = runCatching { scanNow() }.getOrDefault(false)
                if (ok) {
                    misses = 0
                    _peek.value = false
                } else if (++misses >= 3) {
                    _peek.value = true
                    misses = 0
                }
                runCatching { selfHeal() }
                runCatching { keepXtbFront() }
                delay(1_000)
            }
        }
    }

    private suspend fun <T> withAction(block: suspend () -> T): T = uiMutex.withLock {
        _actionActive.value = true
        try {
            delay(350)
            val result = block()
            runCatching { restoreTicketTop() }
            result
        } finally {
            _actionActive.value = false
        }
    }

    private fun appRoot(): AccessibilityNodeInfo? {
        val active = getRootInActiveWindow()
        if (active != null && active.packageName?.toString()?.let { targetLabel(it) } != null) return active
        return runCatching {
            windows.mapNotNull { it.root }.firstOrNull { r -> r.packageName?.toString()?.let { targetLabel(it) } != null }
        }.getOrNull() ?: active
    }

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

    fun dumpTargetWindow(): String? {
        val root = appRoot() ?: return null
        return ScreenScanner.dump(root)
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
        val root = appRoot() ?: return ExecutionResult.Rejected("Brak dostępu do okna")
        val pkg = root.packageName?.toString()
        if (pkg != p.packageName) return ExecutionResult.Rejected("Na ekranie: $pkg zamiast ${p.packageName}")

        if (order.reduceOnly && !p.canClosePositions) {
            if (pkg == ScreenScanner.XTB_PACKAGE) {
                openPositionScreen(order.symbol)?.let { return ExecutionResult.Rejected("Zamykanie ${order.symbol}: $it") }
                return doClosePosition(order.symbol)
            }
            return ExecutionResult.Rejected("ZAMKNIJ ${order.symbol} ręcznie")
        }

        if (p.verifyInstrument) {
            val q = screenQuote.value
            if (q == null || !q.isFresh() || q.instrument != order.symbol) {
                return ExecutionResult.Rejected("Ekran pokazuje ${q?.instrument ?: "?"}, zlecenie dotyczy ${order.symbol}")
            }
        }

        if (pkg == ScreenScanner.XTB_PACKAGE) return performXtbEntry(order)
        return ExecutionResult.Rejected("Brak dedykowanego wykonawcy dla $pkg")
    }

    private suspend fun doClosePosition(instrument: String): ExecutionResult {
        openPositionScreen(instrument)?.let { return ExecutionResult.Rejected(it) }
        val close = appRoot()?.let { ScreenScanner.findById(it, "CloseVolumeButton").firstOrNull() }
            ?: return ExecutionResult.Rejected("brak przycisku Zamknij pozycję")
        if (!click(close)) return ExecutionResult.Rejected("nie udało się dotknąć Zamknij pozycję")

        val sheetBtn = awaitById("CloseButton", 3_000)
            ?: return ExecutionResult.Failed(IllegalStateException("Brak arkusza potwierdzenia zamknięcia"))
        val root = appRoot() ?: return ExecutionResult.Failed(IllegalStateException("Brak okna arkusza"))

        val remText = findChildInContainer(root, "RemainingVolumeValue", "ValueTextText")?.text?.toString()
        val remaining = ScreenScanner.parseNumber(remText) ?: if (remText?.trim() == "0") 0.0 else null

        if (remaining != null && remaining > 1e-6) {
            return ExecutionResult.Failed(IllegalStateException("Arkusz nie zamyka całości wolumenu (pozostało $remaining)"))
        }

        if (!click(sheetBtn)) return ExecutionResult.Failed(IllegalStateException("Nie udało się kliknąć potwierdzenia zamknięcia"))

        val gone = withTimeoutOrNull(5_000) {
            while (appRoot()?.let { ScreenScanner.findById(it, "CloseButton").isNotEmpty() } == true) delay(200)
            true
        } ?: false

        return if (gone) ExecutionResult.Submitted("Zamknięto pozycję $instrument w XTB")
        else ExecutionResult.Failed(IllegalStateException("Arkusz zamykania nie zniknął ze stanowiska"))
    }

    private suspend fun performXtbEntry(order: OrderRequest): ExecutionResult {
        val q = screenQuote.value
        val sidePx = q?.let { if (order.side == Side.BUY) it.ask else it.bid }
            ?: return ExecutionResult.Rejected("Brak bieżącej ceny na ekranie XTB")

        val shift = sidePx - order.referencePrice
        val sl = order.stopPrice + shift
        val tp = order.takeProfit?.plus(shift)

        revealById("MarketSizeInput") ?: return ExecutionResult.Rejected("Nie znaleziono pola wolumenu")
        val vol = findChildInContainer(appRoot() ?: return ExecutionResult.Rejected("Brak okna"), "MarketSizeInput", "StepperTextField")
            ?: return ExecutionResult.Rejected("Nie znaleziono edytora wolumenu")

        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, formatQty(order.quantity))
        }
        if (!vol.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) return ExecutionResult.Rejected("Błąd wprowadzania wolumenu")
        delay(300)

        revealById("StopLossContainer") ?: return ExecutionResult.Rejected("Nie odnaleziono sekcji Stop Loss")
        setPriceIn("StopLossContainer", "StopLossToggle", sl)?.let { return ExecutionResult.Rejected("Błąd SL: $it") }

        if (tp != null) {
            revealById("TakeProfitContainer") ?: return ExecutionResult.Rejected("Nie odnaleziono sekcji Take Profit")
            setPriceIn("TakeProfitContainer", "TakeProfitToggle", tp)?.let { return ExecutionResult.Rejected("Błąd TP: $it") }
        }

        val btn = revealById(if (order.side == Side.BUY) "TradingBuyButton" else "TradingSellButton")
            ?: return ExecutionResult.Rejected("Nie znaleziono przycisku zlecenia ${order.side}")
        if (!click(btn)) return ExecutionResult.Rejected("Błąd kliknięcia przycisku ${order.side}")

        val confirm = withTimeoutOrNull(3_000) {
            var n: AccessibilityNodeInfo? = null
            while (n == null) {
                n = appRoot()?.let { findOrderConfirm(it) }
                if (n == null) delay(150)
            }
            n
        }

        if (confirm != null) {
            if (!click(confirm)) {
                return ExecutionResult.Failed(IllegalStateException("Błąd potwierdzenia zlecenia"))
            }
            withTimeoutOrNull(6_000) {
                while (appRoot()?.let { findOrderConfirm(it) } != null) delay(200)
                true
            }
        }
        return ExecutionResult.Submitted("XTB: Otwarto ${order.side} ${formatQty(order.quantity)} ${order.symbol}")
    }

    private fun findChildInContainer(root: AccessibilityNodeInfo, containerId: String, childId: String): AccessibilityNodeInfo? {
        val area = ScreenScanner.findById(root, containerId).firstOrNull()?.let { n -> Rect().also { n.getBoundsInScreen(it) } }
            ?: return null
        val r = Rect()
        return ScreenScanner.findById(root, childId).firstOrNull { n ->
            n.getBoundsInScreen(r)
            area.contains(r.centerX(), r.centerY())
        }
    }

    private suspend fun setPriceIn(containerId: String, toggleId: String, price: Double): String? {
        revealById(containerId) ?: return "brak sekcji"
        var field = appRoot()?.let { findChildInContainer(it, containerId, "StepperTextField") }
        if (field == null) {
            val root = appRoot() ?: return "brak okna"
            val toggleArea = ScreenScanner.findById(root, toggleId).firstOrNull()?.let { n -> Rect().also { n.getBoundsInScreen(it) } }
                ?: return "brak obszaru przełącznika"
            val r = Rect()
            val sw = ScreenScanner.findById(root, "ToggleRowSwitch").firstOrNull { n ->
                n.getBoundsInScreen(r)
                toggleArea.contains(r.centerX(), r.centerY())
            } ?: return "brak włącznika"
            if (!click(sw)) return "nie udało się aktywować opcji"
            delay(500)
            field = appRoot()?.let { findChildInContainer(it, containerId, "StepperTextField") }
                ?: return "pole ceny niedostępne"
        }
        val decimals = field.text?.toString()?.substringAfter('.', "")?.length?.takeIf { it in 1..6 } ?: 2
        val txt = BigDecimal(price).setScale(decimals, RoundingMode.HALF_UP).toPlainString()
        val args = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, txt) }
        if (!field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) return "błąd zapisu ceny"
        delay(300)
        return null
    }

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
                if (area > bestArea) {
                    best = n
                    bestArea = area
                }
            }
            for (i in 0 until n.childCount) n.getChild(i)?.let { queue.add(it) }
        }
        return best
    }

    private suspend fun revealById(id: String): AccessibilityNodeInfo? {
        val plan = listOf(0, 1, 1, 1, -1, -1, -1, -1)
        for (step in plan) {
            val root = appRoot() ?: return null
            ScreenScanner.findById(root, id).firstOrNull()?.let { return it }
            if (step == 0) continue
            val scroller = findScrollable(root) ?: return null
            scroller.performAction(
                if (step > 0) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
            )
            delay(400)
        }
        return null
    }

    private suspend fun restoreTicketTop() {
        val root = appRoot() ?: return
        if (root.packageName?.toString() != ScreenScanner.XTB_PACKAGE) return
        val titleVisible = ScreenScanner.findById(root, "ElementTitleHorizontalText").isNotEmpty()
        val buyVisible = ScreenScanner.findById(root, "TradingBuyButton").isNotEmpty()
        if (titleVisible && buyVisible) return

        repeat(4) {
            val r = appRoot() ?: return
            val scroller = findScrollable(r) ?: return
            scroller.performAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)
            delay(250)
            if (ScreenScanner.findById(r, "ElementTitleHorizontalText").isNotEmpty()) return
        }
    }

    suspend fun xtbPositionExists(instrument: String): Boolean? = withContext(Dispatchers.Main.immediate) {
        try {
            uiMutex.withLock { probeInternal(instrument) }
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            null
        }
    }

    private suspend fun probeInternal(instrument: String): Boolean? {
        val root = appRoot() ?: return null
        if (root.packageName?.toString() != ScreenScanner.XTB_PACKAGE) return null
        restoreTicketTop()
        val found = revealById("PositionRow0")
        restoreTicketTop()
        return found != null
    }

    private suspend fun selfHeal() {
        if (!selfHealEnabled || _actionActive.value) {
            staleSince = 0L
            return
        }
        if (!uiMutex.tryLock()) return
        try {
            val root = appRoot() ?: return
            if (root.packageName?.toString() != ScreenScanner.XTB_PACKAGE) return
            val stale = findOrderConfirm(root) != null || ScreenScanner.findById(root, "CloseButton").isNotEmpty()
            if (stale) {
                val now = SystemClock.uptimeMillis()
                if (staleSince == 0L) staleSince = now
                else if (now - staleSince > 8_000) {
                    performGlobalAction(GLOBAL_ACTION_BACK)
                    staleSince = 0L
                    Log.i(TAG, "Reset zawieszonego widoku XTB")
                }
            } else {
                staleSince = 0L
            }
        } finally {
            uiMutex.unlock()
        }
    }

    private fun keepXtbFront() {
        if (!selfHealEnabled || !getSharedPreferences("ui", MODE_PRIVATE).getBoolean("keep_xtb_front", true)) return
        val since = notVisibleSince
        val now = SystemClock.uptimeMillis()
        if (since == 0L || now - since < 20_000 || now - lastFrontAttempt < 30_000) return
        lastFrontAttempt = now
        val intent = packageManager.getLaunchIntentForPackage(ScreenScanner.XTB_PACKAGE) ?: return
        intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { startActivity(intent) }
    }

    private suspend fun awaitById(id: String, timeoutMs: Long): AccessibilityNodeInfo? = withTimeoutOrNull(timeoutMs) {
        var found: AccessibilityNodeInfo? = null
        while (found == null) {
            found = appRoot()?.let { ScreenScanner.findById(it, id).firstOrNull() }
            if (found == null) delay(150)
        }
        found
    }

    private fun findOrderConfirm(root: AccessibilityNodeInfo): AccessibilityNodeInfo? =
        root.findAccessibilityNodeInfosByText("Potwierdź zlecenie").firstOrNull { n ->
            n.isVisibleToUser && (n.text?.toString() ?: n.contentDescription?.toString() ?: "")
                .startsWith("Potwierdź zlecenie", ignoreCase = true)
        }

    private suspend fun openPositionScreen(instrument: String): String? {
        val root = appRoot() ?: return "brak okna"
        if (root.packageName?.toString() != ScreenScanner.XTB_PACKAGE) return "XTB nieaktywne"
        if (ScreenScanner.findById(root, "ModifyPositionButton").isNotEmpty()) return null
        val row = revealById("PositionRow0") ?: return "nie znaleziono pozycji $instrument"
        if (!click(row)) return "błąd kliknięcia pozycji"
        awaitById("ModifyPositionButton", 3_000) ?: return "ekran pozycji nie otworzył się"
        return null
    }

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
            delay(80)
            return suspendCancellableCoroutine { cont ->
                val gesture = GestureDescription.Builder()
                    .addStroke(GestureDescription.StrokeDescription(Path().apply { moveTo(x, y) }, 0, 60))
                    .build()
                val accepted = dispatchGesture(gesture, object : GestureResultCallback() {
                    override fun onCompleted(gestureDescription: GestureDescription?) {
                        if (cont.isActive) cont.resume(true)
                    }
                    override fun onCancelled(gestureDescription: GestureDescription?) {
                        if (cont.isActive) cont.resume(false)
                    }
                }, null)
                if (!accepted && cont.isActive) cont.resume(false)
            }
        } finally {
            _gestureActive.value = false
        }
    }
}
