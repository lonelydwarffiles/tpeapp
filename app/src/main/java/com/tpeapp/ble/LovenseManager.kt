package com.tpeapp.ble

import android.content.Context
import android.util.Log
import java.util.UUID

/**
 * LovenseManager — high-level Lovense toy controller built on [BleManager].
 *
 * All Lovense toys share a single GATT profile:
 *  - Service UUID (FFF0) : `0000fff0-0000-1000-8000-00805f9b34fb`
 *  - TX (write) UUID (FFF2): `0000fff2-0000-1000-8000-00805f9b34fb`
 *
 * Commands are ASCII strings terminated with `;`, for example `"Vibrate:10;"`.
 * Level range for vibration/rotation is 0–20; pump is 0–3.
 *
 * Typical usage:
 * ```
 * LovenseManager.init(context)
 * LovenseManager.startScan()        // discovers and connects to the first toy
 * // … once connected …
 * LovenseManager.vibrate(10)
 * LovenseManager.stopAll()
 * LovenseManager.disconnect()
 * ```
 *
 * Required BLE permissions are the same as [BleManager]:
 *   `BLUETOOTH_SCAN`, `BLUETOOTH_CONNECT` (Android 12+)
 *   `BLUETOOTH`, `BLUETOOTH_ADMIN`, `ACCESS_FINE_LOCATION` (Android ≤ 11)
 */
object LovenseManager {

    private const val TAG = "LovenseManager"

    /** Lovense GATT service UUID (FFF0). */
    val SERVICE_UUID: UUID = UUID.fromString("0000fff0-0000-1000-8000-00805f9b34fb")

    /** Lovense TX write characteristic UUID (FFF2). */
    val TX_UUID: UUID = UUID.fromString("0000fff2-0000-1000-8000-00805f9b34fb")

    @Volatile private var ble: BleManager? = null

    // ------------------------------------------------------------------
    //  Lifecycle
    // ------------------------------------------------------------------

    /**
     * Initialises the underlying [BleManager] with Lovense UUIDs.
     * Safe to call multiple times with the same context — subsequent calls are no-ops.
     */
    @Synchronized
    fun init(context: Context) {
        if (ble == null) {
            ble = BleManager(
                context = context.applicationContext,
                serviceUuid = SERVICE_UUID,
                charUuid = TX_UUID,
            )
        }
    }

    /** Starts a BLE scan and connects to the first discovered Lovense toy. */
    fun startScan() {
        checkInit("startScan")
        ble!!.startScan()
    }

    /** Stops any ongoing BLE scan. */
    fun stopScan() {
        ble?.stopScan()
    }

    /** Disconnects from the connected toy without releasing resources. */
    fun disconnect() {
        ble?.disconnect()
    }

    /**
     * Disconnects and releases all BLE resources.
     * Call when the manager is permanently no longer needed (e.g. service destroy).
     * A subsequent [init] call can re-create the manager.
     */
    fun close() {
        ble?.close()
        ble = null
    }

    // ------------------------------------------------------------------
    //  Toy commands
    // ------------------------------------------------------------------

    /**
     * Sets vibration intensity.
     * @param level 0 (off) to 20 (maximum).
     */
    fun vibrate(level: Int) {
        send("Vibrate:${level.coerceIn(0, 20)};")
    }

    /** Stops vibration. Equivalent to `vibrate(0)`. */
    fun vibrateStop() = vibrate(0)

    /**
     * Sets rotation intensity.
     * @param level 0 (off) to 20 (maximum).
     */
    fun rotate(level: Int) {
        send("Rotate:${level.coerceIn(0, 20)};")
    }

    /** Stops rotation. Equivalent to `rotate(0)`. */
    fun rotateStop() = rotate(0)

    /**
     * Sets air-pump intensity for compatible toys (e.g. Max).
     * @param level 0 (off) to 3 (maximum).
     */
    fun pump(level: Int) {
        send("Pump:${level.coerceIn(0, 3)};")
    }

    /** Stops air pump. Equivalent to `pump(0)`. */
    fun pumpStop() = pump(0)

    /** Stops all active functions on the toy. */
    fun stopAll() {
        vibrate(0)
        rotate(0)
        pump(0)
    }

    /** Queries the toy's battery level. The response arrives as a BLE RX notification. */
    fun queryBattery() = send("Battery;")

    // ------------------------------------------------------------------
    //  DataChannel dispatch
    // ------------------------------------------------------------------

    /**
     * Dispatches a toy command from a JSON string received over a WebRTC DataChannel.
     *
     * Expected format:
     * ```json
     * { "cmd": "vibrate", "level": 15 }
     * ```
     *
     * Supported `cmd` values: `vibrate`, `rotate`, `pump`, `stop`, `battery`.
     */
    fun onDataChannelMessage(json: String) {
        try {
            val obj   = org.json.JSONObject(json)
            val cmd   = obj.optString("cmd").lowercase()
            val level = obj.optInt("level", 0)
            Log.d(TAG, "DataChannel toy command: cmd=$cmd level=$level")
            when (cmd) {
                "vibrate" -> vibrate(level)
                "rotate"  -> rotate(level)
                "pump"    -> pump(level)
                "stop"    -> stopAll()
                "battery" -> queryBattery()
                else      -> Log.w(TAG, "Unknown Lovense DataChannel command: $cmd")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse Lovense DataChannel message: $json", e)
        }
    }

    // ------------------------------------------------------------------
    //  Internal
    // ------------------------------------------------------------------

    private fun send(command: String) {
        val b = ble
        if (b == null) {
            Log.w(TAG, "LovenseManager not initialised — dropping command: $command")
            return
        }
        Log.d(TAG, "Sending Lovense command: $command")
        b.sendByteCommand(command.toByteArray(Charsets.UTF_8))
    }

    private fun checkInit(caller: String) {
        checkNotNull(ble) { "LovenseManager.init(context) must be called before $caller()" }
    }
}
