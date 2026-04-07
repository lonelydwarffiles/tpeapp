package com.tpeapp.ble

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Context
import android.content.pm.PackageManager
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
    private val scanTimeout: Long = DEFAULT_SCAN_TIMEOUT_MS,
) {

    // ------------------------------------------------------------------
    //  Companion — well-known generic UUIDs (placeholders; swap as needed)
    // ------------------------------------------------------------------

    companion object {
        private const val TAG = "BleManager"

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

        private const val DEFAULT_SCAN_TIMEOUT_MS = 10_000L
    }

    // ------------------------------------------------------------------
    //  State
    // ------------------------------------------------------------------

    private val bluetoothManager: BluetoothManager =
        context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager

    private val bluetoothAdapter: BluetoothAdapter? = bluetoothManager.adapter

    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile private var scanner: BluetoothLeScanner? = null
    @Volatile private var gatt: BluetoothGatt? = null
    @Volatile private var targetCharacteristic: BluetoothGattCharacteristic? = null
    @Volatile private var isScanning = false

    /** Pending payload waiting to be written once the characteristic is discovered. */
    private val pendingCommands: ArrayDeque<ByteArray> = ArrayDeque()

    // ------------------------------------------------------------------
    //  Public API
    // ------------------------------------------------------------------

    /**
     * Starts a BLE scan for any peripheral that advertises [serviceUuid].
     * The scan is automatically stopped after [scanTimeout] ms.
     * Requires `BLUETOOTH_SCAN` (API 31+) or `BLUETOOTH` + location (API ≤ 30).
     */
    fun startScan() {
        val adapter = bluetoothAdapter
        if (adapter == null || !adapter.isEnabled) {
            Log.w(TAG, "Bluetooth is not available or not enabled")
            return
        }

        if (!hasPermission(Manifest.permission.BLUETOOTH_SCAN)) {
            Log.w(TAG, "Missing BLUETOOTH_SCAN permission — cannot start scan")
            return
        }

        if (isScanning) {
            Log.d(TAG, "Scan already in progress")
            return
        }

        scanner = adapter.bluetoothLeScanner
        if (scanner == null) {
            Log.w(TAG, "BluetoothLeScanner not available")
            return
        }

        isScanning = true
        Log.i(TAG, "BLE scan started (timeout=${scanTimeout}ms)")
        scanner?.startScan(scanCallback) ?: run {
            isScanning = false
            Log.w(TAG, "BluetoothLeScanner became null before scan could start")
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
            return
        }
        isScanning = false
        scanner?.stopScan(scanCallback)
        scanner = null
        Log.i(TAG, "BLE scan stopped")
    }

    /**
     * Connects to [device] and begins GATT service discovery.
     * Requires `BLUETOOTH_CONNECT` (API 31+) or `BLUETOOTH` (API ≤ 30).
     */
    fun connect(device: BluetoothDevice) {
        if (!hasPermission(Manifest.permission.BLUETOOTH_CONNECT)) {
            Log.w(TAG, "Missing BLUETOOTH_CONNECT permission — cannot connect")
            return
        }
        Log.i(TAG, "Connecting to ${device.address}")
        // autoConnect = false for faster initial connection
        gatt = device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
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
            return
        }
        writeCharacteristic(characteristic, payload)
    }

    /** Disconnects from the GATT server and releases all resources. */
    fun disconnect() {
        if (!hasPermission(Manifest.permission.BLUETOOTH_CONNECT)) {
            Log.w(TAG, "Missing BLUETOOTH_CONNECT permission — cannot disconnect cleanly")
        } else {
            gatt?.disconnect()
        }
    }

    /** Closes the GATT client and frees all resources. Call when the manager is no longer needed. */
    fun close() {
        stopScan()
        pendingCommands.clear()
        targetCharacteristic = null
        if (hasPermission(Manifest.permission.BLUETOOTH_CONNECT)) {
            gatt?.close()
        }
        gatt = null
        Log.i(TAG, "BleManager closed")
    }

    // ------------------------------------------------------------------
    //  Scan callback
    // ------------------------------------------------------------------

    private val scanCallback = object : ScanCallback() {

        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device
            Log.d(TAG, "Scan result: ${device.address} rssi=${result.rssi}")
            // Connect to the first device found; stop scanning.
            stopScan()
            connect(device)
        }

        override fun onScanFailed(errorCode: Int) {
            isScanning = false
            Log.e(TAG, "BLE scan failed, error=$errorCode")
        }
    }

    // ------------------------------------------------------------------
    //  GATT callback
    // ------------------------------------------------------------------

    private val gattCallback = object : BluetoothGattCallback() {

        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    Log.i(TAG, "GATT connected — discovering services")
                    if (hasPermission(Manifest.permission.BLUETOOTH_CONNECT)) {
                        gatt.discoverServices()
                    } else {
                        Log.w(TAG, "Missing BLUETOOTH_CONNECT — cannot discover services")
                    }
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    Log.i(TAG, "GATT disconnected (status=$status)")
                    targetCharacteristic = null
                    if (hasPermission(Manifest.permission.BLUETOOTH_CONNECT)) {
                        gatt.close()
                    }
                    this@BleManager.gatt = null
                }
                else -> Log.d(TAG, "GATT state changed → $newState (status=$status)")
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.w(TAG, "Service discovery failed, status=$status")
                return
            }

            val service: BluetoothGattService? = gatt.getService(serviceUuid)
            if (service == null) {
                Log.w(TAG, "Service $serviceUuid not found on remote device")
                return
            }

            val characteristic: BluetoothGattCharacteristic? = service.getCharacteristic(charUuid)
            if (characteristic == null) {
                Log.w(TAG, "Characteristic $charUuid not found in service $serviceUuid")
                return
            }

            Log.i(TAG, "Services discovered — characteristic $charUuid ready")
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
            } else {
                Log.w(TAG, "Characteristic write failed, status=$status")
            }
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
            return
        }

        val currentGatt = gatt
        if (currentGatt == null) {
            Log.w(TAG, "GATT is null — cannot write characteristic")
            return
        }

        Log.d(TAG, "Writing ${data.size} bytes to $charUuid")

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // API 33+: new type-safe overload
            val result = currentGatt.writeCharacteristic(
                characteristic,
                data,
                BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT,
            )
            if (result != BluetoothGatt.GATT_SUCCESS) {
                Log.w(TAG, "writeCharacteristic (API33) returned $result")
            }
        } else {
            @Suppress("DEPRECATION")
            characteristic.value = data
            characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            @Suppress("DEPRECATION")
            val enqueued = currentGatt.writeCharacteristic(characteristic)
            if (!enqueued) {
                Log.w(TAG, "writeCharacteristic (legacy) returned false")
            }
        }
    }

    /** Writes all queued commands in FIFO order. */
    private fun drainPendingCommands(characteristic: BluetoothGattCharacteristic) {
        while (pendingCommands.isNotEmpty()) {
            writeCharacteristic(characteristic, pendingCommands.removeFirst())
        }
    }

    /** Returns true if [permission] has been granted. */
    private fun hasPermission(permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
}
