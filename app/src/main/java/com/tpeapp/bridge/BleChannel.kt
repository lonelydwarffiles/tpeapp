package com.tpeapp.bridge

import android.content.Context
import android.util.Log
import com.tpeapp.ble.LovenseManager
import com.tpeapp.ble.PavlokManager
import io.flutter.plugin.common.BinaryMessenger
import io.flutter.plugin.common.EventChannel
import io.flutter.plugin.common.MethodChannel

/**
 * BleChannel — MethodChannel bridge for Lovense and Pavlok BLE devices.
 *
 * Channel names:
 *  - `com.tpeapp/ble`        (MethodChannel — commands)
 *  - `com.tpeapp/ble_events` (EventChannel  — connection-state changes)
 *
 * ## Lovense methods
 *  - `lovense.scan`                         → starts BLE scan for Lovense toy
 *  - `lovense.stopScan`                     → stops BLE scan
 *  - `lovense.disconnect`                   → disconnects toy
 *  - `lovense.vibrate`     (level: Int)     → vibration 0–20
 *  - `lovense.rotate`      (level: Int)     → rotation 0–20
 *  - `lovense.pump`        (level: Int)     → air-pump 0–3
 *  - `lovense.stopAll`                      → stops all functions
 *  - `lovense.battery`                      → requests battery level (response via notification)
 *
 * ## Pavlok methods
 *  - `pavlok.scan`                                           → starts BLE scan for Pavlok
 *  - `pavlok.stopScan`                                       → stops BLE scan
 *  - `pavlok.disconnect`                                     → disconnects wristband
 *  - `pavlok.zap`      (intensity: Int, durationMs: Int)     → electric zap
 *  - `pavlok.vibrate`  (intensity: Int, durationMs: Int)     → wristband vibration
 *  - `pavlok.beep`     (intensity: Int, durationMs: Int)     → audible beep
 *  - `pavlok.stopAll`                                        → stops all stimulation
 *
 * The underlying [LovenseManager] and [PavlokManager] singletons are also used
 * by [com.tpeapp.consequence.ConsequenceDispatcher] for automated punishment /
 * reward — both paths share the same BLE connection.
 */
object BleChannel {

    private const val TAG = "BleChannel"
    private const val CHANNEL        = "com.tpeapp/ble"
    private const val EVENTS_CHANNEL = "com.tpeapp/ble_events"

    fun register(messenger: BinaryMessenger, context: Context) {
        val ctx = context.applicationContext
        LovenseManager.init(ctx)
        PavlokManager.init(ctx)

        MethodChannel(messenger, CHANNEL).setMethodCallHandler { call, result ->
            try {
                when (call.method) {
                    // ── Lovense ──────────────────────────────────────────
                    "lovense.scan"       -> { LovenseManager.startScan();    result.success(null) }
                    "lovense.stopScan"   -> { LovenseManager.stopScan();     result.success(null) }
                    "lovense.disconnect" -> { LovenseManager.disconnect();   result.success(null) }
                    "lovense.vibrate"    -> {
                        val level = call.argument<Int>("level") ?: 0
                        LovenseManager.vibrate(level)
                        result.success(null)
                    }
                    "lovense.rotate"     -> {
                        val level = call.argument<Int>("level") ?: 0
                        LovenseManager.rotate(level)
                        result.success(null)
                    }
                    "lovense.pump"       -> {
                        val level = call.argument<Int>("level") ?: 0
                        LovenseManager.pump(level)
                        result.success(null)
                    }
                    "lovense.stopAll"    -> { LovenseManager.stopAll();      result.success(null) }
                    "lovense.battery"    -> { LovenseManager.queryBattery(); result.success(null) }

                    // ── Pavlok ──────────────────────────────────────────
                    "pavlok.scan"        -> { PavlokManager.startScan();     result.success(null) }
                    "pavlok.stopScan"    -> { PavlokManager.stopScan();      result.success(null) }
                    "pavlok.disconnect"  -> { PavlokManager.disconnect();    result.success(null) }
                    "pavlok.zap"         -> {
                        val intensity  = call.argument<Int>("intensity")  ?: 64
                        val durationMs = call.argument<Int>("durationMs") ?: 500
                        PavlokManager.zap(intensity, durationMs)
                        result.success(null)
                    }
                    "pavlok.vibrate"     -> {
                        val intensity  = call.argument<Int>("intensity")  ?: 128
                        val durationMs = call.argument<Int>("durationMs") ?: 2_000
                        PavlokManager.vibrate(intensity, durationMs)
                        result.success(null)
                    }
                    "pavlok.beep"        -> {
                        val intensity  = call.argument<Int>("intensity")  ?: 128
                        val durationMs = call.argument<Int>("durationMs") ?: 1_000
                        PavlokManager.beep(intensity, durationMs)
                        result.success(null)
                    }
                    "pavlok.stopAll"     -> { PavlokManager.stopAll();       result.success(null) }

                    else -> result.notImplemented()
                }
            } catch (e: Exception) {
                Log.e(TAG, "BLE command failed: ${call.method}", e)
                result.error("BLE_ERROR", e.message, null)
            }
        }

        // EventChannel — currently emits placeholder; extend with BleManager callbacks as needed.
        EventChannel(messenger, EVENTS_CHANNEL).setStreamHandler(
            object : EventChannel.StreamHandler {
                override fun onListen(arguments: Any?, events: EventChannel.EventSink) {
                    // Future work: forward BluetoothGattCallback connection-state changes
                    // to the Dart layer via events.success(mapOf("device" to ..., "state" to ...))
                }
                override fun onCancel(arguments: Any?) {}
            }
        )
    }
}
