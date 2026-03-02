package jr.brian.ping

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            val serviceIntent = PingService.buildIntent(context, PingProfile())
            context.startForegroundService(serviceIntent)
        }
    }
}