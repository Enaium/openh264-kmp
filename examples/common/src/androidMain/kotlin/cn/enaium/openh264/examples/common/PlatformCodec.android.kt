/**
 * Android (JVM/ART) platform codecs.
 *
 * MediaCodec is the platform API on Android: it decodes x264/x265 output into
 * an ImageReader surface (YUV_420_888, converted with libyuv) and encodes
 * camera frames as NV12 for the decoder examples. Desktop targets have no
 * platform codec and fall back to the published bindings.
 */

package cn.enaium.openh264.examples.common

import android.graphics.ImageFormat
import android.media.Image
import android.media.ImageReader
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import cn.enaium.camera.I420Frame
import cn.enaium.libyuv.LibYuv
import cn.enaium.libyuv.toI420
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer

internal actual fun createPlatformDecoder(
    kind: PlatformCodecKind,
    width: Int,
    height: Int,
    csd: ByteArray,
): PlatformDecoder? = runCatching { MediaCodecDecoder(kind, width, height, csd) }.getOrNull()

internal actual fun createPlatformEncoder(
    kind: PlatformCodecKind,
    width: Int,
    height: Int,
): PlatformEncoder? = runCatching { MediaCodecEncoder(kind, width, height) }.getOrNull()

private class MediaCodecDecoder(
    private val kind: PlatformCodecKind,
    private val width: Int,
    private val height: Int,
    csd: ByteArray,
) : PlatformDecoder {
    private val reader = ImageReader.newInstance(width, height, ImageFormat.YUV_420_888, 4)
    private val codec = MediaCodec.createDecoderByType(kind.mime)
    private val info = MediaCodec.BufferInfo()
    private var closed = false

    init {
        val format = MediaFormat.createVideoFormat(kind.mime, width, height).apply {
            setByteBuffer("csd-0", ByteBuffer.wrap(csd))
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, maxOf(width * height, 256 * 1024))
        }
        codec.configure(format, reader.surface, null, 0)
        codec.start()
    }

    override fun decode(chunk: EncodedChunk): List<I420Frame> {
        if (closed) return emptyList()
        try {
            val index = codec.dequeueInputBuffer(10_000)
            if (index >= 0) {
                val buffer = codec.getInputBuffer(index)
                if (buffer != null) {
                    buffer.clear()
                    buffer.put(chunk.data)
                    codec.queueInputBuffer(
                        index,
                        0,
                        chunk.data.size,
                        chunk.pts,
                        if (chunk.keyFrame) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0,
                    )
                } else {
                    codec.queueInputBuffer(index, 0, 0, chunk.pts, 0)
                }
            }
            drain()
        } catch (_: Throwable) {
            return emptyList()
        }
        return collect()
    }

    private fun drain() {
        while (true) {
            when (val index = codec.dequeueOutputBuffer(info, 0)) {
                MediaCodec.INFO_TRY_AGAIN_LATER -> return
                MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> Unit
                else -> {
                    if (index < 0) return
                    codec.releaseOutputBuffer(index, true)
                }
            }
        }
    }

    private fun collect(): List<I420Frame> {
        val image = reader.acquireLatestImage() ?: return emptyList()
        return try {
            listOf(image.toCameraFrame())
        } finally {
            image.close()
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        runCatching { codec.stop() }
        runCatching { codec.release() }
        runCatching { reader.close() }
    }
}

private fun Image.toCameraFrame(): I420Frame {
    val frame = toI420()
    val chromaWidth = (frame.width + 1) / 2
    val chromaHeight = (frame.height + 1) / 2
    val y = ByteArray(frame.width * frame.height)
    val u = ByteArray(chromaWidth * chromaHeight)
    val v = ByteArray(chromaWidth * chromaHeight)
    val yBuffer = frame.y.duplicate().apply { clear() }
    val uBuffer = frame.u.duplicate().apply { clear() }
    val vBuffer = frame.v.duplicate().apply { clear() }
    yBuffer.get(y)
    uBuffer.get(u)
    vBuffer.get(v)
    return I420Frame(frame.width, frame.height, y, u, v, frame.presentationTimeUs * 1_000)
}

private class MediaCodecEncoder(
    private val kind: PlatformCodecKind,
    private val width: Int,
    private val height: Int,
) : PlatformEncoder {
    private val codec = MediaCodec.createEncoderByType(kind.mime)
    private val info = MediaCodec.BufferInfo()
    private var config: AnnexBConfig? = null
    private var configSent = false
    private var closed = false

    init {
        val format = MediaFormat.createVideoFormat(kind.mime, width, height).apply {
            setInteger(
                MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar,
            )
            setInteger(MediaFormat.KEY_BIT_RATE, 4_000_000)
            setInteger(MediaFormat.KEY_FRAME_RATE, 30)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        }
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        codec.start()
    }

    override fun encode(frame: I420Frame): List<EncodedChunk> {
        if (closed) return emptyList()
        val chunks = ArrayList<EncodedChunk>(1)
        try {
            val index = codec.dequeueInputBuffer(10_000)
            if (index >= 0) {
                val buffer = codec.getInputBuffer(index)
                if (buffer == null) {
                    codec.queueInputBuffer(index, 0, 0, frame.timestampNanos / 1_000, 0)
                } else {
                    buffer.clear()
                    val packed = frame.toNv12()
                    buffer.put(packed)
                    codec.queueInputBuffer(
                        index,
                        0,
                        packed.size,
                        frame.timestampNanos / 1_000,
                        0,
                    )
                }
            }
            while (true) {
                when (val out = codec.dequeueOutputBuffer(info, 0)) {
                    MediaCodec.INFO_TRY_AGAIN_LATER -> break
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        codec.outputFormat.getByteBuffer("csd-0")?.let { buffer ->
                            val data = ByteArray(buffer.remaining())
                            buffer.get(data)
                            config = AnnexBConfig.parse(data)
                        }
                    }

                    else -> {
                        if (out < 0) break
                        if (info.size > 0) {
                            val buffer = codec.getOutputBuffer(out)
                            if (buffer != null) {
                                val data = ByteArray(info.size)
                                buffer.position(info.offset)
                                buffer.get(data)
                                if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                                    config = AnnexBConfig.parse(data)
                                } else {
                                    val key = info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0
                                    val annexB = AnnexBConfig.toAnnexB(
                                        data,
                                        config?.lengthSize ?: 4,
                                        config?.prefix,
                                        key || !configSent,
                                    )
                                    if (annexB.isNotEmpty()) {
                                        configSent = true
                                        chunks += EncodedChunk(annexB, info.presentationTimeUs, key)
                                    }
                                }
                            }
                        }
                        codec.releaseOutputBuffer(out, false)
                    }
                }
            }
        } catch (_: Throwable) {
            return chunks
        }
        return chunks
    }

    override fun close() {
        if (closed) return
        closed = true
        runCatching { codec.stop() }
        runCatching { codec.release() }
    }
}

private fun I420Frame.toNv12(): ByteArray {
    val chromaWidth = (width + 1) / 2
    val chromaHeight = (height + 1) / 2
    val nv12 = ByteArray(width * height + chromaWidth * chromaHeight * 2)
    val yPlane = ByteArray(width * height)
    val uvPlane = ByteArray(chromaWidth * chromaHeight * 2)
    LibYuv.i420ToNv12(y, u, v, yPlane, uvPlane, width, height)
    yPlane.copyInto(nv12, 0)
    uvPlane.copyInto(nv12, width * height)
    return nv12
}

internal actual val platformDecoderName: String = "MediaCodec"
internal actual val platformEncoderName: String = "MediaCodec"
