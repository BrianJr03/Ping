
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
import java.util.UUID

class PingManager(
    private val context: Context,
    private var localProfile: PingProfile,
    private val callback: PingCallback
) {
    companion object {
        private const val TAG = "PingManager"
        private const val RECONNECT_COOLDOWN_MS = 60_000L
        private const val MTU_SIZE = 512

        val SERVICE_UUID: UUID = UUID.fromString("0000abcd-0000-1000-8000-00805f9b34fb")
        val CHARACTERISTIC_UUID: UUID = UUID.fromString("0000bcde-0000-1000-8000-00805f9b34fb")
    }

    private val bluetoothManager = context.getSystemService(BluetoothManager::class.java)
    private val bluetoothAdapter: BluetoothAdapter = bluetoothManager.adapter
    private var gattServer: BluetoothGattServer? = null
    private var bleScanner: BluetoothLeScanner? = null
    private var bleAdvertiser: BluetoothLeAdvertiser? = null

    private val discoveredDevices = mutableMapOf<String, Long>()
    private val activeConnections = mutableMapOf<String, BluetoothGatt>()
    private val mainHandler = Handler(Looper.getMainLooper())

    private fun hasPermission(permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    private fun hasConnectPermission() =
        hasPermission(Manifest.permission.BLUETOOTH_CONNECT)

    private fun hasScanPermission() =
        hasPermission(Manifest.permission.BLUETOOTH_SCAN)

    private fun hasAdvertisePermission() =
        hasPermission(Manifest.permission.BLUETOOTH_ADVERTISE)

    fun start() {
        if (!bluetoothAdapter.isEnabled) {
            Log.e(TAG, "Bluetooth is not enabled")
            callback.onStateChanged(false)
            return
        }
        startGattServer()
        startAdvertising()
        startScanning()
        callback.onStateChanged(true)
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun stop() {
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

        if (hasConnectPermission()) {
            activeConnections.values.forEach {
                try { it.disconnect(); it.close() }
                catch (e: SecurityException) { Log.e(TAG, "Failed to disconnect", e) }
            }
        }
        activeConnections.clear()
        gattServer?.close()
        gattServer = null
        callback.onStateChanged(false)
    }

    fun updateProfile(newProfile: PingProfile) {
        localProfile = newProfile
    }

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
            if (!hasConnectPermission()) {
                Log.e(TAG, "Missing BLUETOOTH_CONNECT for read response")
                return
            }
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
            if (responseNeeded) {
                if (!hasConnectPermission()) return
                try {
                    gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
                } catch (e: SecurityException) {
                    Log.e(TAG, "Failed to send write response", e)
                }
            }
        }
    }

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
            val lastSeen = discoveredDevices[address] ?: 0L
            val now = System.currentTimeMillis()
            if (now - lastSeen > RECONNECT_COOLDOWN_MS) {
                discoveredDevices[address] = now
                connectAndExchange(result.device)
            }
        }
        override fun onScanFailed(errorCode: Int) {
            Log.e(TAG, "Scan failed: $errorCode")
        }
    }

    private fun connectAndExchange(device: BluetoothDevice) {
        if (!hasConnectPermission()) {
            Log.e(TAG, "Missing BLUETOOTH_CONNECT for connectAndExchange")
            return
        }
        Log.d(TAG, "Connecting to ${device.address}")
        try {
            device.connectGatt(context, false, object : BluetoothGattCallback() {

                override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
                    if (!hasConnectPermission()) return
                    try {
                        when (newState) {
                            BluetoothProfile.STATE_CONNECTED -> {
                                activeConnections[gatt.device.address] = gatt
                                gatt.requestMtu(MTU_SIZE)
                            }
                            BluetoothProfile.STATE_DISCONNECTED -> {
                                activeConnections.remove(gatt.device.address)
                                gatt.close()
                            }
                        }
                    } catch (e: SecurityException) {
                        Log.e(TAG, "Connection state change error", e)
                    }
                }

                override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
                    if (!hasConnectPermission()) return
                    try { gatt.discoverServices() }
                    catch (e: SecurityException) { Log.e(TAG, "discoverServices failed", e) }
                }

                override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
                    if (!hasConnectPermission()) return
                    if (status != BluetoothGatt.GATT_SUCCESS) {
                        try { gatt.disconnect() } catch (e: SecurityException) { gatt.close() }
                        return
                    }
                    val characteristic = gatt
                        .getService(SERVICE_UUID)
                        ?.getCharacteristic(CHARACTERISTIC_UUID)

                    if (characteristic == null) {
                        try { gatt.disconnect() } catch (e: SecurityException) { gatt.close() }
                    } else {
                        try { gatt.readCharacteristic(characteristic) }
                        catch (e: SecurityException) { Log.e(TAG, "readCharacteristic failed", e) }
                    }
                }

                @Deprecated("Deprecated in Java")
                override fun onCharacteristicRead(
                    gatt: BluetoothGatt,
                    characteristic: BluetoothGattCharacteristic,
                    status: Int
                ) {
                    if (!hasConnectPermission()) return
                    if (status == BluetoothGatt.GATT_SUCCESS) {
                        try {
                            val profile = PingProfile.fromBytes(characteristic.value)
                            mainHandler.post { callback.onEncounter(gatt.device.address, profile) }
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed to parse remote profile", e)
                        }
                        try {
                            characteristic.value = localProfile.toBytes()
                            gatt.writeCharacteristic(characteristic)
                        } catch (e: SecurityException) {
                            Log.e(TAG, "writeCharacteristic failed", e)
                        }
                    }
                }

                override fun onCharacteristicWrite(
                    gatt: BluetoothGatt,
                    characteristic: BluetoothGattCharacteristic,
                    status: Int
                ) {
                    if (!hasConnectPermission()) return
                    Log.d(TAG, "Exchange complete with ${gatt.device.address}")
                    try { gatt.disconnect() }
                    catch (e: SecurityException) { Log.e(TAG, "disconnect failed", e) }
                }
            }, BluetoothDevice.TRANSPORT_LE)
        } catch (e: SecurityException) {
            Log.e(TAG, "connectGatt failed", e)
        }
    }
}