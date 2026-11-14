/**
 * Android native platform codecs (NDK AMediaCodec).
 *
 * The same strategy as Android JVM: MediaCodec decodes or encodes the stream.
 * The decoder reads ByteBuffer output (software codecs and devices that expose
 * YUV output buffers) and converts it with libyuv; encoders write NV12.
 */

@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package cn.enaium.openh264.examples.common

import cn.enaium.camera.I420Frame
import cn.enaium.libyuv.LibYuv
import cnames.structs.AMediaCodec
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.UByteVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.CPointerVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.interpretCPointer
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.readBytes
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.sizeOf
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import platform.media.AMediaCodecBufferInfo
import platform.media.AMediaCodec_configure
import platform.media.AMediaCodec_createDecoderByType
import platform.media.AMediaCodec_createEncoderByType
import platform.media.AMediaCodec_dequeueInputBuffer
import platform.media.AMediaCodec_dequeueOutputBuffer
import platform.media.AMediaCodec_delete
import platform.media.AMediaCodec_getInputBuffer
import platform.media.AMediaCodec_getOutputBuffer
import platform.media.AMediaCodec_getOutputFormat
import platform.media.AMediaCodec_queueInputBuffer
import platform.media.AMediaCodec_releaseOutputBuffer
import platform.media.AMediaCodec_start
import platform.media.AMediaCodec_stop
import platform.media.AMediaFormat_delete
import platform.media.AMediaFormat_getBuffer
import platform.media.AMediaFormat_getInt32
import platform.media.AMediaFormat_new
import platform.media.AMediaFormat_setBuffer
import platform.media.AMediaFormat_setInt32
import platform.media.AMediaFormat_setString
import platform.media.AMEDIACODEC_BUFFER_FLAG_CODEC_CONFIG
import platform.media.AMEDIACODEC_BUFFER_FLAG_END_OF_STREAM
import platform.media.AMEDIACODEC_INFO_OUTPUT_FORMAT_CHANGED
import platform.media.AMEDIACODEC_INFO_TRY_AGAIN_LATER
import platform.posix.memcpy
import platform.posix.size_tVar
import kotlinx.cinterop.IntVar
import kotlinx.cinterop.readBytes
import kotlinx.cinterop.CPointerVarOf

private const val COLOR_YUV420_PLANAR = 19
private const val COLOR_YUV420_SEMI_PLANAR = 21
private const val MIME_HEVC = "video/hevc"
private const val MIME_AVC = "video/avc"

internal actual fun createPlatformDecoder(
    kind: PlatformCodecKind,
    width: Int,
    height: Int,
    csd: ByteArray,
): PlatformDecoder? = runCatching { NativeDecoder(kind, width, height, csd) }.getOrNull()

internal actual fun createPlatformEncoder(
    kind: PlatformCodecKind,
    width: Int,
    height: Int,
): PlatformEncoder? = runCatching { NativeEncoder(kind, width, height) }.getOrNull()

private class NativeDecoder(
    private val kind: PlatformCodecKind,
    private val width: Int,
    private val height: Int,
    csd: ByteArray,
) : PlatformDecoder {
    private val codec: CPointer<AMediaCodec> = createDecoder()
    private var colorFormat = COLOR_YUV420_SEMI_PLANAR
    private var stride = width
    private var sliceHeight = height
    private var closed = false

    init {
        val format = AMediaFormat_new() ?: error("AMediaFormat_new failed")
        try {
            AMediaFormat_setString(format, "mime", kind.mime)
            AMediaFormat_setInt32(format, "width", width)
            AMediaFormat_setInt32(format, "height", height)
            if (csd.isNotEmpty()) {
                csd.usePinned {
                    AMediaFormat_setBuffer(format, "csd-0", it.addressOf(0), csd.size.convert())
                }
            }
            if (AMediaCodec_configure(codec, format, null, null, 0u) != 0 ||
                AMediaCodec_start(codec) != 0
            ) {
                error("decoder configure/start failed")
            }
        } finally {
            AMediaFormat_delete(format)
        }
    }

    private fun createDecoder(): CPointer<AMediaCodec> =
        AMediaCodec_createDecoderByType(kind.mime) ?: error("no ${kind.mime} decoder")

    override fun decode(chunk: EncodedChunk): List<I420Frame> {
        if (closed) return emptyList()
        feed(chunk)
        return drain()
    }

    private fun feed(chunk: EncodedChunk) {
        memScoped {
            val index = AMediaCodec_dequeueInputBuffer(codec, 0)
            if (index < 0) return
            val capacity = alloc<size_tVar>()
            val buffer = AMediaCodec_getInputBuffer(codec, index.convert(), capacity.ptr) ?: return
            if (chunk.data.size > capacity.value.toLong()) return
            buffer.write(chunk.data, chunk.data.size)
            AMediaCodec_queueInputBuffer(
                codec, index.convert(), 0, chunk.data.size.convert(), chunk.pts.toULong(), 0u,
            )
        }
    }

    private fun drain(): List<I420Frame> {
        var result: I420Frame? = null
        memScoped {
            val info = alloc<AMediaCodecBufferInfo>()
            val bufferSize = alloc<size_tVar>()
            while (true) {
                val index = AMediaCodec_dequeueOutputBuffer(codec, info.ptr, 0)
                if (index.toLong() == AMEDIACODEC_INFO_TRY_AGAIN_LATER.toLong()) break
                if (index.toLong() == AMEDIACODEC_INFO_OUTPUT_FORMAT_CHANGED.toLong()) {
                    readFormat()
                    continue
                }
                if (index < 0) break
                if (info.size > 0 &&
                    (info.flags.toInt() and AMEDIACODEC_BUFFER_FLAG_CODEC_CONFIG) == 0
                ) {
                    val buffer = AMediaCodec_getOutputBuffer(codec, index.convert(), bufferSize.ptr)
                    if (buffer != null) {
                        result = toFrame(buffer.reinterpret<ByteVar>().readBytes(info.size), info.presentationTimeUs)
                    }
                }
                AMediaCodec_releaseOutputBuffer(codec, index.convert(), false)
                if ((info.flags.toInt() and AMEDIACODEC_BUFFER_FLAG_END_OF_STREAM) != 0) break
            }
        }
        return listOfNotNull(result)
    }

    private fun readFormat() {
        val format = AMediaCodec_getOutputFormat(codec) ?: return
        try {
            memScoped {
                val value = alloc<IntVar>()
                if (AMediaFormat_getInt32(format, "color-format", value.ptr)) colorFormat = value.value
                if (AMediaFormat_getInt32(format, "stride", value.ptr)) stride = value.value
                if (AMediaFormat_getInt32(format, "slice-height", value.ptr)) sliceHeight = value.value
            }
        } finally {
            AMediaFormat_delete(format)
        }
    }

    private fun toFrame(data: ByteArray, ptsUs: Long): I420Frame {
        val w = width
        val h = height
        val s = if (stride > 0) stride else w
        val slice = if (sliceHeight > 0) sliceHeight else h
        val ySize = s * slice
        val cw = (w + 1) / 2
        val ch = (h + 1) / 2
        val y = ByteArray(w * h)
        val u = ByteArray(cw * ch)
        val v = ByteArray(cw * ch)
        if (colorFormat == COLOR_YUV420_PLANAR) {
            val chromaStride = (s + 1) / 2
            val uSize = chromaStride * ((slice + 1) / 2)
            if (data.size < ySize + uSize * 2) return I420Frame(w, h, y, u, v, ptsUs * 1_000)
            LibYuv.android420ToI420(
                data.copyOfRange(0, ySize),
                data.copyOfRange(ySize, ySize + uSize),
                data.copyOfRange(ySize + uSize, ySize + uSize * 2),
                y, u, v, w, h,
                srcStrideY = s, srcStrideU = chromaStride, srcStrideV = chromaStride,
                srcPixelStrideUv = 1,
            )
        } else {
            val uvHeight = (slice + 1) / 2
            val uvSize = s * uvHeight
            if (data.size < ySize + uvSize) return I420Frame(w, h, y, u, v, ptsUs * 1_000)
            LibYuv.nv12ToI420(
                data.copyOfRange(0, ySize),
                data.copyOfRange(ySize, ySize + uvSize),
                y, u, v, w, h,
                srcStrideY = s, srcStrideUv = s,
            )
        }
        return I420Frame(w, h, y, u, v, ptsUs * 1_000)
    }

    override fun close() {
        if (closed) return
        closed = true
        runCatching { AMediaCodec_stop(codec) }
        runCatching { AMediaCodec_delete(codec) }
    }
}

private class NativeEncoder(
    private val kind: PlatformCodecKind,
    private val width: Int,
    private val height: Int,
) : PlatformEncoder {
    private val codec: CPointer<AMediaCodec>
    private var config: AnnexBConfig? = null
    private var configSent = false
    private var closed = false

    init {
        val created = AMediaCodec_createEncoderByType(kind.mime) ?: error("no ${kind.mime} encoder")
        val format = AMediaFormat_new() ?: run { AMediaCodec_delete(created); error("AMediaFormat_new failed") }
        try {
            AMediaFormat_setString(format, "mime", kind.mime)
            AMediaFormat_setInt32(format, "width", width)
            AMediaFormat_setInt32(format, "height", height)
            AMediaFormat_setInt32(format, "color-format", COLOR_YUV420_SEMI_PLANAR)
            AMediaFormat_setInt32(format, "bitrate", 4_000_000)
            AMediaFormat_setInt32(format, "frame-rate", 30)
            AMediaFormat_setInt32(format, "i-frame-interval", 1)
            if (AMediaCodec_configure(created, format, null, null, 1u) != 0 ||
                AMediaCodec_start(created) != 0
            ) {
                AMediaCodec_delete(created)
                error("encoder configure/start failed")
            }
        } finally {
            AMediaFormat_delete(format)
        }
        codec = created
    }

    override fun encode(frame: I420Frame): List<EncodedChunk> {
        if (closed) return emptyList()
        feed(frame)
        return drain()
    }

    private fun feed(frame: I420Frame) {
        val nv12 = frame.toNv12()
        memScoped {
            val index = AMediaCodec_dequeueInputBuffer(codec, 0)
            if (index < 0) return
            val capacity = alloc<size_tVar>()
            val buffer = AMediaCodec_getInputBuffer(codec, index.convert(), capacity.ptr) ?: return
            if (nv12.size > capacity.value.toLong()) return
            buffer.write(nv12, nv12.size)
            AMediaCodec_queueInputBuffer(
                codec, index.convert(), 0, nv12.size.convert(),
                (frame.timestampNanos / 1_000).toULong(), 0u,
            )
        }
    }

    private fun drain(): List<EncodedChunk> {
        val chunks = ArrayList<EncodedChunk>(1)
        memScoped {
            val info = alloc<AMediaCodecBufferInfo>()
            val bufferSize = alloc<size_tVar>()
            while (true) {
                val index = AMediaCodec_dequeueOutputBuffer(codec, info.ptr, 0)
                if (index.toLong() == AMEDIACODEC_INFO_TRY_AGAIN_LATER.toLong()) break
                if (index.toLong() == AMEDIACODEC_INFO_OUTPUT_FORMAT_CHANGED.toLong()) {
                    readFormat()
                    continue
                }
                if (index < 0) break
                if (info.size > 0) {
                    val buffer = AMediaCodec_getOutputBuffer(codec, index.convert(), bufferSize.ptr)
                    if (buffer != null) {
                        val data = buffer.reinterpret<ByteVar>().readBytes(info.size)
                        if ((info.flags.toInt() and AMEDIACODEC_BUFFER_FLAG_CODEC_CONFIG) != 0) {
                            config = AnnexBConfig.parse(data)
                        } else {
                            val key = info.flags.toInt() and 1 != 0
                            val prefix = if (key || !configSent) config?.prefix else null
                            val annexB = AnnexBConfig.toAnnexB(
                                data, config?.lengthSize ?: 4, prefix, key || !configSent,
                            )
                            if (annexB.isNotEmpty()) {
                                configSent = true
                                chunks += EncodedChunk(annexB, info.presentationTimeUs, key)
                            }
                        }
                    }
                }
                AMediaCodec_releaseOutputBuffer(codec, index.convert(), false)
                if ((info.flags.toInt() and AMEDIACODEC_BUFFER_FLAG_END_OF_STREAM) != 0) break
            }
        }
        return chunks
    }

    private fun readFormat() {
        val format = AMediaCodec_getOutputFormat(codec) ?: return
        try {
            memScoped {
                val data = alloc<CPointerVarOf<CPointer<out kotlinx.cinterop.CPointed>>>()
                val size = alloc<size_tVar>()
                if (AMediaFormat_getBuffer(format, "csd-0", data.ptr, size.ptr)) {
                    val bytes = data.value?.reinterpret<ByteVar>()?.readBytes(size.value.toInt())
                    if (bytes != null && bytes.isNotEmpty()) config = AnnexBConfig.parse(bytes)
                }
            }
        } finally {
            AMediaFormat_delete(format)
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        runCatching { AMediaCodec_stop(codec) }
        runCatching { AMediaCodec_delete(codec) }
    }
}

private fun I420Frame.toNv12(): ByteArray {
    val cw = (width + 1) / 2
    val ch = (height + 1) / 2
    val yPlane = ByteArray(width * height)
    val uvPlane = ByteArray(cw * ch * 2)
    LibYuv.i420ToNv12(y, u, v, yPlane, uvPlane, width, height)
    val out = ByteArray(width * height + cw * ch * 2)
    yPlane.copyInto(out, 0)
    uvPlane.copyInto(out, width * height)
    return out
}

private fun CPointer<UByteVar>.write(bytes: ByteArray, length: Int) {
    if (length == 0) return
    val dst = interpretCPointer<ByteVar>(rawValue) ?: return
    bytes.usePinned { memcpy(dst, it.addressOf(0), length.convert()) }
}


internal actual val platformDecoderName: String = "MediaCodec"
internal actual val platformEncoderName: String = "MediaCodec"
