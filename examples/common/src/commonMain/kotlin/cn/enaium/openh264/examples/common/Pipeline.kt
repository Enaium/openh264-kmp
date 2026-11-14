/*
 * Copyright (c) 2026 Enaium
 * SPDX-License-Identifier: BSD-2-Clause
 */

@file:OptIn(kotlin.concurrent.atomics.ExperimentalAtomicApi::class)

package cn.enaium.openh264.examples.common

import cn.enaium.camera.CameraConfig
import cn.enaium.camera.CameraFormat
import cn.enaium.camera.CameraLensFacing
import cn.enaium.camera.CameraSession
import cn.enaium.camera.CameraSystem
import cn.enaium.camera.I420Frame
import cn.enaium.camera.PixelFormat
import cn.enaium.camera.cameraSystem
import cn.enaium.openh264.OpenH264ChromaFormat
import cn.enaium.openh264.OpenH264Decoder
import cn.enaium.x264.X264ChromaFormat
import cn.enaium.x264.X264Encoder
import cn.enaium.x264.X264LogLevel
import cn.enaium.x264.X264Param
import cn.enaium.x264.X264Picture
import cn.enaium.x264.X264Preset
import cn.enaium.x264.X264Tune
import kotlin.concurrent.atomics.AtomicReference

/** What the UI shows above the pictures. */
internal data class ExampleStatus(
    val source: String,
    val encodedFrames: Long,
    val encodedBytes: Long,
    val decodedFrames: Long,
    val lastFrameSize: Int,
    val error: String?,
)

/**
 * Camera (camera-kmp) -> x264 encode -> openh264 decode.
 *
 * The captured I420 picture is encoded with x264 and the Annex-B access units
 * are fed straight into openh264, so the decoded picture is the camera picture
 * after a full round trip. A deterministic test pattern takes over only until
 * the camera delivers its first frame (or when no camera exists), so the
 * preview never alternates between the two sources.
 */
internal class ExamplePipeline {
    private val camera = CameraSource()
    private val loop = EncodeDecodeLoop()
    private val pattern = TestPattern(WIDTH, HEIGHT)
    private var lastCamera: I420Frame? = null

    var preview: I420Frame? = null
        private set

    var decoded: I420Frame? = null
        private set

    val status: ExampleStatus
        get() = ExampleStatus(
            source = camera.source,
            encodedFrames = loop.encodedFrames,
            encodedBytes = loop.encodedBytes,
            decodedFrames = loop.decodedFrames,
            lastFrameSize = loop.lastFrameSize,
            error = camera.error ?: loop.error,
        )

    fun pump() {
        val fresh = camera.poll()
        if (fresh != null) lastCamera = fresh
        val current = lastCamera ?: pattern.next()
        preview = current
        loop.step(current)?.let { decoded = it }
    }

    fun restartCamera() {
        camera.restart()
    }

    fun close() {
        camera.close()
        loop.close()
    }

    private companion object {
        const val WIDTH = 640
        const val HEIGHT = 480
    }
}

/** Owns the camera session and hands the newest I420 frame to the UI thread. */
private class CameraSource {
    private val latest = AtomicReference<I420Frame?>(null)
    private var system: CameraSystem? = null
    private var session: CameraSession? = null

    var source: String = "starting camera..."
        private set

    var error: String? = null
        private set

    init {
        restart()
    }

    fun restart() {
        close()
        error = null
        latest.store(null)
        try {
            val system = cameraSystem()
            this.system = system
            val camera = system.defaultCamera(CameraLensFacing.FRONT)
                ?: system.cameras().firstOrNull()
            if (camera == null) {
                source = "no camera - synthetic test pattern"
                return
            }
            val session = system.open(
                camera,
                CameraConfig(
                    format = CameraFormat(640, 480, PixelFormat.I420, 30),
                    lensFacing = camera.lensFacing,
                    mirrorHorizontally = camera.lensFacing == CameraLensFacing.FRONT,
                ),
            )
            this.session = session
            session.start(
                { frame -> latest.store(frame.toI420()) },
                { failure ->
                    error = failure.message ?: failure.toString()
                    source = "camera error - last picture"
                },
            )
            source = "${camera.name} ${session.format.width}x${session.format.height} " +
                "@${session.format.frameRate}fps (${system.name})"
        } catch (t: Throwable) {
            error = t.message ?: t.toString()
            source = "camera unavailable - synthetic test pattern"
        }
    }

    fun poll(): I420Frame? {
        val frame = latest.load()
        if (frame != null) latest.store(null)
        return frame
    }

    fun close() {
        runCatching { session?.close() }
        session = null
        runCatching { system?.close() }
        system = null
    }
}

/**
 * The capture encoder: MediaCodec/WebCodecs where the platform exposes them,
 * x264 on the desktop targets; its Annex-B output is decoded by openh264.
 */
private class EncodeDecodeLoop {
    private var encoder: PlatformEncoder? = null
    private val decoder = OpenH264Decoder.create()

    var encodedFrames = 0L
        private set

    var encodedBytes = 0L
        private set

    var decodedFrames = 0L
        private set

    var lastFrameSize = 0
        private set

    var error: String? = null
        private set

    private var pumps = 0L
    private var usingPlatform = false

    fun step(frame: I420Frame): I420Frame? {
        if (error != null) return null
        return try {
            var active = encoder ?: openEncoder(frame.width, frame.height)
            pumps++
            if (active.status == -1 || (usingPlatform && pumps > 60 && encodedFrames == 0L)) {
                active.close()
                active = X264PlatformEncoder(frame.width, frame.height)
                encoder = active
            }
            for (chunk in active.encode(frame)) {
                encodedFrames++
                encodedBytes += chunk.data.size
                lastFrameSize = chunk.data.size
                feed(chunk.data, chunk.pts)
            }
            lastDecoded
        } catch (t: Throwable) {
            error = t.message ?: t.toString()
            null
        }
    }

    private var lastDecoded: I420Frame? = null

    private fun openEncoder(width: Int, height: Int): PlatformEncoder {
        val selected = createPlatformEncoder(PlatformCodecKind.HEVC, width, height)
            ?: X264PlatformEncoder(width, height)
        encoder = selected
        return selected
    }

    private fun feed(data: ByteArray, pts: Long) {
        val frame = decoder.decode(data, pts) ?: return
        if (frame.chromaFormat != OpenH264ChromaFormat.I420) return
        decodedFrames++
        lastDecoded = I420Frame(frame.width, frame.height, frame.y, frame.u, frame.v, frame.pts)
    }

    fun close() {
        runCatching { encoder?.close() }
        runCatching { decoder.close() }
        encoder = null
    }
}

/** x264 fallback for the desktop targets (and browsers without WebCodecs). */
private class X264PlatformEncoder(width: Int, height: Int) : PlatformEncoder {
    private var encoder: X264Encoder? = null
    private var headersSent = false

    init {
        val param = X264Param.create(width, height, X264ChromaFormat.I420, X264Preset.ULTRAFAST)
        try {
            param.tune(X264Tune.ZERO_LATENCY)
            param.set("crf", 28)
            param.set("keyint", 30)
            param.set("repeat-headers", true)
            param.logLevel = X264LogLevel.NONE
            encoder = X264Encoder.open(param)
        } finally {
            param.close()
        }
    }

    override fun encode(frame: I420Frame): List<EncodedChunk> {
        val active = encoder ?: return emptyList()
        val output = active.encode(
            X264Picture.i420(
                frame.y, frame.u, frame.v,
                frame.width, frame.height,
                if (frame.timestampNanos > 0) frame.timestampNanos / 1_000 else 0L,
            ),
        ) ?: return emptyList()
        val chunks = ArrayList<EncodedChunk>(2)
        if (!headersSent) {
            headersSent = true
            chunks += EncodedChunk(active.headers().parameterSets(), output.pts, true)
        }
        chunks += EncodedChunk(output.toAnnexB(), output.pts, output.isKeyFrame)
        return chunks
    }

    override fun close() {
        runCatching { encoder?.close() }
        encoder = null
    }
}

/** Deterministic I420 colour bars with a moving box, used when no camera exists. */
private class TestPattern(private val width: Int, private val height: Int) {
    private var frame = 0L

    fun next(): I420Frame {
        val y = ByteArray(width * height)
        val u = ByteArray(((width + 1) / 2) * ((height + 1) / 2))
        val v = ByteArray(u.size)
        val bars = intArrayOf(16, 60, 100, 140, 180, 220, 250, 16)
        for (row in 0 until height) {
            for (column in 0 until width) {
                y[row * width + column] = bars[(column * bars.size / width).coerceIn(0, bars.size - 1)].toByte()
            }
        }
        val boxWidth = width / 6
        val boxHeight = height / 6
        val boxX = ((frame * width / 90) % (width - boxWidth)).toInt()
        val boxY = height / 3
        for (row in boxY until (boxY + boxHeight).coerceAtMost(height)) {
            for (column in boxX until (boxX + boxWidth).coerceAtMost(width)) {
                y[row * width + column] = 235.toByte()
            }
        }
        u.fill(128.toByte())
        v.fill(128.toByte())
        frame++
        return I420Frame(width, height, y, u, v, frame * 33_333_333L)
    }
}
