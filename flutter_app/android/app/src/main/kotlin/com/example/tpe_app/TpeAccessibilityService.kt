package com.example.tpe_app

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.graphics.Path
import android.util.Log
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent

private const val ACCESSIBILITY_PREFS = "tpe_accessibility_service"
private const val ACCESSIBILITY_CONNECTED_KEY = "connected"
private const val ACCESSIBILITY_LAST_PACKAGE_KEY = "last_package"
private const val ACCESSIBILITY_LAST_EVENT_KEY = "last_event_time"

class TpeAccessibilityService : AccessibilityService() {
    companion object {
        private const val TAG = "TpeAccessibilityService"
        private const val TAP_DURATION_MS = 50L

        @Volatile
        var instance: TpeAccessibilityService? = null
            private set

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
        val packageName = event?.packageName?.toString()?.trim().orEmpty()
        if (packageName.isBlank()) return
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
}