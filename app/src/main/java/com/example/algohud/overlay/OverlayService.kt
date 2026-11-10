package com.example.algohud.overlay

import android.animation.ValueAnimator
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.concurrent.TimeUnit
import kotlin.math.hypot

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

        fun command(ctx: Context, action: String, value: String? = null) = ContextCompat.startForegroundService(
            ctx, Intent(ctx, OverlayService::class.java).setAction(action).apply { value?.let { putExtra(EXTRA_VALUE, it) } }
        )

        private const val CHANNEL_ID = "algo_hud"
        private const val NOTIF_ID = 1001

        fun start(ctx: Context) {
            ctx.getSharedPreferences("ui", Context.MODE_PRIVATE).edit().putBoolean("autostart", true).apply()
            ContextCompat.startForegroundService(ctx, Intent(ctx, OverlayService::class.java).setAction(ACTION_START))
        }
    }

    private val botScope = CoroutineScope(
        SupervisorJob() + Dispatchers.Default +
            CoroutineExceptionHandler { _, t -> Log.e("OverlayService", "Błąd orkiestratora", t) }
    )

    private lateinit var wm: WindowManager
    private lateinit var params: WindowManager.LayoutParams
    private var rootView: LinearLayout? = null
    private lateinit var bubble: TextView
    private lateinit var panel: LinearLayout
    private lateinit var tvStatus: TextView
    private lateinit var tvMessage: TextView
    private lateinit var tvStats: TextView
    private lateinit var btnConfirm: Button

    private lateinit var risk: RiskManager
    private lateinit var engine: ExecutionEngine
    private lateinit var bot: BotOrchestrator

    private var expanded = false
    private var shield: View? = null
    private var shieldParams: WindowManager.LayoutParams? = null
    @Volatile private var shieldUserOffUntil = 0L
    private var lastNotifKey = ""

    private fun shieldEnabled() = uiPrefs().getBoolean("shield", true)

    private fun setShieldTouchable(touchable: Boolean) {
        val v = shield ?: return
        val p = shieldParams ?: return
        val has = p.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE == 0
        if (has == touchable) return
        p.flags = if (touchable) p.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
        else p.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        runCatching { wm.updateViewLayout(v, p) }
    }

    private fun setShield(on: Boolean) {
        if (on && shield == null) {
            val v = TextView(this).apply {
                text = "🛡 Tarcza dotyku aktywna\n3× głośność w dół = pauza na 5 min"
                setTextColor(0x99FFFFFF.toInt())
                textSize = 11f
                gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                setPadding(0, 0, 0, dp(110))
                setBackgroundColor(0x12000000)
                setOnTouchListener { _, _ -> true }
            }
            val p = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
            )
            runCatching {
                wm.addView(v, p)
                shield = v
                shieldParams = p
            }
        } else if (!on && shield != null) {
            runCatching { wm.removeView(shield) }
            shield = null
            shieldParams = null
        }
    }

    override fun onCreate() {
        super.onCreate()
        startAsForeground("Uruchamianie Algo HUD…")
        if (!Settings.canDrawOverlays(this)) {
            toast("Brak uprawnienia do nakładki")
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
            ACTION_STOP -> {
                uiPrefs().edit().putBoolean("autostart", false).apply()
                stopSelf()
            }
            ACTION_KILL -> if (::engine.isInitialized) botScope.launch { engine.flattenAll("KILL ręczny") }
            ACTION_MODE -> if (::engine.isInitialized) {
                val m = runCatching { ExecutionMode.valueOf(intent.getStringExtra(EXTRA_VALUE)!!) }.getOrNull()
                if (m != null) {
                    uiPrefs().edit().putString("mode", m.name).apply()
                    botScope.launch {
                        val blocked = engine.setMode(m, overrideGate = true)
                        toast(if (blocked != null) "🔒 Zablokowany: $blocked" else "Tryb: $m")
                    }
                }
            }
            ACTION_PROFILE -> if (::bot.isInitialized) {
                runCatching { BotProfile.valueOf(intent.getStringExtra(EXTRA_VALUE)!!) }.getOrNull()?.let {
                    bot.setProfile(it)
                    toast(it.label)
                }
            }
            ACTION_SHIELD -> {
                val on = intent.getStringExtra(EXTRA_VALUE) != "off"
                uiPrefs().edit().putBoolean("shield", on).apply()
                toast(if (on) "Tarcza aktywna w AUTO" else "Tarcza wyłączona")
            }
            ACTION_RESET_KILL -> if (::risk.isInitialized) botScope.launch {
                toast(if (risk.resetKillSwitch()) "Odblokowano KILL" else "Limit dzienny – blokada do jutra")
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

        AutoExecutionService.activeProfile = UiProfiles.XTB
        val executor: OrderExecutor = AccessibilityExecutor { AutoExecutionService.activeProfile }

        engine = ExecutionEngine(
            risk = risk,
            executor = executor,
            initialMode = ExecutionMode.DRY_RUN,
            positionProbe = { sym -> AutoExecutionService.instance?.xtbPositionExists(sym) },
            autoGate = { null }
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
                    funds != null -> funds to "XTB:${AutoExecutionService.lastAccountCurrency ?: "PLN"}"
                    engine.mode.value == ExecutionMode.DRY_RUN -> 10_000.0 to "PAPER"
                    else -> null
                }
            }
        )
        bot.start()
    }

    private fun uiPrefs() = getSharedPreferences("ui", MODE_PRIVATE)

    private fun restoreMode() {
        val saved = runCatching { ExecutionMode.valueOf(uiPrefs().getString("mode", ExecutionMode.DRY_RUN.name)!!) }
            .getOrDefault(ExecutionMode.DRY_RUN)
        if (saved == ExecutionMode.DRY_RUN) return
        botScope.launch {
            repeat(30) {
                if (engine.setMode(saved, overrideGate = true) == null) return@launch
                delay(3_000)
            }
        }
    }

    private fun createOverlay() {
        bubble = TextView(this).apply {
            text = "◉ HUD"
            setTextColor(Color.WHITE)
            textSize = 13f
            setPadding(dp(12), dp(8), dp(12), dp(8))
