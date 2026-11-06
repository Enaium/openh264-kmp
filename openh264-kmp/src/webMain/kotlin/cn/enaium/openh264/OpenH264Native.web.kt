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

@file:OptIn(kotlin.io.encoding.ExperimentalEncodingApi::class)

package cn.enaium.openh264

import kotlin.io.encoding.Base64

internal actual class OpenH264DecoderHandle actual constructor() {
    /** WebAssembly pointer, always 32-bit. */
    internal var pointer: Int = 0
}

internal actual object OpenH264Native {
    private var initialised = false

    private fun ensureInitialised() {
        if (!initialised) {
            webInit()
            initialised = true
        }
    }

    actual fun versionPacked(): Int {
        ensureInitialised()
        return webVersionPacked()
    }

    actual fun versionString(): String {
        ensureInitialised()
        return webVersionString()
    }

    actual fun apiBuild(): Int {
        ensureInitialised()
        return webApiBuild()
    }

    actual fun decoderCreate(): OpenH264DecoderHandle? {
        ensureInitialised()
        val pointer = webDecoderCreate()
        if (pointer == 0) return null
        return OpenH264DecoderHandle().also { it.pointer = pointer }
    }

    actual fun decoderDestroy(handle: OpenH264DecoderHandle) {
        ensureInitialised()
        webDecoderDestroy(handle.pointer)
        handle.pointer = 0
    }

    actual fun decode(handle: OpenH264DecoderHandle, accessUnit: ByteArray, pts: Long): Int {
        ensureInitialised()
        if (accessUnit.isEmpty()) return OPENH264_BUFFERED
        val payload = Base64.encode(accessUnit)
        return webDecode(handle.pointer, payload, pts.toDouble())
    }

    actual fun flush(handle: OpenH264DecoderHandle): Int {
        ensureInitialised()
        return webFlush(handle.pointer)
    }

    actual fun frameBlob(handle: OpenH264DecoderHandle): ByteArray? {
        ensureInitialised()
        return parseWebBlob(webFrameBlob(handle.pointer), "frame")
    }
}
