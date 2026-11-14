@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlin.experimental.ExperimentalNativeApi::class)

package cn.enaium.openh264.examples.common

import cn.enaium.sdl.SDL
import cn.enaium.sdl.SDLLog
import kotlin.native.CName
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.CPointerVar
import platform.android.__android_log_write

/**
 * Android native entry point. SDLActivity loads libmain.so and calls the
 * exported SDL_main symbol on the SDL thread it manages.
 */
@CName("SDL_main")
fun sdlMain(argc: Int, argv: CPointer<CPointerVar<ByteVar>>?): Int {
    return try {
        runExample()
        SDL.quit()
        0
    } catch (t: Throwable) {
        __android_log_write(6, "OPENH264Example", "failed: ${t.message}")
        SDLLog.error("openh264 example failed: ${t.message}")
        t.printStackTrace()
        SDL.quit()
        1
    }
}
