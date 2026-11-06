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

import android.Manifest
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.os.Bundle
import org.libsdl.app.SDLActivity

/**
 * Launcher activity hosting the native example.
 *
 * SDLActivity loads the app's shared libraries and calls the exported `SDL_main`
 * symbol of `libmain.so` on a dedicated SDL thread. That library links SDL3,
 * ImGui, libyuv and OpenH264 statically.
 */
class MainActivity : SDLActivity() {

    /**
     * `libmain.so` references the C++ runtime without declaring it as a
     * dependency of its own, so `libc++_shared` is loaded first: with only
     * `main` the dynamic linker cannot resolve those symbols and SDL reports
     * `dlopen failed: cannot locate symbol _ZTTNSt6__ndk1...`.
     */
    override fun getLibraries(): Array<String> = arrayOf("c++_shared", "main")

    override fun onCreate(savedInstanceState: Bundle?) {
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.CAMERA), CAMERA_REQUEST)
        }
        super.onCreate(savedInstanceState)
    }

    /** The example is laid out for the wider axis. */
    override fun setOrientationBis(w: Int, h: Int, resizable: Boolean, hint: String?) {
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
    }

    private companion object {
        const val CAMERA_REQUEST = 1
    }
}
