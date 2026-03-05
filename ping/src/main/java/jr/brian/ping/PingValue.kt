package jr.brian.ping

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * A discriminated union of the primitive types that can be stored in [PingProfile.customData].
 *
 * **Wrapping** — use the extension functions ([String.toPingValue], [Int.toPingValue], etc.)
 * to convert a plain value into the appropriate subtype.
 *
 * **Unwrapping** — use the `as*` helpers ([asString], [asInt], etc.) on the receiver side.
 * All helpers return **nullable** — always use `?.` or provide a fallback.
 *
 * Serialized as a tagged union by `kotlinx.serialization` / MessagePack.
 */
@Serializable
sealed class PingValue {
    /** A UTF-8 string value. Wrap with [String.toPingValue], unpack with [asString]. */
    @Serializable @SerialName("text")
    data class Text(val value: String) : PingValue()

    /** A 64-bit integer value. Wrap with [Int.toPingValue] or [Long.toPingValue], unpack with [asInt] or [asLong]. */
    @Serializable @SerialName("int")
    data class Integer(val value: Long) : PingValue()

    /** A double-precision float. Wrap with [Double.toPingValue] or [Float.toPingValue], unpack with [asDouble]. */
    @Serializable @SerialName("decimal")
    data class Decimal(val value: Double) : PingValue()

    /** A boolean value. Wrap with [Boolean.toPingValue], unpack with [asBoolean]. */
    @Serializable @SerialName("bool")
    data class Bool(val value: Boolean) : PingValue()
}

// ── Wrap helpers ──────────────────────────────────────────────────────────────

/** Wraps this [String] as a [PingValue.Text]. */
fun String.toPingValue(): PingValue = PingValue.Text(this)

/** Wraps this [Int] as a [PingValue.Integer]. */
fun Int.toPingValue(): PingValue = PingValue.Integer(this.toLong())

/** Wraps this [Long] as a [PingValue.Integer]. */
fun Long.toPingValue(): PingValue = PingValue.Integer(this)

/** Wraps this [Double] as a [PingValue.Decimal]. */
fun Double.toPingValue(): PingValue = PingValue.Decimal(this)

/** Wraps this [Float] as a [PingValue.Decimal]. */
fun Float.toPingValue(): PingValue = PingValue.Decimal(this.toDouble())

/** Wraps this [Boolean] as a [PingValue.Bool]. */
fun Boolean.toPingValue(): PingValue = PingValue.Bool(this)

// ── Unpack helpers ────────────────────────────────────────────────────────────

/** Returns the underlying [String] if this is a [PingValue.Text], otherwise `null`. */
fun PingValue.asString(): String? = (this as? PingValue.Text)?.value

/** Returns the underlying value as [Int] if this is a [PingValue.Integer], otherwise `null`. */
fun PingValue.asInt(): Int? = (this as? PingValue.Integer)?.value?.toInt()

/** Returns the underlying [Long] if this is a [PingValue.Integer], otherwise `null`. */
fun PingValue.asLong(): Long? = (this as? PingValue.Integer)?.value

/** Returns the underlying [Double] if this is a [PingValue.Decimal], otherwise `null`. */
fun PingValue.asDouble(): Double? = (this as? PingValue.Decimal)?.value

/** Returns the underlying [Boolean] if this is a [PingValue.Bool], otherwise `null`. */
fun PingValue.asBoolean(): Boolean? = (this as? PingValue.Bool)?.value
