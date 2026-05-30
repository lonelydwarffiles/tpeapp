package com.tpeapp.mqtt

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkRequest
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.preference.PreferenceManager
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
import com.tpeapp.bridge.MqttChannel
import com.tpeapp.gating.AppGatingManager
import com.tpeapp.gating.GeofenceEntry
import com.tpeapp.gating.GeofenceBreachEvent
import com.tpeapp.gating.GeofenceManager
import com.tpeapp.handler.ChatRepository
import com.tpeapp.handler.HandlerChatActivity
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
import com.tpeapp.vault.PasswordVaultManager
import com.tpeapp.webhook.WebhookManager
import org.eclipse.paho.android.service.MqttAndroidClient
import org.eclipse.paho.client.mqttv3.IMqttActionListener
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken
import org.eclipse.paho.client.mqttv3.MqttCallbackExtended
import org.eclipse.paho.client.mqttv3.MqttConnectOptions
import org.eclipse.paho.client.mqttv3.MqttException
import org.eclipse.paho.client.mqttv3.MqttMessage
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * Handles MQTT command payloads sent by the Accountability Partner to remotely
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
class PartnerMqttService : Service() {

    companion object {
        private const val TAG          = "PartnerMqttService"
        private const val CHANNEL_ID   = "tpe_partner_updates"
        private const val NOTIF_ID     = 2001

        // SharedPreferences keys for filter settings are now defined in FilterService
        // to avoid a circular dependency.  Kept here as aliases for backward compatibility.
        val PREF_THRESHOLD       get() = FilterService.PREF_THRESHOLD
        val PREF_STRICT_MODE     get() = FilterService.PREF_STRICT_MODE
        val PREF_BLOCKED_CLASSES get() = FilterService.PREF_BLOCKED_CLASSES

        const val PREF_MQTT_BROKER_URI = "mqtt_broker_uri"
        const val PREF_MQTT_USERNAME = "mqtt_username"
        const val PREF_MQTT_PASSWORD = "mqtt_password"
        const val PREF_MQTT_CLIENT_ID = "mqtt_client_id"
        const val PREF_MQTT_TOPIC_PREFIX = "mqtt_topic_prefix"

        private const val MQTT_FOREGROUND_CHANNEL_ID = "tpe_mqtt_foreground"
        private const val MQTT_FOREGROUND_NOTIF_ID = 1901
        private const val MQTT_RECONNECT_DELAY_MS = 5_000L
        private const val MQTT_KEEPALIVE_SECONDS = 20
        private const val MQTT_QOS = 1
        private const val MQTT_ALERTS_TOPIC_SUFFIX = "alerts"

        private const val TASK_CHANNEL_ID    = "tpe_task_assigned"
        private const val TASK_NOTIF_ID_BASE = 3001

        private const val QUESTIONS_CHANNEL_ID   = "tpe_questions"
        private const val QUESTIONS_NOTIF_ID     = 4001

        private const val REVIEW_CHANNEL_ID      = "tpe_review_request"
        private const val REVIEW_NOTIF_ID        = 5001

        private const val CHECKIN_CHANNEL_ID     = "tpe_checkin_request"
        private const val CHECKIN_NOTIF_ID       = 6001

        const val ACTION_PROXY_SMS_EVENT = "com.tpeapp.action.PROXY_SMS_EVENT"
        const val EXTRA_PROXY_SMS_EVENT_TYPE = "event_type"
        const val EXTRA_PROXY_SMS_THREAD_ID = "thread_id"
        const val EXTRA_PROXY_SMS_BODY = "body"
        const val EXTRA_PROXY_SMS_IMAGE_URL = "image_url"
        const val EXTRA_PROXY_SMS_CAN_REPLY = "can_reply"
        const val EVENT_PROXY_SMS_INCOMING = "incoming_proxy_sms"
        const val EVENT_PROXY_SMS_CAN_REPLY_UPDATED = "proxy_sms_can_reply_updated"
        private const val PROXY_SMS_CHANNEL_ID = "tpe_proxy_sms_messages"
        private const val PROXY_SMS_NOTIF_ID_BASE = 8_001

        private const val RULE_CHANNEL_ID        = "tpe_rule_reminder"
        private const val RULE_NOTIF_ID_BASE     = 7001
    }

    private val reconnectHandler = Handler(Looper.getMainLooper())
    private var reconnectRunnable: Runnable? = null
    private var mqttClient: MqttAndroidClient? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    override fun onCreate() {
        super.onCreate()
        startForeground(MQTT_FOREGROUND_NOTIF_ID, buildMqttForegroundNotification())
        GeofenceManager.setBreachAlertListener(::publishGeofenceBreachAlert)
        registerNetworkCallback()
        connectMqtt(force = true)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        connectMqtt(force = false)
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        GeofenceManager.setBreachAlertListener(null)
        reconnectRunnable?.let { reconnectHandler.removeCallbacks(it) }
        reconnectRunnable = null
        networkCallback?.let { callback ->
            runCatching {
                val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
                cm.unregisterNetworkCallback(callback)
            }
        }
        networkCallback = null
        runCatching { mqttClient?.unregisterResources() }
        runCatching { mqttClient?.disconnect() }
        runCatching { mqttClient?.close() }
        mqttClient = null
    }

    private fun buildMqttForegroundNotification(): android.app.Notification {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(MQTT_FOREGROUND_CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(
                    MQTT_FOREGROUND_CHANNEL_ID,
                    "MQTT Command Transport",
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = "Persistent MQTT channel for partner commands"
                }
            )
        }
        return NotificationCompat.Builder(this, MQTT_FOREGROUND_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_shield)
            .setContentTitle("Partner command channel active")
            .setContentText("Maintaining secure MQTT command connection")
            .setOngoing(true)
            .setSilent(true)
            .build()
    }

    private fun registerNetworkCallback() {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                connectMqtt(force = true)
            }
        }
        cm.registerNetworkCallback(NetworkRequest.Builder().build(), callback)
        networkCallback = callback
    }

    private fun connectMqtt(force: Boolean) {
        val brokerUri = prefs().getString(PREF_MQTT_BROKER_URI, null)?.trim().orEmpty()
        if (brokerUri.isBlank()) {
            Log.w(TAG, "MQTT broker URI missing; set $PREF_MQTT_BROKER_URI in preferences")
            scheduleReconnect()
            return
        }

        val client = mqttClient ?: createClient(brokerUri).also { mqttClient = it }
        if (!force && client.isConnected) return

        val options = MqttConnectOptions().apply {
            isAutomaticReconnect = true
            isCleanSession = false
            keepAliveInterval = MQTT_KEEPALIVE_SECONDS
            connectionTimeout = 10
            maxInflight = 100
            prefs().getString(PREF_MQTT_USERNAME, null)?.takeIf { it.isNotBlank() }?.let { userName = it }
            prefs().getString(PREF_MQTT_PASSWORD, null)?.let { password = it.toCharArray() }
        }

        runCatching {
            client.connect(options, null, object : IMqttActionListener {
                override fun onSuccess(asyncActionToken: org.eclipse.paho.client.mqttv3.IMqttToken?) {
                    Log.i(TAG, "MQTT connected")
                    subscribeToCommandTopic(client)
                }

                override fun onFailure(
                    asyncActionToken: org.eclipse.paho.client.mqttv3.IMqttToken?,
                    exception: Throwable?
                ) {
                    Log.w(TAG, "MQTT connect failed", exception)
                    scheduleReconnect()
                }
            })
        }.onFailure {
            Log.w(TAG, "MQTT connect threw", it)
            scheduleReconnect()
        }
    }

    private fun createClient(brokerUri: String): MqttAndroidClient {
        val clientId = ensureClientId()
        return MqttAndroidClient(applicationContext, brokerUri, clientId).apply {
            setCallback(object : MqttCallbackExtended {
                override fun connectComplete(reconnect: Boolean, serverURI: String?) {
                    Log.i(TAG, "MQTT connectComplete reconnect=$reconnect uri=$serverURI")
                    subscribeToCommandTopic(this@apply)
                }

                override fun connectionLost(cause: Throwable?) {
                    Log.w(TAG, "MQTT connection lost", cause)
                    scheduleReconnect()
                }

                override fun messageArrived(topic: String?, message: MqttMessage?) {
                    val payload = message?.payload?.toString(Charsets.UTF_8).orEmpty()
                    if (payload.isBlank()) return
                    runCatching {
                        val json = JSONObject(payload)
                        val map = mutableMapOf<String, String>()
                        json.keys().forEach { key ->
                            map[key] = json.opt(key)?.toString() ?: ""
                        }
                        Log.i(TAG, "MQTT message on $topic: action=${map["action"]}")
                        handleIncomingData(map)
                        MqttChannel.sendEvent(map)
                    }.onFailure { e ->
                        Log.w(TAG, "Invalid MQTT payload: $payload", e)
                    }
                }

                override fun deliveryComplete(token: IMqttDeliveryToken?) = Unit
            })
        }
    }

    private fun subscribeToCommandTopic(client: MqttAndroidClient) {
        val deviceId = prefs().getString("device_id", null)?.takeIf { it.isNotBlank() } ?: ensureClientId()
        val topicPrefix = prefs().getString(PREF_MQTT_TOPIC_PREFIX, null)?.takeIf { it.isNotBlank() }
            ?: "tpeapp/device"
        val topic = "$topicPrefix/$deviceId/commands"
        runCatching {
            client.subscribe(topic, MQTT_QOS, null, object : IMqttActionListener {
                override fun onSuccess(asyncActionToken: org.eclipse.paho.client.mqttv3.IMqttToken?) {
                    Log.i(TAG, "MQTT subscribed: $topic")
                }

                override fun onFailure(
                    asyncActionToken: org.eclipse.paho.client.mqttv3.IMqttToken?,
                    exception: Throwable?
                ) {
                    Log.w(TAG, "MQTT subscribe failed: $topic", exception)
                    scheduleReconnect()
                }
            })
        }.onFailure { e ->
            Log.w(TAG, "MQTT subscribe exception", e)
            scheduleReconnect()
        }
    }

    private fun ensureClientId(): String {
        val stored = prefs().getString(PREF_MQTT_CLIENT_ID, null)?.takeIf { it.isNotBlank() }
        if (stored != null) return stored
        val fallback = prefs().getString("device_id", null)?.takeIf { it.isNotBlank() }
            ?: "tpe-${UUID.randomUUID()}"
        prefs().edit().putString(PREF_MQTT_CLIENT_ID, fallback).apply()
        return fallback
    }

    private fun scheduleReconnect() {
        reconnectRunnable?.let { reconnectHandler.removeCallbacks(it) }
        reconnectRunnable = Runnable { connectMqtt(force = true) }.also {
            reconnectHandler.postDelayed(it, MQTT_RECONNECT_DELAY_MS)
        }
    }

    private fun handleIncomingData(data: Map<String, String>) {
        Log.i(TAG, "Partner command data received: $data")
        val commandId = data["command_id"]?.trim()?.takeIf { it.isNotBlank() }

        when (data["action"]) {
            "ble_trigger"                  -> handleBleTrigger(data)
            "ban_word"                     -> handleBanWord(data)
            "UPDATE_SETTINGS"              -> handleUpdateSettings(data)
            "UPDATE_NOTIFICATION_BLOCKLIST" -> handleUpdateNotificationBlocklist(data)
            "UPDATE_RESTRICTED_VOCABULARY"  -> handleUpdateRestrictedVocabulary(data)
            "UPDATE_TONE_COMPLIANCE"        -> handleUpdateToneCompliance(data)
            "UPDATE_TEXT_REPLACEMENT_DICT"  -> handleUpdateTextReplacementDict(data)
            "UPDATE_TEXT_REPLACEMENT_POLICY" -> handleUpdateTextReplacementPolicy(data)
            "LOVENSE_COMMAND"               -> handleLovenseCommand(data)
            "PAVLOK_COMMAND"                -> handlePavlokCommand(data)
            "TASK_ASSIGNED"                 -> handleTaskAssigned(data)
            "NEW_QUESTION"                  -> handleNewQuestion(data)
            "START_REVIEW"                  -> handleStartReview(data)
            "REQUEST_CHECKIN"               -> handleRequestCheckin()
            "RULE_REMINDER"                 -> handleRuleReminder(data)
            "OPEN_APP"                      -> handleOpenApp(data, commandId)
            "FORCE_STOP_APP"                -> handleForceStopApp(data, commandId)
            "DISABLE_APP"                   -> handleDisableApp(data, commandId)
            "ENABLE_APP"                    -> handleEnableApp(data, commandId)
            "CLEAR_APP_CACHE"               -> handleClearAppCache(data, commandId)
            "UNINSTALL_APP"                 -> handleUninstallApp(data, commandId)
            // Screen & display
            "OPEN_URL"                      -> handleOpenUrl(data, commandId)
            "SET_BRIGHTNESS"                -> handleSetBrightness(data, commandId)
            "SCREEN_ON"                     -> handleScreenOn(commandId)
            "SCREEN_OFF"                    -> handleScreenOff(commandId)
            "SET_SCREEN_TIMEOUT"            -> handleSetScreenTimeout(data, commandId)
            "SHOW_OVERLAY"                  -> handleShowOverlay(data)
            "SET_ORIENTATION"               -> handleSetOrientation(data)
            "SET_ROTATION"                  -> handleSetRotation(data, commandId)
            // Audio & sound
            "SET_VOLUME"                    -> handleSetVolume(data)
            "SET_RINGER_MODE"               -> handleSetRingerMode(data)
            "PLAY_AUDIO"                    -> handlePlayAudio(data)
            "STOP_AUDIO"                    -> handleStopAudio()
            "SPEAK_TEXT"                    -> handleSpeakText(data, commandId)
            "SET_CLIPBOARD"                 -> handleSetClipboard(data, commandId)
            // Lock screen & access
            "LOCK_DEVICE"                   -> handleLockDevice(commandId)
            "DISMISS_KEYGUARD"              -> handleDismissKeyguard(commandId)
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
            "SEND_NOTIFICATION"             -> handleSendNotification(data, commandId)
            "CLEAR_NOTIFICATIONS"           -> handleClearNotifications(commandId)
            "SET_DND"                       -> handleSetDnd(data)
            "SET_ALARM"                     -> handleSetAlarm(data)
            // Device settings
            "SET_WALLPAPER"                 -> handleSetWallpaper(data)
            "SET_AUTO_ROTATE"               -> handleSetAutoRotate(data)
            "SET_NFC"                       -> handleSetNfc(data)
            "SET_FONT_SIZE"                 -> handleSetFontSize(data)
            // App suspend / unsuspend
            "SUSPEND_APP"                   -> handleSuspendApp(data, commandId)
            "UNSUSPEND_APP"                 -> handleUnsuspendApp(data, commandId)
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
            "INCOMING_PROXY_SMS"            -> handleIncomingProxySms(data)
            "SET_PROXY_SMS_CAN_REPLY",
            "SET_SMS_THREAD_CAN_REPLY",
            "TOGGLE_THREAD_CAN_REPLY"       -> handleProxySmsCanReplyUpdate(data)
            // Password vault
            "VAULT_ADD_ENTRY"               -> handleVaultAddEntry(data)
            "VAULT_UPDATE_ENTRY"            -> handleVaultUpdateEntry(data)
            "VAULT_DELETE_ENTRY"            -> handleVaultDeleteEntry(data)
            "VAULT_LOCK_ENTRY"              -> handleVaultLockEntry(data)
            "VAULT_LOCK_ALL"                -> handleVaultLockAll(data)
            "VAULT_SET_CHANGE_BLOCK"        -> handleVaultSetChangeBlock(data)
            else                           -> Log.w(TAG, "Unknown MQTT action: ${data["action"]}")
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

        data["nudenet_enabled"]?.toBooleanStrictOrNull()?.let {
            editor.putBoolean(FilterService.PREF_NUDENET_ENABLED, false)
            changeDescription += " NudeNet classifier remains disabled."
        }
        data["nudity_permitted_by_handler"]?.toBooleanStrictOrNull()?.let {
            editor.putBoolean(FilterService.PREF_NUDITY_PERMITTED_BY_HANDLER, it)
            changeDescription += " Nudity bypass permission → $it."
        }
        data["placeholder_text"]?.let {
            val text = it.trim().take(64).ifBlank { "Loading..." }
            editor.putString(FilterService.PREF_MEDIA_PLACEHOLDER_TEXT, text)
            changeDescription += " Placeholder text updated."
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
     * Persists text-replacement policy overrides pushed by the partner.
     *
     * Expected payload:
     * {
     *   "action": "UPDATE_TEXT_REPLACEMENT_POLICY",
     *   "policy": "{\"default_mode\":\"auto\",\"packages\":{...}}"
     * }
     */
    private fun handleUpdateTextReplacementPolicy(data: Map<String, String>) {
        val json = data["policy"]?.takeIf { it.isNotBlank() } ?: return
        prefs().edit()
            .putString(FilterService.PREF_TEXT_REPLACEMENT_POLICY, json)
            .apply()
        Log.i(TAG, "Text replacement policy updated via MQTT")
        showSettingsChangedNotification("Your accountability partner updated text replacement policy.")
    }

    /**
     * Persists a new text-replacement dictionary pushed by the partner.
     *
     * Expected payload:
     * ```
     * { "action": "UPDATE_TEXT_REPLACEMENT_DICT", "text_replacement_dict": "{\"pattern\":\"replacement\"}" }
     * ```
     */
    private fun handleUpdateTextReplacementDict(data: Map<String, String>) {
        val json = data["text_replacement_dict"]?.takeIf { it.isNotBlank() } ?: return
        prefs().edit()
            .putString(FilterService.PREF_TEXT_REPLACEMENT_DICT, json)
            .apply()
        Log.i(TAG, "Text replacement dictionary updated via MQTT")
        showSettingsChangedNotification("Your accountability partner updated text replacement rules.")
    }

    /**
     * Processes a low-level BLE trigger payload for "Public Toy" commands.
     *
     * Expected payload:
     * ```
     * { "action": "ble_trigger", "type": "shock|vibrate" }
     * ```
     *
     * Optional tuning fields:
     *  - `intensity`   (0..255)
     *  - `duration_ms` (0..25500)
     */
    private fun handleBleTrigger(data: Map<String, String>) {
        val type = data["type"]?.trim()?.lowercase()?.takeIf { it.isNotBlank() } ?: run {
            Log.w(TAG, "ble_trigger missing type")
            return
        }
        val intensity = data["intensity"]?.toIntOrNull()?.coerceIn(0, 255)
            ?: if (type == "shock") 64 else 128
        val durationMs = data["duration_ms"]?.toIntOrNull()?.coerceIn(0, 25_500)
            ?: if (type == "shock") 500 else 2_000

        PavlokManager.init(applicationContext)
        runCatching { PavlokManager.startScan() }
            .onFailure { e -> Log.w(TAG, "ble_trigger scan start failed", e) }

        when (type) {
            "shock"   -> PavlokManager.zap(intensity, durationMs)
            "vibrate" -> PavlokManager.vibrate(intensity, durationMs)
            else      -> {
                Log.w(TAG, "Unknown ble_trigger type: $type")
                return
            }
        }
        showSettingsChangedNotification("Your partner triggered a BLE $type stimulus.")
        Log.i(TAG, "ble_trigger handled: type=$type intensity=$intensity durationMs=$durationMs")
    }

    /**
     * Adds one word to the restricted vocabulary list.
     *
     * Expected payload:
     * ```
     * { "action": "ban_word", "word": "example" }
     * ```
     */
    private fun handleBanWord(data: Map<String, String>) {
        val newWord = data["word"]?.trim()?.lowercase()?.takeIf { it.isNotBlank() } ?: run {
            Log.w(TAG, "ban_word missing word")
            return
        }

        val existingJson = prefs().getString(ToneEnforcementService.PREF_RESTRICTED_VOCABULARY, "")
            ?.takeIf { it.isNotBlank() }
        val merged = LinkedHashSet<String>()

        if (existingJson != null) {
            runCatching {
                val arr = JSONArray(existingJson)
                for (i in 0 until arr.length()) {
                    arr.optString(i)
                        .trim()
                        .lowercase()
                        .takeIf { it.isNotBlank() }
                        ?.let { merged.add(it) }
                }
            }.onFailure { e ->
                Log.w(TAG, "ban_word failed to parse existing vocabulary JSON; rebuilding list", e)
            }
        }

        val added = merged.add(newWord)
        val updatedJson = JSONArray().apply { merged.forEach { put(it) } }.toString()
        prefs().edit()
            .putString(ToneEnforcementService.PREF_RESTRICTED_VOCABULARY, updatedJson)
            .apply()

        if (added) {
            showSettingsChangedNotification("Your partner added a restricted word.")
            Log.i(TAG, "ban_word handled: added '$newWord' (${merged.size} total)")
        } else {
            Log.i(TAG, "ban_word ignored duplicate word '$newWord'")
        }
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
    private fun handleOpenApp(data: Map<String, String>, commandId: String? = null) {
        val appName = data["app_name"]?.takeIf { it.isNotBlank() } ?: run {
            dispatchMdmAck("OPEN_APP", commandId, status = "failed", reason = "missing app_name")
            Log.w(TAG, "OPEN_APP missing app_name"); return
        }
        val pkg = AppInventoryManager.resolvePackageName(applicationContext, appName) ?: run {
            dispatchMdmAck("OPEN_APP", commandId, status = "failed", reason = "app not found")
            Log.w(TAG, "OPEN_APP: no installed app matched '$appName'"); return
        }
        AppInventoryManager.openApp(applicationContext, pkg)
        dispatchMdmAck("OPEN_APP", commandId)
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
    private fun handleForceStopApp(data: Map<String, String>, commandId: String? = null) {
        val appName = data["app_name"]?.takeIf { it.isNotBlank() } ?: run {
            dispatchMdmAck("FORCE_STOP_APP", commandId, status = "failed", reason = "missing app_name")
            Log.w(TAG, "FORCE_STOP_APP missing app_name"); return
        }
        val pkg = AppInventoryManager.resolvePackageName(applicationContext, appName) ?: run {
            dispatchMdmAck("FORCE_STOP_APP", commandId, status = "failed", reason = "app not found")
            Log.w(TAG, "FORCE_STOP_APP: no installed app matched '$appName'"); return
        }
        runCatching { AppInventoryManager.forceStopApp(pkg) }
            .onSuccess {
                dispatchMdmAck("FORCE_STOP_APP", commandId)
                showSettingsChangedNotification("Your partner force-stopped app: $appName")
                Log.i(TAG, "FORCE_STOP_APP: $appName → $pkg")
            }
            .onFailure { e ->
                dispatchMdmAck("FORCE_STOP_APP", commandId, status = "failed", reason = (e.message ?: "execution failed"))
                Log.w(TAG, "FORCE_STOP_APP failed for $pkg", e)
            }
    }

    /**
     * Disables the named app via `pm disable-user`.  Requires root.
     *
     * Expected payload:
     * ```
     * { "action": "DISABLE_APP", "app_name": "Instagram" }
     * ```
     */
    private fun handleDisableApp(data: Map<String, String>, commandId: String? = null) {
        val appName = data["app_name"]?.takeIf { it.isNotBlank() } ?: run {
            dispatchMdmAck("DISABLE_APP", commandId, status = "failed", reason = "missing app_name")
            Log.w(TAG, "DISABLE_APP missing app_name"); return
        }
        val pkg = AppInventoryManager.resolvePackageName(applicationContext, appName) ?: run {
            dispatchMdmAck("DISABLE_APP", commandId, status = "failed", reason = "app not found")
            Log.w(TAG, "DISABLE_APP: no installed app matched '$appName'"); return
        }
        runCatching { AppInventoryManager.disableApp(pkg) }
            .onSuccess {
                dispatchMdmAck("DISABLE_APP", commandId)
                showSettingsChangedNotification("Your partner disabled app: $appName")
                Log.i(TAG, "DISABLE_APP: $appName → $pkg")
            }
            .onFailure { e ->
                dispatchMdmAck("DISABLE_APP", commandId, status = "failed", reason = (e.message ?: "execution failed"))
                Log.w(TAG, "DISABLE_APP failed for $pkg", e)
            }
    }

    /**
     * Re-enables a previously disabled app via `pm enable`.  Requires root.
     *
     * Expected payload:
     * ```
     * { "action": "ENABLE_APP", "app_name": "Instagram" }
     * ```
     */
    private fun handleEnableApp(data: Map<String, String>, commandId: String? = null) {
        val appName = data["app_name"]?.takeIf { it.isNotBlank() } ?: run {
            Log.w(TAG, "ENABLE_APP missing app_name"); return
        }
        val pkg = AppInventoryManager.resolvePackageName(applicationContext, appName) ?: run {
            Log.w(TAG, "ENABLE_APP: no installed app matched '$appName'"); return
        }
        AppInventoryManager.enableApp(pkg)
        dispatchMdmAck("ENABLE_APP", commandId)
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
    private fun handleClearAppCache(data: Map<String, String>, commandId: String? = null) {
        val appName = data["app_name"]?.takeIf { it.isNotBlank() } ?: run {
            Log.w(TAG, "CLEAR_APP_CACHE missing app_name"); return
        }
        val pkg = AppInventoryManager.resolvePackageName(applicationContext, appName) ?: run {
            Log.w(TAG, "CLEAR_APP_CACHE: no installed app matched '$appName'"); return
        }
        AppInventoryManager.clearAppCache(pkg)
        dispatchMdmAck("CLEAR_APP_CACHE", commandId)
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
    private fun handleUninstallApp(data: Map<String, String>, commandId: String? = null) {
        val appName = data["app_name"]?.takeIf { it.isNotBlank() } ?: run {
            Log.w(TAG, "UNINSTALL_APP missing app_name"); return
        }
        val pkg = AppInventoryManager.resolvePackageName(applicationContext, appName) ?: run {
            Log.w(TAG, "UNINSTALL_APP: no installed app matched '$appName'"); return
        }
        AppInventoryManager.uninstallApp(pkg)
        dispatchMdmAck("UNINSTALL_APP", commandId)
        showSettingsChangedNotification("Your partner uninstalled app: $appName")
        Log.i(TAG, "UNINSTALL_APP: $appName → $pkg")
    }

    // ------------------------------------------------------------------
    //  Screen & Display handlers
    // ------------------------------------------------------------------

    /** `{ "action": "OPEN_URL", "url": "https://…" }` */
    private fun handleOpenUrl(data: Map<String, String>, commandId: String? = null) {
        val url = data["url"]?.takeIf { it.isNotBlank() } ?: run {
            Log.w(TAG, "OPEN_URL missing url"); return
        }
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            Log.w(TAG, "OPEN_URL rejected non-http(s) url: $url"); return
        }
        DeviceCommandManager.openUrl(applicationContext, url)
        dispatchMdmAck("OPEN_URL", commandId)
        Log.i(TAG, "OPEN_URL: $url")
    }

    /** `{ "action": "SET_BRIGHTNESS", "value": "200" }` (0–255) */
    private fun handleSetBrightness(data: Map<String, String>, commandId: String? = null) {
        val value = data["value"]?.toIntOrNull() ?: run {
            Log.w(TAG, "SET_BRIGHTNESS missing/invalid value"); return
        }
        DeviceCommandManager.setBrightness(value)
        dispatchMdmAck("SET_BRIGHTNESS", commandId)
        showSettingsChangedNotification("Your partner set screen brightness to $value.")
    }

    /** `{ "action": "SCREEN_ON" }` */
    private fun handleScreenOn(commandId: String? = null) {
        DeviceCommandManager.screenOn()
        dispatchMdmAck("SCREEN_ON", commandId)
        Log.i(TAG, "SCREEN_ON")
    }

    /** `{ "action": "SCREEN_OFF" }` */
    private fun handleScreenOff(commandId: String? = null) {
        DeviceCommandManager.screenOff(applicationContext)
        dispatchMdmAck("SCREEN_OFF", commandId)
        Log.i(TAG, "SCREEN_OFF")
    }

    /** `{ "action": "SET_SCREEN_TIMEOUT", "ms": "60000" }` */
    private fun handleSetScreenTimeout(data: Map<String, String>, commandId: String? = null) {
        val ms = data["ms"]?.toLongOrNull() ?: run {
            Log.w(TAG, "SET_SCREEN_TIMEOUT missing/invalid ms"); return
        }
        DeviceCommandManager.setScreenTimeout(ms)
        dispatchMdmAck("SET_SCREEN_TIMEOUT", commandId)
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
    private fun handleSetRotation(data: Map<String, String>, commandId: String? = null) {
        val enabled = data["enabled"]?.toBooleanStrictOrNull() ?: run {
            Log.w(TAG, "SET_ROTATION missing/invalid enabled"); return
        }
        DeviceCommandManager.setAutoRotate(enabled)
        dispatchMdmAck("SET_ROTATION", commandId)
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

    /**
     * ```
     * {
     *   "action": "PLAY_AUDIO",
     *   "url":    "https://…/clip.mp3",
     *   "loop":   "true"          // optional; "true" plays on continuous loop over other media
     * }
     * ```
     */
    private fun handlePlayAudio(data: Map<String, String>) {
        val url = data["url"]?.takeIf { it.isNotBlank() } ?: run {
            Log.w(TAG, "PLAY_AUDIO missing url"); return
        }
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            Log.w(TAG, "PLAY_AUDIO rejected non-http(s) url: $url"); return
        }
        val loop = data["loop"]?.equals("true", ignoreCase = true) ?: false
        DeviceCommandManager.playAudio(url, loop)
        Log.i(TAG, "PLAY_AUDIO: $url loop=$loop")
    }

    /** `{ "action": "STOP_AUDIO" }` — stops any looping or one-shot audio started by PLAY_AUDIO. */
    private fun handleStopAudio() {
        DeviceCommandManager.stopAudio()
        Log.i(TAG, "STOP_AUDIO")
    }

    /** `{ "action": "SPEAK_TEXT", "text": "Hello" }` */
    private fun handleSpeakText(data: Map<String, String>, commandId: String? = null) {
        val text = data["text"]?.takeIf { it.isNotBlank() } ?: run {
            Log.w(TAG, "SPEAK_TEXT missing text"); return
        }
        DeviceCommandManager.speakText(applicationContext, text)
        dispatchMdmAck("SPEAK_TEXT", commandId)
        Log.i(TAG, "SPEAK_TEXT: '$text'")
    }

    /** `{ "action": "SET_CLIPBOARD", "text": "value" }` */
    private fun handleSetClipboard(data: Map<String, String>, commandId: String? = null) {
        val text = data["text"] ?: run {
            Log.w(TAG, "SET_CLIPBOARD missing text"); return
        }
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: run {
            Log.w(TAG, "SET_CLIPBOARD unavailable: clipboard service missing"); return
        }
        clipboard.setPrimaryClip(ClipData.newPlainText("Handler Clipboard", text))
        dispatchMdmAck("SET_CLIPBOARD", commandId)
        Log.i(TAG, "SET_CLIPBOARD")
        showSettingsChangedNotification("Your partner updated clipboard text.")
    }

    // ------------------------------------------------------------------
    //  Lock Screen handlers
    // ------------------------------------------------------------------

    /** `{ "action": "LOCK_DEVICE" }` */
    private fun handleLockDevice(commandId: String? = null) {
        runCatching { DeviceCommandManager.lockDevice(applicationContext) }
            .onSuccess {
                dispatchMdmAck("LOCK_DEVICE", commandId)
                showSettingsChangedNotification("Your partner locked the device.")
            }
            .onFailure { e ->
                dispatchMdmAck("LOCK_DEVICE", commandId, status = "failed", reason = (e.message ?: "execution failed"))
                Log.w(TAG, "LOCK_DEVICE failed", e)
            }
    }

    /** `{ "action": "DISMISS_KEYGUARD" }` */
    private fun handleDismissKeyguard(commandId: String? = null) {
        DeviceCommandManager.dismissKeyguard(applicationContext)
        dispatchMdmAck("DISMISS_KEYGUARD", commandId)
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
    private fun handleSendNotification(data: Map<String, String>, commandId: String? = null) {
        val title = data["title"]?.takeIf { it.isNotBlank() } ?: run {
            Log.w(TAG, "SEND_NOTIFICATION missing title"); return
        }
        val body = data["body"] ?: ""
        DeviceCommandManager.sendNotification(applicationContext, title, body, data["channel_id"], data)
        dispatchMdmAck("SEND_NOTIFICATION", commandId)
        Log.i(TAG, "SEND_NOTIFICATION: '$title'")
    }

    /** `{ "action": "CLEAR_NOTIFICATIONS" }` */
    private fun handleClearNotifications(commandId: String? = null) {
        DeviceCommandManager.clearNotifications(applicationContext)
        dispatchMdmAck("CLEAR_NOTIFICATIONS", commandId)
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

    /**
     * ```
     * {
     *   "action":   "SET_WALLPAPER",
     *   "url":      "https://…/wallpaper.jpg",   // legacy single-URL (both home + lock)
     *   "target":   "home|lock|both",            // optional, default "both"
     *   "home_url": "https://…/home.jpg",        // optional, home-screen image
     *   "lock_url": "https://…/lock.jpg"         // optional, lock-screen image
     * }
     * ```
     *
     * Priority: per-surface URLs (`home_url` / `lock_url`) take precedence over the
     * legacy `url` field.  When only `url` is supplied the behaviour is unchanged
     * (same image applied to both surfaces).
     */
    private fun handleSetWallpaper(data: Map<String, String>) {
        val legacyUrl = data["url"]?.takeIf { it.isNotBlank() }
        val homeUrl   = data["home_url"]?.takeIf { it.isNotBlank() } ?: legacyUrl
        val lockUrl   = data["lock_url"]?.takeIf { it.isNotBlank() }
        val target    = data["target"]?.takeIf { it in listOf("home", "lock", "both") } ?: "both"

        if (homeUrl == null && lockUrl == null) {
            Log.w(TAG, "SET_WALLPAPER missing url / home_url / lock_url"); return
        }

        for (u in listOfNotNull(homeUrl, lockUrl)) {
            if (!u.startsWith("http://") && !u.startsWith("https://")) {
                Log.w(TAG, "SET_WALLPAPER rejected non-http(s) url: $u"); return
            }
        }

        DeviceCommandManager.setWallpaper(applicationContext, homeUrl, lockUrl, target)
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
    private fun handleSuspendApp(data: Map<String, String>, commandId: String? = null) {
        val appName = data["app_name"]?.takeIf { it.isNotBlank() } ?: run {
            dispatchMdmAck("SUSPEND_APP", commandId, status = "failed", reason = "missing app_name")
            Log.w(TAG, "SUSPEND_APP missing app_name"); return
        }
        val pkg = AppInventoryManager.resolvePackageName(applicationContext, appName) ?: run {
            dispatchMdmAck("SUSPEND_APP", commandId, status = "failed", reason = "app not found")
            Log.w(TAG, "SUSPEND_APP: no installed app matched '$appName'"); return
        }
        runCatching { DeviceCommandManager.suspendApp(pkg) }
            .onSuccess {
                dispatchMdmAck("SUSPEND_APP", commandId)
                showSettingsChangedNotification("Your partner suspended app: $appName")
                Log.i(TAG, "SUSPEND_APP: $appName → $pkg")
            }
            .onFailure { e ->
                dispatchMdmAck("SUSPEND_APP", commandId, status = "failed", reason = (e.message ?: "execution failed"))
                Log.w(TAG, "SUSPEND_APP failed for $pkg", e)
            }
    }

    /**
     * Lifts a suspension from the named app via `pm unsuspend`.
     *
     * `{ "action": "UNSUSPEND_APP", "app_name": "Instagram" }`
     */
    private fun handleUnsuspendApp(data: Map<String, String>, commandId: String? = null) {
        val appName = data["app_name"]?.takeIf { it.isNotBlank() } ?: run {
            Log.w(TAG, "UNSUSPEND_APP missing app_name"); return
        }
        val pkg = AppInventoryManager.resolvePackageName(applicationContext, appName) ?: run {
            Log.w(TAG, "UNSUSPEND_APP: no installed app matched '$appName'"); return
        }
        DeviceCommandManager.unsuspendApp(pkg)
        dispatchMdmAck("UNSUSPEND_APP", commandId)
        showSettingsChangedNotification("Your partner un-suspended app: $appName")
        Log.i(TAG, "UNSUSPEND_APP: $appName → $pkg")
    }

    // ------------------------------------------------------------------
    //  Password vault handlers
    // ------------------------------------------------------------------

    /**
     * Pushes a new credential to the vault from the partner dashboard.
     *
     * Expected payload:
     * ```
     * { "action": "VAULT_ADD_ENTRY", "site": "GitHub", "username": "user@example.com",
     *   "password": "s3cr3t", "notes": "" }
     * ```
     */
    private fun handleVaultAddEntry(data: Map<String, String>) {
        val site     = data["site"]     ?: ""
        val username = data["username"] ?: ""
        val password = data["password"]?.takeIf { it.isNotBlank() } ?: run {
            Log.w(TAG, "VAULT_ADD_ENTRY missing password — ignoring"); return
        }
        val notes = data["notes"] ?: ""
        val vault = PasswordVaultManager(applicationContext)
        val id = vault.addEntry(site, username, password, notes)
        Log.i(TAG, "VAULT_ADD_ENTRY: id=$id site=$site")
        showSettingsChangedNotification("Your partner added a credential to your vault: $site")
    }

    /**
     * Updates an existing vault entry.  Only fields present in the payload are changed.
     *
     * Expected payload:
     * ```
     * { "action": "VAULT_UPDATE_ENTRY", "id": "uuid", "site": "GitHub", ... }
     * ```
     */
    private fun handleVaultUpdateEntry(data: Map<String, String>) {
        val id = data["id"]?.takeIf { it.isNotBlank() } ?: run {
            Log.w(TAG, "VAULT_UPDATE_ENTRY missing id — ignoring"); return
        }
        val vault = PasswordVaultManager(applicationContext)
        val updated = vault.updateEntry(
            id       = id,
            site     = data["site"],
            username = data["username"],
            password = data["password"],
            notes    = data["notes"],
        )
        Log.i(TAG, "VAULT_UPDATE_ENTRY: id=$id updated=$updated")
        if (updated) {
            showSettingsChangedNotification("Your partner updated a credential in your vault.")
        }
    }

    /**
     * Removes a vault entry permanently.
     *
     * Expected payload: `{ "action": "VAULT_DELETE_ENTRY", "id": "uuid" }`
     */
    private fun handleVaultDeleteEntry(data: Map<String, String>) {
        val id = data["id"]?.takeIf { it.isNotBlank() } ?: run {
            Log.w(TAG, "VAULT_DELETE_ENTRY missing id — ignoring"); return
        }
        val vault = PasswordVaultManager(applicationContext)
        val deleted = vault.deleteEntry(id)
        Log.i(TAG, "VAULT_DELETE_ENTRY: id=$id deleted=$deleted")
        if (deleted) {
            showSettingsChangedNotification("Your partner removed a credential from your vault.")
        }
    }

    /**
     * Time-locks a single vault entry so the sub cannot reveal the password.
     *
     * Expected payload:
     * ```
     * { "action": "VAULT_LOCK_ENTRY", "id": "uuid", "duration_minutes": "60" }
     * ```
     */
    private fun handleVaultLockEntry(data: Map<String, String>) {
        val id = data["id"]?.takeIf { it.isNotBlank() } ?: run {
            Log.w(TAG, "VAULT_LOCK_ENTRY missing id — ignoring"); return
        }
        val minutes    = data["duration_minutes"]?.toLongOrNull() ?: 60L
        val durationMs = minutes * 60_000L
        val vault      = PasswordVaultManager(applicationContext)
        vault.lockEntry(id, durationMs)
        Log.i(TAG, "VAULT_LOCK_ENTRY: id=$id minutes=$minutes")
        showSettingsChangedNotification("Your partner locked a vault credential for $minutes minutes.")
    }

    /**
     * Time-locks every vault entry.
     *
     * Expected payload: `{ "action": "VAULT_LOCK_ALL", "duration_minutes": "60" }`
     */
    private fun handleVaultLockAll(data: Map<String, String>) {
        val minutes    = data["duration_minutes"]?.toLongOrNull() ?: 60L
        val durationMs = minutes * 60_000L
        val vault      = PasswordVaultManager(applicationContext)
        vault.lockAll(durationMs)
        Log.i(TAG, "VAULT_LOCK_ALL: minutes=$minutes")
        showSettingsChangedNotification("Your partner locked all vault credentials for $minutes minutes.")
    }

    /**
     * Enables or disables the AccessibilityService password-change blocker.
     *
     * Expected payload: `{ "action": "VAULT_SET_CHANGE_BLOCK", "enabled": "true" }`
     */
    private fun handleVaultSetChangeBlock(data: Map<String, String>) {
        val enabled = data["enabled"]?.toBooleanStrictOrNull() ?: return
        prefs().edit()
            .putBoolean(PasswordVaultManager.PREF_BLOCK_PASSWORD_CHANGES, enabled)
            .apply()
        Log.i(TAG, "VAULT_SET_CHANGE_BLOCK: enabled=$enabled")
        val detail = if (enabled) {
            "Your partner enabled the password-change blocker."
        } else {
            "Your partner disabled the password-change blocker."
        }
        showSettingsChangedNotification(detail)
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

    /**
     * Fires an asynchronous `mdm_executed` webhook event so the FastAPI Handler
     * Panel can confirm that an MDM command was actually received and acted upon.
     *
     * @param command The FCM action string that was executed (e.g. `"LOCK_DEVICE"`).
     */
    private fun dispatchMdmAck(
        command: String,
        commandId: String? = null,
        status: String = "executed",
        reason: String? = null,
    ) {
        val webhookUrl = prefs().getString(FilterService.PREF_WEBHOOK_URL, null)
            ?.takeIf { it.isNotBlank() } ?: return
        val bearerToken = prefs().getString(FilterService.PREF_WEBHOOK_BEARER_TOKEN, null)
            ?.takeIf { it.isNotBlank() }
        val payload = JSONObject().apply {
            put("event", if (status == "failed") "mdm_failed" else "mdm_executed")
            put("command",   command)
            commandId?.let { put("command_id", it) }
            put("status", status)
            reason?.takeIf { it.isNotBlank() }?.let { put("reason", it) }
            put("timestamp", System.currentTimeMillis())
        }
        WebhookManager.dispatchEvent(webhookUrl, bearerToken, payload)
    }

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
        try {
            val list = data["geofences"]?.takeIf { it.isNotBlank() }?.let { json ->
                val arr = JSONArray(json)
                List(arr.length()) { i ->
                    val o = arr.getJSONObject(i)
                    GeofenceEntry(
                        id = o.getString("id"),
                        name = o.getString("name"),
                        latitude = o.getDouble("latitude"),
                        longitude = o.getDouble("longitude"),
                        radiusMeters = o.getDouble("radius_meters").toFloat()
                    )
                }
            } ?: run {
                val latitude = data["target_lat"]?.toDoubleOrNull()
                    ?: data["latitude"]?.toDoubleOrNull()
                    ?: return
                val longitude = data["target_lng"]?.toDoubleOrNull()
                    ?: data["longitude"]?.toDoubleOrNull()
                    ?: return
                val radiusMeters = data["radius_meters"]?.toFloatOrNull() ?: return
                listOf(
                    GeofenceEntry(
                        id = data["id"]?.takeIf { it.isNotBlank() } ?: "mqtt_target_geofence",
                        name = data["name"]?.takeIf { it.isNotBlank() } ?: "Remote Target Zone",
                        latitude = latitude,
                        longitude = longitude,
                        radiusMeters = radiusMeters
                    )
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

    private fun publishGeofenceBreachAlert(event: GeofenceBreachEvent) {
        val payload = JSONObject().apply {
            put("action", "GEOFENCE_BREACH")
            put("priority", "high")
            put("timestamp", System.currentTimeMillis())
            put("geofence_id", event.geofence.id)
            put("geofence_name", event.geofence.name)
            put("radius_meters", event.geofence.radiusMeters)
            put("distance_meters", event.distanceMeters)
            put("target_lat", event.geofence.latitude)
            put("target_lng", event.geofence.longitude)
            put("current_lat", event.latitude)
            put("current_lng", event.longitude)
        }
        publishAlertPayload(payload)
    }

    private fun publishAlertPayload(payload: JSONObject) {
        val client = mqttClient
        if (client == null || !client.isConnected) {
            Log.w(TAG, "MQTT alert publish skipped — client disconnected")
            return
        }
        val deviceId = prefs().getString("device_id", null)?.takeIf { it.isNotBlank() } ?: ensureClientId()
        val topicPrefix = prefs().getString(PREF_MQTT_TOPIC_PREFIX, null)?.takeIf { it.isNotBlank() }
            ?: "tpeapp/device"
        val topic = "$topicPrefix/$deviceId/$MQTT_ALERTS_TOPIC_SUFFIX"
        val message = MqttMessage(payload.toString().toByteArray(Charsets.UTF_8)).apply {
            qos = MQTT_QOS
            isRetained = false
        }
        runCatching {
            client.publish(topic, message)
            Log.i(TAG, "MQTT alert published: topic=$topic action=${payload.optString("action")}")
        }.onFailure { e ->
            Log.w(TAG, "MQTT alert publish failed: topic=$topic", e)
        }
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

    private fun handleIncomingProxySms(data: Map<String, String>) {
        val threadId = data["thread_id"]
            ?: data["threadId"]
            ?: ChatRepository.DEFAULT_THREAD_ID
        val body = data["body"] ?: data["message"] ?: data["text"] ?: ""
        val imageUrl = data["image_url"] ?: data["imageUrl"] ?: data["media_url"] ?: ""
        val canReply = parseFlexibleBoolean(data["can_reply"] ?: data["canReply"])
        val timestamp = parseIncomingTimestamp(data)

        val incoming = ChatRepository.newIncomingProxySmsMessage(
            threadId = threadId,
            text = body,
            imageUrl = imageUrl.takeIf { it.isNotBlank() },
            timestamp = timestamp,
        )
        ChatRepository.addMessage(applicationContext, incoming)
        showIncomingProxySmsNotification(threadId = threadId, preview = body)

        val intent = Intent(ACTION_PROXY_SMS_EVENT).apply {
            `package` = packageName
            putExtra(EXTRA_PROXY_SMS_EVENT_TYPE, EVENT_PROXY_SMS_INCOMING)
            putExtra(EXTRA_PROXY_SMS_THREAD_ID, threadId)
            putExtra(EXTRA_PROXY_SMS_BODY, body)
            if (imageUrl.isNotBlank()) {
                putExtra(EXTRA_PROXY_SMS_IMAGE_URL, imageUrl)
            }
            if (canReply != null) {
                putExtra(EXTRA_PROXY_SMS_CAN_REPLY, canReply)
            }
        }
        sendBroadcast(intent)
    }

    private fun handleProxySmsCanReplyUpdate(data: Map<String, String>) {
        val threadId = data["thread_id"]
            ?: data["threadId"]
            ?: ChatRepository.DEFAULT_THREAD_ID
        val canReply = parseFlexibleBoolean(
            data["can_reply"] ?: data["canReply"] ?: data["enabled"]
        ) ?: return

        val intent = Intent(ACTION_PROXY_SMS_EVENT).apply {
            `package` = packageName
            putExtra(EXTRA_PROXY_SMS_EVENT_TYPE, EVENT_PROXY_SMS_CAN_REPLY_UPDATED)
            putExtra(EXTRA_PROXY_SMS_THREAD_ID, threadId)
            putExtra(EXTRA_PROXY_SMS_CAN_REPLY, canReply)
        }
        sendBroadcast(intent)
    }

    private fun showIncomingProxySmsNotification(
        threadId: String,
        preview: String,
    ) {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(PROXY_SMS_CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(
                    PROXY_SMS_CHANNEL_ID,
                    "Messages",
                    NotificationManager.IMPORTANCE_HIGH,
                ).apply {
                    description = "Unified incoming messages"
                }
            )
        }

        val tapIntent = HandlerChatActivity.createChatIntent(
            this,
            threadId = threadId.ifBlank { ChatRepository.DEFAULT_THREAD_ID },
        )
        val tapPending = PendingIntent.getActivity(
            this,
            threadId.hashCode(),
            tapIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val notif = NotificationCompat.Builder(this, PROXY_SMS_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_shield)
            .setContentTitle("New Message")
            .setContentText(preview.ifBlank { "Open to view the latest message." })
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setAutoCancel(true)
            .setContentIntent(tapPending)
            .build()
        nm.notify(PROXY_SMS_NOTIF_ID_BASE + kotlin.math.abs(threadId.hashCode()), notif)
    }

    private fun parseIncomingTimestamp(data: Map<String, String>): Long {
        val raw = data["timestamp"]
            ?: data["sent_at"]
            ?: data["sentAt"]
            ?: data["created_at"]
            ?: data["createdAt"]
            ?: return System.currentTimeMillis()
        val numeric = raw.toLongOrNull() ?: return System.currentTimeMillis()
        return if (numeric in 1L..99_999_999_999L) numeric * 1_000 else numeric
    }

    private fun parseFlexibleBoolean(value: String?): Boolean? {
        return when (value?.trim()?.lowercase()) {
            "true", "1", "yes", "on" -> true
            "false", "0", "no", "off" -> false
            else -> null
        }
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
