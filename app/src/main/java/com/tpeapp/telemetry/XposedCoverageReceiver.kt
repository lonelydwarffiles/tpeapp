package com.tpeapp.telemetry

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.preference.PreferenceManager
import com.tpeapp.service.FilterService
import com.tpeapp.webhook.WebhookManager
import org.json.JSONObject

/**
 * Receives explicit broadcasts from Xposed media hooks and forwards normalized
 * coverage telemetry to the configured webhook endpoint.
 */
class XposedCoverageReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "XposedCoverageReceiver"

        const val ACTION_XPOSED_COVERAGE_EVENT = "com.hound.controller.ACTION_XPOSED_COVERAGE_EVENT"

        const val EXTRA_LANE = "lane"
        const val EXTRA_STAGE = "stage"
        const val EXTRA_MEDIA_TYPE = "media_type"
        const val EXTRA_SOURCE_PACKAGE = "source_package"
        const val EXTRA_SENSITIVE = "sensitive"
        const val EXTRA_CONFIDENCE = "confidence"
        const val EXTRA_LATENCY_MS = "latency_ms"
        const val EXTRA_REASON = "reason"
        const val EXTRA_TIMESTAMP = "timestamp"

        private const val DEFAULT_CONFIDENCE = -1f
        private const val DEFAULT_LATENCY_MS = -1L
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_XPOSED_COVERAGE_EVENT) return

        val pendingResult = goAsync()
        try {
            val prefs = PreferenceManager.getDefaultSharedPreferences(context)
            val webhookUrl = prefs.getString(FilterService.PREF_WEBHOOK_URL, null)
                ?.takeIf { it.isNotBlank() } ?: return
            val bearerToken = prefs.getString(FilterService.PREF_WEBHOOK_BEARER_TOKEN, null)
                ?.takeIf { it.isNotBlank() }

            val payload = JSONObject().apply {
                put("event", "xposed_coverage_event")
                put("schema_version", 1)
                put("lane", intent.getStringExtra(EXTRA_LANE) ?: "unknown")
                put("stage", intent.getStringExtra(EXTRA_STAGE) ?: "unknown")
                put("media_type", intent.getStringExtra(EXTRA_MEDIA_TYPE) ?: "unknown")
                put("source_package", intent.getStringExtra(EXTRA_SOURCE_PACKAGE) ?: "unknown")
                put("sensitive", intent.getBooleanExtra(EXTRA_SENSITIVE, false))

                val confidence = intent.getFloatExtra(EXTRA_CONFIDENCE, DEFAULT_CONFIDENCE)
                if (confidence >= 0f) {
                    put("confidence", confidence)
                }

                val latencyMs = intent.getLongExtra(EXTRA_LATENCY_MS, DEFAULT_LATENCY_MS)
                if (latencyMs >= 0L) {
                    put("latency_ms", latencyMs)
                }

                intent.getStringExtra(EXTRA_REASON)?.takeIf { it.isNotBlank() }?.let {
                    put("reason", it)
                }

                put("timestamp", intent.getLongExtra(EXTRA_TIMESTAMP, System.currentTimeMillis()))
            }

            Log.i(
                TAG,
                "coverage lane=${payload.optString("lane")} stage=${payload.optString("stage")} " +
                    "sensitive=${payload.optBoolean("sensitive", false)}",
            )

            WebhookManager.dispatchEvent(webhookUrl, bearerToken, payload)
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to dispatch Xposed coverage telemetry", t)
        } finally {
            pendingResult.finish()
        }
    }
}

