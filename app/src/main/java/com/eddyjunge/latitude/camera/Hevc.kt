package com.eddyjunge.latitude.camera

import android.util.Log
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer

/** HEVC bitstream helpers: MediaCodec speaks Annex-B (start codes), MP4 wants length prefixes. */
object Hevc {
    private const val NAL_VPS = 32
    private const val NAL_SPS = 33
    private const val NAL_PPS = 34

    /** NAL units (without start codes) in an Annex-B buffer. */
    fun splitAnnexB(data: ByteArray, length: Int = data.size): List<ByteArray> {
        val units = mutableListOf<ByteArray>()
        var start = -1
        var i = 0
        while (i + 2 < length) {
            if (data[i].toInt() == 0 && data[i + 1].toInt() == 0 && data[i + 2].toInt() == 1) {
                if (start >= 0) units += data.copyOfRange(start, trimZeros(data, start, i))
                i += 3
                start = i
            } else {
                i++
            }
        }
        if (start >= 0 && start < length) units += data.copyOfRange(start, length)
        return units
    }

    /** End of a NAL that is followed by a start code: trailing zero bytes belong to the start code. */
    private fun trimZeros(data: ByteArray, start: Int, end: Int): Int {
        var e = end
        while (e > start && data[e - 1].toInt() == 0) e--
        return e
    }

    /**
     * Rewrites one Annex-B access unit into [out] as 4-byte length-prefixed NAL units and returns
     * [out] (grown if needed), flipped and ready to write.
     */
    fun toLengthPrefixed(src: ByteBuffer, scratch: ByteArray, out: ByteBuffer?): Pair<ByteBuffer, ByteArray> {
        val length = src.remaining()
        val bytes = if (scratch.size >= length) scratch else ByteArray(length + length / 4)
        src.get(bytes, 0, length)
        var dst = out?.takeIf { it.capacity() >= length + 64 } ?: ByteBuffer.allocateDirect(length + length / 4 + 64)
        dst.clear()
        var start = -1
        var i = 0
        while (i + 2 < length) {
            if (bytes[i].toInt() == 0 && bytes[i + 1].toInt() == 0 && bytes[i + 2].toInt() == 1) {
                if (start >= 0) dst = putUnit(dst, bytes, start, trimZeros(bytes, start, i))
                i += 3
                start = i
            } else {
                i++
            }
        }
        if (start >= 0 && start < length) dst = putUnit(dst, bytes, start, length)
        dst.flip()
        return dst to bytes
    }

    private fun putUnit(dst: ByteBuffer, bytes: ByteArray, start: Int, end: Int): ByteBuffer {
        val n = end - start
        var target = dst
        if (target.remaining() < n + 4) {
            val bigger = ByteBuffer.allocateDirect((target.capacity() + n + 4) * 2)
            target.flip()
            bigger.put(target)
            target = bigger
        }
        target.putInt(n)
        target.put(bytes, start, n)
        return target
    }

    /**
     * 'hvcC' decoder configuration record (ISO/IEC 14496-15) from the encoder's codec-config
     * buffer, which holds VPS, SPS and PPS in Annex-B form. [vui] corrects the SPS's color
     * description, which the encoder fills from its own idea of the input rather than the data.
     */
    fun configurationRecord(codecConfig: ByteArray, vui: VuiFix? = null): ByteArray {
        val units = splitAnnexB(codecConfig)
        val vps = units.filter { type(it) == NAL_VPS }
        val sps = units.filter { type(it) == NAL_SPS }.map { if (vui != null) patchVui(it, vui) else it }
        val pps = units.filter { type(it) == NAL_PPS }
        require(sps.isNotEmpty() && pps.isNotEmpty()) { "no SPS/PPS in codec config" }
        val info = SpsInfo.parse(sps.first())
        val out = ByteArrayOutputStream()
        out.write(1) // configurationVersion
        out.write(info.profileTierLevel, 0, 12) // profile space .. level, straight from the SPS
        out.write(0xF0); out.write(0x00) // min_spatial_segmentation_idc 0
        out.write(0xFC) // parallelismType 0
        out.write(0xFC or info.chromaFormat)
        out.write(0xF8 or info.bitDepthLumaMinus8)
        out.write(0xF8 or info.bitDepthChromaMinus8)
        out.write(0); out.write(0) // avgFrameRate unknown
        // constantFrameRate 0, numTemporalLayers, temporalIdNested, lengthSizeMinusOne 3
        out.write(((info.maxSubLayersMinus1 + 1) shl 3) or (info.temporalIdNesting shl 2) or 3)
        val arrays = listOf(NAL_VPS to vps, NAL_SPS to sps, NAL_PPS to pps).filter { it.second.isNotEmpty() }
        out.write(arrays.size)
        for ((type, list) in arrays) {
            out.write(0x80 or type) // array_completeness 1
            out.write(list.size ushr 8); out.write(list.size and 0xFF)
            for (unit in list) {
                out.write(unit.size ushr 8); out.write(unit.size and 0xFF)
                out.write(unit)
            }
        }
        return out.toByteArray()
    }

    private fun type(unit: ByteArray) = (unit[0].toInt() shr 1) and 0x3F

    /** What the SPS's video signal type should say. [transfer] null keeps the encoder's. */
    class VuiFix(val fullRange: Boolean, val transfer: Int?)

    /**
     * Rewrites video_full_range_flag (and transfer_characteristics) in an SPS. The fields are
     * found by their signature: video_signal_type_present 1, video_format, full range flag,
     * colour_description_present 1, then primaries, transfer and matrix as the encoder wrote
     * them (BT.2020/HLG or BT.709). Returns the SPS unchanged if that isn't exactly one match.
     */
    private fun patchVui(sps: ByteArray, fix: VuiFix): ByteArray {
        val rbsp = unescape(sps, 2)
        val bits = rbsp.size * 8
        fun bit(p: Int) = (rbsp[p ushr 3].toInt() shr (7 - (p and 7))) and 1
        fun read(p: Int, n: Int): Int {
            var v = 0
            for (i in 0 until n) v = (v shl 1) or bit(p + i)
            return v
        }
        val known = setOf(Triple(9, 18, 9), Triple(1, 1, 1), Triple(9, 2, 9))
        val matches = (0 until bits - 30).filter { p ->
            bit(p) == 1 && bit(p + 5) == 1 && Triple(read(p + 6, 8), read(p + 14, 8), read(p + 22, 8)) in known
        }
        if (matches.size != 1) {
            Log.w("Hevc", "VUI color description not found (${matches.size} candidates), SPS left as is")
            return sps
        }
        val p = matches[0]
        fun write(at: Int, n: Int, value: Int) {
            for (i in 0 until n) {
                val q = at + i
                val mask = 1 shl (7 - (q and 7))
                val idx = q ushr 3
                rbsp[idx] = if ((value shr (n - 1 - i)) and 1 == 1) {
                    (rbsp[idx].toInt() or mask).toByte()
                } else {
                    (rbsp[idx].toInt() and mask.inv()).toByte()
                }
            }
        }
        write(p + 4, 1, if (fix.fullRange) 1 else 0)
        fix.transfer?.let { write(p + 14, 8, it) }
        return sps.copyOfRange(0, 2) + escape(rbsp)
    }

    /** Drops emulation prevention bytes (00 00 03) from [data] starting at [from]. */
    private fun unescape(data: ByteArray, from: Int): ByteArray {
        val out = ByteArrayOutputStream(data.size)
        var zeros = 0
        for (i in from until data.size) {
            val b = data[i].toInt() and 0xFF
            if (zeros >= 2 && b == 3) {
                zeros = 0
                continue
            }
            zeros = if (b == 0) zeros + 1 else 0
            out.write(b)
        }
        return out.toByteArray()
    }

    /** Inserts emulation prevention bytes where two zeros precede a byte of 0–3. */
    private fun escape(rbsp: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(rbsp.size + 8)
        var zeros = 0
        for (byte in rbsp) {
            val b = byte.toInt() and 0xFF
            if (zeros >= 2 && b <= 3) {
                out.write(3)
                zeros = 0
            }
            out.write(b)
            zeros = if (b == 0) zeros + 1 else 0
        }
        return out.toByteArray()
    }

    private class SpsInfo(
        val profileTierLevel: ByteArray,
        val maxSubLayersMinus1: Int,
        val temporalIdNesting: Int,
        val chromaFormat: Int,
        val bitDepthLumaMinus8: Int,
        val bitDepthChromaMinus8: Int,
    ) {
        companion object {
            fun parse(sps: ByteArray): SpsInfo {
                val rbsp = unescape(sps, 2) // skip the 2-byte NAL header
                val r = BitReader(rbsp)
                r.bits(4) // sps_video_parameter_set_id
                val maxSubLayersMinus1 = r.bits(3)
                val nesting = r.bits(1)
                // general profile_tier_level is byte aligned here: 12 bytes.
                val ptl = rbsp.copyOfRange(1, 13)
                r.skip(96)
                val subProfile = BooleanArray(maxSubLayersMinus1)
                val subLevel = BooleanArray(maxSubLayersMinus1)
                for (i in 0 until maxSubLayersMinus1) {
                    subProfile[i] = r.bits(1) == 1
                    subLevel[i] = r.bits(1) == 1
                }
                if (maxSubLayersMinus1 > 0) r.skip(2 * (8 - maxSubLayersMinus1))
                for (i in 0 until maxSubLayersMinus1) {
                    if (subProfile[i]) r.skip(88)
                    if (subLevel[i]) r.skip(8)
                }
                r.ue() // sps_seq_parameter_set_id
                val chroma = r.ue()
                if (chroma == 3) r.bits(1)
                r.ue(); r.ue() // width, height
                if (r.bits(1) == 1) repeat(4) { r.ue() } // conformance window
                val lumaMinus8 = r.ue()
                val chromaMinus8 = r.ue()
                return SpsInfo(ptl, maxSubLayersMinus1, nesting, chroma, lumaMinus8, chromaMinus8)
            }

        }
    }

    private class BitReader(private val data: ByteArray) {
        private var pos = 0

        fun bits(n: Int): Int {
            var v = 0
            repeat(n) {
                val byte = data[pos ushr 3].toInt()
                v = (v shl 1) or ((byte shr (7 - (pos and 7))) and 1)
                pos++
            }
            return v
        }

        fun skip(n: Int) {
            pos += n
        }

        fun ue(): Int {
            var zeros = 0
            while (bits(1) == 0) zeros++
            return (1 shl zeros) - 1 + if (zeros > 0) bits(zeros) else 0
        }
    }
}
