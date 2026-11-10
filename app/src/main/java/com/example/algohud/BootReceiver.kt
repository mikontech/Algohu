package com.example.algohud

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Settings
import com.example.algohud.overlay.OverlayService

/** Po restarcie telefonu albo aktualizacji aplikacji uruchamia bota, jeśli był włączony (nie ręcznie zatrzymany). */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val autostart = ctx.getSharedPreferences("ui", Context.MODE_PRIVATE).getBoolean("autostart", false)
        if (autostart && Settings.canDrawOverlays(ctx)) runCatching { OverlayService.start(ctx) }
    }
}
