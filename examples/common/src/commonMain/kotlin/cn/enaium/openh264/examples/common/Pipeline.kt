/*
 * Copyright (c) 2026 Enaium
 * SPDX-License-Identifier: LGPL-3.0-or-later
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
import kotlin.concurrent.atomics.AtomicReference
import kotlin.io.encoding.Base64

/** What the UI shows above the pictures. */
internal data class ExampleStatus(
    val source: String,
    val decodedFrames: Long,
    val lastFrameSize: Int,
    val error: String?,
)

/**
 * Camera (camera-kmp) plus the OpenH264 decoder.
 *
 * The camera picture proves the shared capture path on every target; the
 * decoder loop repeatedly feeds the embedded HEVC stream so the binding is
 * exercised even when no camera is available.
 */
internal class ExamplePipeline {
    private val camera = CameraSource()
    private val decoder = OpenH264Pipeline()
    private val pattern = TestPattern(WIDTH, HEIGHT)
    private var pump = 0L
    private var lastDecoded: I420Frame? = null

    var preview: I420Frame? = null
        private set

    var decoded: I420Frame? = null
        private set

    val status: ExampleStatus
        get() = ExampleStatus(
            source = camera.source,
            decodedFrames = decoder.frames,
            lastFrameSize = decoder.lastFrameSize,
            error = camera.error ?: decoder.error,
        )

    fun pump() {
        preview = camera.poll() ?: pattern.next()
        if (pump++ % 2L == 0L) {
            decoder.step()?.let { lastDecoded = it }
        }
        decoded = lastDecoded
    }

    fun restartCamera() {
        camera.restart()
    }

    fun close() {
        camera.close()
        decoder.close()
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
                    source = "camera error - synthetic test pattern"
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

/** Decodes the embedded Annex-B stream over and over. */
private class OpenH264Pipeline {
    private val decoder = OpenH264Decoder.create()
    private val stream: ByteArray by lazy { Base64.decode(SAMPLE_STREAM_BASE64) }
    private var pts = 0L

    var frames = 0L
        private set

    var lastFrameSize = 0
        private set

    var error: String? = null
        private set

    fun step(): I420Frame? {
        if (error != null) return null
        return try {
            val frame = decoder.decode(stream, pts * 33_333L)
            pts++
            if (frame == null || frame.chromaFormat != OpenH264ChromaFormat.I420) return null
            frames++
            lastFrameSize = frame.byteSize
            I420Frame(frame.width, frame.height, frame.y, frame.u, frame.v, frame.pts)
        } catch (t: Throwable) {
            error = t.message ?: t.toString()
            null
        }
    }

    fun close() {
        runCatching { decoder.close() }
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
