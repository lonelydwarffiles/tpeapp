package com.tpeapp.consequence

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.preference.PreferenceManager
import com.tpeapp.R
import com.tpeapp.ble.LovenseManager
import com.tpeapp.ble.PavlokManager
import com.tpeapp.service.FilterService
import com.tpeapp.webhook.WebhookManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * ConsequenceDispatcher — centralised routing for every punishment and reward
 * stimulus in the TPE app.
 *
 * All violation paths (content filter, tone enforcement, missed check-ins, …)
 * funnel through [punish].  All compliance paths (clean scan, on-time check-in,
 * bypass accepted, …) funnel through [reward].  Both functions:
 *
 *  1. Initialise and command the **Lovense** toy via [LovenseManager].
 *  2. Initialise and command the **Pavlok** wristband via [PavlokManager].
 *  3. Dispatch a webhook event via [WebhookManager] (if a URL + token are
 *     configured in SharedPreferences under [FilterService.PREF_WEBHOOK_URL] /
 *     [FilterService.PREF_WEBHOOK_BEARER_TOKEN]).
 *
 * ### Punishment profile
 *  - Lovense: vibrate at level 20 (maximum) for [PUNISHMENT_DURATION_MS] ms
 *  - Pavlok:  zap at intensity [PUNISHMENT_ZAP_INTENSITY] for [PUNISHMENT_DURATION_MS] ms
 *  - Webhook: `{ "event": "punishment", "reason": <reason>, "timestamp": … }`
 *
 * ### Reward profile
 *  - Lovense: vibrate at level [REWARD_LOVENSE_LEVEL] for [REWARD_DURATION_MS] ms
 *  - Pavlok:  beep at intensity [REWARD_BEEP_INTENSITY] for [REWARD_DURATION_MS] ms
 *  - Webhook: `{ "event": "reward", "reason": <reason>, "timestamp": … }`
 *
 * All BLE commands are executed on [Dispatchers.IO]; callers are never blocked.
 */
object ConsequenceDispatcher {

    private const val TAG = "ConsequenceDispatcher"

    // ------------------------------------------------------------------
    //  Notification channel for punishment alerts
    // ------------------------------------------------------------------

    private const val PUNISHMENT_CHANNEL_ID   = "tpe_punishment_alert"
    private const val PUNISHMENT_CHANNEL_NAME = "Punishment Alerts"
    private const val PUNISHMENT_NOTIF_ID_BASE = 8000

    // ------------------------------------------------------------------
    //  Punishment tuning
    // ------------------------------------------------------------------

    /** Lovense vibration level for a punishment (0–20; 20 is the maximum Lovense intensity). */
    const val PUNISHMENT_LOVENSE_LEVEL: Int = 20

    /** Pavlok zap intensity for a punishment (0–255). */
    const val PUNISHMENT_ZAP_INTENSITY: Int = 64

    /** Duration of the punishment stimulus in milliseconds. */
    const val PUNISHMENT_DURATION_MS: Int = 3_000

    // ------------------------------------------------------------------
    //  Reward tuning
    // ------------------------------------------------------------------

    /** Lovense vibration level for a reward (0–20). */
    const val REWARD_LOVENSE_LEVEL: Int = 5

    /** Pavlok beep intensity for a reward (0–255). */
    const val REWARD_BEEP_INTENSITY: Int = 64

    /** Duration of the reward stimulus in milliseconds. */
    const val REWARD_DURATION_MS: Int = 1_000

    // ------------------------------------------------------------------
    //  Internal coroutine scope
    // ------------------------------------------------------------------

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // ------------------------------------------------------------------
    //  Public API
    // ------------------------------------------------------------------

    /**
     * Fires a punishment stimulus on all connected devices and dispatches a
     * webhook event.
     *
     * Safe to call from any thread.  All I/O is performed asynchronously.
     *
     * @param context Application or service context.
     * @param reason  Human-readable description of the violation (included in
     *                the webhook payload for the Dom's dashboard).
     */
    fun punish(context: Context, reason: String) {
        Log.i(TAG, "Punishment triggered: $reason")
        val appContext = context.applicationContext
        showPunishmentNotification(appContext, reason)
        scope.launch {
            LovenseManager.init(appContext)
            PavlokManager.init(appContext)

            LovenseManager.vibrate(PUNISHMENT_LOVENSE_LEVEL)
            PavlokManager.zap(PUNISHMENT_ZAP_INTENSITY, PUNISHMENT_DURATION_MS)

            delay(PUNISHMENT_DURATION_MS.toLong())

            LovenseManager.stopAll()
            PavlokManager.stopAll()

            dispatchWebhook(appContext, "punishment", reason)
        }
    }

    /**
     * Fires a reward stimulus on all connected devices and dispatches a
     * webhook event.
     *
     * Safe to call from any thread.  All I/O is performed asynchronously.
     *
     * @param context Application or service context.
     * @param reason  Human-readable description of the compliance event (included
     *                in the webhook payload).
     */
    fun reward(context: Context, reason: String) {
        Log.i(TAG, "Reward triggered: $reason")
        val appContext = context.applicationContext
        scope.launch {
            LovenseManager.init(appContext)
            PavlokManager.init(appContext)

            LovenseManager.vibrate(REWARD_LOVENSE_LEVEL)
            PavlokManager.beep(REWARD_BEEP_INTENSITY, REWARD_DURATION_MS)

            delay(REWARD_DURATION_MS.toLong())

            LovenseManager.stopAll()
            PavlokManager.stopAll()

            dispatchWebhook(appContext, "reward", reason)
        }
    }

    // ------------------------------------------------------------------
    //  Internal
    // ------------------------------------------------------------------

    /**
     * Posts a high-priority local notification explaining exactly which rule
     * triggered the punishment.  The notification is shown on the heads-up
     * display so it is immediately visible even when the screen is on.
     */
    private fun showPunishmentNotification(context: Context, reason: String) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        // Ensure the channel exists (idempotent; safe to call repeatedly).
        if (nm.getNotificationChannel(PUNISHMENT_CHANNEL_ID) == null) {
            val channel = NotificationChannel(
                PUNISHMENT_CHANNEL_ID,
                PUNISHMENT_CHANNEL_NAME,
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Alerts shown when a punishment is triggered by a rule violation."
                enableVibration(true)
            }
            nm.createNotificationChannel(channel)
        }

        val notif = NotificationCompat.Builder(context, PUNISHMENT_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("Rule Violation Detected")
            .setContentText(reason)
            .setStyle(NotificationCompat.BigTextStyle().bigText(reason))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()

        // Use a stable-but-unique ID derived from the reason hash so rapid
        // consecutive punishments each get their own notification slot.
        val notifId = PUNISHMENT_NOTIF_ID_BASE + (reason.hashCode() and 0x0FFF)
        nm.notify(notifId, notif)
    }

    private fun dispatchWebhook(context: Context, event: String, reason: String) {
        val prefs       = PreferenceManager.getDefaultSharedPreferences(context)
        val webhookUrl  = prefs.getString(FilterService.PREF_WEBHOOK_URL, null)
            ?.takeIf { it.isNotBlank() } ?: return
        // Bearer token is optional — server accepts no-auth requests when no
        // webhook secret is configured.
        val bearerToken = prefs.getString(FilterService.PREF_WEBHOOK_BEARER_TOKEN, null)
            ?.takeIf { it.isNotBlank() }

        val payload = JSONObject().apply {
            put("event",     event)
            put("reason",    reason)
            put("timestamp", System.currentTimeMillis())
        }
        WebhookManager.dispatchEvent(webhookUrl, bearerToken, payload)
    }
}
