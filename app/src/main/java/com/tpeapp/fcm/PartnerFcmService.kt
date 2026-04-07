package com.tpeapp.fcm

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.SharedPreferences
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.preference.PreferenceManager
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.tpeapp.R
import com.tpeapp.mindful.MindfulNotificationService
import com.tpeapp.mindful.ToneEnforcementService

/**
 * Handles FCM messages sent by the Accountability Partner to remotely
 * update filter settings.
 *
 * Expected message payload (data map):
 *
 * ```
 * {
 *   "action":    "UPDATE_SETTINGS",
 *   "threshold": "0.55",          // optional: new confidence threshold
 *   "strict":    "true"           // optional: enable maximum strictness
 * }
 * ```
 *
 * The service persists changes to [SharedPreferences] and shows a local
 * notification so the device owner is always aware of any configuration
 * change — fulfilling the transparency / consent requirement.
 */
class PartnerFcmService : FirebaseMessagingService() {

    companion object {
        private const val TAG          = "PartnerFcmService"
        private const val CHANNEL_ID   = "tpe_partner_updates"
        private const val NOTIF_ID     = 2001

        // SharedPreferences keys (also read by FilterService / Settings UI)
        const val PREF_THRESHOLD       = "filter_confidence_threshold"
        const val PREF_STRICT_MODE     = "filter_strict_mode"
        const val PREF_FCM_TOKEN       = "fcm_registration_token"
        const val PREF_BLOCKED_CLASSES = "filter_blocked_classes"
    }

    override fun onNewToken(token: String) {
        super.onNewToken(token)
        Log.i(TAG, "FCM token refreshed")
        prefs().edit().putString(PREF_FCM_TOKEN, token).apply()
        // In production: upload token to the partner's backend.
    }

    override fun onMessageReceived(message: RemoteMessage) {
        super.onMessageReceived(message)
        val data = message.data
        Log.i(TAG, "FCM data received: $data")

        when (data["action"]) {
            "UPDATE_SETTINGS"           -> handleUpdateSettings(data)
            "UPDATE_NOTIFICATION_BLOCKLIST" -> handleUpdateNotificationBlocklist(data)
            "UPDATE_RESTRICTED_VOCABULARY"  -> handleUpdateRestrictedVocabulary(data)
            else                        -> Log.w(TAG, "Unknown FCM action: ${data["action"]}")
        }
    }

    // ------------------------------------------------------------------
    //  Handlers
    // ------------------------------------------------------------------

    private fun handleUpdateSettings(data: Map<String, String>) {
        val editor = prefs().edit()
        var changeDescription = "Your accountability partner updated filter settings."

        data["threshold"]?.toFloatOrNull()?.let { newThreshold ->
            editor.putFloat(PREF_THRESHOLD, newThreshold.coerceIn(0f, 1f))
            changeDescription += " Threshold → $newThreshold."
        }

        data["strict"]?.toBooleanStrictOrNull()?.let { strict ->
            editor.putBoolean(PREF_STRICT_MODE, strict)
            changeDescription += " Strict mode → $strict."
        }

        // JSON-encoded list of NudeNet label strings sent by the partner dashboard.
        // Example: ["EXPOSED_GENITALIA_F","EXPOSED_BREAST_F"]
        data["blocked_classes"]?.takeIf { it.isNotBlank() }?.let { json ->
            editor.putString(PREF_BLOCKED_CLASSES, json)
            changeDescription += " Blocked content classes updated."
        }

        editor.apply()

        // Notify the user so they always know a settings change occurred.
        showSettingsChangedNotification(changeDescription)
    }

    /**
     * Persists a new notification blocklist pushed by the partner.
     *
     * Expected payload:
     * ```
     * { "action": "UPDATE_NOTIFICATION_BLOCKLIST", "blocklist": "[\"hate\",\"slur\"]" }
     * ```
     */
    private fun handleUpdateNotificationBlocklist(data: Map<String, String>) {
        val json = data["blocklist"]?.takeIf { it.isNotBlank() } ?: return
        prefs().edit()
            .putString(MindfulNotificationService.PREF_NOTIFICATION_BLOCKLIST, json)
            .apply()
        Log.i(TAG, "Notification blocklist updated via FCM")
        showSettingsChangedNotification("Your accountability partner updated the message blocklist.")
    }

    /**
     * Persists a new restricted vocabulary list pushed by the partner.
     *
     * Expected payload:
     * ```
     * { "action": "UPDATE_RESTRICTED_VOCABULARY", "vocabulary": "[\"word1\",\"word2\"]" }
     * ```
     */
    private fun handleUpdateRestrictedVocabulary(data: Map<String, String>) {
        val json = data["vocabulary"]?.takeIf { it.isNotBlank() } ?: return
        prefs().edit()
            .putString(ToneEnforcementService.PREF_RESTRICTED_VOCABULARY, json)
            .apply()
        Log.i(TAG, "Restricted vocabulary updated via FCM")
        showSettingsChangedNotification("Your accountability partner updated the restricted keyword list.")
    }

    // ------------------------------------------------------------------
    //  Notification (user transparency)
    // ------------------------------------------------------------------

    private fun showSettingsChangedNotification(details: String) {
        val nm = getSystemService(NotificationManager::class.java)
        ensureChannel(nm)

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_shield)
            .setContentTitle("Accountability settings updated")
            .setContentText(details)
            .setStyle(NotificationCompat.BigTextStyle().bigText(details))
            .setAutoCancel(true)
            .build()

        nm.notify(NOTIF_ID, notification)
    }

    private fun ensureChannel(nm: NotificationManager) {
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        val ch = NotificationChannel(
            CHANNEL_ID,
            "Partner Setting Changes",
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = "Notifications when your accountability partner changes settings"
        }
        nm.createNotificationChannel(ch)
    }

    private fun prefs(): SharedPreferences =
        PreferenceManager.getDefaultSharedPreferences(applicationContext)
}
