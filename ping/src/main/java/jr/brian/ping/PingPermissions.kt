package jr.brian.ping

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.result.ActivityResultLauncher
import androidx.core.content.ContextCompat
import androidx.core.net.toUri

/**
 * The complete set of runtime permissions required by the `ping` BLE module.
 *
 * [Manifest.permission.BLUETOOTH_SCAN], [Manifest.permission.BLUETOOTH_CONNECT], and
 * [Manifest.permission.BLUETOOTH_ADVERTISE] all require a runtime prompt on Android 12+.
 * Pass this array directly to [ActivityResultLauncher.launch] or use
 * [PingPermissions.requestPingPermissions] as a convenience wrapper.
 */
val PING_PERMISSIONS = arrayOf(
    Manifest.permission.BLUETOOTH_SCAN,
    Manifest.permission.BLUETOOTH_CONNECT,
    Manifest.permission.BLUETOOTH_ADVERTISE
)

/**
 * Utility object for checking and requesting the BLE permissions required by Ping.
 *
 * Use [hasPingPermissions] to gate service startup and [requestPingPermissions] to
 * launch the system permission dialog. For reliable background operation also call
 * [requestBatteryOptimizationExemption].
 *
 * ### Typical setup
 * ```kotlin
 * val launcher = registerForActivityResult(RequestMultiplePermissions()) { granted ->
 *     if (granted.values.all { it }) context.startForegroundService(...)
 * }
 * with(PingPermissions) {
 *     if (!hasPingPermissions()) requestPingPermissions(launcher)
 *     requestBatteryOptimizationExemption(context)
 * }
 * ```
 */
@Suppress("unused")
object PingPermissions {
    /**
     * Returns `true` if all [PING_PERMISSIONS] are currently granted.
     */
    fun Context.hasPingPermissions(): Boolean =
        PING_PERMISSIONS.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }

    /**
     * Launches the system permission dialog for all [PING_PERMISSIONS].
     *
     * @param launcher An [ActivityResultLauncher] registered with [RequestMultiplePermissions].
     */
    fun requestPingPermissions(launcher: ActivityResultLauncher<Array<String>>) {
        launcher.launch(PING_PERMISSIONS)
    }

    /**
     * Prompts the user to exempt this app from battery optimization.
     *
     * Without this exemption Android may throttle or kill BLE scanning in the background,
     * making encounters unreliable. Has no effect if the app is already exempted.
     *
     * @param context Any context — the system settings screen is opened via [startActivity].
     */
    fun requestBatteryOptimizationExemption(context: Context) {
        val pm = context.getSystemService(PowerManager::class.java)
        if (!pm.isIgnoringBatteryOptimizations(context.packageName)) {
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = "package:${context.packageName}".toUri()
            }
            context.startActivity(intent)
        }
    }
}
