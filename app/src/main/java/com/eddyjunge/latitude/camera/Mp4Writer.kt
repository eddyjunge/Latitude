package com.eddyjunge.latitude.camera

import java.io.FileDescriptor
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel

/** Color description written to the 'colr' box (ITU-T H.273 code points). */
data class ColorTag(val primaries: Int, val transfer: Int, val matrix: Int, val fullRange: Boolean)

/** Frame n is stored at n · [frameTicks] / [timescale] seconds, e.g. 1001/30000 for 29.97 fps. */
class VideoTrackFormat(
    val width: Int,
    val height: Int,
    val timescale: Long,
    val frameTicks: Long,
    val rotation: Int,
    val color: ColorTag,
    val hvcC: ByteArray,
)

/**
 * Start timecode: frame [frame] counted at [framesPerSecond] (non-drop), e.g. the time of day
 * the first frame was exposed.
 */
class Timecode(val frame: Long, val framesPerSecond: Int)

/** Little-endian integer PCM, interleaved. */
class AudioTrackFormat(val sampleRate: Int, val channels: Int, val bitsPerSample: Int) {
    val bytesPerFrame: Int get() = channels * bitsPerSample / 8
}

/**
 * MP4 writer for one HEVC track and an optional uncompressed PCM track, built for what
 * DaVinci Resolve needs and MediaRecorder can't give:
 * - constant frame rate: every frame lasts exactly one frame period (a single 'stts' entry);
 * - exact color tags and QuickTime metadata keys, e.g. the one that makes Resolve pick Apple Log;
 * - 24-bit PCM as 'ipcm'. Resolve on Linux decodes no AAC at all;
 * - a start timecode ('tmcd' track, laid out like FFmpeg writes it), which Resolve reads from MP4.
 *
 * Crash safe: 'moov' sits in space reserved right after 'ftyp' and is rewritten with the size of
 * 'mdat' at every [checkpoint], so a take cut short by a crash still opens up to that point.
 * Takes too long for the reserved space get their final 'moov' at the end instead.
 *
 * Not thread safe; one thread writes.
 */
class Mp4Writer(
    fd: FileDescriptor,
    private val video: VideoTrackFormat,
    private val audio: AudioTrackFormat?,
    private val metadata: List<Pair<String, String>>,
    private val timecode: Timecode? = null,
) {
    private val channel: FileChannel = FileOutputStream(fd).channel
    private val creationTime = System.currentTimeMillis() / 1000 + SECONDS_1904_TO_1970

    private val ftypEnd: Long
    private val mdatStart: Long
    private var pos: Long

    private val videoSizes = IntList()
    private val videoOffsets = LongList()
    private val syncSamples = IntList()
    private val audioOffsets = LongList()
    private val audioChunkFrames = IntList()
    private var audioFrames = 0L
    private var timecodeOffset = 0L

    private val audioTrackId = 2
    private val timecodeTrackId = if (audio != null) 3 else 2

    /** The moov outgrew the reserved space; the final one goes to the end of the file. */
    private var overflowed = false

    init {
        channel.truncate(0)
        val ftyp = BoxWriter().apply {
            begin("ftyp")
            fourcc("isom"); u32(0x200)
            fourcc("isom"); fourcc("iso2"); fourcc("mp41")
            end()
        }.toBuffer()
        write(ftyp, 0)
        ftypEnd = ftyp.capacity().toLong()
        mdatStart = ftypEnd + RESERVED_BYTES
        writeFree(ftypEnd, RESERVED_BYTES)
        pos = mdatStart + MDAT_HEADER
        if (timecode != null) {
            // The one timecode sample: the frame number the take starts at.
            timecodeOffset = pos
            write(ByteBuffer.allocate(4).apply { putInt(timecode.frame.toInt()); flip() }, pos)
            pos += 4
        }
        writeMdatHeader()
    }

    val videoSampleCount: Int get() = videoSizes.size

    /** One encoded frame as length-prefixed NAL units, from [data]'s position to its limit. */
    fun writeVideo(data: ByteBuffer, keyFrame: Boolean) {
        val size = data.remaining()
        videoOffsets.add(pos)
        videoSizes.add(size)
        if (keyFrame) syncSamples.add(videoSizes.size)
        write(data, pos)
        pos += size
    }

    /** Whole PCM frames from [data]. */
    fun writeAudio(data: ByteArray, offset: Int, length: Int) {
        val format = audio ?: return
        require(length % format.bytesPerFrame == 0)
        audioOffsets.add(pos)
        audioChunkFrames.add(length / format.bytesPerFrame)
        audioFrames += length / format.bytesPerFrame
        write(ByteBuffer.wrap(data, offset, length), pos)
        pos += length
    }

    /** Makes everything written so far readable, should the take end here. */
    fun checkpoint() {
        if (overflowed) return
        val moov = buildMoov()
        if (moov.capacity() > RESERVED_BYTES - FREE_HEADER) {
            overflowed = true
            return
        }
        writeMoovIntoReserved(moov)
        writeMdatHeader()
    }

    /** Writes the final index and flushes to storage. The file stays open (its owner closes it). */
    fun finish() {
        val moov = buildMoov()
        writeMdatHeader()
        if (!overflowed && moov.capacity() <= RESERVED_BYTES - FREE_HEADER) {
            writeMoovIntoReserved(moov)
        } else {
            writeFree(ftypEnd, RESERVED_BYTES)
            write(moov, pos)
            pos += moov.capacity()
        }
        channel.force(true)
    }

    private fun writeMoovIntoReserved(moov: ByteBuffer) {
        val size = moov.capacity().toLong()
        write(moov, ftypEnd)
        if (size < RESERVED_BYTES) writeFree(ftypEnd + size, RESERVED_BYTES - size)
    }

    private fun writeMdatHeader() {
        val header = ByteBuffer.allocate(MDAT_HEADER.toInt())
        header.putInt(1).put("mdat".toByteArray()).putLong(pos - mdatStart).flip()
        write(header, mdatStart)
    }

    private fun writeFree(at: Long, size: Long) {
        // A 'free' box header; its content can stay whatever was there.
        val header = ByteBuffer.allocate(8)
        header.putInt(size.toInt()).put("free".toByteArray()).flip()
        write(header, at)
    }

    private fun write(buffer: ByteBuffer, at: Long) {
        var offset = at
        while (buffer.hasRemaining()) offset += channel.write(buffer, offset)
    }

    // --- moov ---------------------------------------------------------------------------

    private fun buildMoov(): ByteBuffer {
        val frameTicks = video.frameTicks
        val videoTimescale = video.timescale
        val videoDuration = videoSizes.size * frameTicks
        val movieDurationMs = maxOf(
            videoDuration * 1000 / videoTimescale,
            audio?.let { audioFrames * 1000 / it.sampleRate } ?: 0,
        )
        return BoxWriter().apply {
            begin("moov")
            mvhd(movieDurationMs)
            videoTrak(videoDuration * 1000 / videoTimescale, videoTimescale, videoDuration, frameTicks)
            audio?.let { audioTrak(it, audioFrames * 1000 / it.sampleRate) }
            timecode?.let { timecodeTrak(it, videoDuration * 1000 / videoTimescale, videoTimescale, videoDuration, frameTicks) }
            if (metadata.isNotEmpty()) meta()
            end()
        }.toBuffer()
    }

    private fun BoxWriter.mvhd(durationMs: Long) {
        begin("mvhd")
        u32(0) // version, flags
        u32(creationTime); u32(creationTime)
        u32(1000); u32(durationMs)
        u32(0x00010000) // rate 1.0
        u16(0x0100) // volume 1.0
        zeros(10)
        matrix(0)
        zeros(24)
        u32(timecodeTrackId + if (timecode != null) 1L else 0L) // next track id
        end()
    }

    private fun BoxWriter.tkhd(trackId: Int, durationMs: Long, isAudio: Boolean, enabled: Boolean = true) {
        begin("tkhd")
        u32(if (enabled) 0x00000003 else 0x00000002) // version 0, (enabled +) in movie
        u32(creationTime); u32(creationTime)
        u32(trackId.toLong()); u32(0)
        u32(durationMs)
        zeros(8)
        u16(0); u16(if (isAudio) 1 else 0) // layer, alternate group
        u16(if (isAudio) 0x0100 else 0); u16(0) // volume
        matrix(if (isAudio) 0 else video.rotation)
        u32(if (isAudio) 0L else video.width.toLong() shl 16)
        u32(if (isAudio) 0L else video.height.toLong() shl 16)
        end()
    }

    private fun BoxWriter.mdhd(timescale: Long, duration: Long) {
        begin("mdhd")
        u32(0)
        u32(creationTime); u32(creationTime)
        u32(timescale); u32(duration)
        u16(0x55C4) // language "und"
        u16(0)
        end()
    }

    private fun BoxWriter.hdlr(type: String, name: String) {
        begin("hdlr")
        u32(0); u32(0)
        fourcc(type)
        zeros(12)
        bytes(name.toByteArray()); u8(0)
        end()
    }

    private fun BoxWriter.dinf() {
        begin("dinf")
        begin("dref"); u32(0); u32(1)
        begin("url "); u32(1); end()
        end()
        end()
    }

    private fun BoxWriter.videoTrak(durationMs: Long, timescale: Long, duration: Long, frameTicks: Long) {
        begin("trak")
        tkhd(1, durationMs, isAudio = false)
        if (timecode != null) {
            begin("tref"); begin("tmcd"); u32(timecodeTrackId.toLong()); end(); end()
        }
        begin("mdia")
        mdhd(timescale, duration)
        hdlr("vide", "VideoHandler")
        begin("minf")
        begin("vmhd"); u32(1); zeros(8); end()
        dinf()
        begin("stbl")
        begin("stsd"); u32(0); u32(1)
        hvc1()
        end()
        begin("stts"); u32(0)
        if (videoSizes.size > 0) { u32(1); u32(videoSizes.size.toLong()); u32(frameTicks) } else u32(0)
        end()
        begin("stss"); u32(0); u32(syncSamples.size.toLong())
        for (i in 0 until syncSamples.size) u32(syncSamples[i].toLong())
        end()
        begin("stsz"); u32(0); u32(0); u32(videoSizes.size.toLong())
        for (i in 0 until videoSizes.size) u32(videoSizes[i].toLong())
        end()
        begin("stsc"); u32(0)
        if (videoSizes.size > 0) { u32(1); u32(1); u32(1); u32(1) } else u32(0)
        end()
        co64(videoOffsets)
        end() // stbl
        end() // minf
        end() // mdia
        end() // trak
    }

    private fun BoxWriter.hvc1() {
        begin("hvc1")
        zeros(6); u16(1) // reserved, data reference index
        zeros(16)
        u16(video.width); u16(video.height)
        u32(0x00480000); u32(0x00480000) // 72 dpi
        u32(0)
        u16(1) // frame count
        val name = "HEVC Coding".toByteArray()
        u8(name.size); bytes(name); zeros(31 - name.size)
        u16(0x0018) // depth
        u16(0xFFFF)
        begin("hvcC"); bytes(video.hvcC); end()
        begin("colr")
        fourcc("nclx")
        u16(video.color.primaries); u16(video.color.transfer); u16(video.color.matrix)
        u8(if (video.color.fullRange) 0x80 else 0)
        end()
        begin("pasp"); u32(1); u32(1); end()
        end()
    }

    private fun BoxWriter.audioTrak(format: AudioTrackFormat, durationMs: Long) {
        begin("trak")
        tkhd(audioTrackId, durationMs, isAudio = true)
        begin("mdia")
        mdhd(format.sampleRate.toLong(), audioFrames)
        hdlr("soun", "SoundHandler")
        begin("minf")
        begin("smhd"); u32(0); u32(0); end()
        dinf()
        begin("stbl")
        begin("stsd"); u32(0); u32(1)
        ipcm(format)
        end()
        begin("stts"); u32(0)
        if (audioFrames > 0) { u32(1); u32(audioFrames); u32(1) } else u32(0)
        end()
        // Every PCM frame is one sample of fixed size.
        begin("stsz"); u32(0); u32(format.bytesPerFrame.toLong()); u32(audioFrames)
        end()
        stscRuns(audioChunkFrames)
        co64(audioOffsets)
        end() // stbl
        end() // minf
        end() // mdia
        end() // trak
    }

    private fun BoxWriter.timecodeTrak(tc: Timecode, durationMs: Long, timescale: Long, duration: Long, frameTicks: Long) {
        begin("trak")
        tkhd(timecodeTrackId, durationMs, isAudio = false, enabled = false)
        begin("mdia")
        mdhd(timescale, duration)
        hdlr("tmcd", "TimeCodeHandler")
        begin("minf")
        begin("nmhd"); u32(0); end()
        dinf()
        begin("stbl")
        begin("stsd"); u32(0); u32(1)
        begin("tmcd")
        zeros(6); u16(1) // reserved, data reference index
        u32(0) // reserved
        u32(0) // flags: non-drop, no 24 h wrap, no negatives
        u32(timescale); u32(frameTicks)
        u8(tc.framesPerSecond); u8(0)
        end()
        end()
        // One sample spanning the whole take.
        begin("stts"); u32(0); u32(1); u32(1); u32(maxOf(duration, 1)); end()
        begin("stsz"); u32(0); u32(4); u32(1); end()
        begin("stsc"); u32(0); u32(1); u32(1); u32(1); u32(1); end()
        begin("co64"); u32(0); u32(1); u64(timecodeOffset); end()
        end() // stbl
        end() // minf
        end() // mdia
        end() // trak
    }

    /** ISO/IEC 23003-5 integer PCM, laid out like FFmpeg writes it (which Resolve reads). */
    private fun BoxWriter.ipcm(format: AudioTrackFormat) {
        begin("ipcm")
        zeros(6); u16(1)
        zeros(8)
        u16(format.channels); u16(16) // sample size: the real one is in pcmC
        u16(0); u16(0)
        u32(format.sampleRate.toLong() shl 16)
        begin("chnl")
        u32(0)
        u8(1) // channel structured
        u8(if (format.channels == 2) 2 else 1) // ISO/IEC 23091-3 layout: 1 mono, 2 stereo
        zeros(8) // omitted channels map
        end()
        begin("pcmC")
        u32(0)
        u8(1) // little endian
        u8(format.bitsPerSample)
        end()
        end()
    }

    private fun BoxWriter.stscRuns(chunkFrames: IntList) {
        begin("stsc"); u32(0)
        val runsAt = position()
        u32(0)
        var runs = 0
        var last = -1
        for (i in 0 until chunkFrames.size) {
            if (chunkFrames[i] != last) {
                u32(i + 1L); u32(chunkFrames[i].toLong()); u32(1)
                last = chunkFrames[i]
                runs++
            }
        }
        patchU32(runsAt, runs.toLong())
        end()
    }

    private fun BoxWriter.co64(offsets: LongList) {
        begin("co64"); u32(0); u32(offsets.size.toLong())
        for (i in 0 until offsets.size) u64(offsets[i])
        end()
    }

    /** QuickTime metadata ('mdta' keys), the layout Apple and Resolve use. */
    private fun BoxWriter.meta() {
        begin("meta")
        hdlr("mdta", "")
        begin("keys"); u32(0); u32(metadata.size.toLong())
        for ((key, _) in metadata) { begin("mdta"); bytes(key.toByteArray()); end() }
        end()
        begin("ilst")
        metadata.forEachIndexed { i, (_, value) ->
            val bytes = value.toByteArray()
            u32(8 + 16L + bytes.size); u32(i + 1L)
            begin("data"); u32(1); u32(0); bytes(bytes); end() // type 1 = UTF-8
        }
        end()
        end()
    }

    /** Transformation matrix for a clockwise display rotation, as MediaRecorder writes it. */
    private fun BoxWriter.matrix(rotation: Int) {
        val (a, b, c, d) = when (rotation) {
            90 -> listOf(0, 0x10000, -0x10000, 0)
            180 -> listOf(-0x10000, 0, 0, -0x10000)
            270 -> listOf(0, -0x10000, 0x10000, 0)
            else -> listOf(0x10000, 0, 0, 0x10000)
        }
        s32(a); s32(b); s32(0)
        s32(c); s32(d); s32(0)
        s32(0); s32(0); s32(0x40000000)
    }

    companion object {
        /** Room for the index of about 1.8 hours at 30 fps with audio. */
        private const val RESERVED_BYTES = 4L * 1024 * 1024
        private const val MDAT_HEADER = 16L
        private const val FREE_HEADER = 8L
        private const val SECONDS_1904_TO_1970 = 2_082_844_800L
    }
}

/** Big-endian box builder with nested size patching. */
private class BoxWriter {
    private var data = ByteArray(64 * 1024)
    private var size = 0
    private val starts = ArrayDeque<Int>()

    fun position() = size

    fun begin(type: String) {
        starts.addLast(size)
        u32(0)
        fourcc(type)
    }

    fun end() {
        val start = starts.removeLast()
        patchU32(start, (size - start).toLong())
    }

    fun patchU32(at: Int, v: Long) {
        data[at] = (v ushr 24).toByte()
        data[at + 1] = (v ushr 16).toByte()
        data[at + 2] = (v ushr 8).toByte()
        data[at + 3] = v.toByte()
    }

    private fun ensure(n: Int) {
        if (size + n > data.size) data = data.copyOf(maxOf(data.size * 2, size + n))
    }

    fun u8(v: Int) { ensure(1); data[size++] = v.toByte() }
    fun u16(v: Int) { u8(v ushr 8); u8(v) }
    fun u32(v: Long) { ensure(4); patchU32(size, v); size += 4 }
    fun s32(v: Int) = u32(v.toLong() and 0xFFFFFFFFL)
    fun u64(v: Long) { u32(v ushr 32); u32(v and 0xFFFFFFFFL) }
    fun fourcc(s: String) = bytes(s.toByteArray(Charsets.US_ASCII))
    fun zeros(n: Int) { ensure(n); size += n }
    fun bytes(b: ByteArray) { ensure(b.size); b.copyInto(data, size); size += b.size }

    fun toBuffer(): ByteBuffer = ByteBuffer.wrap(data, 0, size).slice()
}

private class IntList {
    private var data = IntArray(1024)
    var size = 0
        private set

    fun add(v: Int) {
        if (size == data.size) data = data.copyOf(size * 2)
        data[size++] = v
    }

    operator fun get(i: Int) = data[i]
}

private class LongList {
    private var data = LongArray(1024)
    var size = 0
        private set

    fun add(v: Long) {
        if (size == data.size) data = data.copyOf(size * 2)
        data[size++] = v
    }

    operator fun get(i: Int) = data[i]
}
