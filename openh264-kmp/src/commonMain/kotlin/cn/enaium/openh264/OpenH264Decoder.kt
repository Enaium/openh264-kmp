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
 * A stateful H.264 decoder.
 *
 * The decoder is not thread-safe: call [decode], [flush] and [close] from one
 * thread at a time. Every call takes one Annex-B access unit (one or more NAL
 * units with start codes, the format MediaCodec and WebCodecs produce) and
 * returns the picture it completes, if any.
 *
 * ```kotlin
 * OpenH264Decoder.create().use { decoder ->
 *     for (accessUnit in accessUnits) {
 *         decoder.decode(accessUnit, pts)?.let { render(it) }
 *     }
 *     decoder.flush().forEach { render(it) }
 * }
 * ```
 */
class OpenH264Decoder private constructor(
    private val handle: OpenH264DecoderHandle,
) : AutoCloseable {
    private var closed = false

    /**
     * Decodes one Annex-B access unit.
     *
     * Returns the decoded picture, or `null` when the decoder needs more data
     * (for example a delta frame that arrived before its reference). `pts` is
     * reported back on the returned frame.
     */
    fun decode(accessUnit: ByteArray, pts: Long = 0L): OpenH264Frame? {
        checkOpen()
        require(accessUnit.isNotEmpty()) { "access unit must not be empty" }
        return when (val result = OpenH264Native.decode(handle, accessUnit, pts)) {
            OPENH264_OK -> frame()
            OPENH264_BUFFERED -> null
            else -> throw OpenH264Exception("OpenH264 failed to decode an access unit (code $result)")
        }
    }

    /**
     * Drains every picture still held in the decoder's reorder buffer. Call
     * once after the last access unit; the decoder can still decode afterwards
     * but a fresh stream should start with a key frame.
     */
    fun flush(): List<OpenH264Frame> {
        checkOpen()
        val frames = ArrayList<OpenH264Frame>()
        // FlushFrame releases at most one buffered picture per call. The cap
        // guards against a decoder that never reports an empty buffer.
        repeat(MAX_FLUSH_FRAMES) {
            when (val result = OpenH264Native.flush(handle)) {
                OPENH264_OK -> frames.add(frame())
                OPENH264_BUFFERED -> return frames
                else -> throw OpenH264Exception("OpenH264 failed to flush (code $result)")
            }
        }
        return frames
    }

    override fun close() {
        if (closed) return
        closed = true
        OpenH264Native.decoderDestroy(handle)
    }

    override fun toString(): String = "OpenH264Decoder"

    private fun frame(): OpenH264Frame {
        val blob = OpenH264Native.frameBlob(handle)
            ?: throw OpenH264Exception("OpenH264 produced a picture but no output buffer")
        return OpenH264Output.parse(blob)
    }

    private fun checkOpen() {
        check(!closed) { "OpenH264Decoder is closed" }
    }

    companion object {
        private const val MAX_FLUSH_FRAMES = 1 shl 16

        /** Creates and initialises a decoder. */
        fun create(): OpenH264Decoder {
            val handle = OpenH264Native.decoderCreate()
                ?: throw OpenH264Exception(
                    "OpenH264 failed to create a decoder; the library may be built without decoder support",
                )
            return OpenH264Decoder(handle)
        }
    }
}
