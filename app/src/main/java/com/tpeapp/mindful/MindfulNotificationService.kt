package com.tpeapp.mindful

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import androidx.preference.PreferenceManager
import com.tpeapp.ble.LovenseManager
import com.tpeapp.ble.PavlokManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.random.Random
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
        private const val COMMAND_DEDUPE_WINDOW_MS = 15_000L
        private const val CONNECT_WAIT_TIMEOUT_MS = 12_000L
        private const val CONNECT_POLL_MS = 400L
        private const val INTER_PULSE_DELAY_MIN_MS = 500L
        private const val INTER_PULSE_DELAY_MAX_MS = 2_300L
        private const val BUZZ_STRENGTH_MIN_PERCENT = 10
        private const val BUZZ_STRENGTH_MAX_PERCENT = 100

        private val BUZZ_SECONDS_REGEX = Regex("""^\d{1,3}s?$""")
        private val ZAP_STRENGTH_REGEX = Regex("""^\d{1,3}%?$""")

        /**
         * Live reference to the bound service instance.  Set in [onListenerConnected]
         * and cleared in [onListenerDisconnected] so it is only non-null when the
         * system has actually connected the listener.
         */
        @Volatile private var instance: MindfulNotificationService? = null

        /**
         * Cancels all active status-bar notifications.
         * @return `true` if the listener was connected and the call was delegated,
         *         `false` if the listener is not currently bound.
         */
        fun clearAll(): Boolean {
            val svc = instance ?: return false
            svc.cancelAllNotifications()
            Log.i(TAG, "clearAll: all notifications cancelled")
            return true
        }
    }

    private var serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val recentCommandSignatures = LinkedHashMap<String, Long>(32, 0.75f, true)

    override fun onListenerConnected() {
        super.onListenerConnected()
        instance = this
        serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        Log.i(TAG, "Notification listener connected")
        runCatching {
            LovenseManager.init(applicationContext)
            PavlokManager.init(applicationContext)
        }.onFailure { err ->
            Log.w(TAG, "Failed to init BLE managers for notification commands", err)
        }
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        instance = null
        serviceScope.cancel()
        Log.i(TAG, "Notification listener disconnected")
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val extras = sbn.notification?.extras ?: return

        val title = extras.getCharSequence("android.title")?.toString().orEmpty()
        val text  = extras.getCharSequence("android.text")?.toString().orEmpty()
        val big   = extras.getCharSequence("android.bigText")?.toString().orEmpty()
        val sub   = extras.getCharSequence("android.subText")?.toString().orEmpty()
        val summary = extras.getCharSequence("android.summaryText")?.toString().orEmpty()
        val actions = sbn.notification.actions
            ?.mapNotNull { action -> action?.title?.toString()?.trim()?.takeIf { it.isNotBlank() } }
            ?.joinToString(" ")
            .orEmpty()

        val combined = listOf(title, text, big, sub, summary, actions)
            .filter { it.isNotBlank() }
            .joinToString(" ")
        Log.i(TAG, "Notification posted from ${sbn.packageName}; textLength=${combined.length}")
        Log.i(TAG, "Notification payload title='$title' text='$text' big='$big' sub='$sub' summary='$summary' actions='$actions'")

        if (combined.isBlank()) return

        handleNotificationCommand(combined)

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

    private fun handleNotificationCommand(combined: String) {
        val parsed = parseNotificationCommand(combined) ?: return
        Log.i(TAG, "Parsed notification command: $parsed")
        val now = System.currentTimeMillis()
        val signature = when (parsed) {
            is NotificationCommand.Buzz -> "buzz:${parsed.seconds}:${parsed.repeatCount}"
            is NotificationCommand.Zap -> "zap:${parsed.intensityPercent}:${parsed.repeatCount}"
        }
        if (isDuplicateCommand(signature, now)) {
            return
        }

        when (parsed) {
            is NotificationCommand.Buzz -> {
                serviceScope.launch {
                    if (!awaitLovenseConnection()) {
                        Log.w(TAG, "Skipping buzz command: Lovense did not connect in time")
                        return@launch
                    }
                    repeat(parsed.repeatCount) { idx ->
                        val strengthPercent = Random.nextInt(
                            from = BUZZ_STRENGTH_MIN_PERCENT,
                            until = BUZZ_STRENGTH_MAX_PERCENT + 1,
                        )
                        val lovenseLevel = ((strengthPercent / 100.0) * 20.0)
                            .toInt()
                            .coerceIn(2, 20)
                        LovenseManager.vibrate(lovenseLevel)
                        delay(parsed.seconds * 1000L)
                        LovenseManager.stopAll()
                        if (idx < parsed.repeatCount - 1) {
                            val gap = Random.nextLong(
                                from = INTER_PULSE_DELAY_MIN_MS,
                                until = INTER_PULSE_DELAY_MAX_MS + 1,
                            )
                            delay(gap)
                        }
                    }
                }
            }
            is NotificationCommand.Zap -> {
                val intensity = ((parsed.intensityPercent / 100.0) * 255.0).toInt().coerceIn(1, 255)
                serviceScope.launch {
                    if (!awaitPavlokConnection()) {
                        Log.w(TAG, "Skipping zap command: Pavlok did not connect in time")
                        return@launch
                    }
                    repeat(parsed.repeatCount) { idx ->
                        PavlokManager.zap(intensity = intensity, durationMs = 500)
                        if (idx < parsed.repeatCount - 1) {
                            val gap = Random.nextLong(
                                from = INTER_PULSE_DELAY_MIN_MS,
                                until = INTER_PULSE_DELAY_MAX_MS + 1,
                            )
                            delay(gap)
                        }
                    }
                }
            }
        }
    }

    private suspend fun awaitLovenseConnection(): Boolean {
        if (LovenseManager.isConnected()) return true
        Log.i(TAG, "Buzz command received while Lovense disconnected; starting scan")
        LovenseManager.startScan()
        val deadline = System.currentTimeMillis() + CONNECT_WAIT_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            if (LovenseManager.isConnected()) return true
            delay(CONNECT_POLL_MS)
        }
        return LovenseManager.isConnected()
    }

    private suspend fun awaitPavlokConnection(): Boolean {
        if (PavlokManager.isConnected()) return true
        Log.i(TAG, "Zap command received while Pavlok disconnected; starting scan")
        PavlokManager.startScan()
        val deadline = System.currentTimeMillis() + CONNECT_WAIT_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            if (PavlokManager.isConnected()) return true
            delay(CONNECT_POLL_MS)
        }
        return PavlokManager.isConnected()
    }

    private fun isDuplicateCommand(signature: String, nowMs: Long): Boolean {
        val entries = recentCommandSignatures.entries.iterator()
        while (entries.hasNext()) {
            val age = nowMs - entries.next().value
            if (age > COMMAND_DEDUPE_WINDOW_MS) {
                entries.remove()
            }
        }
        val seenAt = recentCommandSignatures[signature]
        if (seenAt != null && nowMs - seenAt < COMMAND_DEDUPE_WINDOW_MS) {
            return true
        }
        recentCommandSignatures[signature] = nowMs
        if (recentCommandSignatures.size > 64) {
            recentCommandSignatures.entries.firstOrNull()?.let {
                recentCommandSignatures.remove(it.key)
            }
        }
        return false
    }

    private fun parseNotificationCommand(raw: String): NotificationCommand? {
        val tokens = Regex("""[a-z0-9%]+""")
            .findAll(raw.lowercase())
            .map { it.value }
            .toList()
        if (tokens.isEmpty()) return null

        val buzzIndex = tokens.indexOf("buzz")
        if (buzzIndex >= 0) {
            val seconds = extractBuzzSeconds(tokens, buzzIndex)
                ?: extractAnySeconds(tokens)
                ?: 1
            val loop = tokens.contains("loop")
            val loopIndex = tokens.indexOf("loop")
            val repeatCount = if (loop) {
                extractNumberAfterIndex(tokens, loopIndex, BUZZ_SECONDS_REGEX, "s")
                    ?: extractSecondNumeric(tokens, seconds, BUZZ_SECONDS_REGEX, "s")
                    ?: seconds
            } else {
                1
            }
            return NotificationCommand.Buzz(
                seconds = seconds.coerceIn(1, 300),
                repeatCount = repeatCount.coerceIn(1, 300),
            )
        }

        val zapIndex = tokens.indexOf("zap")
        if (zapIndex >= 0) {
            val strength = extractZapStrength(tokens, zapIndex) ?: 40
            val loop = tokens.contains("loop")
            val loopIndex = tokens.indexOf("loop")
            val repeatCount = if (loop) {
                extractNumberAfterIndex(tokens, loopIndex, ZAP_STRENGTH_REGEX, "%")
                    ?: extractSecondNumeric(tokens, strength, ZAP_STRENGTH_REGEX, "%")
                    ?: strength
            } else {
                1
            }
            return NotificationCommand.Zap(
                intensityPercent = strength.coerceIn(1, 100),
                repeatCount = repeatCount.coerceIn(1, 100),
            )
        }
        return null
    }

    private fun extractBuzzSeconds(tokens: List<String>, index: Int): Int? {
        val candidates = listOfNotNull(
            tokens.getOrNull(index + 1),
            tokens.getOrNull(index + 2),
            tokens.getOrNull(index - 1),
        )
        for (token in candidates) {
            if (!BUZZ_SECONDS_REGEX.matches(token)) continue
            val normalized = if (token.endsWith("s")) token.dropLast(1) else token
            val seconds = normalized.toIntOrNull() ?: continue
            return seconds
        }
        return null
    }

    private fun extractAnySeconds(tokens: List<String>): Int? {
        for (token in tokens) {
            if (!BUZZ_SECONDS_REGEX.matches(token)) continue
            val normalized = if (token.endsWith("s")) token.dropLast(1) else token
            val seconds = normalized.toIntOrNull() ?: continue
            return seconds
        }
        return null
    }

    private fun extractZapStrength(tokens: List<String>, index: Int): Int? {
        val candidates = listOfNotNull(
            tokens.getOrNull(index + 1),
            tokens.getOrNull(index + 2),
            tokens.getOrNull(index - 1),
        )
        for (token in candidates) {
            if (!ZAP_STRENGTH_REGEX.matches(token)) continue
            val normalized = if (token.endsWith("%")) token.dropLast(1) else token
            val value = normalized.toIntOrNull() ?: continue
            return value
        }
        return null
    }

    private fun extractNumberAfterIndex(
        tokens: List<String>,
        index: Int,
        regex: Regex,
        trailingSymbol: String,
    ): Int? {
        if (index < 0) return null
        for (i in (index + 1) until tokens.size) {
            val token = tokens[i]
            if (!regex.matches(token)) continue
            val normalized = if (token.endsWith(trailingSymbol)) token.dropLast(1) else token
            return normalized.toIntOrNull()
        }
        return null
    }

    private fun extractSecondNumeric(
        tokens: List<String>,
        firstValue: Int,
        regex: Regex,
        trailingSymbol: String,
    ): Int? {
        var firstConsumed = false
        for (token in tokens) {
            if (!regex.matches(token)) continue
            val normalized = if (token.endsWith(trailingSymbol)) token.dropLast(1) else token
            val parsed = normalized.toIntOrNull() ?: continue
            if (!firstConsumed && parsed == firstValue) {
                firstConsumed = true
                continue
            }
            return parsed
        }
        return null
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

    private sealed class NotificationCommand {
        data class Buzz(val seconds: Int, val repeatCount: Int) : NotificationCommand()
        data class Zap(val intensityPercent: Int, val repeatCount: Int) : NotificationCommand()
    }
}
