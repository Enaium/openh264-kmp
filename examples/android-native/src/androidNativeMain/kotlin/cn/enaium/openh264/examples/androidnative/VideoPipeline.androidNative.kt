/*
 * Copyright (c) 2026 Enaium
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package cn.enaium.openh264.examples.androidnative

import cn.enaium.libyuv.LibYuv
import cn.enaium.libyuv.Rotation
import cn.enaium.openh264.OpenH264Decoder
import cn.enaium.openh264.OpenH264Frame
import cn.enaium.sdl.SDL
import cnames.structs.AMediaCodec
import cnames.structs.AMediaFormat
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.IntVar
import kotlinx.cinterop.UByteVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.get
import kotlinx.cinterop.interpretCPointer
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.plus
import kotlinx.cinterop.ptr
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import platform.media.AMEDIAFORMAT_KEY_BIT_RATE
import platform.media.AMEDIAFORMAT_KEY_COLOR_FORMAT
import platform.media.AMEDIAFORMAT_KEY_FRAME_RATE
import platform.media.AMEDIAFORMAT_KEY_HEIGHT
import platform.media.AMEDIAFORMAT_KEY_I_FRAME_INTERVAL
import platform.media.AMEDIAFORMAT_KEY_MIME
import platform.media.AMEDIAFORMAT_KEY_WIDTH
import platform.media.AMEDIACODEC_BUFFER_FLAG_CODEC_CONFIG
import platform.media.AMEDIACODEC_BUFFER_FLAG_END_OF_STREAM
import platform.media.AMEDIACODEC_CONFIGURE_FLAG_ENCODE
import platform.media.AMEDIACODEC_INFO_OUTPUT_FORMAT_CHANGED
import platform.media.AMEDIACODEC_INFO_TRY_AGAIN_LATER
import platform.media.AMediaCodecBufferInfo
import platform.media.AMediaCodec_configure
import platform.media.AMediaCodec_createEncoderByType
import platform.media.AMediaCodec_dequeueInputBuffer
import platform.media.AMediaCodec_dequeueOutputBuffer
import platform.media.AMediaCodec_delete
import platform.media.AMediaCodec_getInputBuffer
import platform.media.AMediaCodec_getOutputBuffer
import platform.media.AMediaCodec_queueInputBuffer
import platform.media.AMediaCodec_releaseOutputBuffer
import platform.media.AMediaCodec_start
import platform.media.AMediaCodec_stop
import platform.media.AMediaFormat_delete
import platform.media.AMediaFormat_new
import platform.media.AMediaFormat_setInt32
import platform.media.AMediaFormat_setString
import platform.posix.memcpy
import platform.posix.size_tVar

private const val MIME_AVC = "video/avc"

/** MediaCodecInfo.CodecCapabilities color formats, from the Android SDK. */
private const val COLOR_YUV420_SEMI_PLANAR = 21

/** Microseconds between two camera frames at 30 fps. */
private const val FRAME_INTERVAL_US = 33_333L

private const val BITRATE = 2_000_000
private const val FRAME_RATE = 30
private const val I_FRAME_INTERVAL = 1

private data class EncodedPacket(val data: ByteArray, val ptsUs: Long)

/**
 * The Android native loopback:
 *
 *   Camera2 (YUV_420_888) -> libyuv Android420ToI420
 *     -> NDK AMediaCodec H.264 encoder (semi-planar input, Annex-B output)
 *     -> OpenH264 decoder (this binding) -> libyuv I420ToRGBA -> ImGui texture
 *
 * The encoder uses the NDK's `AMediaCodec` C API; the decoder is the Kotlin
 * Multiplatform OpenH264 binding linked into `libmain.so`.
 */
private class AndroidVideoPipeline : VideoPipeline {
    private var camera: Camera2Source? = null
    private var cameraWidth = 0
    private var cameraHeight = 0
    private var encodedWidth = 0
    private var encodedHeight = 0
    private var encoder: CPointer<AMediaCodec>? = null
    private var decoder: OpenH264Decoder? = null
    private var parameterSets: ByteArray? = null

    private var sourceY = ByteArray(0)
    private var sourceU = ByteArray(0)
    private var sourceV = ByteArray(0)
    private var rotatedY = ByteArray(0)
    private var rotatedU = ByteArray(0)
    private var rotatedV = ByteArray(0)
    private var nv12Y = ByteArray(0)
    private var nv12Uv = ByteArray(0)

    private var latest: VideoFrame? = null
    private var started = false
    private var retryAt = 0uL
    private var error: String? = null
    private var encodedFrames = 0L
    private var encodedBytes = 0L
    private var decodedFrames = 0L
    private var presentationTimeUs = 0L
    private var rotationIndex = 0
    private val packets = ArrayDeque<EncodedPacket>()

    override val status: PipelineStatus
        get() = PipelineStatus(
            source = "camera ${cameraWidth}x$cameraHeight Camera2 YUV_420_888",
            path = "camera -> I420 -> MediaCodec H.264 encode -> " +
                "OpenH264 decode -> I420 -> RGBA",
            rotation = rotationName(),
            encodedFrames = encodedFrames,
            encodedBytes = encodedBytes,
            decodedFrames = decodedFrames,
            error = error,
        )

    override fun pump() {
        if (!started) {
            val now = SDL.getTicks()
            if (now < retryAt) return
            if (!start()) {
                retryAt = SDL.getTicks() + 2_000u
                return
            }
        }
        try {
            pumpCamera()
            feedEncoder()
            drainEncoder()
        } catch (t: Throwable) {
            fail(t.message ?: t.toString())
        }
    }

    override fun currentFrame(): VideoFrame? = latest

    override fun restart() {
        close()
        latest = null
        encodedFrames = 0
        encodedBytes = 0
        decodedFrames = 0
        presentationTimeUs = 0
        retryAt = 0u
        error = null
    }

    override fun cycleRotation() {
        rotationIndex = (rotationIndex + 1) % Rotation.entries.size
        restart()
    }

    override fun close() {
        camera?.close()
        camera = null
        closeCodecs()
        started = false
    }

    private fun start(): Boolean = try {
        val source = Camera2Source(640, 480).also { it.open() }
        camera = source
        cameraWidth = source.width
        cameraHeight = source.height
        encodedWidth = if (rotated()) cameraHeight else cameraWidth
        encodedHeight = if (rotated()) cameraWidth else cameraHeight
        allocateSourceBuffers()
        startEncoder()
        decoder = OpenH264Decoder.create()
        started = true
        error = null
        true
    } catch (t: Throwable) {
        close()
        error = t.message ?: t.toString()
        false
    }

    private fun allocateSourceBuffers() {
        val w = cameraWidth
        val h = cameraHeight
        val cw = (w + 1) / 2
        val ch = (h + 1) / 2
        sourceY = ByteArray(w * h)
        sourceU = ByteArray(cw * ch)
        sourceV = ByteArray(cw * ch)
        nv12Y = ByteArray(w * h)
        nv12Uv = ByteArray(cw * ch * 2)
    }

    private fun startEncoder() {
        val codec = AMediaCodec_createEncoderByType(MIME_AVC)
            ?: throw IllegalStateException("no H.264 encoder")
        val format = AMediaFormat_new() ?: run {
            AMediaCodec_delete(codec)
            throw IllegalStateException("AMediaFormat_new failed")
        }
        try {
            AMediaFormat_setString(format, "mime", MIME_AVC)
            AMediaFormat_setInt32(format, AMEDIAFORMAT_KEY_WIDTH, encodedWidth)
            AMediaFormat_setInt32(format, AMEDIAFORMAT_KEY_HEIGHT, encodedHeight)
            AMediaFormat_setInt32(format, AMEDIAFORMAT_KEY_COLOR_FORMAT, COLOR_YUV420_SEMI_PLANAR)
            AMediaFormat_setInt32(format, AMEDIAFORMAT_KEY_BIT_RATE, BITRATE)
            AMediaFormat_setInt32(format, AMEDIAFORMAT_KEY_FRAME_RATE, FRAME_RATE)
            AMediaFormat_setInt32(format, AMEDIAFORMAT_KEY_I_FRAME_INTERVAL, I_FRAME_INTERVAL)
            AMediaFormat_setInt32(format, "prepend-sps-pps-to-idr-frames", 1)
            if (AMediaCodec_configure(codec, format, null, null, AMEDIACODEC_CONFIGURE_FLAG_ENCODE.toUInt()) != 0 ||
                AMediaCodec_start(codec) != 0
            ) {
                throw IllegalStateException("encoder configure/start failed")
            }
            encoder = codec
        } catch (t: Throwable) {
            AMediaCodec_delete(codec)
            throw t
        } finally {
            AMediaFormat_delete(format)
        }
    }

    private fun pumpCamera() {
        val cam = camera ?: return
        if (!cam.acquire(sourceY, sourceU, sourceV)) return
        val frame = rotatedI420(sourceY, sourceU, sourceV)
        val pts = presentationTimeUs
        presentationTimeUs += FRAME_INTERVAL_US
        val w = encodedWidth
        val h = encodedHeight
        val chromaWidth = (w + 1) / 2
        val chromaHeight = (h + 1) / 2
        if (nv12Y.size != w * h) nv12Y = ByteArray(w * h)
        if (nv12Uv.size != chromaWidth * chromaHeight * 2) {
            nv12Uv = ByteArray(chromaWidth * chromaHeight * 2)
        }
        LibYuv.i420ToNv12(frame.first, frame.second, frame.third, nv12Y, nv12Uv, w, h)
        packets.addLast(EncodedPacket(nv12Y + nv12Uv, pts))
        while (packets.size > 4) packets.removeFirst()
    }

    private fun rotatedI420(
        y: ByteArray,
        u: ByteArray,
        v: ByteArray,
    ): Triple<ByteArray, ByteArray, ByteArray> {
        val rotation = Rotation.entries[rotationIndex]
        if (rotation == Rotation.NONE) return Triple(y, u, v)
        val w = encodedWidth
        val h = encodedHeight
        if (rotatedY.size != w * h) {
            rotatedY = ByteArray(w * h)
            rotatedU = ByteArray(((w + 1) / 2) * ((h + 1) / 2))
            rotatedV = ByteArray(rotatedU.size)
        }
        LibYuv.i420Rotate(
            y, u, v,
            rotatedY, rotatedU, rotatedV,
            cameraWidth, cameraHeight, rotation,
        )
        return Triple(rotatedY, rotatedU, rotatedV)
    }

    /** Queues the newest NV12 picture into the encoder. */
    private fun feedEncoder() {
        val codec = encoder ?: return
        while (true) {
            val packet = packets.removeFirstOrNull() ?: return
            val index = AMediaCodec_dequeueInputBuffer(codec, 0)
            if (index < 0) {
                // The encoder is full; put the picture back and try next pump.
                packets.addFirst(packet)
                return
            }
            memScoped {
                val capacity = alloc<size_tVar>()
                val buffer = AMediaCodec_getInputBuffer(codec, index.convert(), capacity.ptr)
                    ?: throw IllegalStateException("encoder input buffer is null")
                if (packet.data.size > capacity.value.toLong()) {
                    throw IllegalStateException("encoder input ${capacity.value} < packet ${packet.data.size}")
                }
                buffer.write(packet.data, packet.data.size)
                val result = AMediaCodec_queueInputBuffer(
                    codec,
                    index.convert(),
                    0,
                    packet.data.size.convert(),
                    packet.ptsUs.toULong(),
                    0u,
                )
                if (result != 0) throw IllegalStateException("encoder queueInputBuffer=$result")
            }
        }
    }

    private fun drainEncoder() {
        val codec = encoder ?: return
        memScoped {
            val info = alloc<AMediaCodecBufferInfo>()
            val bufferSize = alloc<size_tVar>()
            while (true) {
                val index = AMediaCodec_dequeueOutputBuffer(codec, info.ptr, 0)
                if (index.toLong() == AMEDIACODEC_INFO_TRY_AGAIN_LATER.toLong()) break
                if (index.toLong() == AMEDIACODEC_INFO_OUTPUT_FORMAT_CHANGED.toLong()) continue
                if (index < 0) break
                if (info.size > 0) {
                    val buffer = AMediaCodec_getOutputBuffer(codec, index.convert(), bufferSize.ptr)
                    if (buffer != null) {
                        val data = buffer.read(info.offset, info.size)
                        val flags = info.flags.toInt()
                        if (flags and AMEDIACODEC_BUFFER_FLAG_CODEC_CONFIG.toInt() != 0) {
                            parameterSets = data
                        } else {
                            encodedFrames++
                            encodedBytes += data.size
                            val keyFrame = flags and 1 != 0
                            val accessUnit = if (keyFrame && !data.containsNalType(7)) {
                                (parameterSets ?: ByteArray(0)) + data
                            } else {
                                data
                            }
                            decoder?.decode(accessUnit, info.presentationTimeUs.toLong())?.let(::render)
                        }
                    }
                }
                AMediaCodec_releaseOutputBuffer(codec, index.convert(), false)
                if (info.flags.toInt() and AMEDIACODEC_BUFFER_FLAG_END_OF_STREAM.toInt() != 0) break
            }
        }
    }

    private fun render(frame: OpenH264Frame) {
        val w = frame.width
        val h = frame.height
        val rgba = ByteArray(w * h * 4)
        LibYuv.i420ToRgbaBt709(frame.y, frame.u, frame.v, rgba, w, h)
        latest = VideoFrame(w, h, rgba)
        decodedFrames++
    }

    private fun closeCodecs() {
        encoder?.let {
            AMediaCodec_stop(it)
            AMediaCodec_delete(it)
        }
        encoder = null
        decoder?.let { runCatching { it.close() } }
        decoder = null
        parameterSets = null
        packets.clear()
    }

    private fun fail(message: String) {
        error = message
        close()
        retryAt = SDL.getTicks() + 2_000u
    }

    private fun rotated(): Boolean =
        Rotation.entries[rotationIndex] == Rotation.CLOCKWISE_90 ||
            Rotation.entries[rotationIndex] == Rotation.CLOCKWISE_270

    private fun rotationName(): String = when (Rotation.entries[rotationIndex]) {
        Rotation.NONE -> "0 deg"
        Rotation.CLOCKWISE_90 -> "90 deg"
        Rotation.ROTATE_180 -> "180 deg"
        Rotation.CLOCKWISE_270 -> "270 deg"
    }
}

internal actual fun createVideoPipeline(): VideoPipeline = AndroidVideoPipeline()

private fun CPointer<UByteVar>.write(bytes: ByteArray, length: Int) {
    if (length == 0) return
    val dst = interpretCPointer<ByteVar>(rawValue) ?: return
    bytes.usePinned { memcpy(dst, it.addressOf(0), length.convert()) }
}

private fun CPointer<UByteVar>.read(offset: Int, length: Int): ByteArray {
    if (length <= 0) return ByteArray(0)
    val base = interpretCPointer<ByteVar>(rawValue)
        ?: throw IllegalStateException("null MediaCodec buffer")
    return base.plus(offset)?.readFast(length) ?: ByteArray(0)
}

private fun CPointer<*>.readFast(length: Int): ByteArray {
    val out = ByteArray(length)
    if (length == 0) return out
    out.usePinned { memcpy(it.addressOf(0), this, length.convert()) }
    return out
}

/** True when the Annex-B buffer contains a NAL unit of [type]. */
private fun ByteArray.containsNalType(type: Int): Boolean {
    var index = 0
    while (index + 4 < size) {
        val startCode = when {
            this[index].toInt() == 0 && this[index + 1].toInt() == 0 &&
                this[index + 2].toInt() == 0 && this[index + 3].toInt() == 1 -> 4
            this[index].toInt() == 0 && this[index + 1].toInt() == 0 &&
                this[index + 2].toInt() == 1 -> 3
            else -> 0
        }
        if (startCode > 0 && index + startCode < size) {
            if ((this[index + startCode].toInt() and 0x1F) == type) return true
            index += startCode
        } else {
            index++
        }
    }
    return false
}
