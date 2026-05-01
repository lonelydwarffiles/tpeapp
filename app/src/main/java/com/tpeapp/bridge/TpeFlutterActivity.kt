package com.tpeapp.bridge

import android.content.Intent
import android.os.Bundle
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
 *  | com.tpeapp/fcm                   | [FcmChannel]                  |
 *  | com.tpeapp/device_commands       | [DeviceCommandChannel]        |
 *
 * The Xposed module, FilterService, AppDeviceAdminReceiver, PartnerFcmService,
 * and all background workers remain purely native and are NOT changed.
 */
class TpeFlutterActivity : FlutterActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Keep FilterService alive for the lifetime of the host activity.
        startForegroundService(Intent(this, FilterService::class.java))
    }

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)
        val messenger = flutterEngine.dartExecutor.binaryMessenger

        FilterServiceChannel.register(messenger, applicationContext)
        DeviceAdminChannel.register(messenger, applicationContext)
        PartnerPinChannel.register(messenger, applicationContext)
        BleChannel.register(messenger, applicationContext)
        FcmChannel.register(messenger, applicationContext)
        DeviceCommandChannel.register(messenger, applicationContext)
    }
}
