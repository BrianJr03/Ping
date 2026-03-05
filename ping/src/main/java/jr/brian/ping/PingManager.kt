
package jr.brian.ping

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
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

/**
 * Core BLE engine that simultaneously advertises and scans for other Ping-enabled devices,
 * then performs a full profile exchange over GATT when a new device is discovered.
 *
 * ### Exchange protocol
 * 1. Scanner connects and negotiates MTU (512 bytes).
 * 2. Scanner enables notifications on the characteristic (writes CCCD).
 * 3. Scanner writes its own [PingProfile] to the characteristic.
 * 4. Peripheral receives the profile, fires [PingCallback.onEncounter], then notifies
 *    its own profile back as one or more **chunked notifications**.
 * 5. Scanner reassembles the chunks and fires [PingCallback.onEncounter].
 *
 * ### Concurrency
 * Up to [MAX_CONCURRENT_CONNECTIONS] outgoing connections run simultaneously.
 * The [reconnectCooldownMs] gate prevents redundant connections to the same address.
 *
 * ### Lifecycle
 * Call [start] once to begin advertising and scanning. Call [stop] to tear everything
 * down cleanly. [start] recreates the internal [CoroutineScope] so the instance can be
 * reused after a stop/start cycle.
 *
 * @param context            Application or service context.
 * @param localProfile       The profile to advertise and send to peers. Update live with [updateProfile].
 * @param reconnectCooldownMs Minimum milliseconds between GATT connection attempts to the same device.
 * @param callback           Receives encounter results, errors, and state changes.
 */
class PingManager(
    private val context: Context,
    @Volatile private var localProfile: PingProfile,
    private val reconnectCooldownMs: Long,
    private val callback: PingCallback
) {
    companion object {
        private const val TAG = "PingManager"
        private const val RECONNECT_COOLDOWN_MS = 60_000L
        private const val CONNECTION_TIMEOUT_MS = 15_000L
        private const val MAX_CONCURRENT_CONNECTIONS = 4
        private const val MTU_SIZE = 512

        /**
         * Marks the first byte of every chunked notification.
         * 0xCA is not a valid first byte for MsgPack maps (which start with 0x8x/0xDe/0xDF),
         * so the receiver can distinguish chunked from legacy single-packet payloads.
         * Chunk format: [CHUNK_MAGIC:1][totalChunks:1][chunkIndex:1][data…]
         */
        private const val CHUNK_MAGIC: Byte = 0xCA.toByte()

        /** Standard Client Characteristic Configuration Descriptor UUID. */
        private val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        val SERVICE_UUID: UUID = UUID.fromString("0000abcd-0000-1000-8000-00805f9b34fb")
        val CHARACTERISTIC_UUID: UUID = UUID.fromString("0000bcde-0000-1000-8000-00805f9b34fb")
    }

    private val bluetoothManager = context.getSystemService(BluetoothManager::class.java)
    private val bluetoothAdapter: BluetoothAdapter = bluetoothManager.adapter
    private var gattServer: BluetoothGattServer? = null
    private var bleScanner: BluetoothLeScanner? = null
    private var bleAdvertiser: BluetoothLeAdvertiser? = null

    /** Negotiated MTU per remote device address, tracked by the GATT server. */
    private val serverDeviceMtu = ConcurrentHashMap<String, Int>()

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

    /**
     * Starts the GATT server, BLE advertising, and BLE scanning.
     *
     * Fires [PingCallback.onStateChanged] with `true` on success, or `false` if
     * Bluetooth is disabled. Safe to call after [stop] — the internal coroutine scope
     * is recreated each time.
     */
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

    /**
     * Stops advertising, scanning, and the GATT server, and cancels all in-flight
     * coroutines. Fires [PingCallback.onStateChanged] with `false`.
     */
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
        serverDeviceMtu.clear()
        callback.onStateChanged(false)
    }

    /**
     * Replaces the local profile sent to future peers.
     *
     * Thread-safe — [localProfile] is marked `@Volatile`. Already-connected peers
     * receive the profile that was current at the moment their write request arrived.
     *
     * @param newProfile The updated profile to advertise going forward.
     */
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
            val cccd = BluetoothGattDescriptor(
                CCCD_UUID,
                BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE
            )
            val characteristic = BluetoothGattCharacteristic(
                CHARACTERISTIC_UUID,
                BluetoothGattCharacteristic.PROPERTY_WRITE or
                        BluetoothGattCharacteristic.PROPERTY_NOTIFY,
                BluetoothGattCharacteristic.PERMISSION_WRITE
            ).also { it.addDescriptor(cccd) }

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
            if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                serverDeviceMtu.remove(device.address)
            }
        }

        /** Track the MTU the client negotiated so chunks are sized correctly. */
        override fun onMtuChanged(device: BluetoothDevice, mtu: Int) {
            serverDeviceMtu[device.address] = mtu
            Log.d(TAG, "Server MTU updated for ${device.address}: $mtu")
        }

        /** Ack CCCD writes so the client knows notifications are enabled. */
        override fun onDescriptorWriteRequest(
            device: BluetoothDevice, requestId: Int,
            descriptor: BluetoothGattDescriptor,
            preparedWrite: Boolean, responseNeeded: Boolean,
            offset: Int, value: ByteArray
        ) {
            if (responseNeeded && hasConnectPermission()) {
                try {
                    gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
                } catch (e: SecurityException) {
                    Log.e(TAG, "Failed to ack CCCD write", e)
                }
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
            // Notify the client of our profile (chunked if payload exceeds MTU)
            val mtu = serverDeviceMtu[device.address] ?: MTU_SIZE
            scope.launch { sendChunkedNotification(device, localProfile.toBytes(), mtu) }
        }
    }

    /**
     * Sends [payload] to [device] as one or more chunked GATT notifications.
     *
     * Chunk format: `[CHUNK_MAGIC:1][totalChunks:1][chunkIndex:1][data…]`
     *
     * The magic byte (0xCA) is not a valid first byte for a MsgPack map, so the
     * client can safely distinguish chunked notifications from a legacy single-packet
     * payload without a separate version negotiation.
     *
     * @param mtu The negotiated ATT MTU. Each packet must be ≤ (mtu - 3) bytes;
     *            3 bytes are consumed by the ATT op-code + handle overhead.
     */
    private fun sendChunkedNotification(device: BluetoothDevice, payload: ByteArray, mtu: Int) {
        if (!hasConnectPermission()) return
        val characteristic = gattServer
            ?.getService(SERVICE_UUID)
            ?.getCharacteristic(CHARACTERISTIC_UUID) ?: return

        // ATT overhead: 3 bytes. Chunk header: 3 bytes (magic + totalChunks + chunkIndex).
        val maxDataPerChunk = maxOf(1, mtu - 3 - 3)
        val chunks = payload.toList().chunked(maxDataPerChunk) { it.toByteArray() }
        val totalChunks = chunks.size.coerceAtMost(255)

        Log.d(TAG, "Notifying ${device.address}: ${payload.size} bytes in $totalChunks chunk(s) (mtu=$mtu)")
        chunks.forEachIndexed { index, data ->
            if (index >= 255) return@forEachIndexed
            val packet = byteArrayOf(CHUNK_MAGIC, totalChunks.toByte(), index.toByte()) + data
            try {
                gattServer?.notifyCharacteristicChanged(device, characteristic, false, packet)
            } catch (e: SecurityException) {
                Log.e(TAG, "Failed to send notification chunk $index", e)
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
                if (lastSeen == null || now - lastSeen > reconnectCooldownMs) {
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
     * Bridges the async [BluetoothGattCallback] into suspend-friendly
     * [CompletableDeferred] steps.
     *
     * If the device disconnects at any point, [failAll] cancels every pending
     * step so the coroutine surfaces the error immediately rather than hanging
     * until the timeout fires.
     *
     * Chunk reassembly: notifications arrive one-at-a-time on the callback
     * thread. Each chunk is stored in [chunkBuffer] by index. Once every slot
     * is filled [profileDeferred] completes with the fully assembled payload.
     */
    private class GattSession {
        private val connectionDeferred = CompletableDeferred<Unit>()
        private val mtuDeferred        = CompletableDeferred<Unit>()
        private val servicesDeferred   = CompletableDeferred<Unit>()
        private val descriptorDeferred = CompletableDeferred<Unit>()
        private val writeDeferred      = CompletableDeferred<Unit>()
        private val profileDeferred    = CompletableDeferred<ByteArray>()

        /** Sized on first chunk arrival; null until then. */
        private var chunkBuffer: Array<ByteArray?>? = null

        private fun failAll(e: Exception) {
            connectionDeferred.completeExceptionally(e)
            mtuDeferred.completeExceptionally(e)
            servicesDeferred.completeExceptionally(e)
            descriptorDeferred.completeExceptionally(e)
            writeDeferred.completeExceptionally(e)
            profileDeferred.completeExceptionally(e)
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

            override fun onDescriptorWrite(
                gatt: BluetoothGatt,
                descriptor: BluetoothGattDescriptor,
                status: Int
            ) {
                if (status == BluetoothGatt.GATT_SUCCESS) descriptorDeferred.complete(Unit)
                else failAll(Exception("Descriptor write failed (status=$status)"))
            }

            override fun onCharacteristicWrite(
                gatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
                status: Int
            ) {
                if (status == BluetoothGatt.GATT_SUCCESS) writeDeferred.complete(Unit)
                else failAll(Exception("Characteristic write failed (status=$status)"))
            }

            override fun onCharacteristicChanged(
                gatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
                value: ByteArray
            ) {
                if (characteristic.uuid != CHARACTERISTIC_UUID) return

                // Non-chunked (or legacy) payload — complete immediately
                if (value.size < 4 || value[0] != CHUNK_MAGIC) {
                    profileDeferred.complete(value)
                    return
                }

                val totalChunks = value[1].toInt() and 0xFF
                val chunkIndex  = value[2].toInt() and 0xFF
                val data        = value.copyOfRange(3, value.size)

                // Allocate buffer on first chunk; subsequent chunks reuse it.
                val buf = chunkBuffer
                    ?: arrayOfNulls<ByteArray>(totalChunks).also { chunkBuffer = it }
                if (chunkIndex < buf.size) buf[chunkIndex] = data

                if (buf.all { it != null }) {
                    val assembled = buf.filterNotNull()
                        .fold(byteArrayOf()) { acc, b -> acc + b }
                    profileDeferred.complete(assembled)
                }
            }
        }

        suspend fun awaitConnect()            = connectionDeferred.await()
        suspend fun awaitMtu()                = mtuDeferred.await()
        suspend fun awaitServices()           = servicesDeferred.await()
        suspend fun awaitDescriptor()         = descriptorDeferred.await()
        suspend fun awaitWrite()              = writeDeferred.await()
        suspend fun awaitProfile(): ByteArray = profileDeferred.await()
    }

    @SuppressLint("MissingPermission")
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

                    // Enable notifications so we can receive the server's chunked profile
                    gatt.setCharacteristicNotification(characteristic, true)
                    val cccd = characteristic.getDescriptor(CCCD_UUID)
                    gatt.writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                    session.awaitDescriptor()

                    // Write our profile — this triggers the server to notify its profile back
                    gatt.writeCharacteristic(
                        characteristic,
                        localProfile.toBytes(),
                        BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                    )
                    session.awaitWrite()

                    // Collect chunked notifications and reassemble the remote profile
                    val remoteProfile = PingProfile.fromBytes(session.awaitProfile())
                    withContext(Dispatchers.Main) {
                        callback.onEncounter(device.address, remoteProfile)
                    }

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
