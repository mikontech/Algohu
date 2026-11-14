package com.example.algohud.overlay

import android.animation.ValueAnimator
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.provider.Settings
import android.text.TextUtils
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.example.algohud.bot.BotOrchestrator
import com.example.algohud.bot.BotProfile
import com.example.algohud.core.ExecutionMode
import com.example.algohud.core.HudState
import com.example.algohud.core.Side
import com.example.algohud.data.KeywordSentimentScorer
import com.example.algohud.data.MarketNewsEngine
import com.example.algohud.data.PythFeed
import com.example.algohud.execution.AccessibilityExecutor
import com.example.algohud.execution.AutoExecutionService
import com.example.algohud.execution.ExecutionEngine
import com.example.algohud.execution.OrderExecutor
import com.example.algohud.execution.UiProfiles
import com.example.algohud.risk.RiskManager
import com.example.algohud.risk.TradeJournal
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.delay
import com.example.algohud.core.HudUiState
import okhttp3.OkHttpClient
import java.math.BigDecimal
import java.util.concurrent.TimeUnit
import kotlin.math.hypot

/**
 * Foreground Service = właściciel całego stosu (dane, ryzyko, wykonanie) + pływający HUD.
 * Dopóki działa z powiadomieniem, system nie ubija procesu bota.
 */
class OverlayService : LifecycleService() {

    companion object {
        const val ACTION_START = "com.example.algohud.START"
        const val ACTION_STOP = "com.example.algohud.STOP"
        const val ACTION_KILL = "com.example.algohud.KILL"
        const val ACTION_MODE = "com.example.algohud.MODE"
        const val ACTION_PROFILE = "com.example.algohud.PROFILE"
        const val ACTION_RESET_KILL = "com.example.algohud.RESET_KILL"
        const val ACTION_SHIELD = "com.example.algohud.SHIELD"
        const val EXTRA_VALUE = "value"

        /** Polecenie z aplikacji (uruchamia usługę, jeśli nie działa). */
        fun command(ctx: Context, action: String, value: String? = null) = ContextCompat.startForegroundService(
            ctx, Intent(ctx, OverlayService::class.java).setAction(action).apply { value?.let { putExtra(EXTRA_VALUE, it) } }
        )
        private const val CHANNEL_ID = "algo_hud"
        private const val NOTIF_ID = 1001
        private const val MIN_PAPER_TRADES = 30

        fun start(ctx: Context) {
            // Zapamiętaj, że bot ma działać – po restarcie telefonu BootReceiver uruchomi go ponownie.
            ctx.getSharedPreferences("ui", Context.MODE_PRIVATE).edit().putBoolean("autostart", true).apply()
            ContextCompat.startForegroundService(ctx, Intent(ctx, OverlayService::class.java).setAction(ACTION_START))
        }
    }

    private val botScope = CoroutineScope(
        SupervisorJob() + Dispatchers.Default +
            CoroutineExceptionHandler { _, t -> Log.e("OverlayService", "Błąd bota", t) }
    )

    private lateinit var btnStopEntries: Button
    private lateinit var btnCloseBot: Button
    private lateinit var holdProgress: android.widget.ProgressBar
    private lateinit var tvProtection: TextView
    private lateinit var tvFreshness: TextView
    private lateinit var tvSpreadCost: TextView
    private lateinit var tvBudget: TextView
    private val hudUi = MutableStateFlow(HudUiState())
    private var renderedUi: HudUiState? = null
    private var closeBusy = false
    private val lastSpread = MutableStateFlow<com.example.algohud.risk.SpreadCost?>(null)
    @Volatile private var lastCandidate: com.example.algohud.core.OrderRequest? = null
    @Volatile private var lastCandidateAt = 0L
    private var detailDialog: android.app.AlertDialog? = null
    private lateinit var tradeJournal: TradeJournal
    private lateinit var wm: WindowManager
    private lateinit var params: WindowManager.LayoutParams
    private var rootView: LinearLayout? = null
    private lateinit var bubble: TextView
    private lateinit var panel: LinearLayout
    private lateinit var tvStatus: TextView
    private lateinit var tvCompact: TextView
    private lateinit var btnDetails: Button
    /** false = uproszczony HUD (pozycja, co dalej, ostatnie transakcje); true = wszystkie szczegóły. */
    private var showDetails = false
    private lateinit var tvMessage: TextView
    private lateinit var tvScreen: TextView
    private lateinit var tvStats: TextView
    private lateinit var tvPredict: TextView
    private lateinit var tvPlan: TextView
    private lateinit var btnMode: Button
    private lateinit var btnConfirm: Button

    private lateinit var risk: RiskManager
    private lateinit var engine: ExecutionEngine
    private lateinit var bot: BotOrchestrator
    private var council: com.example.algohud.council.AgentCouncil? = null
    private var aiNav: com.example.algohud.council.AiNavigator? = null
    private lateinit var tvCouncil: TextView
    private lateinit var tvDebate: TextView
    private var showDebate = false

    private var expanded = false

    // ───────────── Tarcza dotyku (ochrona przed „duchami” uszkodzonego ekranu) ─────────────
    private var shield: View? = null
    private var shieldParams: WindowManager.LayoutParams? = null

    /** Uchylenie tarczy: okno zostaje, ale przestaje być dotykowe – wtedy usługa ułatwień dostępu widzi XTB pod spodem. */
    private fun setShieldTouchable(touchable: Boolean) {
        val v = shield ?: return
        val p = shieldParams ?: return
        val has = p.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE == 0
        if (has == touchable) return
        p.flags = if (touchable) p.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
        else p.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        runCatching { wm.updateViewLayout(v, p) }
    }
    @Volatile private var shieldUserOffUntil = 0L

    private fun shieldEnabled() = uiPrefs().getBoolean("shield", true)

    /** Przezroczysta warstwa na cały ekran, pochłaniająca WSZYSTKIE dotknięcia. Bot klika przez ułatwienia dostępu (nie przez dotyk). */
    private fun setShield(on: Boolean) {
        if (on && shield == null) {
            val v = TextView(this).apply {
                text = "🛡 Tarcza dotyku – ekran ignoruje dotknięcia\n3× „głośność w dół” = odblokuj na 5 min"
                setTextColor(0x99FFFFFF.toInt()); textSize = 11f
                gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                setPadding(0, 0, 0, dp(110))
                setBackgroundColor(0x12000000)
                setOnTouchListener { _, _ -> true } // pochłania dotyk
            }
            val p = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
            )
            runCatching { wm.addView(v, p); shield = v; shieldParams = p }
        } else if (!on && shield != null) {
            runCatching { wm.removeView(shield) }
            shield = null
        }
    }
    @Volatile private var lastAutoAsk = 0L
    private var lastNotifKey = ""

    // ───────────── Cykl życia ─────────────

    override fun onCreate() {
        super.onCreate()
        com.example.algohud.bot.Ntfy.init(this)
        startAsForeground("Uruchamianie…")
        if (!Settings.canDrawOverlays(this)) {
            toast("Brak uprawnienia do wyświetlania nad innymi aplikacjami")
            stopSelf()
            return
        }
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        buildTradingStack()
        createOverlay()
        observeState()
        restoreMode()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_STOP -> { uiPrefs().edit().putBoolean("autostart", false).apply(); stopSelf() }
            ACTION_KILL -> if (::engine.isInitialized) { engine.stopEntries() }
            ACTION_MODE -> if (::engine.isInitialized) {
                val m = runCatching { ExecutionMode.valueOf(intent.getStringExtra(EXTRA_VALUE)!!) }.getOrNull()
                if (m != null) {
                    uiPrefs().edit().putString("mode", m.name).apply()
                    botScope.launch {
                        val blocked = engine.setMode(m, overrideGate = true)
                        toast(if (blocked != null) "🔒 $m zablokowany: $blocked" else "Tryb: $m")
                    }
                }
            }
            ACTION_PROFILE -> if (::bot.isInitialized) {
                runCatching { BotProfile.valueOf(intent.getStringExtra(EXTRA_VALUE)!!) }.getOrNull()?.let { bot.setProfile(it); toast(it.label) }
            }
            ACTION_SHIELD -> {
                val on = intent.getStringExtra(EXTRA_VALUE) != "off"
                uiPrefs().edit().putBoolean("shield", on).apply()
                toast(if (on) "🛡 Tarcza dotyku włączona (działa w trybie AUTO)" else "Tarcza dotyku wyłączona")
            }
            "RESUME_ENTRIES" -> if (::risk.isInitialized) risk.pauseEntries(false)
            ACTION_RESET_KILL -> if (::risk.isInitialized) botScope.launch {
                toast(if (risk.resetKillSwitch()) "Kill-switch zdjęty" else "Blokada dziennego limitu – do jutra")
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        stopAi()
        AutoExecutionService.aiNavigator = null
        detailDialog?.dismiss()
        AutoExecutionService.entryBlocked = null
        AutoExecutionService.entryCheck = null
        botScope.cancel()
        setShield(false)
        rootView?.let { runCatching { wm.removeView(it) } }
        rootView = null
        super.onDestroy()
    }

    // ───────────── Składanie zależności (w większym projekcie: Hilt/Koin) ─────────────

    private fun buildTradingStack() {
        val http = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()

        val news = MarketNewsEngine(botScope, http, MarketNewsEngine.Config(), KeywordSentimentScorer())

        val journal = TradeJournal(getSharedPreferences("journal", MODE_PRIVATE))
        tradeJournal = journal
        risk = RiskManager(
            prefs = getSharedPreferences("risk_state", MODE_PRIVATE),
            config = RiskManager.Config(),
            marketBlackout = { news.isInMacroBlackout() },
            journal = journal
        )

        // Wariant B (UI): wymaga włączonej usługi ułatwień dostępu i poprawnego profilu.
        AutoExecutionService.activeProfile = UiProfiles.XTB
        val executor: OrderExecutor = AccessibilityExecutor { AutoExecutionService.activeProfile }
        // Wariant A (API) – preferowany, gdy broker ma API:
        // val executor = BinanceSpotExecutor(http, apiKey, apiSecret, risk.config.allowedSymbols)

        engine = ExecutionEngine(
            risk, executor, initialMode = ExecutionMode.DRY_RUN,
            entryGate = { order ->
                lastCandidate = order; lastCandidateAt = System.currentTimeMillis()
                val threshold = uiPrefs().getString("spread_limit_pct", "25")?.toDoubleOrNull() ?: 25.0
                val cost = com.example.algohud.risk.SpreadGuard.assess(order, AutoExecutionService.screenQuote.value, threshold, System.currentTimeMillis())
                lastSpread.value = cost
                cost.block
            },
            reverseEntries = { getSharedPreferences("ui", MODE_PRIVATE).getBoolean("reverse_entries", true) },
            // AUTO dopiero po udokumentowanym dodatnim wyniku na papierze.
            positionProbe = { sym -> AutoExecutionService.instance?.xtbPositionExists(sym) },
            autoGate = { null }
        )

        // Agenci i nawigator AI odłączeni; brak wywołań API z usługi.
        council = null
        aiNav = null
        AutoExecutionService.aiNavigator = null
        AutoExecutionService.entryBlocked = { risk.entriesPaused.value || risk.killSwitch.value }
        AutoExecutionService.entryCheck = { order, quote ->
            com.example.algohud.risk.SpreadGuard.assess(order, quote,
                uiPrefs().getString("spread_limit_pct", "25")?.toDoubleOrNull() ?: 25.0, System.currentTimeMillis()).block
        }
        AutoExecutionService.navAbort = { risk.killSwitch.value }
        AutoExecutionService.navAutonomy = uiPrefs().getBoolean("nav_autonomy", true)

        bot = BotOrchestrator(
            scope = botScope,
            news = news,
            risk = risk,
            engine = engine,
            journal = journal,
            prefs = getSharedPreferences("instruments", MODE_PRIVATE),
            pyth = PythFeed(botScope, http),
            barStore = com.example.algohud.bot.BarStore(filesDir),
            equityProvider = {
                val funds = AutoExecutionService.lastAccountFunds
                when {
                    funds != null -> funds to "XTB:${AutoExecutionService.lastAccountCurrency ?: "?"}"
                    engine.mode.value == ExecutionMode.DRY_RUN -> 10_000.0 to "PAPER" // bez XTB: konto papierowe
                    else -> null
                }
            },
            council = null
        )
        bot.start()
    }

    private fun uiPrefs() = getSharedPreferences("ui", MODE_PRIVATE)

    private fun stopAi() {
        council?.cancelAll()
        aiNav?.cancel()
    }

    /** Po starcie wraca tryb wybrany w aplikacji (np. AUTO) – ponawia, dopóki ułatwienia dostępu nie będą gotowe. */
    private fun restoreMode() {
        val saved = runCatching { ExecutionMode.valueOf(uiPrefs().getString("mode", ExecutionMode.DRY_RUN.name)!!) }
            .getOrDefault(ExecutionMode.DRY_RUN)
        if (saved == ExecutionMode.DRY_RUN) return
        botScope.launch {
            repeat(60) {
                if (uiPrefs().getString("mode", null) != saved.name) return@launch // użytkownik zmienił w międzyczasie
                if (engine.setMode(saved, overrideGate = true) == null) return@launch
                kotlinx.coroutines.delay(5_000)
            }
        }
    }

    // ───────────── Nakładka ─────────────

    private fun createOverlay() {
        bubble = TextView(this).apply {
            text = "◉ HUD"
            setTextColor(Color.WHITE)
            textSize = 13f
            setPadding(dp(12), dp(8), dp(12), dp(8))
            background = roundedBg(0xE0202020.toInt(), 18)
        }
        tvStatus = TextView(this).apply { setTextColor(0xFFDDDDDD.toInt()); textSize = 11f }
        tvCompact = TextView(this).apply { setTextColor(0xFFEEEEEE.toInt()); textSize = 12.5f; setLineSpacing(0f, 1.1f) }
        showDetails = getSharedPreferences("ui", MODE_PRIVATE).getBoolean("hud_details", false)
        tvMessage = TextView(this).apply {
            setTextColor(0xFFAAAAAA.toInt()); textSize = 11f
            maxLines = 4; maxWidth = dp(280); ellipsize = TextUtils.TruncateAt.END
        }
        tvStats = TextView(this).apply { setTextColor(0xFF90CAF9.toInt()); textSize = 11f; maxWidth = dp(280); maxLines = 8 }
        tvPlan = TextView(this).apply { setTextColor(0xFFFFE082.toInt()); textSize = 11.5f; visibility = View.GONE }
        tvPredict = TextView(this).apply { setTextColor(0xFFBDBDBD.toInt()); textSize = 11.5f; visibility = View.GONE }
        tvScreen = TextView(this).apply { setTextColor(0xFFFFE082.toInt()); textSize = 11f; maxWidth = dp(280); maxLines = 3 }
        tvCouncil = TextView(this).apply { setTextColor(0xFFCE93D8.toInt()); textSize = 11.5f; maxLines = 3 }
        tvDebate = TextView(this).apply { setTextColor(0xFFE1BEE7.toInt()); textSize = 11f; maxLines = 16; visibility = View.GONE }
        btnConfirm = actionButton("✔ Potwierdź", 0xFF1565C0.toInt()) {
            botScope.launch { engine.confirmPending() }
        }.apply { visibility = View.GONE }
        tvCompact.typeface = android.graphics.Typeface.MONOSPACE
        tvCompact.setLines(2)
        tvCompact.ellipsize = TextUtils.TruncateAt.END
        tvCompact.setOnClickListener { showHudDetails() }
        fun metric(): TextView = TextView(this).apply {
            setTextColor(Color.WHITE); textSize = 11f; typeface = android.graphics.Typeface.MONOSPACE
            setLines(1); ellipsize = TextUtils.TruncateAt.END
            setOnClickListener { showHudDetails() }
        }
        tvProtection = metric(); tvFreshness = metric(); tvSpreadCost = metric(); tvBudget = metric().apply { setLines(2); textSize = 10f }
        btnStopEntries = actionButton("STOP\nWEJŚĆ", 0xFF37474F.toInt()) { engine.stopEntries() }
        btnCloseBot = actionButton("PRZYTRZYMAJ\nZAMKNIJ BOTA", 0xFF7F0000.toInt()) { }
        btnCloseBot.textSize = 10f
        holdProgress = android.widget.ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 1000; progress = 0 }
        installCloseHold()
        val killRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(btnStopEntries, LinearLayout.LayoutParams(0, dp(48), 1f))
            addView(btnCloseBot, LinearLayout.LayoutParams(0, dp(48), 1.35f).apply { leftMargin = dp(16) })
        }
        btnDetails = actionButton("SZCZEGÓŁY", 0xFF37474F.toInt()) { showHudDetails() }
        fun full() = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        listOf(tvStatus, tvPlan, tvPredict, tvStats, tvMessage).forEach { it.maxWidth = Int.MAX_VALUE; it.maxLines = 12 }
        tvCompact.maxWidth = Int.MAX_VALUE; tvCompact.setLines(2)
        tvStatus.setTextColor(0xFFEEEEEE.toInt())
        panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = roundedBg(0xE8121212.toInt(), 14)
            setPadding(dp(10), dp(8), dp(10), dp(8))
            visibility = View.GONE
            addView(tvCompact, LinearLayout.LayoutParams(-1, dp(38)))
            for (metric in listOf(tvProtection, tvFreshness, tvSpreadCost)) addView(metric, LinearLayout.LayoutParams(-1, dp(19)))
            addView(tvBudget, LinearLayout.LayoutParams(-1, dp(32)))
            addView(killRow, LinearLayout.LayoutParams(-1, dp(48)).apply { topMargin = dp(4) })
            addView(holdProgress, LinearLayout.LayoutParams(-1, dp(4)))
            addView(btnConfirm, LinearLayout.LayoutParams(-1, dp(40)))

        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(bubble)
            // Stała szerokość panelu (cały tekst widoczny, bez ucinania) – maks. 360 dp albo szerokość ekranu.
            val w = minOf(resources.displayMetrics.widthPixels * 2 / 3 - dp(12), dp(300))
            addView(panel, LinearLayout.LayoutParams(w, dp(246)).apply { topMargin = dp(4) })
        }

        params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            // NOT_FOCUSABLE: klawiatura i "aktywne okno" zostają w aplikacji brokera.
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = uiPrefs().getInt("hud_x", 0)
            y = uiPrefs().getInt("hud_y", dp(28))
        }

        bubble.setOnTouchListener(DragListener())
        wm.addView(root, params)
        rootView = root
    }

    /** Bąbel: przeciąganie po ekranie, krótkie dotknięcie = zwiń/rozwiń, puszczenie = przyklejenie do krawędzi. */
    private inner class DragListener : View.OnTouchListener {
        private val slop = ViewConfiguration.get(this@OverlayService).scaledTouchSlop
        private var startX = 0
        private var startY = 0
        private var downX = 0f
        private var downY = 0f
        private var dragging = false

        override fun onTouch(v: View, e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startX = params.x; startY = params.y
                    downX = e.rawX; downY = e.rawY
                    dragging = false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX
                    val dy = e.rawY - downY
                    if (!dragging && hypot(dx, dy) > slop) dragging = true
                    if (dragging) moveTo(startX + dx.toInt(), startY + dy.toInt())
                }
                MotionEvent.ACTION_UP -> if (dragging) {
                    uiPrefs().edit().putInt("hud_x", params.x).putInt("hud_y", params.y).apply()
                } else { v.performClick(); toggleExpanded() }
            }
            return true
        }
    }

    private fun moveTo(x: Int, y: Int) {
        val root = rootView ?: return
        val dm = resources.displayMetrics
        params.x = x.coerceIn(0, (dm.widthPixels - root.width).coerceAtLeast(0))
        params.y = y.coerceIn(0, (dm.heightPixels - root.height).coerceAtLeast(0))
        wm.updateViewLayout(root, params)
    }

    private fun snapToEdge() {
        val root = rootView ?: return
        val w = resources.displayMetrics.widthPixels
        val target = if (params.x + root.width / 2 < w / 2) 0 else (w - root.width).coerceAtLeast(0)
        ValueAnimator.ofInt(params.x, target).apply {
            duration = 180
            addUpdateListener {
                if (rootView != null) {
                    params.x = it.animatedValue as Int
                    wm.updateViewLayout(root, params)
                }
            }
            start()
        }
    }

    private fun toggleExpanded() {
        expanded = !expanded
        val proposed = android.graphics.Rect(params.x, params.y + bubble.height, params.x + panel.layoutParams.width, params.y + bubble.height + dp(250))
        if (expanded && AutoExecutionService.hudObstacles.any { android.graphics.Rect.intersects(it, proposed) }) {
            expanded = false
            toast("Przesuń HUD: ten obszar zasłania wykres lub przyciski XTB")
        }
        panel.visibility = if (expanded) View.VISIBLE else View.GONE
    }

    /** W trybie AUTO okno HUD trzyma ekran włączony (tylko gdy HUD jest widoczny; nie wymaga uprawnień). */
    private fun setKeepScreenOn(on: Boolean) {
        val root = rootView ?: return
        val has = params.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON != 0
        if (has == on) return
        params.flags = if (on) params.flags or WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
        else params.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON.inv()
        wm.updateViewLayout(root, params)
    }

    /** Na czas gestu wykonawcy HUD znika i przepuszcza dotyk, żeby nie zasłonić przycisku brokera. */
    private fun setPassThrough(on: Boolean) {
        if (on) setShield(false) // gest musi trafić w XTB, a nie w tarczę
        val root = rootView ?: return
        root.visibility = if (on) View.GONE else View.VISIBLE
        params.flags = if (on) params.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        else params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
        params.alpha = if (on) 0f else 1f
        wm.updateViewLayout(root, params)
    }

    // ───────────── Stan → UI ─────────────

    private fun observeState() {
        // Market/strategy streams are not sampled. Only the rendering projection runs at 5 Hz.
        lifecycleScope.launch {
            while (true) { projectHud(); delay(200) }
        }
        lifecycleScope.launch { hudUi.distinctUntilChanged { a, b -> a == b }.collect { renderUi(it) } }
        lifecycleScope.launch { engine.operation.collect { projectHud() } }
        lifecycleScope.launch { risk.entriesPaused.collect { projectHud() } }
        lifecycleScope.launch { risk.killSwitch.collect { projectHud() } }
        lifecycleScope.launch { AutoExecutionService.protectionStatus.collect { projectHud() } }
        lifecycleScope.launch { AutoExecutionService.brokerReading.map { it?.row?.signature to it?.row?.sl }.distinctUntilChanged().collect { projectHud() } }
        lifecycleScope.launch {
            engine.pendingConfirmation.collect { pending ->
                btnConfirm.visibility = if (pending != null) View.VISIBLE else View.INVISIBLE
                if (pending != null && !expanded) toggleExpanded()
            }
        }
        lifecycleScope.launch { AutoExecutionService.gestureActive.collect { setPassThrough(it) } }
        // Kill-switch ma pierwszeństwo: przerywa debatę rady i nawigację AI.
        lifecycleScope.launch { risk.killSwitch.collect { if (it) stopAi() } }
        council?.let { c ->
            lifecycleScope.launch {
                kotlinx.coroutines.flow.combine(c.status, kotlinx.coroutines.flow.flow { while (true) { emit(Unit); kotlinx.coroutines.delay(2_000) } }) { st, _ -> st }
                    .collect { st ->
                        val paused = AutoExecutionService.navPausedUntil > System.currentTimeMillis()
                        tvCouncil.text = "$st\n🧭 ${if (paused) "⏸ samonaprawa w pauzie • " else ""}${AutoExecutionService.navStatus}"
                    }
            }
            lifecycleScope.launch { c.transcript.collect { tvDebate.text = it.takeLast(14).joinToString("\n").ifBlank { "Brak debaty – rada rusza przy kandydacie na wejście" } } }
        }
        lifecycleScope.launch { engine.mode.collect { AutoExecutionService.selfHealEnabled = it == ExecutionMode.AUTO } }
        lifecycleScope.launch {
            kotlinx.coroutines.flow.combine(AutoExecutionService.peek, AutoExecutionService.actionActive) { a, b -> a || b }
                .collect { open -> setShieldTouchable(!open) }
        }
        // Tarcza: włączona w AUTO (jeśli nie wyłączona w aplikacji), 3× głośność w dół = przerwa 5 min / ponowne włączenie.
        lifecycleScope.launch {
            AutoExecutionService.volumeTriple.collect {
                if (shield != null) { shieldUserOffUntil = System.currentTimeMillis() + 5 * 60_000; toast("🛡 Tarcza wyłączona na 5 min") }
                else { shieldUserOffUntil = 0L; toast("🛡 Tarcza włączona") }
            }
        }
        lifecycleScope.launch {
            while (true) {
                val want = shieldEnabled() && engine.mode.value == ExecutionMode.AUTO &&
                    System.currentTimeMillis() > shieldUserOffUntil && !AutoExecutionService.gestureActive.value &&
                    System.currentTimeMillis() > AutoExecutionService.remoteSessionUntil
                setShield(want)
                kotlinx.coroutines.delay(1_000)
            }
        }
    }

    /** Uproszczony HUD: widać tylko [tvCompact] + przyciski; szczegóły po „ℹ️ Szczegóły”. */
    private fun applyDetailsVisibility() {
        tvCompact.visibility = View.VISIBLE
        listOf(tvStatus, tvStats, tvMessage, tvPlan, tvPredict, tvCouncil, tvDebate, tvScreen).forEach { it.visibility = View.GONE }

    }

    private suspend fun projectHud() {
        val now = System.currentTimeMillis()
        val s = bot.hud.value
        val op = engine.operation.value
        val symbols = risk.openSymbols()
        val pos = symbols.firstOrNull()?.let { risk.position(it) }
        val inst = pos?.symbol ?: AutoExecutionService.screenQuote.value?.instrument ?: s.symbol
        val reading = AutoExecutionService.brokerReading.value?.takeIf {
            it.quote.instrument == inst && pos != null && it.row.side == pos.side &&
                it.row.volume?.let { v -> kotlin.math.abs(kotlin.math.abs(v) - pos.quantity) < 1e-8 } == true &&
                now - it.quote.timestamp in 0..3000
        }
        val protectionOp = AutoExecutionService.protectionStatus.value
        val writing = protectionOp.active && protectionOp.symbol == inst && protectionOp.text.startsWith("SL")
        val protectionError = protectionOp.error && protectionOp.symbol == inst && now - protectionOp.at in 0..15000
        val slMissing = pos != null && !pos.paper && reading?.row?.sl == null
        val protection = when {
            pos == null -> "SL: —"
            pos.paper -> "SL PAPER: %.2f".format(pos.stopPrice)
            writing -> "SL: ZAPISUJĘ…"
            protectionError -> "SL: ZMIANA NIEPOTWIERDZONA"
            reading == null -> "SL: BRAK POTWIERDZENIA"
            reading.row.sl == null -> "SL: BRAK USTAWIONEGO STOPA"
            else -> "SL %.2f ✓".format(reading.row.sl)
        }
        val status = when {
            risk.killSwitch.value -> "BLOKADA RYZYKA • sprawdź szczegóły"
            op.active -> "${op.text} ${op.symbol}"
            pos != null -> (if (pos.paper) "PAPER " else if (reading != null) "POTWIERDZONY " else "WERYFIKUJĘ ") +
                (if (pos.side == Side.BUY) "LONG " else "SHORT ") + inst
            op.text.isNotEmpty() && now - op.at in 0..15000 -> op.text
            risk.entriesPaused.value -> "STOP WEJŚĆ • ochrona działa"
            else -> "CZEKAM NA WEJŚCIE • $inst"
        }
        val quote = AutoExecutionService.screenQuote.value?.takeIf { it.instrument == inst }
        val age = quote?.let { now - it.timestamp }?.takeIf { it >= 0 }
        val stale = age == null || age > 3000
        val freshText = if (age == null) "XTB: BRAK ODCZYTU" else "XTB %.1f s%s".format(age / 1000.0, if (stale) " • NIEAKTUALNY" else "")
        val candidate = lastCandidate?.takeIf { it.symbol == inst && now - lastCandidateAt in 0..30000 }
        val cost = candidate?.let { com.example.algohud.risk.SpreadGuard.assess(it, quote,
            uiPrefs().getString("spread_limit_pct", "25")?.toDoubleOrNull() ?: 25.0, now) }
        val spread = if (cost?.amount != null && cost.percent != null) "SPREAD %.2f %s • %.1f%% R%s".format(cost.amount, cost.currency, cost.percent,
            if (cost.block != null) " • STOP" else "") else if (candidate != null) "SPREAD: BRAK ODCZYTU • STOP" else "SPREAD: brak planowanego wejścia"
        val remaining = risk.remainingBudget()
        val budgetCurrency = AutoExecutionService.lastAccountCurrency ?: "wal.konta"
        val budget = "POZOSTAŁY BUDŻET: " + (remaining?.let { "%.2f".format(it) } ?: "—") + " $budgetCurrency\nŹródło: księga bota (szac.)"
        val closing = closeBusy || (op.active && op.text.startsWith("ZAMYKAM"))
        val severity = if (risk.killSwitch.value || (slMissing && !writing && reading != null)) 2
            else if (stale || slMissing || writing || protectionError || op.active || cost?.block != null || risk.entriesPaused.value) 1 else 0
        hudUi.value = HudUiState(status, protection, freshText, spread, budget, severity,
            risk.entriesPaused.value, closing, pos?.symbol, pos?.openedAt,
            "${if (op.active || !op.error) op.id + op.active else ""}|${protectionOp.id}:${protectionOp.active}:${protectionOp.error}|${pos?.openedAt}|${reading != null}|${risk.killSwitch.value}")
    }

    private fun renderUi(s: HudUiState) {
        val previous = renderedUi
        fun update(view: TextView, text: String) { if (view.text.toString() != text) view.text = text }
        update(tvCompact, s.status); update(tvProtection, s.protection); update(tvFreshness, s.freshness)
        update(tvSpreadCost, s.spread); update(tvBudget, s.budget)
        update(bubble, "HUD • ${engine.mode.value} • dotknij / przesuń")
        val color = when (s.severity) { 2 -> 0xFFFF5252.toInt(); 1 -> 0xFFFFCA28.toInt(); else -> Color.WHITE }
        tvCompact.setTextColor(color)
        tvProtection.setTextColor(if (s.protection.contains("BRAK")) 0xFFFFCA28.toInt() else Color.WHITE)
        update(btnStopEntries, if (s.paused) "WEJŚCIA\nSTOP" else "STOP\nWEJŚĆ")
        update(btnCloseBot, if (s.closing) "ZAMYKAM…" else "PRZYTRZYMAJ\nZAMKNIJ BOTA")
        btnCloseBot.isEnabled = s.positionSymbol != null && !s.closing
        if (previous != null && previous.eventKey != s.eventKey && android.os.SystemClock.uptimeMillis() - lastHaptic > 1000) {
            tvCompact.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
            lastHaptic = android.os.SystemClock.uptimeMillis()
        }
        renderedUi = s
        if (expanded && !closeBusy) {
            val bounds = android.graphics.Rect()
            panel.getGlobalVisibleRect(bounds)
            if (AutoExecutionService.hudObstacles.any { android.graphics.Rect.intersects(it, bounds) }) {
                expanded = false; panel.visibility = View.GONE
            }
        }
        setKeepScreenOn(engine.mode.value == ExecutionMode.AUTO && !risk.killSwitch.value)
    }
    private var lastHaptic = 0L

    private fun showHudDetails() {
        detailDialog?.dismiss()
        bot.detailsRequested = true
        val s = bot.hud.value
        val content = android.widget.ScrollView(this).apply {
            addView(TextView(this@OverlayService).apply {
                setPadding(dp(16), dp(12), dp(16), dp(12)); textSize = 13f
                text = listOf(hudUi.value.status, hudUi.value.protection, hudUi.value.spread,
                    "* Budżet: szacowana księga bota w walucie konta, z otwartym wynikiem feedu; nie pełny rachunek XTB.",
                    "STOP WEJŚĆ nie zamyka pozycji. Wznawianie w ustawieniach aplikacji.",
                    "Operacja: ${engine.operation.value.id} • ${engine.operation.value.text}",
                    s.dataStatus, s.plan, s.prediction, s.paperStats, s.lastMessage,
                    "Historia (księga bota):\n" + tradeJournal.all().takeLast(10).reversed().joinToString("\n") {
                        "${it.symbol} ${it.side} %.2f %s".format(it.pnl, if (it.paper) "PAPER" else "szacunek")
                    }).filter { it.isNotBlank() }.joinToString("\n\n")
            })
        }
        detailDialog = android.app.AlertDialog.Builder(this).setTitle("Szczegóły HUD").setView(content)
            .setPositiveButton("Zamknij", null).create().also { dialog ->
                dialog.window?.setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
                dialog.setOnDismissListener { bot.detailsRequested = false }
                dialog.show()
            }
    }

    private fun installCloseHold() {
        var hold: kotlinx.coroutines.Job? = null
        btnCloseBot.setOnTouchListener { view, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    hold?.cancel(); holdProgress.progress = 0
                    val expected = hudUi.value
                    if (expected.positionSymbol != null && expected.positionAt != null && !closeBusy) {
                        hold = lifecycleScope.launch {
                            val start = android.os.SystemClock.uptimeMillis()
                            while (android.os.SystemClock.uptimeMillis() - start < 900) {
                                holdProgress.progress = ((android.os.SystemClock.uptimeMillis() - start) * 1000 / 900).toInt()
                                delay(16)
                            }
                            if (hudUi.value.positionAt != expected.positionAt || hudUi.value.positionSymbol != expected.positionSymbol) return@launch
                            closeBusy = true; holdProgress.progress = 1000; projectHud()
                            // Execution lives outside the press coroutine: ACTION_UP cannot cancel a submitted close.
                            botScope.launch {
                                try { engine.closeBotPosition(expected.positionSymbol, expected.positionAt) }
                                finally { kotlinx.coroutines.withContext(Dispatchers.Main) { closeBusy = false; holdProgress.progress = 0; projectHud() } }
                            }
                        }
                    }
                }
                MotionEvent.ACTION_MOVE -> if (event.x < 0 || event.y < 0 || event.x > view.width || event.y > view.height) { hold?.cancel(); holdProgress.progress = 0 }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { hold?.cancel(); if (!closeBusy) holdProgress.progress = 0 }
            }
            true
        }
    }

    // ───────────── Powiadomienie / Foreground ─────────────

    private fun startAsForeground(text: String) {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Algo HUD – status bota", NotificationManager.IMPORTANCE_LOW)
        )
        val n = buildNotification(text)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIF_ID, n)
        }
    }

    private fun buildNotification(text: String): Notification {
        fun pi(action: String, code: Int) = PendingIntent.getService(
            this, code, Intent(this, OverlayService::class.java).setAction(action),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentTitle("Algo HUD")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .addAction(0, "STOP WEJŚĆ", pi(ACTION_KILL, 1))
            .addAction(0, "Zatrzymaj", pi(ACTION_STOP, 2))
            .build()
    }

    // ───────────── Pomocnicze ─────────────

    private fun dumpScreen() {
        val svc = AutoExecutionService.instance ?: return toast("Najpierw włącz usługę ułatwień dostępu (krok 3)")
        val text = svc.dumpTargetWindow() ?: return toast("XTB nie jest na ekranie – otwórz w nim instrument i spróbuj ponownie")
        getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Algo HUD dump", text))
        toast("Skopiowano zrzut (${text.lines().size} linii) – wklej go w czacie")
    }

    private fun fmtPrice(v: Double): String =
        BigDecimal.valueOf(v).setScale(if (v >= 100) 2 else 5, java.math.RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun wrap() = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
    )

    private fun roundedBg(color: Int, radiusDp: Int) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radiusDp).toFloat()
    }

    private fun actionButton(label: String, color: Int, longPress: Boolean = false, onAction: () -> Unit) =
        Button(this).apply {
            text = label
            textSize = 12f
            isAllCaps = false
            setTextColor(Color.WHITE)
            background = roundedBg(color, 10)
            minWidth = 0; minimumWidth = 0; minHeight = 0; minimumHeight = 0
            setPadding(dp(10), dp(6), dp(10), dp(6))
            layoutParams = wrap().apply { marginEnd = dp(6) }
            if (longPress) {
                // Zlecenia wymagają przytrzymania – chroni przed przypadkowym dotknięciem.
                setOnClickListener { toast("Przytrzymaj, aby wysłać") }
                setOnLongClickListener { onAction(); true }
            } else {
                setOnClickListener { onAction() }
            }
        }

    private fun toast(text: String) = ContextCompat.getMainExecutor(this).execute {
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
    }
}
