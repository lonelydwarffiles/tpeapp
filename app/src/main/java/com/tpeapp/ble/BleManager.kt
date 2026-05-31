package com.tpeapp.ble

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothGattCharacteristic.PROPERTY_READ
import android.bluetooth.BluetoothGattCharacteristic.PROPERTY_WRITE
import android.bluetooth.BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Context
import android.content.pm.PackageManager
import android.content.SharedPreferences
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import java.util.UUID

/**
 * BleManager — a self-contained BLE peripheral manager built entirely on
 * [android.bluetooth] (no third-party SDKs).
 *
 * Typical usage:
 * ```
 * val ble = BleManager(context)
 * ble.startScan()                // discovers the first matching peripheral
 * // … once connected …
 * ble.sendByteCommand(byteArrayOf(0x01, 0x02))
 * ble.disconnect()
 * ```
 *
 * Required manifest permissions (Android 12+):
 *   `BLUETOOTH_SCAN`, `BLUETOOTH_CONNECT`
 *
 * Required manifest permissions (Android 11 and below):
 *   `BLUETOOTH`, `BLUETOOTH_ADMIN`, `ACCESS_FINE_LOCATION`
 *
 * @param context     Application or activity context used for system service lookups.
 * @param serviceUuid UUID of the GATT service that exposes the target characteristic.
 * @param charUuid    UUID of the GATT characteristic to write commands to.
 * @param scanTimeout Duration (ms) after which a scan is stopped automatically.
 *                    Defaults to 10 seconds.
 */
class BleManager(
    private val context: Context,
    val serviceUuid: UUID  = SERVICE_UUID,
    val charUuid: UUID     = CHARACTERISTIC_UUID,
    private val serviceUuidAlternates: Set<UUID> = emptySet(),
    private val charUuidAlternates: Set<UUID> = emptySet(),
    private val allowWritableCharFallback: Boolean = false,
    private val scanTimeout: Long = DEFAULT_SCAN_TIMEOUT_MS,
) {

    fun interface EventListener {
        fun onEvent(type: String, payload: Map<String, Any?>)
    }

    // ------------------------------------------------------------------
    //  Companion — well-known generic UUIDs (placeholders; swap as needed)
    // ------------------------------------------------------------------

    companion object {
        private const val TAG = "BleManager"
        private const val PREFS_NAME = "ble_manager_prefs"
        private const val MAX_RECONNECT_ATTEMPTS = 30
        private const val INITIAL_RECONNECT_DELAY_MS = 1_500L
        private const val MAX_RECONNECT_DELAY_MS = 30_000L

        /**
         * Example GATT service UUID (Bluetooth SIG Heart Rate Service — 0x180D).
         * Replace with the actual UUID advertised by the target peripheral.
         */
        val SERVICE_UUID: UUID = UUID.fromString("0000180d-0000-1000-8000-00805f9b34fb")

        /**
         * Example GATT characteristic UUID (Bluetooth SIG Heart Rate Measurement — 0x2A37).
         * Replace with the UUID of the specific characteristic to write commands to.
         */
        val CHARACTERISTIC_UUID: UUID = UUID.fromString("00002a37-0000-1000-8000-00805f9b34fb")

        private val BATTERY_SERVICE_UUID: UUID =
            UUID.fromString("0000180f-0000-1000-8000-00805f9b34fb")
        private val BATTERY_LEVEL_CHAR_UUID: UUID =
            UUID.fromString("00002a19-0000-1000-8000-00805f9b34fb")

        private const val DEFAULT_SCAN_TIMEOUT_MS = 10_000L
    }

    // ------------------------------------------------------------------
    //  State
    // ------------------------------------------------------------------

    private val bluetoothManager: BluetoothManager =
        context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager

    private val bluetoothAdapter: BluetoothAdapter? = bluetoothManager.adapter

    private val mainHandler = Handler(Looper.getMainLooper())
    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    @Volatile private var scanner: BluetoothLeScanner? = null
    @Volatile private var gatt: BluetoothGatt? = null
    @Volatile private var targetCharacteristic: BluetoothGattCharacteristic? = null
    @Volatile private var isScanning = false
    @Volatile private var eventListener: EventListener? = null
    @Volatile private var autoReconnectEnabled = true
    @Volatile private var reconnectAttempts = 0

    private val reconnectRunnable = Runnable {
        reconnectToLastKnownDevice(reason = "scheduled_retry")
    }

    @Volatile private var candidateScanCallback: ScanCallback? = null
    @Volatile private var candidateScanCompletion: ((List<Map<String, Any?>>) -> Unit)? = null
    private val candidateScanResults: LinkedHashMap<String, Map<String, Any?>> = LinkedHashMap()

    /** Pending payload waiting to be written once the characteristic is discovered. */
    private val pendingCommands: ArrayDeque<ByteArray> = ArrayDeque()

    private val savedAddressKey =
        "saved_address_${serviceUuid}_$charUuid".replace('-', '_')

    // ------------------------------------------------------------------
    //  Public API
    // ------------------------------------------------------------------

    fun setEventListener(listener: EventListener?) {
        eventListener = listener
    }

    /**
     * Starts a BLE scan for any peripheral that advertises [serviceUuid].
     * The scan is automatically stopped after [scanTimeout] ms.
     * Requires `BLUETOOTH_SCAN` (API 31+) or `BLUETOOTH` + location (API ≤ 30).
     */
    fun startScan() {
        val adapter = bluetoothAdapter
        if (adapter == null || !adapter.isEnabled) {
            Log.w(TAG, "Bluetooth is not available or not enabled")
            emit("scan_unavailable")
            return
        }

        if (!hasPermission(Manifest.permission.BLUETOOTH_SCAN)) {
            Log.w(TAG, "Missing BLUETOOTH_SCAN permission — cannot start scan")
            emit("scan_permission_missing")
            return
        }

        if (isScanning) {
            Log.d(TAG, "Scan already in progress")
            emit("scan_already_running")
            return
        }

        scanner = adapter.bluetoothLeScanner
        if (scanner == null) {
            Log.w(TAG, "BluetoothLeScanner not available")
            emit("scan_unavailable")
            return
        }

        isScanning = true
        Log.i(TAG, "BLE scan started (timeout=${scanTimeout}ms)")
        emit("scan_started", mapOf("timeout_ms" to scanTimeout))
        scanner?.startScan(scanCallback) ?: run {
            isScanning = false
            Log.w(TAG, "BluetoothLeScanner became null before scan could start")
            emit("scan_failed")
            return
        }

        // Auto-stop after timeout.
        mainHandler.postDelayed({ stopScan() }, scanTimeout)
    }

    /** Stops any ongoing BLE scan. */
    fun stopScan() {
        if (!isScanning) return
        if (!hasPermission(Manifest.permission.BLUETOOTH_SCAN)) {
            Log.w(TAG, "Missing BLUETOOTH_SCAN permission — cannot stop scan")
            emit("scan_permission_missing")
            return
        }
        isScanning = false
        candidateScanCallback?.let { scanner?.stopScan(it) }
        scanner?.stopScan(scanCallback)
        candidateScanCallback = null
        candidateScanCompletion = null
        candidateScanResults.clear()
        scanner = null
        Log.i(TAG, "BLE scan stopped")
        emit("scan_stopped")
    }

    /**
     * Connects to [device] and begins GATT service discovery.
     * Requires `BLUETOOTH_CONNECT` (API 31+) or `BLUETOOTH` (API ≤ 30).
     */
    fun connect(device: BluetoothDevice) {
        if (!hasPermission(Manifest.permission.BLUETOOTH_CONNECT)) {
            Log.w(TAG, "Missing BLUETOOTH_CONNECT permission — cannot connect")
            emit("connect_permission_missing")
            return
        }
        cancelReconnect()
        autoReconnectEnabled = true
        Log.i(TAG, "Connecting to ${device.address}")
        emit("connecting", mapOf("address" to device.address, "name" to device.name))
        this.gatt?.close()
        // autoConnect = false for faster initial connection
        gatt = device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
    }

    /** Connects to a BLE device by address. */
    fun connect(address: String) {
        val adapter = bluetoothAdapter
        if (adapter == null || !adapter.isEnabled) {
            emit("scan_unavailable")
            return
        }
        try {
            connect(adapter.getRemoteDevice(address))
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "Invalid BLE address for connect: $address", e)
            emit("connect_invalid_address", mapOf("address" to address))
        }
    }

    /** Scans candidates without auto-connecting, then returns unique addresses. */
    fun scanCandidates(timeoutMs: Long = scanTimeout, onComplete: (List<Map<String, Any?>>) -> Unit) {
        val adapter = bluetoothAdapter
        if (adapter == null || !adapter.isEnabled) {
            emit("scan_unavailable")
            onComplete(emptyList())
            return
        }
        if (!hasPermission(Manifest.permission.BLUETOOTH_SCAN)) {
            emit("scan_permission_missing")
            onComplete(emptyList())
            return
        }

        stopScan()
        scanner = adapter.bluetoothLeScanner
        if (scanner == null) {
            emit("scan_unavailable")
            onComplete(emptyList())
            return
        }

        candidateScanResults.clear()
        candidateScanCompletion = onComplete
        isScanning = true

        val cb = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val device = result.device
                val address = device.address ?: return
                val candidate = mapOf(
                    "address" to address,
                    "name" to (device.name ?: ""),
                    "rssi" to result.rssi,
                )
                candidateScanResults[address] = candidate
                emit("scan_result", candidate)
            }

            override fun onScanFailed(errorCode: Int) {
                Log.e(TAG, "Candidate scan failed, error=$errorCode")
                emit("scan_failed", mapOf("error_code" to errorCode))
                finishCandidateScan()
            }
        }

        candidateScanCallback = cb
        emit("scan_started", mapOf("timeout_ms" to timeoutMs, "mode" to "candidates"))
        scanner?.startScan(cb)
        mainHandler.postDelayed({ finishCandidateScan() }, timeoutMs)
    }

    /**
     * Writes [payload] to [charUuid] on the connected GATT server.
     *
     * If GATT is not yet connected / services not yet discovered the command
     * is queued and executed once the characteristic becomes available.
     *
     * Requires `BLUETOOTH_CONNECT` permission.
     */
    fun sendByteCommand(payload: ByteArray) {
        val characteristic = targetCharacteristic
        if (characteristic == null) {
            Log.d(TAG, "Characteristic not ready — queuing command (${payload.size} bytes)")
            pendingCommands.addLast(payload.copyOf())
            emit("command_queued", mapOf("bytes" to payload.size))
            return
        }
        writeCharacteristic(characteristic, payload)
    }

    /**
     * Writes [payload] to the first connected characteristic matching [characteristicUuids].
     * Falls back to [targetCharacteristic] when none of the requested UUIDs are present.
     */
    fun sendByteCommandToCharacteristic(
        characteristicUuids: List<UUID>,
        payload: ByteArray,
    ) {
        val currentGatt = gatt
        if (currentGatt == null) {
            emit("write_failed", mapOf("reason" to "gatt_null"))
            return
        }

        var selected: BluetoothGattCharacteristic? = null
        var selectedUuid: UUID? = null

        for (uuid in characteristicUuids) {
            val candidate = currentGatt.services.firstNotNullOfOrNull { svc ->
                svc.getCharacteristic(uuid)
            }
            if (candidate != null) {
                selected = candidate
                selectedUuid = uuid
                break
            }
        }

        if (selected == null) {
            selected = targetCharacteristic
            if (selected != null) {
                emit(
                    "characteristic_fallback",
                    mapOf(
                        "mode" to "target_characteristic",
                        "characteristic_uuid" to selected.uuid.toString(),
                        "requested_uuids" to characteristicUuids.map { it.toString() },
                    ),
                )
            }
        }

        if (selected == null) {
            emit(
                "write_failed",
                mapOf(
                    "reason" to "characteristic_missing",
                    "requested_uuids" to characteristicUuids.map { it.toString() },
                    "available_services" to currentGatt.services.map { it.uuid.toString() },
                ),
            )
            return
        }

        if (selectedUuid != null) {
            emit(
                "write_route",
                mapOf(
                    "characteristic_uuid" to selected.uuid.toString(),
                    "requested_uuid" to selectedUuid.toString(),
                ),
            )
        }
        writeCharacteristic(selected, payload)
    }

    /** True when GATT characteristic is ready for write commands. */
    fun isReady(): Boolean = targetCharacteristic != null

    /** Reads battery percentage from the standard Battery Service (0x180F/0x2A19). */
    fun readBatteryLevel() {
        if (!hasPermission(Manifest.permission.BLUETOOTH_CONNECT)) {
            emit("connect_permission_missing")
            return
        }

        val currentGatt = gatt
        if (currentGatt == null) {
            emit("battery_read_failed", mapOf("reason" to "gatt_null"))
            return
        }

        val batteryService = currentGatt.getService(BATTERY_SERVICE_UUID)
        val batteryChar = batteryService?.getCharacteristic(BATTERY_LEVEL_CHAR_UUID)
        if (batteryChar == null) {
            emit(
                "battery_unavailable",
                mapOf(
                    "service_uuid" to BATTERY_SERVICE_UUID.toString(),
                    "characteristic_uuid" to BATTERY_LEVEL_CHAR_UUID.toString(),
                ),
            )
            return
        }

        if ((batteryChar.properties and PROPERTY_READ) == 0) {
            emit(
                "battery_read_failed",
                mapOf(
                    "reason" to "characteristic_not_readable",
                    "characteristic_uuid" to batteryChar.uuid.toString(),
                ),
            )
            return
        }

        emit("battery_read_requested", mapOf("characteristic_uuid" to batteryChar.uuid.toString()))

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val enqueued = currentGatt.readCharacteristic(batteryChar)
            if (!enqueued) {
                emit("battery_read_failed", mapOf("reason" to "enqueue_failed"))
            }
        } else {
            @Suppress("DEPRECATION")
            val enqueued = currentGatt.readCharacteristic(batteryChar)
            if (!enqueued) {
                emit("battery_read_failed", mapOf("reason" to "enqueue_failed"))
            }
        }
    }

    /** Disconnects from the GATT server and releases all resources. */
    fun disconnect() {
        autoReconnectEnabled = false
        cancelReconnect()
        if (!hasPermission(Manifest.permission.BLUETOOTH_CONNECT)) {
            Log.w(TAG, "Missing BLUETOOTH_CONNECT permission — cannot disconnect cleanly")
            emit("disconnect_permission_missing")
        } else {
            gatt?.disconnect()
        }
    }

    /** Closes the GATT client and frees all resources. Call when the manager is no longer needed. */
    fun close() {
        autoReconnectEnabled = false
        cancelReconnect()
        stopScan()
        pendingCommands.clear()
        targetCharacteristic = null
        if (hasPermission(Manifest.permission.BLUETOOTH_CONNECT)) {
            gatt?.close()
        }
        gatt = null
        Log.i(TAG, "BleManager closed")
        emit("closed")
    }

    // ------------------------------------------------------------------
    //  Scan callback
    // ------------------------------------------------------------------

    private val scanCallback = object : ScanCallback() {

        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device
            Log.d(TAG, "Scan result: ${device.address} rssi=${result.rssi}")
            emit("scan_result", mapOf(
                "address" to device.address,
                "name" to device.name,
                "rssi" to result.rssi,
            ))
            // Connect to the first device found; stop scanning.
            stopScan()
            connect(device)
        }

        override fun onScanFailed(errorCode: Int) {
            isScanning = false
            Log.e(TAG, "BLE scan failed, error=$errorCode")
            emit("scan_failed", mapOf("error_code" to errorCode))
        }
    }

    // ------------------------------------------------------------------
    //  GATT callback
    // ------------------------------------------------------------------

    private val gattCallback = object : BluetoothGattCallback() {

        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    reconnectAttempts = 0
                    saveLastDeviceAddress(gatt.device.address)
                    Log.i(TAG, "GATT connected — discovering services")
                    emit("connected")
                    if (hasPermission(Manifest.permission.BLUETOOTH_CONNECT)) {
                        gatt.discoverServices()
                    } else {
                        Log.w(TAG, "Missing BLUETOOTH_CONNECT — cannot discover services")
                        emit("connect_permission_missing")
                    }
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    Log.i(TAG, "GATT disconnected (status=$status)")
                    emit("disconnected", mapOf("status" to status))
                    targetCharacteristic = null
                    if (hasPermission(Manifest.permission.BLUETOOTH_CONNECT)) {
                        gatt.close()
                    }
                    this@BleManager.gatt = null
                    scheduleReconnect(reason = "disconnected")
                }
                else -> Log.d(TAG, "GATT state changed → $newState (status=$status)")
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.w(TAG, "Service discovery failed, status=$status")
                emit("services_discovery_failed", mapOf("status" to status))
                return
            }

            val serviceCandidates = buildList {
                add(serviceUuid)
                addAll(serviceUuidAlternates)
            }
            val charCandidates = buildList {
                add(charUuid)
                addAll(charUuidAlternates)
            }
            var service: BluetoothGattService? =
                serviceCandidates.firstNotNullOfOrNull { candidate -> gatt.getService(candidate) }
            var characteristic: BluetoothGattCharacteristic? = null

            if (service != null) {
                characteristic = charCandidates.firstNotNullOfOrNull { candidate ->
                    service!!.getCharacteristic(candidate)
                }
            }

            if (service == null && allowWritableCharFallback) {
                // Service UUIDs can vary between models/firmware. Try finding known
                // characteristic UUIDs across all services before giving up.
                for (svc in gatt.services) {
                    val byKnownChar = charCandidates.firstNotNullOfOrNull { candidate ->
                        svc.getCharacteristic(candidate)
                    }
                    if (byKnownChar != null) {
                        service = svc
                        characteristic = byKnownChar
                        emit("service_fallback", mapOf(
                            "service_uuid" to svc.uuid.toString(),
                            "characteristic_uuid" to byKnownChar.uuid.toString(),
                            "mode" to "known_characteristic",
                        ))
                        break
                    }
                }
            }

            if (service == null && allowWritableCharFallback) {
                // Last-resort fallback: pick the first service that exposes any writable
                // characteristic so unknown profiles can still be exercised.
                for (svc in gatt.services) {
                    val writable = svc.characteristics.firstOrNull { c ->
                        val props = c.properties
                        (props and PROPERTY_WRITE) != 0 || (props and PROPERTY_WRITE_NO_RESPONSE) != 0
                    }
                    if (writable != null) {
                        service = svc
                        characteristic = writable
                        emit("service_fallback", mapOf(
                            "service_uuid" to svc.uuid.toString(),
                            "characteristic_uuid" to writable.uuid.toString(),
                            "mode" to "any_writable_service",
                        ))
                        break
                    }
                }
            }

            if (service == null) {
                Log.w(TAG, "Service $serviceUuid not found on remote device")
                emit("service_missing", mapOf(
                    "service_uuid" to serviceUuid.toString(),
                    "service_candidates" to serviceCandidates.map { it.toString() },
                    "available_services" to gatt.services.map { it.uuid.toString() },
                ))
                return
            }

            if (characteristic == null && allowWritableCharFallback) {
                characteristic = service.characteristics.firstOrNull { c ->
                    val props = c.properties
                    (props and PROPERTY_WRITE) != 0 || (props and PROPERTY_WRITE_NO_RESPONSE) != 0
                }
                if (characteristic != null) {
                    Log.w(
                        TAG,
                        "Preferred characteristic not found; using writable fallback ${characteristic.uuid}",
                    )
                    emit("characteristic_fallback", mapOf(
                        "service_uuid" to service.uuid.toString(),
                        "characteristic_uuid" to characteristic.uuid.toString(),
                        "mode" to "first_writable",
                    ))
                }
            }

            if (characteristic == null && allowWritableCharFallback) {
                // As a final fallback, search all services for any writable characteristic.
                for (svc in gatt.services) {
                    val writable = svc.characteristics.firstOrNull { c ->
                        val props = c.properties
                        (props and PROPERTY_WRITE) != 0 || (props and PROPERTY_WRITE_NO_RESPONSE) != 0
                    }
                    if (writable != null) {
                        service = svc
                        characteristic = writable
                        emit("characteristic_fallback", mapOf(
                            "service_uuid" to svc.uuid.toString(),
                            "characteristic_uuid" to writable.uuid.toString(),
                            "mode" to "any_writable",
                        ))
                        break
                    }
                }
            }

            if (characteristic == null) {
                val selectedService = service
                if (selectedService == null) {
                    emit("characteristic_missing", mapOf(
                        "characteristic_uuid" to charUuid.toString(),
                        "characteristic_candidates" to charCandidates.map { it.toString() },
                        "service_uuid" to serviceUuid.toString(),
                        "available_services" to gatt.services.map { it.uuid.toString() },
                    ))
                    return
                }
                Log.w(TAG, "Characteristic $charUuid not found in service $serviceUuid")
                emit("characteristic_missing", mapOf(
                    "characteristic_uuid" to charUuid.toString(),
                    "characteristic_candidates" to charCandidates.map { it.toString() },
                    "service_uuid" to selectedService.uuid.toString(),
                    "available_characteristics" to selectedService.characteristics.map { it.uuid.toString() },
                ))
                return
            }

            val selectedService = service ?: run {
                emit("services_discovery_failed", mapOf("reason" to "service_lost_after_fallback"))
                return
            }

            Log.i(TAG, "Services discovered — characteristic ${characteristic.uuid} ready")
            emit("ready", mapOf(
                "service_uuid" to selectedService.uuid.toString(),
                "characteristic_uuid" to characteristic.uuid.toString(),
            ))
            targetCharacteristic = characteristic

            // Drain any commands that arrived before the characteristic was ready.
            drainPendingCommands(characteristic)
        }

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int,
        ) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                Log.d(TAG, "Characteristic write succeeded (uuid=${characteristic.uuid})")
                emit("write_ok", mapOf("characteristic_uuid" to characteristic.uuid.toString()))
            } else {
                Log.w(TAG, "Characteristic write failed, status=$status")
                emit("write_failed", mapOf("status" to status, "characteristic_uuid" to characteristic.uuid.toString()))
            }
        }

        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int,
        ) {
            if (characteristic.uuid != BATTERY_LEVEL_CHAR_UUID) {
                return
            }
            if (status != BluetoothGatt.GATT_SUCCESS) {
                emit("battery_read_failed", mapOf("status" to status))
                return
            }
            tryEmitBatteryLevel(characteristic.value)
        }

        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
            status: Int,
        ) {
            if (characteristic.uuid != BATTERY_LEVEL_CHAR_UUID) {
                return
            }
            if (status != BluetoothGatt.GATT_SUCCESS) {
                emit("battery_read_failed", mapOf("status" to status))
                return
            }
            tryEmitBatteryLevel(value)
        }
    }

    // ------------------------------------------------------------------
    //  Helpers
    // ------------------------------------------------------------------

    /**
     * Writes [data] to [characteristic] using the appropriate API for the
     * running Android version.
     */
    private fun writeCharacteristic(characteristic: BluetoothGattCharacteristic, data: ByteArray) {
        if (!hasPermission(Manifest.permission.BLUETOOTH_CONNECT)) {
            Log.w(TAG, "Missing BLUETOOTH_CONNECT — cannot write characteristic")
            emit("connect_permission_missing")
            return
        }

        val currentGatt = gatt
        if (currentGatt == null) {
            Log.w(TAG, "GATT is null — cannot write characteristic")
            emit("write_failed", mapOf("reason" to "gatt_null"))
            return
        }

        val props = characteristic.properties
        val supportsNoResponse = (props and PROPERTY_WRITE_NO_RESPONSE) != 0
        val supportsWithResponse = (props and PROPERTY_WRITE) != 0
        val writeType = if (supportsNoResponse) {
            BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        } else {
            BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        }

        Log.d(TAG, "Writing ${data.size} bytes to $charUuid (type=$writeType noResp=$supportsNoResponse withResp=$supportsWithResponse)")
        emit("write_attempt", mapOf(
            "bytes" to data.size,
            "write_type" to writeType,
            "supports_no_response" to supportsNoResponse,
            "supports_with_response" to supportsWithResponse,
        ))

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // API 33+: new type-safe overload
            var effectiveWriteType = writeType
            var result = currentGatt.writeCharacteristic(
                characteristic,
                data,
                writeType,
            )
            if (result != BluetoothGatt.GATT_SUCCESS &&
                writeType == BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE &&
                supportsWithResponse) {
                Log.w(TAG, "writeCharacteristic (API33) no-response returned $result; retrying with response")
                result = currentGatt.writeCharacteristic(
                    characteristic,
                    data,
                    BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT,
                )
                if (result == BluetoothGatt.GATT_SUCCESS) {
                    effectiveWriteType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                    emit("write_attempt", mapOf(
                        "bytes" to data.size,
                        "write_type" to BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT,
                        "retry_after_no_response_failure" to true,
                    ))
                }
            }
            if (result != BluetoothGatt.GATT_SUCCESS) {
                Log.w(TAG, "writeCharacteristic (API33) returned $result")
                emit("write_failed", mapOf("status" to result))
            } else if (effectiveWriteType == BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE) {
                // No-response writes may not trigger onCharacteristicWrite on all devices.
                emit("write_ok", mapOf("mode" to "no_response", "bytes" to data.size))
            }
        } else {
            @Suppress("DEPRECATION")
            characteristic.value = data
            characteristic.writeType = writeType
            @Suppress("DEPRECATION")
            val enqueued = currentGatt.writeCharacteristic(characteristic)
            if (!enqueued) {
                Log.w(TAG, "writeCharacteristic (legacy) returned false")
                emit("write_failed", mapOf("reason" to "enqueue_failed"))
            } else if (writeType == BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE) {
                emit("write_ok", mapOf("mode" to "no_response", "bytes" to data.size))
            }
        }
    }

    /** Writes all queued commands in FIFO order. */
    private fun drainPendingCommands(characteristic: BluetoothGattCharacteristic) {
        while (pendingCommands.isNotEmpty()) {
            writeCharacteristic(characteristic, pendingCommands.removeFirst())
        }
    }

    private fun finishCandidateScan() {
        if (!isScanning) return
        isScanning = false
        if (hasPermission(Manifest.permission.BLUETOOTH_SCAN)) {
            candidateScanCallback?.let { scanner?.stopScan(it) }
        }
        val results = candidateScanResults.values.toList()
        candidateScanResults.clear()
        candidateScanCallback = null
        scanner = null
        emit("scan_stopped", mapOf("mode" to "candidates", "count" to results.size))
        val completion = candidateScanCompletion
        candidateScanCompletion = null
        completion?.invoke(results)
    }

    private fun saveLastDeviceAddress(address: String?) {
        if (address.isNullOrBlank()) return
        prefs.edit().putString(savedAddressKey, address).apply()
    }

    private fun readLastDeviceAddress(): String? = prefs.getString(savedAddressKey, null)

    private fun scheduleReconnect(reason: String) {
        if (!autoReconnectEnabled) return
        if (isScanning || gatt != null) return

        val address = readLastDeviceAddress()
        if (address.isNullOrBlank()) {
            Log.d(TAG, "No saved BLE address available for auto-reconnect")
            emit("reconnect_skipped", mapOf("reason" to "no_saved_address"))
            return
        }
        if (reconnectAttempts >= MAX_RECONNECT_ATTEMPTS) {
            Log.w(TAG, "Auto-reconnect max attempts reached for $address")
            emit("reconnect_exhausted", mapOf("address" to address))
            return
        }

        val exponent = reconnectAttempts.coerceAtMost(5)
        val delay = (INITIAL_RECONNECT_DELAY_MS shl exponent).coerceAtMost(MAX_RECONNECT_DELAY_MS)
        reconnectAttempts += 1
        cancelReconnect()
        Log.i(TAG, "Scheduling BLE reconnect #$reconnectAttempts in ${delay}ms ($reason)")
        emit("reconnect_scheduled", mapOf(
            "attempt" to reconnectAttempts,
            "delay_ms" to delay,
            "reason" to reason,
            "address" to address,
        ))
        mainHandler.postDelayed(reconnectRunnable, delay)
    }

    private fun reconnectToLastKnownDevice(reason: String) {
        if (!autoReconnectEnabled) return
        if (gatt != null || isScanning) return
        if (!hasPermission(Manifest.permission.BLUETOOTH_CONNECT)) {
            emit("connect_permission_missing")
            return
        }

        val adapter = bluetoothAdapter
        if (adapter == null || !adapter.isEnabled) {
            emit("scan_unavailable")
            return
        }

        val address = readLastDeviceAddress()
        if (address.isNullOrBlank()) return

        try {
            val device = adapter.getRemoteDevice(address)
            emit("reconnecting", mapOf("address" to address, "reason" to reason))
            connect(device)
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "Saved BLE address is invalid: $address", e)
            emit("reconnect_skipped", mapOf("reason" to "invalid_saved_address"))
        }
    }

    private fun cancelReconnect() {
        mainHandler.removeCallbacks(reconnectRunnable)
    }

    private fun tryEmitBatteryLevel(bytes: ByteArray?) {
        if (bytes == null || bytes.isEmpty()) {
            emit("battery_read_failed", mapOf("reason" to "empty_value"))
            return
        }
        val raw = bytes.first().toInt() and 0xFF
        val batteryPct = raw.coerceIn(0, 100)
        emit(
            "battery_level",
            mapOf(
                "battery_pct" to batteryPct,
                "raw" to raw,
                "characteristic_uuid" to BATTERY_LEVEL_CHAR_UUID.toString(),
            ),
        )
    }

    /** Returns true if [permission] has been granted. */
    private fun hasPermission(permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    private fun emit(type: String, payload: Map<String, Any?> = emptyMap()) {
        try {
            eventListener?.onEvent(type, payload)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to emit BLE event: $type", e)
        }
    }
}
