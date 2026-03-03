
package jr.brian.ping

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.util.Log
import androidx.annotation.RequiresPermission
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class PingManager(
    private val context: Context,
    @Volatile private var localProfile: PingProfile,
    private val callback: PingCallback
) {
    companion object {
        private const val TAG = "PingManager"
        private const val RECONNECT_COOLDOWN_MS = 60_000L
        private const val CONNECTION_TIMEOUT_MS = 15_000L
        private const val MAX_CONCURRENT_CONNECTIONS = 4
        private const val MTU_SIZE = 512

        val SERVICE_UUID: UUID = UUID.fromString("0000abcd-0000-1000-8000-00805f9b34fb")
        val CHARACTERISTIC_UUID: UUID = UUID.fromString("0000bcde-0000-1000-8000-00805f9b34fb")
    }

    private val bluetoothManager = context.getSystemService(BluetoothManager::class.java)
    private val bluetoothAdapter: BluetoothAdapter = bluetoothManager.adapter
    private var gattServer: BluetoothGattServer? = null
    private var bleScanner: BluetoothLeScanner? = null
    private var bleAdvertiser: BluetoothLeAdvertiser? = null

    private val discoveredDevices = ConcurrentHashMap<String, Long>()
    private val connectionSemaphore = Semaphore(MAX_CONCURRENT_CONNECTIONS)
    private val mainHandler = Handler(Looper.getMainLooper())

    // Recreated on each start() so stop()+start() works correctly.
    private var scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private fun hasPermission(permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    private fun hasConnectPermission() = hasPermission(Manifest.permission.BLUETOOTH_CONNECT)
    private fun hasScanPermission() = hasPermission(Manifest.permission.BLUETOOTH_SCAN)
    private fun hasAdvertisePermission() = hasPermission(Manifest.permission.BLUETOOTH_ADVERTISE)

    fun start() {
        if (!bluetoothAdapter.isEnabled) {
            Log.e(TAG, "Bluetooth is not enabled")
            callback.onStateChanged(false)
            return
        }
        scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        startGattServer()
        startAdvertising()
        startScanning()
        callback.onStateChanged(true)
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun stop() {
        scope.cancel()

        bleAdvertiser?.let {
            if (hasAdvertisePermission()) {
                try { it.stopAdvertising(advertiseCallback) }
                catch (e: SecurityException) { Log.e(TAG, "Failed to stop advertising", e) }
            }
        }

        bleScanner?.let {
            if (hasScanPermission()) {
                try { it.stopScan(scanCallback) }
                catch (e: SecurityException) { Log.e(TAG, "Failed to stop scan", e) }
            }
        }

        gattServer?.close()
        gattServer = null
        callback.onStateChanged(false)
    }

    fun updateProfile(newProfile: PingProfile) {
        localProfile = newProfile
    }

    // ── GATT server (we are the peripheral / responder) ──────────────────────

    private fun startGattServer() {
        if (!hasConnectPermission()) {
            Log.e(TAG, "Missing BLUETOOTH_CONNECT permission")
            return
        }
        try {
            val characteristic = BluetoothGattCharacteristic(
                CHARACTERISTIC_UUID,
                BluetoothGattCharacteristic.PROPERTY_READ or BluetoothGattCharacteristic.PROPERTY_WRITE,
                BluetoothGattCharacteristic.PERMISSION_READ or BluetoothGattCharacteristic.PERMISSION_WRITE
            )
            val service = BluetoothGattService(
                SERVICE_UUID,
                BluetoothGattService.SERVICE_TYPE_PRIMARY
            ).also { it.addCharacteristic(characteristic) }

            gattServer = bluetoothManager.openGattServer(context, gattServerCallback)
            gattServer?.addService(service)
            Log.d(TAG, "GATT server started")
        } catch (e: SecurityException) {
            Log.e(TAG, "Failed to start GATT server", e)
        }
    }

    private val gattServerCallback = object : BluetoothGattServerCallback() {

        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            Log.d(TAG, "Server connection state: $newState for ${device.address}")
        }

        override fun onCharacteristicReadRequest(
            device: BluetoothDevice, requestId: Int, offset: Int,
            characteristic: BluetoothGattCharacteristic
        ) {
            if (characteristic.uuid != CHARACTERISTIC_UUID) return
            if (!hasConnectPermission()) return
            try {
                val payload = localProfile.toBytes()
                val chunk = if (offset < payload.size) payload.copyOfRange(offset, payload.size) else ByteArray(0)
                gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, chunk)
            } catch (e: SecurityException) {
                Log.e(TAG, "Failed to send read response", e)
            }
        }

        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice, requestId: Int,
            characteristic: BluetoothGattCharacteristic,
            preparedWrite: Boolean, responseNeeded: Boolean,
            offset: Int, value: ByteArray
        ) {
            if (characteristic.uuid != CHARACTERISTIC_UUID) return
            try {
                val profile = PingProfile.fromBytes(value)
                mainHandler.post { callback.onEncounter(device.address, profile) }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to parse incoming profile", e)
                mainHandler.post { callback.onEncounterError(device.address, e.message ?: "Parse error") }
            }
            if (responseNeeded && hasConnectPermission()) {
                try {
                    gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
                } catch (e: SecurityException) {
                    Log.e(TAG, "Failed to send write response", e)
                }
            }
        }
    }

    // ── Advertising ───────────────────────────────────────────────────────────

    private fun startAdvertising() {
        if (!hasAdvertisePermission()) {
            Log.e(TAG, "Missing BLUETOOTH_ADVERTISE permission")
            return
        }
        bleAdvertiser = bluetoothAdapter.bluetoothLeAdvertiser
        if (bleAdvertiser == null) {
            Log.e(TAG, "Device does not support BLE advertising")
            return
        }
        try {
            val settings = AdvertiseSettings.Builder()
                .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_BALANCED)
                .setConnectable(true)
                .setTimeout(0)
                .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM)
                .build()

            val data = AdvertiseData.Builder()
                .setIncludeDeviceName(false)
                .addServiceUuid(ParcelUuid(SERVICE_UUID))
                .build()

            bleAdvertiser?.startAdvertising(settings, data, advertiseCallback)
        } catch (e: SecurityException) {
            Log.e(TAG, "Failed to start advertising", e)
        }
    }

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings) {
            Log.d(TAG, "Advertising started")
        }
        override fun onStartFailure(errorCode: Int) {
            Log.e(TAG, "Advertising failed: $errorCode")
            mainHandler.post { callback.onEncounterError("advertiser", "Advertise failed: $errorCode") }
        }
    }

    // ── Scanning ──────────────────────────────────────────────────────────────

    private fun startScanning() {
        if (!hasScanPermission()) {
            Log.e(TAG, "Missing scan permission")
            return
        }
        try {
            bleScanner = bluetoothAdapter.bluetoothLeScanner
            val filter = ScanFilter.Builder()
                .setServiceUuid(ParcelUuid(SERVICE_UUID))
                .build()
            val settings = ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_BALANCED)
                .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
                .build()
            bleScanner?.startScan(listOf(filter), settings, scanCallback)
            Log.d(TAG, "Scanning started")
        } catch (e: SecurityException) {
            Log.e(TAG, "Failed to start scanning", e)
        }
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val address = result.device.address
            val now = System.currentTimeMillis()
            // Atomic check-and-set: only launch if cooldown has expired.
            var shouldConnect = false
            discoveredDevices.compute(address) { _, lastSeen ->
                if (lastSeen == null || now - lastSeen > RECONNECT_COOLDOWN_MS) {
                    shouldConnect = true
                    now
                } else {
                    lastSeen
                }
            }
            if (shouldConnect) scope.launch { connectAndExchange(result.device) }
        }

        override fun onScanFailed(errorCode: Int) {
            Log.e(TAG, "Scan failed: $errorCode")
        }
    }

    // ── Concurrent GATT client (we are the initiator) ────────────────────────

    /**
     * Bridges the async BluetoothGattCallback into suspend-friendly
     * CompletableDeferred steps. If the device disconnects at any point,
     * failAll() cancels every pending step so the coroutine surfaces the error
     * immediately rather than hanging until the timeout.
     */
    private inner class GattSession {
        private val connectionDeferred = CompletableDeferred<Unit>()
        private val mtuDeferred = CompletableDeferred<Unit>()
        private val servicesDeferred = CompletableDeferred<Unit>()
        private val readDeferred = CompletableDeferred<ByteArray>()
        private val writeDeferred = CompletableDeferred<Unit>()

        private fun failAll(e: Exception) {
            connectionDeferred.completeExceptionally(e)
            mtuDeferred.completeExceptionally(e)
            servicesDeferred.completeExceptionally(e)
            readDeferred.completeExceptionally(e)
            writeDeferred.completeExceptionally(e)
        }

        val gattCallback = object : BluetoothGattCallback() {
            override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
                when (newState) {
                    BluetoothProfile.STATE_CONNECTED ->
                        connectionDeferred.complete(Unit)
                    BluetoothProfile.STATE_DISCONNECTED ->
                        failAll(Exception("Disconnected during exchange (status=$status)"))
                }
            }

            override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
                if (status == BluetoothGatt.GATT_SUCCESS) mtuDeferred.complete(Unit)
                else failAll(Exception("MTU negotiation failed (status=$status)"))
            }

            override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
                if (status == BluetoothGatt.GATT_SUCCESS) servicesDeferred.complete(Unit)
                else failAll(Exception("Service discovery failed (status=$status)"))
            }

            @Deprecated("Deprecated in Java")
            @Suppress("DEPRECATION")
            override fun onCharacteristicRead(
                gatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
                status: Int
            ) {
                if (status == BluetoothGatt.GATT_SUCCESS) readDeferred.complete(characteristic.value)
                else failAll(Exception("Characteristic read failed (status=$status)"))
            }

            override fun onCharacteristicWrite(
                gatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
                status: Int
            ) {
                if (status == BluetoothGatt.GATT_SUCCESS) writeDeferred.complete(Unit)
                else failAll(Exception("Characteristic write failed (status=$status)"))
            }
        }

        suspend fun awaitConnect() = connectionDeferred.await()
        suspend fun awaitMtu() = mtuDeferred.await()
        suspend fun awaitServices() = servicesDeferred.await()
        suspend fun awaitRead(): ByteArray = readDeferred.await()
        suspend fun awaitWrite() = writeDeferred.await()
    }

    private suspend fun connectAndExchange(device: BluetoothDevice) {
        if (!hasConnectPermission()) {
            Log.e(TAG, "Missing BLUETOOTH_CONNECT for connectAndExchange")
            return
        }
        // Semaphore caps concurrent outgoing connections to what the BLE stack
        // can reliably handle; excess devices queue here and connect in turn.
        connectionSemaphore.withPermit {
            Log.d(TAG, "Connecting to ${device.address}")
            val session = GattSession()
            val gatt: BluetoothGatt
            try {
                gatt = device.connectGatt(
                    context, false, session.gattCallback, BluetoothDevice.TRANSPORT_LE
                ) ?: run {
                    Log.e(TAG, "connectGatt returned null for ${device.address}")
                    return
                }
            } catch (e: SecurityException) {
                Log.e(TAG, "connectGatt failed", e)
                return
            }

            try {
                withTimeout(CONNECTION_TIMEOUT_MS) {
                    session.awaitConnect()

                    gatt.requestMtu(MTU_SIZE)
                    session.awaitMtu()

                    gatt.discoverServices()
                    session.awaitServices()

                    val characteristic = gatt.getService(SERVICE_UUID)
                        ?.getCharacteristic(CHARACTERISTIC_UUID)
                        ?: throw Exception("Characteristic not found on ${device.address}")

                    @Suppress("DEPRECATION")
                    gatt.readCharacteristic(characteristic)
                    val remoteProfile = PingProfile.fromBytes(session.awaitRead())

                    withContext(Dispatchers.Main) {
                        callback.onEncounter(device.address, remoteProfile)
                    }

                    @Suppress("DEPRECATION")
                    characteristic.value = localProfile.toBytes()
                    gatt.writeCharacteristic(characteristic)
                    session.awaitWrite()

                    Log.d(TAG, "Exchange complete with ${device.address}")
                }
            } catch (e: CancellationException) {
                throw e  // propagate scope cancellation (stop() was called)
            } catch (e: Exception) {
                Log.e(TAG, "Exchange failed with ${device.address}: ${e.message}")
                withContext(Dispatchers.Main) {
                    callback.onEncounterError(device.address, e.message ?: "Exchange error")
                }
            } finally {
                try { gatt.disconnect() } catch (_: SecurityException) {}
                try { gatt.close() } catch (_: SecurityException) {}
            }
        }
    }
}
