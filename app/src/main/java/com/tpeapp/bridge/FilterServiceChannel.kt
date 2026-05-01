package com.tpeapp.bridge

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.preference.PreferenceManager
import com.tpeapp.service.FilterService
import io.flutter.plugin.common.BinaryMessenger
import io.flutter.plugin.common.MethodChannel

/**
 * FilterServiceChannel — MethodChannel bridge for [FilterService].
 *
 * Channel name: `com.tpeapp/filter_service`
 *
 * Methods exposed to Dart:
 *  - `start`                                 → starts FilterService as a foreground service
 *  - `stop`                                  → (no-op on rooted devices; service is restart-pinned)
 *  - `isReady`            → Boolean          → true once NudeNetClassifier is initialised
 *  - `setThreshold`       (threshold: Double)→ persists a new confidence threshold [0.0, 1.0]
 *  - `setStrictMode`      (enabled: Boolean) → persists strict-mode flag
 *  - `getWebhookUrl`      → String?          → current webhook URL
 *  - `setWebhookUrl`      (url: String)      → persists webhook URL
 *  - `getWebhookToken`    → String?          → current bearer token
 *  - `setWebhookToken`    (token: String)    → persists bearer token
 */
object FilterServiceChannel {

    private const val TAG = "FilterServiceChannel"
    private const val CHANNEL = "com.tpeapp/filter_service"

    fun register(messenger: BinaryMessenger, context: Context) {
        MethodChannel(messenger, CHANNEL).setMethodCallHandler { call, result ->
            val ctx = context.applicationContext
            when (call.method) {
                "start" -> {
                    ctx.startForegroundService(Intent(ctx, FilterService::class.java))
                    result.success(null)
                }
                "stop" -> {
                    // FilterService is intentionally hard to stop (it must survive
                    // orientation changes, task-kill, etc.).  This is a no-op by design;
                    // stopping is only allowed via the Device Admin deactivation flow.
                    Log.w(TAG, "stop() called from Dart — no-op by design")
                    result.success(null)
                }
                "setThreshold" -> {
                    val threshold = call.argument<Double>("threshold")
                        ?: return@setMethodCallHandler result.error("INVALID", "threshold required", null)
                    PreferenceManager.getDefaultSharedPreferences(ctx).edit()
                        .putFloat(FilterService.PREF_THRESHOLD, threshold.toFloat().coerceIn(0f, 1f))
                        .apply()
                    result.success(null)
                }
                "setStrictMode" -> {
                    val enabled = call.argument<Boolean>("enabled")
                        ?: return@setMethodCallHandler result.error("INVALID", "enabled required", null)
                    PreferenceManager.getDefaultSharedPreferences(ctx).edit()
                        .putBoolean(FilterService.PREF_STRICT_MODE, enabled)
                        .apply()
                    result.success(null)
                }
                "getWebhookUrl" -> {
                    val url = PreferenceManager.getDefaultSharedPreferences(ctx)
                        .getString(FilterService.PREF_WEBHOOK_URL, null)
                    result.success(url)
                }
                "setWebhookUrl" -> {
                    val url = call.argument<String>("url")
                        ?: return@setMethodCallHandler result.error("INVALID", "url required", null)
                    PreferenceManager.getDefaultSharedPreferences(ctx).edit()
                        .putString(FilterService.PREF_WEBHOOK_URL, url)
                        .apply()
                    result.success(null)
                }
                "getWebhookToken" -> {
                    val token = PreferenceManager.getDefaultSharedPreferences(ctx)
                        .getString(FilterService.PREF_WEBHOOK_BEARER_TOKEN, null)
                    result.success(token)
                }
                "setWebhookToken" -> {
                    val token = call.argument<String>("token")
                        ?: return@setMethodCallHandler result.error("INVALID", "token required", null)
                    PreferenceManager.getDefaultSharedPreferences(ctx).edit()
                        .putString(FilterService.PREF_WEBHOOK_BEARER_TOKEN, token)
                        .apply()
                    result.success(null)
                }
                else -> result.notImplemented()
            }
        }
    }
}
