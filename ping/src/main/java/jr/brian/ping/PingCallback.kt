package jr.brian.ping

/**
 * Callback interface for [PingManager] events.
 *
 * Implement this to receive encounter results, errors, and state changes.
 * All callbacks are dispatched on the **main thread**.
 */
interface PingCallback {
    /**
     * Called when a profile is successfully exchanged with a nearby device.
     *
     * @param deviceAddress The Bluetooth MAC address of the remote device.
     * @param profile       The [PingProfile] received from the remote device.
     */
    fun onEncounter(deviceAddress: String, profile: PingProfile)

    /**
     * Called when an error occurs during an encounter attempt.
     *
     * @param deviceAddress The Bluetooth MAC address of the device involved.
     * @param error         A human-readable description of the failure.
     */
    fun onEncounterError(deviceAddress: String, error: String)

    /**
     * Called when advertising/scanning starts or stops.
     *
     * @param isActive `true` when BLE operations are running, `false` after [PingManager.stop].
     */
    fun onStateChanged(isActive: Boolean)
}