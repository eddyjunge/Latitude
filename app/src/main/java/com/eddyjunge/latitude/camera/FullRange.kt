package com.eddyjunge.latitude.camera

import android.system.Os
import android.util.Log
import java.io.FileDescriptor
import java.nio.ByteBuffer

private const val TAG = "FullRange"

/**
 * The Xiaomi 15 Ultra's ISP hands the encoder full-range YUV in the 8-bit Rec.709 session (our
 * curve's top 0.932 lands at code 238 of 255, dark scenes go down to 0), but MediaRecorder tags
 * every file as limited range. Resolve would then stretch 16–235 to 0–1 and add ~17 % contrast.
 *
 * Not for 10-bit: the HLG10 session (HLG and Log) delivers limited range like any BT.2100 video
 * (measured 2026-11-02: every viewfinder level v lands at code 64 + 876·v, Log's top at ~865),
 * which is what MediaRecorder already says.
 *
 * After recording, the two flags that say "limited" are flipped to "full" in place: the 'colr'
 * box of the video sample entry and video_full_range_flag in the HEVC SPS inside 'hvcC'. Both
 * live in 'moov', a few KB; sizes don't change, the picture data isn't touched.
 */
object FullRange {
    /** True if the file now says full range; false leaves it as it was. */
    fun mark(fd: FileDescriptor): Boolean {
        val moov = findTopLevel(fd, "moov") ?: return fail("no moov")
        val (offset, size) = moov
        if (size > MAX_MOOV) return fail("moov too large: $size")
        val buf = ByteArray(size.toInt())
        if (Os.pread(fd, buf, 0, buf.size, offset) != buf.size) return fail("short read")
        val entry = findSampleEntry(buf, 8, buf.size) ?: return fail("no HEVC sample entry")
        // Sample entry: 8 header + 78 bytes of visual fields, then its child boxes.
        val colr = findChild(buf, entry.first + 86, entry.second, "colr") ?: return fail("no colr")
        val hvcC = findChild(buf, entry.first + 86, entry.second, "hvcC") ?: return fail("no hvcC")
        val c = colr.first + 8
        if (String(buf, c, 4, Charsets.US_ASCII) != "nclx") return fail("colr is not nclx")
        val primaries = u16(buf, c + 4)
        val transfer = u16(buf, c + 6)
        val matrix = u16(buf, c + 8)
        val spsBit = findSpsRangeFlag(buf, hvcC.first + 8, hvcC.second, primaries, transfer, matrix)
            ?: return fail("range flag not found in SPS")
        buf[c + 10] = (buf[c + 10].toInt() or 0x80).toByte()
        buf[spsBit / 8] = (buf[spsBit / 8].toInt() or (0x80 ushr (spsBit % 8))).toByte()
        if (Os.pwrite(fd, buf, 0, buf.size, offset) != buf.size) return fail("short write")
        return true
    }

    private fun fail(why: String): Boolean {
        Log.w(TAG, "left as limited range: $why")
        return false
    }

    /** Offset and size of the first top-level box of [type]. */
    private fun findTopLevel(fd: FileDescriptor, type: String): Pair<Long, Long>? {
        val header = ByteArray(16)
        var offset = 0L
        val end = Os.fstat(fd).st_size
        while (offset + 8 <= end) {
            if (Os.pread(fd, header, 0, 16, offset) < 8) return null
            var size = u32(header, 0)
            if (size == 1L) size = ByteBuffer.wrap(header, 8, 8).long
            if (size == 0L) size = end - offset
            if (size < 8) return null
            if (String(header, 4, 4, Charsets.US_ASCII) == type) return offset to size
            offset += size
        }
        return null
    }

    /** Start and end (exclusive) of the 'hvc1'/'hev1' sample entry somewhere below [from]. */
    private fun findSampleEntry(buf: ByteArray, from: Int, to: Int): Pair<Int, Int>? {
        var off = from
        while (off + 8 <= to) {
            val size = u32(buf, off).toInt()
            if (size < 8 || off + size > to) return null
            when (String(buf, off + 4, 4, Charsets.US_ASCII)) {
                "hvc1", "hev1" -> return off to off + size
                "trak", "mdia", "minf", "stbl" -> findSampleEntry(buf, off + 8, off + size)?.let { return it }
                // Full box (version + flags) and an entry count before the entries.
                "stsd" -> findSampleEntry(buf, off + 16, off + size)?.let { return it }
            }
            off += size
        }
        return null
    }

    private fun findChild(buf: ByteArray, from: Int, to: Int, type: String): Pair<Int, Int>? {
        var off = from
        while (off + 8 <= to) {
            val size = u32(buf, off).toInt()
            if (size < 8 || off + size > to) return null
            if (String(buf, off + 4, 4, Charsets.US_ASCII) == type) return off to off + size
            off += size
        }
        return null
    }

    /**
     * Absolute bit position (in [buf]) of video_full_range_flag in the SPS inside the hvcC
     * payload [from]..[to]. Instead of parsing the whole SPS, the VUI is found by its unique
     * signature: signal type present (1), video format (3 bits), the flag, colour description
     * present (1), then the same primaries, transfer and matrix the colr box carries.
     * Only a single, unambiguous match counts.
     */
    private fun findSpsRangeFlag(buf: ByteArray, from: Int, to: Int, primaries: Int, transfer: Int, matrix: Int): Int? {
        // hvcC: 22 bytes of configuration, then arrays of NAL units.
        var off = from + 22
        val arrays = buf[off++].toInt() and 0xFF
        repeat(arrays) {
            if (off + 3 > to) return null
            val nalType = buf[off].toInt() and 0x3F
            val count = u16(buf, off + 1)
            off += 3
            repeat(count) {
                val length = u16(buf, off)
                val start = off + 2
                off = start + length
                if (off > to) return null
                if (nalType == SPS) return rangeFlagIn(buf, start, off, primaries, transfer, matrix)
            }
        }
        return null
    }

    private fun rangeFlagIn(buf: ByteArray, start: Int, end: Int, primaries: Int, transfer: Int, matrix: Int): Int? {
        // Payload without emulation prevention bytes, remembering where each byte came from.
        val rbsp = ArrayList<Int>(end - start)
        val origin = ArrayList<Int>(end - start)
        var zeros = 0
        for (i in start + 2 until end) { // skip the 2-byte NAL header
            val b = buf[i].toInt() and 0xFF
            if (zeros >= 2 && b == 3) {
                zeros = 0
                continue
            }
            zeros = if (b == 0) zeros + 1 else 0
            rbsp += b
            origin += i
        }
        fun bit(p: Int) = rbsp[p / 8] ushr (7 - p % 8) and 1
        fun bits(p: Int, n: Int): Int {
            var v = 0
            for (k in 0 until n) v = v shl 1 or bit(p + k)
            return v
        }
        val total = rbsp.size * 8
        val matches = (0..total - 30).filter { p ->
            bit(p) == 1 && bit(p + 5) == 1 &&
                bits(p + 6, 8) == primaries && bits(p + 14, 8) == transfer && bits(p + 22, 8) == matrix
        }
        val p = matches.singleOrNull() ?: return null
        val flag = p + 4
        return origin[flag / 8] * 8 + flag % 8
    }

    private fun u16(b: ByteArray, o: Int) = (b[o].toInt() and 0xFF shl 8) or (b[o + 1].toInt() and 0xFF)

    private fun u32(b: ByteArray, o: Int): Long =
        ((b[o].toLong() and 0xFF) shl 24) or ((b[o + 1].toLong() and 0xFF) shl 16) or
            ((b[o + 2].toLong() and 0xFF) shl 8) or (b[o + 3].toLong() and 0xFF)

    private const val SPS = 33
    private const val MAX_MOOV = 16L shl 20
}
