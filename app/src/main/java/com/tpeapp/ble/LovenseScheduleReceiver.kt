package com.tpeapp.ble

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.preference.PreferenceManager
import com.tpeapp.service.FilterService
import com.tpeapp.webhook.WebhookManager
import org.json.JSONObject

/**
 * LovenseScheduleReceiver — fires when a scheduled Lovense pattern alarm triggers.
 * Vibrates for the configured duration, then stops and dispatches a webhook.
 */
class LovenseScheduleReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "LovenseScheduleRx"
        const val ACTION_LOVENSE_SCHEDULED = "com.tpeapp.ACTION_LOVENSE_SCHEDULED"
        const val EXTRA_VIBRATION_LEVEL = "vibration_level"
        const val EXTRA_DURATION_MS = "duration_ms"
        const val EXTRA_SCHEDULE_ID = "schedule_id"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_LOVENSE_SCHEDULED) return

        val level = intent.getIntExtra(EXTRA_VIBRATION_LEVEL, 10)
        val durationMs = intent.getIntExtra(EXTRA_DURATION_MS, 3_000)
        val scheduleId = intent.getStringExtra(EXTRA_SCHEDULE_ID) ?: ""

        Log.i(TAG, "Scheduled Lovense play: level=$level duration=${durationMs}ms id=$scheduleId")

        val appCtx = context.applicationContext
        LovenseManager.init(appCtx)
        LovenseManager.vibrate(level)

        Handler(Looper.getMainLooper()).postDelayed({
            LovenseManager.stopAll()
            dispatchWebhook(appCtx, scheduleId)
        }, durationMs.toLong())
    }

    private fun dispatchWebhook(ctx: Context, scheduleId: String) {
        val prefs = PreferenceManager.getDefaultSharedPreferences(ctx)
        val url = prefs.getString(FilterService.PREF_WEBHOOK_URL, null)?.takeIf { it.isNotBlank() } ?: return
        val token = prefs.getString(FilterService.PREF_WEBHOOK_BEARER_TOKEN, null)
        WebhookManager.dispatchEvent(url, token, JSONObject().apply {
            put("event", "lovense_scheduled_play")
            put("schedule_id", scheduleId)
            put("timestamp", System.currentTimeMillis())
        })
    }
}
