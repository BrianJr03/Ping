package jr.brian.ping

interface PingCallback {
    /** Called when a profile is successfully exchanged with a nearby device */
    fun onEncounter(deviceAddress: String, profile: PingProfile)

    /** Called when an error occurs during an encounter */
    fun onEncounterError(deviceAddress: String, error: String)

    /** Called when advertising or scanning state changes */
    fun onStateChanged(isActive: Boolean)
}