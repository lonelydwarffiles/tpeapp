package com.tpeapp.bridge

import android.content.Context
import android.util.Log
import androidx.preference.PreferenceManager
import com.tpeapp.review.RootChecker
import io.flutter.plugin.common.BinaryMessenger
import io.flutter.plugin.common.MethodChannel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * RemoteControlChannel — MethodChannel bridge that exposes the gesture-injection
 * mode selector and root-availability query to Flutter.
 *
 * Channel name: `com.tpeapp/remote_control`
 *
 * ## Injection modes
 *
 * | Mode            | Behaviour                                                        |
 * |-----------------|------------------------------------------------------------------|
 * | `auto`          | Try root first (if available), fall back to AccessibilityService |
 * | `root`          | Always use `su -c input tap X Y`; fail silently if not rooted   |
 * | `accessibility` | Always use [RemoteControlService.dispatchGesture]; fail silently |
 *                    if the service is not enabled                                   |
 *
 * The selected mode is persisted in default SharedPreferences under
 * [PREF_INJECTION_MODE] and read by [ScreenShareChannel] on every `injectTap`
 * call so that the setting takes effect immediately without a restart.
 *
 * ## Methods exposed to Dart
 *
 * | Method               | Arguments             | Returns            |
 * |----------------------|-----------------------|--------------------|
 * | `getInjectionMode`   | —                     | String (mode name) |
 * | `setInjectionMode`   | mode: String          | null               |
 * | `isRootAvailable`    | —                     | Boolean            |
 */
object RemoteControlChannel {

    private const val TAG     = "RemoteControlChannel"
    const val CHANNEL         = "com.tpeapp/remote_control"
    const val PREF_INJECTION_MODE = "remote_control_injection_mode"
    const val MODE_AUTO          = "auto"
    const val MODE_ROOT          = "root"
    const val MODE_ACCESSIBILITY = "accessibility"

    private val VALID_MODES = setOf(MODE_AUTO, MODE_ROOT, MODE_ACCESSIBILITY)

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    fun register(messenger: BinaryMessenger, context: Context) {
        val ctx   = context.applicationContext
        val prefs = PreferenceManager.getDefaultSharedPreferences(ctx)

        MethodChannel(messenger, CHANNEL).setMethodCallHandler { call, result ->
            try {
                when (call.method) {
                    "getInjectionMode" -> {
                        val mode = prefs.getString(PREF_INJECTION_MODE, MODE_AUTO) ?: MODE_AUTO
                        result.success(mode)
                    }

                    "setInjectionMode" -> {
                        val mode = call.argument<String>("mode") ?: MODE_AUTO
                        if (mode !in VALID_MODES) {
                            result.error(
                                "INVALID_MODE",
                                "mode must be one of: ${VALID_MODES.joinToString()}",
                                null
                            )
                            return@setMethodCallHandler
                        }
                        prefs.edit().putString(PREF_INJECTION_MODE, mode).apply()
                        Log.i(TAG, "Injection mode set to: $mode")
                        result.success(null)
                    }

                    "isRootAvailable" -> {
                        // Run on the IO dispatcher to avoid blocking the platform thread.
                        scope.launch {
                            val available = RootChecker.isRootAvailable()
                            result.success(available)
                        }
                    }

                    else -> result.notImplemented()
                }
            } catch (e: Exception) {
                Log.e(TAG, "RemoteControlChannel error: ${call.method}", e)
                result.error("REMOTE_CONTROL_ERROR", e.message, null)
            }
        }
    }
}
