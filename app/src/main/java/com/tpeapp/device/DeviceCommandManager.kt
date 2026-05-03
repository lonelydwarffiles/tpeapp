package com.tpeapp.device

import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.hardware.camera2.CameraManager
import android.location.LocationManager
import android.media.AudioManager
import android.media.MediaPlayer
import android.net.Uri
import android.speech.tts.TextToSpeech
import android.util.Log
import androidx.preference.PreferenceManager
import com.tpeapp.mdm.AppDeviceAdminReceiver
import com.tpeapp.mindful.MindfulNotificationService
import com.tpeapp.pairing.PairingActivity
import com.tpeapp.service.FilterService
import com.tpeapp.webhook.WebhookManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import org.json.JSONObject
import java.io.File
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * DeviceCommandManager — singleton that implements all FCM-driven remote device
 * control commands in one place.  Every public method is safe to call from a
 * background thread; privileged operations that require root use `su -c`.
 *
 * Categories covered:
 *  1. Screen & display (brightness, on/off, timeout, orientation, rotation)
 *  2. Audio & sound (volume streams, ringer mode, audio playback, TTS)
 *  3. Lock screen & access (lock, dismiss keyguard)
 *  4. Network & connectivity (Wi-Fi, mobile data, airplane mode, Bluetooth)
 *  5. Camera & sensors (screenshot, screen recording, flashlight, location)
 *  6. Notifications & interruptions (custom notification, clear all, DND, alarm)
 *  7. Device settings (wallpaper, NFC, font scale)
 *  8. App & system control (suspend / unsuspend apps)
 */
object DeviceCommandManager {

    private const val TAG = "DeviceCommandManager"

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val uploadClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .build()

    /** Reusable notification channel for partner command confirmations. */
    private const val CMD_CHANNEL_ID   = "tpe_device_commands"
    private const val CMD_NOTIF_ID     = 8001

    // ======================================================================
    //  1. Screen & Display
    // ======================================================================

    /**
     * Opens [url] in the default browser.  The intent uses the full-screen-intent
     * pattern via [OverlayActivity] so it can appear over the lock screen.
     */
    fun openUrl(context: Context, url: String) {
        val intent = Intent(context, OverlayActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            putExtra(OverlayActivity.EXTRA_OPEN_URL, url)
        }
        context.startActivity(intent)
        Log.i(TAG, "openUrl: $url")
    }

    /**
     * Sets the screen brightness via the root settings provider.
     * @param value 0–255 (0 = minimum, 255 = maximum)
     */
    fun setBrightness(value: Int) {
        val clamped = value.coerceIn(0, 255)
        execRoot("settings put system screen_brightness_mode 0")
        execRoot("settings put system screen_brightness $clamped")
        Log.i(TAG, "setBrightness: $clamped")
    }

    /** Wakes the screen via a root key event. */
    fun screenOn() = execRoot("input keyevent KEYCODE_WAKEUP")

    /**
     * Turns the screen off.  Tries [DevicePolicyManager.lockNow] first (works when
     * the app is a Device Admin); falls back to root power-key injection.
     */
    fun screenOff(context: Context) {
        val dpm  = context.getSystemService(DevicePolicyManager::class.java)
        val admin = ComponentName(context, AppDeviceAdminReceiver::class.java)
        if (dpm.isAdminActive(admin)) {
            dpm.lockNow()
        } else {
            execRoot("input keyevent KEYCODE_SLEEP")
        }
        Log.i(TAG, "screenOff")
    }

    /**
     * Sets the screen-off timeout.
     * @param ms Duration in milliseconds before the screen auto-locks.
     */
    fun setScreenTimeout(ms: Long) {
        execRoot("settings put system screen_off_timeout $ms")
        Log.i(TAG, "setScreenTimeout: ${ms}ms")
    }

    /**
     * Launches [OverlayActivity] to display a full-screen message over the lock screen.
     * @param imageUrl Optional HTTP/HTTPS URL for an image to display alongside the text.
     */
    fun showOverlay(context: Context, title: String, message: String, imageUrl: String?) {
        val intent = Intent(context, OverlayActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            putExtra(OverlayActivity.EXTRA_TITLE, title)
            putExtra(OverlayActivity.EXTRA_MESSAGE, message)
            imageUrl?.let { putExtra(OverlayActivity.EXTRA_IMAGE_URL, it) }
        }
        context.startActivity(intent)
        Log.i(TAG, "showOverlay: title='$title'")
    }

    /**
     * Forces the device into portrait or landscape via the root accelerometer override.
     * @param landscape `true` → landscape, `false` → portrait.
     */
    fun setOrientation(landscape: Boolean) {
        // 0 = portrait, 1 = landscape
        val value = if (landscape) 1 else 0
        execRoot("settings put system user_rotation $value")
        execRoot("settings put system accelerometer_rotation 0")
        Log.i(TAG, "setOrientation: landscape=$landscape")
    }

    /**
     * Enables or disables automatic screen rotation.
     * @param enabled `true` = auto-rotate on, `false` = locked.
     */
    fun setAutoRotate(enabled: Boolean) {
        val value = if (enabled) 1 else 0
        execRoot("settings put system accelerometer_rotation $value")
        Log.i(TAG, "setAutoRotate: $enabled")
    }

    // ======================================================================
    //  2. Audio & Sound
    // ======================================================================

    /**
     * Sets the volume for a named audio stream.
     * @param stream One of: `media`, `ring`, `alarm`, `notification`, `system`, `call`.
     * @param level  Volume level (0–100; clamped to the stream's actual max by AudioManager).
     * @param max    When `true`, [level] is ignored and volume is set to the stream maximum.
     */
    fun setVolume(context: Context, stream: String, level: Int, max: Boolean) {
        val am = context.getSystemService(AudioManager::class.java)
        val streamType = when (stream.lowercase()) {
            "media"        -> AudioManager.STREAM_MUSIC
            "ring"         -> AudioManager.STREAM_RING
            "alarm"        -> AudioManager.STREAM_ALARM
            "notification" -> AudioManager.STREAM_NOTIFICATION
            "system"       -> AudioManager.STREAM_SYSTEM
            "call"         -> AudioManager.STREAM_VOICE_CALL
            else           -> AudioManager.STREAM_MUSIC
        }
        val target = if (max) am.getStreamMaxVolume(streamType)
        else level.coerceIn(0, am.getStreamMaxVolume(streamType))
        am.setStreamVolume(streamType, target, 0)
        Log.i(TAG, "setVolume: stream=$stream level=$target")
    }

    /**
     * Sets the ringer mode.
     * @param mode One of: `normal`, `vibrate`, `silent`.
     */
    fun setRingerMode(context: Context, mode: String) {
        val am = context.getSystemService(AudioManager::class.java)
        val ringerMode = when (mode.lowercase()) {
            "normal"  -> AudioManager.RINGER_MODE_NORMAL
            "vibrate" -> AudioManager.RINGER_MODE_VIBRATE
            "silent"  -> AudioManager.RINGER_MODE_SILENT
            else      -> AudioManager.RINGER_MODE_NORMAL
        }
        am.ringerMode = ringerMode
        Log.i(TAG, "setRingerMode: $mode")
    }

    /** Streams an audio clip from [url] via [MediaPlayer], replacing any current playback. */
    @Volatile private var mediaPlayer: MediaPlayer? = null

    fun playAudio(url: String) {
        scope.launch {
            mediaPlayer?.release()
            mediaPlayer = null
            runCatching {
                val mp = MediaPlayer().apply {
                    setDataSource(url)
                    prepare()
                    start()
                }
                mediaPlayer = mp
                Log.i(TAG, "playAudio: $url")
            }.onFailure { e ->
                Log.e(TAG, "playAudio failed: $url", e)
            }
        }
    }

    /** Speaks [text] aloud using the device's text-to-speech engine. */
    @Volatile private var tts: TextToSpeech? = null
    @Volatile private var ttsReady = false

    fun speakText(context: Context, text: String) {
        if (tts != null && ttsReady) {
            tts!!.speak(text, TextToSpeech.QUEUE_FLUSH, null, "tpe_tts")
            Log.i(TAG, "speakText: '$text'")
            return
        }
        tts = TextToSpeech(context.applicationContext) { status ->
            ttsReady = (status == TextToSpeech.SUCCESS)
            if (ttsReady) {
                tts!!.language = Locale.getDefault()
                tts!!.speak(text, TextToSpeech.QUEUE_FLUSH, null, "tpe_tts")
                Log.i(TAG, "speakText (after TTS init): '$text'")
            } else {
                Log.e(TAG, "TTS initialization failed with status=$status")
            }
        }
    }

    // ======================================================================
    //  3. Lock Screen & Access
    // ======================================================================

    /**
     * Immediately locks the device.  Uses [DevicePolicyManager.lockNow] when the app
     * is a Device Admin, otherwise injects KEYCODE_SLEEP via root.
     */
    fun lockDevice(context: Context) {
        val dpm   = context.getSystemService(DevicePolicyManager::class.java)
        val admin = ComponentName(context, AppDeviceAdminReceiver::class.java)
        if (dpm.isAdminActive(admin)) {
            dpm.lockNow()
            Log.i(TAG, "lockDevice: via DevicePolicyManager")
        } else {
            execRoot("input keyevent KEYCODE_SLEEP")
            Log.i(TAG, "lockDevice: via root keyevent")
        }
    }

    /**
     * Launches [OverlayActivity] with the "dismiss keyguard" flag so content is
     * shown over the lock screen without requiring the user to unlock.
     */
    fun dismissKeyguard(context: Context) {
        val intent = Intent(context, OverlayActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            putExtra(OverlayActivity.EXTRA_DISMISS_KEYGUARD, true)
        }
        context.startActivity(intent)
        Log.i(TAG, "dismissKeyguard")
    }

    // ======================================================================
    //  4. Network & Connectivity
    // ======================================================================

    /** Enables or disables Wi-Fi via `svc wifi`. Requires root. */
    fun setWifi(enabled: Boolean) =
        execRoot("svc wifi ${if (enabled) "enable" else "disable"}")

    /** Enables or disables mobile data via `svc data`. Requires root. */
    fun setMobileData(enabled: Boolean) =
        execRoot("svc data ${if (enabled) "enable" else "disable"}")

    /** Enables or disables airplane mode via root settings + broadcast. Requires root. */
    fun setAirplaneMode(enabled: Boolean) {
        val value = if (enabled) 1 else 0
        execRoot(
            "settings put global airplane_mode_on $value && " +
            "am broadcast -a android.intent.action.AIRPLANE_MODE --ez state ${enabled}"
        )
        Log.i(TAG, "setAirplaneMode: $enabled")
    }

    /** Enables or disables Bluetooth via `svc bluetooth`. Requires root. */
    fun setBluetooth(enabled: Boolean) =
        execRoot("svc bluetooth ${if (enabled) "enable" else "disable"}")

    /**
     * Connects to a Wi-Fi network via the `cmd wifi connect-network` shell command.
     * Requires root.
     * @param password Leave blank or `null` for open networks.
     */
    fun connectWifi(ssid: String, password: String?) {
        val cmd = if (password.isNullOrBlank()) {
            "cmd wifi connect-network \"$ssid\" open"
        } else {
            "cmd wifi connect-network \"$ssid\" wpa2 \"$password\""
        }
        execRoot(cmd)
        Log.i(TAG, "connectWifi: ssid='$ssid'")
    }

    // ======================================================================
    //  5. Camera & Sensors
    // ======================================================================

    /**
     * Takes a screenshot via `screencap` and uploads it as a multipart file to
     * `$endpoint/api/tpe/upload`.
     */
    fun takeScreenshot(context: Context) {
        scope.launch {
            val path = "/data/local/tmp/tpe_ss_${System.currentTimeMillis()}.png"
            execRootSync("screencap -p $path")
            uploadFile(context, File(path), "image/png", "screenshot")
            execRootSync("rm -f $path")
        }
    }

    /**
     * Records a screen clip via `screenrecord` and uploads it.
     * @param durationSec Maximum recording length in seconds (1–30; clamped).
     */
    fun recordScreen(context: Context, durationSec: Int) {
        scope.launch {
            val dur  = durationSec.coerceIn(1, 30)
            val path = "/data/local/tmp/tpe_clip_${System.currentTimeMillis()}.mp4"
            execRootSync("screenrecord --time-limit $dur $path")
            // Give the file a moment to be flushed before we upload it.
            delay(1_000)
            uploadFile(context, File(path), "video/mp4", "screen_recording")
            execRootSync("rm -f $path")
        }
    }

    /** Turns the device flashlight on or off via [CameraManager.setTorchMode]. */
    fun setFlashlight(context: Context, enabled: Boolean) {
        runCatching {
            val cm = context.getSystemService(CameraManager::class.java)
            val cameraId = cm.cameraIdList.firstOrNull() ?: return
            cm.setTorchMode(cameraId, enabled)
            Log.i(TAG, "setFlashlight: $enabled")
        }.onFailure { e ->
            Log.e(TAG, "setFlashlight failed", e)
        }
    }

    /**
     * Reads the last-known device location (GPS then network) and dispatches it to
     * the webhook as a `device_location` event.
     */
    @SuppressLint("MissingPermission")
    fun getLocation(context: Context) {
        scope.launch {
            runCatching {
                val lm = context.getSystemService(LocationManager::class.java)
                val loc = lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                    ?: lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)

                val prefs      = PreferenceManager.getDefaultSharedPreferences(context)
                val webhookUrl = prefs.getString(FilterService.PREF_WEBHOOK_URL, null)
                    ?.takeIf { it.isNotBlank() } ?: return@runCatching
                val bearer     = prefs.getString(FilterService.PREF_WEBHOOK_BEARER_TOKEN, null)
                    ?.takeIf { it.isNotBlank() }

                val payload = JSONObject().apply {
                    put("event", "device_location")
                    put("timestamp", System.currentTimeMillis())
                    if (loc != null) {
                        put("latitude",  loc.latitude)
                        put("longitude", loc.longitude)
                        put("accuracy",  loc.accuracy)
                        put("provider",  loc.provider)
                    } else {
                        put("error", "location_unavailable")
                    }
                }
                WebhookManager.dispatchEvent(webhookUrl, bearer, payload)
                Log.i(TAG, "getLocation: dispatched lat=${loc?.latitude} lon=${loc?.longitude}")
            }.onFailure { e ->
                Log.e(TAG, "getLocation failed", e)
            }
        }
    }

    // ======================================================================
    //  6. Notifications & Interruptions
    // ======================================================================

    /**
     * Posts a custom local notification on behalf of the partner.
     * @param channelId Optional override; falls back to [CMD_CHANNEL_ID].
     */
    fun sendNotification(context: Context, title: String, body: String, channelId: String?) {
        val nm      = context.getSystemService(NotificationManager::class.java)
        val channel = channelId?.takeIf { it.isNotBlank() } ?: CMD_CHANNEL_ID
        ensureCmdChannel(nm)
        val notification = androidx.core.app.NotificationCompat.Builder(context, channel)
            .setSmallIcon(com.tpeapp.R.drawable.ic_shield)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(androidx.core.app.NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(androidx.core.app.NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()
        nm.notify(CMD_NOTIF_ID + title.hashCode() and 0x0FFF, notification)
        Log.i(TAG, "sendNotification: '$title'")
    }

    /**
     * Cancels all current notifications via [MindfulNotificationService] if the
     * listener is connected; falls back to a root broadcast.
     */
    fun clearNotifications(context: Context) {
        if (MindfulNotificationService.clearAll()) {
            Log.i(TAG, "clearNotifications: via NotificationListenerService")
        } else {
            Log.w(TAG, "clearNotifications: listener not connected — skipped")
        }
    }

    /**
     * Sets the device Do-Not-Disturb interruption filter.
     * @param policy One of: `all` (DND off), `priority`, `alarms`, `none` (total silence).
     */
    fun setDnd(context: Context, policy: String) {
        val nm = context.getSystemService(NotificationManager::class.java)
        val filter = when (policy.lowercase()) {
            "all"      -> NotificationManager.INTERRUPTION_FILTER_ALL
            "priority" -> NotificationManager.INTERRUPTION_FILTER_PRIORITY
            "alarms"   -> NotificationManager.INTERRUPTION_FILTER_ALARMS
            "none"     -> NotificationManager.INTERRUPTION_FILTER_NONE
            else       -> NotificationManager.INTERRUPTION_FILTER_ALL
        }
        if (nm.isNotificationPolicyAccessGranted) {
            nm.setInterruptionFilter(filter)
            Log.i(TAG, "setDnd: policy=$policy filter=$filter")
        } else {
            Log.w(TAG, "setDnd: Notification Policy access not granted — cannot change DND")
        }
    }

    /**
     * Creates an alarm via the system clock app.
     * @param title  Label shown on the alarm.
     * @param timeMs Epoch-millisecond time when the alarm should fire.
     */
    fun setAlarm(context: Context, title: String, timeMs: Long) {
        val cal = java.util.Calendar.getInstance().apply { timeInMillis = timeMs }
        val intent = Intent(AlarmManager.ACTION_SET_ALARM).apply {
            putExtra(AlarmManager.EXTRA_HOUR,    cal.get(java.util.Calendar.HOUR_OF_DAY))
            putExtra(AlarmManager.EXTRA_MINUTES, cal.get(java.util.Calendar.MINUTE))
            putExtra(AlarmManager.EXTRA_MESSAGE, title)
            putExtra(AlarmManager.EXTRA_SKIP_UI, true)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
        Log.i(TAG, "setAlarm: '$title' at $timeMs")
    }

    // ======================================================================
    //  7. Device Settings
    // ======================================================================

    /**
     * Downloads an image from [url] and sets it as the home + lock screen wallpaper.
     */
    fun setWallpaper(context: Context, url: String) {
        scope.launch {
            runCatching {
                val response = uploadClient.newCall(
                    Request.Builder().url(url).get().build()
                ).execute()
                response.use { resp ->
                    if (!resp.isSuccessful) {
                        Log.w(TAG, "setWallpaper: failed to download $url (${resp.code})")
                        return@runCatching
                    }
                    val bytes = resp.body?.bytes() ?: return@runCatching
                    val bm = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                        ?: return@runCatching
                    val wm = android.app.WallpaperManager.getInstance(context)
                    wm.setBitmap(bm)
                    Log.i(TAG, "setWallpaper: set from $url")
                }
            }.onFailure { e ->
                Log.e(TAG, "setWallpaper failed", e)
            }
        }
    }

    /** Enables or disables NFC via `svc nfc`. Requires root. */
    fun setNfc(enabled: Boolean) =
        execRoot("svc nfc ${if (enabled) "enable" else "disable"}")

    /**
     * Changes the system font scale.
     * @param scale Typical values: 0.85 (small), 1.0 (normal), 1.15 (large), 1.3 (largest).
     */
    fun setFontSize(scale: Float) {
        val clamped = scale.coerceIn(0.5f, 2.0f)
        execRoot("settings put system font_scale $clamped")
        Log.i(TAG, "setFontSize: scale=$clamped")
    }

    // ======================================================================
    //  8. App & System Control
    // ======================================================================

    /**
     * Suspends the app by package name via `pm suspend`.
     * A suspended app's icon is greyed out and it cannot be launched by the user.
     * Requires root.
     */
    fun suspendApp(packageName: String) =
        execRoot("pm suspend --user 0 $packageName")

    /**
     * Lifts a suspension applied by [suspendApp] via `pm unsuspend`. Requires root.
     */
    fun unsuspendApp(packageName: String) =
        execRoot("pm unsuspend --user 0 $packageName")

    // ======================================================================
    //  Private helpers
    // ======================================================================

    /** Enqueues a root command on the IO dispatcher; callers are never blocked. */
    private fun execRoot(command: String) {
        scope.launch { execRootSync(command) }
    }

    /** Runs a root command synchronously; must be called from a coroutine. */
    private fun execRootSync(command: String) {
        runCatching {
            val process = ProcessBuilder("su", "-c", command)
                .redirectErrorStream(true)
                .start()
            process.waitFor()
            Log.i(TAG, "Root cmd: [$command] exit=${process.exitValue()}")
        }.onFailure { e ->
            Log.e(TAG, "Root cmd failed: [$command]", e)
        }
    }

    /**
     * Uploads [file] to `$endpoint/api/tpe/upload` as a multipart POST.
     * Uses the same webhook bearer token as all other outbound requests.
     * [type] is the MIME type (e.g. `image/png` or `video/mp4`).
     * [kind] is a plain-text label field sent alongside the file (e.g. `screenshot`).
     */
    private suspend fun uploadFile(context: Context, file: File, type: String, kind: String) {
        if (!file.exists()) { Log.w(TAG, "uploadFile: file not found: ${file.path}"); return }
        val prefs    = PreferenceManager.getDefaultSharedPreferences(context)
        val endpoint = prefs.getString(PairingActivity.PREF_PARTNER_ENDPOINT, null)
            ?.takeIf { it.isNotBlank() } ?: run {
            Log.w(TAG, "uploadFile: partner endpoint not set"); return
        }
        val bearer   = prefs.getString(FilterService.PREF_WEBHOOK_BEARER_TOKEN, null)
            ?.takeIf { it.isNotBlank() }
        val deviceId = prefs.getString("device_id", null)?.takeIf { it.isNotBlank() }

        val requestBody = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("file", file.name, file.asRequestBody(type.toMediaType()))
            .addFormDataPart("kind", kind)
            .addFormDataPart("timestamp", System.currentTimeMillis().toString())
            .build()

        val requestBuilder = Request.Builder()
            .url("$endpoint/api/tpe/upload")
            .post(requestBody)
        if (bearer != null) requestBuilder.addHeader("Authorization", "Bearer $bearer")
        if (deviceId != null) requestBuilder.addHeader("X-Device-ID", deviceId)

        runCatching {
            val resp = uploadClient.newCall(requestBuilder.build()).execute()
            resp.use {
                if (it.isSuccessful) Log.i(TAG, "uploadFile: $kind uploaded (${it.code})")
                else Log.w(TAG, "uploadFile: $kind upload failed (${it.code})")
            }
        }.onFailure { e ->
            Log.e(TAG, "uploadFile: $kind upload error", e)
        }
    }

    private fun ensureCmdChannel(nm: NotificationManager) {
        if (nm.getNotificationChannel(CMD_CHANNEL_ID) != null) return
        nm.createNotificationChannel(
            NotificationChannel(
                CMD_CHANNEL_ID,
                "Partner Device Commands",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Notifications triggered by partner device commands"
            }
        )
    }
}
