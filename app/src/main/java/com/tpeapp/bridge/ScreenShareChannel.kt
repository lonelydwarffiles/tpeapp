package com.tpeapp.bridge

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.preference.PreferenceManager
import com.tpeapp.review.RemoteControlService
import com.tpeapp.review.RemoteInputDispatcher
import com.tpeapp.review.RootChecker
import com.tpeapp.review.ScreencastService
import io.flutter.plugin.common.BinaryMessenger
import io.flutter.plugin.common.MethodChannel
import org.json.JSONObject

/**
 * ScreenShareChannel — MethodChannel bridge between the Flutter-side
 * [ScreenShareService] (which manages the WebRTC peer connection and the
 * remote-control DataChannel) and the native gesture-injection layer.
 *
 * Channel name: `com.tpeapp/screen_share`
 *
 * Methods exposed to Dart:
 *
 * | Method                  | Arguments                     | Description                           |
 * |-------------------------|-------------------------------|---------------------------------------|
 * | `injectTap`             | x: Double, y: Double          | Inject a tap at normalised coords.    |
 * | `stopNativeScreenShare` | —                             | Stop [ScreencastService] if running.  |
 *
 * ### `injectTap` dispatch strategy
 *
 * The method is routed through [routeInjection] which reads the user-configured
 * injection mode from SharedPreferences ([RemoteControlChannel.PREF_INJECTION_MODE]):
 *
 * | Mode            | Behaviour                                                     |
 * |-----------------|---------------------------------------------------------------|
 * | `auto` (default)| Try root ([RootChecker.isRootAvailable]) first; fall back to  |
 * |                 | AccessibilityService [RemoteControlService.injectTap].        |
 * | `root`          | Always execute `su -c input tap X Y` via [RemoteInputDispatcher].|
 * | `accessibility` | Always use [RemoteControlService.dispatchGesture].            |
 *
 * The mode is selected by the user via the Settings screen and persisted across
 * restarts. It can be changed at runtime without restarting the app.
 */
object ScreenShareChannel {

    private const val TAG     = "ScreenShareChannel"
    private const val CHANNEL = "com.tpeapp/screen_share"

    fun register(messenger: BinaryMessenger, context: Context) {
        val ctx = context.applicationContext
        MethodChannel(messenger, CHANNEL).setMethodCallHandler { call, result ->
            try {
                when (call.method) {
                    "injectTap" -> {
                        val normX = call.argument<Double>("x")?.toFloat() ?: 0f
                        val normY = call.argument<Double>("y")?.toFloat() ?: 0f

                        routeInjection(ctx, normX, normY)
                        result.success(null)
                    }

                    "stopNativeScreenShare" -> {
                        ctx.stopService(Intent(ctx, ScreencastService::class.java))
                        result.success(null)
                    }

                    else -> result.notImplemented()
                }
            } catch (e: Exception) {
                Log.e(TAG, "ScreenShareChannel error: ${call.method}", e)
                result.error("SCREEN_SHARE_ERROR", e.message, null)
            }
        }
    }

    /**
     * Routes a tap injection to the appropriate method based on the user-configured
     * injection mode stored in SharedPreferences under
     * [RemoteControlChannel.PREF_INJECTION_MODE].
     *
     * | Mode            | Behaviour                                                     |
     * |-----------------|---------------------------------------------------------------|
     * | `auto`          | Try root if available, else fall back to AccessibilityService |
     * | `root`          | Always attempt `su -c input tap X Y`                         |
     * | `accessibility` | Always use [RemoteControlService.dispatchGesture]             |
     */
    private fun routeInjection(ctx: Context, normX: Float, normY: Float) {
        val prefs = PreferenceManager.getDefaultSharedPreferences(ctx)
        val mode  = prefs.getString(
            RemoteControlChannel.PREF_INJECTION_MODE,
            RemoteControlChannel.MODE_AUTO
        ) ?: RemoteControlChannel.MODE_AUTO

        when (mode) {
            RemoteControlChannel.MODE_ROOT -> {
                // Force root path regardless of AccessibilityService state.
                dispatchViaRoot(ctx, normX, normY)
            }
            RemoteControlChannel.MODE_ACCESSIBILITY -> {
                // Force AccessibilityService path.
                RemoteControlService.injectTap(normX, normY)
            }
            else -> {
                // Auto: prefer root when available, fall back to AccessibilityService.
                if (RootChecker.isRootAvailable()) {
                    dispatchViaRoot(ctx, normX, normY)
                } else if (!RemoteControlService.injectTap(normX, normY)) {
                    Log.w(TAG, "injectTap: AccessibilityService not running and root unavailable")
                }
            }
        }
    }

    private fun dispatchViaRoot(ctx: Context, normX: Float, normY: Float) {
        val json = JSONObject().apply {
            put("type", "tap")
            put("x", normX.toDouble())
            put("y", normY.toDouble())
        }.toString()
        RemoteInputDispatcher.dispatch(ctx, json)
    }
}
