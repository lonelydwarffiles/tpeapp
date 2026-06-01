package com.tpeapp.bridge

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
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
        runCatching {
            val registrant = Class.forName("io.flutter.plugins.GeneratedPluginRegistrant")
            val registerWith = registrant.getDeclaredMethod("registerWith", FlutterEngine::class.java)
            registerWith.invoke(null, flutterEngine)
        }.onFailure { err ->
            Log.w(TAG, "GeneratedPluginRegistrant registration unavailable", err)
        }

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
