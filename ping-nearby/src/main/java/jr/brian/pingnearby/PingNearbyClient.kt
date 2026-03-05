package jr.brian.pingnearby

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.ParcelFileDescriptor
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.AdvertisingOptions
import com.google.android.gms.nearby.connection.ConnectionInfo
import com.google.android.gms.nearby.connection.ConnectionLifecycleCallback
import com.google.android.gms.nearby.connection.ConnectionResolution
import com.google.android.gms.nearby.connection.ConnectionsStatusCodes
import com.google.android.gms.nearby.connection.DiscoveredEndpointInfo
import com.google.android.gms.nearby.connection.DiscoveryOptions
import com.google.android.gms.nearby.connection.EndpointDiscoveryCallback
import com.google.android.gms.nearby.connection.Payload
import com.google.android.gms.nearby.connection.PayloadCallback
import com.google.android.gms.nearby.connection.PayloadTransferUpdate
import com.google.android.gms.nearby.connection.Strategy
import java.io.File
import java.io.FileOutputStream

/**
 * Handles peer-to-peer image and data transfer using the Nearby Connections API.
 *
 * Advertises and discovers simultaneously using [Strategy.P2P_CLUSTER]. Connections
 * are accepted automatically without UI, matching the core Ping BLE behavior.
 *
 * @param context Application or service context.
 * @param serviceId Unique identifier for this session — use the app's package name by convention.
 */
@Suppress("unused")
class PingNearbyClient(
    private val context: Context,
    private val serviceId: String
) {
    /** Fires when a remote endpoint is found. Connect to it or present it in UI. */
    var onEndpointFound: ((endpointId: String, name: String) -> Unit)? = null

    /** Fires when a connection to a remote endpoint is fully established. */
    var onConnected: ((endpointId: String, name: String) -> Unit)? = null

    /** Fires when a previously connected endpoint disconnects. */
    var onDisconnected: ((endpointId: String) -> Unit)? = null

    /** Fires when raw bytes are received from a remote endpoint. */
    var onBytesReceived: ((endpointId: String, bytes: ByteArray) -> Unit)? = null

    /**
     * Fires when bytes are received and automatically classified by [PingMediaDetector].
     * Use this instead of [onBytesReceived] when the payload may be an image, GIF, or video.
     */
    var onMediaReceived: ((endpointId: String, bytes: ByteArray, type: PingMediaType) -> Unit)? = null

    /** Fires when a complete image file has been received and decoded. */
    var onImageReceived: ((endpointId: String, bitmap: Bitmap) -> Unit)? = null

    /** Fires when a non-image file (e.g. video) is received. Caller is responsible for closing [pfd]. */
    var onFileReceived: ((endpointId: String, pfd: ParcelFileDescriptor) -> Unit)? = null

    /** Fires during file transfers with a 0..1 progress fraction. */
    var onTransferUpdate: ((endpointId: String, progress: Float) -> Unit)? = null

    /** Fires when a file transfer fails or is canceled before completion. */
    var onTransferFailed: ((endpointId: String) -> Unit)? = null

    private val connectionsClient = Nearby.getConnectionsClient(context)

    // Temp files created for outgoing image sends, cleaned up on completion
    private val outgoingTempFiles = mutableMapOf<Long, File>()

    private var localDisplayName = ""

    // Cached remote names keyed by endpointId, populated in onConnectionInitiated
    private val pendingNames = mutableMapOf<String, String>()

    // ── Connection lifecycle ──────────────────────────────────────────────────

    private val connectionLifecycleCallback = object : ConnectionLifecycleCallback() {

        override fun onConnectionInitiated(endpointId: String, info: ConnectionInfo) {
            pendingNames[endpointId] = info.endpointName
            // Auto-accept all connections — no confirmation UI, matching Ping BLE behavior.
            connectionsClient.acceptConnection(endpointId, payloadCallback)
        }

        override fun onConnectionResult(endpointId: String, result: ConnectionResolution) {
            if (result.status.statusCode == ConnectionsStatusCodes.STATUS_OK) {
                val name = pendingNames.remove(endpointId) ?: endpointId
                onConnected?.invoke(endpointId, name)
            } else {
                pendingNames.remove(endpointId)
            }
        }

        override fun onDisconnected(endpointId: String) {
            onDisconnected?.invoke(endpointId)
        }
    }

    // ── Endpoint discovery ────────────────────────────────────────────────────

    private val endpointDiscoveryCallback = object : EndpointDiscoveryCallback() {

        override fun onEndpointFound(endpointId: String, info: DiscoveredEndpointInfo) {
            onEndpointFound?.invoke(endpointId, info.endpointName)
            connectionsClient.requestConnection(
                localDisplayName,
                endpointId,
                connectionLifecycleCallback
            )
        }

        override fun onEndpointLost(endpointId: String) {}
    }

    // ── Payload handling ──────────────────────────────────────────────────────

    private val payloadCallback = object : PayloadCallback() {

        override fun onPayloadReceived(endpointId: String, payload: Payload) {
            when (payload.type) {
                Payload.Type.BYTES -> {
                    val bytes = payload.asBytes() ?: return
                    val type = PingMediaDetector.detect(bytes)
                    onMediaReceived?.invoke(endpointId, bytes, type)
                    onBytesReceived?.invoke(endpointId, bytes)
                }
                Payload.Type.FILE -> {
                    val pfd = payload.asFile()?.asParcelFileDescriptor() ?: return
                    val bitmap = BitmapFactory.decodeFileDescriptor(pfd.fileDescriptor)
                    if (bitmap != null) {
                        pfd.close()
                        onImageReceived?.invoke(endpointId, bitmap)
                    } else {
                        onFileReceived?.invoke(endpointId, pfd)
                    }
                }
                else -> Unit
            }
        }

        override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) {
            when (update.status) {
                PayloadTransferUpdate.Status.FAILURE,
                PayloadTransferUpdate.Status.CANCELED -> {
                    outgoingTempFiles.remove(update.payloadId)?.delete()
                    onTransferFailed?.invoke(endpointId)
                }
                PayloadTransferUpdate.Status.SUCCESS -> {
                    outgoingTempFiles.remove(update.payloadId)?.delete()
                    onTransferUpdate?.invoke(endpointId, 1f)
                }
                else -> {
                    val progress = if (update.totalBytes > 0) {
                        update.bytesTransferred.toFloat() / update.totalBytes
                    } else 0f
                    onTransferUpdate?.invoke(endpointId, progress)
                }
            }
        }
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Starts advertising and discovery simultaneously.
     *
     * Call [PingNearbyPermissions.requestNearbyPermissions] and verify
     * [PingNearbyPermissions.hasNearbyPermissions] before calling this.
     *
     * @param displayName Human-readable name broadcast to nearby devices.
     */
    fun start(displayName: String) {
        localDisplayName = displayName

        connectionsClient.startAdvertising(
            displayName,
            serviceId,
            connectionLifecycleCallback,
            AdvertisingOptions.Builder().setStrategy(Strategy.P2P_CLUSTER).build()
        )

        connectionsClient.startDiscovery(
            serviceId,
            endpointDiscoveryCallback,
            DiscoveryOptions.Builder().setStrategy(Strategy.P2P_CLUSTER).build()
        )
    }

    /** Stops advertising, discovery, and disconnects all endpoints. */
    fun stop() {
        connectionsClient.stopAdvertising()
        connectionsClient.stopDiscovery()
        connectionsClient.stopAllEndpoints()
        pendingNames.clear()
        outgoingTempFiles.values.forEach { it.delete() }
        outgoingTempFiles.clear()
    }

    /**
     * Compresses [bitmap] to JPEG at quality 60 and sends it to [endpointId] as a file payload.
     * Progress is reported via [onTransferUpdate].
     */
    fun sendImage(endpointId: String, bitmap: Bitmap) {
        val file = File(context.cacheDir, "ping_nearby_out_${System.currentTimeMillis()}.jpg")
        FileOutputStream(file).use { out ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, 60, out)
        }
        val payload = Payload.fromFile(file)
        outgoingTempFiles[payload.id] = file
        connectionsClient.sendPayload(endpointId, payload)
    }

    /**
     * Sends a file identified by [uri] to [endpointId] as a file payload.
     * Suitable for videos or any arbitrary file. Progress is reported via [onTransferUpdate].
     */
    fun sendFile(endpointId: String, uri: Uri) {
        val pfd = context.contentResolver.openFileDescriptor(uri, "r") ?: return
        val payload = Payload.fromFile(pfd)
        connectionsClient.sendPayload(endpointId, payload)
    }

    /**
     * Sends raw [data] bytes to [endpointId]. Suitable for small payloads such as
     * structured data or control messages. For larger transfers use [sendImage] or [sendFile].
     */
    fun sendBytes(endpointId: String, data: ByteArray) {
        connectionsClient.sendPayload(endpointId, Payload.fromBytes(data))
    }
}
