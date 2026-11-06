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

package cn.enaium.openh264.examples.androidjvm

import android.graphics.Bitmap
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.SystemClock
import android.util.Log
import cn.enaium.libyuv.I420Frame
import cn.enaium.libyuv.LibYuv
import cn.enaium.openh264.OpenH264Decoder
import cn.enaium.openh264.OpenH264Frame
import java.nio.ByteBuffer
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit

internal data class PipelineStats(
    val encodedFrames: Long,
    val decodedFrames: Long,
    val droppedFrames: Long,
    val bytes: Long,
    val width: Int,
    val height: Int,
    val fps: Double,
)

/**
 * Camera frames in, decoded video out.
 *
 *   CameraX (YUV_420_888) -> libyuv I420
 *     -> MediaCodec AVC encoder (hardware, Annex-B output)
 *     -> OpenH264 decoder (this binding)
 *     -> libyuv I420 -> RGBA -> Bitmap
 *
 * Only one thread touches the codecs; [submit] is the only method safe to call
 * from the camera executor.
 */
internal class H264Pipeline(
    private val onFrame: (Bitmap) -> Unit,
    private val onStats: (PipelineStats) -> Unit,
    private val onError: (Throwable) -> Unit,
) {
    private val queue = ArrayBlockingQueue<I420Frame>(3)

    @Volatile
    private var running = false
    private var thread: Thread? = null

    fun start() {
        if (running) return
        running = true
        thread = Thread({ runPipeline() }, "openh264-avc").apply { start() }
    }

    fun submit(frame: I420Frame) {
        if (!running) return
        // Keep the newest frame: encoding slower than the camera must not grow
        // an unbounded backlog.
        while (!queue.offer(frame)) {
            queue.poll()
        }
    }

    fun stop() {
        if (!running) return
        running = false
        queue.clear()
        thread?.join(1_000)
        thread = null
    }

    private fun runPipeline() {
        val session = Session(onFrame, onStats)
        try {
            while (running || queue.isNotEmpty()) {
                val frame = queue.poll(20, TimeUnit.MILLISECONDS) ?: continue
                session.encode(frame)
            }
            session.finish()
        } catch (t: Throwable) {
            onError(t)
        } finally {
            session.close()
        }
    }

    private class Session(
        private val onFrame: (Bitmap) -> Unit,
        private val onStats: (PipelineStats) -> Unit,
    ) {
        private var encoder: MediaCodec? = null
        private var decoder: OpenH264Decoder? = null
        private var parameterSets: ByteArray? = null
        private var width = 0
        private var height = 0
        private var encodedFrames = 0L
        private var decodedFrames = 0L
        private var droppedFrames = 0L
        private var bytes = 0L
        private var lastReportMs = SystemClock.elapsedRealtime()
        private var framesSinceReport = 0

        fun encode(frame: I420Frame) {
            val codec = encoder ?: createEncoder(frame.width, frame.height)
            val inputIndex = codec.dequeueInputBuffer(10_000)
            if (inputIndex < 0) {
                droppedFrames++
                report(force = false)
                return
            }
            val y = frame.y.toPlaneArray(frame.width * frame.height)
            val u = frame.u.toPlaneArray(frame.chromaWidth * frame.chromaHeight)
            val v = frame.v.toPlaneArray(frame.chromaWidth * frame.chromaHeight)
            val image = codec.getInputImage(inputIndex)
            if (image != null) {
                copyPlane(y, frame.width, image.planes[0], frame.width, frame.height)
                copyPlane(u, frame.chromaWidth, image.planes[1], frame.chromaWidth, frame.chromaHeight)
                copyPlane(v, frame.chromaWidth, image.planes[2], frame.chromaWidth, frame.chromaHeight)
            } else {
                val buffer = codec.getInputBuffer(inputIndex)
                if (buffer == null) {
                    droppedFrames++
                    return
                }
                buffer.clear()
                buffer.put(y)
                buffer.put(u)
                buffer.put(v)
            }
            codec.queueInputBuffer(
                inputIndex,
                0,
                frame.width * frame.height * 3 / 2,
                frame.presentationTimeUs,
                0,
            )
            drainEncoder()
            report(force = false)
        }

        fun finish() {
            val codec = encoder ?: return
            val index = codec.dequeueInputBuffer(100_000)
            if (index >= 0) {
                codec.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
            }
            drainEncoder(endOfStream = true)
            decoder?.flush()?.forEach(::render)
            report(force = true)
        }

        fun close() {
            val codec = encoder
            encoder = null
            if (codec != null) {
                runCatching { codec.stop() }
                runCatching { codec.release() }
            }
            decoder?.let { runCatching { it.close() } }
            decoder = null
        }

        private fun createEncoder(sourceWidth: Int, sourceHeight: Int): MediaCodec {
            width = sourceWidth
            height = sourceHeight
            val format = MediaFormat.createVideoFormat(MIME_AVC, sourceWidth, sourceHeight).apply {
                setInteger(
                    MediaFormat.KEY_COLOR_FORMAT,
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible,
                )
                setInteger(MediaFormat.KEY_BIT_RATE, BITRATE)
                setInteger(MediaFormat.KEY_FRAME_RATE, FRAME_RATE)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, I_FRAME_INTERVAL)
                setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR)
                // MediaCodec then writes SPS/PPS before every IDR, so the
                // access units handed to OpenH264 are self-contained.
                setInteger("prepend-sps-pps-to-idr-frames", 1)
            }
            val codec = MediaCodec.createEncoderByType(MIME_AVC)
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()
            encoder = codec
            decoder = OpenH264Decoder.create()
            Log.i(TAG, "encoder=${codec.name} ${sourceWidth}x$sourceHeight")
            return codec
        }

        private fun drainEncoder(endOfStream: Boolean = false) {
            val codec = encoder ?: return
            val info = MediaCodec.BufferInfo()
            while (true) {
                val index = codec.dequeueOutputBuffer(info, 0)
                when {
                    index == MediaCodec.INFO_TRY_AGAIN_LATER -> return
                    index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val format = codec.outputFormat
                        if (format.containsKey("csd-0")) {
                            parameterSets = format.getByteBuffer("csd-0")?.toByteArray()
                        }
                        Log.i(TAG, "encoder output format ${format.getString(MediaFormat.KEY_MIME)}")
                    }
                    index >= 0 -> {
                        val buffer = codec.getOutputBuffer(index)
                        if (buffer != null && info.size > 0) {
                            val data = ByteArray(info.size)
                            buffer.position(info.offset)
                            buffer.get(data)
                            if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                                parameterSets = data
                            } else {
                                publish(data, info.presentationTimeUs, info.flags)
                            }
                        }
                        codec.releaseOutputBuffer(index, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                    }
                    else -> return
                }
                if (endOfStream) return
            }
        }

        private fun publish(data: ByteArray, ptsUs: Long, flags: Int) {
            val keyFrame = flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0
            encodedFrames++
            bytes += data.size
            framesSinceReport++
            val accessUnit = if (keyFrame && !data.containsStartCodeOf(7)) {
                val csd = parameterSets ?: ByteArray(0)
                csd + data
            } else {
                data
            }
            val decoded = decoder?.decode(accessUnit, ptsUs)
            if (decoded != null) {
                render(decoded)
            } else {
                droppedFrames++
            }
        }

        private fun render(frame: OpenH264Frame) {
            val rgba = ByteArray(frame.width * frame.height * 4)
            LibYuv.i420ToRgbaBt709(frame.y, frame.u, frame.v, rgba, frame.width, frame.height)
            val bitmap = Bitmap.createBitmap(frame.width, frame.height, Bitmap.Config.ARGB_8888)
            bitmap.copyPixelsFromBuffer(ByteBuffer.wrap(rgba))
            decodedFrames++
            onFrame(bitmap)
        }

        private fun copyPlane(
            source: ByteArray,
            sourceStride: Int,
            plane: android.media.Image.Plane,
            planeWidth: Int,
            planeHeight: Int,
        ) {
            val buffer = plane.buffer
            val rowStride = plane.rowStride
            val pixelStride = plane.pixelStride
            for (row in 0 until planeHeight) {
                var sourceIndex = row * sourceStride
                var targetIndex = row * rowStride
                for (column in 0 until planeWidth) {
                    buffer.put(targetIndex, source[sourceIndex])
                    sourceIndex++
                    targetIndex += pixelStride
                }
            }
        }

        private fun report(force: Boolean) {
            val now = SystemClock.elapsedRealtime()
            val elapsed = now - lastReportMs
            if (!force && elapsed < 500) return
            val fps = if (elapsed > 0) framesSinceReport * 1000.0 / elapsed else 0.0
            onStats(
                PipelineStats(
                    encodedFrames = encodedFrames,
                    decodedFrames = decodedFrames,
                    droppedFrames = droppedFrames,
                    bytes = bytes,
                    width = width,
                    height = height,
                    fps = fps,
                ),
            )
            lastReportMs = now
            framesSinceReport = 0
        }

        private companion object {
            const val MIME_AVC = MediaFormat.MIMETYPE_VIDEO_AVC
            const val TAG = "openh264-avc"
            const val BITRATE = 2_000_000
            const val FRAME_RATE = 30
            const val I_FRAME_INTERVAL = 1
        }
    }

    private companion object {
        const val TAG = "openh264-avc"
    }
}

/** Copies a direct plane into a tightly packed array. */
private fun ByteBuffer.toPlaneArray(size: Int): ByteArray {
    val duplicate = duplicate()
    duplicate.clear()
    val out = ByteArray(size)
    duplicate.get(out, 0, minOf(size, duplicate.remaining()))
    return out
}

private fun ByteBuffer.toByteArray(): ByteArray {
    val duplicate = duplicate()
    duplicate.clear()
    val out = ByteArray(duplicate.remaining())
    duplicate.get(out)
    return out
}

/** True when the Annex-B buffer contains a NAL unit of [type]. */
private fun ByteArray.containsStartCodeOf(type: Int): Boolean {
    var index = 0
    while (index + 4 < size) {
        val startCode = if (
            this[index].toInt() == 0 && this[index + 1].toInt() == 0 &&
            this[index + 2].toInt() == 0 && this[index + 3].toInt() == 1
        ) {
            4
        } else if (
            this[index].toInt() == 0 && this[index + 1].toInt() == 0 &&
            this[index + 2].toInt() == 1
        ) {
            3
        } else {
            0
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

