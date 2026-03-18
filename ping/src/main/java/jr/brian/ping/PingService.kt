package jr.brian.ping

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.annotation.RequiresPermission
import androidx.core.app.NotificationCompat

/**
 * Foreground service that runs [PingManager] to advertise, scan, and exchange profiles
 * with nearby BLE devices.
 *
 * ### Lifecycle
 * - Start with [buildIntent] + `startForegroundService` — calling it again with a new
 *   profile stops the current manager and restarts with the new profile immediately.
 * - Stop with `stopService(Intent(context, PingService::class.java))`.
 * - [BootReceiver] restarts this service automatically after device reboot.
 *
 * ### Receiving encounters
 * Set [onEncounter] **before** starting the service. It is a single static slot —
 * only the last assignment is active. The callback is dispatched on the **main thread**.
 *
 * ### Cooldowns
 * Two independent cooldowns control encounter frequency — see [encounterCooldownMs]
 * and [reconnectCooldownMs] for details.
 */
class PingService : Service() {
    companion object {
        private const val CHANNEL_ID = "ping_channel"
        private const val NOTIF_ID = 1001

        /** Intent extra key used to pass the serialized [PingProfile] to the service. */
        const val EXTRA_PROFILE = "extra_profile"

        /**
         * Minimum time in milliseconds between [onEncounter] callbacks for the **same device**.
         *
         * This is the app-level deduplication window — the callback will not fire again for a
         * given address until this window expires. Tracked in a static map so it survives
         * service restarts within the same process. Default: **30 000 ms (30 s)**.
         */
        var encounterCooldownMs: Long = 30_000L

        /**
         * Minimum time in milliseconds between GATT connection attempts to the **same device**.
         *
         * This is the BLE-level gate — even if scanning continuously sees a device, no new
         * connection is attempted until this window expires. Keeping this value larger than
         * [encounterCooldownMs] reduces battery drain and BLE congestion. Default: **60 000 ms (60 s)**.
         */
        var reconnectCooldownMs: Long = 60_000L

        /**
         * Builds a start intent for [PingService] carrying the serialized [profile].
         *
         * Pass the returned intent to `startForegroundService`. Calling this again with a
         * new profile while the service is already running restarts [PingManager] with the
         * updated profile.
         */
        fun buildIntent(context: Context, profile: PingProfile) =
            Intent(context, PingService::class.java).apply {
                putExtra(EXTRA_PROFILE, profile.toBytes())
            }

        /**
         * Called on the **main thread** whenever a new encounter passes the [encounterCooldownMs]
         * gate. Set this before starting the service.
         *
         * Only one callback is active at a time — each assignment replaces the previous one.
         * For multiple consumers see the broadcaster pattern in the README.
         */
        var onEncounter: ((String, PingProfile) -> Unit)? = null

        /** Title shown in the persistent foreground service notification. Default: `"Ping is Active"`. */
        var notificationTitle: String = "Ping is Active"

        /** Body text shown in the foreground notification. `null` or `""` omits the body entirely. */
        var notificationText: String? = null

        /** Optional [PendingIntent] launched when the user taps the foreground notification. */
        var notificationIntent: PendingIntent? = null

        // Static map so cooldown tracking survives service restarts within the same process.
        private val lastEncounters = mutableMapOf<String, Long>()

        /**
         * Clears the encounter cooldown map, allowing all devices to trigger [onEncounter]
         * immediately on the next discovery. Useful during testing.
         */
        @Suppress("unused")
        fun clearEncounters() {
            lastEncounters.clear()
        }
    }

    private var manager: PingManager? = null

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        createNotificationChannel()
        startForeground(NOTIF_ID, buildNotification())

        // Stop existing manager if running to avoid duplicate scans/broadcasts
        manager?.stop()

        val profile = intent?.getByteArrayExtra(EXTRA_PROFILE)
            ?.let { PingProfile.fromBytes(it) }
            ?: PingProfile()

        manager = PingManager(this, profile, reconnectCooldownMs, object : PingCallback {
            override fun onEncounter(deviceAddress: String, profile: PingProfile) {
                val now = System.currentTimeMillis()
                val lastTime = lastEncounters[deviceAddress] ?: 0L

                if (now - lastTime > encounterCooldownMs) {
                    lastEncounters[deviceAddress] = now
                    onEncounter?.invoke(deviceAddress, profile)
                }
            }
            override fun onEncounterError(deviceAddress: String, error: String) {}
            override fun onStateChanged(isActive: Boolean) {}
        })
        manager?.start()

        return START_STICKY
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    override fun onDestroy() {
        manager?.stop()
        manager = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun buildNotification() = NotificationCompat.Builder(this, CHANNEL_ID)
        .setContentTitle(notificationTitle)
        .setSmallIcon(R.drawable.outline_bluetooth_24)
        .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
        .apply { notificationText?.takeIf { it.isNotEmpty() }?.let { setContentText(it) } }
        .apply { notificationIntent?.let { setContentIntent(it) } }
        .build()
    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "ping",
            NotificationManager.IMPORTANCE_LOW
        ).apply { description = "ping encounter service" }

        getSystemService(NotificationManager::class.java)
            .createNotificationChannel(channel)
    }
}
