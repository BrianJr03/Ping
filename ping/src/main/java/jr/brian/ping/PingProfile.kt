package jr.brian.ping

import com.ensarsarajcic.kotlinx.serialization.msgpack.MsgPack
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The data payload exchanged between two Ping-enabled devices during a BLE encounter.
 *
 * Serialized as **MessagePack** binary for compact over-the-air transfer. All fields
 * are optional — `PingProfile()` with no arguments is valid and produces a minimal payload.
 *
 * Keep the total serialized size lean for faster exchanges (fewer BLE packets). See the
 * README "Payload Size" section for budgeting guidance.
 *
 * @property userId        Stable identifier for this device or user (e.g. a UUID or
 *                         `Build.MODEL`). Visible to the receiver as the sender's identity.
 * @property displayName   Human-readable name or session label shown in encounter lists.
 * @property message       Optional short status message (e.g. "Looking for a match").
 * @property customData    Arbitrary typed key-value data. Values must be [PingValue] subtypes —
 *                         use extension functions like [String.toPingValue] to wrap and
 *                         [PingValue.asString] etc. to unpack on the receiving end.
 * @property timestamp     Profile creation time in epoch milliseconds. Auto-set on construction.
 * @property schemaVersion Serialized as `_v`. Bump this when making breaking changes to your
 *                         [customData] format so receivers can detect and handle stale profiles.
 */
@Serializable
data class PingProfile(
    val userId: String = "",
    val displayName: String = "",
    val message: String = "",
    val customData: Map<String, PingValue> = emptyMap(),
    val timestamp: Long = System.currentTimeMillis(),
    @SerialName("_v") val schemaVersion: Int = 1
) {
    /** Serializes this profile to MessagePack bytes for BLE transmission. */
    fun toBytes(): ByteArray = MsgPack.encodeToByteArray(serializer(), this)

    companion object {
        /**
         * Deserializes a [PingProfile] from MessagePack [bytes].
         *
         * @throws Exception if [bytes] is not a valid MessagePack-encoded [PingProfile].
         */
        fun fromBytes(bytes: ByteArray): PingProfile = MsgPack.decodeFromByteArray(serializer(), bytes)
    }
}
