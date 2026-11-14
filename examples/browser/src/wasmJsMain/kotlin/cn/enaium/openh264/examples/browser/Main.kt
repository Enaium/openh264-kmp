package cn.enaium.openh264.examples.browser

import cn.enaium.openh264.examples.common.AppFrameDriver
import cn.enaium.openh264.examples.common.runExample
import kotlinx.browser.window

/**
 * The browser cannot block the main thread, so requestAnimationFrame drives
 * the shared SDL/ImGui loop one step at a time.
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
