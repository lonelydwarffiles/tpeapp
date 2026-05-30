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

    /** Alternate profile variants seen across Pavlok hardware/firmware revisions. */
    private val ALT_SERVICE_UUIDS: Set<UUID> = setOf(
        UUID.fromString("156e0000-a300-4fea-897b-86f698d74461"),
        UUID.fromString("156e1000-a300-4fea-897b-86f698d74461"),
    )

    private val ALT_TX_UUIDS: Set<UUID> = setOf(
        UUID.fromString("00001001-0000-1000-8000-00805f9b34fb"),
        UUID.fromString("00001002-0000-1000-8000-00805f9b34fb"),
        UUID.fromString("00001003-0000-1000-8000-00805f9b34fb"),
        UUID.fromString("156e2000-a300-4fea-897b-86f698d74461"),
        UUID.fromString("156e4000-a300-4fea-897b-86f698d74461"),
        UUID.fromString("156e5000-a300-4fea-897b-86f698d74461"),
        UUID.fromString("156e6000-a300-4fea-897b-86f698d74461"),
        UUID.fromString("156e7000-a300-4fea-897b-86f698d74461"),
    )

    // ------------------------------------------------------------------
    //  Pavlok-S characteristic UUIDs (Buttplug reference)
    // ------------------------------------------------------------------

    private val VIBRATE_CHAR_UUID: UUID =
        UUID.fromString("00001001-0000-1000-8000-00805f9b34fb")
    private val BEEP_CHAR_UUID: UUID =
        UUID.fromString("00001002-0000-1000-8000-00805f9b34fb")
    private val ZAP_CHAR_UUID: UUID =
        UUID.fromString("00001003-0000-1000-8000-00805f9b34fb")
    private val ALT_CHAR_156E2000: UUID =
        UUID.fromString("156e2000-a300-4fea-897b-86f698d74461")
    private val ALT_CHAR_156E4000: UUID =
        UUID.fromString("156e4000-a300-4fea-897b-86f698d74461")
    private val ALT_CHAR_156E5000: UUID =
        UUID.fromString("156e5000-a300-4fea-897b-86f698d74461")
    private val ALT_CHAR_156E6000: UUID =
        UUID.fromString("156e6000-a300-4fea-897b-86f698d74461")
    private val ALT_CHAR_156E7000: UUID =
        UUID.fromString("156e7000-a300-4fea-897b-86f698d74461")

    // Protocol constants from Pavlok-S reverse engineering.
    private const val REPEAT_BASE: Int = 0x80
    private const val VIBE_BEEP_CONST: Int = 0x0C
    private const val LEGACY_TIME_BYTE: Int = 0xFA

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
                serviceUuidAlternates = ALT_SERVICE_UUIDS,
                charUuidAlternates = ALT_TX_UUIDS,
                allowWritableCharFallback = true,
            )
        }
    }

    /** Starts a BLE scan and connects to the first discovered Pavlok in range. */
    fun startScan() {
        checkInit("startScan")
        ble!!.startScan()
    }

    /** Scans for nearby Pavlok candidates without auto-connecting. */
    fun scanCandidates(
        timeoutMs: Long = 8_000L,
        onComplete: (List<Map<String, Any?>>) -> Unit,
    ) {
        checkInit("scanCandidates")
        ble!!.scanCandidates(timeoutMs, onComplete)
    }

    /** Connects to a Pavlok device by BLE MAC address. */
    fun connectAddress(address: String) {
        checkInit("connectAddress")
        ble!!.connect(address)
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

    fun setEventListener(listener: BleManager.EventListener?) {
        ble?.setEventListener(listener)
    }

    fun isConnected(): Boolean = ble?.isReady() == true

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
        sendZap(intensity, durationMs)

    /**
     * Activates wristband vibration.
     *
     * @param intensity  0–255.
     * @param durationMs Duration in milliseconds (clamped to 0–25 500 ms, 100 ms resolution).
     */
    fun vibrate(intensity: Int = 128, durationMs: Int = 2_000) =
        sendVibrateOrBeep(
            characteristicHint = VIBRATE_CHAR_UUID,
            label = "vibrate",
            intensity = intensity,
            durationMs = durationMs,
        )

    /**
     * Triggers an audible beep.
     *
     * @param intensity  0–255 (maps to volume / pattern on the device).
     * @param durationMs Duration in milliseconds (clamped to 0–25 500 ms, 100 ms resolution).
     */
    fun beep(intensity: Int = 128, durationMs: Int = 1_000) =
        sendVibrateOrBeep(
            characteristicHint = BEEP_CHAR_UUID,
            label = "beep",
            intensity = intensity,
            durationMs = durationMs,
        )

    /** Silences active stimulation by sending zero-intensity packets. */
    fun stopAll() {
        vibrate(0, 0)
        beep(0, 0)
        zap(0, 0)
    }

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

    /** Sends Pavlok-S vibrate/beep packet: RR 0C II TA TO */
    private fun sendVibrateOrBeep(
        characteristicHint: UUID,
        label: String,
        intensity: Int,
        durationMs: Int,
    ) {
        val b = ble
        if (b == null) {
            Log.w(TAG, "PavlokManager not initialised — dropping $label command")
            return
        }
        val safeIntensity = toPercentIntensity(intensity)
        val repeats = encodeRepeats(1)
        val time = timeCodeForPavlok2(durationMs.toLong())
        val active = if (time >= 0) time else LEGACY_TIME_BYTE
        val off = if (time >= 0) time else LEGACY_TIME_BYTE
        val payload = byteArrayOf(
            repeats.toByte(),
            VIBE_BEEP_CONST.toByte(),
            safeIntensity.toByte(),
            active.toByte(),
            off.toByte(),
        )
        Log.d(
            TAG,
            "Sending Pavlok $label packet to $characteristicHint: rr=%d ii=%d ta=%d to=%d".format(
                repeats,
                safeIntensity,
                active,
                off,
            ),
        )
        b.sendByteCommandToCharacteristic(
            characteristicUuids = listOf(
                characteristicHint,
                ALT_CHAR_156E2000,
                ALT_CHAR_156E4000,
                ALT_CHAR_156E5000,
                ALT_CHAR_156E6000,
                ALT_CHAR_156E7000,
                TX_UUID,
            ),
            payload = payload,
        )
    }

    /** Sends Pavlok-S zap packet: RR II */
    private fun sendZap(intensity: Int, durationMs: Int) {
        val b = ble
        if (b == null) {
            Log.w(TAG, "PavlokManager not initialised — dropping zap command")
            return
        }
        // Approximate repeats from duration (500 ms chunks), minimum one repeat for non-zero duration.
        val repeatCount = if (durationMs <= 0) 1 else (durationMs / 500).coerceIn(1, 10)
        val repeats = encodeRepeats(repeatCount)
        val safeIntensity = toPercentIntensity(intensity)
        val payload = byteArrayOf(repeats.toByte(), safeIntensity.toByte())
        Log.d(
            TAG,
            "Sending Pavlok zap packet to $ZAP_CHAR_UUID: rr=%d ii=%d".format(repeats, safeIntensity),
        )
        b.sendByteCommandToCharacteristic(
            characteristicUuids = listOf(
                ZAP_CHAR_UUID,
                ALT_CHAR_156E5000,
                ALT_CHAR_156E6000,
                ALT_CHAR_156E2000,
                ALT_CHAR_156E4000,
                ALT_CHAR_156E7000,
                TX_UUID,
            ),
            payload = payload,
        )
    }

    private fun encodeRepeats(repeatCount: Int): Int =
        (REPEAT_BASE + repeatCount.coerceIn(1, 127)) and 0xFF

    private fun toPercentIntensity(intensity: Int): Int {
        val clamped = intensity.coerceIn(0, 255)
        return ((clamped / 255.0) * 100.0).toInt().coerceIn(0, 100)
    }

    // Time-code mapping from Pavlok-S reference implementation.
    private fun timeCodeForPavlok2(timeMs: Long): Int {
        return when {
            timeMs > 10_000L -> 62
            timeMs >= 3_000L -> (((timeMs - 3_000L) / 500L).toInt() or 48)
            timeMs >= 1_000L -> (((timeMs - 1_000L) / 100L).toInt() or 32)
            timeMs >= 200L -> (((timeMs - 200L) / 50L).toInt() or 16)
            timeMs >= 0L -> ((timeMs / 10L).toInt() or 0)
            else -> -1
        }
    }

    private fun checkInit(caller: String) {
        checkNotNull(ble) { "PavlokManager.init(context) must be called before $caller()" }
    }
}
