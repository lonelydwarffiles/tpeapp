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
import com.tpeapp.affirmation.AffirmationActivity
import com.tpeapp.affirmation.AffirmationEntry
import com.tpeapp.affirmation.AffirmationRepository
import com.tpeapp.affirmation.MantraAlarmReceiver
import com.tpeapp.apps.AppInventoryManager
import com.tpeapp.ble.LovenseScheduleManager
import com.tpeapp.checkin.CheckInActivity
import com.tpeapp.consequence.ConsequenceEscalationHelper
import com.tpeapp.consequence.CornerTimeActivity
import com.tpeapp.device.DeviceCommandManager
import com.tpeapp.gating.AppGatingManager
import com.tpeapp.gating.GeofenceEntry
import com.tpeapp.gating.GeofenceManager
import com.tpeapp.mindful.ComplianceManager
import com.tpeapp.mindful.HonorificManager
import com.tpeapp.mindful.MindfulNotificationService
import com.tpeapp.mindful.PermissionToSpeakManager
import com.tpeapp.mindful.ToneEnforcementService
import com.tpeapp.questions.QuestionsActivity
import com.tpeapp.review.ReviewActivity
import com.tpeapp.review.ScreencastService
import com.tpeapp.ritual.RitualRepository
import com.tpeapp.ritual.RitualStep
import com.tpeapp.service.FilterService
import com.tpeapp.ble.LovenseManager
import com.tpeapp.ble.PavlokManager
import com.tpeapp.status.SubStatusManager
import com.tpeapp.tasks.Task
import com.tpeapp.tasks.TaskListActivity
import com.tpeapp.tasks.TaskRepository
import com.tpeapp.tasks.TaskStatus
import org.json.JSONArray
import org.json.JSONObject

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

        // SharedPreferences keys for filter settings are now defined in FilterService
        // to avoid a circular dependency.  Kept here as aliases for backward compatibility.
        val PREF_THRESHOLD       get() = FilterService.PREF_THRESHOLD
        val PREF_STRICT_MODE     get() = FilterService.PREF_STRICT_MODE
        val PREF_BLOCKED_CLASSES get() = FilterService.PREF_BLOCKED_CLASSES

        const val PREF_FCM_TOKEN = "fcm_registration_token"

        private const val TASK_CHANNEL_ID    = "tpe_task_assigned"
        private const val TASK_NOTIF_ID_BASE = 3001

        private const val QUESTIONS_CHANNEL_ID   = "tpe_questions"
        private const val QUESTIONS_NOTIF_ID     = 4001

        private const val REVIEW_CHANNEL_ID      = "tpe_review_request"
        private const val REVIEW_NOTIF_ID        = 5001

        private const val CHECKIN_CHANNEL_ID     = "tpe_checkin_request"
        private const val CHECKIN_NOTIF_ID       = 6001

        private const val RULE_CHANNEL_ID        = "tpe_rule_reminder"
        private const val RULE_NOTIF_ID_BASE     = 7001
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
            "START_REVIEW"                  -> handleStartReview(data)
            "REQUEST_CHECKIN"               -> handleRequestCheckin()
            "RULE_REMINDER"                 -> handleRuleReminder(data)
            "OPEN_APP"                      -> handleOpenApp(data)
            "FORCE_STOP_APP"                -> handleForceStopApp(data)
            "DISABLE_APP"                   -> handleDisableApp(data)
            "ENABLE_APP"                    -> handleEnableApp(data)
            "CLEAR_APP_CACHE"               -> handleClearAppCache(data)
            "UNINSTALL_APP"                 -> handleUninstallApp(data)
            // Screen & display
            "OPEN_URL"                      -> handleOpenUrl(data)
            "SET_BRIGHTNESS"                -> handleSetBrightness(data)
            "SCREEN_ON"                     -> handleScreenOn()
            "SCREEN_OFF"                    -> handleScreenOff()
            "SET_SCREEN_TIMEOUT"            -> handleSetScreenTimeout(data)
            "SHOW_OVERLAY"                  -> handleShowOverlay(data)
            "SET_ORIENTATION"               -> handleSetOrientation(data)
            "SET_ROTATION"                  -> handleSetRotation(data)
            // Audio & sound
            "SET_VOLUME"                    -> handleSetVolume(data)
            "SET_RINGER_MODE"               -> handleSetRingerMode(data)
            "PLAY_AUDIO"                    -> handlePlayAudio(data)
            "SPEAK_TEXT"                    -> handleSpeakText(data)
            // Lock screen & access
            "LOCK_DEVICE"                   -> handleLockDevice()
            "DISMISS_KEYGUARD"              -> handleDismissKeyguard()
            // Network & connectivity
            "SET_WIFI"                      -> handleSetWifi(data)
            "SET_MOBILE_DATA"               -> handleSetMobileData(data)
            "SET_AIRPLANE_MODE"             -> handleSetAirplaneMode(data)
            "SET_BLUETOOTH"                 -> handleSetBluetooth(data)
            "CONNECT_WIFI"                  -> handleConnectWifi(data)
            // Camera & sensors
            "TAKE_SCREENSHOT"               -> handleTakeScreenshot()
            "RECORD_SCREEN"                 -> handleRecordScreen(data)
            "SET_FLASHLIGHT"                -> handleSetFlashlight(data)
            "GET_LOCATION"                  -> handleGetLocation()
            // Notifications & interruptions
            "SEND_NOTIFICATION"             -> handleSendNotification(data)
            "CLEAR_NOTIFICATIONS"           -> handleClearNotifications()
            "SET_DND"                       -> handleSetDnd(data)
            "SET_ALARM"                     -> handleSetAlarm(data)
            // Device settings
            "SET_WALLPAPER"                 -> handleSetWallpaper(data)
            "SET_AUTO_ROTATE"               -> handleSetAutoRotate(data)
            "SET_NFC"                       -> handleSetNfc(data)
            "SET_FONT_SIZE"                 -> handleSetFontSize(data)
            // App suspend / unsuspend
            "SUSPEND_APP"                   -> handleSuspendApp(data)
            "UNSUSPEND_APP"                 -> handleUnsuspendApp(data)
            // New submission-deepening features
            "SET_RITUALS"                   -> handleSetRituals(data)
            "SET_RITUAL_TIMES"              -> handleSetRitualTimes(data)
            "SET_HONORIFIC"                 -> handleSetHonorific(data)
            "SET_HONORIFIC_ENABLED"         -> handleSetHonorificEnabled(data)
            "SET_PTS_ENABLED"               -> handleSetPtsEnabled(data)
            "SET_PTS_APPROVED"              -> handleSetPtsApproved(data)
            "APP_PERMISSION_RESPONSE"       -> handleAppPermissionResponse(data)
            "START_CORNER_TIME"             -> handleStartCornerTime(data)
            "CANCEL_ESCALATION"             -> handleCancelEscalation()
            "SET_AFFIRMATIONS"              -> handleSetAffirmations(data)
            "SHOW_AFFIRMATION"              -> handleShowAffirmation(data)
            "SET_MANTRA_ENABLED"            -> handleSetMantraEnabled(data)
            "SET_MANTRA_INTERVAL"           -> handleSetMantraInterval(data)
            "SET_GATING_ENABLED"            -> handleSetGatingEnabled(data)
            "SET_GATING_APPROVED"           -> handleSetGatingApproved(data)
            "SET_GEOFENCES"                 -> handleSetGeofences(data)
            "SET_GEOFENCE_ENABLED"          -> handleSetGeofenceEnabled(data)
            "SET_LOVENSE_SCHEDULES"         -> handleSetLovenseSchedules(data)
            "SET_SUB_STATUS"                -> handleSetSubStatus(data)
            "SET_HANDLER_SYSTEM_PROMPT"     -> handleSetHandlerSystemPrompt(data)
            "SET_HANDLER_API_KEY"           -> handleSetHandlerApiKey(data)
            "SET_HANDLER_ENDPOINT"          -> handleSetHandlerEndpoint(data)
            "SET_HANDLER_MODEL"             -> handleSetHandlerModel(data)
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
    //  App control handlers
    // ------------------------------------------------------------------

    /**
     * Opens the named app by resolving its package and launching the system
     * launch intent.  Does not require root.
     *
     * Expected payload:
     * ```
     * { "action": "OPEN_APP", "app_name": "Instagram" }
     * ```
     */
    private fun handleOpenApp(data: Map<String, String>) {
        val appName = data["app_name"]?.takeIf { it.isNotBlank() } ?: run {
            Log.w(TAG, "OPEN_APP missing app_name"); return
        }
        val pkg = AppInventoryManager.resolvePackageName(applicationContext, appName) ?: run {
            Log.w(TAG, "OPEN_APP: no installed app matched '$appName'"); return
        }
        AppInventoryManager.openApp(applicationContext, pkg)
        showSettingsChangedNotification("Your partner opened app: $appName")
        Log.i(TAG, "OPEN_APP: $appName → $pkg")
    }

    /**
     * Force-stops the named app via `am force-stop`.  Requires root.
     *
     * Expected payload:
     * ```
     * { "action": "FORCE_STOP_APP", "app_name": "Instagram" }
     * ```
     */
    private fun handleForceStopApp(data: Map<String, String>) {
        val appName = data["app_name"]?.takeIf { it.isNotBlank() } ?: run {
            Log.w(TAG, "FORCE_STOP_APP missing app_name"); return
        }
        val pkg = AppInventoryManager.resolvePackageName(applicationContext, appName) ?: run {
            Log.w(TAG, "FORCE_STOP_APP: no installed app matched '$appName'"); return
        }
        AppInventoryManager.forceStopApp(pkg)
        showSettingsChangedNotification("Your partner force-stopped app: $appName")
        Log.i(TAG, "FORCE_STOP_APP: $appName → $pkg")
    }

    /**
     * Disables the named app via `pm disable-user`.  Requires root.
     *
     * Expected payload:
     * ```
     * { "action": "DISABLE_APP", "app_name": "Instagram" }
     * ```
     */
    private fun handleDisableApp(data: Map<String, String>) {
        val appName = data["app_name"]?.takeIf { it.isNotBlank() } ?: run {
            Log.w(TAG, "DISABLE_APP missing app_name"); return
        }
        val pkg = AppInventoryManager.resolvePackageName(applicationContext, appName) ?: run {
            Log.w(TAG, "DISABLE_APP: no installed app matched '$appName'"); return
        }
        AppInventoryManager.disableApp(pkg)
        showSettingsChangedNotification("Your partner disabled app: $appName")
        Log.i(TAG, "DISABLE_APP: $appName → $pkg")
    }

    /**
     * Re-enables a previously disabled app via `pm enable`.  Requires root.
     *
     * Expected payload:
     * ```
     * { "action": "ENABLE_APP", "app_name": "Instagram" }
     * ```
     */
    private fun handleEnableApp(data: Map<String, String>) {
        val appName = data["app_name"]?.takeIf { it.isNotBlank() } ?: run {
            Log.w(TAG, "ENABLE_APP missing app_name"); return
        }
        val pkg = AppInventoryManager.resolvePackageName(applicationContext, appName) ?: run {
            Log.w(TAG, "ENABLE_APP: no installed app matched '$appName'"); return
        }
        AppInventoryManager.enableApp(pkg)
        showSettingsChangedNotification("Your partner re-enabled app: $appName")
        Log.i(TAG, "ENABLE_APP: $appName → $pkg")
    }

    /**
     * Clears the named app's cache directory.  Requires root.
     *
     * Expected payload:
     * ```
     * { "action": "CLEAR_APP_CACHE", "app_name": "Instagram" }
     * ```
     */
    private fun handleClearAppCache(data: Map<String, String>) {
        val appName = data["app_name"]?.takeIf { it.isNotBlank() } ?: run {
            Log.w(TAG, "CLEAR_APP_CACHE missing app_name"); return
        }
        val pkg = AppInventoryManager.resolvePackageName(applicationContext, appName) ?: run {
            Log.w(TAG, "CLEAR_APP_CACHE: no installed app matched '$appName'"); return
        }
        AppInventoryManager.clearAppCache(pkg)
        showSettingsChangedNotification("Your partner cleared the cache for: $appName")
        Log.i(TAG, "CLEAR_APP_CACHE: $appName → $pkg")
    }

    /**
     * Uninstalls the named app for the current user via `pm uninstall --user 0`.
     * Requires root.
     *
     * Expected payload:
     * ```
     * { "action": "UNINSTALL_APP", "app_name": "Instagram" }
     * ```
     */
    private fun handleUninstallApp(data: Map<String, String>) {
        val appName = data["app_name"]?.takeIf { it.isNotBlank() } ?: run {
            Log.w(TAG, "UNINSTALL_APP missing app_name"); return
        }
        val pkg = AppInventoryManager.resolvePackageName(applicationContext, appName) ?: run {
            Log.w(TAG, "UNINSTALL_APP: no installed app matched '$appName'"); return
        }
        AppInventoryManager.uninstallApp(pkg)
        showSettingsChangedNotification("Your partner uninstalled app: $appName")
        Log.i(TAG, "UNINSTALL_APP: $appName → $pkg")
    }

    // ------------------------------------------------------------------
    //  Screen & Display handlers
    // ------------------------------------------------------------------

    /** `{ "action": "OPEN_URL", "url": "https://…" }` */
    private fun handleOpenUrl(data: Map<String, String>) {
        val url = data["url"]?.takeIf { it.isNotBlank() } ?: run {
            Log.w(TAG, "OPEN_URL missing url"); return
        }
        DeviceCommandManager.openUrl(applicationContext, url)
        Log.i(TAG, "OPEN_URL: $url")
    }

    /** `{ "action": "SET_BRIGHTNESS", "value": "200" }` (0–255) */
    private fun handleSetBrightness(data: Map<String, String>) {
        val value = data["value"]?.toIntOrNull() ?: run {
            Log.w(TAG, "SET_BRIGHTNESS missing/invalid value"); return
        }
        DeviceCommandManager.setBrightness(value)
        showSettingsChangedNotification("Your partner set screen brightness to $value.")
    }

    /** `{ "action": "SCREEN_ON" }` */
    private fun handleScreenOn() {
        DeviceCommandManager.screenOn()
        Log.i(TAG, "SCREEN_ON")
    }

    /** `{ "action": "SCREEN_OFF" }` */
    private fun handleScreenOff() {
        DeviceCommandManager.screenOff(applicationContext)
        Log.i(TAG, "SCREEN_OFF")
    }

    /** `{ "action": "SET_SCREEN_TIMEOUT", "ms": "60000" }` */
    private fun handleSetScreenTimeout(data: Map<String, String>) {
        val ms = data["ms"]?.toLongOrNull() ?: run {
            Log.w(TAG, "SET_SCREEN_TIMEOUT missing/invalid ms"); return
        }
        DeviceCommandManager.setScreenTimeout(ms)
        showSettingsChangedNotification("Your partner set screen timeout to ${ms / 1000}s.")
    }

    /**
     * ```
     * { "action": "SHOW_OVERLAY", "title": "…", "message": "…", "image_url": "https://…" }
     * ```
     */
    private fun handleShowOverlay(data: Map<String, String>) {
        DeviceCommandManager.showOverlay(
            context  = applicationContext,
            title    = data["title"]   ?: "",
            message  = data["message"] ?: "",
            imageUrl = data["image_url"]
        )
        Log.i(TAG, "SHOW_OVERLAY")
    }

    /** `{ "action": "SET_ORIENTATION", "landscape": "true" }` */
    private fun handleSetOrientation(data: Map<String, String>) {
        val landscape = data["landscape"]?.toBooleanStrictOrNull() ?: run {
            Log.w(TAG, "SET_ORIENTATION missing/invalid landscape"); return
        }
        DeviceCommandManager.setOrientation(landscape)
        showSettingsChangedNotification(
            "Your partner set orientation to ${if (landscape) "landscape" else "portrait"}."
        )
    }

    /** `{ "action": "SET_ROTATION", "enabled": "true" }` */
    private fun handleSetRotation(data: Map<String, String>) {
        val enabled = data["enabled"]?.toBooleanStrictOrNull() ?: run {
            Log.w(TAG, "SET_ROTATION missing/invalid enabled"); return
        }
        DeviceCommandManager.setAutoRotate(enabled)
        showSettingsChangedNotification(
            "Your partner ${if (enabled) "enabled" else "disabled"} auto-rotation."
        )
    }

    // ------------------------------------------------------------------
    //  Audio & Sound handlers
    // ------------------------------------------------------------------

    /**
     * ```
     * { "action": "SET_VOLUME", "stream": "media", "level": "80", "max": "false" }
     * ```
     */
    private fun handleSetVolume(data: Map<String, String>) {
        val stream = data["stream"] ?: "media"
        val level  = data["level"]?.toIntOrNull() ?: 50
        val max    = data["max"]?.toBooleanStrictOrNull() ?: false
        DeviceCommandManager.setVolume(applicationContext, stream, level, max)
        showSettingsChangedNotification("Your partner set $stream volume.")
    }

    /** `{ "action": "SET_RINGER_MODE", "mode": "vibrate" }` (normal/vibrate/silent) */
    private fun handleSetRingerMode(data: Map<String, String>) {
        val mode = data["mode"]?.takeIf { it.isNotBlank() } ?: run {
            Log.w(TAG, "SET_RINGER_MODE missing mode"); return
        }
        DeviceCommandManager.setRingerMode(applicationContext, mode)
        showSettingsChangedNotification("Your partner set ringer mode to $mode.")
    }

    /** `{ "action": "PLAY_AUDIO", "url": "https://…/clip.mp3" }` */
    private fun handlePlayAudio(data: Map<String, String>) {
        val url = data["url"]?.takeIf { it.isNotBlank() } ?: run {
            Log.w(TAG, "PLAY_AUDIO missing url"); return
        }
        DeviceCommandManager.playAudio(url)
        Log.i(TAG, "PLAY_AUDIO: $url")
    }

    /** `{ "action": "SPEAK_TEXT", "text": "Hello" }` */
    private fun handleSpeakText(data: Map<String, String>) {
        val text = data["text"]?.takeIf { it.isNotBlank() } ?: run {
            Log.w(TAG, "SPEAK_TEXT missing text"); return
        }
        DeviceCommandManager.speakText(applicationContext, text)
        Log.i(TAG, "SPEAK_TEXT: '$text'")
    }

    // ------------------------------------------------------------------
    //  Lock Screen handlers
    // ------------------------------------------------------------------

    /** `{ "action": "LOCK_DEVICE" }` */
    private fun handleLockDevice() {
        DeviceCommandManager.lockDevice(applicationContext)
        showSettingsChangedNotification("Your partner locked the device.")
    }

    /** `{ "action": "DISMISS_KEYGUARD" }` */
    private fun handleDismissKeyguard() {
        DeviceCommandManager.dismissKeyguard(applicationContext)
        Log.i(TAG, "DISMISS_KEYGUARD")
    }

    // ------------------------------------------------------------------
    //  Network & Connectivity handlers
    // ------------------------------------------------------------------

    /** `{ "action": "SET_WIFI", "enabled": "true" }` */
    private fun handleSetWifi(data: Map<String, String>) {
        val enabled = data["enabled"]?.toBooleanStrictOrNull() ?: run {
            Log.w(TAG, "SET_WIFI missing/invalid enabled"); return
        }
        DeviceCommandManager.setWifi(enabled)
        showSettingsChangedNotification("Your partner ${if (enabled) "enabled" else "disabled"} Wi-Fi.")
    }

    /** `{ "action": "SET_MOBILE_DATA", "enabled": "true" }` */
    private fun handleSetMobileData(data: Map<String, String>) {
        val enabled = data["enabled"]?.toBooleanStrictOrNull() ?: run {
            Log.w(TAG, "SET_MOBILE_DATA missing/invalid enabled"); return
        }
        DeviceCommandManager.setMobileData(enabled)
        showSettingsChangedNotification("Your partner ${if (enabled) "enabled" else "disabled"} mobile data.")
    }

    /** `{ "action": "SET_AIRPLANE_MODE", "enabled": "true" }` */
    private fun handleSetAirplaneMode(data: Map<String, String>) {
        val enabled = data["enabled"]?.toBooleanStrictOrNull() ?: run {
            Log.w(TAG, "SET_AIRPLANE_MODE missing/invalid enabled"); return
        }
        DeviceCommandManager.setAirplaneMode(enabled)
        showSettingsChangedNotification("Your partner ${if (enabled) "enabled" else "disabled"} airplane mode.")
    }

    /** `{ "action": "SET_BLUETOOTH", "enabled": "true" }` */
    private fun handleSetBluetooth(data: Map<String, String>) {
        val enabled = data["enabled"]?.toBooleanStrictOrNull() ?: run {
            Log.w(TAG, "SET_BLUETOOTH missing/invalid enabled"); return
        }
        DeviceCommandManager.setBluetooth(enabled)
        showSettingsChangedNotification("Your partner ${if (enabled) "enabled" else "disabled"} Bluetooth.")
    }

    /** `{ "action": "CONNECT_WIFI", "ssid": "Home", "password": "hunter2" }` */
    private fun handleConnectWifi(data: Map<String, String>) {
        val ssid = data["ssid"]?.takeIf { it.isNotBlank() } ?: run {
            Log.w(TAG, "CONNECT_WIFI missing ssid"); return
        }
        DeviceCommandManager.connectWifi(ssid, data["password"])
        showSettingsChangedNotification("Your partner connected you to Wi-Fi: $ssid.")
    }

    // ------------------------------------------------------------------
    //  Camera & Sensors handlers
    // ------------------------------------------------------------------

    /** `{ "action": "TAKE_SCREENSHOT" }` */
    private fun handleTakeScreenshot() {
        DeviceCommandManager.takeScreenshot(applicationContext)
        Log.i(TAG, "TAKE_SCREENSHOT")
    }

    /** `{ "action": "RECORD_SCREEN", "duration_sec": "10" }` */
    private fun handleRecordScreen(data: Map<String, String>) {
        val dur = data["duration_sec"]?.toIntOrNull() ?: 10
        DeviceCommandManager.recordScreen(applicationContext, dur)
        Log.i(TAG, "RECORD_SCREEN: duration=$dur")
    }

    /** `{ "action": "SET_FLASHLIGHT", "enabled": "true" }` */
    private fun handleSetFlashlight(data: Map<String, String>) {
        val enabled = data["enabled"]?.toBooleanStrictOrNull() ?: run {
            Log.w(TAG, "SET_FLASHLIGHT missing/invalid enabled"); return
        }
        DeviceCommandManager.setFlashlight(applicationContext, enabled)
        Log.i(TAG, "SET_FLASHLIGHT: $enabled")
    }

    /** `{ "action": "GET_LOCATION" }` */
    private fun handleGetLocation() {
        DeviceCommandManager.getLocation(applicationContext)
        Log.i(TAG, "GET_LOCATION")
    }

    // ------------------------------------------------------------------
    //  Notifications & Interruptions handlers
    // ------------------------------------------------------------------

    /** `{ "action": "SEND_NOTIFICATION", "title": "Hey", "body": "Check in now" }` */
    private fun handleSendNotification(data: Map<String, String>) {
        val title = data["title"]?.takeIf { it.isNotBlank() } ?: run {
            Log.w(TAG, "SEND_NOTIFICATION missing title"); return
        }
        val body = data["body"] ?: ""
        DeviceCommandManager.sendNotification(applicationContext, title, body, data["channel_id"])
        Log.i(TAG, "SEND_NOTIFICATION: '$title'")
    }

    /** `{ "action": "CLEAR_NOTIFICATIONS" }` */
    private fun handleClearNotifications() {
        DeviceCommandManager.clearNotifications(applicationContext)
        Log.i(TAG, "CLEAR_NOTIFICATIONS")
    }

    /**
     * ```
     * { "action": "SET_DND", "policy": "none" }
     * ```
     * policy: `all` (off) | `priority` | `alarms` | `none` (total silence)
     */
    private fun handleSetDnd(data: Map<String, String>) {
        val policy = data["policy"]?.takeIf { it.isNotBlank() } ?: "all"
        DeviceCommandManager.setDnd(applicationContext, policy)
        showSettingsChangedNotification("Your partner set Do Not Disturb to: $policy.")
    }

    /** `{ "action": "SET_ALARM", "title": "Morning", "time_ms": "1712345678000" }` */
    private fun handleSetAlarm(data: Map<String, String>) {
        val title  = data["title"] ?: "Partner Alarm"
        val timeMs = data["time_ms"]?.toLongOrNull() ?: run {
            Log.w(TAG, "SET_ALARM missing/invalid time_ms"); return
        }
        DeviceCommandManager.setAlarm(applicationContext, title, timeMs)
        showSettingsChangedNotification("Your partner set an alarm: $title.")
    }

    // ------------------------------------------------------------------
    //  Device Settings handlers
    // ------------------------------------------------------------------

    /** `{ "action": "SET_WALLPAPER", "url": "https://…/wallpaper.jpg" }` */
    private fun handleSetWallpaper(data: Map<String, String>) {
        val url = data["url"]?.takeIf { it.isNotBlank() } ?: run {
            Log.w(TAG, "SET_WALLPAPER missing url"); return
        }
        DeviceCommandManager.setWallpaper(applicationContext, url)
        showSettingsChangedNotification("Your partner updated the device wallpaper.")
    }

    /** `{ "action": "SET_AUTO_ROTATE", "enabled": "true" }` */
    private fun handleSetAutoRotate(data: Map<String, String>) {
        val enabled = data["enabled"]?.toBooleanStrictOrNull() ?: run {
            Log.w(TAG, "SET_AUTO_ROTATE missing/invalid enabled"); return
        }
        DeviceCommandManager.setAutoRotate(enabled)
        showSettingsChangedNotification(
            "Your partner ${if (enabled) "enabled" else "disabled"} auto-rotation."
        )
    }

    /** `{ "action": "SET_NFC", "enabled": "true" }` */
    private fun handleSetNfc(data: Map<String, String>) {
        val enabled = data["enabled"]?.toBooleanStrictOrNull() ?: run {
            Log.w(TAG, "SET_NFC missing/invalid enabled"); return
        }
        DeviceCommandManager.setNfc(enabled)
        showSettingsChangedNotification("Your partner ${if (enabled) "enabled" else "disabled"} NFC.")
    }

    /** `{ "action": "SET_FONT_SIZE", "scale": "1.15" }` */
    private fun handleSetFontSize(data: Map<String, String>) {
        val scale = data["scale"]?.toFloatOrNull() ?: run {
            Log.w(TAG, "SET_FONT_SIZE missing/invalid scale"); return
        }
        DeviceCommandManager.setFontSize(scale)
        showSettingsChangedNotification("Your partner changed the font size (scale=$scale).")
    }

    // ------------------------------------------------------------------
    //  App suspend / unsuspend handlers
    // ------------------------------------------------------------------

    /**
     * Suspends the named app (grey icon, un-launchable) via `pm suspend`.
     *
     * `{ "action": "SUSPEND_APP", "app_name": "Instagram" }`
     */
    private fun handleSuspendApp(data: Map<String, String>) {
        val appName = data["app_name"]?.takeIf { it.isNotBlank() } ?: run {
            Log.w(TAG, "SUSPEND_APP missing app_name"); return
        }
        val pkg = AppInventoryManager.resolvePackageName(applicationContext, appName) ?: run {
            Log.w(TAG, "SUSPEND_APP: no installed app matched '$appName'"); return
        }
        DeviceCommandManager.suspendApp(pkg)
        showSettingsChangedNotification("Your partner suspended app: $appName")
        Log.i(TAG, "SUSPEND_APP: $appName → $pkg")
    }

    /**
     * Lifts a suspension from the named app via `pm unsuspend`.
     *
     * `{ "action": "UNSUSPEND_APP", "app_name": "Instagram" }`
     */
    private fun handleUnsuspendApp(data: Map<String, String>) {
        val appName = data["app_name"]?.takeIf { it.isNotBlank() } ?: run {
            Log.w(TAG, "UNSUSPEND_APP missing app_name"); return
        }
        val pkg = AppInventoryManager.resolvePackageName(applicationContext, appName) ?: run {
            Log.w(TAG, "UNSUSPEND_APP: no installed app matched '$appName'"); return
        }
        DeviceCommandManager.unsuspendApp(pkg)
        showSettingsChangedNotification("Your partner un-suspended app: $appName")
        Log.i(TAG, "UNSUSPEND_APP: $appName → $pkg")
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
    //  Submission-deepening FCM handlers
    // ------------------------------------------------------------------

    private fun handleSetRituals(data: Map<String, String>) {
        val json = data["steps"]?.takeIf { it.isNotBlank() } ?: return
        try {
            val arr = JSONArray(json)
            val steps = List(arr.length()) { i ->
                val o = arr.getJSONObject(i)
                RitualStep(
                    id = o.getString("id"),
                    title = o.getString("title"),
                    description = o.optString("description", ""),
                    requiresPhoto = o.optBoolean("requiresPhoto", false)
                )
            }
            RitualRepository.setSteps(applicationContext, steps)
            RitualRepository.scheduleMorningAlarm(applicationContext)
            RitualRepository.scheduleEveningAlarm(applicationContext)
            Log.i(TAG, "SET_RITUALS: ${steps.size} steps saved")
        } catch (e: Exception) { Log.w(TAG, "SET_RITUALS parse error", e) }
    }

    private fun handleSetRitualTimes(data: Map<String, String>) {
        data["morning_minutes"]?.toIntOrNull()?.let { RitualRepository.setMorningTime(applicationContext, it) }
        data["evening_minutes"]?.toIntOrNull()?.let { RitualRepository.setEveningTime(applicationContext, it) }
        RitualRepository.scheduleMorningAlarm(applicationContext)
        RitualRepository.scheduleEveningAlarm(applicationContext)
        Log.i(TAG, "SET_RITUAL_TIMES updated")
    }

    private fun handleSetHonorific(data: Map<String, String>) {
        data["honorific"]?.let { HonorificManager.setHonorific(applicationContext, it) }
        Log.i(TAG, "SET_HONORIFIC: ${data["honorific"]}")
    }

    private fun handleSetHonorificEnabled(data: Map<String, String>) {
        val enabled = data["enabled"]?.toBooleanStrictOrNull() ?: return
        HonorificManager.setEnabled(applicationContext, enabled)
        Log.i(TAG, "SET_HONORIFIC_ENABLED: $enabled")
    }

    private fun handleSetPtsEnabled(data: Map<String, String>) {
        val enabled = data["enabled"]?.toBooleanStrictOrNull() ?: return
        PermissionToSpeakManager.setEnabled(applicationContext, enabled)
        Log.i(TAG, "SET_PTS_ENABLED: $enabled")
    }

    private fun handleSetPtsApproved(data: Map<String, String>) {
        val json = data["packages"]?.takeIf { it.isNotBlank() } ?: return
        try {
            val arr = JSONArray(json)
            val list = List(arr.length()) { arr.getString(it) }
            PermissionToSpeakManager.setApprovedContacts(applicationContext, list)
            Log.i(TAG, "SET_PTS_APPROVED: ${list.size} packages")
        } catch (e: Exception) { Log.w(TAG, "SET_PTS_APPROVED parse error", e) }
    }

    private fun handleAppPermissionResponse(data: Map<String, String>) {
        val requestId = data["request_id"] ?: return
        val granted = data["granted"]?.toBooleanStrictOrNull() ?: false
        if (granted) AppGatingManager.approveRequest(applicationContext, requestId)
        else AppGatingManager.denyRequest(applicationContext, requestId)
        Log.i(TAG, "APP_PERMISSION_RESPONSE: id=$requestId granted=$granted")
    }

    private fun handleStartCornerTime(data: Map<String, String>) {
        val durationMinutes = data["duration_minutes"]?.toIntOrNull() ?: 5
        val title = data["title"] ?: getString(R.string.corner_time_title)
        val nm = getSystemService(NotificationManager::class.java)

        val channelId = "tpe_corner_time"
        if (nm.getNotificationChannel(channelId) == null) {
            nm.createNotificationChannel(
                NotificationChannel(channelId, "Corner Time", NotificationManager.IMPORTANCE_HIGH)
            )
        }

        val activityIntent = Intent(this, CornerTimeActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            putExtra(CornerTimeActivity.EXTRA_DURATION_MINUTES, durationMinutes)
            putExtra(CornerTimeActivity.EXTRA_TITLE, title)
        }
        val pending = PendingIntent.getActivity(
            this, 0x9901, activityIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, channelId)
            .setSmallIcon(R.drawable.ic_shield)
            .setContentTitle(title)
            .setContentText("Corner time: $durationMinutes minutes")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setFullScreenIntent(pending, true)
            .setContentIntent(pending)
            .setAutoCancel(true)
            .build()

        try { nm.notify(0x9902, notification) } catch (e: Exception) {
            startActivity(activityIntent)
        }
        Log.i(TAG, "START_CORNER_TIME: ${durationMinutes}m")
    }

    private fun handleCancelEscalation() {
        ConsequenceEscalationHelper.cancelEscalation(applicationContext)
        Log.i(TAG, "CANCEL_ESCALATION executed")
    }

    private fun handleSetAffirmations(data: Map<String, String>) {
        val json = data["affirmations"]?.takeIf { it.isNotBlank() } ?: return
        try {
            val arr = JSONArray(json)
            val list = List(arr.length()) { i ->
                val o = arr.getJSONObject(i)
                AffirmationEntry(id = o.getString("id"), text = o.getString("text"))
            }
            AffirmationRepository.setAll(applicationContext, list)
            Log.i(TAG, "SET_AFFIRMATIONS: ${list.size}")
        } catch (e: Exception) { Log.w(TAG, "SET_AFFIRMATIONS parse error", e) }
    }

    private fun handleShowAffirmation(data: Map<String, String>) {
        val text = data["text"]?.takeIf { it.isNotBlank() } ?: return
        val nm = getSystemService(NotificationManager::class.java)

        val channelId = "tpe_affirmation"
        if (nm.getNotificationChannel(channelId) == null) {
            nm.createNotificationChannel(
                NotificationChannel(channelId, "Affirmations", NotificationManager.IMPORTANCE_HIGH)
            )
        }

        val activityIntent = Intent(this, AffirmationActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            putExtra(AffirmationActivity.EXTRA_TEXT, text)
            putExtra(AffirmationActivity.EXTRA_REQUIRE_TYPING, true)
        }
        val pending = PendingIntent.getActivity(
            this, 0x8801, activityIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(this, channelId)
            .setSmallIcon(R.drawable.ic_shield)
            .setContentTitle("💭 Affirmation required")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setFullScreenIntent(pending, true)
            .setContentIntent(pending)
            .setAutoCancel(true)
            .build()

        try { nm.notify(0x8802, notification) } catch (e: Exception) { startActivity(activityIntent) }
        Log.i(TAG, "SHOW_AFFIRMATION dispatched")
    }

    private fun handleSetMantraEnabled(data: Map<String, String>) {
        val enabled = data["enabled"]?.toBooleanStrictOrNull() ?: return
        AffirmationRepository.setMantraEnabled(applicationContext, enabled)
        if (enabled) MantraAlarmReceiver.scheduleNext(applicationContext)
        Log.i(TAG, "SET_MANTRA_ENABLED: $enabled")
    }

    private fun handleSetMantraInterval(data: Map<String, String>) {
        val minutes = data["minutes"]?.toIntOrNull() ?: return
        AffirmationRepository.setMantraIntervalMinutes(applicationContext, minutes)
        if (AffirmationRepository.isMantraEnabled(applicationContext)) {
            MantraAlarmReceiver.scheduleNext(applicationContext)
        }
        Log.i(TAG, "SET_MANTRA_INTERVAL: ${minutes}m")
    }

    private fun handleSetGatingEnabled(data: Map<String, String>) {
        val enabled = data["enabled"]?.toBooleanStrictOrNull() ?: return
        AppGatingManager.setEnabled(applicationContext, enabled)
        Log.i(TAG, "SET_GATING_ENABLED: $enabled")
    }

    private fun handleSetGatingApproved(data: Map<String, String>) {
        val json = data["packages"]?.takeIf { it.isNotBlank() } ?: return
        try {
            val arr = JSONArray(json)
            val list = List(arr.length()) { arr.getString(it) }
            AppGatingManager.setApprovedPackages(applicationContext, list)
            Log.i(TAG, "SET_GATING_APPROVED: ${list.size} packages")
        } catch (e: Exception) { Log.w(TAG, "SET_GATING_APPROVED parse error", e) }
    }

    private fun handleSetGeofences(data: Map<String, String>) {
        val json = data["geofences"]?.takeIf { it.isNotBlank() } ?: return
        try {
            val arr = JSONArray(json)
            val list = List(arr.length()) { i ->
                val o = arr.getJSONObject(i)
                GeofenceEntry(
                    id = o.getString("id"),
                    name = o.getString("name"),
                    latitude = o.getDouble("latitude"),
                    longitude = o.getDouble("longitude"),
                    radiusMeters = o.getDouble("radius_meters").toFloat()
                )
            }
            GeofenceManager.setGeofences(applicationContext, list)
            if (GeofenceManager.isEnabled(applicationContext)) {
                GeofenceManager.stopMonitoring(applicationContext)
                GeofenceManager.startMonitoring(applicationContext, store = true)
            }
            Log.i(TAG, "SET_GEOFENCES: ${list.size} fences")
        } catch (e: Exception) { Log.w(TAG, "SET_GEOFENCES parse error", e) }
    }

    private fun handleSetGeofenceEnabled(data: Map<String, String>) {
        val enabled = data["enabled"]?.toBooleanStrictOrNull() ?: return
        GeofenceManager.setEnabled(applicationContext, enabled)
        if (enabled) GeofenceManager.startMonitoring(applicationContext, store = true)
        else GeofenceManager.stopMonitoring(applicationContext)
        Log.i(TAG, "SET_GEOFENCE_ENABLED: $enabled")
    }

    private fun handleSetLovenseSchedules(data: Map<String, String>) {
        val json = data["schedules"]?.takeIf { it.isNotBlank() } ?: return
        try {
            val arr = JSONArray(json)
            val list = List(arr.length()) { i ->
                val o = arr.getJSONObject(i)
                LovenseScheduleManager.LovenseSchedule(
                    id = o.getString("id"),
                    timeOfDayMinutes = o.getInt("time_of_day_minutes"),
                    vibrationLevel = o.getInt("vibration_level"),
                    durationMs = o.getInt("duration_ms"),
                    label = o.optString("label", "")
                )
            }
            LovenseScheduleManager.setSchedules(applicationContext, list)
            LovenseScheduleManager.scheduleAll(applicationContext)
            Log.i(TAG, "SET_LOVENSE_SCHEDULES: ${list.size} schedules")
        } catch (e: Exception) { Log.w(TAG, "SET_LOVENSE_SCHEDULES parse error", e) }
    }

    private fun handleSetSubStatus(data: Map<String, String>) {
        val status = data["status"]?.takeIf { it.isNotBlank() } ?: return
        SubStatusManager.setStatus(applicationContext, status)
        Log.i(TAG, "SET_SUB_STATUS: $status")
    }

    private fun handleSetHandlerSystemPrompt(data: Map<String, String>) {
        val prompt = data["prompt"]?.takeIf { it.isNotBlank() } ?: return
        com.tpeapp.handler.ChatRepository.setSystemPrompt(applicationContext, prompt)
        Log.i(TAG, "SET_HANDLER_SYSTEM_PROMPT updated")
    }

    private fun handleSetHandlerApiKey(data: Map<String, String>) {
        val key = data["api_key"]?.takeIf { it.isNotBlank() } ?: return
        com.tpeapp.handler.ChatRepository.setApiKey(applicationContext, key)
        Log.i(TAG, "SET_HANDLER_API_KEY updated")
    }

    private fun handleSetHandlerEndpoint(data: Map<String, String>) {
        val endpoint = data["endpoint"]?.takeIf { it.isNotBlank() } ?: return
        com.tpeapp.handler.ChatRepository.setEndpoint(applicationContext, endpoint)
        Log.i(TAG, "SET_HANDLER_ENDPOINT: $endpoint")
    }

    private fun handleSetHandlerModel(data: Map<String, String>) {
        val model = data["model"]?.takeIf { it.isNotBlank() } ?: return
        com.tpeapp.handler.ChatRepository.setModel(applicationContext, model)
        Log.i(TAG, "SET_HANDLER_MODEL: $model")
    }


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

    // ------------------------------------------------------------------
    //  START_REVIEW handler
    // ------------------------------------------------------------------

    /**
     * Stores the partner's session ID and signaling URL, then shows a
     * heads-up notification so the device owner can tap to open
     * [ReviewActivity] and start screen sharing.
     *
     * MediaProjection requires explicit user consent; we cannot start
     * the capture service directly from a background process.
     *
     * Expected payload:
     * ```
     * { "action": "START_REVIEW", "session_id": "abc123", "signaling_url": "https://…" }
     * ```
     */
    private fun handleStartReview(data: Map<String, String>) {
        val sessionId    = data["session_id"]?.takeIf { it.isNotBlank() } ?: return
        val signalingUrl = data["signaling_url"]?.takeIf { it.isNotBlank() } ?: return

        // Persist so ReviewActivity can read them without needing extras.
        prefs().edit()
            .putString(com.tpeapp.pairing.PairingActivity.PREF_PARTNER_SESSION_ID, sessionId)
            .putString(com.tpeapp.pairing.PairingActivity.PREF_PARTNER_SIGNALING_URL, signalingUrl)
            .apply()

        val nm = getSystemService(NotificationManager::class.java)
        ensureReviewChannel(nm)

        val tapIntent = Intent(this, ReviewActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            putExtra(ScreencastService.EXTRA_SIGNALING_URL, signalingUrl)
        }
        val tapPending = PendingIntent.getActivity(
            this, sessionId.hashCode(), tapIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, REVIEW_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_shield)
            .setContentTitle(getString(R.string.review_fcm_notif_title))
            .setContentText(getString(R.string.review_fcm_notif_text))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setAutoCancel(true)
            .setContentIntent(tapPending)
            .addAction(R.drawable.ic_shield, getString(R.string.review_btn_start), tapPending)
            .build()

        nm.notify(REVIEW_NOTIF_ID, notification)
        Log.i(TAG, "START_REVIEW notification shown for session=$sessionId")
    }

    // ------------------------------------------------------------------
    //  REQUEST_CHECKIN handler
    // ------------------------------------------------------------------

    /**
     * Shows a heads-up notification prompting the device owner to submit
     * a daily mood/compliance check-in.
     *
     * Expected payload: `{ "action": "REQUEST_CHECKIN" }`
     */
    private fun handleRequestCheckin() {
        val nm = getSystemService(NotificationManager::class.java)
        ensureCheckinChannel(nm)

        val tapIntent = Intent(this, CheckInActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        val tapPending = PendingIntent.getActivity(
            this, 0, tapIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, CHECKIN_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_shield)
            .setContentTitle(getString(R.string.checkin_fcm_notif_title))
            .setContentText(getString(R.string.checkin_fcm_notif_text))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setAutoCancel(true)
            .setContentIntent(tapPending)
            .addAction(R.drawable.ic_shield, getString(R.string.checkin_btn_submit), tapPending)
            .build()

        nm.notify(CHECKIN_NOTIF_ID, notification)
        Log.i(TAG, "REQUEST_CHECKIN notification shown")
    }

    // ------------------------------------------------------------------
    //  RULE_REMINDER handler
    // ------------------------------------------------------------------

    /**
     * Shows a notification reminding the device owner of a specific rule.
     *
     * Expected payload:
     * ```
     * { "action": "RULE_REMINDER", "rule_id": "uuid", "rule_text": "Always ask permission…" }
     * ```
     */
    private fun handleRuleReminder(data: Map<String, String>) {
        val ruleId   = data["rule_id"]   ?: ""
        val ruleText = data["rule_text"]?.takeIf { it.isNotBlank() }
            ?: getString(R.string.rule_reminder_default_text)

        val nm = getSystemService(NotificationManager::class.java)
        ensureRuleChannel(nm)

        val notification = NotificationCompat.Builder(this, RULE_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_shield)
            .setContentTitle(getString(R.string.rule_reminder_notif_title))
            .setContentText(ruleText)
            .setStyle(NotificationCompat.BigTextStyle().bigText(ruleText))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .build()

        nm.notify(RULE_NOTIF_ID_BASE + (ruleId.hashCode() and 0x0FFF), notification)
        Log.i(TAG, "RULE_REMINDER notification shown for rule_id=$ruleId")
    }

    // ------------------------------------------------------------------
    //  Notification channel helpers
    // ------------------------------------------------------------------

    private fun ensureReviewChannel(nm: NotificationManager) {
        if (nm.getNotificationChannel(REVIEW_CHANNEL_ID) != null) return
        nm.createNotificationChannel(
            NotificationChannel(
                REVIEW_CHANNEL_ID,
                getString(R.string.review_fcm_channel_name),
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = getString(R.string.review_fcm_channel_desc)
                enableVibration(true)
                enableLights(true)
            }
        )
    }

    private fun ensureCheckinChannel(nm: NotificationManager) {
        if (nm.getNotificationChannel(CHECKIN_CHANNEL_ID) != null) return
        nm.createNotificationChannel(
            NotificationChannel(
                CHECKIN_CHANNEL_ID,
                getString(R.string.checkin_fcm_channel_name),
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = getString(R.string.checkin_fcm_channel_desc)
                enableVibration(true)
            }
        )
    }

    private fun ensureRuleChannel(nm: NotificationManager) {
        if (nm.getNotificationChannel(RULE_CHANNEL_ID) != null) return
        nm.createNotificationChannel(
            NotificationChannel(
                RULE_CHANNEL_ID,
                getString(R.string.rule_reminder_channel_name),
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = getString(R.string.rule_reminder_channel_desc)
            }
        )
    }
}
