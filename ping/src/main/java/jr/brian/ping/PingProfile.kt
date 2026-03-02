package jr.brian.ping

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class PingProfile(
    val userId: String = "",
    val displayName: String = "",
    val message: String = "",
    val customData: Map<String, PingValue> = emptyMap(),
    val timestamp: Long = System.currentTimeMillis()
) {
    fun toJson(): String = Json.encodeToString(serializer(), this)

    fun toBytes(): ByteArray = toJson().toByteArray(Charsets.UTF_8)

    companion object {
        fun fromJson(json: String): PingProfile = Json.decodeFromString(serializer(), json)

        fun fromBytes(bytes: ByteArray): PingProfile = fromJson(String(bytes, Charsets.UTF_8))
    }
}