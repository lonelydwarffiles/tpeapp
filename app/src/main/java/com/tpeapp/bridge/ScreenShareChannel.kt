package com.hound.controller.bridge

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.preference.PreferenceManager
import com.hound.controller.capability.TpeCapabilityService
import com.hound.controller.review.RemoteInputDispatcher
import com.hound.controller.review.RootChecker
import com.hound.controller.review.ScreencastService
import io.flutter.plugin.common.BinaryMessenger
import io.flutter.plugin.common.MethodChannel
import org.json.JSONObject

/**
 * ScreenShareChannel — MethodChannel bridge between the Flutter-side
 * [ScreenShareService] (which manages the WebRTC peer connection and the
 * remote-control DataChannel) and the native gesture-injection layer.
 *
 * Channel name: `com.hound.controller/screen_share`
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
 * |                 | AccessibilityService [TpeCapabilityService.injectTap].        |
 * | `root`          | Always execute `su -c input tap X Y` via [RemoteInputDispatcher].|
 * | `accessibility` | Always use [TpeCapabilityService.dispatchGesture].            |
 *
 * The mode is selected by the user via the Settings screen and persisted across
 * restarts. It can be changed at runtime without restarting the app.
 */
object ScreenShareChannel {

    private const val TAG     = "ScreenShareChannel"
    private const val CHANNEL = "com.hound.controller/screen_share"
    private const val PREF_TOUCH_LOCK_ENABLED = "screen_touch_lock_enabled"
    private const val PREF_TOUCH_LOCK_MODE = "screen_touch_lock_mode"
    private const val PREF_TOUCH_LOCK_REMOTE_INPUT = "screen_touch_lock_allow_remote_input"
    private const val PREF_TOUCH_LOCK_SESSION_ID = "screen_touch_lock_session_id"
    private const val PREF_TOUCH_LOCK_EXPIRES_AT = "screen_touch_lock_expires_at"

    private val mainHandler = Handler(Looper.getMainLooper())
    private var autoUnlockRunnable: Runnable? = null

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

                    "setTouchLock" -> {
                        val enabled = call.argument<Boolean>("enabled") ?: false
                        val mode = call.argument<String>("mode") ?: "advisory"
                        val allowRemoteInput = call.argument<Boolean>("allowRemoteInput") ?: false
                        val sessionId = call.argument<String>("sessionId")
                        val ttlSec = call.argument<Int>("ttlSec") ?: 0

                        if (enabled && mode == "strict" && !RootChecker.isRootAvailable()) {
                            result.error(
                                "ROOT_UNAVAILABLE",
                                "Strict touch lock requires root access",
                                null
                            )
                            return@setMethodCallHandler
                        }

                        persistTouchLockState(
                            ctx = ctx,
                            enabled = enabled,
                            mode = mode,
                            allowRemoteInput = allowRemoteInput,
                            sessionId = sessionId,
                            ttlSec = ttlSec,
                        )

                        result.success(readTouchLockState(ctx))
                    }

                    "getTouchLockState" -> {
                        result.success(readTouchLockState(ctx))
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
    * | `accessibility` | Always use [TpeCapabilityService.dispatchGesture]             |
     */
    private fun routeInjection(ctx: Context, normX: Float, normY: Float) {
        val lockEnabled = isTouchLockEnabled(ctx)
        val lockAllowsRemoteInput = isTouchLockRemoteInputAllowed(ctx)
        if (lockEnabled && !lockAllowsRemoteInput) {
            Log.w(TAG, "Dropping injectTap while touch lock disallows remote input")
            return
        }

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
                TpeCapabilityService.injectTap(normX, normY)
            }
            else -> {
                // Auto: prefer root when available, fall back to AccessibilityService.
                // Use the cached result only to avoid blocking the platform thread.
                // If the root check has never been run (cache is null), skip root and
                // let AccessibilityService handle the tap; the cache will be populated
                // by the first explicit isRootAvailable() call from the settings screen.
                val rootKnownAvailable = RootChecker.cachedAvailable == true
                if (rootKnownAvailable) {
                    dispatchViaRoot(ctx, normX, normY)
                } else {
                    val injected = TpeCapabilityService.injectTap(normX, normY)
                    if (!injected) {
                        if (RootChecker.cachedAvailable == null) {
                            Log.w(TAG, "injectTap: AccessibilityService not running and root availability unknown (check not yet performed)")
                        } else {
                            Log.w(TAG, "injectTap: AccessibilityService not running and root is unavailable")
                        }
                    }
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

    private fun persistTouchLockState(
        ctx: Context,
        enabled: Boolean,
        mode: String,
        allowRemoteInput: Boolean,
        sessionId: String?,
        ttlSec: Int,
    ) {
        val expiresAt = if (enabled && ttlSec > 0) {
            System.currentTimeMillis() + (ttlSec * 1000L)
        } else {
            0L
        }

        PreferenceManager.getDefaultSharedPreferences(ctx).edit()
            .putBoolean(PREF_TOUCH_LOCK_ENABLED, enabled)
            .putString(PREF_TOUCH_LOCK_MODE, mode)
            .putBoolean(PREF_TOUCH_LOCK_REMOTE_INPUT, allowRemoteInput)
            .putString(PREF_TOUCH_LOCK_SESSION_ID, sessionId)
            .putLong(PREF_TOUCH_LOCK_EXPIRES_AT, expiresAt)
            .apply()

        RemoteInputDispatcher.remoteControlEnabled = enabled && allowRemoteInput

        scheduleAutoUnlock(ctx, expiresAt)
    }

    private fun scheduleAutoUnlock(ctx: Context, expiresAt: Long) {
        autoUnlockRunnable?.let { mainHandler.removeCallbacks(it) }
        autoUnlockRunnable = null

        if (expiresAt <= 0L) return

        val delayMs = (expiresAt - System.currentTimeMillis()).coerceAtLeast(0L)
        val runnable = Runnable {
            persistTouchLockState(
                ctx = ctx,
                enabled = false,
                mode = "strict",
                allowRemoteInput = false,
                sessionId = null,
                ttlSec = 0,
            )
            Log.i(TAG, "Touch lock auto-released after TTL expiry")
        }
        autoUnlockRunnable = runnable
        mainHandler.postDelayed(runnable, delayMs)
    }

    private fun isTouchLockEnabled(ctx: Context): Boolean {
        return PreferenceManager.getDefaultSharedPreferences(ctx)
            .getBoolean(PREF_TOUCH_LOCK_ENABLED, false)
    }

    private fun isTouchLockRemoteInputAllowed(ctx: Context): Boolean {
        return PreferenceManager.getDefaultSharedPreferences(ctx)
            .getBoolean(PREF_TOUCH_LOCK_REMOTE_INPUT, false)
    }

    private fun readTouchLockState(ctx: Context): Map<String, Any?> {
        val prefs = PreferenceManager.getDefaultSharedPreferences(ctx)
        return hashMapOf(
            "enabled" to prefs.getBoolean(PREF_TOUCH_LOCK_ENABLED, false),
            "mode" to (prefs.getString(PREF_TOUCH_LOCK_MODE, "advisory") ?: "advisory"),
            "allowRemoteInput" to prefs.getBoolean(PREF_TOUCH_LOCK_REMOTE_INPUT, false),
            "sessionId" to prefs.getString(PREF_TOUCH_LOCK_SESSION_ID, null),
            "expiresAtMs" to prefs.getLong(PREF_TOUCH_LOCK_EXPIRES_AT, 0L),
        )
    }
}
