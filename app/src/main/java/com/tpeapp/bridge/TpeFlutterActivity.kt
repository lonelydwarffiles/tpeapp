package com.tpeapp.bridge

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import com.tpeapp.fcm.PartnerFcmService
import com.tpeapp.service.FilterService
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine

/**
 * TpeFlutterActivity — the Flutter host activity for the TPE app.
 *
 * Replaces the old Jetpack-only [com.tpeapp.ui.MainActivity].  All UI is now
 * rendered by the Flutter engine; the native Kotlin layer exposes the following
 * services to Dart via MethodChannels:
 *
 *  | Channel                          | Bridge class                  |
 *  |----------------------------------|-------------------------------|
 *  | com.tpeapp/filter_service        | [FilterServiceChannel]        |
 *  | com.tpeapp/device_admin          | [DeviceAdminChannel]          |
 *  | com.tpeapp/partner_pin           | [PartnerPinChannel]           |
 *  | com.tpeapp/ble                   | [BleChannel]                  |
 *  | com.tpeapp/mqtt_events           | [MqttChannel]                 |
 *  | com.tpeapp/device_commands       | [DeviceCommandChannel]        |
 *  | com.tpeapp/screen_share          | [ScreenShareChannel]          |
 *  | com.tpeapp/remote_control        | [RemoteControlChannel]        |
 *  | com.tpeapp/text_replacement      | [TextReplacementChannel]      |
 *  | com.tpeapp/password_vault        | [PasswordVaultChannel]        |
 *
 * The Xposed module, FilterService, AppDeviceAdminReceiver, PartnerFcmService,
 * and all background workers remain purely native and are NOT changed.
 */
class TpeFlutterActivity : FlutterActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Keep FilterService alive for the lifetime of the host activity.
        startForegroundService(Intent(this, FilterService::class.java))
        startForegroundService(Intent(this, PartnerFcmService::class.java))
        requestIgnoreBatteryOptimizationsIfNeeded()
    }

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)
        val messenger = flutterEngine.dartExecutor.binaryMessenger

        FilterServiceChannel.register(messenger, applicationContext)
        DeviceAdminChannel.register(messenger, applicationContext)
        PartnerPinChannel.register(messenger, applicationContext)
        BleChannel.register(messenger, applicationContext)
        MqttChannel.register(messenger, applicationContext)
        DeviceCommandChannel.register(messenger, applicationContext)
        ScreenShareChannel.register(messenger, applicationContext)
        RemoteControlChannel.register(messenger, applicationContext)
        TextReplacementChannel.register(messenger, applicationContext)
        PasswordVaultChannel.register(messenger, applicationContext)
    }

    private fun requestIgnoreBatteryOptimizationsIfNeeded() {
        val pm = getSystemService(PowerManager::class.java)
        if (pm.isIgnoringBatteryOptimizations(packageName)) return
        runCatching {
            startActivity(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.parse("package:$packageName")
                }
            )
        }
    }
}
