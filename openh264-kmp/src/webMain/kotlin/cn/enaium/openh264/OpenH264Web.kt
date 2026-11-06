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

/**
 * Web bridge shared by the Kotlin/JS and Kotlin/Wasm targets.
 *
 * The decoder is a stateful C library, so the JavaScript glue exposes one
 * function per C entry point and keeps the exported function table. The access
 * unit and the result blob cross the boundary as base64 strings, because those
 * are the two calls that carry bulk data.
 *
 * Pointers are 32-bit WebAssembly addresses and therefore `Int`s here.
 */

internal expect fun webInit()

internal expect fun webVersionPacked(): Int

internal expect fun webVersionString(): String

internal expect fun webApiBuild(): Int

internal expect fun webDecoderCreate(): Int

internal expect fun webDecoderDestroy(decoder: Int)

/** Returns the C status code. `pts` is in microseconds. */
internal expect fun webDecode(decoder: Int, payloadBase64: String, pts: Double): Int

internal expect fun webFlush(decoder: Int): Int

/** Returns `"<code>:<base64 blob>"`. */
internal expect fun webFrameBlob(decoder: Int): String

/** Splits the `"<code>:<base64>"` envelope the JavaScript glue returns. */
internal fun parseWebBlob(result: String, what: String): ByteArray? {
    val separator = result.indexOf(':')
    check(separator > 0) { "malformed OpenH264 web result during $what: $result" }
    val code = result.substring(0, separator).toInt()
    return when (code) {
        OPENH264_BUFFERED -> null
        OPENH264_OK -> {
            val encoded = result.substring(separator + 1)
            if (encoded.isEmpty()) {
                throw OpenH264Exception("OpenH264 reported a picture during $what but sent no payload")
            }
            Base64.decode(encoded)
        }
        else -> throw OpenH264Exception("OpenH264 failed during $what (code $code)")
    }
}
