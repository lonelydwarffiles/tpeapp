package com.tpeapp.ble

import android.content.Context
import android.util.Log
import java.util.UUID

/**
 * PavlokManager — high-level Pavlok wristband controller built on [BleManager].
 *
 * Pavlok BLE GATT profile (Pavlok 2 / Pavlok 3):
 *  - Service UUID  : [SERVICE_UUID]  (`0000fee9-0000-1000-8000-00805f9b34fb`)
 *  - Write char UUID: [TX_UUID]      (`d44bc439-abfd-45a2-b575-925416129600`)
 *
 * Commands are 3-byte arrays `[stimulusType, intensity, durationUnit]`:
 *  - `stimulusType` : [CMD_ZAP] = 0x04 · [CMD_VIBRATE] = 0x01 · [CMD_BEEP] = 0x02
 *  - `intensity`    : 0–255 (0 = off, 255 = maximum)
 *  - `durationUnit` : 0–255, each unit = 100 ms (e.g. 10 → 1 second)
 *
 * NOTE: The UUIDs and command encoding are based on community reverse-engineering of
 * the Pavlok 2/3 BLE firmware.  Verify [SERVICE_UUID], [TX_UUID], and command bytes
 * against the device firmware version in use if you observe unexpected behaviour.
 *
 * Typical usage:
 * ```
 * PavlokManager.init(context)
 * PavlokManager.startScan()      // discovers and connects to the first Pavlok in range
 * // … once connected …
 * PavlokManager.zap(64, 500)     // short 25 % zap for 500 ms
 * PavlokManager.stopAll()
 * PavlokManager.disconnect()
 * ```
 *
 * Required BLE permissions are the same as [BleManager]:
 *   `BLUETOOTH_SCAN`, `BLUETOOTH_CONNECT` (Android 12+)
 *   `BLUETOOTH`, `BLUETOOTH_ADMIN`, `ACCESS_FINE_LOCATION` (Android ≤ 11)
 */
object PavlokManager {

    private const val TAG = "PavlokManager"

    // ------------------------------------------------------------------
    //  GATT UUIDs — Pavlok 2/3
    // ------------------------------------------------------------------

    /** Pavlok GATT service UUID. */
    val SERVICE_UUID: UUID = UUID.fromString("0000fee9-0000-1000-8000-00805f9b34fb")

    /** Pavlok TX (write) characteristic UUID. */
    val TX_UUID: UUID = UUID.fromString("d44bc439-abfd-45a2-b575-925416129600")

    // ------------------------------------------------------------------
    //  Stimulus command bytes
    // ------------------------------------------------------------------

    /** Stimulus type — vibration. */
    const val CMD_VIBRATE: Byte = 0x01

    /** Stimulus type — audible beep. */
    const val CMD_BEEP: Byte = 0x02

    /** Stimulus type — electric zap. */
    const val CMD_ZAP: Byte = 0x04

    // ------------------------------------------------------------------
    //  State
    // ------------------------------------------------------------------

    @Volatile private var ble: BleManager? = null

    // ------------------------------------------------------------------
    //  Lifecycle
    // ------------------------------------------------------------------

    /**
     * Initialises the underlying [BleManager] with Pavlok UUIDs.
     * Safe to call multiple times — subsequent calls are no-ops.
     */
    @Synchronized
    fun init(context: Context) {
        if (ble == null) {
            ble = BleManager(
                context     = context.applicationContext,
                serviceUuid = SERVICE_UUID,
                charUuid    = TX_UUID,
            )
        }
    }

    /** Starts a BLE scan and connects to the first discovered Pavlok in range. */
    fun startScan() {
        checkInit("startScan")
        ble!!.startScan()
    }

    /** Stops any ongoing BLE scan. */
    fun stopScan() {
        ble?.stopScan()
    }

    /** Disconnects from the wristband without releasing resources. */
    fun disconnect() {
        ble?.disconnect()
    }

    /**
     * Disconnects and releases all BLE resources.
     * A subsequent [init] call can re-create the manager.
     */
    fun close() {
        ble?.close()
        ble = null
    }

    // ------------------------------------------------------------------
    //  Stimulus commands
    // ------------------------------------------------------------------

    /**
     * Delivers an electric zap.
     *
     * @param intensity  0–255; recommended ≤ 64 (approximately 25 %) for automated consequences.
     * @param durationMs Duration in milliseconds (clamped to 0–25 500 ms, 100 ms resolution).
     */
    fun zap(intensity: Int = 64, durationMs: Int = 500) =
        send(CMD_ZAP, intensity, durationMs)

    /**
     * Activates wristband vibration.
     *
     * @param intensity  0–255.
     * @param durationMs Duration in milliseconds (clamped to 0–25 500 ms, 100 ms resolution).
     */
    fun vibrate(intensity: Int = 128, durationMs: Int = 2_000) =
        send(CMD_VIBRATE, intensity, durationMs)

    /**
     * Triggers an audible beep.
     *
     * @param intensity  0–255 (maps to volume / pattern on the device).
     * @param durationMs Duration in milliseconds (clamped to 0–25 500 ms, 100 ms resolution).
     */
    fun beep(intensity: Int = 128, durationMs: Int = 1_000) =
        send(CMD_BEEP, intensity, durationMs)

    /** Silences all active stimulation by sending a zero-intensity zap command. */
    fun stopAll() = send(CMD_ZAP, 0, 0)

    // ------------------------------------------------------------------
    //  DataChannel dispatch
    // ------------------------------------------------------------------

    /**
     * Dispatches a stimulus command from a JSON string received over a WebRTC DataChannel.
     *
     * Expected format:
     * ```json
     * { "cmd": "zap", "intensity": 64, "duration_ms": 500 }
     * ```
     *
     * Supported `cmd` values: `zap`, `vibrate`, `beep`, `stop`.
     */
    fun onDataChannelMessage(json: String) {
        try {
            val obj        = org.json.JSONObject(json)
            val cmd        = obj.optString("cmd").lowercase()
            val intensity  = obj.optInt("intensity", 64)
            val durationMs = obj.optInt("duration_ms", 500)
            Log.d(TAG, "DataChannel Pavlok command: cmd=$cmd intensity=$intensity durationMs=$durationMs")
            when (cmd) {
                "zap"     -> zap(intensity, durationMs)
                "vibrate" -> vibrate(intensity, durationMs)
                "beep"    -> beep(intensity, durationMs)
                "stop"    -> stopAll()
                else      -> Log.w(TAG, "Unknown Pavlok DataChannel command: $cmd")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse Pavlok DataChannel message: $json", e)
        }
    }

    // ------------------------------------------------------------------
    //  Internal
    // ------------------------------------------------------------------

    /**
     * Encodes and sends a 3-byte stimulus command.
     *
     * @param type       Stimulus type byte ([CMD_ZAP], [CMD_VIBRATE], [CMD_BEEP]).
     * @param intensity  0–255 intensity value.
     * @param durationMs Duration in ms; converted to 100 ms units, clamped to 0–255.
     */
    private fun send(type: Byte, intensity: Int, durationMs: Int) {
        val b = ble
        if (b == null) {
            Log.w(TAG, "PavlokManager not initialised — dropping command type=0x%02x".format(type))
            return
        }
        val intensityByte = intensity.coerceIn(0, 255).toByte()
        val durationUnit  = (durationMs / 100).coerceIn(0, 255).toByte()
        val command = byteArrayOf(type, intensityByte, durationUnit)
        Log.d(TAG, "Sending Pavlok command: type=0x%02x intensity=%d (clamped=%d) durationMs=%d (units=%d)".format(
            type, intensity, intensityByte.toInt() and 0xFF, durationMs, durationUnit.toInt() and 0xFF))
        b.sendByteCommand(command)
    }

    private fun checkInit(caller: String) {
        checkNotNull(ble) { "PavlokManager.init(context) must be called before $caller()" }
    }
}
