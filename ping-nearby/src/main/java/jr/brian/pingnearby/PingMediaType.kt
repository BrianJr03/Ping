package jr.brian.pingnearby

sealed class PingMediaType {
    object Image : PingMediaType()
    object Gif : PingMediaType()
    object Video : PingMediaType()
}

object PingMediaDetector {

    fun detect(bytes: ByteArray): PingMediaType {
        if (isGif(bytes)) return PingMediaType.Gif
        if (isVideo(bytes)) return PingMediaType.Video
        return PingMediaType.Image
    }

    fun isPng(bytes: ByteArray) =
        bytes.size >= 8 &&
        bytes[0] == 0x89.toByte() &&
        bytes[1] == 0x50.toByte() &&
        bytes[2] == 0x4E.toByte() &&
        bytes[3] == 0x47.toByte()

    private fun isGif(bytes: ByteArray) =
        bytes.size >= 6 &&
        bytes[0] == 0x47.toByte() &&
        bytes[1] == 0x49.toByte() &&
        bytes[2] == 0x46.toByte()

    private fun isVideo(bytes: ByteArray) = isMp4(bytes) || isMkv(bytes)

    private fun isMp4(bytes: ByteArray) =
        bytes.size >= 12 &&
        bytes[4] == 0x66.toByte() &&
        bytes[5] == 0x74.toByte() &&
        bytes[6] == 0x79.toByte() &&
        bytes[7] == 0x70.toByte()

    private fun isMkv(bytes: ByteArray) =
        bytes.size >= 4 &&
        bytes[0] == 0x1A.toByte() &&
        bytes[1] == 0x45.toByte() &&
        bytes[2] == 0xDF.toByte() &&
        bytes[3] == 0xA3.toByte()
}
