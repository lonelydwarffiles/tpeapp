package com.tpeapp.bridge

import android.content.Context
import android.content.Intent
import android.util.Log
import com.tpeapp.review.RemoteControlService
import com.tpeapp.review.RemoteInputDispatcher
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
 * 1. **[RemoteControlService]** — if the AccessibilityService is enabled and
 *    running, [RemoteControlService.injectTap] is called.  It scales the
 *    normalised coordinates to physical pixels and uses [dispatchGesture].
 *
 * 2. **[RemoteInputDispatcher]** (su -c fallback) — if the AccessibilityService
 *    is not running (permission not granted, or device is rooted and the user
 *    prefers the root path), [RemoteInputDispatcher.dispatch] is called with a
 *    synthetic tap JSON envelope.  It executes `su -c input tap X Y`.
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

                        // Primary: AccessibilityService dispatchGesture
                        if (!RemoteControlService.injectTap(normX, normY)) {
                            // Fallback: su -c input tap X Y (rooted devices)
                            // RemoteInputDispatcher accepts normalised coords in [0,1].
                            val json = JSONObject().apply {
                                put("type", "tap")
                                put("x", normX.toDouble())
                                put("y", normY.toDouble())
                            }.toString()
                            RemoteInputDispatcher.dispatch(ctx, json)
                        }
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
}
