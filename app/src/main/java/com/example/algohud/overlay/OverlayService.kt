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
import com.example.algohud.core.ExecutionMode
import com.example.algohud.core.HudState
import com.example.algohud.core.Side
import com.example.algohud.data.KeywordSentimentScorer
import com.example.algohud.data.MarketNewsEngine
import com.example.algohud.execution.AccessibilityExecutor
import com.example.algohud.execution.AutoExecutionService
import com.example.algohud.execution.ExecutionEngine
import com.example.algohud.execution.OrderExecutor
import com.example.algohud.execution.UiProfiles
import com.example.algohud.risk.RiskManager
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
        private const val CHANNEL_ID = "algo_hud"
        private const val NOTIF_ID = 1001

        fun start(ctx: Context) = ContextCompat.startForegroundService(
            ctx, Intent(ctx, OverlayService::class.java).setAction(ACTION_START)
        )
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
    private lateinit var btnMode: Button
    private lateinit var btnConfirm: Button

    private lateinit var risk: RiskManager
    private lateinit var engine: ExecutionEngine
    private lateinit var bot: BotOrchestrator

    private var expanded = false
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
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_STOP -> stopSelf()
            ACTION_KILL -> if (::engine.isInitialized) botScope.launch { engine.flattenAll("KILL z powiadomienia") }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        botScope.cancel()
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

        risk = RiskManager(
            prefs = getSharedPreferences("risk_state", MODE_PRIVATE),
            config = RiskManager.Config(),
            marketBlackout = { news.isInMacroBlackout() }
        )

        // Wariant B (UI): wymaga włączonej usługi ułatwień dostępu i poprawnego profilu.
        AutoExecutionService.activeProfile = UiProfiles.EXAMPLE_BROKER
        val executor: OrderExecutor = AccessibilityExecutor { AutoExecutionService.activeProfile }
        // Wariant A (API) – preferowany, gdy broker ma API:
        // val executor = BinanceSpotExecutor(http, apiKey, apiSecret, risk.config.allowedSymbols)

        engine = ExecutionEngine(risk, executor, initialMode = ExecutionMode.DRY_RUN)

        bot = BotOrchestrator(
            scope = botScope,
            news = news,
            risk = risk,
            engine = engine,
            equityProvider = { 10_000.0 } // TODO: saldo z API brokera
        )
        bot.start()
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
            maxLines = 2; maxWidth = dp(260); ellipsize = TextUtils.TruncateAt.END
        }
        tvScreen = TextView(this).apply { setTextColor(0xFFFFE082.toInt()); textSize = 11f; maxWidth = dp(280); maxLines = 3 }
        // Dotknięcie: DRY_RUN ⇄ CONFIRM. Przytrzymanie: AUTO. Oba wymagają gotowego wykonawcy.
        btnMode = actionButton("Tryb", 0xFF455A64.toInt()) {
            val target = if (engine.mode.value == ExecutionMode.DRY_RUN) ExecutionMode.CONFIRM else ExecutionMode.DRY_RUN
            engine.setMode(target)?.let { toast("🔒 $target zablokowany: $it") }
        }.apply {
            setOnLongClickListener {
                val blocked = engine.setMode(ExecutionMode.AUTO)
                toast(if (blocked != null) "🔒 AUTO zablokowane: $blocked" else "⚠️ AUTO włączone – bot sam składa zlecenia")
                true
            }
        }
        btnConfirm = actionButton("✔ Potwierdź", 0xFF1565C0.toInt()) {
            botScope.launch { engine.confirmPending() }
        }.apply { visibility = View.GONE }

        val tradeRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(actionButton("KUP", 0xFF2E7D32.toInt(), longPress = true) {
                botScope.launch { bot.manualOrder(Side.BUY) }
            })
            addView(actionButton("SPRZEDAJ", 0xFFC62828.toInt(), longPress = true) {
                botScope.launch { bot.manualOrder(Side.SELL) }
            })
            // KILL celowo jednym dotknięciem – akcja bezpieczeństwa ma być najłatwiejsza.
            addView(actionButton("⛔ KILL", 0xFF7F0000.toInt()) {
                botScope.launch { engine.flattenAll("KILL ręczny z HUD") }
            })
        }
        val controlRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(btnMode)
            addView(btnConfirm)
            addView(actionButton("Reset KILL", 0xFF37474F.toInt(), longPress = true) {
                botScope.launch {
                    val ok = risk.resetKillSwitch()
                    toast(if (ok) "Kill-switch zdjęty" else "Blokada dziennego limitu – do jutra")
                }
            })
        }
        val toolsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(actionButton("📋 Zrzut ekranu", 0xFF5D4037.toInt()) { dumpScreen() })
        }
        panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = roundedBg(0xE0121212.toInt(), 14)
            setPadding(dp(10), dp(8), dp(10), dp(8))
            visibility = View.GONE
            addView(tvStatus)
            addView(tvScreen)
            addView(tvMessage)
            addView(tradeRow, wrap().apply { topMargin = dp(6) })
            addView(controlRow, wrap().apply { topMargin = dp(6) })
            addView(toolsRow, wrap().apply { topMargin = dp(6) })
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(bubble)
            addView(panel, wrap().apply { topMargin = dp(4) })
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

    /** Na czas gestu wykonawcy HUD znika i przepuszcza dotyk, żeby nie zasłonić przycisku brokera. */
    private fun setPassThrough(on: Boolean) {
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
            q?.instrument != null -> "$arrow %+.2f  ${q.instrument} ${q.mid?.let(::fmtPrice) ?: ""}".format(s.sentiment)
            else -> "$arrow %+.2f".format(s.sentiment) + (s.lastPrice?.let { "  %.2f".format(it) } ?: "")
        }
        tvScreen.text = when {
            q == null -> "XTB: niewidoczne – otwórz instrument w XTB"
            q.instrument == null -> "${q.appLabel}: nie rozpoznano instrumentu"
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
        btnMode.text = "Tryb: ${s.mode.name}"

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

    private fun fmtPrice(v: Double): String = BigDecimal.valueOf(v).stripTrailingZeros().toPlainString()

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
