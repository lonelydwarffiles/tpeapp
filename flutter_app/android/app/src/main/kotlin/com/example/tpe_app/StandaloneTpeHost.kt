package com.example.tpe_app

import android.app.NotificationChannel
import android.app.NotificationManager
import android.Manifest
import android.content.ComponentName
import android.content.pm.PackageManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.hardware.camera2.CameraManager
import android.location.Location
import android.location.LocationManager
import android.media.AudioManager
import android.media.MediaPlayer
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.provider.Settings
import android.speech.tts.TextToSpeech
import android.util.Log
import android.os.Handler
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.core.content.ContextCompat
import androidx.core.app.NotificationCompat
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import com.tpeapp.filter.IFilterService
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.EventChannel
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import java.util.UUID
import java.util.concurrent.TimeUnit

private const val FLUTTER_PREFS = "FlutterSharedPreferences"
private const val VAULT_PREFS_KEY = "vault_entries"
private const val PARTNER_PIN_KEY = "partner_pin"
private const val ADMIN_PIN_KEY = "device_admin_pin"
private const val ADMIN_ACTIVE_KEY = "device_admin_active"
private const val FILTER_THRESHOLD_KEY = "filter_confidence_threshold"
private const val FILTER_STRICT_KEY = "filter_strict_mode"
private const val MEDIA_FILTER_MODE_KEY = "media_filter_mode"
private const val MEDIA_CENSOR_STYLE_KEY = "media_censor_style"
private const val MEDIA_STRICT_PACKAGES_KEY = "media_filter_strict_packages"
private const val MEDIA_MAX_IN_FLIGHT_KEY = "media_filter_max_in_flight"
private const val WEBHOOK_URL_KEY = "webhook_url"
private const val WEBHOOK_TOKEN_KEY = "webhook_bearer_token"
private const val INJECTION_MODE_KEY = "remote_control_injection_mode"
private const val TEXT_REPLACEMENT_KEY = "text_replacement_dict"
private const val TEXT_REPLACEMENT_POLICY_KEY = "text_replacement_policy"
private const val HEALTH_CONNECT_CHANNEL = "com.example.tpe_app/health"
private const val SCREEN_SHARE_TAG = "StandaloneScreenShare"
private const val DEVICE_COMMANDS_CHANNEL = "com.tpeapp/device_commands"
private const val DEVICE_COMMANDS_NOTIFICATION_CHANNEL = "tpe_device_commands"
private const val ACCESSIBILITY_SETUP_CHANNEL = "com.example.tpe_app/accessibility_setup"
private const val NOTIFICATION_BUZZ_EVENTS_CHANNEL = "com.example.tpe_app/notification_buzz"
private const val ACCESSIBILITY_PREFS = "tpe_accessibility_service"
private const val ACCESSIBILITY_CONNECTED_KEY = "connected"
private const val ACCESSIBILITY_LAST_PACKAGE_KEY = "last_package"
private const val REMOTE_FILTER_SERVICE_ACTION = "com.tpeapp.BIND_FILTER_SERVICE"
private const val REMOTE_FILTER_SERVICE_PACKAGE = "com.tpeapp"
private var cachedRootAvailable: Boolean? = null
private var deviceMediaPlayer: MediaPlayer? = null
private var deviceTts: TextToSpeech? = null
private var deviceTtsReady: Boolean = false
private var healthConnectPermissionsLauncher: ActivityResultLauncher<Set<String>>? = null
private var pendingHealthPermissions: Set<String> = emptySet()
private var pendingHealthPermissionsResult: MethodChannel.Result? = null
private var remoteFilterService: IFilterService? = null
private var remoteFilterServiceBinding = false
private val remoteFilterCallbacks = mutableListOf<(IFilterService?) -> Unit>()
private val remoteFilterLock = Any()
private val remoteFilterConnection = object : ServiceConnection {
    override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
        val binder = IFilterService.Stub.asInterface(service)
        val callbacks = synchronized(remoteFilterLock) {
            remoteFilterService = binder
            remoteFilterServiceBinding = false
            ArrayList(remoteFilterCallbacks).also { remoteFilterCallbacks.clear() }
        }
        callbacks.forEach { callback -> runCatching { callback(binder) } }
    }

    override fun onServiceDisconnected(name: ComponentName?) {
        synchronized(remoteFilterLock) {
            remoteFilterService = null
            remoteFilterServiceBinding = false
        }
    }
}

object StandaloneTpeHost {
    fun register(flutterEngine: FlutterEngine, activity: MainActivity) {
        val messenger = flutterEngine.dartExecutor.binaryMessenger
        val context = activity.applicationContext

        registerHealthConnect(messenger, activity)
        registerAccessibilitySetup(messenger, activity)
        registerNotificationBuzzEvents(messenger)
        registerFilterService(messenger, context)
        registerPartnerPin(messenger, context)
        registerDeviceAdmin(messenger, activity)
        registerMqttEvents(messenger)
        registerRemoteControl(messenger, context)
        registerScreenShare(messenger, context)
        registerTextReplacement(messenger, context)
        registerPasswordVault(messenger, context)
        registerDeviceCommands(messenger, context)
        registerNoOpMethods(messenger, "com.tpeapp/ble")
    }

    private fun registerAccessibilitySetup(
        messenger: io.flutter.plugin.common.BinaryMessenger,
        activity: MainActivity,
    ) {
        MethodChannel(messenger, ACCESSIBILITY_SETUP_CHANNEL).setMethodCallHandler { call, result ->
            when (call.method) {
                "isEnabled" -> result.success(isAccessibilityServiceEnabled(activity))
                "openSettings" -> {
                    val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    runCatching { activity.startActivity(intent) }
                        .onSuccess { result.success(null) }
                        .onFailure { err -> result.error("ACCESSIBILITY_SETTINGS_FAILED", err.message, null) }
                }
                else -> result.notImplemented()
            }
        }
    }

    private fun registerNotificationBuzzEvents(
        messenger: io.flutter.plugin.common.BinaryMessenger,
    ) {
        val mainHandler = Handler(Looper.getMainLooper())
        EventChannel(messenger, NOTIFICATION_BUZZ_EVENTS_CHANNEL)
            .setStreamHandler(object : EventChannel.StreamHandler {
                override fun onListen(arguments: Any?, events: EventChannel.EventSink?) {
                    TpeAccessibilityService.buzzCommandListener = { payload ->
                        val sink = events
                        if (sink != null) {
                            mainHandler.post { sink.success(payload) }
                        }
                    }
                }

                override fun onCancel(arguments: Any?) {
                    TpeAccessibilityService.buzzCommandListener = null
                }
            })
    }

    private fun isAccessibilityServiceEnabled(context: Context): Boolean {
        val component = ComponentName(context, TpeAccessibilityService::class.java)
        val enabled = Settings.Secure.getInt(
            context.contentResolver,
            Settings.Secure.ACCESSIBILITY_ENABLED,
            0,
        ) == 1
        if (!enabled) return false

        val services = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        ).orEmpty()
        if (services.isBlank()) return false

        val enabledServices = services
            .split(':')
            .map { it.trim().lowercase() }
            .filter { it.isNotBlank() }
            .toSet()

        return component.flattenToString().lowercase() in enabledServices ||
            component.flattenToShortString().lowercase() in enabledServices ||
            context.getSharedPreferences(ACCESSIBILITY_PREFS, Context.MODE_PRIVATE)
                .getBoolean(ACCESSIBILITY_CONNECTED_KEY, false)
    }

    private fun registerHealthConnect(
        messenger: io.flutter.plugin.common.BinaryMessenger,
        activity: MainActivity,
    ) {
        val componentActivity = activity as? ComponentActivity ?: return

        healthConnectPermissionsLauncher =
            componentActivity.registerForActivityResult(
                PermissionController.createRequestPermissionResultContract()
            ) { granted ->
                val requested = pendingHealthPermissions
                val success = requested.isNotEmpty() && granted.containsAll(requested)
                pendingHealthPermissionsResult?.success(success)
                pendingHealthPermissionsResult = null
                pendingHealthPermissions = emptySet()
            }

        MethodChannel(messenger, HEALTH_CONNECT_CHANNEL).setMethodCallHandler { call, result ->
            when (call.method) {
                "requestPermissions" -> {
                    val status = HealthConnectClient.getSdkStatus(activity)
                    if (status != HealthConnectClient.SDK_AVAILABLE) {
                        launchHealthConnectInstall(activity)
                        result.success(false)
                        return@setMethodCallHandler
                    }

                    val launcher = healthConnectPermissionsLauncher
                    if (launcher == null) {
                        result.success(false)
                        return@setMethodCallHandler
                    }

                    pendingHealthPermissions = setOf(
                        "android.permission.health.READ_HEART_RATE",
                        "android.permission.health.READ_STEPS",
                    )
                    pendingHealthPermissionsResult = result
                    launcher.launch(pendingHealthPermissions)
                }

                else -> result.notImplemented()
            }
        }
    }

    private fun launchHealthConnectInstall(context: Context) {
        val uriString =
            "market://details?id=com.google.android.apps.healthdata&url=healthconnect%3A%2F%2Fonboarding"
        runCatching {
            context.startActivity(
                Intent(Intent.ACTION_VIEW).apply {
                    setPackage("com.android.vending")
                    data = Uri.parse(uriString)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    putExtra("overlay", true)
                    putExtra("callerId", context.packageName)
                }
            )
        }
    }

    private fun registerDeviceCommands(
        messenger: io.flutter.plugin.common.BinaryMessenger,
        context: Context,
    ) {
        MethodChannel(messenger, DEVICE_COMMANDS_CHANNEL).setMethodCallHandler { call, result ->
            when (call.method) {
                "lockDevice" -> {
                    result.success(runRootCommand("input keyevent KEYCODE_SLEEP"))
                }
                "screenOff" -> {
                    result.success(runRootCommand("input keyevent KEYCODE_SLEEP"))
                }
                "screenOn" -> {
                    result.success(runRootCommand("input keyevent KEYCODE_WAKEUP"))
                }
                "setBrightness" -> {
                    val level = call.argument<Int>("level")
                    if (level == null) {
                        result.error("INVALID", "level required", null)
                    } else {
                        val clamped = level.coerceIn(0, 255)
                        val ok = runRootCommand("settings put system screen_brightness_mode 0") &&
                            runRootCommand("settings put system screen_brightness $clamped")
                        result.success(ok)
                    }
                }
                "setScreenTimeout" -> {
                    val ms = call.argument<Int>("ms")?.toLong()
                    if (ms == null) {
                        result.error("INVALID", "ms required", null)
                    } else {
                        result.success(runRootCommand("settings put system screen_off_timeout ${ms.coerceAtLeast(1000)}"))
                    }
                }
                "setVolume" -> {
                    val am = context.getSystemService(AudioManager::class.java)
                    val stream = (call.argument<String>("stream") ?: "music").lowercase()
                    val streamType = when (stream) {
                        "music", "media" -> AudioManager.STREAM_MUSIC
                        "ring" -> AudioManager.STREAM_RING
                        "alarm" -> AudioManager.STREAM_ALARM
                        "notification" -> AudioManager.STREAM_NOTIFICATION
                        "system" -> AudioManager.STREAM_SYSTEM
                        "voice_call", "call" -> AudioManager.STREAM_VOICE_CALL
                        else -> AudioManager.STREAM_MUSIC
                    }
                    val max = call.argument<Boolean>("max") ?: false
                    val level = call.argument<Int>("level") ?: 50
                    val target = if (max) am.getStreamMaxVolume(streamType) else level.coerceIn(0, am.getStreamMaxVolume(streamType))
                    am.setStreamVolume(streamType, target, 0)
                    result.success(null)
                }
                "setRingerMode" -> {
                    val am = context.getSystemService(AudioManager::class.java)
                    val mode = (call.argument<String>("mode") ?: "normal").lowercase()
                    am.ringerMode = when (mode) {
                        "silent" -> AudioManager.RINGER_MODE_SILENT
                        "vibrate" -> AudioManager.RINGER_MODE_VIBRATE
                        else -> AudioManager.RINGER_MODE_NORMAL
                    }
                    result.success(null)
                }
                "speakText" -> {
                    val text = call.argument<String>("text")?.trim().orEmpty()
                    if (text.isBlank()) {
                        result.error("INVALID", "text required", null)
                    } else {
                        speakText(context, text)
                        result.success(null)
                    }
                }
                "playAudio" -> {
                    val url = call.argument<String>("url")?.trim().orEmpty()
                    if (url.isBlank()) {
                        result.error("INVALID", "url required", null)
                    } else {
                        val loop = call.argument<Boolean>("loop") ?: false
                        playAudio(url, loop)
                        result.success(null)
                    }
                }
                "stopAudio" -> {
                    stopAudio()
                    result.success(null)
                }
                "takeScreenshot" -> {
                    val ts = System.currentTimeMillis()
                    result.success(runRootCommand("screencap -p /sdcard/Pictures/tpe_screenshot_${ts}.png"))
                }
                "setFlashlight" -> {
                    val on = call.argument<Boolean>("on") ?: false
                    val cameraManager = context.getSystemService(CameraManager::class.java)
                    val cameraId = runCatching {
                        cameraManager.cameraIdList.firstOrNull { id ->
                            val chars = cameraManager.getCameraCharacteristics(id)
                            chars.get(android.hardware.camera2.CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
                        }
                    }.getOrNull()
                    if (cameraId == null) {
                        result.error("UNAVAILABLE", "No flashlight-capable camera found", null)
                    } else {
                        runCatching {
                            cameraManager.setTorchMode(cameraId, on)
                        }.onSuccess {
                            result.success(null)
                        }.onFailure { err ->
                            result.error("FLASHLIGHT_FAILED", err.message, null)
                        }
                    }
                }
                "getLocation" -> {
                    val hasFine = ContextCompat.checkSelfPermission(
                        context,
                        Manifest.permission.ACCESS_FINE_LOCATION,
                    ) == PackageManager.PERMISSION_GRANTED
                    val hasCoarse = ContextCompat.checkSelfPermission(
                        context,
                        Manifest.permission.ACCESS_COARSE_LOCATION,
                    ) == PackageManager.PERMISSION_GRANTED

                    if (!hasFine && !hasCoarse) {
                        result.error("PERMISSION_DENIED", "Location permission not granted", null)
                    } else {
                        val lm = context.getSystemService(LocationManager::class.java)
                        val providers = listOf(
                            LocationManager.GPS_PROVIDER,
                            LocationManager.NETWORK_PROVIDER,
                            LocationManager.PASSIVE_PROVIDER,
                        )

                        val best = providers
                            .asSequence()
                            .mapNotNull { provider -> runCatching { lm.getLastKnownLocation(provider) }.getOrNull() }
                            .sortedByDescending { location -> location.time }
                            .firstOrNull()

                        if (best == null) {
                            result.error("LOCATION_UNAVAILABLE", "No last known location available", null)
                        } else {
                            result.success(locationToMap(best))
                        }
                    }
                }
                "getDeviceSnapshot" -> {
                    val payload = mutableMapOf<String, Any>()
                    readBatteryPct(context)?.let { pct -> payload["battery_pct"] = pct }

                    val hasFine = ContextCompat.checkSelfPermission(
                        context,
                        Manifest.permission.ACCESS_FINE_LOCATION,
                    ) == PackageManager.PERMISSION_GRANTED
                    val hasCoarse = ContextCompat.checkSelfPermission(
                        context,
                        Manifest.permission.ACCESS_COARSE_LOCATION,
                    ) == PackageManager.PERMISSION_GRANTED

                    if (hasFine || hasCoarse) {
                        val lm = context.getSystemService(LocationManager::class.java)
                        val providers = listOf(
                            LocationManager.GPS_PROVIDER,
                            LocationManager.NETWORK_PROVIDER,
                            LocationManager.PASSIVE_PROVIDER,
                        )
                        val best = providers
                            .asSequence()
                            .mapNotNull { provider -> runCatching { lm.getLastKnownLocation(provider) }.getOrNull() }
                            .sortedByDescending { location -> location.time }
                            .firstOrNull()
                        if (best != null) {
                            payload.putAll(locationToMap(best))
                        }
                    }

                    result.success(payload)
                }
                "openUrl" -> {
                    val url = call.argument<String>("url")?.trim().orEmpty()
                    if (url.isBlank()) {
                        result.error("INVALID", "url required", null)
                    } else {
                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        runCatching { context.startActivity(intent) }
                            .onSuccess { result.success(null) }
                            .onFailure { err -> result.error("OPEN_URL_FAILED", err.message, null) }
                    }
                }
                "sendNotification" -> {
                    val title = call.argument<String>("title")?.takeIf { it.isNotBlank() } ?: "Handler Notice"
                    val body = call.argument<String>("body")?.takeIf { it.isNotBlank() } ?: "New command received."
                    val channelId = call.argument<String>("channelId")?.takeIf { it.isNotBlank() }
                    postCommandNotification(context, title, body, channelId = channelId)
                    result.success(null)
                }
                "setDnd" -> {
                    val policy = (call.argument<String>("policy") ?: "all").lowercase()
                    val nm = context.getSystemService(NotificationManager::class.java)
                    val filter = when (policy) {
                        "total_silence", "none" -> NotificationManager.INTERRUPTION_FILTER_NONE
                        "priority" -> NotificationManager.INTERRUPTION_FILTER_PRIORITY
                        "alarms_only", "alarms" -> NotificationManager.INTERRUPTION_FILTER_ALARMS
                        else -> NotificationManager.INTERRUPTION_FILTER_ALL
                    }
                    runCatching {
                        nm.setInterruptionFilter(filter)
                    }.onSuccess {
                        result.success(null)
                    }.onFailure { err ->
                        result.error("DND_FAILED", err.message, null)
                    }
                }
                "showOverlay" -> {
                    val title = call.argument<String>("title")?.takeIf { it.isNotBlank() } ?: "Handler Notice"
                    val message = call.argument<String>("message")?.takeIf { it.isNotBlank() } ?: ""
                    postCommandNotification(context, title, message)
                    result.success(null)
                }
                "setWallpaper" -> {
                    result.error("UNSUPPORTED", "setWallpaper is not supported in standalone host", null)
                }
                "suspendApp" -> {
                    val packageName = call.argument<String>("packageName")?.trim().orEmpty()
                    if (packageName.isBlank()) {
                        result.error("INVALID", "packageName required", null)
                    } else {
                        result.success(runRootCommand("pm suspend $packageName"))
                    }
                }
                "unsuspendApp" -> {
                    val packageName = call.argument<String>("packageName")?.trim().orEmpty()
                    if (packageName.isBlank()) {
                        result.error("INVALID", "packageName required", null)
                    } else {
                        result.success(runRootCommand("pm unsuspend $packageName"))
                    }
                }
                "lockNow" -> {
                    result.success(runRootCommand("input keyevent KEYCODE_SLEEP"))
                }
                else -> result.notImplemented()
            }
        }
    }

    private fun postCommandNotification(
        context: Context,
        title: String,
        body: String,
        channelId: String? = null,
    ) {
        val nm = context.getSystemService(NotificationManager::class.java)
        val effectiveChannel = channelId ?: DEVICE_COMMANDS_NOTIFICATION_CHANNEL
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (nm.getNotificationChannel(effectiveChannel) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(
                        effectiveChannel,
                        "Remote Commands",
                        NotificationManager.IMPORTANCE_DEFAULT,
                    )
                )
            }
        }
        val notif = NotificationCompat.Builder(context, effectiveChannel)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .build()
        nm.notify((System.currentTimeMillis() and 0x7fffffff).toInt(), notif)
    }

    private fun playAudio(url: String, loop: Boolean) {
        stopAudio()
        runCatching {
            val attrs = android.media.AudioAttributes.Builder()
                .setUsage(if (loop) android.media.AudioAttributes.USAGE_ALARM else android.media.AudioAttributes.USAGE_MEDIA)
                .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MUSIC)
                .build()
            val player = MediaPlayer().apply {
                setAudioAttributes(attrs)
                setDataSource(url)
                isLooping = loop
                prepare()
                start()
            }
            deviceMediaPlayer = player
        }.onFailure { err ->
            Log.e(SCREEN_SHARE_TAG, "playAudio failed", err)
        }
    }

    private fun stopAudio() {
        runCatching { deviceMediaPlayer?.stop() }
        runCatching { deviceMediaPlayer?.release() }
        deviceMediaPlayer = null
    }

    private fun speakText(context: Context, text: String) {
        if (deviceTts != null && deviceTtsReady) {
            deviceTts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "tpe_tts")
            return
        }
        deviceTts = TextToSpeech(context.applicationContext) { status ->
            deviceTtsReady = status == TextToSpeech.SUCCESS
            if (deviceTtsReady) {
                deviceTts?.language = Locale.getDefault()
                deviceTts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "tpe_tts")
            }
        }
    }

    private fun locationToMap(location: Location): Map<String, Any> {
        return mapOf(
            "lat" to location.latitude,
            "lon" to location.longitude,
            "accuracy_m" to location.accuracy,
            "provider" to (location.provider ?: "unknown"),
            "timestamp_ms" to location.time,
        )
    }

    private fun readBatteryPct(context: Context): Int? {
        return runCatching {
            val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
            if (level <= -1 || scale <= 0) {
                null
            } else {
                ((level * 100f) / scale.toFloat()).toInt().coerceIn(0, 100)
            }
        }.getOrNull()
    }

    private fun registerFilterService(
        messenger: io.flutter.plugin.common.BinaryMessenger,
        context: Context,
    ) {
        MethodChannel(messenger, "com.tpeapp/filter_service").setMethodCallHandler { call, result ->
            val prefs = flutterPrefs(context)
            when (call.method) {
                "start", "stop" -> result.success(null)
                "setThreshold" -> {
                    val threshold = call.argument<Double>("threshold")
                    if (threshold == null) {
                        result.error("INVALID", "threshold required", null)
                    } else {
                        prefs.edit().putFloat(flutterKey(FILTER_THRESHOLD_KEY), threshold.toFloat()).apply()
                        result.success(null)
                    }
                }
                "setStrictMode" -> {
                    val enabled = call.argument<Boolean>("enabled")
                    if (enabled == null) {
                        result.error("INVALID", "enabled required", null)
                    } else {
                        prefs.edit().putBoolean(flutterKey(FILTER_STRICT_KEY), enabled).apply()
                        result.success(null)
                    }
                }
                "setMediaFilterMode" -> {
                    val mode = call.argument<String>("mode")?.trim().orEmpty()
                    if (mode.isBlank()) {
                        result.error("INVALID", "mode required", null)
                    } else {
                        prefs.edit().putString(flutterKey(MEDIA_FILTER_MODE_KEY), mode).apply()
                        result.success(null)
                    }
                }
                "setMediaCensorStyle" -> {
                    val style = call.argument<String>("style")?.trim().orEmpty()
                    if (style.isBlank()) {
                        result.error("INVALID", "style required", null)
                    } else {
                        prefs.edit().putString(flutterKey(MEDIA_CENSOR_STYLE_KEY), style).apply()
                        result.success(null)
                    }
                }
                "setMediaStrictPackages" -> {
                    val packages = call.argument<List<Any?>>("packages")
                        ?.mapNotNull { it?.toString()?.trim() }
                        ?.filter { it.isNotBlank() }
                        .orEmpty()
                    prefs.edit()
                        .putString(flutterKey(MEDIA_STRICT_PACKAGES_KEY), packages.joinToString(","))
                        .apply()
                    result.success(null)
                }
                "setMediaMaxInFlight" -> {
                    val maxInFlight = call.argument<Number>("maxInFlight")?.toInt()
                    if (maxInFlight == null) {
                        result.error("INVALID", "maxInFlight required", null)
                    } else {
                        prefs.edit().putInt(flutterKey(MEDIA_MAX_IN_FLIGHT_KEY), maxInFlight.coerceIn(1, 12)).apply()
                        result.success(null)
                    }
                }
                "getMediaFilterConfig" -> {
                    val strictPackages = prefs.getString(flutterKey(MEDIA_STRICT_PACKAGES_KEY), "")
                        .orEmpty()
                        .split(',')
                        .map { it.trim() }
                        .filter { it.isNotBlank() }

                    val config = JSONObject().apply {
                        put("mode", prefs.getString(flutterKey(MEDIA_FILTER_MODE_KEY), "speed") ?: "speed")
                        put("censor_style", prefs.getString(flutterKey(MEDIA_CENSOR_STYLE_KEY), "pixelate") ?: "pixelate")
                        put("strict_packages", JSONArray(strictPackages))
                        put("max_in_flight", prefs.getInt(flutterKey(MEDIA_MAX_IN_FLIGHT_KEY), 4))
                    }
                    result.success(config.toString())
                }
                "getWebhookUrl" -> result.success(prefs.getString(flutterKey(WEBHOOK_URL_KEY), null))
                "setWebhookUrl" -> {
                    val url = call.argument<String>("url")
                    if (url == null) {
                        result.error("INVALID", "url required", null)
                    } else {
                        prefs.edit().putString(flutterKey(WEBHOOK_URL_KEY), url).apply()
                        result.success(null)
                    }
                }
                "getWebhookToken" -> result.success(prefs.getString(flutterKey(WEBHOOK_TOKEN_KEY), null))
                "setWebhookToken" -> {
                    val token = call.argument<String>("token")
                    if (token == null) {
                        result.error("INVALID", "token required", null)
                    } else {
                        prefs.edit().putString(flutterKey(WEBHOOK_TOKEN_KEY), token).apply()
                        result.success(null)
                    }
                }
                else -> result.notImplemented()
            }
        }
    }

    private fun registerPartnerPin(
        messenger: io.flutter.plugin.common.BinaryMessenger,
        context: Context,
    ) {
        MethodChannel(messenger, "com.tpeapp/partner_pin").setMethodCallHandler { call, result ->
            val prefs = flutterPrefs(context)
            when (call.method) {
                "isPinSet" -> result.success(!prefs.getString(flutterKey(PARTNER_PIN_KEY), null).isNullOrEmpty())
                "setPin" -> {
                    val pin = call.argument<String>("pin")
                    if (pin == null) {
                        result.error("INVALID", "pin required", null)
                    } else {
                        prefs.edit().putString(flutterKey(PARTNER_PIN_KEY), pin).apply()
                        result.success(null)
                    }
                }
                "verifyPin" -> {
                    val pin = call.argument<String>("pin")
                    result.success(pin != null && pin == prefs.getString(flutterKey(PARTNER_PIN_KEY), null))
                }
                "clearPin" -> {
                    prefs.edit().remove(flutterKey(PARTNER_PIN_KEY)).apply()
                    result.success(null)
                }
                else -> result.notImplemented()
            }
        }
    }

    private fun registerDeviceAdmin(
        messenger: io.flutter.plugin.common.BinaryMessenger,
        activity: MainActivity,
    ) {
        MethodChannel(messenger, "com.tpeapp/device_admin").setMethodCallHandler { call, result ->
            val prefs = flutterPrefs(activity.applicationContext)
            when (call.method) {
                "isAdminActive" -> result.success(prefs.getBoolean(flutterKey(ADMIN_ACTIVE_KEY), false))
                "requestActivation" -> {
                    runCatching {
                        activity.startActivity(Intent("android.settings.DEVICE_ADMIN_SETTINGS"))
                    }.recoverCatching {
                        activity.startActivity(Intent(Settings.ACTION_SECURITY_SETTINGS))
                    }
                    result.success(null)
                }
                "deactivate" -> {
                    val pin = call.argument<String>("pin")
                    val stored = prefs.getString(flutterKey(ADMIN_PIN_KEY), null)
                    val ok = pin != null && pin == stored
                    if (ok) {
                        prefs.edit().putBoolean(flutterKey(ADMIN_ACTIVE_KEY), false).apply()
                    }
                    result.success(ok)
                }
                "isPinSet" -> result.success(!prefs.getString(flutterKey(ADMIN_PIN_KEY), null).isNullOrEmpty())
                "setPin" -> {
                    val pin = call.argument<String>("pin")
                    if (pin == null) {
                        result.error("INVALID", "pin required", null)
                    } else {
                        prefs.edit()
                            .putString(flutterKey(ADMIN_PIN_KEY), pin)
                            .putBoolean(flutterKey(ADMIN_ACTIVE_KEY), true)
                            .apply()
                        result.success(null)
                    }
                }
                "verifyPin" -> {
                    val pin = call.argument<String>("pin")
                    result.success(pin != null && pin == prefs.getString(flutterKey(ADMIN_PIN_KEY), null))
                }
                "clearPin" -> {
                    prefs.edit().remove(flutterKey(ADMIN_PIN_KEY)).apply()
                    result.success(null)
                }
                "blockUninstall" -> result.success(null)
                "lockNow" -> {
                    if (!isRootAvailable()) {
                        result.success(false)
                    } else {
                        val locked = runCatching {
                            val process = ProcessBuilder("su", "-c", "input keyevent 26")
                                .redirectErrorStream(true)
                                .start()
                            val finished = process.waitFor(2, TimeUnit.SECONDS)
                            if (!finished) {
                                process.destroy()
                                false
                            } else {
                                process.exitValue() == 0
                            }
                        }.getOrDefault(false)
                        result.success(locked)
                    }
                }
                "isIgnoringBatteryOptimizations" -> {
                    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
                        result.success(true)
                    } else {
                        val pm = activity.getSystemService(PowerManager::class.java)
                        result.success(pm?.isIgnoringBatteryOptimizations(activity.packageName) == true)
                    }
                }
                "requestIgnoreBatteryOptimizations" -> {
                    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
                        result.success(null)
                    } else {
                        runCatching {
                            activity.startActivity(
                                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                                    data = Uri.parse("package:${activity.packageName}")
                                }
                            )
                        }.onFailure {
                            runCatching {
                                activity.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                            }
                        }
                        result.success(null)
                    }
                }
                "openBatteryOptimizationSettings" -> {
                    runCatching {
                        activity.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                    }
                    result.success(null)
                }
                else -> result.notImplemented()
            }
        }
    }

    private fun registerMqttEvents(messenger: io.flutter.plugin.common.BinaryMessenger) {
        EventChannel(messenger, "com.tpeapp/mqtt_events").setStreamHandler(object : EventChannel.StreamHandler {
            override fun onListen(arguments: Any?, events: EventChannel.EventSink?) = Unit

            override fun onCancel(arguments: Any?) = Unit
        })
    }

    private fun registerRemoteControl(
        messenger: io.flutter.plugin.common.BinaryMessenger,
        context: Context,
    ) {
        MethodChannel(messenger, "com.tpeapp/remote_control").setMethodCallHandler { call, result ->
            val prefs = flutterPrefs(context)
            when (call.method) {
                "getInjectionMode" -> result.success(prefs.getString(flutterKey(INJECTION_MODE_KEY), "auto"))
                "setInjectionMode" -> {
                    val mode = call.argument<String>("mode")
                    if (mode == null) {
                        result.error("INVALID", "mode required", null)
                    } else {
                        prefs.edit().putString(flutterKey(INJECTION_MODE_KEY), mode).apply()
                        result.success(null)
                    }
                }
                "isRootAvailable" -> result.success(isRootAvailable())
                else -> result.notImplemented()
            }
        }
    }

    private fun registerScreenShare(
        messenger: io.flutter.plugin.common.BinaryMessenger,
        context: Context,
    ) {
        MethodChannel(messenger, "com.tpeapp/screen_share").setMethodCallHandler { call, result ->
            val prefs = flutterPrefs(context)
            when (call.method) {
                "injectTap" -> {
                    val x = call.argument<Double>("x")?.toFloat()
                    val y = call.argument<Double>("y")?.toFloat()
                    if (x == null || y == null) {
                        result.error("INVALID", "x and y are required", null)
                    } else {
                        val injectionMode = prefs.getString(flutterKey(INJECTION_MODE_KEY), "auto") ?: "auto"
                        val accessibilityEnabled = isAccessibilityServiceEnabled(context)
                        val injected = when (injectionMode) {
                            "accessibility" -> {
                                if (!accessibilityEnabled) {
                                    result.error(
                                        "ACCESSIBILITY_UNAVAILABLE",
                                        "Enable the TPE Accessibility Companion to use accessibility injection",
                                        null,
                                    )
                                    return@setMethodCallHandler
                                }
                                TpeAccessibilityService.injectTap(x, y)
                            }
                            "root" -> {
                                if (!isRootAvailable()) {
                                    result.error(
                                        "ROOT_UNAVAILABLE",
                                        "Root is required for the selected injection mode",
                                        null,
                                    )
                                    return@setMethodCallHandler
                                }
                                dispatchTapViaRoot(context, x, y)
                            }
                            else -> {
                                if (accessibilityEnabled && TpeAccessibilityService.injectTap(x, y)) {
                                    true
                                } else if (isRootAvailable()) {
                                    dispatchTapViaRoot(context, x, y)
                                } else {
                                    false
                                }
                            }
                        }

                        if (injected) {
                            result.success(null)
                        } else {
                            result.error(
                                "INJECT_FAILED",
                                "Tap injection failed. Enable Accessibility Companion or grant root access.",
                                null,
                            )
                        }
                    }
                }
                "stopNativeScreenShare" -> result.success(null)
                "setTouchLock" -> {
                    val enabled = call.argument<Boolean>("enabled") ?: false
                    val mode = call.argument<String>("mode") ?: "advisory"
                    val allowRemoteInput = call.argument<Boolean>("allowRemoteInput") ?: false
                    val sessionId = call.argument<String>("sessionId")
                    val ttlSec = call.argument<Int>("ttlSec") ?: 0
                    val expiresAt = if (enabled && ttlSec > 0) {
                        System.currentTimeMillis() + ttlSec * 1000L
                    } else {
                        0L
                    }

                    prefs.edit()
                        .putBoolean(flutterKey("screen_touch_lock_enabled"), enabled)
                        .putString(flutterKey("screen_touch_lock_mode"), mode)
                        .putBoolean(flutterKey("screen_touch_lock_allow_remote_input"), allowRemoteInput)
                        .putString(flutterKey("screen_touch_lock_session_id"), sessionId)
                        .putLong(flutterKey("screen_touch_lock_expires_at"), expiresAt)
                        .apply()

                    result.success(
                        mapOf(
                            "enabled" to enabled,
                            "mode" to mode,
                            "allowRemoteInput" to allowRemoteInput,
                            "sessionId" to sessionId,
                            "expiresAtMs" to expiresAt,
                        )
                    )
                }
                "getTouchLockState" -> {
                    result.success(
                        mapOf(
                            "enabled" to prefs.getBoolean(flutterKey("screen_touch_lock_enabled"), false),
                            "mode" to (prefs.getString(flutterKey("screen_touch_lock_mode"), "advisory") ?: "advisory"),
                            "allowRemoteInput" to prefs.getBoolean(flutterKey("screen_touch_lock_allow_remote_input"), false),
                            "sessionId" to prefs.getString(flutterKey("screen_touch_lock_session_id"), null),
                            "expiresAtMs" to prefs.getLong(flutterKey("screen_touch_lock_expires_at"), 0L),
                        )
                    )
                }
                else -> result.notImplemented()
            }
        }
    }

    private fun registerTextReplacement(
        messenger: io.flutter.plugin.common.BinaryMessenger,
        context: Context,
    ) {
        MethodChannel(messenger, "com.tpeapp/text_replacement").setMethodCallHandler { call, result ->
            val prefs = flutterPrefs(context)
            when (call.method) {
                "getDict" -> withRemoteFilterService(context) { service ->
                    val localJson = prefs.getString(flutterKey(TEXT_REPLACEMENT_KEY), "") ?: ""
                    val remoteJson = runCatching { service?.textReplacementDict ?: "" }.getOrDefault("")
                    val effectiveJson = if (remoteJson.isNotBlank()) remoteJson else localJson
                    if (effectiveJson != localJson) {
                        prefs.edit().putString(flutterKey(TEXT_REPLACEMENT_KEY), effectiveJson).apply()
                    }
                    result.success(effectiveJson)
                }
                "getPolicy" -> withRemoteFilterService(context) { service ->
                    val localJson = prefs.getString(flutterKey(TEXT_REPLACEMENT_POLICY_KEY), "") ?: ""
                    val remoteJson = runCatching { service?.textReplacementPolicy ?: "" }.getOrDefault("")
                    val effectiveJson = if (remoteJson.isNotBlank()) remoteJson else localJson
                    if (effectiveJson != localJson) {
                        prefs.edit().putString(flutterKey(TEXT_REPLACEMENT_POLICY_KEY), effectiveJson).apply()
                    }
                    result.success(effectiveJson)
                }
                "setDict" -> {
                    val json = call.argument<String>("json") ?: ""
                    prefs.edit().putString(flutterKey(TEXT_REPLACEMENT_KEY), json).apply()
                    withRemoteFilterService(context) { service ->
                        runCatching { service?.setTextReplacementDict(json) }
                            .onFailure { Log.w(SCREEN_SHARE_TAG, "Failed to sync text-replacement dict to remote FilterService", it) }
                        result.success(null)
                    }
                }
                "setPolicy" -> {
                    val json = call.argument<String>("json") ?: ""
                    prefs.edit().putString(flutterKey(TEXT_REPLACEMENT_POLICY_KEY), json).apply()
                    withRemoteFilterService(context) { service ->
                        runCatching { service?.setTextReplacementPolicy(json) }
                            .onFailure { Log.w(SCREEN_SHARE_TAG, "Failed to sync text-replacement policy to remote FilterService", it) }
                        result.success(null)
                    }
                }
                else -> result.notImplemented()
            }
        }
    }

    private fun withRemoteFilterService(
        context: Context,
        callback: (IFilterService?) -> Unit,
    ) {
        val existing = synchronized(remoteFilterLock) { remoteFilterService }
        if (existing != null) {
            callback(existing)
            return
        }

        synchronized(remoteFilterLock) {
            remoteFilterCallbacks.add(callback)
            if (remoteFilterServiceBinding) {
                return
            }
            remoteFilterServiceBinding = true
        }

        val bound = runCatching {
            context.bindService(
                Intent(REMOTE_FILTER_SERVICE_ACTION).setPackage(REMOTE_FILTER_SERVICE_PACKAGE),
                remoteFilterConnection,
                Context.BIND_AUTO_CREATE,
            )
        }.getOrDefault(false)

        if (!bound) {
            val callbacks = synchronized(remoteFilterLock) {
                remoteFilterServiceBinding = false
                ArrayList(remoteFilterCallbacks).also { remoteFilterCallbacks.clear() }
            }
            callbacks.forEach { pending -> runCatching { pending(null) } }
        }
    }

    private fun registerPasswordVault(
        messenger: io.flutter.plugin.common.BinaryMessenger,
        context: Context,
    ) {
        MethodChannel(messenger, "com.tpeapp/password_vault").setMethodCallHandler { call, result ->
            val prefs = flutterPrefs(context)
            when (call.method) {
                "getEntries" -> result.success(getEntriesForFlutter(prefs))
                "revealPassword" -> {
                    val id = call.argument<String>("id")
                    result.success(revealPassword(prefs, id))
                }
                "addEntry" -> {
                    val id = UUID.randomUUID().toString()
                    val entry = JSONObject().apply {
                        put("id", id)
                        put("site", call.argument<String>("site") ?: "")
                        put("username", call.argument<String>("username") ?: "")
                        put("password", call.argument<String>("password") ?: "")
                        put("notes", call.argument<String>("notes") ?: "")
                        put("lockedUntil", 0L)
                    }
                    val items = loadVaultEntries(prefs)
                    items.put(entry)
                    saveVaultEntries(prefs, items)
                    result.success(id)
                }
                "updateEntry" -> {
                    val id = call.argument<String>("id")
                    val items = loadVaultEntries(prefs)
                    var updated = false
                    for (index in 0 until items.length()) {
                        val item = items.getJSONObject(index)
                        if (item.optString("id") != id) continue
                        updateIfPresent(call, "site") { item.put("site", it) }
                        updateIfPresent(call, "username") { item.put("username", it) }
                        updateIfPresent(call, "password") { item.put("password", it) }
                        updateIfPresent(call, "notes") { item.put("notes", it) }
                        updated = true
                        break
                    }
                    if (updated) saveVaultEntries(prefs, items)
                    result.success(updated)
                }
                "deleteEntry" -> {
                    val id = call.argument<String>("id")
                    val items = loadVaultEntries(prefs)
                    val filtered = JSONArray()
                    var deleted = false
                    for (index in 0 until items.length()) {
                        val item = items.getJSONObject(index)
                        if (item.optString("id") == id) {
                            deleted = true
                        } else {
                            filtered.put(item)
                        }
                    }
                    if (deleted) saveVaultEntries(prefs, filtered)
                    result.success(deleted)
                }
                "lockEntry" -> {
                    val id = call.argument<String>("id")
                    val durationMs = call.argument<Number>("durationMs")?.toLong() ?: 0L
                    val items = loadVaultEntries(prefs)
                    val until = System.currentTimeMillis() + durationMs
                    for (index in 0 until items.length()) {
                        val item = items.getJSONObject(index)
                        if (item.optString("id") == id) {
                            item.put("lockedUntil", until)
                            break
                        }
                    }
                    saveVaultEntries(prefs, items)
                    result.success(null)
                }
                "lockAll" -> {
                    val durationMs = call.argument<Number>("durationMs")?.toLong() ?: 0L
                    val until = System.currentTimeMillis() + durationMs
                    val items = loadVaultEntries(prefs)
                    for (index in 0 until items.length()) {
                        items.getJSONObject(index).put("lockedUntil", until)
                    }
                    saveVaultEntries(prefs, items)
                    result.success(null)
                }
                "importEntries" -> {
                    val rawEntries = call.argument<List<Map<String, Any?>>>("entries") ?: emptyList()
                    val items = loadVaultEntries(prefs)
                    val existingPairs = mutableSetOf<String>()
                    for (index in 0 until items.length()) {
                        val item = items.getJSONObject(index)
                        existingPairs += item.optString("site") + "\u0000" + item.optString("username")
                    }
                    var inserted = 0
                    for (raw in rawEntries) {
                        val site = raw["site"] as? String ?: continue
                        val username = raw["username"] as? String ?: continue
                        val password = raw["password"] as? String ?: continue
                        val key = site + "\u0000" + username
                        if (!existingPairs.add(key)) continue
                        items.put(JSONObject().apply {
                            put("id", UUID.randomUUID().toString())
                            put("site", site)
                            put("username", username)
                            put("password", password)
                            put("notes", raw["notes"] as? String ?: "")
                            put("lockedUntil", 0L)
                        })
                        inserted += 1
                    }
                    saveVaultEntries(prefs, items)
                    result.success(inserted)
                }
                else -> result.notImplemented()
            }
        }
    }

    private fun registerNoOpMethods(
        messenger: io.flutter.plugin.common.BinaryMessenger,
        channelName: String,
    ) {
        MethodChannel(messenger, channelName).setMethodCallHandler { _, result ->
            result.success(null)
        }
    }

    private fun flutterPrefs(context: Context) =
        context.getSharedPreferences(FLUTTER_PREFS, Context.MODE_PRIVATE)

    private fun flutterKey(key: String) = "flutter.$key"

    private fun loadVaultEntries(prefs: android.content.SharedPreferences): JSONArray {
        val raw = prefs.getString(flutterKey(VAULT_PREFS_KEY), null)
        return if (raw.isNullOrBlank()) JSONArray() else runCatching { JSONArray(raw) }.getOrElse { JSONArray() }
    }

    private fun saveVaultEntries(prefs: android.content.SharedPreferences, items: JSONArray) {
        prefs.edit().putString(flutterKey(VAULT_PREFS_KEY), items.toString()).apply()
    }

    private fun getEntriesForFlutter(prefs: android.content.SharedPreferences): List<Map<String, Any>> {
        val items = loadVaultEntries(prefs)
        val result = ArrayList<Map<String, Any>>(items.length())
        for (index in 0 until items.length()) {
            val item = items.getJSONObject(index)
            result += mapOf(
                "id" to item.optString("id"),
                "site" to item.optString("site"),
                "username" to item.optString("username"),
                "notes" to item.optString("notes"),
                "lockedUntil" to item.optLong("lockedUntil", 0L),
            )
        }
        return result
    }

    private fun revealPassword(prefs: android.content.SharedPreferences, id: String?): String? {
        if (id == null) return null
        val items = loadVaultEntries(prefs)
        for (index in 0 until items.length()) {
            val item = items.getJSONObject(index)
            if (item.optString("id") != id) continue
            if (item.optLong("lockedUntil", 0L) > System.currentTimeMillis()) return null
            return item.optString("password")
        }
        return null
    }

    private fun updateIfPresent(call: MethodCall, key: String, block: (String) -> Unit) {
        val value = call.argument<String>(key) ?: return
        block(value)
    }

    private fun isRootAvailable(): Boolean {
        cachedRootAvailable?.let { return it }
        val available = runCatching {
            val process = ProcessBuilder("su", "-c", "id")
                .redirectErrorStream(true)
                .start()
            val finished = process.waitFor(2, TimeUnit.SECONDS)
            if (!finished) {
                process.destroy()
                false
            } else {
                val output = process.inputStream.bufferedReader().use { it.readText() }
                process.exitValue() == 0 && output.contains("uid=0")
            }
        }.getOrDefault(false)
        cachedRootAvailable = available
        return available
    }

    private fun dispatchTapViaRoot(context: Context, normX: Float, normY: Float): Boolean {
        val dm = context.resources.displayMetrics
        val px = (normX.coerceIn(0f, 1f) * dm.widthPixels).toInt()
        val py = (normY.coerceIn(0f, 1f) * dm.heightPixels).toInt()

        return runCatching {
            val process = ProcessBuilder("su", "-c", "input tap $px $py")
                .redirectErrorStream(true)
                .start()
            val finished = process.waitFor(2, TimeUnit.SECONDS)
            if (!finished) {
                process.destroy()
                false
            } else {
                process.exitValue() == 0
            }
        }.onFailure { err ->
            Log.e(SCREEN_SHARE_TAG, "dispatchTapViaRoot failed", err)
        }.getOrDefault(false)
    }

    private fun runRootCommand(command: String): Boolean {
        if (!isRootAvailable()) return false
        return runCatching {
            val process = ProcessBuilder("su", "-c", command)
                .redirectErrorStream(true)
                .start()
            val finished = process.waitFor(2, TimeUnit.SECONDS)
            if (!finished) {
                process.destroy()
                false
            } else {
                process.exitValue() == 0
            }
        }.getOrDefault(false)
    }
}