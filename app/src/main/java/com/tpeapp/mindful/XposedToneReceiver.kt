package com.tpeapp.mindful

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.preference.PreferenceManager
import com.tpeapp.consequence.ConsequenceDispatcher
import com.tpeapp.service.FilterService
import com.tpeapp.webhook.WebhookManager
import org.json.JSONObject

/**
 * XposedToneReceiver — catches soft-mode infraction events fired by
 * [com.tpeapp.xposed.InputConnectionHook] and executes the punishment webhook.
 *
 * ### Why this receiver exists
 * The Xposed module runs inside third-party apps' processes and must never
 * make direct network calls (to avoid declaring INTERNET in every hooked
 * process and to keep network logic centralised).  Instead, the module sends
 * an explicit broadcast with action [ACTION_XPOSED_TONE_INFRACTION]
 * directed at this package, and this receiver handles the network side.
 *
 * ### Security
 * The broadcast is sent with [Intent.setPackage] pointing exclusively at
 * `com.tpeapp`, so no other app can intercept it.  The receiver is
 * `exported="true"` only because the broadcast originates from outside this
 * process (the Xposed hook runs inside the hooked app).
 *
 * ### Extras
 * | Key              | Type   | Description                              |
 * |------------------|--------|------------------------------------------|
 * | `word`           | String | The restricted word that was overridden  |
 * | `timestamp`      | Long   | Epoch-ms when the bypass was triggered   |
 * | `source_package` | String | Package name of the app where it happened|
 */
class XposedToneReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "XposedToneReceiver"

        /**
         * Broadcast action fired by [com.tpeapp.xposed.InputConnectionHook]
         * when a soft-mode bypass override is successfully triggered.
         *
         * Defined here (in the app module) so the Xposed hook can reference it
         * via its compileOnly dependency on :app, without requiring a reverse
         * dependency from :app to :xposed.
         */
        const val ACTION_XPOSED_TONE_INFRACTION = "com.tpeapp.ACTION_XPOSED_TONE_INFRACTION"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_XPOSED_TONE_INFRACTION) return

        val word          = intent.getStringExtra("word")           ?: return
        val timestamp     = intent.getLongExtra("timestamp", 0L)
        val sourcePackage = intent.getStringExtra("source_package") ?: "unknown"

        Log.i(TAG, "Xposed tone infraction received — word='$word' from=$sourcePackage")

        // Fire punishment consequence (Lovense / Pavlok / existing escalation chain).
        ConsequenceDispatcher.punish(context, "tone_bypass=$word")

        // Dispatch the override-used telemetry webhook.
        val prefs       = PreferenceManager.getDefaultSharedPreferences(context)
        val webhookUrl  = prefs.getString(FilterService.PREF_WEBHOOK_URL, null)
            ?.takeIf { it.isNotBlank() } ?: return
        val bearerToken = prefs.getString(FilterService.PREF_WEBHOOK_BEARER_TOKEN, null)
            ?.takeIf { it.isNotBlank() }

        val payload = JSONObject().apply {
            put("event",          "tone_bypass_used")
            put("original_text",  word)
            put("source_package", sourcePackage)
            put("timestamp",      timestamp)
        }
        WebhookManager.dispatchEvent(webhookUrl, bearerToken, payload)
    }
}
