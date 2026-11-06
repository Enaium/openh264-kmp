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
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OpenH264Test {
    private val width = 64
    private val height = 48

    @Test
    fun versionMatchesThePinnedRelease() {
        assertEquals("2.7.0.1", OpenH264.VERSION)
        assertEquals("2.7.0", OpenH264.libraryVersion)
        assertEquals(20700, OpenH264.libraryVersionPacked)
        assertEquals(20700, OpenH264.apiBuild)
        assertEquals(8, OpenH264.BIT_DEPTH)
    }

    @Test
    fun decodesARealAnnexBStream() {
        val accessUnits = anneBUnits(Base64.decode(TEST_STREAM))
        assertEquals(6, accessUnits.size, "the test stream should contain six access units")

        val frames = ArrayList<OpenH264Frame>()
        OpenH264Decoder.create().use { decoder ->
            accessUnits.forEachIndexed { index, unit ->
                val pts = index * 100_000L
                val frame = decoder.decode(unit, pts)
                if (frame != null) {
                    assertEquals(pts, frame.pts)
                    frames.add(frame)
                }
            }
            frames.addAll(decoder.flush())
        }

        assertEquals(6, frames.size, "every access unit should produce one picture")
        frames.forEach { frame ->
            assertEquals(width, frame.width)
            assertEquals(height, frame.height)
            assertEquals(OpenH264ChromaFormat.I420, frame.chromaFormat)
            assertEquals(width / 2, frame.chromaWidth)
            assertEquals(height / 2, frame.chromaHeight)
            assertEquals(width * height, frame.y.size)
            assertEquals(frame.chromaWidth * frame.chromaHeight, frame.u.size)
            assertEquals(frame.chromaWidth * frame.chromaHeight, frame.v.size)
            assertTrue(frame.y.any { it.toInt() != 0 }, "luma plane is blank")
            assertEquals(frame.byteSize, frame.toI420().size)
        }
        // The generated source is not a flat colour, so two frames differ.
        assertTrue(!frames.first().y.contentEquals(frames.last().y) || !frames.first().u.contentEquals(frames.last().u))
    }

    @Test
    fun decodesASingleKeyFrame() {
        // SPS, PPS, SEI and the IDR slice make up the first access unit that
        // produces a picture; drop the leading AUD to exercise NAL parsing.
        val units = anneBUnits(Base64.decode(TEST_STREAM))
        val firstPicture = units.first { unit -> unit.any { (it.toInt() and 0x1F) == 5 } }
        OpenH264Decoder.create().use { decoder ->
            val frame = assertNotNull(decoder.decode(firstPicture, pts = 7))
            assertEquals(7, frame.pts)
            assertEquals(width, frame.width)
            assertEquals(height, frame.height)
        }
    }

    @Test
    fun flushOnAFreshDecoderProducesNoFrames() {
        OpenH264Decoder.create().use { decoder ->
            assertEquals(0, decoder.flush().size)
        }
    }

    @Test
    fun rejectsEmptyAccessUnits() {
        OpenH264Decoder.create().use { decoder ->
            assertFailsWith<IllegalArgumentException> { decoder.decode(ByteArray(0)) }
        }
    }

    @Test
    fun closedDecoderRejectsCalls() {
        val decoder = OpenH264Decoder.create()
        decoder.close()
        assertFailsWith<IllegalStateException> { decoder.flush() }
        assertNull(runCatching { decoder.decode(byteArrayOf(0, 0, 0, 1, 9, 0x10)) }.getOrNull())
    }

    /** Splits an Annex-B stream at every access unit delimiter (NAL type 9). */
    private fun anneBUnits(stream: ByteArray): List<ByteArray> {
        val starts = ArrayList<Int>()
        var index = 0
        while (index + 4 < stream.size) {
            val startCode = startCodeLength(stream, index)
            if (startCode > 0 && (stream[index + startCode].toInt() and 0x1F) == 9) {
                starts.add(index)
                index += startCode
            } else {
                index++
            }
        }
        if (starts.isEmpty()) return listOf(stream)
        return starts.mapIndexed { position, start ->
            val end = if (position + 1 < starts.size) starts[position + 1] else stream.size
            stream.copyOfRange(start, end)
        }
    }

    private fun startCodeLength(stream: ByteArray, index: Int): Int = when {
        stream[index].toInt() == 0 && stream[index + 1].toInt() == 0 &&
            stream[index + 2].toInt() == 0 && stream[index + 3].toInt() == 1 -> 4
        stream[index].toInt() == 0 && stream[index + 1].toInt() == 0 &&
            stream[index + 2].toInt() == 1 -> 3
        else -> 0
    }

    private companion object {
        /**
         * Six baseline-profile access units of a 64x48 testsrc2 pattern,
         * encoded with ffmpeg/libx264:
         *
         * ffmpeg -f lavfi -i testsrc2=size=64x48:rate=10 -frames:v 6 \
         *   -c:v libx264 -profile:v baseline -preset ultrafast -bf 0 \
         *   -x264-params aud=1:cabac=0:8x8dct=0:keyint=6:min-keyint=6:scenecut=0 \
         *   -f h264 test.h264
         */
        val TEST_STREAM: String =
        "AAAAAQkQAAAAAWdCwAraEewEQAAAAwBAAAAFA8SJqAAAAAFozg/IAAABBgX//03cRem95tlIt5Ys2CDZI+7veDI2NCAtIGNv" +
        "cmUgMTY1IHIzMjIyIGIzNTYwNWEgLSBILjI2NC9NUEVHLTQgQVZDIGNvZGVjIC0gQ29weWxlZnQgMjAwMy0yMDI1IC0gaHR0" +
        "cDovL3d3dy52aWRlb2xhbi5vcmcveDI2NC5odG1sIC0gb3B0aW9uczogY2FiYWM9MCByZWY9MSBkZWJsb2NrPTA6MDowIGFu" +
        "YWx5c2U9MDowIG1lPWRpYSBzdWJtZT0wIHBzeT0xIHBzeV9yZD0xLjAwOjAuMDAgbWl4ZWRfcmVmPTAgbWVfcmFuZ2U9MTYg" +
        "Y2hyb21hX21lPTEgdHJlbGxpcz0wIDh4OGRjdD0wIGNxbT0wIGRlYWR6b25lPTIxLDExIGZhc3RfcHNraXA9MSBjaHJvbWFf" +
        "cXBfb2Zmc2V0PTAgdGhyZWFkcz0xIGxvb2thaGVhZF90aHJlYWRzPTEgc2xpY2VkX3RocmVhZHM9MCBucj0wIGRlY2ltYXRl" +
        "PTEgaW50ZXJsYWNlZD0wIGJsdXJheV9jb21wYXQ9MCBjb25zdHJhaW5lZF9pbnRyYT0wIGJmcmFtZXM9MCB3ZWlnaHRwPTAg" +
        "a2V5aW50PTYga2V5aW50X21pbj00IHNjZW5lY3V0PTAgaW50cmFfcmVmcmVzaD0wIHJjPWNyZiBtYnRyZWU9MCBjcmY9MjMu" +
        "MCBxY29tcD0wLjYwIHFwbWluPTAgcXBtYXg9NjkgcXBzdGVwPTQgaXBfcmF0aW89MS40MCBhcT0wAIAAAAFliIQ6DGABbEIR" +
        "5QUbjiYytAf7wArTYMxjbUICBDIgIEYADkZeFNUQZhKAlF/+HAQEMjgAEwACGdGCTBTFPM0lIT5/+0QXopKGFU1A3r/8OAgI" +
        "xRkEqDhKCnznq8OAgIwDwACqBSHq0kDPX75REGiDhCCvzvq9Tglwo3iHSnq9IAAVUIQ5WEgb+/fJAACeHY1FtBnL98CAAEAY" +
        "AAwHGQkAAQBQQAIAA3YyjRKlF8McW7G4tkAH2LGJH6xgWJ7//RwACVBiKmEaQAZgFc0JMs48b4JZGk0FayIWT1WwAINJn/+1" +
        "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"
    }
}
