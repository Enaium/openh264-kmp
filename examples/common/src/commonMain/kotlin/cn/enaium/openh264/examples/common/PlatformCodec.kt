/*
 * Copyright (c) 2026 Enaium
 * SPDX-License-Identifier: GPL-2.0-or-later
 */

package cn.enaium.openh264.examples.common

import cn.enaium.camera.I420Frame

/** Codec families the examples can hand to a platform encoder/decoder. */
internal enum class PlatformCodecKind(val mime: String, val webCodecs: String) {
    HEVC("video/hevc", "hev1.1.6.L93.B0"),
    AVC("video/avc", "avc1.42001E"),
}

/** One Annex-B access unit produced by an encoder. */
internal class EncodedChunk(
    val data: ByteArray,
    val pts: Long,
    val keyFrame: Boolean,
)

/**
 * A decoder backed by the platform: MediaCodec on Android and WebCodecs in the
 * browser. Desktop targets have no platform decoder and use the codec binding
 * instead, so [createPlatformDecoder] returns null there.
 */
internal interface PlatformDecoder {
    fun decode(chunk: EncodedChunk): List<I420Frame>
    fun close()
}

/**
 * A camera encoder backed by the platform. Like [PlatformDecoder], desktop
 * targets return null and the example falls back to the published binding.
 */
internal interface PlatformEncoder {
    /** `0` while the async browser check is running, `1` ready, `-1` unsupported. */
    val status: Int get() = 1

    fun encode(frame: I420Frame): List<EncodedChunk>
    fun close()
}

internal expect fun createPlatformDecoder(
    kind: PlatformCodecKind,
    width: Int,
    height: Int,
    csd: ByteArray,
): PlatformDecoder?

internal expect fun createPlatformEncoder(
    kind: PlatformCodecKind,
    width: Int,
    height: Int,
): PlatformEncoder?

/**
 * Converts MediaCodec's codec-specific data (`avcC`/`hvcC`) and length-prefixed
 * access units to the Annex-B form the bindings and WebCodecs consume.
 */
internal class AnnexBConfig(val prefix: ByteArray, val lengthSize: Int) {
    companion object {
        private val START_CODE = byteArrayOf(0, 0, 0, 1)

        fun parse(csd: ByteArray): AnnexBConfig {
            if (csd.size >= 4 && csd[0] == 0.toByte() && csd[1] == 0.toByte() &&
                csd[2] == 0.toByte() && csd[3] == 1.toByte()
            ) {
                return AnnexBConfig(csd, 4)
            }
            return if (csd.size > 4 && (csd[0].toInt() and 0xFF) == 1) parseAvc(csd) else parseHevc(csd)
        }

        fun toAnnexB(
            data: ByteArray,
            lengthSize: Int,
            prefix: ByteArray?,
            prepend: Boolean,
        ): ByteArray {
            if (data.size >= 4 && data[0] == 0.toByte() && data[1] == 0.toByte() &&
                data[2] == 0.toByte() && data[3] == 1.toByte()
            ) {
                return if (prepend && prefix != null) prefix + data else data
            }
            val units = ArrayList<ByteArray>()
            var offset = 0
            while (offset + lengthSize <= data.size) {
                var length = 0
                for (i in 0 until lengthSize) {
                    length = (length shl 8) or (data[offset + i].toInt() and 0xFF)
                }
                offset += lengthSize
                if (length <= 0 || offset + length > data.size) break
                units += data.copyOfRange(offset, offset + length)
                offset += length
            }
            if (units.isEmpty()) return ByteArray(0)
            val prefixBytes = if (prepend) prefix else null
            val total = units.sumOf { it.size + 4 } + (prefixBytes?.size ?: 0)
            val out = ByteArray(total)
            var at = 0
            if (prefixBytes != null) {
                prefixBytes.copyInto(out, at)
                at += prefixBytes.size
            }
            for (unit in units) {
                START_CODE.copyInto(out, at)
                at += 4
                unit.copyInto(out, at)
                at += unit.size
            }
            return out
        }

        private fun parseHevc(csd: ByteArray): AnnexBConfig {
            require(csd.size > 23) { "hvcC record is only ${csd.size} bytes" }
            val lengthSize = (csd[21].toInt() and 0x03) + 1
            var position = 22
            val arrays = csd[position].toInt() and 0xFF
            position++
            val units = ArrayList<ByteArray>()
            repeat(arrays) {
                val nalType = csd[position].toInt() and 0x3F
                val count = readUInt16(csd, position + 1)
                position += 3
                repeat(count) {
                    val size = readUInt16(csd, position)
                    position += 2
                    require(position + size <= csd.size) { "truncated hvcC NAL" }
                    if (nalType in 32..34) units += csd.copyOfRange(position, position + size)
                    position += size
                }
            }
            return AnnexBConfig(annexB(units), lengthSize)
        }

        private fun parseAvc(csd: ByteArray): AnnexBConfig {
            require(csd.size >= 7) { "avcC record is only ${csd.size} bytes" }
            val lengthSize = (csd[4].toInt() and 0x03) + 1
            var position = 5
            val spsCount = csd[position].toInt() and 0x1F
            position++
            val units = ArrayList<ByteArray>()
            repeat(spsCount) {
                val size = readUInt16(csd, position)
                position += 2
                units += csd.copyOfRange(position, position + size)
                position += size
            }
            val ppsCount = csd[position].toInt() and 0xFF
            position++
            repeat(ppsCount) {
                val size = readUInt16(csd, position)
                position += 2
                units += csd.copyOfRange(position, position + size)
                position += size
            }
            return AnnexBConfig(annexB(units), lengthSize)
        }

        private fun annexB(units: List<ByteArray>): ByteArray {
            val out = ByteArray(units.sumOf { it.size + 4 })
            var at = 0
            for (unit in units) {
                START_CODE.copyInto(out, at)
                at += 4
                unit.copyInto(out, at)
                at += unit.size
            }
            return out
        }

        private fun readUInt16(data: ByteArray, offset: Int): Int =
            ((data[offset].toInt() and 0xFF) shl 8) or (data[offset + 1].toInt() and 0xFF)
    }
}

internal expect val platformDecoderName: String
internal expect val platformEncoderName: String
