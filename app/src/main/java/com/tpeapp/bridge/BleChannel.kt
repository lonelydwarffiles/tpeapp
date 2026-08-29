package com.hound.controller.bridge

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.hound.controller.ble.LovenseManager
import com.hound.controller.ble.PavlokManager
import io.flutter.plugin.common.BinaryMessenger
import io.flutter.plugin.common.EventChannel
import io.flutter.plugin.common.MethodChannel

/**
 * BleChannel — MethodChannel bridge for Lovense and Pavlok BLE devices.
 *
 * Channel names:
 *  - `com.hound.controller/ble`        (MethodChannel — commands)
 *  - `com.hound.controller/ble_events` (EventChannel  — connection-state changes)
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
 *  - `lovense.readBatteryLevel`             → reads battery level from standard BLE Battery Service
 *
 * ## Pavlok methods
 *  - `pavlok.scan`                                           → starts BLE scan for Pavlok
 *  - `pavlok.stopScan`                                       → stops BLE scan
 *  - `pavlok.disconnect`                                     → disconnects wristband
 *  - `pavlok.zap`      (intensity: Int, durationMs: Int)     → electric zap
 *  - `pavlok.vibrate`  (intensity: Int, durationMs: Int)     → wristband vibration
 *  - `pavlok.beep`     (intensity: Int, durationMs: Int)     → audible beep
 *  - `pavlok.stopAll`                                        → stops all stimulation
 *  - `pavlok.readBatteryLevel`                                → reads battery level from standard BLE Battery Service
 *
 * The underlying [LovenseManager] and [PavlokManager] singletons are also used
 * by [com.hound.controller.consequence.ConsequenceDispatcher] for automated punishment /
 * reward — both paths share the same BLE connection.
 */
object BleChannel {

    private const val TAG = "BleChannel"
    private const val CHANNEL        = "com.hound.controller/ble"
    private const val EVENTS_CHANNEL = "com.hound.controller/ble_events"

    fun register(messenger: BinaryMessenger, context: Context) {
        val ctx = context.applicationContext
        LovenseManager.init(ctx)
        PavlokManager.init(ctx)

        MethodChannel(messenger, CHANNEL).setMethodCallHandler { call, result ->
            try {
                when (call.method) {
                    "restorePairings" -> {
                        LovenseManager.restoreConnection()
                        PavlokManager.restoreConnection()
                        result.success(null)
                    }

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
                    "lovense.readBatteryLevel" -> { LovenseManager.readBatteryLevel(); result.success(null) }
                    "lovense.scanCandidates" -> {
                        val timeoutMs = (call.argument<Int>("timeoutMs") ?: 8000).toLong()
                        LovenseManager.scanCandidates(timeoutMs) { candidates ->
                            result.success(candidates)
                        }
                    }
                    "lovense.connectAddress" -> {
                        val address = call.argument<String>("address")
                        if (address.isNullOrBlank()) {
                            result.error("BLE_ERROR", "address is required", null)
                        } else {
                            LovenseManager.connectAddress(address)
                            result.success(null)
                        }
                    }
                    "lovense.isConnected" -> {
                        result.success(LovenseManager.isConnected())
                    }

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
                    "pavlok.readBatteryLevel" -> { PavlokManager.readBatteryLevel(); result.success(null) }
                    "pavlok.scanCandidates" -> {
                        val timeoutMs = (call.argument<Int>("timeoutMs") ?: 8000).toLong()
                        PavlokManager.scanCandidates(timeoutMs) { candidates ->
                            result.success(candidates)
                        }
                    }
                    "pavlok.connectAddress" -> {
                        val address = call.argument<String>("address")
                        if (address.isNullOrBlank()) {
                            result.error("BLE_ERROR", "address is required", null)
                        } else {
                            PavlokManager.connectAddress(address)
                            result.success(null)
                        }
                    }
                    "pavlok.isConnected" -> {
                        result.success(PavlokManager.isConnected())
                    }

                    else -> result.notImplemented()
                }
            } catch (e: Exception) {
                Log.e(TAG, "BLE command failed: ${call.method}", e)
                result.error("BLE_ERROR", e.message, null)
            }
        }

        val mainHandler = Handler(Looper.getMainLooper())

        fun emit(
            sink: EventChannel.EventSink?,
            device: String,
            type: String,
            payload: Map<String, Any?>,
        ) {
            val event = HashMap<String, Any?>()
            event["device"] = device
            event["type"] = type
            event.putAll(payload)
            mainHandler.post { sink?.success(event) }
        }

        EventChannel(messenger, EVENTS_CHANNEL).setStreamHandler(
            object : EventChannel.StreamHandler {
                private var sink: EventChannel.EventSink? = null

                override fun onListen(arguments: Any?, events: EventChannel.EventSink) {
                    sink = events

                    LovenseManager.setEventListener { type, payload ->
                        emit(sink, "lovense", type, payload)
                    }
                    PavlokManager.setEventListener { type, payload ->
                        emit(sink, "pavlok", type, payload)
                    }

                    emit(sink, "system", "listener_attached", emptyMap())
                }

                override fun onCancel(arguments: Any?) {
                    LovenseManager.setEventListener(null)
                    PavlokManager.setEventListener(null)
                    sink = null
                }
            }
        )
    }
}
