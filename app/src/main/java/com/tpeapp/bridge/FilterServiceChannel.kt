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
    private const val CHANNEL = "com.hound.controller/filter_service"

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
                "setMediaFilterMode" -> {
                    val mode = call.argument<String>("mode")
                        ?: return@setMethodCallHandler result.error("INVALID", "mode required", null)
                    PreferenceManager.getDefaultSharedPreferences(ctx).edit()
                        .putString(FilterService.PREF_MEDIA_FILTER_MODE, mode)
                        .apply()
                    result.success(null)
                }
                "setMediaCensorStyle" -> {
                    val style = call.argument<String>("style")
                        ?: return@setMethodCallHandler result.error("INVALID", "style required", null)
                    PreferenceManager.getDefaultSharedPreferences(ctx).edit()
                        .putString(FilterService.PREF_MEDIA_CENSOR_STYLE, style)
                        .apply()
                    result.success(null)
                }
                "setMediaStrictPackages" -> {
                    val packages = call.argument<List<String>>("packages") ?: emptyList()
                    val arr = org.json.JSONArray()
                    packages
                        .map { it.trim() }
                        .filter { it.isNotEmpty() }
                        .distinct()
                        .forEach { arr.put(it) }
                    PreferenceManager.getDefaultSharedPreferences(ctx).edit()
                        .putString(FilterService.PREF_MEDIA_STRICT_PACKAGES, arr.toString())
                        .apply()
                    result.success(null)
                }
                "setMediaForbiddenClassIds" -> {
                    val classIds = call.argument<List<Int>>("classIds") ?: emptyList()
                    val arr = org.json.JSONArray()
                    classIds
                        .map { it.coerceAtLeast(0) }
                        .distinct()
                        .forEach { arr.put(it) }
                    PreferenceManager.getDefaultSharedPreferences(ctx).edit()
                        .putString(FilterService.PREF_MEDIA_FORBIDDEN_CLASS_IDS, arr.toString())
                        .apply()
                    result.success(null)
                }
                "setMediaMaxInFlight" -> {
                    val maxInFlight = call.argument<Int>("maxInFlight")
                        ?: return@setMethodCallHandler result.error("INVALID", "maxInFlight required", null)
                    PreferenceManager.getDefaultSharedPreferences(ctx).edit()
                        .putInt(FilterService.PREF_MEDIA_MAX_IN_FLIGHT, maxInFlight.coerceIn(1, 12))
                        .apply()
                    result.success(null)
                }
                "setMediaFailClosed" -> {
                    val enabled = call.argument<Boolean>("enabled")
                        ?: return@setMethodCallHandler result.error("INVALID", "enabled required", null)
                    PreferenceManager.getDefaultSharedPreferences(ctx).edit()
                        .putBoolean(FilterService.PREF_MEDIA_FAIL_CLOSED, enabled)
                        .apply()
                    result.success(null)
                }
                "setMediaRevealDurationMs" -> {
                    val durationMs = call.argument<Int>("durationMs")
                        ?: return@setMethodCallHandler result.error("INVALID", "durationMs required", null)
                    PreferenceManager.getDefaultSharedPreferences(ctx).edit()
                        .putInt(FilterService.PREF_MEDIA_REVEAL_DURATION_MS, durationMs.coerceIn(0, 3000))
                        .apply()
                    result.success(null)
                }
                "setMediaPlaceholderText" -> {
                    val placeholder = call.argument<String>("placeholder")
                        ?: return@setMethodCallHandler result.error("INVALID", "placeholder required", null)
                    PreferenceManager.getDefaultSharedPreferences(ctx).edit()
                        .putString(FilterService.PREF_MEDIA_PLACEHOLDER_TEXT, placeholder.trim().take(64))
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
                "getMediaFilterConfig" -> {
                    val prefs = PreferenceManager.getDefaultSharedPreferences(ctx)
                    val json = org.json.JSONObject().apply {
                        put("mode", prefs.getString(FilterService.PREF_MEDIA_FILTER_MODE, "speed") ?: "speed")
                        put("censor_style", prefs.getString(FilterService.PREF_MEDIA_CENSOR_STYLE, "pixelate") ?: "pixelate")
                        put("strict_packages", org.json.JSONArray(
                            prefs.getString(FilterService.PREF_MEDIA_STRICT_PACKAGES, "[]") ?: "[]"
                        ))
                        put("forbidden_class_ids", org.json.JSONArray(
                            prefs.getString(FilterService.PREF_MEDIA_FORBIDDEN_CLASS_IDS, "[0,1,2,3,4,5]") ?: "[0,1,2,3,4,5]"
                        ))
                        put("max_in_flight", prefs.getInt(FilterService.PREF_MEDIA_MAX_IN_FLIGHT, 4).coerceIn(1, 12))
                        put("images_caught_count", prefs.getInt(FilterService.PREF_MEDIA_IMAGES_CAUGHT_COUNT, 0).coerceIn(0, Int.MAX_VALUE))
                        put("fail_closed", prefs.getBoolean(FilterService.PREF_MEDIA_FAIL_CLOSED, true))
                        put("reveal_duration_ms", prefs.getInt(FilterService.PREF_MEDIA_REVEAL_DURATION_MS, 300).coerceIn(0, 3000))
                        put(
                            "placeholder_text",
                            prefs.getString(FilterService.PREF_MEDIA_PLACEHOLDER_TEXT, "Loading...") ?: "Loading..."
                        )
                    }
                    result.success(json.toString())
                }
                else -> result.notImplemented()
            }
        }
    }
}
