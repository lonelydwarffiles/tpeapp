package com.tpeapp.mindful

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import androidx.preference.PreferenceManager
import org.json.JSONArray

/**
 * MindfulNotificationService — a [NotificationListenerService] that intercepts
 * incoming notifications and silently cancels any whose text or title contains a
 * word from the dynamic "blocklist" managed by the Accountability Partner via FCM.
 *
 * The blocklist is stored as a JSON array string in [SharedPreferences] under the
 * key [PREF_NOTIFICATION_BLOCKLIST].  An empty or absent key means no notifications
 * are filtered.
 *
 * Memory efficiency: the blocklist is loaded from prefs only once per notification
 * event and compared with simple [String.contains] checks (O(n × m) where n is
 * the word count and m is the text length — both are expected to be small).
 */
class MindfulNotificationService : NotificationListenerService() {

    companion object {
        private const val TAG = "MindfulNotifService"

        /** SharedPreferences key for the JSON-encoded blocklist pushed via FCM. */
        const val PREF_NOTIFICATION_BLOCKLIST = "mindful_notification_blocklist"

        /** Pattern template for whole-word matching; filled with the escaped word. */
        private const val WORD_BOUNDARY_REGEX = "(?<![\\w])%s(?![\\w])"
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val extras = sbn.notification?.extras ?: return

        val title = extras.getCharSequence("android.title")?.toString().orEmpty()
        val text  = extras.getCharSequence("android.text")?.toString().orEmpty()
        val big   = extras.getCharSequence("android.bigText")?.toString().orEmpty()

        val combined = "$title $text $big"

        if (combined.isBlank()) return

        val blocklist = loadBlocklist()
        if (blocklist.isEmpty()) return

        val combinedLower = combined.lowercase()
        for (word in blocklist) {
            if (word.isNotBlank() && WORD_BOUNDARY_REGEX.format(Regex.escape(word)).toRegex()
                    .containsMatchIn(combinedLower)) {
                Log.i(TAG, "Cancelling notification from ${sbn.packageName}: matched a blocked word")
                cancelNotification(sbn.key)
                return
            }
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        // No-op — we only care about incoming notifications.
    }

    // ------------------------------------------------------------------
    //  Helpers
    // ------------------------------------------------------------------

    /**
     * Loads the blocklist from [SharedPreferences].  Parses the stored JSON array
     * into a plain [List] of lower-case strings.  Returns an empty list on any
     * parse error so the service degrades gracefully.
     */
    private fun loadBlocklist(): List<String> {
        val prefs = PreferenceManager.getDefaultSharedPreferences(applicationContext)
        val json  = prefs.getString(PREF_NOTIFICATION_BLOCKLIST, null)
            ?.takeIf { it.isNotBlank() } ?: return emptyList()

        return try {
            val arr = JSONArray(json)
            List(arr.length()) { i -> arr.getString(i).lowercase() }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse notification blocklist JSON", e)
            emptyList()
        }
    }
}
