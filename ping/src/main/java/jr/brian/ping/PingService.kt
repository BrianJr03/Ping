package jr.brian.ping

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.annotation.RequiresPermission
import androidx.core.app.NotificationCompat

class PingService : Service() {
    companion object {
        private const val CHANNEL_ID = "ping_channel"
        private const val NOTIF_ID = 1001

        const val EXTRA_PROFILE = "extra_profile"

        /** Minimum ms between [onEncounter] callbacks for the same device. Default: 30s. */
        var encounterCooldownMs: Long = 30_000L

        /** Minimum ms between GATT connection attempts to the same device. Default: 60s. */
        var reconnectCooldownMs: Long = 60_000L

        fun buildIntent(context: Context, profile: PingProfile) =
            Intent(context, PingService::class.java).apply {
                putExtra(EXTRA_PROFILE, profile.toJson())
            }

        var onEncounter: ((String, PingProfile) -> Unit)? = null
        var notificationTitle: String = "Ping is Active"
        var notificationText: String? = null
        
        // Use a static map to track encounters across service restarts/re-starts
        private val lastEncounters = mutableMapOf<String, Long>()

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

        val profile = intent?.getStringExtra(EXTRA_PROFILE)
            ?.let { PingProfile.fromJson(it) }
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
