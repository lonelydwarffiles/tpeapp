package com.tpeapp.bridge

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import com.tpeapp.service.CoreServiceKeeper
import io.flutter.embedding.android.FlutterFragmentActivity
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
 * The Xposed module, FilterService, AppDeviceAdminReceiver, PartnerMqttService,
 * and all background workers remain purely native and are NOT changed.
 */
class TpeFlutterActivity : FlutterFragmentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Re-assert critical services whenever the host UI opens.
        CoreServiceKeeper.ensureCoreServicesRunning(this, "flutter_activity_on_create")
        CoreServiceKeeper.scheduleWatchdog(this)
        requestIgnoreBatteryOptimizationsIfNeeded()
    }

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)
        val messenger = flutterEngine.dartExecutor.binaryMessenger

        PermissionsChannel.register(messenger, this)
        AccessibilitySetupChannel.register(messenger, this)
        FilterServiceChannel.register(messenger, applicationContext)
        DeviceAdminChannel.register(messenger, this)
        PartnerPinChannel.register(messenger, applicationContext)
        BleChannel.register(messenger, applicationContext)
        MqttChannel.register(messenger, applicationContext)
        DeviceCommandChannel.register(messenger, applicationContext)
        ScreenShareChannel.register(messenger, applicationContext)
        RemoteControlChannel.register(messenger, applicationContext)
        TextReplacementChannel.register(messenger, applicationContext)
        PasswordVaultChannel.register(messenger, applicationContext)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        if (PermissionsChannel.onRequestPermissionsResult(this, requestCode)) {
            return
        }
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
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
