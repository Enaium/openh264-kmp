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

// =========================================================================
// JNI bridge: declarations are shared by the JVM and Android targets.
// =========================================================================

internal object JniOpenH264 {
    init {
        NativeLoader.load()
    }

    external fun nativeVersionPacked(): Int

    external fun nativeVersionString(): String

    external fun nativeApiBuild(): Int

    external fun nativeDecoderCreate(): Long

    external fun nativeDecoderDestroy(handle: Long)

    external fun nativeDecode(handle: Long, accessUnit: ByteArray, pts: Long): Int

    external fun nativeFlush(handle: Long): Int

    external fun nativeFrameBlob(handle: Long): ByteArray?
}

internal actual class OpenH264DecoderHandle actual constructor() {
    internal var pointer: Long = 0
}

internal actual object OpenH264Native {
    actual fun versionPacked(): Int = JniOpenH264.nativeVersionPacked()

    actual fun versionString(): String = JniOpenH264.nativeVersionString()

    actual fun apiBuild(): Int = JniOpenH264.nativeApiBuild()

    actual fun decoderCreate(): OpenH264DecoderHandle? {
        val pointer = JniOpenH264.nativeDecoderCreate()
        if (pointer == 0L) return null
        return OpenH264DecoderHandle().also { it.pointer = pointer }
    }

    actual fun decoderDestroy(handle: OpenH264DecoderHandle) {
        JniOpenH264.nativeDecoderDestroy(handle.pointer)
        handle.pointer = 0
    }

    actual fun decode(handle: OpenH264DecoderHandle, accessUnit: ByteArray, pts: Long): Int =
        JniOpenH264.nativeDecode(handle.pointer, accessUnit, pts)

    actual fun flush(handle: OpenH264DecoderHandle): Int =
        JniOpenH264.nativeFlush(handle.pointer)

    actual fun frameBlob(handle: OpenH264DecoderHandle): ByteArray? =
        JniOpenH264.nativeFrameBlob(handle.pointer)
}
