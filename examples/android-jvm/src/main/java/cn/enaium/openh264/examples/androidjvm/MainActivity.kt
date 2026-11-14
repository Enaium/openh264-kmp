package cn.enaium.openh264.examples.androidjvm

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import cn.enaium.sdl.SDL
import cn.enaium.openh264.examples.common.runExample
import org.libsdl.app.SDLActivity

/**
 * Android JVM host of the shared example.
 *
 * The stock SDL Android integration loads a native library and calls its
 * SDL_main; a Kotlin/JVM app has none, so this activity overrides [main]
 * instead (SDL's documented application entry point, run on SDL's dedicated
 * thread) and calls the same commonMain loop the browser and desktop builds
 * use. Camera capture goes through camera-kmp's Camera2 backend.
 */
class MainActivity : SDLActivity() {

    override fun getLibraries(): Array<String> = arrayOf("sdl_jni")

    override fun onCreate(savedInstanceState: Bundle?) {
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.CAMERA), CAMERA_REQUEST)
        }
        super.onCreate(savedInstanceState)
    }

    override fun main() {
        try {
            runExample()
        } catch (t: Throwable) {
            Log.e(TAG, "openh264 example failed", t)
        } finally {
            SDL.quit()
        }
    }

    private companion object {
        const val TAG = "OPENH264Example"
        const val CAMERA_REQUEST = 1
    }
}
