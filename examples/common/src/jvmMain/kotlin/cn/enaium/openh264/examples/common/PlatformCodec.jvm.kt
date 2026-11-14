package cn.enaium.openh264.examples.common

internal actual fun createPlatformDecoder(
    kind: PlatformCodecKind,
    width: Int,
    height: Int,
    csd: ByteArray,
): PlatformDecoder? = null

internal actual fun createPlatformEncoder(
    kind: PlatformCodecKind,
    width: Int,
    height: Int,
): PlatformEncoder? = null

internal actual val platformDecoderName: String = "libde265"
internal actual val platformEncoderName: String = "x265"
