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

import cn.enaium.imgui.ImFontConfig
import cn.enaium.imgui.ImGui
import cn.enaium.imgui.ImTextureID
import cn.enaium.imgui.backends.sdl.ImGuiSdlBackend
import cn.enaium.imgui.backends.sdl.ImGuiSdlRendererBackend
import cn.enaium.imgui.backends.sdl.toImTextureID
import cn.enaium.sdl.nativePtr
import cn.enaium.sdl.SDL
import cn.enaium.sdl.SDLBlendMode
import cn.enaium.sdl.SDLColor
import cn.enaium.sdl.SDLEvent
import cn.enaium.sdl.SDLInitFlags
import cn.enaium.sdl.SDLPixelFormat
import cn.enaium.sdl.SDLScaleMode
import cn.enaium.sdl.SDLTexture
import cn.enaium.sdl.SDLTextureAccess
import cn.enaium.sdl.SDLWindow
import cn.enaium.sdl.SDLWindowEventType
import cn.enaium.sdl.SDLWindowFlags
import sdl3.SDL_GetWindowDisplayScale

/**
 * Boots an SDL3 window with the published imgui-kmp SDL backends and runs the
 * frame loop calling [draw]. Returns `false` when SDL cannot open a window.
 */
internal object ImGuiSdlHost {

    fun run(
        title: String,
        frames: Int = Int.MAX_VALUE,
        draw: (frame: Int, presenter: FramePresenter) -> Unit,
    ): Boolean {
        SDL.setMainReady()
        if (!SDL.init(SDLInitFlags.VIDEO or SDLInitFlags.EVENTS)) {
            println("SDL_Init failed: ${SDL.error()}$videoInitHint")
            return false
        }
        val version = SDL.version()
        println("SDL ${version.major}.${version.minor}.${version.micro} (${SDL.revision()})")
        println("video driver: ${SDL.getCurrentVideoDriver()}")

        SDL.createWindow(
            title = title,
            width = 1280,
            height = 800,
            flags = SDLWindowFlags.RESIZABLE or SDLWindowFlags.HIGH_PIXEL_DENSITY,
        ).use { window ->
            if (windowStartsFullscreen) window.fullscreen = true

            val context = ImGui.createContext()
            try {
                val platform = ImGuiSdlBackend(window)
                platform.init()
                // The 2D renderer is used on every platform: SDL_GPU needs the
                // window to be claimed by the GPU device before its swapchain
                // format can be queried, which the imgui-kmp backend does not do
                // on Android. The renderer path is the portable one.
                val frameBackend = RendererFrameBackend(window)
                try {
                    // Bake the font atlas at the display's pixel density so the
                    // text stays crisp on Retina and high-density phone screens.
                    val fonts = ImGui.getIO().fonts
                    // framebufferScale is 1 when SDL already reports window
                    // coordinates in physical pixels (Android). The window's
                    // display scale is the density the UI has to grow by.
                    val displayScale = window.nativePtr?.let { SDL_GetWindowDisplayScale(it) } ?: 0f
                    val density = maxOf(
                        displayScale,
                        platform.framebufferScale.x,
                        platform.framebufferScale.y,
                        1f,
                    ).coerceAtMost(3f)
                    fonts.addFontDefault(ImFontConfig(sizePixels = 13f * density, rasterizerDensity = 1f))
                    check(fonts.build()) { "font atlas build failed" }
                    val texData = fonts.getTexDataAsRGBA32()
                    fonts.setTexID(frameBackend.uploadFontTexture(texData.pixels, texData.width, texData.height))

                    var running = true
                    var frame = 0
                    while (running && frame < frames) {
                        while (true) {
                            val event = SDL.pollEvent() ?: break
                            when (event) {
                                is SDLEvent.Quit -> running = false
                                is SDLEvent.Window ->
                                    if (event.type == SDLWindowEventType.CLOSE_REQUESTED) running = false

                                else -> platform.processEvent(event)
                            }
                        }

                        platform.newFrame()
                        draw(frame, frameBackend)
                        ImGui.render()
                        frameBackend.render()
                        frame++
                    }
                } finally {
                    frameBackend.close()
                }
            } finally {
                ImGui.destroyContext(context)
            }
        }
        return true
    }

    /** Uploads a full decoded frame for [ImGui.image]. */
    internal interface FramePresenter {
        fun uploadRgba(pixels: ByteArray, width: Int, height: Int): ImTextureID
    }

    private interface FrameBackend : FramePresenter {
        fun uploadFontTexture(pixels: ByteArray, width: Int, height: Int): ImTextureID
        fun render()
        fun close()
    }

    /** SDL's 2D renderer. */
    private class RendererFrameBackend(window: SDLWindow) : FrameBackend {
        private val renderer = SDL.createRenderer(window)
        private val backend = ImGuiSdlRendererBackend(renderer)
        private var image: SDLTexture? = null
        private var imageWidth = 0
        private var imageHeight = 0

        override fun uploadFontTexture(pixels: ByteArray, width: Int, height: Int): ImTextureID =
            backend.uploadFontTexture(pixels, width, height)

        override fun uploadRgba(pixels: ByteArray, width: Int, height: Int): ImTextureID {
            val texture = if (image == null || imageWidth != width || imageHeight != height) {
                image?.let { backend.unregisterTexture(it) }
                val created = renderer.createTexture(
                    format = SDLPixelFormat.RGBA32,
                    access = SDLTextureAccess.STREAMING,
                    width = width,
                    height = height,
                )
                created.blendMode = SDLBlendMode.BLEND
                created.scaleMode = SDLScaleMode.LINEAR
                backend.registerTexture(created)
                image = created
                imageWidth = width
                imageHeight = height
                created
            } else {
                image!!
            }
            texture.update(rect = null, pixels = pixels, pitch = width * 4)
            return texture.toImTextureID()
        }

        override fun render() {
            renderer.drawColor = SDLColor(18, 18, 24)
            renderer.clear()
            backend.renderDrawData(ImGui.getDrawData())
            renderer.present()
        }

        override fun close() {
            backend.close()
            renderer.close()
        }
    }
}
