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

@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package cn.enaium.openh264

import cnames.structs.ok_decoder
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.IntVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.readBytes
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.toKString
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import openh264_kmp.ok_api_build
import openh264_kmp.ok_decoder_create
import openh264_kmp.ok_decoder_decode
import openh264_kmp.ok_decoder_destroy
import openh264_kmp.ok_decoder_flush
import openh264_kmp.ok_decoder_frame_blob
import openh264_kmp.ok_version_packed
import openh264_kmp.ok_version_string

internal actual class OpenH264DecoderHandle actual constructor() {
    internal var pointer: CPointer<ok_decoder>? = null
}

internal actual object OpenH264Native {
    actual fun versionPacked(): Int = ok_version_packed()

    actual fun versionString(): String = ok_version_string()?.toKString() ?: ""

    actual fun apiBuild(): Int = ok_api_build()

    actual fun decoderCreate(): OpenH264DecoderHandle? {
        val pointer = ok_decoder_create() ?: return null
        return OpenH264DecoderHandle().also { it.pointer = pointer }
    }

    actual fun decoderDestroy(handle: OpenH264DecoderHandle) {
        ok_decoder_destroy(handle.pointer)
        handle.pointer = null
    }

    actual fun decode(handle: OpenH264DecoderHandle, accessUnit: ByteArray, pts: Long): Int {
        val pointer = handle.pointer ?: throw OpenH264Exception("decoder is closed")
        if (accessUnit.isEmpty()) return OPENH264_BUFFERED
        return accessUnit.usePinned { pinned ->
            ok_decoder_decode(pointer, pinned.addressOf(0).reinterpret(), accessUnit.size, pts)
        }
    }

    actual fun flush(handle: OpenH264DecoderHandle): Int {
        val pointer = handle.pointer ?: throw OpenH264Exception("decoder is closed")
        return ok_decoder_flush(pointer)
    }

    actual fun frameBlob(handle: OpenH264DecoderHandle): ByteArray? {
        val pointer = handle.pointer ?: throw OpenH264Exception("decoder is closed")
        return memScoped {
            val size = alloc<IntVar>()
            val blob = ok_decoder_frame_blob(pointer, size.ptr)
            if (blob == null || size.value <= 0) {
                null
            } else {
                blob.reinterpret<kotlinx.cinterop.ByteVar>().readBytes(size.value)
            }
        }
    }
}
