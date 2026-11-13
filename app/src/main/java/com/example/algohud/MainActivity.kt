package com.example.algohud

import com.example.algohud.council.AiSettings
import com.example.algohud.council.AiProvider
import com.example.algohud.council.AiRoute
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import androidx.core.widget.doAfterTextChanged
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

        val reverseSwitch = android.widget.Switch(this).apply {
            text = "Odwracaj nowe wejścia: LONG ↔ SHORT"
            isChecked = getSharedPreferences("ui", MODE_PRIVATE).getBoolean("reverse_entries", true)
            setOnCheckedChangeListener { _, enabled ->
                getSharedPreferences("ui", MODE_PRIVATE).edit().putBoolean("reverse_entries", enabled).apply()
                Toast.makeText(this@MainActivity, "Nowe wejścia: " + if (enabled) "odwrócone" else "normalne", Toast.LENGTH_LONG).show()
            }
        }
        layout.addView(reverseSwitch)
        // ───────────── Rada AI (4 agenty) i samonaprawa widoku ─────────────
        TextView(this).apply { text = "\n🧠 Rada AI (4 agenty)"; textSize = 18f }.also { layout.addView(it) }
        fun council() = getSharedPreferences("council", MODE_PRIVATE)
        TextView(this).apply {
            text = "Tryb oszczędny: do 4 zapytań/debatę, nie częściej niż co 10 min, maks. 12 debat/dobę UTC. " +
                "Maks. 60 zapytań/dostawcę/dobę łącznie z nawigatorem. Po HTTP 429 przerwa min. 15 min. " +
                "Analiza świec nadal działa lokalnie co sekundę. Limity konta mogą być niższe. " +
                "Darmowość zależy od planu u dostawcy; aplikacja nie zmienia rozliczeń konta."
        }.also { layout.addView(it) }
        fun selectAi(route: AiRoute) {
            council().edit().putString("ai_route", route.name).apply()
            Toast.makeText(this, "Dostawca AI: ${route.name}; zmiana dla następnej debaty", Toast.LENGTH_LONG).show()
            refresh()
        }
        button("AI: Gemini + Groq (oba klucze)") { selectAi(AiRoute.GEMINI_GROQ) }
        button("AI: tylko Gemini") { selectAi(AiRoute.GEMINI) }
        button("AI: tylko Groq") { selectAi(AiRoute.GROQ) }
        button("AI: Claude (dotychczasowy)") { selectAi(AiRoute.CLAUDE) }
        TextView(this).apply {
            text = "Gemini + Groq: Gemini = Obserwator/Dowódca/nawigator; Groq = Strateg/Sceptyk. " +
                "Wybrany dostawca otrzymuje dane analizy, a przy ocenie ekranu także zrzut XTB. " +
                "Po 502/503/504 jedna ponowna próba u tego samego dostawcy. Klucze zostają po restarcie i aktualizacji APK; odinstalowanie usuwa dane."
        }.also { layout.addView(it) }
        for (provider in listOf(AiProvider.GEMINI, AiProvider.GROQ, AiProvider.CLAUDE)) {
            val keyEdit = android.widget.EditText(this).apply {
                hint = "${provider.name}: " + if (AiSettings.key(council(), provider).isNullOrBlank()) "wklej klucz API" else "klucz zapisany ✓"
                inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
                isSaveEnabled = false
            }.also { layout.addView(it) }
            val keyStatus = TextView(this).also { layout.addView(it) }
            fun keyStatusRefresh() { keyStatus.text = "${provider.name}: " + if (AiSettings.key(council(), provider).isNullOrBlank()) "brak klucza" else "klucz zapisany na telefonie ✓" }
            keyStatusRefresh()
            keyEdit.doAfterTextChanged { value ->
                val key = value.toString().trim()
                if (key.isNotBlank() && key.none { it.isWhitespace() }) {
                    council().edit().putString(AiSettings.keyPref(provider), key).apply()
                    keyStatusRefresh()
                }
            }
            button("Zapisz klucz ${provider.name}") {
                val key = keyEdit.text.toString().trim()
                if (key.isBlank() || key.any { it.isWhitespace() }) Toast.makeText(this, "Wklej poprawny klucz bez spacji", Toast.LENGTH_LONG).show()
                else {
                    if (!council().edit().putString(AiSettings.keyPref(provider), key).commit()) {
                        keyStatus.text = "Błąd zapisu klucza"; return@button
                    }
                    keyStatusRefresh()
                    keyEdit.setText(""); keyEdit.hint = "${provider.name}: klucz zapisany ✓"; refresh()
                }
            }
        }
        val apiStatus = TextView(this).also { layout.addView(it) }
        var testingApi = false
        button("Testuj zapisane klucze i modele API") {
            if (!testingApi) lifecycleScope.launch {
                testingApi = true
                apiStatus.text = "Test API trwa…"
                try {
                    val plan = AiSettings.plan(council())
                    val client = com.example.algohud.council.ClaudeClient.configured(council())
                    val results = mutableListOf<String>()
                    for (model in listOf(plan.fast, plan.deep).distinct()) {
                        val result = try {
                            client.askJson(model, "Return only a JSON object.", "Return {\"ok\":true}", maxTokens = 100)
                            "$model: połączenie działa ✓"
                        } catch (e: kotlinx.coroutines.CancellationException) { throw e }
                        catch (e: Exception) { "$model: ${e.message}" }
                        results += result
                        apiStatus.text = results.joinToString("\n")
                    }
                } finally { testingApi = false }
            }
        }
        for ((pref, defaultModel) in listOf("gemini_model" to AiSettings.GEMINI_MODEL, "groq_model" to AiSettings.GROQ_MODEL)) {
            val modelEdit = android.widget.EditText(this).apply {
                hint = "Model ${pref.substringBefore('_')}"
                setText(council().getString(pref, defaultModel))
                isSingleLine = true
            }.also { layout.addView(it) }
            button("Zapisz model ${pref.substringBefore('_')}") {
                val model = modelEdit.text.toString().trim()
                if (!model.matches(Regex("[A-Za-z0-9._/-]+")) || (pref == "gemini_model" && model.contains('/')))
                    Toast.makeText(this, "Nieprawidłowa nazwa modelu", Toast.LENGTH_LONG).show()
                else { council().edit().putString(pref, model).apply(); Toast.makeText(this, "Zapisano model", Toast.LENGTH_SHORT).show() }
            }
        }
        fun councilMode(m: com.example.algohud.council.CouncilMode, note: String) {
            council().edit().putString(com.example.algohud.council.AgentCouncil.PREF_MODE, m.name).apply()
            Toast.makeText(this, "Rada AI: ${m.name} – $note", Toast.LENGTH_LONG).show(); refresh()
        }
        button("Rada: ADVISORY – tylko komentuje (domyślnie)") { councilMode(com.example.algohud.council.CouncilMode.ADVISORY, "bot wchodzi jak dotąd") }
        button("Rada: GATE – może zawetować wejście") { councilMode(com.example.algohud.council.CouncilMode.GATE, "weto blokuje wejście, błąd API = brak wejścia") }
        button("Rada: OFF") { councilMode(com.example.algohud.council.CouncilMode.OFF, "wyłączona") }
        button("Nawigator AI (zapas, gdy bot nie znajdzie GOLD): WŁĄCZ") { council().edit().putBoolean("ai_nav", true).apply(); refresh() }
        button("Nawigator AI: wyłącz") { council().edit().putBoolean("ai_nav", false).apply(); refresh() }
        button("Samonaprawa widoku we WSZYSTKICH trybach (też DRY_RUN): WŁĄCZ") {
            getSharedPreferences("ui", MODE_PRIVATE).edit().putBoolean("nav_autonomy", true).apply()
            AutoExecutionService.navAutonomy = true; refresh()
        }
        button("Samonaprawa widoku: tylko w AUTO") {
            getSharedPreferences("ui", MODE_PRIVATE).edit().putBoolean("nav_autonomy", false).apply()
            AutoExecutionService.navAutonomy = false; refresh()
        }
        button("⏸ Wstrzymaj samonaprawę na 30 min (używam telefonu)") {
            AutoExecutionService.navPausedUntil = System.currentTimeMillis() + 30 * 60_000L; refresh()
        }
        button("▶ Wznów samonaprawę") { AutoExecutionService.navPausedUntil = 0L; refresh() }

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
        button("📡 Skopiuj raport danych (źródło, czas, jednostka, status)") {
            val d = com.example.algohud.bot.DataQuality.lastReport
            if (d == null) Toast.makeText(this, "Brak – uruchom bota i otwórz bilet GOLD w XTB", Toast.LENGTH_LONG).show()
            else {
                getSystemService(android.content.ClipboardManager::class.java)
                    .setPrimaryClip(android.content.ClipData.newPlainText("raport danych", d))
                Toast.makeText(this, "Skopiowano – wklej w czacie", Toast.LENGTH_SHORT).show()
            }
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
            appendLine("Domyślny tryb: DRY_RUN (paper trading)")
            val cp = getSharedPreferences("council", MODE_PRIVATE)
            appendLine("Rada AI: " + (cp.getString(com.example.algohud.council.AgentCouncil.PREF_MODE, null) ?: "ADVISORY") +
                " • " + AiSettings.route(cp).name + " • klucze: " + (if (AiSettings.hasKeys(cp)) "✅" else "❌ uzupełnij") +
                " • nawigator AI: " + (if (cp.getBoolean("ai_nav", true)) "✅" else "wył."))
            val paused = AutoExecutionService.navPausedUntil > System.currentTimeMillis()
            append("Samonaprawa widoku: " + (if (getSharedPreferences("ui", MODE_PRIVATE).getBoolean("nav_autonomy", true)) "wszystkie tryby" else "tylko AUTO") +
                (if (paused) " (⏸ pauza)" else "") + " • zrzuty ekranu: " + (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) "✅ Android 11+" else "❌ za stary Android"))
        }
    }
}
