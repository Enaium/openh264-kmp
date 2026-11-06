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

import kotlin.js.js

// The glue is instantiated lazily: decoding and compiling the embedded module
// is the only expensive step, and it should not run for a process that never
// calls the decoder.
private val openh264WebInitialized: Unit by lazy {
    js(OPENH264_WEB_INIT_JS)(OPENH264_WASM_BASE64)
}

internal actual fun webInit() {
    openh264WebInitialized
}

internal actual fun webVersionPacked(): Int =
    js("() => globalThis.__openh264Web.versionPacked()")() as Int

internal actual fun webVersionString(): String =
    js("() => globalThis.__openh264Web.versionString()")() as String

internal actual fun webApiBuild(): Int =
    js("() => globalThis.__openh264Web.apiBuild()")() as Int

internal actual fun webDecoderCreate(): Int =
    js("() => globalThis.__openh264Web.decoderCreate()")() as Int

internal actual fun webDecoderDestroy(decoder: Int) {
    js("(d) => globalThis.__openh264Web.decoderDestroy(d)")(decoder)
}

internal actual fun webDecode(decoder: Int, payloadBase64: String, pts: Double): Int =
    js("(d, p, t) => globalThis.__openh264Web.decode(d, p, t)")(decoder, payloadBase64, pts) as Int

internal actual fun webFlush(decoder: Int): Int =
    js("(d) => globalThis.__openh264Web.flush(d)")(decoder) as Int

internal actual fun webFrameBlob(decoder: Int): String =
    js("(d) => globalThis.__openh264Web.frameBlob(d)")(decoder) as String
