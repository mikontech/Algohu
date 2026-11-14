package com.example.algohud.execution

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
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
    private val executorTimeoutMs: Long = 75_000, // pełne wejście w XTB (przewijanie, SL, TP, potwierdzenie) trwa 15–40 s
    /** Dodatkowa bramka AUTO (np. wyniki papierowe); null = OK. */
    private val autoGate: () -> String? = { null },
    /** Czy pozycja instrumentu istnieje w aplikacji brokera: true / false / null = nie da się ustalić. */
    private val reverseEntries: () -> Boolean = { false },
    private val entryGate: (OrderRequest) -> String? = { null },
    private val positionProbe: suspend (String) -> Boolean? = { null }
) {
    private var pendingAt = 0L
    private val _mode = MutableStateFlow(initialMode)
    val mode: StateFlow<ExecutionMode> = _mode.asStateFlow()

    private val _events = MutableSharedFlow<String>(extraBufferCapacity = 64)
    val events: SharedFlow<String> = _events.asSharedFlow()

    private val _pending = MutableStateFlow<OrderRequest?>(null)
    val pendingConfirmation: StateFlow<OrderRequest?> = _pending.asStateFlow()

    private val queue = OperationQueue()
    private val _operation = MutableStateFlow(OperationStatus())
    val operation: StateFlow<OperationStatus> = _operation.asStateFlow()
    fun stopEntries() { risk.pauseEntries(true); rejectPending() }
    suspend fun closeBotPosition(symbol: String, openedAt: Long) {
        stopEntries()
        for (order in risk.flattenOrders().filter { it.symbol == symbol })
            submit(order.copy(reason = "ZAMKNIĘCIE Z HUD", expectedPositionAt = openedAt), bypassConfirmation = true)
    }

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
    fun prepareEntry(order: OrderRequest): OrderRequest =
        com.example.algohud.core.EntryDirection.prepare(order, reverseEntries())

    suspend fun submit(request: OrderRequest, bypassConfirmation: Boolean = false): ExecutionResult {
        val order = prepareEntry(request)
        if (_mode.value == ExecutionMode.CONFIRM && !bypassConfirmation && !order.reduceOnly) {
            if (_pending.value != null) return ExecutionResult.Rejected("Jest już propozycja do potwierdzenia")
            if (risk.entriesPaused.value) return ExecutionResult.Rejected("STOP WEJŚĆ aktywny")
            _pending.value = order
            pendingAt = System.currentTimeMillis()
            return ExecutionResult.Rejected("Oczekuje na potwierdzenie")
        }
        val requestedMode = _mode.value
        return queue.run(order.clientOrderId, "${order.symbol}:${order.reduceOnly}", if (order.reduceOnly) 90_000 else 5_000) {
            if (order.expectedPositionAt != null && risk.position(order.symbol)?.openedAt != order.expectedPositionAt)
                return@run ExecutionResult.Rejected("Pozycja zmieniła się – anulowano zamknięcie")
            if (_mode.value != requestedMode) return@run ExecutionResult.Rejected("Tryb zmienił się w kolejce")
            if (!order.reduceOnly && _mode.value != ExecutionMode.DRY_RUN) entryGate(order)?.let { why ->
                _operation.value = OperationStatus(order.clientOrderId, order.symbol, why, error = true)
                return@run ExecutionResult.Rejected(why)
            }
            when (val decision = risk.evaluate(order)) {
                is RiskManager.Decision.Rejected -> {
                    _operation.value = OperationStatus(order.clientOrderId, order.symbol, decision.reason, error = true)
                    return@run ExecutionResult.Rejected(decision.reason)
                }
                else -> Unit
            }
            val side = if (order.side == Side.BUY) "LONG" else "SHORT"
            _operation.value = OperationStatus(order.clientOrderId, order.symbol,
                if (order.reduceOnly) "ZAMYKAM…" else "OTWIERAM $side…", active = true)
            try {
                val result = execute(order)
                val text = when (result) {
                    is ExecutionResult.Filled -> if (order.reduceOnly) "ZAMKNIĘTO" else "OTWARTO $side"
                    is ExecutionResult.Submitted -> if (order.reduceOnly) "ZAMKNIĘCIE POTWIERDZONE" else "WYSŁANO $side • weryfikacja pozycji"
                    is ExecutionResult.Rejected -> result.reason
                    is ExecutionResult.Failed -> "STAN NIEZNANY • sprawdź XTB"
                }
                _operation.value = OperationStatus(order.clientOrderId, order.symbol, text,
                    error = result is ExecutionResult.Failed || result is ExecutionResult.Rejected)
                result
            } catch (e: CancellationException) {
                _operation.value = OperationStatus(order.clientOrderId, order.symbol, "PRZERWANO • sprawdź XTB", error = true)
                throw e
            } catch (e: Exception) {
                risk.engageKillSwitch("Nieznany stan operacji")
                _operation.value = OperationStatus(order.clientOrderId, order.symbol, "STAN NIEZNANY • sprawdź XTB", error = true)
                ExecutionResult.Failed(e)
            }
        }
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
            // Bez przełączania na DRY_RUN: chwilowy stan ekranu (np. po nieudanym wejściu) nie może wyłączać bota na stałe.
            log("⏳ Wykonawca chwilowo niegotowy ($reason) – pomijam to zlecenie, tryb ${_mode.value} bez zmian")
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
                return recoverFromFailure(order, result)
            }
        }
        return result
    }

    /**
     * Błąd w trakcie obsługi XTB nie oznacza automatycznie KILL: sprawdzamy w aplikacji, co naprawdę się stało.
     * KILL tylko wtedy, gdy stanu nie da się ustalić.
     */
    private suspend fun recoverFromFailure(order: OrderRequest, failed: ExecutionResult.Failed): ExecutionResult {
        val exists = runCatching { positionProbe(order.symbol) }.getOrNull()
        return when {
            !order.reduceOnly && exists == true -> {
                risk.onFill(order, order.referencePrice, order.quantity) // SL jest wpisany PRZED kliknięciem Buy/Sell
                log("🔎 Po błędzie: pozycja ${order.symbol} jest w XTB – zaksięgowana")
                ExecutionResult.Submitted("zaksięgowano po weryfikacji")
            }
            !order.reduceOnly && exists == false -> {
                log("🔎 Po błędzie: pozycji ${order.symbol} w XTB nie ma – zlecenie nie zostało otwarte (bez KILL)")
                ExecutionResult.Rejected("nie otwarto (zweryfikowano)")
            }
            order.reduceOnly && exists == false -> {
                risk.onFill(order, order.referencePrice, order.quantity)
                log("🔎 Po błędzie: pozycja ${order.symbol} jest już zamknięta w XTB – zaksięgowana")
                ExecutionResult.Submitted("zamknięcie zweryfikowane")
            }
            order.reduceOnly && exists == true -> {
                log("⚠️ Zamknięcie ${order.symbol} nie powiodło się; pozycja otwarta (chroni ją SL w XTB) – ponowię")
                ExecutionResult.Rejected("pozycja nadal otwarta – ponowię")
            }
            else -> {
                risk.engageKillSwitch("Błąd wykonania, stanu XTB nie da się zweryfikować: ${failed.error.message}")
                failed
            }
        }
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

        private val _uiEvents = MutableSharedFlow<String>(extraBufferCapacity = 16)
        /** Komunikaty dla HUD (np. wynik weryfikacji SL/TP i przywracania suwaków). */
        val uiEvents: SharedFlow<String> = _uiEvents.asSharedFlow()

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
        /** Samonaprawa ekranu i przywracanie XTB na wierzch – tylko w trybie AUTO (ustawia nakładka). */
        @Volatile var selfHealEnabled: Boolean = false
        /** Ostatni alarm (czas, treść) – do uproszczonego HUD. */
        @Volatile var lastAlert: Pair<Long, String>? = null
        /** Instrument, którego bilet bot ma mieć otwarty w XTB (ustawia orkiestrator), i czy jest otwarta pozycja. */
        @Volatile var wantedInstrument: String? = null
        @Volatile var wantedUrgent: Boolean = false

        /**
         * Autonomia nawigacji we WSZYSTKICH trybach (także DRY_RUN): XTB wraca na wierzch, bot wraca na bilet GOLD,
         * zamyka wiszące okna i ekran błędu. Zlecenia dalej tylko w AUTO. Ustawia nakładka z prefs „nav_autonomy”.
         */
        @Volatile var navAutonomy: Boolean = true
        /** Pauza autonomii (np. „chcę chwilę użyć telefonu”) – do tej chwili (System.currentTimeMillis). */
        @Volatile var navPausedUntil: Long = 0L
        /** Zapasowy nawigator wizyjny (AI) – wołany, gdy zwykłe szukanie biletu zawiedzie. Ustawia nakładka. */
        @Volatile var aiNavigator: (suspend (target: String) -> Boolean)? = null
        /** Kill-switch: przerywa trwającą nawigację AI (sprawdzane przed każdym krokiem). */
        @Volatile var navAbort: () -> Boolean = { false }
        /** Jedna linia: co robi samonaprawa widoku (do HUD i świadomości agentów). */
        val protectionStatus = MutableStateFlow(OperationStatus())
        data class BrokerReading(val quote: ScreenQuote, val row: PositionRowInfo)
        val brokerReading = MutableStateFlow<BrokerReading?>(null)
        @Volatile var entryBlocked: (() -> Boolean)? = null
        @Volatile var entryCheck: ((OrderRequest) -> String?)? = null
        @Volatile var hudObstacles: List<Rect> = emptyList()
        @Volatile var navStatus: String = "—"
        /** Pakiet aplikacji, która jest teraz na wierzchu (gdy nie XTB) – do świadomości agentów. */
        @Volatile var foregroundPackage: String? = null
        /** Aplikacje, z których bot NIE wyciąga użytkownika na siłę (ustawienia, instalator, Algo HUD, okna systemowe). */
        private val NO_YANK_PREFIXES = listOf(
            "com.example.algohud", "com.android.settings", "com.android.packageinstaller", "com.google.android.packageinstaller",
            "com.android.systemui", "android", "com.google.android.permissioncontroller", "com.android.permissioncontroller"
        )
        private const val AI_NAV_MAX_PER_HOUR = 6

        private val _gestureActive = MutableStateFlow(false)
        /** HUD obserwuje to i chowa się na czas gestu, żeby nie przechwycić dotyku. */
        val gestureActive: StateFlow<Boolean> = _gestureActive.asStateFlow()

        private const val TAG = "AutoExecutionService"
        private const val ALERT_CHANNEL = "algo_hud_alert"
        private const val ALERT_NOTIF_ID = 4242
        private const val NAV_WAIT_URGENT_MS = 5_000L
        private const val NAV_WAIT_MS = 20_000L
        private const val NAV_RETRY_MS = 20_000L
        private const val NAV_SCROLLS = 10
        /** Podpis instrumentu w XTB (wiersz listy/wyniku i nagłówek biletu). */
        private val SUBTITLES = mapOf("GOLD" to "Troy Ounce of Gold")
        private val TICKET_MARKERS = listOf("Otwarte pozycje", "Stop Loss", "Take Profit", "Opis instrumentu")

        /** Diagnostyka: ostatnio widziane okno nagrywania (do skopiowania z aplikacji, gdy auto-akceptacja nie trafi). */
        @Volatile var lastCaptureDump: String? = null
        /** Do kiedy uznajemy, że trwa sesja zdalna (tarcza dotyku jest wtedy wstrzymana). */
        @Volatile var remoteSessionUntil: Long = 0L
        /** Diagnostyka okna prośby o połączenie AnyDesk + ostatni komunikat (akceptacja / odmowa). */
        @Volatile var lastRemoteDump: String? = null
        @Volatile var lastRemoteNote: String? = null
        private val REMOTE_HINTS = listOf(
            "połącz", "polacz", "połączeni", "żądanie", "zadanie", "przychodz", "sesj", "dostęp",
            "connect", "request", "incoming", "session", "access", "wants to"
        )
        private val REMOTE_ACCEPT = listOf("Akceptuj", "Zaakceptuj", "Przyjmij", "Zezwól", "Zezwalaj", "Accept", "Allow")
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
        // Ponownie ustaw flagi również po aktualizacji APK z działającą usługą.
        serviceInfo = serviceInfo.apply {
            flags = flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                android.accessibilityservice.AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
            eventTypes = eventTypes or AccessibilityEvent.TYPE_WINDOWS_CHANGED
        }
        instance = this
        com.example.algohud.bot.Ntfy.init(this)
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

    /**
     * Prośba o połączenie AnyDesk (okno aplikacji AnyDesk albo powiadomienie): akceptuje TYLKO, gdy w oknie
     * widać ID lub alias z listy zaufanych. Obcy ID → nic nie klika, tylko zapisuje diagnostykę i ostrzega.
     */
    private fun maybeAcceptRemoteRequest() {
        val ui = getSharedPreferences("ui", MODE_PRIVATE)
        val enabled = ui.getBoolean("auto_anydesk_accept", false)
        val now = SystemClock.uptimeMillis()
        if (now - lastRemoteAttempt < 700) return
        lastRemoteAttempt = now
        val allowed = allowedRemote()
        val roots = runCatching {
            windows.mapNotNull { it.root }.filter { r ->
                val p = r.packageName?.toString() ?: ""
                p.startsWith("com.anydesk") || p == "com.android.systemui"
            }
        }.getOrDefault(emptyList())
        for (root in roots) {
            val nodes = textNodes(root)
            val texts = nodes.map { it.second }
            val fromSystemUi = root.packageName?.toString() == "com.android.systemui"
            if (fromSystemUi && texts.none { it.contains("AnyDesk", ignoreCase = true) }) continue
            if (texts.none { t -> REMOTE_HINTS.any { t.contains(it, ignoreCase = true) } }) continue
            val accept = nodes.firstOrNull { (_, t) -> isAcceptLabel(t) }?.first
            if (accept == null) {
                // Samo powiadomienie (bez przycisku) – otwórz je, żeby AnyDesk pokazał okno z „Akceptuj”.
                if (fromSystemUi && enabled) {
                    val note = nodes.firstOrNull { (_, t) -> t.contains("AnyDesk", ignoreCase = true) }?.first
                    var n: AccessibilityNodeInfo? = note
                    while (n != null && !n.isClickable) n = n.parent
                    if (n?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true) remoteNote("📡 AnyDesk: otwieram powiadomienie o połączeniu…")
                } else remoteNote("📡 AnyDesk: widzę okno połączenia, ale nie znalazłem przycisku akceptacji – skopiuj diagnostykę w aplikacji")
                lastRemoteDump = ScreenScanner.dump(root)
                continue
            }
            lastRemoteDump = ScreenScanner.dump(root)
            val caller = callerText(texts)
            if (!enabled) { remoteNote("ℹ️ AnyDesk: prośba o połączenie ($caller), ale auto-akceptacja jest wyłączona"); return }
            if (!isTrusted(allowed, texts) && fromSystemUi) {
                // Belka z góry często nie pokazuje ID – otwieramy ją (nie akceptując); ID sprawdzimy w oknie AnyDesk.
                val note = nodes.firstOrNull { (_, t) -> t.contains("AnyDesk", ignoreCase = true) }?.first
                var b: AccessibilityNodeInfo? = note
                while (b != null && !b.isClickable) b = b.parent
                if (b != null && b != accept && b.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                    remoteNote("📡 AnyDesk: belka bez zaufanego ID («$caller») – otwieram okno prośby, tam sprawdzę ID")
                    return
                }
            }
            if (!isTrusted(allowed, texts)) {
                remoteNote("⛔ AnyDesk: prośba od «$caller» – nie ma na liście zaufanych (${allowed.joinToString().ifEmpty { "lista pusta" }}). Nie wpuszczam.")
                return
            }
            var n: AccessibilityNodeInfo? = accept
            while (n != null && !n.isClickable) n = n.parent
            if ((n ?: accept).performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                remoteSessionUntil = System.currentTimeMillis() + 30 * 60_000 // tarcza dotyku nie blokuje sterowania
                remoteNote("✅ AnyDesk: zaakceptowano połączenie od «$caller»", force = true)
            } else remoteNote("⚠️ AnyDesk: kliknięcie „Akceptuj” nie zadziałało – skopiuj diagnostykę w aplikacji")
            return
        }
    }

    /** Powiadomienie AnyDesk: akcja „Akceptuj” wprost z powiadomienia albo otwarcie okna prośby. */
    private fun handleAnydeskNotification(event: AccessibilityEvent) {
        val ui = getSharedPreferences("ui", MODE_PRIVATE)
        val notif = event.parcelableData as? android.app.Notification ?: return
        val ex = notif.extras
        val texts = listOfNotNull(
            ex?.getCharSequence(android.app.Notification.EXTRA_TITLE), ex?.getCharSequence(android.app.Notification.EXTRA_TEXT),
            ex?.getCharSequence(android.app.Notification.EXTRA_BIG_TEXT), notif.tickerText
        ).map { it.toString() } + event.text.map { it.toString() }
        val actions = notif.actions.orEmpty()
        lastRemoteDump = "# powiadomienie AnyDesk\n" + texts.joinToString("\n") + "\n# akcje: " + actions.joinToString { it.title?.toString() ?: "?" }
        if (texts.none { t -> REMOTE_HINTS.any { t.contains(it, ignoreCase = true) } } && actions.none { isAcceptLabel(it.title?.toString() ?: "") }) return
        val caller = callerText(texts)
        if (!ui.getBoolean("auto_anydesk_accept", false)) { remoteNote("ℹ️ AnyDesk: powiadomienie o połączeniu ($caller), auto-akceptacja wyłączona"); return }
        val action = actions.firstOrNull { isAcceptLabel(it.title?.toString() ?: "") }
        if (action != null) {
            // W powiadomieniu nie zawsze widać ID – wtedy otwieramy okno i tam sprawdzamy listę zaufanych.
            if (isTrusted(allowedRemote(), texts)) {
                runCatching { action.actionIntent.send() }.onSuccess {
                    remoteSessionUntil = System.currentTimeMillis() + 30 * 60_000
                    remoteNote("✅ AnyDesk: zaakceptowano z powiadomienia («$caller»)", force = true)
                    return
                }
            }
        }
        runCatching { notif.contentIntent?.send() }.onSuccess { remoteNote("📡 AnyDesk: otwieram okno prośby o połączenie…") }
    }

    private fun allowedRemote(): List<String> =
        (getSharedPreferences("ui", MODE_PRIVATE).getString("anydesk_allow", "") ?: "")
            .split(',', ';', '\n').map { it.trim() }.filter { it.isNotEmpty() }

    private fun isAcceptLabel(t: String): Boolean {
        val s = t.trim()
        return s.length <= 30 && REMOTE_ACCEPT.any { s.startsWith(it, ignoreCase = true) }
    }

    private fun isTrusted(allowed: List<String>, texts: List<String>): Boolean = allowed.any { a ->
        val digits = a.filter(Char::isDigit)
        texts.any { t ->
            (digits.length >= 6 && t.filter(Char::isDigit).contains(digits)) ||
                (a.length >= 3 && t.contains(a, ignoreCase = true))
        }
    }

    private fun callerText(texts: List<String>): String =
        (texts.firstOrNull { it.count(Char::isDigit) >= 9 } ?: texts.firstOrNull { '@' in it } ?: texts.take(3).joinToString(" | ")).take(60)

    private fun remoteNote(text: String, force: Boolean = false) {
        lastRemoteNote = text
        val now = SystemClock.uptimeMillis()
        if (!force && text == lastRemoteSent && now - lastRemoteWarn < 30_000) return
        lastRemoteSent = text; lastRemoteWarn = now
        Log.i(TAG, text)
        _uiEvents.tryEmit(text)
    }
    private var lastRemoteSent: String? = null
    private var lastRemoteAttempt = 0L
    private var lastRemoteWarn = 0L

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
        if (event?.eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED) { scanNow(); return }
        val pkg = event?.packageName?.toString() ?: return
        if (pkg.startsWith("com.anydesk")) {
            if (event.eventType == AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED) handleAnydeskNotification(event)
            maybeAcceptRemoteRequest(); return
        }
        if (pkg == "com.android.systemui" || pkg == "android") { maybeAcceptCapture(pkg); maybeAcceptRemoteRequest(); return }
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
            foregroundPackage = pkg
            brokerReading.value = null
            if (notVisibleSince == 0L) notVisibleSince = now
            diag = "nie widzę okna XTB" + (if (root != null) " (na wierzchu: $pkg)" else "") +
                (if (lastTargetEventAt > 0) ", ostatni sygnał z XTB ${(now - lastTargetEventAt) / 1000} s temu" else ", brak sygnałów z XTB")
            return false
        }
        notVisibleSince = 0L
        foregroundPackage = null
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
        // XTB odświeża Sell i Buy osobno: w szybkim ruchu przez ułamek sekundy widać nową cenę Sell i starą Buy
        // (Buy < Sell). Taki odczyt pomijamy – zostaje poprzedni, a bramka danych nie blokuje wejść przez chwilowy artefakt.
        val qb = quote?.bid
        val qa = quote?.ask
        val torn = qb != null && qa != null && qa < qb
        if (torn) {
            tornReads++
            if (tornReads % 20 == 1L) Log.i(TAG, "Pominięty rozjechany odczyt Sell/Buy ($qb / $qa), łącznie $tornReads")
            return true
        }
        if (quote != null) {
            _screenQuote.value = quote
            val obstacles = mutableListOf<Rect>()
            val nodes = ArrayDeque<AccessibilityNodeInfo>().apply { add(root) }
            var count = 0
            while (nodes.isNotEmpty() && count++ < 1500) {
                val n = nodes.removeFirst()
                val id = n.viewIdResourceName?.substringAfterLast('/').orEmpty()
                val isChart = id.contains("chart", true) || n.className?.toString()?.contains("chart", true) == true
                val isControl = id in setOf("TradingBuyButton", "TradingSellButton", "ModifyPositionButton", "CloseVolumeButton", "StepperTextField")
                if (n.isVisibleToUser && (isChart || isControl)) {
                    val rect = Rect().also { n.getBoundsInScreen(it) }
                    if (isChart) rect.left = rect.left + rect.width() * 2 / 3
                    if (!rect.isEmpty) obstacles.add(rect)
                }
                for (i in 0 until n.childCount) n.getChild(i)?.let { nodes.add(it) }
            }
            hudObstacles = obstacles
            brokerReading.value = if (quote.instrument != null) runCatching {
                readPositionRows(root).singleOrNull()?.takeIf { it.signature == profitAnchors[quote.instrument] }
                    ?.let { BrokerReading(quote, it) }
            }.getOrNull() else null
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
                runCatching { recoverFromXtbError() }
                runCatching { findWantedInstrument() }
                runCatching { selfHeal() }
                runCatching { maybeAcceptRemoteRequest() }
                runCatching { keepXtbFront() }
                delay(1_000)
            }
        }
    }

    private val serviceScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.Main)
    private var pollJob: kotlinx.coroutines.Job? = null

    /** Na czas klikania w XTB tarcza musi być uchylona. */
    private suspend fun <T> withAction(block: suspend () -> T): T = uiMutex.withLock {
        _actionActive.value = true
        try {
            delay(350) // czas na uchylenie tarczy
            val result = block()
            runCatching { restoreTicketTop() } // po każdej akcji bilet wraca na górę – bot dalej widzi ceny
            result
        } finally {
            _actionActive.value = false
        }
    }

    /** Jedna operacja na ekranie XTB naraz (akcje bota, weryfikacja pozycji, samonaprawa). */
    private val uiMutex = Mutex()


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
        if (pkg == ScreenScanner.XTB_PACKAGE) return "XTB"
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
    private val protectionMutex = Mutex()
    suspend fun setProtection(instrument: String, sl: Double?, tp: Double?): ExecutionResult =
        withContext(Dispatchers.Main.immediate) {
            try {
                if (!protectionMutex.tryLock()) return@withContext ExecutionResult.Rejected("Zapis SL/TP już trwa")
                val id = java.util.UUID.randomUUID().toString()
                protectionStatus.value = OperationStatus(id, instrument, "SL: ZAPISUJĘ…", active = true)
                try {
                    val result = withAction { doSetProtection(instrument, sl, tp) }
                    protectionStatus.value = OperationStatus(id, instrument,
                        if (result is ExecutionResult.Submitted || result is ExecutionResult.Filled) "SL: ZAPISANY ✓" else "SL: BRAK POTWIERDZENIA",
                        error = result !is ExecutionResult.Submitted && result !is ExecutionResult.Filled)
                    result
                } finally {
                    if (protectionStatus.value.active) protectionStatus.value = OperationStatus(id, instrument, "SL: BRAK POTWIERDZENIA", error = true)
                    protectionMutex.unlock()
                }
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
            ?.text?.toString()?.trim()?.split(Regex("\\s+"))?.firstOrNull()
        if (title != instrument) return "na ekranie jest ${title ?: "?"}, a pozycja dotyczy $instrument"
        if (ScreenScanner.findById(root, "ModifyPositionButton").isEmpty()) {
            return "otwórz w XTB ekran pozycji $instrument (Take Profit / Stop Loss / Zamknij pozycję)"
        }
        return null
    }

    private suspend fun doSetProtection(instrument: String, sl: Double?, tp: Double?): ExecutionResult {
        val before = appRoot()?.let { readPositionRows(it) }?.singleOrNull()
            ?: return ExecutionResult.Rejected("brak jednoznacznego odczytu pozycji przed zmianą SL/TP")
        if (before.signature != profitAnchors[instrument]) return ExecutionResult.Rejected("pozycja niepotwierdzona przez bota")
        if (sl != null && before.sl != null && before.side != null && (sl - before.sl) * before.side.sign < -0.005)
            return ExecutionResult.Rejected("nie cofam SL względem poziomu w XTB")
        openPositionScreen(instrument)?.let { return ExecutionResult.Rejected(it) }
        tp?.let { price -> setPriceIn("TakeProfitInput", "TakeProfitToggle", price)?.let { return ExecutionResult.Rejected("TP: $it") } }
        sl?.let { price -> setPriceIn("StopLossInput", "StopLossToggle", price)?.let { return ExecutionResult.Rejected("SL: $it") } }
        val modify = appRoot()?.let { ScreenScanner.findById(it, "ModifyPositionButton").firstOrNull() }
            ?: return ExecutionResult.Rejected("brak przycisku Modyfikuj pozycję")
        if (!click(modify)) return ExecutionResult.Rejected("nie udało się dotknąć Modyfikuj pozycję")
        delay(1_200)
        // Wpis w formularzu nie jest dowodem zapisu u brokera.
        repeat(4) {
            val row = appRoot()?.let { readPositionRows(it) }?.singleOrNull { it.signature == before.signature }
            if (row != null && (sl == null || row.sl?.let { abs(it - sl) <= 0.011 } == true) &&
                (tp == null || row.tp?.let { abs(it - tp) <= 0.011 } == true)) {
                return ExecutionResult.Submitted("SL/TP potwierdzone przy pozycji XTB")
            }
            if (it == 0) backStayInXtb()
            delay(500)
        }
        return ExecutionResult.Rejected("zmiana wysłana, ale brak potwierdzenia SL/TP przy pozycji XTB")
    }

    private suspend fun doClosePosition(instrument: String): ExecutionResult {
        // Re-read the ticket and exact bot-owned row before opening the close sheet.
        if (appRoot()?.let { ScreenScanner.findById(it, "ModifyPositionButton").isNotEmpty() } == true) backStayInXtb()
        revealById("PositionRow0") ?: return ExecutionResult.Rejected("brak wiersza pozycji do potwierdzenia")
        val rootBefore = appRoot() ?: return ExecutionResult.Rejected("brak okna XTB")
        val rowBefore = readPositionRows(rootBefore).singleOrNull()
        if (xtbTitle(rootBefore) != instrument || rowBefore == null || rowBefore.signature != profitAnchors[instrument])
            return ExecutionResult.Rejected("nie potwierdzono tożsamości pozycji bota – nie zamykam ręcznej pozycji")
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
        if (gone && probeInternal(instrument) == false) {
            delay(500)
            if (probeInternal(instrument) == false) {
                profitAnchors.remove(instrument)
                brokerReading.value = null
                return ExecutionResult.Submitted("Zamknięcie $instrument potwierdzone w XTB")
            }
        }
        return ExecutionResult.Failed(IllegalStateException("brak potwierdzenia zamknięcia – sprawdź XTB"))
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
        centerOn(toggleId)
        var field = revealField(containerId)
        if (field == null && readEnabled(containerId, toggleId)) return "suwak włączony, ale pole ceny niewidoczne"
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
            delay(300)
        }
        return null
    }

    /**
     * Doprowadza na ekran kontener RAZEM z jego polem StepperTextField.
     * revealById zwraca kontener, gdy wystaje choćby krawędzią – pole w środku bywa wtedy
     * niewidoczne (albo pod przyklejonym nagłówkiem XTB). centerOn dociąga go na środek ekranu.
     */
    private suspend fun revealField(containerId: String): AccessibilityNodeInfo? {
        centerOn(containerId) ?: return null
        return fieldIn(containerId)
    }

    /**
     * revealById + dociągnięcie elementu do „bezpiecznego pasa” ekranu: poniżej przyklejonego
     * nagłówka (tytuł/cena XTB) i HUD-a, powyżej dolnej krawędzi. Przewija krótkim, wolnym
     * przeciągnięciem o wyliczoną odległość – SCROLL_FORWARD/BACKWARD skacze o cały ekran
     * i potrafi przeskoczyć element. Zwraca węzeł (świeży) albo null, gdy go nie ma.
     */
    private suspend fun centerOn(id: String): AccessibilityNodeInfo? {
        revealById(id) ?: return null
        val n = Rect(); val sc = Rect()
        repeat(5) {
            val root = appRoot() ?: return null
            val node = ScreenScanner.findById(root, id).firstOrNull() ?: return revealById(id)
            val scroller = findScrollable(root) ?: return node
            node.getBoundsInScreen(n); scroller.getBoundsInScreen(sc)
            val h = sc.height().toFloat()
            if (h <= 0f) return node
            val bandTop = sc.top + h * 0.30f
            val bandBottom = sc.bottom - h * 0.15f
            if (n.top >= bandTop && n.bottom <= bandBottom) return node          // cały w pasie
            if (n.height() > bandBottom - bandTop && n.top >= bandTop) return node // wyższy niż pas – góra widoczna
            // Przesunięcie treści: środek elementu → środek pasa (dodatnie = treść w górę).
            val delta = (n.exactCenterY() - (bandTop + bandBottom) / 2f).coerceIn(-h * 0.5f, h * 0.5f)
            if (kotlin.math.abs(delta) < 8f) return node
            val x = sc.exactCenterX() // środek: krawędź ekranu = systemowy gest „Wstecz”
            val y0 = if (delta > 0) sc.bottom - h * 0.12f else sc.top + h * 0.35f
            if (!drag(x, y0, y0 - delta)) return node
            delay(250)
        }
        return appRoot()?.let { ScreenScanner.findById(it, id).firstOrNull() }
    }

    /** Wolne pionowe przeciągnięcie (bez „flinga”), HUD schowany na czas gestu. */
    private suspend fun drag(x: Float, fromY: Float, toY: Float): Boolean {
        _gestureActive.value = true
        try {
            delay(80)
            return suspendCancellableCoroutine { cont ->
                val path = Path().apply { moveTo(x, fromY); lineTo(x, toY) }
                val gesture = GestureDescription.Builder()
                    .addStroke(GestureDescription.StrokeDescription(path, 0, 350))
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

    // ───────────── Samonaprawa i weryfikacja stanu w XTB ─────────────

    private var tornReads = 0L
    private var staleSince = 0L
    private var notVisibleSince = 0L
    private var lastFrontAttempt = 0L
    private var frontAttempts = 0

    /** Samonaprawa widoku: AUTO albo włączona autonomia nawigacji, i nie w pauzie użytkownika. */
    private fun autonomyOn(): Boolean =
        (selfHealEnabled || navAutonomy) && System.currentTimeMillis() > navPausedUntil

    /** Przewija bilet z powrotem na górę (tytuł instrumentu + przyciski Sell/Buy widoczne) – inaczej bot „ślepnie”. */
    private suspend fun restoreTicketTop() {
        repeat(8) {
            val root = appRoot() ?: return
            if (root.packageName?.toString() != ScreenScanner.XTB_PACKAGE) return
            if (ScreenScanner.findById(root, "ModifyPositionButton").isNotEmpty()) return // ekran pozycji – nie ruszamy
            val titleVisible = ScreenScanner.findById(root, "ElementTitleHorizontalText").isNotEmpty()
            val buyVisible = ScreenScanner.findById(root, "TradingBuyButton").isNotEmpty()
            if (titleVisible && buyVisible) return
            val isTicket = listOf("TradingBuyButton", "MarketSizeInput", "StopLossContainer", "TakeProfitContainer", "PositionRow0")
                .any { ScreenScanner.findById(root, it, visibleOnly = false).isNotEmpty() }
            if (!isTicket) return // to nie bilet
            val scroller = findScrollable(root) ?: return
            scroller.performAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)
            delay(350)
        }
    }

    /**
     * Czy w XTB istnieje otwarta pozycja instrumentu? true / false / null (nie da się ustalić).
     * Zamyka po drodze „wiszące” okna (potwierdzenie zlecenia, arkusz zamknięcia, ekran pozycji).
     */
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
        val first = appRoot() ?: return null
        if (first.packageName?.toString() != ScreenScanner.XTB_PACKAGE) return null
        repeat(3) {
            val r = appRoot() ?: return null
            val stale = findOrderConfirm(r) != null ||
                ScreenScanner.findById(r, "CloseButton").isNotEmpty() ||
                ScreenScanner.findById(r, "ModifyPositionButton").isNotEmpty()
            if (stale) {
                performGlobalAction(GLOBAL_ACTION_BACK)
                delay(900)
            }
        }
        restoreTicketTop()
        val root = appRoot() ?: return null
        if (xtbTitle(root) != instrument) return null
        if (ScreenScanner.findById(root, "TradingBuyButton", visibleOnly = false).isEmpty()) return null
        val found = revealById("PositionRow0")
        restoreTicketTop()
        return found != null
    }

    /** W trybie AUTO: zamyka okna, na których bot utknął, i przywraca bilet na górę. Nie działa w trakcie akcji bota. */
    private suspend fun selfHeal() {
        if (!autonomyOn() || _actionActive.value) { staleSince = 0L; return }
        if (!uiMutex.tryLock()) return
        try {
            val root = appRoot() ?: return
            if (root.packageName?.toString() != ScreenScanner.XTB_PACKAGE) { staleSince = 0L; return }
            val stale = findOrderConfirm(root) != null ||
                ScreenScanner.findById(root, "CloseButton").isNotEmpty() ||
                ScreenScanner.findById(root, "ModifyPositionButton").isNotEmpty()
            if (stale) {
                val now = SystemClock.uptimeMillis()
                if (staleSince == 0L) staleSince = now
                else if (now - staleSince > 8_000) {
                    performGlobalAction(GLOBAL_ACTION_BACK)
                    staleSince = 0L
                    Log.i(TAG, "Samonaprawa: zamknięto wiszące okno XTB")
                }
                return
            }
            staleSince = 0L
            restoreTicketTop()
        } finally {
            uiMutex.unlock()
        }
    }

    // ───────────── Ekran błędu XTB („Coś poszło nie tak … Odśwież”) ─────────────

    private val xtbError = XtbErrorRecovery()

    /** true = XTB pokazuje teraz ekran błędu (bot nie może handlować ani zamykać pozycji). */
    @Volatile var xtbErrorVisible: Boolean = false
        private set

    /**
     * Co sekundę: gdy XTB pokazuje ekran błędu, bot sam naciska „Odśwież”, a gdy to nie pomaga –
     * otwiera XTB od nowa (jak ręczne wyłącz/włącz). Przy restarcie i dalej co minutę – alarm z dźwiękiem.
     */
    private suspend fun recoverFromXtbError() {
        val root = appRoot() ?: return
        if (root.packageName?.toString() != ScreenScanner.XTB_PACKAGE) return // nie widać XTB – nie oceniamy
        val nodes = textNodes(root, 250)
        val error = XtbErrorRecovery.isErrorText(nodes.map { it.second })
        xtbErrorVisible = error
        if (error) diag = "XTB pokazuje ekran błędu „Coś poszło nie tak” – naprawiam"
        when (xtbError.onScan(error, SystemClock.uptimeMillis())) {
            XtbErrorRecovery.Action.NONE -> Unit
            XtbErrorRecovery.Action.TAP_REFRESH -> {
                if (!autonomyOn()) { remoteNote("⚠️ XTB: ekran błędu – naciśnij „Odśwież” (samonaprawa wyłączona albo w pauzie)"); return }
                val btn = nodes.firstOrNull { (_, t) -> XtbErrorRecovery.REFRESH_TEXTS.any { t.equals(it, ignoreCase = true) } }?.first
                if (btn == null) { remoteNote("⚠️ XTB: ekran błędu bez przycisku „Odśwież” – spróbuję restartu XTB"); return }
                if (!uiMutex.tryLock()) return
                val ok = try { click(btn) } finally { uiMutex.unlock() }
                remoteNote(if (ok) "🔄 XTB: ekran błędu – naciskam „Odśwież”" else "⚠️ XTB: nie udało się nacisnąć „Odśwież”", force = true)
            }
            XtbErrorRecovery.Action.RESTART_APP -> {
                if (!autonomyOn()) { alert("XTB pokazuje błąd – uruchom XTB ponownie"); return }
                restartXtb()
                alert("XTB się zawiesiło („Coś poszło nie tak”) – otwieram XTB od nowa (${xtbError.restartCount}×). Sprawdź otwarte pozycje!")
            }
            XtbErrorRecovery.Action.ALERT -> alert("XTB nadal pokazuje błąd – sprawdź telefon i otwarte pozycje!")
            XtbErrorRecovery.Action.RECOVERED -> remoteNote("✅ XTB wróciło po błędzie – bot znów widzi aplikację", force = true)
        }
    }

    // ───────────── Szukanie instrumentu w XTB (bot sam wraca na bilet GOLD) ─────────────

    private var offTargetSince = 0L
    private var lastNavAttempt = 0L
    private var navFails = 0
    private var lastScrollUp = 0L

    /** Przerwa przed kolejną próbą: 20 s przez pierwsze 3 próby, potem 2, 5 i 10 min (nie błądzimy po XTB godzinami). */
    private fun navRetryMs(fails: Int): Long = when {
        fails < 3 -> NAV_RETRY_MS
        fails < 5 -> 2 * 60_000L
        fails < 8 -> 5 * 60_000L
        else -> 10 * 60_000L
    }

    /**
     * Co sekundę: XTB jest na wierzchu, ale nie pokazuje biletu [wantedInstrument] (ekran główny po restarcie,
     * Portfolio, Wiadomości…) → bot sam go otwiera: wyszukiwarka → wynik (dla GOLD po podpisie „Troy Ounce of Gold”),
     * zapasowo Rynki → Surowce. Przy otwartej pozycji po 5 s, bez pozycji po 20 s.
     * Gdy bilet właściwego instrumentu jest otwarty, ale przewinięty (nie widać cen) – tylko przewija go do góry.
     */
    private suspend fun findWantedInstrument() {
        val target = wantedInstrument ?: return
        if (!autonomyOn() || _actionActive.value || xtbErrorVisible) { offTargetSince = 0L; return }
        val root = appRoot() ?: return
        if (root.packageName?.toString() != ScreenScanner.XTB_PACKAGE) { offTargetSince = 0L; return }
        val now = SystemClock.uptimeMillis()
        val q = _screenQuote.value
        val onTicket = q != null && q.isFresh() && q.instrument != null && q.bid != null && q.ask != null
        if (onTicket && (!wantedUrgent || q?.instrument.equals(target, ignoreCase = true))) { offTargetSince = 0L; navFails = 0; return }
        // Bilet tego instrumentu jest otwarty, tylko przewinięty w dół (np. do listy pozycji) – nie wychodzimy z niego.
        if (isTargetTicket(root, target)) {
            offTargetSince = 0L
            if (now - lastScrollUp > 10_000L && uiMutex.tryLock()) {
                lastScrollUp = now
                try { scrollTicketUp(root) } finally { uiMutex.unlock() }
                remoteNote("🧭 XTB: bilet $target przewinięty – przewijam do góry, żeby widzieć ceny")
            }
            return
        }
        if (findOrderConfirm(root) != null) return // trwa składanie zlecenia
        if (offTargetSince == 0L) { offTargetSince = now; return }
        val wait = if (wantedUrgent) NAV_WAIT_URGENT_MS else NAV_WAIT_MS
        if (now - offTargetSince < wait || now - lastNavAttempt < navRetryMs(navFails)) return
        lastNavAttempt = now
        if (!uiMutex.tryLock()) return
        navStatus = "szukam biletu $target (próba ${navFails + 1})"
        var viaAi = false
        val ok = try {
            navigateTo(target) || run {
                // Zwykłe szukanie zawiodło – nawigator wizyjny (zrzut ekranu + AI), z limitem na godzinę.
                val ai = aiNavigator
                if (ai != null && aiNavAllowed()) {
                    aiNavTimes.addLast(System.currentTimeMillis())
                    viaAi = runCatching { ai(target) }.getOrDefault(false)
                    viaAi
                } else false
            }
        } finally { uiMutex.unlock() }
        navStatus = if (ok) "bilet $target otwarty${if (viaAi) " (nawigator AI)" else ""}" else "nie znalazłem $target (próba ${navFails + 1})"
        if (ok) {
            navFails = 0; offTargetSince = 0L
            remoteNote("🧭 XTB: sam otworzyłem bilet $target${if (viaAi) " – pomógł nawigator AI 👁️" else ""}", force = true)
            if (wantedUrgent) com.example.algohud.bot.Ntfy.send("🧭 XTB: wróciłem na bilet $target", "pozycja znów pod nadzorem bota",
                com.example.algohud.bot.Ntfy.NORMAL, "compass")
        } else {
            navFails++
            remoteNote("🧭 XTB: nie znalazłem $target (próba $navFails, następna za ${navRetryMs(navFails) / 60_000L} min) • ekran: ${lastNavDump.take(400)}", force = true)
            if (navFails == 3 || navFails % 6 == 0) {
                alert("Nie mogę sam otworzyć $target w XTB ($navFails prób). Otwórz bilet $target ręcznie" +
                    if (wantedUrgent) " – bot ma otwartą pozycję!" else ".")
                // Do diagnozy: co bot widział na ekranie (cicho, raz na próbę z alarmem).
                com.example.algohud.bot.Ntfy.send("🔍 Diagnoza szukania $target", lastNavDump.take(900),
                    com.example.algohud.bot.Ntfy.MIN, "mag")
            }
        }
    }

    /**
     * Ekran biletu (także przewiniętego w dół): przyciski Sell/Buy albo elementy dolnej części biletu
     * („Otwarte pozycje”, „Stop Loss”, „Opis instrumentu”). Lista wyników i lista Rynki ich nie mają.
     */
    private fun hasTicketHeader(root: AccessibilityNodeInfo, target: String): Boolean =
        hasTradeButtons(root) || TICKET_MARKERS.any { m ->
            root.findAccessibilityNodeInfosByText(m).any { it.isVisibleToUser && !it.isEditable }
        }

    private suspend fun scrollTicketUp(root: AccessibilityNodeInfo) {
        val sc = findScrollable(root)
        repeat(4) {
            if (sc == null || !sc.performAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)) {
                drag(screenW() / 2, screenH() * 0.35f, screenH() * 0.8f)
            }
            delay(350)
        }
    }

    private fun screenH() = resources.displayMetrics.heightPixels.toFloat()
    private fun screenW() = resources.displayMetrics.widthPixels.toFloat()

    /** Tekst węzła to nazwa instrumentu: „GOLD”, „GOLD CFD”, „GOLD\nCFD” – ale nie „GOLD 24/7”, „Gold.com”. */
    private fun isInstrumentLabel(t: String, target: String): Boolean {
        val n = t.replace('\n', ' ').replace(Regex("\\s+"), " ").trim()
        if (n.equals(target, ignoreCase = true)) return true
        val first = n.substringBefore(' ')
        return first.equals(target, ignoreCase = true) && !n.contains("24/7") &&
            (n.length == first.length || n.substring(first.length).trim().startsWith("CFD", ignoreCase = true))
    }

    /** Pierwszy kafelek „Ostatnio wyszukiwane” z tym instrumentem (ekran po naciśnięciu lupy). */
    private fun recentTile(root: AccessibilityNodeInfo, target: String): AccessibilityNodeInfo? {
        val h = screenH()
        val r = Rect()
        return textNodes(root, 400).map { it.first to it.second }.firstOrNull { (n, t) ->
            n.getBoundsInScreen(r)
            !n.isEditable && r.centerY() > 0.1f * h && r.centerY() < 0.45f * h && isInstrumentLabel(t, target)
        }?.first
    }

    /** Widoczny, NIEEDYTOWALNY węzeł z dokładnie tym tekstem, w pasie ekranu [minY]–[maxY] (ułamki wysokości). */
    private fun nodeWithText(root: AccessibilityNodeInfo, text: String, minY: Float = 0f, maxY: Float = 1f): AccessibilityNodeInfo? {
        val r = Rect()
        val h = screenH()
        return root.findAccessibilityNodeInfosByText(text).firstOrNull { n ->
            val t = (n.text ?: n.contentDescription)?.toString()?.trim()
            n.getBoundsInScreen(r)
            n.isVisibleToUser && !n.isEditable && !n.isFocused && t.equals(text, ignoreCase = true) && !r.isEmpty &&
                r.centerY() >= minY * h && r.centerY() <= maxY * h
        }
    }

    /**
     * Wiersz wyniku/listy z instrumentem: dla znanych instrumentów po podpisie (GOLD → „Troy Ounce of Gold”,
     * żeby nie trafić w „GOLD 24/7” ani „Gold.com”), inaczej po tytule – nigdy pole wyszukiwania.
     */
    private fun instrumentRow(root: AccessibilityNodeInfo, target: String): AccessibilityNodeInfo? {
        val h = screenH()
        val r = Rect()
        SUBTITLES[target.uppercase()]?.let { sub ->
            root.findAccessibilityNodeInfosByText(sub).firstOrNull { n ->
                n.getBoundsInScreen(r)
                n.isVisibleToUser && !n.isEditable && r.centerY() > 0.15f * h && r.centerY() < 0.92f * h
            }?.let { return it }
        }
        nodeWithText(root, target, minY = 0.15f, maxY = 0.92f)?.let { return it }
        return textNodes(root, 400).firstOrNull { (n, t) ->
            n.getBoundsInScreen(r)
            n.isVisibleToUser && !n.isEditable && r.centerY() > 0.15f * h && r.centerY() < 0.92f * h && isInstrumentLabel(t, target)
        }?.first
    }

    private fun isTargetTicket(root: AccessibilityNodeInfo, target: String): Boolean {
        val title = ScreenScanner.findById(root, "ElementTitleHorizontalText").firstOrNull()?.text?.toString()
        return XtbTargetRecognition.matches(root.packageName?.toString(), title, target, hasTicketHeader(root, target))
    }

    private fun targetOpen(target: String): Boolean = appRoot()?.let { isTargetTicket(it, target) } == true

    private suspend fun onTarget(target: String): Boolean {
        // Najpierw sprawdzenie, potem czekanie. Ceny/przyciski mogą doładować się później niż nagłówek.
        repeat(20) {
            if (targetOpen(target)) return true
            delay(400)
        }
        return targetOpen(target)
    }

    /** Przyciski „Sell …” / „Buy …” biletu (nie „Sellas Life Sciences” z listy – po słowie musi być koniec albo odstęp). */
    private fun hasTradeButtons(root: AccessibilityNodeInfo): Boolean = listOf("Sell", "Buy").any { word ->
        root.findAccessibilityNodeInfosByText(word).any { n ->
            val t = (n.text ?: n.contentDescription)?.toString()?.trim().orEmpty()
            n.isVisibleToUser && t.startsWith(word) && (t.length == word.length || t[word.length].isWhitespace())
        }
    }

    /** Teksty widoczne na ekranie przy ostatniej nieudanej próbie – do diagnozy (HUD „Szczegóły” i alarm). */
    @Volatile private var lastNavDump: String = ""

    private fun screenDump(): String = appRoot()?.let { r ->
        textNodes(r, 300).map { it.second.replace('\n', ' ').take(30) }.distinct().take(30).joinToString(" | ")
    } ?: "brak okna"

    private fun inXtb(): Boolean = appRoot()?.packageName?.toString() == ScreenScanner.XTB_PACKAGE

    /** „Wstecz” tylko w obrębie XTB: jeśli po nim XTB zniknęło (pulpit), od razu otwieramy XTB z powrotem. */
    private suspend fun backStayInXtb() {
        performGlobalAction(GLOBAL_ACTION_BACK); delay(700)
        if (!inXtb()) {
            packageManager.getLaunchIntentForPackage(ScreenScanner.XTB_PACKAGE)?.let { i ->
                i.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                runCatching { startActivity(i) }
            }
            delay(2_000)
        }
    }

    /** Dotknięcie środka węzła palcem (gest) – pewniejsze niż ACTION_CLICK dla ikon w nagłówku XTB. */
    private suspend fun tapNode(n: AccessibilityNodeInfo): Boolean {
        val r = Rect().also(n::getBoundsInScreen)
        if (r.isEmpty) return false
        return tap(r.exactCenterX(), r.exactCenterY())
    }

    /** Na bieżącej liście: wiersz instrumentu → klik → bilet; inaczej przewijanie (maks. [NAV_SCROLLS] razy). */
    private suspend fun clickRowWithScroll(target: String): Boolean {
        repeat(NAV_SCROLLS) {
            if (!inXtb()) return false
            val r = appRoot() ?: return false
            if (isTargetTicket(r, target)) return true
            val row = instrumentRow(r, target)
            if (row != null) {
                click(row)
                if (onTarget(target)) return true
                if (targetOpen(target)) return true
                backStayInXtb()
                return false
            }
            val scroller = findScrollable(r)
            if (scroller == null || !scroller.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)) {
                drag(screenW() / 2, screenH() * 0.72f, screenH() * 0.38f)
            }
            delay(600)
        }
        return false
    }

    private suspend fun navigateTo(target: String): Boolean {
        Log.i(TAG, "Szukam $target w XTB")
        if (!inXtb()) return false // nigdy nie klikamy po pulpicie ani innych aplikacjach
        if (targetOpen(target)) return true
        // 1) Rynki → CFD (lista obserwowanych) → przewijanie do wiersza instrumentu
        appRoot()?.let { r -> nodeWithText(r, "Rynki", minY = 0.8f)?.let { click(it); delay(1_200) } }
        appRoot()?.let { r -> nodeWithText(r, "CFD", maxY = 0.35f)?.let { click(it); delay(1_000) } }
        // na początek listy, potem w dół
        appRoot()?.let { r -> findScrollable(r)?.let { sc -> repeat(3) { sc.performAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD) } } }
        delay(500)
        if (clickRowWithScroll(target)) return true
        lastNavDump = screenDump()
        if (!inXtb()) return false
        if (targetOpen(target)) return true
        // 2) Wyszukiwarka: lupa („Wyszukaj”) dotknięta palcem → pole → nazwa → wiersz wyniku (nie pole tekstowe!)
        run {
            val r0 = appRoot() ?: return false
            val lupa = r0.findAccessibilityNodeInfosByText("Wyszukaj").firstOrNull { it.isVisibleToUser && !it.isEditable }
                ?: r0.findAccessibilityNodeInfosByText("Szukaj").firstOrNull { it.isVisibleToUser && !it.isEditable }
            if (lupa != null) tapNode(lupa) else tap(screenW() * 0.91f, screenH() * 0.085f)
            delay(1_500)
            // Ekran „Instrumenty”: w „Ostatnio wyszukiwane” instrument jest pierwszym kafelkiem u góry – wchodzimy w niego.
            appRoot()?.let { r ->
                recentTile(r, target)?.let { tile ->
                    click(tile)
                    if (onTarget(target)) return true
                    // Nie używamy węzła z poprzedniego ekranu po przejściu na instrument.
                    if (targetOpen(target)) return true
                    appRoot()?.let { recentTile(it, target) }?.let { fresh -> tapNode(fresh) }
                    if (onTarget(target)) return true
                }
                // bez rozpoznanego tekstu: pierwszy kafelek pod nagłówkiem „Ostatnio wyszukiwane”
                r.findAccessibilityNodeInfosByText("Ostatnio wyszukiwane").firstOrNull { it.isVisibleToUser }?.let { head ->
                    val hb = Rect().also(head::getBoundsInScreen)
                    tap(screenW() * 0.21f, hb.bottom + screenH() * 0.055f)
                    if (onTarget(target)) return true
                    if (targetOpen(target)) return true
                    backStayInXtb()
                    if (lupa != null) appRoot()?.findAccessibilityNodeInfosByText("Wyszukaj")?.firstOrNull { it.isVisibleToUser }?.let { tapNode(it); delay(1_500) }
                }
            }
            if (targetOpen(target)) return true
            var field = appRoot()?.let { findEditable(it) }
            if (field == null && inXtb() && appRoot()?.findAccessibilityNodeInfosByText("Ostatnio wyszukiwane").isNullOrEmpty()) {
                tap(screenW() / 2, screenH() * 0.19f); delay(900); field = appRoot()?.let { findEditable(it) }
            }
            if (field != null) {
                val args = android.os.Bundle().apply {
                    putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, target)
                }
                field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
                delay(1_500)
                appRoot()?.let { r -> instrumentRow(r, target) }?.let { row ->
                    click(row)
                    if (onTarget(target)) return true
                }
            } else if (inXtb()) {
                // ekran „Instrumenty”: kafelek z „Ostatnio wyszukiwanych” albo kategoria „Surowce”
                appRoot()?.let { r -> instrumentRow(r, target) }?.let { tile -> click(tile); if (onTarget(target)) return true }
                appRoot()?.let { r -> nodeWithText(r, "Surowce", minY = 0.15f) }?.let { cat ->
                    click(cat); delay(1_500)
                    if (clickRowWithScroll(target)) return true
                }
            }
            if (targetOpen(target)) return true
            lastNavDump = lastNavDump + " || " + screenDump()
            backStayInXtb()
        }
        return false
    }

    /** Wiersze otwartych pozycji widoczne w drzewie bieżącego ekranu XTB (bez klikania) – do wykrycia pozycji spoza księgi. */
    private val profitAnchors = java.util.concurrent.ConcurrentHashMap<String, String>()

    /** Wyłącznie świeży odczyt pozycji, której otwarcie potwierdził bot. */
    fun profitSnapshot(instrument: String, side: Side, quantity: Double): Pair<ScreenQuote, PositionRowInfo>? =
        positionSnapshot(instrument, side, quantity)?.takeIf { it.first.accountCurrency == "PLN" }

    fun positionSnapshot(instrument: String, side: Side, quantity: Double): Pair<ScreenQuote, PositionRowInfo>? {
        val root = appRoot()?.takeIf { it.packageName?.toString() == ScreenScanner.XTB_PACKAGE } ?: return null
        val q = ScreenScanner.scanXtb(root, ScreenScanner.XTB_PACKAGE, "XTB", System.currentTimeMillis()) ?: return null
        if (q.instrument != instrument) return null
        val row = readPositionRows(root).singleOrNull() ?: return null
        if (row.signature != profitAnchors[instrument] || row.side != side ||
            row.volume?.let { abs(abs(it) - quantity) > 1e-8 } != false) return null
        return q to row
    }

    fun visiblePositionRows(): List<PositionRowInfo> =
        appRoot()?.takeIf { it.packageName?.toString() == ScreenScanner.XTB_PACKAGE }?.let { runCatching { readPositionRows(it) }.getOrNull() }.orEmpty()

    private fun findEditable(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val queue = ArrayDeque<AccessibilityNodeInfo>().apply { add(root) }
        var visited = 0
        while (queue.isNotEmpty() && visited < 1500) {
            val n = queue.removeFirst(); visited++
            if (n.isEditable && n.isVisibleToUser) return n
            for (i in 0 until n.childCount) n.getChild(i)?.let { queue.add(it) }
        }
        return null
    }

    /** Otwiera XTB od nowa: zamyka bieżące ekrany aplikacji i startuje ją od ekranu startowego. */
    private fun restartXtb() {
        val intent = packageManager.getLaunchIntentForPackage(ScreenScanner.XTB_PACKAGE) ?: return
        intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK)
        runCatching { startActivity(intent) }
            .onFailure { Log.w(TAG, "Restart XTB nieudany", it) }
        Log.i(TAG, "Ekran błędu XTB – restart aplikacji")
    }

    /**
     * Alarm dla użytkownika: wiadomość w HUD + powiadomienie z dźwiękiem i wibracją (osobny kanał o wysokim priorytecie).
     * Używane, gdy bot nie może sam dokończyć sprawy (zawieszone XTB, pozycja bez nadzoru).
     */
    fun alert(text: String) {
        _uiEvents.tryEmit("🚨 $text")
        lastAlert = System.currentTimeMillis() to text
        // Na główny telefon (ntfy) – pilne, z dźwiękiem; ten sam alarm najwyżej raz na minutę.
        // Ten sam rodzaj alarmu (treść bez liczb – „324 prób”, „6827 s”) najwyżej raz na 10 min.
        com.example.algohud.bot.Ntfy.send("🚨 Algo HUD – awaria", text, com.example.algohud.bot.Ntfy.URGENT, "rotating_light",
            key = "alert:" + text.filterNot { it.isDigit() }.take(50), minGapMs = 600_000L)
        runCatching {
            val nm = getSystemService(android.app.NotificationManager::class.java)
            nm.createNotificationChannel(
                android.app.NotificationChannel(ALERT_CHANNEL, "Algo HUD – alarmy", android.app.NotificationManager.IMPORTANCE_HIGH)
                    .apply { enableVibration(true) }
            )
            val n = androidx.core.app.NotificationCompat.Builder(this, ALERT_CHANNEL)
                .setSmallIcon(android.R.drawable.stat_sys_warning)
                .setContentTitle("Algo HUD – wymaga uwagi")
                .setContentText(text)
                .setStyle(androidx.core.app.NotificationCompat.BigTextStyle().bigText(text))
                .setPriority(androidx.core.app.NotificationCompat.PRIORITY_HIGH)
                .setCategory(androidx.core.app.NotificationCompat.CATEGORY_ALARM)
                .setAutoCancel(true)
                .build()
            nm.notify(ALERT_NOTIF_ID, n)
        }.onFailure { Log.w(TAG, "Alarm nieudany", it) }
    }

    /**
     * XTB zniknęło z ekranu (pulpit, inna aplikacja) na ponad 20 s → przywraca je na pierwszy plan.
     * Działa we wszystkich trybach przy włączonej autonomii. Nie wyciąga z ustawień, instalatora, Algo HUD
     * i okien systemowych (np. zgoda AnyDesk). Ekran wygaszony = nic nie robi.
     */
    private fun keepXtbFront() {
        if (!autonomyOn() || !getSharedPreferences("ui", MODE_PRIVATE).getBoolean("keep_xtb_front", true)) return
        val since = notVisibleSince
        val now = SystemClock.uptimeMillis()
        if (since == 0L) { frontAttempts = 0; return }
        val fg = foregroundPackage
        if (fg != null && NO_YANK_PREFIXES.any { fg == it || fg.startsWith("$it.") }) {
            navStatus = "XTB w tle – czekam, bo na wierzchu: $fg"
            return
        }
        if (fg != null && fg.startsWith("com.anydesk") && now - since < 120_000) return // okno AnyDesk – daj 2 min
        if (now - since < 20_000 || now - lastFrontAttempt < 30_000) return
        if (getSystemService(android.os.PowerManager::class.java)?.isInteractive != true) return
        lastFrontAttempt = now
        frontAttempts++
        val intent = packageManager.getLaunchIntentForPackage(ScreenScanner.XTB_PACKAGE) ?: run { navStatus = "XTB nie jest zainstalowane?"; return }
        intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
        runCatching { startActivity(intent) }.onFailure { Log.w(TAG, "Przywracanie XTB nieudane", it) }
        navStatus = "przywracam XTB na wierzch (${frontAttempts}×, na wierzchu było: ${fg ?: "?"})"
        Log.i(TAG, "XTB niewidoczne od ${(now - since) / 1000} s – przywracam na pierwszy plan ($frontAttempts×)")
        // Android może zablokować start z tła – po 3 nieudanych próbach jeden alarm (powtórka co 10 min w alert()).
        if (frontAttempts == 3 || frontAttempts % 20 == 0) alert("Nie mogę przywrócić XTB na ekran (${fg ?: "?"} na wierzchu) – otwórz XTB ręcznie")
    }

    // ───────────── Prymitywy nawigacji dla nawigatora AI (wołane TYLKO pod uiMutex z findWantedInstrument) ─────────────

    private val aiNavTimes = ArrayDeque<Long>()
    private fun aiNavAllowed(): Boolean {
        val now = System.currentTimeMillis()
        while (aiNavTimes.isNotEmpty() && now - aiNavTimes.first() > 3_600_000L) aiNavTimes.removeFirst()
        return aiNavTimes.size < AI_NAV_MAX_PER_HOUR
    }

    /** Słowa przycisków, w które nawigator AI NIGDY nie może trafić (zlecenia, zamknięcia, modyfikacje). */
    private val navForbidden = listOf("Sell", "Buy", "Sprzedaj", "Kup", "Potwierdź", "Zamknij", "Close", "Modyfikuj", "Modify",
        "Usuń", "Delete", "Wyloguj", "Log out", "Wpłać", "Wypłać", "Deposit", "Withdraw")

    /** Wymiary ekranu jak na zrzucie (cały wyświetlacz, z paskami). */
    private fun realScreen(): Pair<Float, Float> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            getSystemService(android.view.WindowManager::class.java).currentWindowMetrics.bounds.let { it.width().toFloat() to it.height().toFloat() }
        } else screenW() to screenH()

    /**
     * Dotknięcie w punkcie (0..1, 0..1 względem całego ekranu). Zwraca null = dotknięto, inaczej powód odmowy.
     * Odmawia poza XTB i gdy w punkcie jest przycisk z [navForbidden].
     */
    suspend fun navTapNormalized(nx: Float, ny: Float): String? {
        if (navAbort()) return "kill-switch"
        if (nx !in 0.02f..0.98f || ny !in 0.03f..0.97f) return "punkt poza bezpiecznym obszarem"
        val root = appRoot() ?: return "brak okna"
        if (root.packageName?.toString() != ScreenScanner.XTB_PACKAGE) return "XTB nie jest na wierzchu"
        if (findOrderConfirm(root) != null) return "otwarte potwierdzenie zlecenia – nie dotykam"
        val (w, h) = realScreen()
        val x = nx * w; val y = ny * h
        val hit = textNodes(root, 400).firstOrNull { (n, t) ->
            val r = Rect().also(n::getBoundsInScreen)
            r.contains(x.toInt(), y.toInt()) && navForbidden.any { f -> t.trim().startsWith(f, ignoreCase = true) }
        }
        if (hit != null) return "w tym miejscu jest „${hit.second.take(20)}” – zakazane dla nawigatora"
        return if (tap(x, y)) null else "gest odrzucony"
    }

    /**
     * Chowa HUD i tarczę na czas [block] (zrzut ekranu dla AI – żeby HUD nie zasłaniał XTB).
     * Gdy trwa jakakolwiek akcja w XTB (zlecenie, nawigacja), NIE rusza HUD-a – zrzut idzie z HUD-em,
     * bo przełączanie widoczności w trakcie gestu mogłoby przechwycić dotyk. [lockHeld] = wołający już trzyma uiMutex.
     */
    suspend fun <T> withHudHidden(lockHeld: Boolean = false, block: suspend () -> T): T {
        val owned = !lockHeld && uiMutex.tryLock()
        val canHide = (owned || lockHeld) && !_actionActive.value && !_gestureActive.value
        try {
            if (!canHide) return block()
            _gestureActive.value = true
            try { delay(150); return block() } finally { _gestureActive.value = false }
        } finally { if (owned) uiMutex.unlock() }
    }

    suspend fun navBack() { if (!navAbort()) backStayInXtb() }

    suspend fun navScroll(down: Boolean) {
        if (navAbort()) return
        val r = appRoot() ?: return
        val sc = findScrollable(r)
        val ok = sc?.performAction(if (down) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD) == true
        if (!ok) { if (down) drag(screenW() / 2, screenH() * 0.72f, screenH() * 0.38f) else drag(screenW() / 2, screenH() * 0.38f, screenH() * 0.72f) }
        delay(600)
    }

    suspend fun navIsOnTarget(target: String): Boolean = onTarget(target)

    /** Krótki opis tekstów widocznych w XTB (dla nawigatora, gdy zrzut ekranu jest zablokowany). */
    fun navScreenText(): String = screenDump()

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
            ?.text?.toString()?.trim()?.split(Regex("\\s+"))?.firstOrNull()

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
    private var entryBaseline: TicketSlTpState? = null
    /** Ostatnio znany stan suwaków SL/TP w bilecie (czas uptime → stan) – żeby nie przewijać biletu przy każdym wejściu. */
    @Volatile private var cachedForm: Pair<Long, TicketSlTpState>? = null

    /** Wejście + sprzątanie: gdy zlecenie NIE poszło, suwaki SL/TP wracają od razu do stanu sprzed wejścia. */
    private suspend fun performXtbEntry(order: OrderRequest): ExecutionResult {
        entryBaseline = null
        val r = performXtbEntryInner(order)
        val base = entryBaseline
        if (base != null && (r is ExecutionResult.Rejected || (r is ExecutionResult.Failed && appRoot()?.let { findOrderConfirm(it) } == null))) {
            runCatching {
                restoreTicketTop()
                val err = applyFormToggle("StopLossContainer", "StopLossToggle", base.slEnabled, base.slValue)?.let { "SL: $it" }
                    ?: applyFormToggle("TakeProfitContainer", "TakeProfitToggle", base.tpEnabled, base.tpValue)?.let { "TP: $it" }
                restoreTicketTop()
                cachedForm = if (err == null) SystemClock.uptimeMillis() to base else null
                _uiEvents.tryEmit(if (err == null) "↩️ ${order.symbol}: wejście nieudane – suwaki SL/TP przywrócone" else "⚠️ ${order.symbol}: wejście nieudane, suwaki nie wróciły ($err)")
            }
        }
        return r
    }

    private suspend fun performXtbEntryInner(order: OrderRequest): ExecutionResult {
        val q = screenQuote.value
        val sidePx = q?.let { if (order.side == Side.BUY) it.ask else it.bid }
            ?: return ExecutionResult.Rejected("brak ceny Buy/Sell na ekranie XTB")
        // Ceny bota mogą pochodzić z feedu w tle (PAXG) – przesuwamy SL/TP o różnicę do ceny XTB.
        val shift = sidePx - order.referencePrice
        val sl = order.stopPrice + shift
        val tp = order.takeProfit?.plus(shift)

        val t0 = SystemClock.uptimeMillis()
        // Stan suwaków SL/TP PRZED wejściem: zapamiętany (z ostatniego przywrócenia/odczytu, < 10 min) – bez przewijania biletu.
        val cached = cachedForm?.takeIf { SystemClock.uptimeMillis() - it.first < 10 * 60_000 }?.second
        val formBefore = cached ?: runCatching { readTicketSlTp() }.getOrNull()
        formBefore?.let { cachedForm = SystemClock.uptimeMillis() to it }
        entryBaseline = formBefore
        val rowsBefore = appRoot()?.let { runCatching { readPositionRows(it) }.getOrNull() }.orEmpty()
        val formOp = formBefore?.let { formCoordinator.beginEntry(order.clientOrderId, order.symbol, it) }
        if (formBefore == null) _uiEvents.tryEmit("⚠️ ${order.symbol}: nie odczytano suwaków SL/TP – po wejściu nie zostaną przywrócone")

        // Kolejność dla szybkości: SL/TP (dół biletu) → góra biletu → wolumen → przycisk (jedno przewinięcie mniej).
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

        restoreTicketTop()
        revealById("MarketSizeInput") ?: return ExecutionResult.Rejected("nie znaleziono sekcji wolumenu")
        val vol = revealField("MarketSizeInput") ?: return ExecutionResult.Rejected("sekcja wolumenu jest, ale pole niewidoczne")
        val curVol = vol.text?.toString()?.let { ScreenScanner.parseNumber(it) }
        if (curVol == null || abs(curVol - order.quantity) > 1e-9 + order.quantity * 1e-6) {
            val args = Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, formatQty(order.quantity))
            }
            if (!vol.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) return ExecutionResult.Rejected("nie udało się wpisać wolumenu")
            delay(250)
        }
        val typed = revealField("MarketSizeInput")?.text?.toString()?.let { ScreenScanner.parseNumber(it) }
        if (typed == null || abs(typed - order.quantity) > 1e-9 + order.quantity * 1e-6) {
            return ExecutionResult.Rejected("XTB nie przyjęło wolumenu (jest $typed, miało być ${formatQty(order.quantity)})")
        }

        // Poślizg: jeśli w trakcie ustawiania biletu cena uciekła o > 30% ryzyka w stronę SL/za wejściem – nie wchodzimy późno.
        val pxNow = screenQuote.value?.let { if (order.side == Side.BUY) it.ask else it.bid }
        val riskDist = abs(sidePx - sl)
        if (pxNow != null && riskDist > 0) {
            val dir = if (order.side == Side.BUY) 1 else -1
            if ((pxNow - sidePx) * dir > 0.3 * riskDist) {
                return ExecutionResult.Rejected("cena uciekła o %.2f w trakcie przygotowania biletu – za późno, nie wchodzę".format(abs(pxNow - sidePx)))
            }
            if ((sidePx - pxNow) * dir > 0.5 * riskDist) {
                return ExecutionResult.Rejected("cena ruszyła o %.2f w stronę SL – setup nieaktualny".format(abs(pxNow - sidePx)))
            }
        }
        if (entryBlocked?.invoke() == true) return ExecutionResult.Rejected("STOP WEJŚĆ – przerwano przed wysłaniem")
        val btn = revealById(if (order.side == Side.BUY) "TradingBuyButton" else "TradingSellButton")
            ?: return ExecutionResult.Rejected("nie znaleziono przycisku ${order.side}")
        scanNow()
        if (entryBlocked?.invoke() == true) return ExecutionResult.Rejected("STOP WEJŚĆ aktywny")
        entryCheck?.invoke(order)?.let { return ExecutionResult.Rejected(it) }
        if (!click(btn)) return ExecutionResult.Rejected("kliknięcie ${order.side} nie powiodło się")
        _uiEvents.tryEmit("⏱ ${order.symbol}: od decyzji do kliknięcia %.1f s".format((SystemClock.uptimeMillis() - t0) / 1000.0))

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
            if (entryBlocked?.invoke() == true) {
                backStayInXtb()
                return ExecutionResult.Rejected("STOP WEJŚĆ – anulowano potwierdzenie")
            }
            // Zabezpieczenie: na ekranie potwierdzenia musi być nasz wolumen.
            val screenText = appRoot()?.let { ScreenScanner.collect(it).joinToString(" ") { t -> t.text } }.orEmpty()
            if (!screenText.contains(formatQty(order.quantity))) {
                return ExecutionResult.Failed(IllegalStateException("ekran potwierdzenia XTB pokazuje inny wolumen – NIE potwierdzam, sprawdź ręcznie"))
            }
            entryCheck?.invoke(order)?.let { why ->
                backStayInXtb()
                return ExecutionResult.Rejected(why)
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

        // Sukces dopiero, gdy XTB pokazuje NOWĄ pozycję z zapisanymi SL/TP (nie samo kliknięcie).
        val desc = "${order.side} ${formatQty(order.quantity)} ${order.symbol}, SL %.2f".format(sl) + (tp?.let { ", TP %.2f".format(it) } ?: "")
        return when (val check = awaitOpenedPosition(order, sl, tp, rowsBefore)) {
            is OpenCheck.Confirmed -> {
                profitAnchors[order.symbol] = check.row.signature
                if (formOp != null) {
                    formCoordinator.onConfirmed(
                        formOp,
                        SlTpFormCoordinator.ConfirmedPosition(formOp.opId, order.symbol, check.row.signature, sl, tp, System.currentTimeMillis()),
                        restore = { restoreFormFor(it) },
                        report = { _uiEvents.tryEmit(it) }
                    )
                }
                ExecutionResult.Submitted(
                    "XTB: $desc – SL/TP potwierdzone przy pozycji ${check.row.entryText ?: ""}" +
                        (if (formOp != null) "; suwaki wrócą za ok. 5 s" else "")
                )
            }
            is OpenCheck.SlTpProblem -> {
                formOp?.let { formCoordinator.onFailed(it) }
                val text = check.problems.joinToString("; ")
                _uiEvents.tryEmit("⛔ ${order.symbol}: pozycja otwarta, ale SL/TP NIE zapisane poprawnie ($text) – sprawdź w XTB; suwaki bez zmian")
                ExecutionResult.Submitted("XTB: $desc – UWAGA: $text")
            }
            OpenCheck.NotFound -> {
                formOp?.let { formCoordinator.onFailed(it) }
                ExecutionResult.Failed(IllegalStateException("XTB nie pokazało nowej pozycji w ciągu 15 s – otwarcie niepotwierdzone"))
            }
        }
    }

    // ───────────── Suwaki SL/TP w bilecie: zapis stanu, weryfikacja przy pozycji, przywracanie ─────────────

    private val formCoordinator by lazy { SlTpFormCoordinator(serviceScope) }

    /** Przełącznik ("suwak") w wierszu sekcji SL/TP biletu. */
    private fun switchIn(toggleId: String): AccessibilityNodeInfo? {
        val root = appRoot() ?: return null
        val area = ScreenScanner.findById(root, toggleId).firstOrNull()?.let { n -> Rect().also { n.getBoundsInScreen(it) } } ?: return null
        val r = Rect()
        return ScreenScanner.findById(root, "ToggleRowSwitch").firstOrNull { n -> n.getBoundsInScreen(r); area.contains(r.centerX(), r.centerY()) }
    }

    /** Włączony = przełącznik zaznaczony (jeśli XTB to raportuje) albo widoczne pole ceny. */
    private fun readEnabled(containerId: String, toggleId: String): Boolean {
        val sw = switchIn(toggleId)
        return if (sw != null && sw.isCheckable) sw.isChecked else fieldIn(containerId) != null
    }

    /** Aktualny stan suwaków SL/TP w bilecie (przewija do sekcji). null = nie udało się odczytać. */
    private suspend fun readTicketSlTp(): TicketSlTpState? {
        revealById("StopLossContainer") ?: return null
        centerOn("StopLossToggle") ?: return null
        val slOn = readEnabled("StopLossContainer", "StopLossToggle")
        val slVal = (if (slOn) revealField("StopLossContainer") else null)?.text?.toString()
        revealById("TakeProfitContainer") ?: return null
        centerOn("TakeProfitToggle") ?: return null
        val tpOn = readEnabled("TakeProfitContainer", "TakeProfitToggle")
        val tpVal = (if (tpOn) revealField("TakeProfitContainer") else null)?.text?.toString()
        return TicketSlTpState(slOn, slVal, tpOn, tpVal)
    }

    /** Wszystkie elementy z tekstem (także poza ekranem) – do odczytu wierszy pozycji. */
    private fun uiItems(root: AccessibilityNodeInfo, limit: Int = 4000): List<UiItem> {
        val out = ArrayList<UiItem>()
        val queue = ArrayDeque<AccessibilityNodeInfo>().apply { add(root) }
        val r = Rect()
        var visited = 0
        while (queue.isNotEmpty() && visited < limit) {
            val n = queue.removeFirst()
            visited++
            val t = n.text?.toString()
            if (!t.isNullOrEmpty()) {
                n.getBoundsInScreen(r)
                out += UiItem(n.viewIdResourceName?.substringAfter(":id/")?.substringAfterLast('/'), t, r.left, r.top, r.right, r.bottom)
            }
            for (i in 0 until n.childCount) n.getChild(i)?.let { queue.add(it) }
        }
        return out
    }

    /** Wiersze sekcji "Otwarte pozycje" biletu XTB. */
    private fun readPositionRows(root: AccessibilityNodeInfo): List<PositionRowInfo> {
        val items = uiItems(root)
        val r = Rect()
        return (0 until 10).mapNotNull { k ->
            val row = ScreenScanner.findById(root, "PositionRow$k", visibleOnly = false).firstOrNull() ?: return@mapNotNull null
            row.getBoundsInScreen(r)
            if (r.isEmpty) return@mapNotNull null
            PositionRowParser.parse(items.filter { it.left >= r.left && it.right <= r.right && it.top >= r.top && it.bottom <= r.bottom })
        }
    }

    private sealed interface OpenCheck {
        data class Confirmed(val row: PositionRowInfo) : OpenCheck
        data class SlTpProblem(val problems: List<String>) : OpenCheck
        object NotFound : OpenCheck
    }

    /** Czeka, aż w XTB pojawi się NOWA pozycja (strona + wolumen) i sprawdza zapisane przy niej SL/TP. */
    private suspend fun awaitOpenedPosition(order: OrderRequest, sl: Double, tp: Double?, before: List<PositionRowInfo>): OpenCheck {
        var lastProblems: List<String>? = null
        var revealed = false
        val deadline = SystemClock.uptimeMillis() + 15_000
        while (SystemClock.uptimeMillis() < deadline) {
            val root = appRoot()
            if (root != null && root.packageName?.toString() == ScreenScanner.XTB_PACKAGE) {
                val rows = readPositionRows(root)
                val candidates = SlTpVerifier.newRows(before, rows, order.side, order.quantity)
                for (c in candidates) {
                    when (val chk = SlTpVerifier.check(c, sl, tp)) {
                        SlTpCheck.Ok -> return OpenCheck.Confirmed(c)
                        is SlTpCheck.Mismatch -> lastProblems = chk.problems // XTB mogło jeszcze nie odświeżyć – czekamy dalej
                    }
                }
                if (candidates.isEmpty() && !revealed && SystemClock.uptimeMillis() > deadline - 10_000) {
                    revealed = true
                    revealById("PositionRow0") // sekcja pozycji mogła nie być jeszcze wyrenderowana
                }
            }
            delay(700)
        }
        return lastProblems?.let { OpenCheck.SlTpProblem(it) } ?: OpenCheck.NotFound
    }

    /** Ustawia przełącznik (i ewentualnie wartość) w bilecie. Dotyczy WYŁĄCZNIE formularza, nie pozycji. */
    private suspend fun applyFormToggle(containerId: String, toggleId: String, on: Boolean, value: String?): String? {
        revealById(containerId) ?: return "nie znaleziono sekcji"
        centerOn(toggleId) ?: return "nie znaleziono wiersza przełącznika"
        if (readEnabled(containerId, toggleId) != on) {
            val sw = switchIn(toggleId) ?: return "nie znaleziono przełącznika"
            if (!click(sw)) return "nie udało się przełączyć"
            delay(500)
            if (readEnabled(containerId, toggleId) != on) return "przełącznik nie zmienił stanu"
        }
        if (on && value != null) {
            val f = revealField(containerId) ?: return "brak pola ceny"
            if (f.text?.toString() != value) {
                val args = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value) }
                if (!f.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) return "nie udało się wpisać wartości"
                delay(300)
                val now = fieldIn(containerId)?.text?.toString()?.let { ScreenScanner.parseNumber(it) }
                val want = ScreenScanner.parseNumber(value)
                if (now == null || want == null || abs(now - want) > 1e-6) return "XTB nie przyjęło wartości $value"
            }
        }
        return null
    }

    /** Przywrócenie formularza – tylko na bilecie tego instrumentu i gdy nic innego nie trwa. */
    private suspend fun restoreFormFor(op: SlTpFormCoordinator.Operation): SlTpFormCoordinator.RestoreOutcome {
        if (_actionActive.value || !uiMutex.tryLock()) return SlTpFormCoordinator.RestoreOutcome.Busy
        try {
            val root = appRoot() ?: return SlTpFormCoordinator.RestoreOutcome.Busy
            if (root.packageName?.toString() != ScreenScanner.XTB_PACKAGE) return SlTpFormCoordinator.RestoreOutcome.Busy
            // Edycja pozycji / arkusz / potwierdzenie zlecenia na ekranie → nie ruszamy formularza.
            if (ScreenScanner.findById(root, "ModifyPositionButton").isNotEmpty() || findOrderConfirm(root) != null ||
                ScreenScanner.findById(root, "CloseButton").isNotEmpty()
            ) return SlTpFormCoordinator.RestoreOutcome.Busy
            if (xtbTitle(root) != op.instrument) return SlTpFormCoordinator.RestoreOutcome.Busy
            _actionActive.value = true // tarcza dotyku uchylona na czas zmian
            delay(350)
            val b = op.baseline
            val err = applyFormToggle("StopLossContainer", "StopLossToggle", b.slEnabled, b.slValue)?.let { "SL: $it" }
                ?: applyFormToggle("TakeProfitContainer", "TakeProfitToggle", b.tpEnabled, b.tpValue)?.let { "TP: $it" }
            runCatching { restoreTicketTop() }
            cachedForm = if (err == null) SystemClock.uptimeMillis() to b else null
            return if (err == null) SlTpFormCoordinator.RestoreOutcome.Done else SlTpFormCoordinator.RestoreOutcome.Failed(err)
        } finally {
            _actionActive.value = false
            uiMutex.unlock()
        }
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
