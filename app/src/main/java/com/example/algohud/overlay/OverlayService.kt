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

    private lateinit var wm: WindowManager
    private lateinit var params: WindowManager.LayoutParams
    private var rootView: LinearLayout? = null
    private lateinit var bubble: TextView
    private lateinit var panel: LinearLayout
    private lateinit var tvStatus: TextView
    private lateinit var tvMessage: TextView
    private lateinit var tvScreen: TextView
    private lateinit var tvStats: TextView
    private lateinit var btnMode: Button
    private lateinit var btnConfirm: Button

    private lateinit var risk: RiskManager
    private lateinit var engine: ExecutionEngine
    private lateinit var bot: BotOrchestrator

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
            ACTION_KILL -> if (::engine.isInitialized) botScope.launch { engine.flattenAll("KILL z powiadomienia") }
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
            ACTION_RESET_KILL -> if (::risk.isInitialized) botScope.launch {
                toast(if (risk.resetKillSwitch()) "Kill-switch zdjęty" else "Blokada dziennego limitu – do jutra")
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
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
            // AUTO dopiero po udokumentowanym dodatnim wyniku na papierze.
            positionProbe = { sym -> AutoExecutionService.instance?.xtbPositionExists(sym) },
            autoGate = {
                val s = journal.stats.value
                when {
                    s.trades < MIN_PAPER_TRADES -> "za mało transakcji papierowych (${s.trades}/$MIN_PAPER_TRADES)"
                    s.avgR <= 0.0 -> "wynik papierowy nie jest dodatni (E=%+.2fR)".format(s.avgR)
                    else -> null
                }
            }
        )

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
            }
        )
        bot.start()
    }

    private fun uiPrefs() = getSharedPreferences("ui", MODE_PRIVATE)

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
        tvMessage = TextView(this).apply {
            setTextColor(0xFFAAAAAA.toInt()); textSize = 11f
            maxLines = 4; maxWidth = dp(280); ellipsize = TextUtils.TruncateAt.END
        }
        tvStats = TextView(this).apply { setTextColor(0xFF90CAF9.toInt()); textSize = 11f; maxWidth = dp(280); maxLines = 8 }
        tvScreen = TextView(this).apply { setTextColor(0xFFFFE082.toInt()); textSize = 11f; maxWidth = dp(280); maxLines = 3 }
        btnConfirm = actionButton("✔ Potwierdź", 0xFF1565C0.toInt()) {
            botScope.launch { engine.confirmPending() }
        }.apply { visibility = View.GONE }
        // HUD bez przycisków sterowania – bot działa sam. Zostaje tylko awaryjny KILL (sterowanie: aplikacja Algo HUD).
        val killRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(actionButton("⛔ KILL", 0xFF7F0000.toInt(), longPress = true) {
                botScope.launch { engine.flattenAll("KILL ręczny z HUD") }
            })
            addView(btnConfirm)
        }
        fun full() = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        listOf(tvStatus, tvStats, tvMessage).forEach { it.maxWidth = Int.MAX_VALUE; it.maxLines = 12 }
        tvStatus.setTextColor(0xFFEEEEEE.toInt())
        panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = roundedBg(0xE8121212.toInt(), 14)
            setPadding(dp(10), dp(8), dp(10), dp(8))
            visibility = View.GONE
            addView(tvStatus, full())
            addView(tvStats, full().apply { topMargin = dp(4) })
            addView(tvMessage, full().apply { topMargin = dp(4) })
            addView(killRow, wrap().apply { topMargin = dp(6) })
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(bubble)
            // Stała szerokość panelu (cały tekst widoczny, bez ucinania) – maks. 360 dp albo szerokość ekranu.
            val w = minOf(resources.displayMetrics.widthPixels - dp(12), dp(360))
            addView(panel, LinearLayout.LayoutParams(w, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(4) })
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
            x = dp(16)
            y = dp(160)
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
                MotionEvent.ACTION_UP -> if (dragging) snapToEdge() else { v.performClick(); toggleExpanded() }
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
        lifecycleScope.launch { bot.hud.collect { render(it) } }
        lifecycleScope.launch {
            engine.pendingConfirmation.collect { pending ->
                btnConfirm.visibility = if (pending != null) View.VISIBLE else View.GONE
                if (pending != null && !expanded) toggleExpanded()
            }
        }
        lifecycleScope.launch { AutoExecutionService.gestureActive.collect { setPassThrough(it) } }
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

    private fun render(s: HudState) {
        val arrow = when {
            s.sentiment > 0.15 -> "▲"
            s.sentiment < -0.15 -> "▼"
            else -> "■"
        }
        val q = s.screen?.takeIf { it.isFresh() }
        bubble.text = when {
            s.killSwitch -> "⛔ KILL"
            q?.instrument != null -> "${q.instrument} ${q.mid?.let(::fmtPrice) ?: ""} • ${s.mode.name}"
            else -> "Algo HUD • ${s.mode.name}"
        }
        tvScreen.text = when {
            q == null -> "XTB: niewidoczne"
            q.instrument == null -> "${q.appLabel}: otwórz ekran instrumentu"
            else -> buildString {
                append(q.appLabel).append(": ").append(q.instrument)
                q.bid?.let { append("  S ").append(fmtPrice(it)) }
                q.ask?.let { append("  K ").append(fmtPrice(it)) }
                append("  📰 %+.2f (%d)".format(s.screenSentiment, s.screenNews))
            }
        }
        (bubble.background as GradientDrawable).setColor(
            when {
                s.killSwitch -> 0xE0B71C1C.toInt()
                s.sentiment > 0.15 -> 0xE01B5E20.toInt()
                s.sentiment < -0.15 -> 0xE0880E4F.toInt()
                else -> 0xE0202020.toInt()
            }
        )
        tvStatus.text = "${s.symbol} • ${if (s.feedConnected) "● LIVE" else "○ offline"} • PnL dnia: %+.2f"
            .format(s.dailyPnl)
        tvMessage.text = s.lastMessage
        setKeepScreenOn(s.mode == ExecutionMode.AUTO && !s.killSwitch)
        tvStats.text = listOf(s.learning, s.paperStats).filter { it.isNotBlank() }.joinToString("\n")
        if (s.status.isNotEmpty()) tvStatus.text = s.status
        tvScreen.visibility = View.GONE

        val key = "${s.mode}|${s.killSwitch}|${s.feedConnected}"
        if (key != lastNotifKey) { // nie aktualizuj powiadomienia przy każdym ticku
            lastNotifKey = key
            val text = if (s.killSwitch) "⛔ KILL-SWITCH aktywny" else "Tryb ${s.mode} • ${if (s.feedConnected) "online" else "offline"}"
            getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification(text))
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
            .addAction(0, "⛔ KILL", pi(ACTION_KILL, 1))
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
