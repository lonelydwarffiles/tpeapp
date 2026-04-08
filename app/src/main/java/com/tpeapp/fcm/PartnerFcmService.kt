package com.tpeapp.fcm

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.SharedPreferences
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.preference.PreferenceManager
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.tpeapp.R
import com.tpeapp.ble.LovenseManager
import com.tpeapp.ble.PavlokManager
import com.tpeapp.mindful.ComplianceManager
import com.tpeapp.mindful.MindfulNotificationService
import com.tpeapp.mindful.ToneEnforcementService
import com.tpeapp.questions.QuestionsActivity
import com.tpeapp.tasks.Task
import com.tpeapp.tasks.TaskListActivity
import com.tpeapp.tasks.TaskRepository
import com.tpeapp.tasks.TaskStatus

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

        private const val TASK_CHANNEL_ID    = "tpe_task_assigned"
        private const val TASK_NOTIF_ID_BASE = 3001

        private const val QUESTIONS_CHANNEL_ID   = "tpe_questions"
        private const val QUESTIONS_NOTIF_ID     = 4001
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
            "UPDATE_SETTINGS"              -> handleUpdateSettings(data)
            "UPDATE_NOTIFICATION_BLOCKLIST" -> handleUpdateNotificationBlocklist(data)
            "UPDATE_RESTRICTED_VOCABULARY"  -> handleUpdateRestrictedVocabulary(data)
            "UPDATE_TONE_COMPLIANCE"        -> handleUpdateToneCompliance(data)
            "LOVENSE_COMMAND"               -> handleLovenseCommand(data)
            "PAVLOK_COMMAND"                -> handlePavlokCommand(data)
            "TASK_ASSIGNED"                 -> handleTaskAssigned(data)
            "NEW_QUESTION"                  -> handleNewQuestion(data)
            else                           -> Log.w(TAG, "Unknown FCM action: ${data["action"]}")
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

    /**
     * Toggles the strict tone-enforcement mode pushed by the partner.
     *
     * Expected payload:
     * ```
     * { "action": "UPDATE_TONE_COMPLIANCE", "strict_tone_mode": "true" }
     * ```
     */
    private fun handleUpdateToneCompliance(data: Map<String, String>) {
        val strict = data["strict_tone_mode"]?.toBooleanStrictOrNull() ?: return
        ComplianceManager.setStrictToneMode(applicationContext, strict)
        Log.i(TAG, "Strict tone mode updated via FCM → $strict")
        val details = if (strict) {
            "Your accountability partner has enabled strict tone enforcement."
        } else {
            "Your accountability partner has disabled strict tone enforcement."
        }
        showSettingsChangedNotification(details)
    }

    /**
     * Processes a toy command pushed by the partner, allowing out-of-band Lovense control
     * without requiring an active streaming session.
     *
     * Expected payload:
     * ```
     * { "action": "LOVENSE_COMMAND", "toy_command": "vibrate", "toy_level": "15" }
     * ```
     *
     * Supported `toy_command` values: `vibrate`, `rotate`, `pump`, `stop`, `battery`.
     */
    private fun handleLovenseCommand(data: Map<String, String>) {
        val cmd   = data["toy_command"]?.lowercase() ?: return
        val level = data["toy_level"]?.toIntOrNull()?.coerceIn(0, 20) ?: 0
        LovenseManager.init(applicationContext)
        when (cmd) {
            "vibrate" -> LovenseManager.vibrate(level)
            "rotate"  -> LovenseManager.rotate(level)
            "pump"    -> LovenseManager.pump(level.coerceIn(0, 3))
            "stop"    -> LovenseManager.stopAll()
            "battery" -> LovenseManager.queryBattery()
            else      -> {
                Log.w(TAG, "Unknown Lovense FCM command: $cmd")
                return
            }
        }
        val details = "Your partner sent a toy command: $cmd" +
            if (cmd != "stop" && cmd != "battery") " (level $level)" else ""
        showSettingsChangedNotification(details)
        Log.i(TAG, "Lovense FCM command handled: cmd=$cmd level=$level")
    }

    /**
     * Processes a Pavlok stimulus command pushed by the partner, allowing out-of-band
     * Pavlok control without requiring an active streaming session.
     *
     * Expected payload:
     * ```
     * {
     *   "action":              "PAVLOK_COMMAND",
     *   "pavlok_cmd":          "zap",   // zap | vibrate | beep | stop
     *   "pavlok_intensity":    "64",    // 0–255
     *   "pavlok_duration_ms":  "500"    // 0–25500 ms
     * }
     * ```
     *
     * Supported `pavlok_cmd` values: `zap`, `vibrate`, `beep`, `stop`.
     */
    private fun handlePavlokCommand(data: Map<String, String>) {
        val cmd        = data["pavlok_cmd"]?.lowercase() ?: return
        val intensity  = data["pavlok_intensity"]?.toIntOrNull()?.coerceIn(0, 255) ?: 64
        val durationMs = data["pavlok_duration_ms"]?.toIntOrNull()?.coerceIn(0, 25_500) ?: 500
        PavlokManager.init(applicationContext)
        when (cmd) {
            "zap"     -> PavlokManager.zap(intensity, durationMs)
            "vibrate" -> PavlokManager.vibrate(intensity, durationMs)
            "beep"    -> PavlokManager.beep(intensity, durationMs)
            "stop"    -> PavlokManager.stopAll()
            else      -> {
                Log.w(TAG, "Unknown Pavlok FCM command: $cmd")
                return
            }
        }
        val details = if (cmd != "stop") {
            "Your partner sent a Pavlok command: $cmd (intensity=$intensity, duration=${durationMs}ms)"
        } else {
            "Your partner sent a Pavlok command: $cmd"
        }
        showSettingsChangedNotification(details)
        Log.i(TAG, "Pavlok FCM command handled: cmd=$cmd intensity=$intensity durationMs=$durationMs")
    }

    /**
     * Persists a new task pushed by the partner, schedules its deadline alarm,
     * and shows a notification so the device owner is immediately aware.
     *
     * Expected payload:
     * ```
     * {
     *   "action":      "TASK_ASSIGNED",
     *   "task_id":     "uuid-string",
     *   "task_title":  "Morning workout",
     *   "task_desc":   "Complete 30 minutes of exercise and take a photo as proof.",
     *   "deadline_ms": "1712345678000"
     * }
     * ```
     */
    private fun handleTaskAssigned(data: Map<String, String>) {
        val taskId     = data["task_id"]?.takeIf { it.isNotBlank() }     ?: run {
            Log.w(TAG, "TASK_ASSIGNED missing task_id — ignoring"); return
        }
        val title      = data["task_title"]?.takeIf { it.isNotBlank() }  ?: run {
            Log.w(TAG, "TASK_ASSIGNED missing task_title — ignoring"); return
        }
        val description = data["task_desc"] ?: ""
        val deadlineMs  = data["deadline_ms"]?.toLongOrNull()             ?: run {
            Log.w(TAG, "TASK_ASSIGNED missing or invalid deadline_ms — ignoring"); return
        }

        val task = Task(
            id          = taskId,
            title       = title,
            description = description,
            deadlineMs  = deadlineMs,
            status      = TaskStatus.PENDING
        )

        TaskRepository.upsertTask(applicationContext, task)
        TaskRepository.scheduleDeadlineAlarm(applicationContext, task)

        showTaskAssignedNotification(task)
        Log.i(TAG, "Task assigned: id=$taskId title='$title' deadline=$deadlineMs")
    }

    /**
     * Fired when someone drops an anonymous question in the Puppy Pouch.
     *
     * Expected payload:
     * ```
     * {
     *   "action":           "NEW_QUESTION",
     *   "question_id":      "uuid-string",
     *   "question_preview": "First 120 chars of the question…"
     * }
     * ```
     *
     * Shows a high-priority heads-up notification.  Tapping it opens
     * [QuestionsActivity] so the partner can answer immediately.
     */
    private fun handleNewQuestion(data: Map<String, String>) {
        val questionId      = data["question_id"] ?: ""
        val questionPreview = data["question_preview"]?.takeIf { it.isNotBlank() }
            ?: getString(R.string.questions_notif_title)

        val nm = getSystemService(NotificationManager::class.java)
        ensureQuestionsChannel(nm)

        val tapIntent = Intent(this, QuestionsActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        val tapPending = PendingIntent.getActivity(
            this,
            questionId.hashCode(),
            tapIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, QUESTIONS_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_shield)
            .setContentTitle(getString(R.string.questions_notif_title))
            .setContentText(questionPreview)
            .setStyle(NotificationCompat.BigTextStyle().bigText(questionPreview))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setAutoCancel(true)
            .setContentIntent(tapPending)
            .addAction(
                R.drawable.ic_shield,
                getString(R.string.questions_notif_action),
                tapPending
            )
            .build()

        nm.notify(QUESTIONS_NOTIF_ID + (questionId.hashCode() and 0x0FFF), notification)
        Log.i(TAG, "NEW_QUESTION notification shown for id=$questionId")
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

    // ------------------------------------------------------------------
    //  Task assignment notification
    // ------------------------------------------------------------------

    private fun showTaskAssignedNotification(task: Task) {
        val nm = getSystemService(NotificationManager::class.java)
        ensureTaskChannel(nm)

        val tapIntent = Intent(this, TaskListActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        val tapPending = PendingIntent.getActivity(
            this,
            task.id.hashCode(),
            tapIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, TASK_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_shield)
            .setContentTitle(getString(R.string.task_fcm_notif_title))
            .setContentText(task.title)
            .setStyle(NotificationCompat.BigTextStyle().bigText(
                "${task.title}\n${task.description}"
            ))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(tapPending)
            .build()

        nm.notify(TASK_NOTIF_ID_BASE + (task.id.hashCode() and 0xFF), notification)
    }

    private fun ensureTaskChannel(nm: NotificationManager) {
        if (nm.getNotificationChannel(TASK_CHANNEL_ID) != null) return
        nm.createNotificationChannel(
            NotificationChannel(
                TASK_CHANNEL_ID,
                getString(R.string.task_fcm_channel_name),
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Alerts when a new task is assigned by your partner"
                enableVibration(true)
            }
        )
    }

    private fun ensureQuestionsChannel(nm: NotificationManager) {
        if (nm.getNotificationChannel(QUESTIONS_CHANNEL_ID) != null) return
        nm.createNotificationChannel(
            NotificationChannel(
                QUESTIONS_CHANNEL_ID,
                getString(R.string.questions_notif_channel_name),
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = getString(R.string.questions_notif_channel_desc)
                enableVibration(true)
                enableLights(true)
            }
        )
    }
}
