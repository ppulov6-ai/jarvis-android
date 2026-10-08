package io.clawdroid.assistant

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.util.Log
import android.view.Display
import io.clawdroid.feature.chat.voice.ScreenshotSource
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.delay
import io.clawdroid.diagnostics.DiagnosticEvents
import kotlin.coroutines.resume

class AccessibilityScreenshotSource : ScreenshotSource {

    @Volatile
    private var service: AccessibilityService? = null

    override val isAvailable: Boolean get() = service != null

    fun setService(s: AccessibilityService) {
        service = s
    }

    fun clearService() {
        service = null
    }

    override suspend fun takeScreenshot(): Bitmap? {
        return withTimeoutOrNull(5_000) {
            var result = capture()
            if (result == null && lastError == AccessibilityService.ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT) {
                delay(400)
                result = capture()
            }
            result
        }
    }

    @Volatile private var lastError: Int? = null
    private suspend fun capture(): Bitmap? {
        val svc = service ?: return null
        return suspendCancellableCoroutine { cont ->
            svc.takeScreenshot(
                Display.DEFAULT_DISPLAY,
                svc.mainExecutor,
                object : AccessibilityService.TakeScreenshotCallback {
                    override fun onSuccess(result: AccessibilityService.ScreenshotResult) {
                        lastError = null
                        val hwBitmap = try { Bitmap.wrapHardwareBuffer(result.hardwareBuffer, result.colorSpace) }
                        finally { result.hardwareBuffer.close() }
                        val swBitmap = hwBitmap?.copy(Bitmap.Config.ARGB_8888, false)
                        hwBitmap?.recycle()
                        if (cont.isActive) cont.resume(swBitmap) { _, bitmap, _ -> bitmap?.recycle() } else swBitmap?.recycle()
                    }

                    override fun onFailure(errorCode: Int) {
                        lastError = errorCode
                        DiagnosticEvents.record("tool", when (errorCode) {
                            AccessibilityService.ERROR_TAKE_SCREENSHOT_SECURE_WINDOW -> "screenshot_secure_window"
                            AccessibilityService.ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT -> "screenshot_interval"
                            AccessibilityService.ERROR_TAKE_SCREENSHOT_NO_ACCESSIBILITY_ACCESS -> "screenshot_access_denied"
                            else -> "screenshot_capture_error"
                        })
                        if (cont.isActive) cont.resume(null)
                    }
                }
            )
        }
    }

    companion object {
        private const val TAG = "A11yScreenshotSource"
    }
}
