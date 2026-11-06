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

/**
 * Thrown when the native OpenH264 library rejects a call: an invalid access
 * unit, a failed decoder creation, or a decoder error.
 */
class OpenH264Exception(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Opaque decoder owned by the native library. The actual classes wrap an
 * `ok_decoder*` (JNI/Kotlin-Native) or a WebAssembly pointer.
 */
internal expect class OpenH264DecoderHandle() {
    // The actuals add the platform pointer; the common API only passes handles
    // between OpenH264Native calls.
}

/**
 * Bridge to the C++ shim, implemented for JNI, Kotlin/Native cinterop and the
 * WebAssembly module. Every implementation follows the same contract:
 *
 * - [decode]/[flush] return [OPENH264_OK] when a picture is ready,
 *   [OPENH264_BUFFERED] when there is none yet and a negative code on failure;
 * - [frameBlob] returns the packed `OPENH264_OUTPUT_MAGIC` record documented in
 *   `jni/include/openh264_kmp.h`.
 */
internal expect object OpenH264Native {
    fun versionPacked(): Int

    fun versionString(): String

    fun apiBuild(): Int

    fun decoderCreate(): OpenH264DecoderHandle?

    fun decoderDestroy(handle: OpenH264DecoderHandle)

    fun decode(handle: OpenH264DecoderHandle, accessUnit: ByteArray, pts: Long): Int

    fun flush(handle: OpenH264DecoderHandle): Int

    fun frameBlob(handle: OpenH264DecoderHandle): ByteArray?
}

/** `OK_OUTPUT_MAGIC`, see `jni/include/openh264_kmp.h`. */
internal const val OPENH264_OUTPUT_MAGIC: Int = 0x314B4F48

/** `OK_OUTPUT_VERSION`. */
internal const val OPENH264_OUTPUT_VERSION: Int = 1

/** `OK_OK`: the call produced a picture. */
internal const val OPENH264_OK: Int = 1

/** `OK_BUFFERED`: the input was accepted but no picture is ready. */
internal const val OPENH264_BUFFERED: Int = 0

/**
 * Parser for the packed result blob. Written by hand instead of using a
 * platform `ByteBuffer` so the exact same code runs on JVM, Kotlin/Native and
 * the web.
 */
internal object OpenH264Output {
    private const val HEADER = 64

    fun parse(bytes: ByteArray): OpenH264Frame {
        require(bytes.size >= HEADER) { "truncated OpenH264 output (${bytes.size} bytes)" }
        val magic = readInt(bytes, 0)
        check(magic == OPENH264_OUTPUT_MAGIC) {
            "bad OpenH264 output magic 0x${magic.toUInt().toString(16)}"
        }
        val version = readInt(bytes, 4)
        check(version == OPENH264_OUTPUT_VERSION) { "unsupported OpenH264 output version $version" }

        val width = readInt(bytes, 8)
        val height = readInt(bytes, 12)
        val chromaFormat = OpenH264ChromaFormat.fromValue(readInt(bytes, 16))
        val pts = readLong(bytes, 32)
        val ySize = readInt(bytes, 40)
        val uSize = readInt(bytes, 44)
        val vSize = readInt(bytes, 48)
        val payloadSize = readInt(bytes, 56)

        require(width > 0 && height > 0) { "invalid frame size ${width}x$height" }
        require(ySize >= 0 && uSize >= 0 && vSize >= 0) { "negative plane size" }
        require(ySize + uSize + vSize <= payloadSize) { "plane sizes exceed the payload" }
        require(HEADER + payloadSize <= bytes.size) {
            "truncated OpenH264 payload ($payloadSize bytes at $HEADER)"
        }

        val yStart = HEADER
        val uStart = yStart + ySize
        val vStart = uStart + uSize
        return OpenH264Frame(
            width = width,
            height = height,
            chromaFormat = chromaFormat,
            pts = pts,
            y = bytes.copyOfRange(yStart, uStart),
            u = bytes.copyOfRange(uStart, vStart),
            v = bytes.copyOfRange(vStart, vStart + vSize),
        )
    }

    private fun readInt(source: ByteArray, offset: Int): Int =
        (source[offset].toInt() and 0xFF) or
            ((source[offset + 1].toInt() and 0xFF) shl 8) or
            ((source[offset + 2].toInt() and 0xFF) shl 16) or
            ((source[offset + 3].toInt() and 0xFF) shl 24)

    private fun readLong(source: ByteArray, offset: Int): Long =
        (readInt(source, offset).toLong() and 0xFFFFFFFFL) or
            (readInt(source, offset + 4).toLong() shl 32)
}
