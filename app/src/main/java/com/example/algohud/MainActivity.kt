package com.example.algohud

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import com.example.algohud.execution.AutoExecutionService
import com.example.algohud.overlay.OverlayService

/** Onboarding uprawnień + start/stop usługi. */
class MainActivity : ComponentActivity() {

    private val notifPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { refresh() }
    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (16 * resources.displayMetrics.density).toInt()
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }
        fun button(label: String, action: () -> Unit) =
            Button(this).apply { text = label; setOnClickListener { action() } }.also { layout.addView(it) }

        status = TextView(this).also { layout.addView(it) }
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
        button("■ Zatrzymaj") { stopService(Intent(this, OverlayService::class.java)) }
        setContentView(layout)
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
