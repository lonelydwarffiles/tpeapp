package com.hound.controller

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.graphics.Path
import android.util.Log
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import java.util.Locale
import java.util.regex.Pattern

private const val ACCESSIBILITY_PREFS = "tpe_accessibility_service"
private const val ACCESSIBILITY_CONNECTED_KEY = "connected"
private const val ACCESSIBILITY_LAST_PACKAGE_KEY = "last_package"
private const val ACCESSIBILITY_LAST_EVENT_KEY = "last_event_time"
private const val MIN_BUZZ_EVENT_GAP_MS = 700L

class TpeAccessibilityService : AccessibilityService() {
    companion object {
        private const val TAG = "TpeAccessibilityService"
        private const val TAP_DURATION_MS = 50L
        private val BUZZ_PATTERN = Pattern.compile("\\bbuzz\\s*(\\d{1,2})?\\b")

        @Volatile
        var instance: TpeAccessibilityService? = null
            private set

        @Volatile
        var buzzCommandListener: ((Map<String, Any>) -> Unit)? = null

        @Volatile
        private var lastBuzzEventAtMs: Long = 0L

        fun injectTap(normX: Float, normY: Float): Boolean {
            val service = instance ?: return false
            val windowManager = service.getSystemService(WINDOW_SERVICE) as WindowManager
            val bounds = windowManager.currentWindowMetrics.bounds
            val px = normX.coerceIn(0f, 1f) * bounds.width()
            val py = normY.coerceIn(0f, 1f) * bounds.height()

            val path = Path().apply { moveTo(px, py) }
            val stroke = GestureDescription.StrokeDescription(path, 0L, TAP_DURATION_MS)
            val gesture = GestureDescription.Builder().addStroke(stroke).build()

            val dispatched = service.dispatchGesture(gesture, null, null)
            if (dispatched) {
                service.persistStatus(connected = true)
                Log.d(TAG, "Injected tap at ($px, $py)")
            }
            return dispatched
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        persistStatus(connected = true)
        Log.i(TAG, "Standalone accessibility service connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val safeEvent = event ?: return
        val packageName = safeEvent.packageName?.toString()?.trim().orEmpty()
        if (packageName.isBlank()) return

        if (safeEvent.eventType == AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED) {
            val payload = extractBuzzPayload(safeEvent, packageName)
            if (payload != null) {
                val now = System.currentTimeMillis()
                if (now - lastBuzzEventAtMs >= MIN_BUZZ_EVENT_GAP_MS) {
                    lastBuzzEventAtMs = now
                    buzzCommandListener?.invoke(payload)
                    Log.d(TAG, "Buzz command detected from notification: $payload")
                }
            }
        }

        persistStatus(connected = true, lastPackage = packageName)
    }

    override fun onInterrupt() {
        Log.w(TAG, "Standalone accessibility service interrupted")
    }

    override fun onDestroy() {
        persistStatus(connected = false)
        instance = null
        Log.i(TAG, "Standalone accessibility service destroyed")
        super.onDestroy()
    }

    private fun persistStatus(
        connected: Boolean,
        lastPackage: String? = null,
    ) {
        getSharedPreferences(ACCESSIBILITY_PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(ACCESSIBILITY_CONNECTED_KEY, connected)
            .putLong(ACCESSIBILITY_LAST_EVENT_KEY, System.currentTimeMillis())
            .apply {
                if (lastPackage != null) {
                    putString(ACCESSIBILITY_LAST_PACKAGE_KEY, lastPackage)
                }
            }
            .apply()
    }

    private fun extractBuzzPayload(
        event: AccessibilityEvent,
        packageName: String,
    ): Map<String, Any>? {
        val combinedText = event.text
            ?.joinToString(" ") { it?.toString().orEmpty() }
            ?.trim()
            .orEmpty()
        if (combinedText.isBlank()) return null

        val lower = combinedText.lowercase(Locale.US)
        val matcher = BUZZ_PATTERN.matcher(lower)
        if (!matcher.find()) return null

        val rawCount = matcher.group(1)?.trim()
        val count = rawCount?.toIntOrNull()?.coerceIn(1, 20) ?: 1
        return mapOf(
            "source" to "notification",
            "package" to packageName,
            "count" to count,
            "raw" to combinedText,
        )
    }
}