package com.example.algohud.council

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.os.Build
import android.util.Base64
import android.view.Display
import com.example.algohud.execution.AutoExecutionService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayOutputStream
import kotlin.coroutines.resume

/**
 * Zrzut ekranu przez AccessibilityService.takeScreenshot (Android 11+), zamiast MediaProjection:
 *  - bez okna zgody przy każdym starcie (Android 14+ wymaga nowej zgody MediaProjection za każdym razem –
 *    na telefonie z uszkodzonym ekranem obsługiwanym przez AnyDesk to byłby problem),
 *  - bez dodatkowej usługi pierwszoplanowej typu mediaProjection.
 * Wymaga android:canTakeScreenshot="true" w accessibility_config.xml.
 * Gdy XTB blokuje zrzuty (FLAG_SECURE) albo Android < 11 → null, a Obserwator pracuje na tekście drzewa widoków.
 */
object ScreenGrabber {
    @Volatile var lastError: String? = null
        private set

    suspend fun jpegBase64(maxWidth: Int = 720, quality: Int = 70): String? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) { lastError = "Android < 11 – brak zrzutów z ułatwień dostępu"; return null }
        val svc = AutoExecutionService.instance ?: run { lastError = "usługa ułatwień dostępu nieaktywna"; return null }
        val bmp = withTimeoutOrNull(4_000L) { capture(svc) } ?: return null
        return withContext(Dispatchers.Default) {
            try {
                val scaled = if (bmp.width > maxWidth) Bitmap.createScaledBitmap(bmp, maxWidth, bmp.height * maxWidth / bmp.width, true) else bmp
                val out = ByteArrayOutputStream()
                scaled.compress(Bitmap.CompressFormat.JPEG, quality, out)
                if (scaled !== bmp) scaled.recycle()
                Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
            } finally { bmp.recycle() }
        }
    }

    @androidx.annotation.RequiresApi(Build.VERSION_CODES.R)
    private suspend fun capture(svc: AccessibilityService): Bitmap? = suspendCancellableCoroutine { cont ->
        svc.takeScreenshot(Display.DEFAULT_DISPLAY, svc.mainExecutor, object : AccessibilityService.TakeScreenshotCallback {
            override fun onSuccess(result: AccessibilityService.ScreenshotResult) {
                val hb = result.hardwareBuffer
                val bmp = try {
                    Bitmap.wrapHardwareBuffer(hb, result.colorSpace)?.copy(Bitmap.Config.ARGB_8888, false)
                } finally { hb.close() }
                lastError = if (bmp == null) "nie udało się odczytać zrzutu" else null
                if (cont.isActive) cont.resume(bmp)
            }
            override fun onFailure(errorCode: Int) {
                lastError = when (errorCode) {
                    AccessibilityService.ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT -> "zrzuty za często"
                    AccessibilityService.ERROR_TAKE_SCREENSHOT_NO_ACCESSIBILITY_ACCESS -> "brak uprawnienia canTakeScreenshot (wyłącz i włącz usługę)"
                    AccessibilityService.ERROR_TAKE_SCREENSHOT_SECURE_WINDOW -> "XTB blokuje zrzuty ekranu"
                    else -> "błąd zrzutu ($errorCode)"
                }
                if (cont.isActive) cont.resume(null)
            }
        })
    }
}
