/*
 * Copyright (c) 2026 Enaium
 * SPDX-License-Identifier: BSD-2-Clause
 */

package cn.enaium.openh264.examples.common

import cn.enaium.camera.I420Frame
import cn.enaium.imgui.ImFontConfig
import cn.enaium.imgui.ImGui
import cn.enaium.imgui.ImGuiCond
import cn.enaium.imgui.ImGuiWindowFlags
import cn.enaium.imgui.ImTextureID
import cn.enaium.imgui.ImVec2
import cn.enaium.imgui.backends.sdl.ImGuiSdlBackend
import cn.enaium.imgui.backends.sdl.ImGuiSdlRendererBackend
import cn.enaium.imgui.backends.sdl.toImTextureID
import cn.enaium.libyuv.LibYuv
import cn.enaium.openh264.OpenH264
import cn.enaium.sdl.SDL
import cn.enaium.sdl.SDLBlendMode
import cn.enaium.sdl.SDLColor
import cn.enaium.sdl.SDLEvent
import cn.enaium.sdl.SDLInitFlags
import cn.enaium.sdl.SDLPixelFormat
import cn.enaium.sdl.SDLScaleMode
import cn.enaium.sdl.SDLTexture
import cn.enaium.sdl.SDLTextureAccess
import cn.enaium.sdl.SDLWindowEventType
import cn.enaium.sdl.SDLWindowFlags
import kotlin.math.min

/** Host-owned browser frame loop: wasm drives it from requestAnimationFrame. */
typealias AppFrameDriver = (step: () -> Boolean, done: () -> Unit) -> Unit

/**
 * Shared SDL3 + ImGui example. The left picture is the camera-kmp capture, the
 * right one is the same picture after the codec round trip.
 */
fun runExample(frames: Int = Int.MAX_VALUE, driver: AppFrameDriver? = null) {
    SDL.setMainReady()
    if (!SDL.init(SDLInitFlags.VIDEO or SDLInitFlags.EVENTS)) {
        SDL.setHint("SDL_VIDEO_DRIVER", "dummy")
        if (SDL.init(SDLInitFlags.VIDEO or SDLInitFlags.EVENTS)) {
            println("video init fell back to the dummy driver - running headless")
        } else {
            error("SDL_Init failed: ${SDL.error()}")
        }
    }
    println("SDL ${SDL.version()} (${SDL.revision()})")
    println("Video driver: ${SDL.getCurrentVideoDriver()}")

    val window = SDL.createWindow(
        title = "openh264-kmp example",
        width = 1280,
        height = 800,
        flags = SDLWindowFlags.RESIZABLE or SDLWindowFlags.HIGH_PIXEL_DENSITY,
    )
    val renderer = SDL.createRenderer(window)
    val context = ImGui.createContext()
    val imgui = ImGuiSdlBackend(window)
    val backend = ImGuiSdlRendererBackend(renderer)
    val pipeline = ExamplePipeline()
    var finished = false

    fun finish() {
        if (finished) return
        finished = true
        val status = pipeline.status
        println(
            "example: encoded=${status.encodedFrames} decoded=${status.decodedFrames} " +
                "bytes=${status.encodedBytes} error=${status.error}",
        )
        pipeline.close()
        backend.close()
        ImGui.destroyContext(context)
        renderer.close()
        window.close()
        SDL.quit()
    }

    try {
        imgui.init()

        val fonts = ImGui.getIO().fonts
        val density = maxOf(imgui.framebufferScale.x, imgui.framebufferScale.y, 1f).coerceAtMost(3f)
        fonts.addFontDefault(
            ImFontConfig(
                sizePixels = 13f * density,
                rasterizerDensity = density,
            ),
        )
        check(fonts.build()) { "font atlas build failed" }
        val texData = fonts.getTexDataAsRGBA32()
        fonts.setTexID(backend.uploadFontTexture(texData.pixels, texData.width, texData.height))

        val ui = ExampleUi(renderer, backend)
        var running = true
        var frameCount = 0

        fun step(): Boolean {
            while (true) {
                val event = SDL.pollEvent() ?: break
                when (event) {
                    is SDLEvent.Quit -> running = false
                    is SDLEvent.Window ->
                        if (event.type == SDLWindowEventType.CLOSE_REQUESTED) running = false
                    else -> imgui.processEvent(event)
                }
            }

            pipeline.pump()
            imgui.newFrame()
            ui.draw(pipeline)
            ImGui.render()

            renderer.drawColor = SDLColor(18, 18, 24, 255)
            renderer.clear()
            backend.renderDrawData(ImGui.getDrawData())
            renderer.present()
            frameCount++
            return running && frameCount < frames
        }

        if (driver != null) {
            driver(::step, ::finish)
        } else {
            while (step()) {
            }
            finish()
        }
    } catch (t: Throwable) {
        finish()
        throw t
    }
}

/** Uploads an I420 picture into one streaming SDL texture. */
private class FrameTextures(
    private val renderer: cn.enaium.sdl.SDLRenderer,
    private val backend: ImGuiSdlRendererBackend,
) {
    private var texture: SDLTexture? = null
    private var width = 0
    private var height = 0
    private var rgba = ByteArray(0)

    fun upload(frame: I420Frame): ImTextureID {
        val bytes = frame.width * frame.height * 4
        if (rgba.size != bytes) rgba = ByteArray(bytes)
        LibYuv.i420ToAbgr(
            frame.y, frame.u, frame.v, rgba,
            frame.width, frame.height,
            dstStrideAbgr = frame.width * 4,
        )
        val current = if (texture == null || width != frame.width || height != frame.height) {
            texture?.let { backend.unregisterTexture(it) }
            val created = renderer.createTexture(
                format = SDLPixelFormat.RGBA32,
                access = SDLTextureAccess.STREAMING,
                width = frame.width,
                height = frame.height,
            )
            created.blendMode = SDLBlendMode.BLEND
            created.scaleMode = SDLScaleMode.LINEAR
            backend.registerTexture(created)
            texture = created
            width = frame.width
            height = frame.height
            created
        } else {
            texture!!
        }
        current.update(rect = null, pixels = rgba, pitch = frame.width * 4)
        return current.toImTextureID()
    }
}

/** Statistics window with the camera and the decoded picture side by side. */
private class ExampleUi(
    renderer: cn.enaium.sdl.SDLRenderer,
    backend: ImGuiSdlRendererBackend,
) {
    private val cameraTextures = FrameTextures(renderer, backend)
    private val decodedTextures = FrameTextures(renderer, backend)
    private var lastCamera: I420Frame? = null
    private var lastDecoded: I420Frame? = null
    private var fpsSince = SDL.getTicks()
    private var fpsFrames = 0
    private var uiFps = 0f
    private var lastEncoded = 0L
    private var lastDecodedCount = 0L
    private var encodeRate = 0f
    private var decodeRate = 0f

    fun draw(pipeline: ExamplePipeline) {
        pipeline.preview?.let { lastCamera = it }
        pipeline.decoded?.let { lastDecoded = it }
        val status = pipeline.status
        updateRates(status)

        val display = ImGui.getIO().displaySize
        ImGui.setNextWindowPos(ImVec2(0f, 0f), ImGuiCond.ALWAYS)
        ImGui.setNextWindowSize(display, ImGuiCond.ALWAYS)
        ImGui.begin("openh264-kmp", null, WINDOW_FLAGS)

        ImGui.text("OpenH264 ${OpenH264.libraryVersion} (api ${OpenH264.apiBuild}) - camera-kmp + ${platformEncoderName} encode")
        ImGui.text("camera: ${status.source}")
        ImGui.text(
            "ui ${uiFps.toInt()} fps  |  encode ${encodeRate.toInt()}/s  decode ${decodeRate.toInt()}/s  |  " +
                "encoded ${status.encodedFrames}  |  decoded ${status.decodedFrames}  |  " +
                "last ${status.lastFrameSize} B",
        )
        ImGui.text("bitrate: ${bitrateKbps(status)} kbit/s")
        status.error?.let { ImGui.text("error: $it") }

        if (ImGui.button("restart camera")) pipeline.restartCamera()
        ImGui.separator()

        val available = ImGui.getContentRegionAvail()
        val gap = 8f
        val halfWidth = (available.x - gap) / 2f
        drawPicture("camera (camera-kmp)", lastCamera, cameraTextures, halfWidth, available.y)
        ImGui.sameLine(0f, gap)
        drawPicture("decoded (OpenH264)", lastDecoded, decodedTextures, halfWidth, available.y)
        ImGui.end()
    }

    private fun drawPicture(
        label: String,
        frame: I420Frame?,
        textures: FrameTextures,
        width: Float,
        height: Float,
    ) {
        ImGui.beginChild(label, ImVec2(width, height))
        ImGui.text(label)
        if (frame == null) {
            ImGui.text("waiting for a picture...")
            ImGui.endChild()
            return
        }
        val room = ImGui.getContentRegionAvail()
        val scale = min(room.x / frame.width, room.y / frame.height).coerceAtLeast(0f)
        if (scale > 0f) {
            ImGui.image(textures.upload(frame), ImVec2(frame.width * scale, frame.height * scale))
        }
        ImGui.endChild()
    }

    private fun updateRates(status: ExampleStatus) {
        fpsFrames++
        val now = SDL.getTicks()
        val elapsed = now - fpsSince
        if (elapsed >= 1_000u) {
            val seconds = elapsed.toFloat() / 1_000f
            uiFps = fpsFrames / seconds
            encodeRate = (status.encodedFrames - lastEncoded) / seconds
            decodeRate = (status.decodedFrames - lastDecodedCount) / seconds
            lastEncoded = status.encodedFrames
            lastDecodedCount = status.decodedFrames
            fpsSince = now
            fpsFrames = 0
        }
    }

    private fun bitrateKbps(status: ExampleStatus): Long {
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
