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

@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package cn.enaium.openh264

import kotlin.JsFun

private var openh264WebInitialized = false

internal actual fun webInit() {
    if (!openh264WebInitialized) {
        initOpenH264Web(OPENH264_WASM_BASE64)
        openh264WebInitialized = true
    }
}

@JsFun(OPENH264_WEB_INIT_JS)
private external fun initOpenH264Web(wasmBase64: String)

@JsFun("() => globalThis.__openh264Web.versionPacked()")
private external fun versionPacked(): Int

@JsFun("() => globalThis.__openh264Web.versionString()")
private external fun versionString(): String

@JsFun("() => globalThis.__openh264Web.apiBuild()")
private external fun apiBuild(): Int

@JsFun("() => globalThis.__openh264Web.decoderCreate()")
private external fun decoderCreate(): Int

@JsFun("(d) => globalThis.__openh264Web.decoderDestroy(d)")
private external fun decoderDestroy(decoder: Int)

@JsFun("(d, p, t) => globalThis.__openh264Web.decode(d, p, t)")
private external fun decode(decoder: Int, payload: String, pts: Double): Int

@JsFun("(d) => globalThis.__openh264Web.flush(d)")
private external fun flush(decoder: Int): Int

@JsFun("(d) => globalThis.__openh264Web.frameBlob(d)")
private external fun frameBlob(decoder: Int): String

internal actual fun webVersionPacked(): Int = versionPacked()

internal actual fun webVersionString(): String = versionString()

internal actual fun webApiBuild(): Int = apiBuild()

internal actual fun webDecoderCreate(): Int = decoderCreate()

internal actual fun webDecoderDestroy(decoder: Int) = decoderDestroy(decoder)

internal actual fun webDecode(decoder: Int, payloadBase64: String, pts: Double): Int =
    decode(decoder, payloadBase64, pts)

internal actual fun webFlush(decoder: Int): Int = flush(decoder)

internal actual fun webFrameBlob(decoder: Int): String = frameBlob(decoder)
