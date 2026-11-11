package com.example.algohud

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.example.algohud.execution.AutoExecutionService
import com.example.algohud.overlay.OverlayService

/** Onboarding uprawnień + start/stop usługi. */
class MainActivity : ComponentActivity() {

    private val notifPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { refresh() }
    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        actionBar?.hide() // Android 15 (edge-to-edge) chował treść pod paskiem tytułu
        val pad = (16 * resources.displayMetrics.density).toInt()
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }
        fun button(label: String, action: () -> Unit) =
            Button(this).apply { text = label; setOnClickListener { action() } }.also { layout.addView(it) }

        TextView(this).apply {
            text = "Algo HUD"
            textSize = 24f
            setPadding(0, 0, 0, pad)
        }.also { layout.addView(it) }
        status = TextView(this).apply { setPadding(0, 0, 0, pad) }.also { layout.addView(it) }
        button("1. Zezwól na wyświetlanie nad aplikacjami") {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        }
        button("2. Zezwól na powiadomienia") {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        button("3. Włącz usługę ułatwień dostępu (Wariant B)") {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        button("▶ Uruchom HUD") {
            if (Settings.canDrawOverlays(this)) OverlayService.start(this)
            else Toast.makeText(this, "Najpierw krok 1", Toast.LENGTH_SHORT).show()
        }
        button("■ Zatrzymaj") {
            getSharedPreferences("ui", MODE_PRIVATE).edit().putBoolean("autostart", false).apply()
            stopService(Intent(this, OverlayService::class.java))
        }

        TextView(this).apply { text = "\nTryb handlu"; textSize = 18f }.also { layout.addView(it) }
        button("🤖 AUTO – bot sam handluje w XTB") { OverlayService.command(this, OverlayService.ACTION_MODE, "AUTO") }
        button("🧪 DRY_RUN – tylko symulacja") { OverlayService.command(this, OverlayService.ACTION_MODE, "DRY_RUN") }

        TextView(this).apply {
            text = "\nStrategia: wszystkie 4 działają razem automatycznie\n🕯️ Świece + AI • ⚡ RT/ORB • 📈 Zakres • 🧠 Turniej\nPierwsza z sygnałem otwiera pozycję (stały lot 0.006); plan widać w HUD."
            textSize = 15f
        }.also { layout.addView(it) }

        TextView(this).apply { text = "\nBezpieczeństwo"; textSize = 18f }.also { layout.addView(it) }
        button("Reset KILL (nie działa po dziennym limicie)") { OverlayService.command(this, OverlayService.ACTION_RESET_KILL) }
        button("🛡 Tarcza dotyku: WŁĄCZ (w AUTO ekran ignoruje dotyk)") { OverlayService.command(this, OverlayService.ACTION_SHIELD, "on") }
        button("Tarcza dotyku: wyłącz") { OverlayService.command(this, OverlayService.ACTION_SHIELD, "off") }

        button("XTB wraca samo na wierzch w AUTO: WŁĄCZ") {
            getSharedPreferences("ui", MODE_PRIVATE).edit().putBoolean("keep_xtb_front", true).apply()
            Toast.makeText(this, "Włączone", Toast.LENGTH_SHORT).show()
        }
        button("XTB wraca samo na wierzch: wyłącz") {
            getSharedPreferences("ui", MODE_PRIVATE).edit().putBoolean("keep_xtb_front", false).apply()
            Toast.makeText(this, "Wyłączone", Toast.LENGTH_SHORT).show()
        }

        TextView(this).apply { text = "\nZdalny dostęp (AnyDesk)"; textSize = 18f }.also { layout.addView(it) }
        fun ui() = getSharedPreferences("ui", MODE_PRIVATE)
        button("AnyDesk: auto-akceptuj „Rozpocząć nagrywanie?” – WŁĄCZ") {
            ui().edit().putBoolean("auto_capture", true).apply()
            Toast.makeText(this, "Włączone – działa tylko dla okna AnyDesk", Toast.LENGTH_LONG).show()
        }
        button("AnyDesk: auto-akceptacja – wyłącz") {
            ui().edit().putBoolean("auto_capture", false).apply()
            Toast.makeText(this, "Wyłączone", Toast.LENGTH_SHORT).show()
        }
        TextView(this).apply {
            text = "\nAkceptacja prośby o połączenie – tylko od zaufanych ID AnyDesk (np. ID Twojego głównego telefonu). " +
                "Obcy ID nie zostanie wpuszczony."
        }.also { layout.addView(it) }
        val allowEdit = android.widget.EditText(this).apply {
            hint = "Zaufane ID AnyDesk (np. 123 456 789), oddziel przecinkiem"
            setText(ui().getString("anydesk_allow", ""))
        }.also { layout.addView(it) }
        button("AnyDesk: zapisz zaufane ID i WŁĄCZ auto-akceptację połączeń") {
            val ids = allowEdit.text.toString().trim()
            if (ids.split(',').none { it.filter(Char::isDigit).length >= 9 || it.trim().length >= 3 }) {
                Toast.makeText(this, "Wpisz co najmniej jedno ID AnyDesk (9–10 cyfr) lub alias", Toast.LENGTH_LONG).show()
            } else {
                ui().edit().putString("anydesk_allow", ids).putBoolean("auto_anydesk_accept", true).apply()
                Toast.makeText(this, "Włączone – akceptuję tylko: $ids", Toast.LENGTH_LONG).show()
            }
        }
        button("AnyDesk: auto-akceptacja połączeń – wyłącz") {
            ui().edit().putBoolean("auto_anydesk_accept", false).apply()
            Toast.makeText(this, "Wyłączone", Toast.LENGTH_SHORT).show()
        }
        button("Skopiuj diagnostykę okna połączenia AnyDesk") {
            val d = AutoExecutionService.lastRemoteDump
            if (d == null) Toast.makeText(this, "Brak – połącz się raz z głównego telefonu", Toast.LENGTH_LONG).show()
            else {
                getSystemService(android.content.ClipboardManager::class.java)
                    .setPrimaryClip(android.content.ClipData.newPlainText("anydesk request",
                        (AutoExecutionService.lastRemoteNote ?: "") + "\n" + d))
                Toast.makeText(this, "Skopiowano – wklej w czacie", Toast.LENGTH_SHORT).show()
            }
        }
        button("Skopiuj diagnostykę okna nagrywania") {
            val d = AutoExecutionService.lastCaptureDump
            if (d == null) Toast.makeText(this, "Brak – najpierw włącz auto-akceptację i połącz się przez AnyDesk", Toast.LENGTH_LONG).show()
            else {
                getSystemService(android.content.ClipboardManager::class.java)
                    .setPrimaryClip(android.content.ClipData.newPlainText("capture dialog", d))
                Toast.makeText(this, "Skopiowano – wklej w czacie", Toast.LENGTH_SHORT).show()
            }
        }
        val scroll = ScrollView(this).apply { addView(layout) }
        // Odsunięcie od paska stanu, wycięcia aparatu i paska nawigacji.
        ViewCompat.setOnApplyWindowInsetsListener(scroll) { v, insets ->
            val b = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            v.setPadding(b.left, b.top, b.right, b.bottom)
            insets
        }
        setContentView(scroll)
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        status.text = buildString {
            appendLine("Nakładka: " + if (Settings.canDrawOverlays(this@MainActivity)) "✅" else "❌")
            appendLine("Ułatwienia dostępu: " + if (AutoExecutionService.instance != null) "✅" else "❌ (potrzebne tylko w Wariancie B)")
            append("Domyślny tryb: DRY_RUN (paper trading)")
        }
    }
}
