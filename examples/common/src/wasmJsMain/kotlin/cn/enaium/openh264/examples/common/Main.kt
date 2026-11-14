package cn.enaium.openh264.examples.common

import kotlinx.browser.window

/**
 * Browser entry point. index.html loads the SDL, imgui and camera runtimes
 * first; requestAnimationFrame then drives the shared SDL/ImGui loop.
 */
private val browserDriver: AppFrameDriver = { step, done ->
    fun tick() {
        if (step()) window.requestAnimationFrame { tick() } else done()
    }
    window.requestAnimationFrame { tick() }
}

fun main() {
    runExample(driver = browserDriver)
}
