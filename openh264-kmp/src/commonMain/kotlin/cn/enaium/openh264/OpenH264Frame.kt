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

package cn.enaium.openh264

/** Chroma format of a decoded picture. OpenH264 always outputs 4:2:0. */
enum class OpenH264ChromaFormat(val value: Int) {
    I420(1),
    ;

    internal fun chromaWidth(width: Int): Int = (width + 1) / 2

    internal fun chromaHeight(height: Int): Int = (height + 1) / 2

    internal companion object {
        fun fromValue(value: Int): OpenH264ChromaFormat =
            entries.firstOrNull { it.value == value }
                ?: error("Unknown OpenH264 colour space $value")
    }
}

/**
 * One decoded picture, planar 8-bit I420.
 *
 * The planes are tightly packed copies of the decoder's output: `y` is
 * [width] x [height], `u` and `v` are half width and half height (rounded
 * up). The frame is independent of the decoder, so it stays valid after the
 * next [OpenH264Decoder.decode] call.
 */
class OpenH264Frame(
    val width: Int,
    val height: Int,
    val chromaFormat: OpenH264ChromaFormat,
    val pts: Long,
    val y: ByteArray,
    val u: ByteArray,
    val v: ByteArray,
) {
    val chromaWidth: Int get() = chromaFormat.chromaWidth(width)

    val chromaHeight: Int get() = chromaFormat.chromaHeight(height)

    /** Total payload bytes of the three planes. */
    val byteSize: Int get() = y.size + u.size + v.size

    /** Concatenates the planes in I420 order. */
    fun toI420(): ByteArray {
        val out = ByteArray(byteSize)
        y.copyInto(out, 0)
        u.copyInto(out, y.size)
        v.copyInto(out, y.size + u.size)
        return out
    }

    override fun toString(): String =
        "OpenH264Frame(${width}x$height $chromaFormat, pts=$pts, bytes=$byteSize)"
}
