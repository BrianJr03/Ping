package jr.brian.pingnearby

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.activity.result.ActivityResultLauncher
import androidx.core.content.ContextCompat

object PingNearbyPermissions {
    /**
     * All permissions required by the Nearby Connections API.
     *
     * Note: [Manifest.permission.ACCESS_WIFI_STATE], [Manifest.permission.CHANGE_WIFI_STATE],
     * [Manifest.permission.CHANGE_NETWORK_STATE], and [Manifest.permission.ACCESS_NETWORK_STATE]
     * are normal-protection permissions granted automatically on install and do not require a
     * runtime dialog — they are included here for completeness and for use with
     * [hasNearbyPermissions].
     */
    val NEARBY_PERMISSIONS = arrayOf(
        Manifest.permission.BLUETOOTH_SCAN,
        Manifest.permission.BLUETOOTH_ADVERTISE,
        Manifest.permission.BLUETOOTH_CONNECT,
        Manifest.permission.ACCESS_WIFI_STATE,
        Manifest.permission.CHANGE_WIFI_STATE,
        Manifest.permission.NEARBY_WIFI_DEVICES,
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.CHANGE_NETWORK_STATE,
        Manifest.permission.ACCESS_NETWORK_STATE
    )

    /** Returns true if all [NEARBY_PERMISSIONS] are currently granted. */
    fun Context.hasNearbyPermissions(): Boolean =
        NEARBY_PERMISSIONS.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }

    /** Launches the system permission dialog for all [NEARBY_PERMISSIONS]. */
    fun requestNearbyPermissions(launcher: ActivityResultLauncher<Array<String>>) {
        launcher.launch(NEARBY_PERMISSIONS)
    }
}
