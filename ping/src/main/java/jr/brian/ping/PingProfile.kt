package jr.brian.ping

import com.ensarsarajcic.kotlinx.serialization.msgpack.MsgPack
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class PingProfile(
    val userId: String = "",
    val displayName: String = "",
    val message: String = "",
    val customData: Map<String, PingValue> = emptyMap(),
    val timestamp: Long = System.currentTimeMillis(),
    @SerialName("_v") val schemaVersion: Int = 1
) {
    fun toBytes(): ByteArray = MsgPack.encodeToByteArray(serializer(), this)

    companion object {
        fun fromBytes(bytes: ByteArray): PingProfile = MsgPack.decodeFromByteArray(serializer(), bytes)
    }
}
