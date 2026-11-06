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

/** One RGBA frame ready for the ImGui draw list. */
internal data class VideoFrame(
    val width: Int,
    val height: Int,
    val rgba: ByteArray,
)

/** What the pipeline is doing, shown above the image. */
internal data class PipelineStatus(
    val source: String,
    val path: String,
    val rotation: String,
    val encodedFrames: Long,
    val encodedBytes: Long,
    val decodedFrames: Long,
    val error: String?,
)

/**
 * The capture -> convert -> encode -> decode -> present loop.
 *
 * Android native implements it with the Camera2 NDK, libyuv, MediaCodec and OpenH264 and the NDK
 * MediaCodec API. The desktop build substitutes a synthetic capture source and
 * only runs the OpenH264 half, because Android's MediaCodec is not available there;
 * the UI code is shared.
 */
internal interface VideoPipeline {

    val status: PipelineStatus

    /** Advances capture and both codecs once; never blocks the caller. */
    fun pump()

    /** The newest decoded frame, or null until the first one is available. */
    fun currentFrame(): VideoFrame?

    /** Tears the current camera/codecs down and starts them again. */
    fun restart()

    /** Rotates the frames before encoding, correcting the sensor orientation. */
    fun cycleRotation()

    /** Releases the camera and the codecs. */
    fun close()
}

internal expect fun createVideoPipeline(): VideoPipeline
