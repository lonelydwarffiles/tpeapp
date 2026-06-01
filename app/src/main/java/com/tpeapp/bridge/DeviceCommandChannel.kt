package com.tpeapp.bridge

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.location.LocationManager
import android.os.BatteryManager
import android.provider.Settings
import android.util.ArrayMap
import android.util.Log
import androidx.preference.PreferenceManager
import com.tpeapp.apps.AppInventoryManager
import com.tpeapp.device.DeviceCommandManager
import com.tpeapp.handler.ChatRepository
import com.tpeapp.handler.HandlerChatActivity
import com.tpeapp.mqtt.PartnerMqttService
import com.tpeapp.pairing.PairingActivity
import com.tpeapp.service.FilterService
import com.tpeapp.service.CoreServiceKeeper
import com.tpeapp.vpn.VpnPolicyManager
import io.flutter.plugin.common.BinaryMessenger
import io.flutter.plugin.common.MethodChannel
import org.json.JSONArray
import org.json.JSONObject

/**
 * DeviceCommandChannel — MethodChannel bridge for [DeviceCommandManager].
 *
 * Channel name: `com.tpeapp/device_commands`
 *
 * Exposes every privileged device command to Dart so the Flutter settings /
 * admin screens can invoke them directly instead of waiting for an FCM push.
 *
 * All commands are executed on the background thread used internally by
 * [DeviceCommandManager] — the MethodChannel result is returned immediately
 * after enqueuing (fire-and-forget model matching FCM behaviour).
 *
 * Methods exposed to Dart (all return null on success):
 *  - `openUrl`           (url: String)
 *  - `setBrightness`     (level: Int)               — brightness value 0–255
 *  - `screenOn`
 *  - `screenOff`
 *  - `setScreenTimeout`  (ms: Long)                 — timeout in milliseconds
 *  - `setVolume`         (stream: String, level: Int, max: Boolean)
 *                                                   — stream: music|ring|alarm|notification|system|voice_call
 *  - `setRingerMode`     (mode: String)             — silent|vibrate|normal
 *  - `speakText`         (text: String)
 *  - `playAudio`         (url: String, loop: Boolean)
 *                                                   — loop=true plays over other media continuously
 *  - `stopAudio`                                    — stops any active playAudio clip
 *  - `lockDevice`
 *  - `takeScreenshot`
 *  - `setFlashlight`     (on: Boolean)
 *  - `getLocation`
 *  - `sendNotification`  (title: String, body: String, channelId: String?)
 *  - `setDnd`            (policy: String)           — total_silence|priority|alarms_only|all
 *  - `setWallpaper`      (url: String)
 *  - `showOverlay`       (title: String, message: String, imageUrl: String?)
 *  - `suspendApp`        (packageName: String)
 *  - `unsuspendApp`      (packageName: String)
 */
object DeviceCommandChannel {

    private const val TAG = "DeviceCommandChannel"
    private const val CHANNEL = "com.hound.controller/device_commands"
    private const val PREF_PENDING_SHARE_PAYLOAD = "pending_share_payload_json"
    @Volatile
    private var pendingSharePayload: Map<String, Any?>? = null

    fun captureIncomingShareIntent(context: Context, intent: Intent?) {
        if (intent == null) return
        val action = intent.action ?: return
        if (action != Intent.ACTION_SEND && action != Intent.ACTION_SEND_MULTIPLE) {
            return
        }

        val type = intent.type?.trim().orEmpty()
        val subject = intent.getStringExtra(Intent.EXTRA_SUBJECT)?.trim().orEmpty()
        val packageName = intent.getStringExtra(Intent.EXTRA_PACKAGE_NAME)?.trim().orEmpty()
        val textParts = mutableListOf<String>()
        val streamUris = mutableListOf<String>()

        val directText = intent.getStringExtra(Intent.EXTRA_TEXT)?.trim().orEmpty()
        if (directText.isNotEmpty()) {
            textParts += directText
        }

        val clip = intent.clipData
        if (clip != null) {
            for (index in 0 until clip.itemCount) {
                val item = clip.getItemAt(index)
                val itemText = item.text?.toString()?.trim().orEmpty()
                if (itemText.isNotEmpty()) {
                    textParts += itemText
                }
                val itemUri = item.uri?.toString()?.trim().orEmpty()
                if (itemUri.isNotEmpty()) {
                    streamUris += itemUri
                }
            }
        }

        val stream = intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
        if (stream != null) {
            streamUris += stream.toString()
        }

        val streams = intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)
        if (streams != null) {
            for (uri in streams) {
                if (uri != null) {
                    streamUris += uri.toString()
                }
            }
        }

        val mergedText = textParts
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
            .joinToString("\n")

        if (mergedText.isBlank() && subject.isBlank() && streamUris.isEmpty()) {
            return
        }

        val payload = mapOf(
            "action" to action,
            "mime_type" to type,
            "subject" to subject,
            "text" to mergedText,
            "source_package" to packageName,
            "stream_uris" to streamUris,
        )
        pendingSharePayload = payload
        persistPendingSharePayload(context.applicationContext, payload)
    }

    private fun persistPendingSharePayload(context: Context, payload: Map<String, Any?>) {
        runCatching {
            val json = JSONObject().apply {
                put("action", payload["action"]?.toString().orEmpty())
                put("mime_type", payload["mime_type"]?.toString().orEmpty())
                put("subject", payload["subject"]?.toString().orEmpty())
                put("text", payload["text"]?.toString().orEmpty())
                put("source_package", payload["source_package"]?.toString().orEmpty())
                put(
                    "stream_uris",
                    JSONArray((payload["stream_uris"] as? List<*>)?.map { it?.toString().orEmpty() } ?: emptyList<String>())
                )
            }
            PreferenceManager.getDefaultSharedPreferences(context)
                .edit()
                .putString(PREF_PENDING_SHARE_PAYLOAD, json.toString())
                .apply()
        }.onFailure { err ->
            Log.w(TAG, "Failed to persist pending share payload", err)
        }
    }

    private fun loadPersistedPendingSharePayload(context: Context): Map<String, Any?>? {
        val raw = PreferenceManager.getDefaultSharedPreferences(context)
            .getString(PREF_PENDING_SHARE_PAYLOAD, null)
            ?.trim()
            .orEmpty()
        if (raw.isEmpty()) {
            return null
        }
        return runCatching {
            val json = JSONObject(raw)
            val streamUris = mutableListOf<String>()
            val array = json.optJSONArray("stream_uris")
            if (array != null) {
                for (index in 0 until array.length()) {
                    val value = array.optString(index).trim()
                    if (value.isNotEmpty()) {
                        streamUris += value
                    }
                }
            }
            mapOf(
                "action" to json.optString("action").trim(),
                "mime_type" to json.optString("mime_type").trim(),
                "subject" to json.optString("subject").trim(),
                "text" to json.optString("text").trim(),
                "source_package" to json.optString("source_package").trim(),
                "stream_uris" to streamUris,
            )
        }.getOrElse { err ->
            Log.w(TAG, "Failed to read persisted pending share payload", err)
            null
        }
    }

    private fun clearPersistedPendingSharePayload(context: Context) {
        PreferenceManager.getDefaultSharedPreferences(context)
            .edit()
            .remove(PREF_PENDING_SHARE_PAYLOAD)
            .apply()
    }

    private fun consumePendingSharePayload(context: Context): Map<String, Any?>? {
        val payload = pendingSharePayload ?: loadPersistedPendingSharePayload(context.applicationContext)
        pendingSharePayload = null
        clearPersistedPendingSharePayload(context.applicationContext)
        return payload
    }

    private fun syncCorePrefsFromFlutter(context: Context, values: Map<String, Any?>?) {
        if (values.isNullOrEmpty()) return

        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val editor = prefs.edit()

        fun putStringIfPresent(key: String, value: Any?) {
            val text = value?.toString()?.trim().orEmpty()
            if (text.isNotEmpty()) {
                editor.putString(key, text)
            }
        }

        fun putBooleanIfPresent(key: String, value: Any?) {
            when (value) {
                is Boolean -> editor.putBoolean(key, value)
                is String -> {
                    val normalized = value.trim().lowercase()
                    if (normalized == "true" || normalized == "1") editor.putBoolean(key, true)
                    if (normalized == "false" || normalized == "0") editor.putBoolean(key, false)
                }
                is Number -> editor.putBoolean(key, value.toInt() != 0)
            }
        }

        putBooleanIfPresent(PairingActivity.PREF_IS_PAIRED, values[PairingActivity.PREF_IS_PAIRED])
        putStringIfPresent(PairingActivity.PREF_PARTNER_ENDPOINT, values[PairingActivity.PREF_PARTNER_ENDPOINT])
        putStringIfPresent(FilterService.PREF_WEBHOOK_URL, values[FilterService.PREF_WEBHOOK_URL])
        putStringIfPresent(FilterService.PREF_WEBHOOK_BEARER_TOKEN, values[FilterService.PREF_WEBHOOK_BEARER_TOKEN])
        putStringIfPresent(PartnerMqttService.PREF_MQTT_BROKER_URI, values[PartnerMqttService.PREF_MQTT_BROKER_URI])
        putStringIfPresent(PartnerMqttService.PREF_MQTT_USERNAME, values[PartnerMqttService.PREF_MQTT_USERNAME])
        putStringIfPresent(PartnerMqttService.PREF_MQTT_PASSWORD, values[PartnerMqttService.PREF_MQTT_PASSWORD])
        putStringIfPresent(PartnerMqttService.PREF_MQTT_CLIENT_ID, values[PartnerMqttService.PREF_MQTT_CLIENT_ID])
        putStringIfPresent(PartnerMqttService.PREF_MQTT_TOPIC_PREFIX, values[PartnerMqttService.PREF_MQTT_TOPIC_PREFIX])
        putStringIfPresent("device_id", values["device_id"])

        editor.apply()

        // Re-assert native foreground services immediately after syncing state.
        CoreServiceKeeper.ensureCoreServicesRunning(context.applicationContext, "flutter_pref_sync")
    }

    fun register(messenger: BinaryMessenger, context: Context) {
        val ctx = context

        MethodChannel(messenger, CHANNEL).setMethodCallHandler { call, result ->
            try {
                when (call.method) {
                    "openUrl" -> {
                        val url = call.argument<String>("url")
                            ?: return@setMethodCallHandler result.error("INVALID", "url required", null)
                        DeviceCommandManager.openUrl(ctx, url)
                        result.success(null)
                    }
                    "setBrightness" -> {
                        val level = call.argument<Int>("level")
                            ?: return@setMethodCallHandler result.error("INVALID", "level required", null)
                        DeviceCommandManager.setBrightness(level)
                        result.success(null)
                    }
                    "screenOn"  -> { DeviceCommandManager.screenOn();     result.success(null) }
                    "screenOff" -> { DeviceCommandManager.screenOff(ctx); result.success(null) }
                    "setScreenTimeout" -> {
                        // Dart passes milliseconds; underlying manager also uses ms
                        val ms = call.argument<Int>("ms")?.toLong()
                            ?: return@setMethodCallHandler result.error("INVALID", "ms required", null)
                        DeviceCommandManager.setScreenTimeout(ms)
                        result.success(null)
                    }
                    "setVolume" -> {
                        // stream: "music"|"ring"|"alarm"|"notification"|"system"|"voice_call"
                        val stream = call.argument<String>("stream") ?: "music"
                        val level  = call.argument<Int>("level")     ?: 0
                        val max    = call.argument<Boolean>("max")   ?: false
                        DeviceCommandManager.setVolume(ctx, stream, level, max)
                        result.success(null)
                    }
                    "setRingerMode" -> {
                        // mode: "silent"|"vibrate"|"normal"
                        val mode = call.argument<String>("mode")
                            ?: return@setMethodCallHandler result.error("INVALID", "mode required", null)
                        DeviceCommandManager.setRingerMode(ctx, mode)
                        result.success(null)
                    }
                    "speakText" -> {
                        val text = call.argument<String>("text")
                            ?: return@setMethodCallHandler result.error("INVALID", "text required", null)
                        DeviceCommandManager.speakText(ctx, text)
                        result.success(null)
                    }
                    "playAudio" -> {
                        val url = call.argument<String>("url")
                            ?: return@setMethodCallHandler result.error("INVALID", "url required", null)
                        val loop = call.argument<Boolean>("loop") ?: false
                        DeviceCommandManager.playAudio(url, loop)
                        result.success(null)
                    }
                    "stopAudio" -> {
                        DeviceCommandManager.stopAudio()
                        result.success(null)
                    }
                    "lockDevice"     -> { DeviceCommandManager.lockDevice(ctx);     result.success(null) }
                    "takeScreenshot" -> { DeviceCommandManager.takeScreenshot(ctx); result.success(null) }
                    "setFlashlight"  -> {
                        val on = call.argument<Boolean>("on") ?: false
                        DeviceCommandManager.setFlashlight(ctx, on)
                        result.success(null)
                    }
                    "getLocation"    -> { DeviceCommandManager.getLocation(ctx);    result.success(null) }
                    "getDeviceSnapshot" -> {
                        val payload = ArrayMap<String, Any?>()

                        runCatching {
                            val bm = ctx.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
                            val batteryPct = bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
                            if (batteryPct != null && batteryPct >= 0) {
                                payload["battery_pct"] = batteryPct.coerceIn(0, 100)
                            }
                        }

                        runCatching {
                            val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
                            val loc = lm?.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                                ?: lm?.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
                            if (loc != null) {
                                payload["lat"] = loc.latitude
                                payload["lon"] = loc.longitude
                                payload["accuracy_m"] = loc.accuracy.toDouble()
                                payload["provider"] = (loc.provider ?: "unknown")
                                payload["timestamp_ms"] = loc.time
                            }
                        }

                        result.success(payload)
                    }
                    "getStableDeviceId" -> {
                        val androidId = runCatching {
                            Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ANDROID_ID)
                        }.getOrNull()?.trim().orEmpty()

                        if (androidId.isNotBlank()) {
                            result.success("android-$androidId")
                        } else {
                            result.error("UNAVAILABLE", "stable device id unavailable", null)
                        }
                    }
                    "consumePendingSharePayload" -> {
                        result.success(consumePendingSharePayload(ctx))
                    }
                    "syncCorePrefs" -> {
                        @Suppress("UNCHECKED_CAST")
                        val values = call.argument<Map<String, Any?>>("values")
                        syncCorePrefsFromFlutter(ctx, values)
                        result.success(null)
                    }
                    "sendNotification" -> {
                        val title     = call.argument<String>("title")     ?: ""
                        val body      = call.argument<String>("body")      ?: ""
                        val channelId = call.argument<String>("channelId")
                        DeviceCommandManager.sendNotification(ctx, title, body, channelId)
                        result.success(null)
                    }
                    "setDnd" -> {
                        // policy: "total_silence"|"priority"|"alarms_only"|"all"
                        val policy = call.argument<String>("policy") ?: "all"
                        DeviceCommandManager.setDnd(ctx, policy)
                        result.success(null)
                    }
                    "setWallpaper" -> {
                        // Supports legacy single-URL call and new per-surface targeting.
                        //   url     (String) — backward-compat single image for both surfaces
                        //   homeUrl (String?) — home-screen image (overrides url for home)
                        //   lockUrl (String?) — lock-screen image (overrides url for lock)
                        //   target  (String)  — "home" | "lock" | "both" (default "both")
                        val url     = call.argument<String>("url")
                        val homeUrl = call.argument<String>("homeUrl") ?: url
                        val lockUrl = call.argument<String>("lockUrl")
                        val target  = call.argument<String>("target") ?: "both"
                        if (homeUrl == null && lockUrl == null) {
                            return@setMethodCallHandler result.error("INVALID", "url or homeUrl required", null)
                        }
                        DeviceCommandManager.setWallpaper(ctx, homeUrl, lockUrl, target)
                        result.success(null)
                    }
                    "showOverlay" -> {
                        val title    = call.argument<String>("title")    ?: ""
                        val message  = call.argument<String>("message")  ?: ""
                        val imageUrl = call.argument<String>("imageUrl")
                        DeviceCommandManager.showOverlay(ctx, title, message, imageUrl)
                        result.success(null)
                    }
                    "suspendApp" -> {
                        val pkg = call.argument<String>("packageName")
                            ?: return@setMethodCallHandler result.error("INVALID", "packageName required", null)
                        DeviceCommandManager.suspendApp(pkg)
                        result.success(null)
                    }
                    "unsuspendApp" -> {
                        val pkg = call.argument<String>("packageName")
                            ?: return@setMethodCallHandler result.error("INVALID", "packageName required", null)
                        DeviceCommandManager.unsuspendApp(pkg)
                        result.success(null)
                    }
                    "openAppByName" -> {
                        val appName = call.argument<String>("appName")
                            ?: return@setMethodCallHandler result.error("INVALID", "appName required", null)
                        val pkg = AppInventoryManager.resolvePackageName(ctx, appName)
                            ?: return@setMethodCallHandler result.error("NOT_FOUND", "No installed app matched '$appName'", null)
                        AppInventoryManager.openApp(ctx, pkg)
                        result.success(null)
                    }
                    "forceStopAppByName" -> {
                        val appName = call.argument<String>("appName")
                            ?: return@setMethodCallHandler result.error("INVALID", "appName required", null)
                        val pkg = AppInventoryManager.resolvePackageName(ctx, appName)
                            ?: return@setMethodCallHandler result.error("NOT_FOUND", "No installed app matched '$appName'", null)
                        AppInventoryManager.forceStopApp(pkg)
                        result.success(null)
                    }
                    "disableAppByName" -> {
                        val appName = call.argument<String>("appName")
                            ?: return@setMethodCallHandler result.error("INVALID", "appName required", null)
                        val pkg = AppInventoryManager.resolvePackageName(ctx, appName)
                            ?: return@setMethodCallHandler result.error("NOT_FOUND", "No installed app matched '$appName'", null)
                        AppInventoryManager.disableApp(pkg)
                        result.success(null)
                    }
                    "enableAppByName" -> {
                        val appName = call.argument<String>("appName")
                            ?: return@setMethodCallHandler result.error("INVALID", "appName required", null)
                        val pkg = AppInventoryManager.resolvePackageName(ctx, appName)
                            ?: return@setMethodCallHandler result.error("NOT_FOUND", "No installed app matched '$appName'", null)
                        AppInventoryManager.enableApp(pkg)
                        result.success(null)
                    }
                    "clearAppCacheByName" -> {
                        val appName = call.argument<String>("appName")
                            ?: return@setMethodCallHandler result.error("INVALID", "appName required", null)
                        val pkg = AppInventoryManager.resolvePackageName(ctx, appName)
                            ?: return@setMethodCallHandler result.error("NOT_FOUND", "No installed app matched '$appName'", null)
                        AppInventoryManager.clearAppCache(pkg)
                        result.success(null)
                    }
                    "uninstallAppByName" -> {
                        val appName = call.argument<String>("appName")
                            ?: return@setMethodCallHandler result.error("INVALID", "appName required", null)
                        val pkg = AppInventoryManager.resolvePackageName(ctx, appName)
                            ?: return@setMethodCallHandler result.error("NOT_FOUND", "No installed app matched '$appName'", null)
                        AppInventoryManager.uninstallApp(pkg)
                        result.success(null)
                    }
                    "setClipboard" -> {
                        val text = call.argument<String>("text")
                            ?: return@setMethodCallHandler result.error("INVALID", "text required", null)
                        val clipboard = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                            ?: return@setMethodCallHandler result.error("UNAVAILABLE", "clipboard service missing", null)
                        runCatching {
                            clipboard.setPrimaryClip(ClipData.newPlainText("Handler Clipboard", text))
                        }.onSuccess {
                            result.success(null)
                        }.onFailure { error ->
                            result.error(
                                "SET_CLIPBOARD_FAILED",
                                error.message ?: "clipboard write failed",
                                null,
                            )
                        }
                    }
                    "openHandlerChat" -> {
                        val threadId = call.argument<String>("threadId")
                            ?.takeIf { it.isNotBlank() }
                            ?: ChatRepository.DEFAULT_THREAD_ID
                        val intent = HandlerChatActivity.createChatIntent(ctx, threadId).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        ctx.startActivity(intent)
                        result.success(null)
                    }
                    "uploadAppInventory" -> {
                        val includeSystem = call.argument<Boolean>("includeSystem") ?: true
                        val fullSnapshot = call.argument<Boolean>("fullSnapshot") ?: true
                        val pollId = call.argument<String>("pollId")
                        val source = call.argument<String>("source") ?: "ws_fallback"
                        AppInventoryManager.uploadInventorySnapshot(
                            context = ctx,
                            pollId = pollId,
                            includeSystem = includeSystem,
                            fullSnapshot = fullSnapshot,
                            source = source,
                        )
                        result.success(null)
                    }
                    "setVpnPolicy" -> {
                        val vpnPolicyJson = call.argument<String>("vpnPolicyJson")
                        val providerMode = call.argument<String>("providerMode")
                        VpnPolicyManager.setPolicy(
                            context = ctx,
                            policyJson = vpnPolicyJson,
                            providerMode = providerMode,
                        )
                        result.success(null)
                    }
                    "setVpnProviderProfile" -> {
                        val providerMode = call.argument<String>("providerMode")
                        val vpnProfileId = call.argument<String>("vpnProfileId")
                        val vpnPolicyJson = call.argument<String>("vpnPolicyJson")
                        VpnPolicyManager.setProviderProfile(
                            context = ctx,
                            providerMode = providerMode,
                            profileId = vpnProfileId,
                            policyJson = vpnPolicyJson,
                        )
                        result.success(null)
                    }
                    "vpnConnect" -> {
                        VpnPolicyManager.requestConnect(ctx)
                        result.success(null)
                    }
                    "vpnDisconnect" -> {
                        VpnPolicyManager.requestDisconnect(ctx)
                        result.success(null)
                    }
                    "getVpnStatus" -> {
                        val payload = ArrayMap<String, Any?>()
                        payload.putAll(VpnPolicyManager.statusSnapshot(ctx))
                        result.success(payload)
                    }
                    else -> result.notImplemented()
                }
            } catch (e: Exception) {
                Log.e(TAG, "DeviceCommand failed: ${call.method}", e)
                result.error("CMD_ERROR", e.message, null)
            }
        }
    }
}
