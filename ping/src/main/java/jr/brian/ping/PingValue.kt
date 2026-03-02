package jr.brian.ping

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
sealed class PingValue {
    @Serializable @SerialName("text")
    data class Text(val value: String) : PingValue()

    @Serializable @SerialName("int")
    data class Integer(val value: Long) : PingValue()

    @Serializable @SerialName("decimal")
    data class Decimal(val value: Double) : PingValue()

    @Serializable @SerialName("bool")
    data class Bool(val value: Boolean) : PingValue()
}

// Convenience conversions
fun String.toPingValue(): PingValue = PingValue.Text(this)
fun Int.toPingValue(): PingValue = PingValue.Integer(this.toLong())
fun Long.toPingValue(): PingValue = PingValue.Integer(this)
fun Double.toPingValue(): PingValue = PingValue.Decimal(this)
fun Float.toPingValue(): PingValue = PingValue.Decimal(this.toDouble())
fun Boolean.toPingValue(): PingValue = PingValue.Bool(this)

// Unpack helpers
fun PingValue.asString(): String? = (this as? PingValue.Text)?.value
fun PingValue.asInt(): Int? = (this as? PingValue.Integer)?.value?.toInt()
fun PingValue.asLong(): Long? = (this as? PingValue.Integer)?.value
fun PingValue.asDouble(): Double? = (this as? PingValue.Decimal)?.value
fun PingValue.asBoolean(): Boolean? = (this as? PingValue.Bool)?.value
