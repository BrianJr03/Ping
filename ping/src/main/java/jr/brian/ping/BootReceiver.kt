package jr.brian.ping

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Restarts [PingService] automatically after the device reboots.
 *
 * Requires the `RECEIVE_BOOT_COMPLETED` permission and the receiver to be registered
 * in `AndroidManifest.xml` with an `ACTION_BOOT_COMPLETED` intent filter.
 *
 * The service is restarted with a default empty [PingProfile]. Re-attach your
 * [PingService.onEncounter] callback in `Application.onCreate` so it is in place
 * before the first post-boot encounter fires.
 */
class BootReceiver : BroadcastReceiver() {
    /**
     * Handles [Intent.ACTION_BOOT_COMPLETED] by starting [PingService] as a
     * foreground service with a default empty [PingProfile].
     */
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            val serviceIntent = PingService.buildIntent(context, PingProfile())
            context.startForegroundService(serviceIntent)
        }
    }
}