package com.tpeapp.review

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.util.Log
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent

/**
 * RemoteControlService — a minimal [AccessibilityService] whose sole purpose
 * is to inject gestures onto the device screen on behalf of the remote
 * accountability partner.
 *
 * Gesture injection flow:
 *  1. The partner sends a normalised tap coordinate (x ∈ [0,1], y ∈ [0,1])
 *     over the WebRTC DataChannel.
 *  2. [ScreenShareService] (Flutter side) receives the DataChannel message and
 *     calls `ScreenShareChannel.injectTap(x, y)` over the MethodChannel.
 *  3. [ScreenShareChannel] (Kotlin bridge) resolves physical pixel coordinates
 *     and calls [RemoteControlService.injectTap].
 *  4. [dispatchGesture] sends a 50 ms tap stroke at the computed coordinates.
 *
 * On rooted devices where this service is not available (or not enabled),
 * [ScreenShareChannel] falls back to [RemoteInputDispatcher] which executes
 * `su -c input tap X Y`.
 *
 * The user must manually enable this service in Android Settings →
 * Accessibility. A dedicated `remote_control_service.xml` config declares
 * `canPerformGestures="true"` which is required by the Android framework.
 */
class RemoteControlService : AccessibilityService() {

    companion object {
        private const val TAG = "RemoteControlService"

        /** Live instance set in [onServiceConnected] and cleared in [onDestroy]. */
        @Volatile
        var instance: RemoteControlService? = null
            private set

        /**
         * Inject a single tap gesture at normalised coordinates.
         *
         * @param normX Horizontal position in [0.0, 1.0] (0 = left edge).
         * @param normY Vertical position   in [0.0, 1.0] (0 = top  edge).
         * @return `true` if the gesture was dispatched; `false` if the service
         *         is not running (caller should fall back to `su -c input tap`).
         */
        fun injectTap(normX: Float, normY: Float): Boolean {
            val svc = instance ?: return false
            val wm = svc.getSystemService(WINDOW_SERVICE) as WindowManager
            val bounds = wm.currentWindowMetrics.bounds
            val px = normX * bounds.width()
            val py = normY * bounds.height()

            val path = Path().apply { moveTo(px, py) }
            // 50 ms stroke duration is long enough to register as a tap but
            // short enough that the system treats it as a discrete click.
            val stroke = GestureDescription.StrokeDescription(path, 0L, 50L)
            val gesture = GestureDescription.Builder().addStroke(stroke).build()

            svc.dispatchGesture(gesture, null, null)
            Log.d(TAG, "Injected tap at ($px, $py)")
            return true
        }
    }

    // ------------------------------------------------------------------
    //  Lifecycle
    // ------------------------------------------------------------------

    override fun onServiceConnected() {
        // Programmatically set CAPABILITY_CAN_PERFORM_GESTURES in addition to
        // the XML declaration so that runtime serviceInfo overrides are picked up.
        val info = serviceInfo
        info.capabilities =
            info.capabilities or AccessibilityServiceInfo.CAPABILITY_CAN_PERFORM_GESTURES
        serviceInfo = info
        instance = this
        Log.i(TAG, "RemoteControlService connected — gesture injection ready")
    }

    override fun onDestroy() {
        instance = null
        Log.i(TAG, "RemoteControlService destroyed")
        super.onDestroy()
    }

    // The service does not need to observe accessibility events; it only
    // injects gestures reactively when called from ScreenShareChannel.
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}
}
