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

package cn.enaium.openh264.examples.androidnative

import cn.enaium.imgui.ImGui
import cn.enaium.imgui.ImGuiCond
import cn.enaium.imgui.ImGuiWindowFlags
import cn.enaium.imgui.ImVec2
import cn.enaium.sdl.SDL
import cn.enaium.openh264.OpenH264
import kotlin.math.min

/**
 * The UI on top of the video pipeline. Android native runs
 * camera -> libyuv -> MediaCodec H.264 encode -> OpenH264 decode -> libyuv -> RGBA;
 * the desktop build supplies a synthetic source and only runs the OpenH264 half
 * because MediaCodec is Android only. The drawing code is the same on both.
 */
internal fun runOpenH264Example(frames: Int = Int.MAX_VALUE): Boolean {
    val pipeline = createVideoPipeline()
    return try {
        val ok = ImGuiSdlHost.run("openh264-kmp Android native", frames) { _, presenter ->
            pipeline.pump()
            renderer.draw(pipeline, presenter)
        }
        val status = pipeline.status
        println(
            "OpenH264 example: encoded=${status.encodedFrames} bytes=${status.encodedBytes} " +
                "decoded=${status.decodedFrames} error=${status.error}",
        )
        ok
    } finally {
        pipeline.close()
    }
}

private val renderer = NativeRenderer()

private class NativeRenderer {
    private var lastFrame: VideoFrame? = null
    private var fpsSince = SDL.getTicks()
    private var fpsFrames = 0
    private var renderFps = 0f
    private var lastEncoded = 0L
    private var lastDecoded = 0L
    private var encodeRate = 0f
    private var decodeRate = 0f

    fun draw(pipeline: VideoPipeline, presenter: ImGuiSdlHost.FramePresenter) {
        val frame = pipeline.currentFrame()
        if (frame != null) lastFrame = frame
        ui(pipeline, lastFrame, presenter)
    }

    private fun ui(
        pipeline: VideoPipeline,
        frame: VideoFrame?,
        presenter: ImGuiSdlHost.FramePresenter,
    ) {
        val status = pipeline.status
        updateRates(status)
        val display = ImGui.getIO().displaySize
        ImGui.setNextWindowPos(ImVec2(0f, 0f), ImGuiCond.ALWAYS)
        ImGui.setNextWindowSize(display, ImGuiCond.ALWAYS)
        ImGui.begin("openh264-kmp native", null, WINDOW_FLAGS)

        ImGui.text("OpenH264 ${OpenH264.libraryVersion} (api ${OpenH264.apiBuild}) - ${OpenH264.VERSION}")
        ImGui.text("capture: ${status.source}")
        ImGui.text("pipeline: ${status.path}")
        ImGui.text(
            "render ${renderFps.toInt()} fps  |  encode ${encodeRate.toInt()}/s  |  " +
                "decode ${decodeRate.toInt()}/s",
        )
        ImGui.text(
            "encoded ${status.encodedFrames}  |  decoded ${status.decodedFrames}  |  " +
                "rotation ${status.rotation}",
        )
        ImGui.text("bitrate: ${bitrateKbps(status)} kbit/s")
        status.error?.let { ImGui.text("error: $it") }

        if (ImGui.button("restart")) pipeline.restart()
        ImGui.sameLine()
        if (ImGui.button("rotate: ${status.rotation}")) pipeline.cycleRotation()
        ImGui.separator()

        val available = ImGui.getContentRegionAvail()
        if (frame == null) {
            ImGui.text("waiting for the first decoded frame...")
            ImGui.end()
            return
        }

        val scale = min(available.x / frame.width, available.y / frame.height).coerceAtLeast(0f)
        val drawWidth = frame.width * scale
        val drawHeight = frame.height * scale

        if (scale > 0f) {
            val texture = presenter.uploadRgba(frame.rgba, frame.width, frame.height)
            ImGui.image(texture, ImVec2(drawWidth, drawHeight))
        }
        ImGui.end()
    }

    private fun updateRates(status: PipelineStatus) {
        fpsFrames++
        val now = SDL.getTicks()
        val elapsed = now - fpsSince
        if (elapsed >= 1_000u) {
            val seconds = elapsed.toFloat() / 1_000f
            renderFps = fpsFrames / seconds
            encodeRate = (status.encodedFrames - lastEncoded) / seconds
            decodeRate = (status.decodedFrames - lastDecoded) / seconds
            lastEncoded = status.encodedFrames
            lastDecoded = status.decodedFrames
            fpsSince = now
            fpsFrames = 0
        }
    }

    /** Average bitrate over the frames encoded so far, assuming 30 fps input. */
    private fun bitrateKbps(status: PipelineStatus): Long {
        if (status.encodedFrames == 0L) return 0
        val bytesPerFrame = status.encodedBytes / status.encodedFrames
        return bytesPerFrame * 30 * 8 / 1_000
    }

    private companion object {
        const val WINDOW_FLAGS = ImGuiWindowFlags.NO_TITLE_BAR or
            ImGuiWindowFlags.NO_RESIZE or
            ImGuiWindowFlags.NO_MOVE or
            ImGuiWindowFlags.NO_SCROLLBAR or
            ImGuiWindowFlags.NO_COLLAPSE or
            ImGuiWindowFlags.NO_SAVED_SETTINGS or
            ImGuiWindowFlags.NO_BRING_TO_FRONT_ON_FOCUS or
            ImGuiWindowFlags.NO_NAV_FOCUS
    }
}
