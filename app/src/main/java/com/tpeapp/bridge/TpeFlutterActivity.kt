package com.hound.controller.bridge

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import com.hound.controller.service.CoreServiceKeeper
import io.flutter.embedding.android.FlutterFragmentActivity
import io.flutter.embedding.engine.FlutterEngine

/**
 * TpeFlutterActivity — the Flutter host activity for the TPE app.
 *
 * Replaces the old Jetpack-only [com.hound.controller.ui.MainActivity].  All UI is now
 * rendered by the Flutter engine; the native Kotlin layer exposes the following
 * services to Dart via MethodChannels:
 *
 *  | Channel                          | Bridge class                  |
 *  |----------------------------------|-------------------------------|
 *  | com.hound.controller/filter_service        | [FilterServiceChannel]        |
 *  | com.hound.controller/device_admin          | [DeviceAdminChannel]          |
 *  | com.hound.controller/partner_pin           | [PartnerPinChannel]           |
 *  | com.hound.controller/ble                   | [BleChannel]                  |
 *  | com.hound.controller/mqtt_events           | [MqttChannel]                 |
 *  | com.hound.controller/device_commands       | [DeviceCommandChannel]        |
 *  | com.hound.controller/screen_share          | [ScreenShareChannel]          |
 *  | com.hound.controller/remote_control        | [RemoteControlChannel]        |
 *  | com.hound.controller/text_replacement      | [TextReplacementChannel]      |
 *  | com.hound.controller/password_vault        | [PasswordVaultChannel]        |
 *
 * The Xposed module, FilterService, AppDeviceAdminReceiver, PartnerMqttService,
 * and all background workers remain purely native and are NOT changed.
 */
class TpeFlutterActivity : FlutterFragmentActivity() {
    companion object {
        private const val TAG = "TpeFlutterActivity"
    }

    private fun isShareIntent(intent: Intent?): Boolean {
        val action = intent?.action ?: return false
        return action == Intent.ACTION_SEND || action == Intent.ACTION_SEND_MULTIPLE
    }

    private fun captureShareIntent(intent: Intent?): Boolean {
        if (!isShareIntent(intent)) return false
        DeviceCommandChannel.captureIncomingShareIntent(applicationContext, intent)
        Log.d(TAG, "Captured quick-share intent for Flutter handoff")
        return true
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        captureShareIntent(intent)
        // Re-assert critical services whenever the host UI opens.
        CoreServiceKeeper.ensureCoreServicesRunning(this, "flutter_activity_on_create")
        CoreServiceKeeper.scheduleWatchdog(this)
        requestIgnoreBatteryOptimizationsIfNeeded()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        captureShareIntent(intent)
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
        DeviceCommandChannel.register(messenger, this)
        ScreenShareChannel.register(messenger, applicationContext)
        RemoteControlChannel.register(messenger, applicationContext)
        TextReplacementChannel.register(messenger, applicationContext)
        PasswordVaultChannel.register(messenger, applicationContext)
        HealthConnectChannel.register(messenger, this)
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
