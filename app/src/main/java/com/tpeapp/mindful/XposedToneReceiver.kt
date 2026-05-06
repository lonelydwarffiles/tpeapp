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
 * XposedToneReceiver — catches tone-enforcement events fired by
 * [com.tpeapp.xposed.InputConnectionHook] and executes webhook telemetry and
 * punishment logic inside the main TPE app process.
 *
 * ### Why this receiver exists
 * The Xposed module runs inside third-party apps' processes and must never
 * make direct network calls (to avoid declaring INTERNET in every hooked
 * process and to keep network logic centralised).  Instead, the module sends
 * explicit broadcasts directed at this package, and this receiver handles the
 * network side.
 *
 * ### Handled broadcasts
 * | Action                          | Fired when                                     |
 * |---------------------------------|------------------------------------------------|
 * | [ACTION_XPOSED_TONE_BLOCK]      | A restricted word was redacted (every block)   |
 * | [ACTION_XPOSED_TONE_INFRACTION] | A soft-mode bypass override was accepted       |
 *
 * ### Security
 * Both broadcasts are sent with [Intent.setPackage] pointing exclusively at
 * `com.tpeapp`, so no other app can intercept them.  The receiver is
 * `exported="true"` only because the broadcasts originate from outside this
 * process (the Xposed hook runs inside the hooked app).
 *
 * ### Extras (both actions)
 * | Key              | Type   | Description                               |
 * |------------------|--------|-------------------------------------------|
 * | `word`           | String | The restricted word that was affected     |
 * | `timestamp`      | Long   | Epoch-ms when the event occurred          |
 * | `source_package` | String | Package name of the app where it happened |
 */
class XposedToneReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "XposedToneReceiver"

        /**
         * Broadcast action fired by [com.tpeapp.xposed.InputConnectionHook]
         * every time a restricted word is redacted (replaced with "[Redacted]").
         * Triggers a `tone_block` telemetry webhook so the partner dashboard
         * receives live blocking events.
         */
        const val ACTION_XPOSED_TONE_BLOCK = "com.tpeapp.ACTION_XPOSED_TONE_BLOCK"

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
        // goAsync() extends the broadcast deadline and prevents premature
        // process death when the main thread is busy, even though both
        // ConsequenceDispatcher and WebhookManager dispatch work asynchronously.
        val pendingResult = goAsync()

        try {
            when (intent.action) {
                ACTION_XPOSED_TONE_BLOCK      -> handleToneBlock(context, intent)
                ACTION_XPOSED_TONE_INFRACTION -> handleToneInfraction(context, intent)
                else -> Log.w(TAG, "Received unexpected action: ${intent.action}")
            }
        } finally {
            pendingResult.finish()
        }
    }

    // ── Handlers ──────────────────────────────────────────────────────────────

    private fun handleToneBlock(context: Context, intent: Intent) {
        val word          = intent.getStringExtra("word")           ?: return
        val timestamp     = intent.getLongExtra("timestamp", 0L)
        val sourcePackage = intent.getStringExtra("source_package") ?: "unknown"

        Log.i(TAG, "Tone block received — word='$word' from=$sourcePackage")

        val prefs       = PreferenceManager.getDefaultSharedPreferences(context)
        val webhookUrl  = prefs.getString(FilterService.PREF_WEBHOOK_URL, null)
            ?.takeIf { it.isNotBlank() } ?: return
        val bearerToken = prefs.getString(FilterService.PREF_WEBHOOK_BEARER_TOKEN, null)
            ?.takeIf { it.isNotBlank() }

        val payload = JSONObject().apply {
            put("event",          "tone_block")
            put("word",           word)
            put("source_package", sourcePackage)
            put("timestamp",      timestamp)
        }
        WebhookManager.dispatchEvent(webhookUrl, bearerToken, payload)
    }

    private fun handleToneInfraction(context: Context, intent: Intent) {
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
