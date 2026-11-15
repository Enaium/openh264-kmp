package cn.enaium.openh264.examples.common

import cn.enaium.camera.CameraFormat
import cn.enaium.camera.CameraLensFacing
import cn.enaium.camera.PixelFormat

/** Capture parameters the left-hand panel edits. */
internal data class CameraSettings(
    val width: Int = 640,
    val height: Int = 480,
    val rotationDegrees: Int = 0,
    val mirrorHorizontally: Boolean = false,
    val lensFacing: CameraLensFacing = CameraLensFacing.FRONT,
) {
    val outputWidth: Int
        get() = if (rotationDegrees % 180 == 0) width else height

    val outputHeight: Int
        get() = if (rotationDegrees % 180 == 0) height else width

    fun format(frameRate: Int = 30): CameraFormat =
        CameraFormat(width, height, PixelFormat.I420, frameRate)
}

internal object CameraOptions {
    val resolutions: List<Pair<Int, Int>> = listOf(
        320 to 240,
        640 to 480,
        960 to 540,
        1280 to 720,
        1920 to 1080,
    )
    val rotations: List<Int> = listOf(0, 90, 180, 270)
    val lenses: List<CameraLensFacing> = listOf(CameraLensFacing.FRONT, CameraLensFacing.BACK)
}
