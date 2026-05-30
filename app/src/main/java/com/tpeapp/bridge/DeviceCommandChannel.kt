package com.tpeapp.bridge

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.location.LocationManager
import android.os.BatteryManager
import android.util.ArrayMap
import android.util.Log
import com.tpeapp.apps.AppInventoryManager
import com.tpeapp.device.DeviceCommandManager
import com.tpeapp.handler.ChatRepository
import com.tpeapp.handler.HandlerChatActivity
import com.tpeapp.vpn.VpnPolicyManager
import io.flutter.plugin.common.BinaryMessenger
import io.flutter.plugin.common.MethodChannel

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

    fun register(messenger: BinaryMessenger, context: Context) {
        val ctx = context.applicationContext

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
                        clipboard.setPrimaryClip(ClipData.newPlainText("Handler Clipboard", text))
                        result.success(null)
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
