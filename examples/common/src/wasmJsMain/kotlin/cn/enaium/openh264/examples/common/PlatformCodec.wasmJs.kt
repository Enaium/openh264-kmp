@file:OptIn(kotlin.io.encoding.ExperimentalEncodingApi::class)

package cn.enaium.openh264.examples.common

import cn.enaium.camera.I420Frame
import kotlin.io.encoding.Base64

private external fun webCodecsCreateDecoder(kind: String, width: Int, height: Int): Int
private external fun webCodecsDecode(handle: Int, data: String, pts: Double, key: Int)
private external fun webCodecsPollDecode(handle: Int): String
private external fun webCodecsCloseDecoder(handle: Int)

private external fun webCodecsCreateEncoder(kind: String, width: Int, height: Int): Int
private external fun webCodecsEncoderStatus(handle: Int): Int
private external fun webCodecsEncode(handle: Int, data: String, width: Int, height: Int, pts: Double)
private external fun webCodecsPollEncode(handle: Int): String
private external fun webCodecsCloseEncoder(handle: Int)

internal actual fun createPlatformDecoder(
    kind: PlatformCodecKind,
    width: Int,
    height: Int,
    csd: ByteArray,
): PlatformDecoder? {
    val handle = webCodecsCreateDecoder(kind.name, width, height)
    return if (handle <= 0) null else WebCodecsDecoder(handle)
}

internal actual fun createPlatformEncoder(
    kind: PlatformCodecKind,
    width: Int,
    height: Int,
): PlatformEncoder? {
    val handle = webCodecsCreateEncoder(kind.name, width, height)
    return if (handle <= 0) null else WebCodecsEncoder(handle)
}

private class WebCodecsDecoder(private val handle: Int) : PlatformDecoder {
    override fun decode(chunk: EncodedChunk): List<I420Frame> {
        webCodecsDecode(handle, Base64.encode(chunk.data), chunk.pts.toDouble(), if (chunk.keyFrame) 1 else 0)
        return parseFrames(webCodecsPollDecode(handle))
    }

    override fun close() {
        webCodecsCloseDecoder(handle)
    }
}

private class WebCodecsEncoder(private val handle: Int) : PlatformEncoder {
    override val status: Int
        get() = webCodecsEncoderStatus(handle)

    override fun encode(frame: I420Frame): List<EncodedChunk> {
        if (status != 1) return emptyList()
        webCodecsEncode(
            handle,
            Base64.encode(frame.toI420Packed()),
            frame.width,
            frame.height,
            frame.timestampNanos / 1_000.0,
        )
        return parseChunks(webCodecsPollEncode(handle))
    }

    override fun close() {
        webCodecsCloseEncoder(handle)
    }
}

private fun I420Frame.toI420Packed(): ByteArray {
    val chromaWidth = (width + 1) / 2
    val chromaHeight = (height + 1) / 2
    val out = ByteArray(width * height + chromaWidth * chromaHeight * 2)
    y.copyInto(out, 0)
    u.copyInto(out, width * height)
    v.copyInto(out, width * height + chromaWidth * chromaHeight)
    return out
}

private fun parseFrames(text: String): List<I420Frame> {
    if (text.isEmpty()) return emptyList()
    val frames = ArrayList<I420Frame>()
    for (line in text.lineSequence()) {
        if (line.isBlank()) continue
        val first = line.indexOf(',')
        val second = line.indexOf(',', first + 1)
        if (first <= 0 || second <= first) continue
        val width = line.substring(0, first).toIntOrNull() ?: continue
        val height = line.substring(first + 1, second).toIntOrNull() ?: continue
        val packed = Base64.decode(line.substring(second + 1))
        val chromaWidth = (width + 1) / 2
        val chromaHeight = (height + 1) / 2
        val lumaSize = width * height
        val chromaSize = chromaWidth * chromaHeight
        if (packed.size < lumaSize + chromaSize * 2) continue
        frames += I420Frame(
            width, height,
            packed.copyOfRange(0, lumaSize),
            packed.copyOfRange(lumaSize, lumaSize + chromaSize),
            packed.copyOfRange(lumaSize + chromaSize, lumaSize + chromaSize * 2),
        )
    }
    return frames
}

private fun parseChunks(text: String): List<EncodedChunk> {
    if (text.isEmpty()) return emptyList()
    val chunks = ArrayList<EncodedChunk>()
    for (line in text.lineSequence()) {
        if (line.isBlank()) continue
        val first = line.indexOf(',')
        val second = line.indexOf(',', first + 1)
        if (first < 0 || second <= first) continue
        val key = line.substring(0, first) == "1"
        val pts = line.substring(first + 1, second).toDoubleOrNull()?.toLong() ?: 0L
        chunks += EncodedChunk(Base64.decode(line.substring(second + 1)), pts, key)
    }
    return chunks
}

internal actual val platformDecoderName: String = "WebCodecs"
internal actual val platformEncoderName: String = "WebCodecs"
