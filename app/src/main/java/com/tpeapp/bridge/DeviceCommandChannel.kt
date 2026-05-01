package com.tpeapp.bridge

import android.content.Context
import android.util.Log
import com.tpeapp.device.DeviceCommandManager
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
    private const val CHANNEL = "com.tpeapp/device_commands"

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
                    "lockDevice"     -> { DeviceCommandManager.lockDevice(ctx);     result.success(null) }
                    "takeScreenshot" -> { DeviceCommandManager.takeScreenshot(ctx); result.success(null) }
                    "setFlashlight"  -> {
                        val on = call.argument<Boolean>("on") ?: false
                        DeviceCommandManager.setFlashlight(ctx, on)
                        result.success(null)
                    }
                    "getLocation"    -> { DeviceCommandManager.getLocation(ctx);    result.success(null) }
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
                        val url = call.argument<String>("url")
                            ?: return@setMethodCallHandler result.error("INVALID", "url required", null)
                        DeviceCommandManager.setWallpaper(ctx, url)
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
                    else -> result.notImplemented()
                }
            } catch (e: Exception) {
                Log.e(TAG, "DeviceCommand failed: ${call.method}", e)
                result.error("CMD_ERROR", e.message, null)
            }
        }
    }
}
