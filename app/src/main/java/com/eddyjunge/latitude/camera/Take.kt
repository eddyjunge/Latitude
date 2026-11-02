package com.eddyjunge.latitude.camera

import android.media.MediaCodec
import android.media.MediaCodecInfo.CodecCapabilities
import android.media.MediaCodecInfo.CodecProfileLevel
import android.media.MediaFormat
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.roundToLong

private const val TAG = "Take"

/** How long [Take.finish] waits for the encoder to hand out its last frames. */
private const val EOS_TIMEOUT_MS = 2000L

/** Longest wait at the first frame for the microphone capture to come up. */
private const val AUDIO_START_WAIT_MS = 500L

/** Longest wait for audio the capture hasn't delivered yet; it normally runs well ahead. */
private const val AUDIO_WAIT_MS = 150L

/**
 * How much later the microphone's timestamps are than the sound itself. Clap test 2026-11-02,
 * internal mic, three claps: sound 24–39 ms behind the frames where the hands meet, minus ~3 ms
 * of sound travel and ~12 ms because the rolling shutter exposes mid-frame rows after the first
 * one (the frame timestamp). The rest is processing the audio timestamp doesn't account for.
 */
private const val AUDIO_LATENCY_NS = 20_000_000L

/** Share of the remaining audio/video offset corrected per frame: settles in about two seconds. */
private const val SYNC_GAIN = 0.02

/**
 * One recording: the camera renders into the persistent [surface], the hardware HEVC encoder
 * compresses, and [Mp4Writer] writes video plus the matching slice of [audio] for every frame.
 *
 * Timing: every frame lasts exactly one period of the file's frame rate (strict CFR). The sensor
 * doesn't run at exactly that rate (30.06 fps measured), so the audio follows the video: for each
 * frame, the sound captured between this frame's sensor time and the next is resampled to exactly
 * one file frame period. Picture and sound stay in sync over any length (the 0.3 % pitch change
 * is inaudible), and a frame the camera dropped takes its audio with it.
 *
 * Encoder callbacks and all writing happen on the take's own thread.
 */
class Take(
    private val surface: Surface,
    private val config: VideoConfig,
    private val tenBit: Boolean,
    private val hevcLevel: Int?,
    private val profile: ColorProfile,
    private val rotation: Int,
    /** Where to get the microphone's audio; null records without sound. */
    private val audioSource: (() -> AudioRing?)?,
    private val output: VideoOutput,
    /** Interval between frames measured from the sensor's timestamps; the first guess for the audio. */
    measuredFramePeriodNs: Long?,
) {
    /**
     * "30 fps" in the file is NTSC 29.97 (30000/1001), like the user's DJI Action 6 and Sony
     * "30p", so every clip in the timeline shares one rate.
     */
    private val timescale = config.fps * 1000L
    private val frameTicks = if (config.fps in NTSC_RATES) 1001L else 1000L
    private val periodNs = frameTicks * 1e9 / timescale
    private val sensorPeriodNs = measuredFramePeriodNs?.toDouble() ?: periodNs

    private val thread = HandlerThread("take", Process.THREAD_PRIORITY_DISPLAY).apply { start() }
    private val handler = Handler(thread.looper)
    private val codec = createEncoder()
    private val eos = CountDownLatch(1)

    private var hvcC: ByteArray? = null
    private var writer: Mp4Writer? = null
    private var startNs = 0L
    private var lastPtsNs = 0L
    private var droppedFrames = 0
    private var leftovers = 0

    private var scratch = ByteArray(1)
    private var nal: ByteBuffer? = null

    private var audio: AudioRing? = null
    /** Capture frame (fractional) the next written sample is taken from. */
    private var position = Double.NaN
    /** Capture frames between two video frames, smoothed. */
    private var sourcePerFrame = 0.0
    private var lastTarget = Double.NaN
    private var outputDue = 0.0
    private var span = ByteArray(0)
    private var audioBuffer = ByteArray(0)

    @Volatile
    private var failure: Throwable? = null
    private var started = false

    /** Starts the encoder. Frames older than [startNs] (CLOCK_MONOTONIC) are leftovers of the last take. */
    fun start(startNs: Long) {
        this.startNs = startNs
        codec.setCallback(callback, handler)
        codec.configure(encoderFormat(config, tenBit, hevcLevel), null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        codec.setInputSurface(surface)
        codec.start()
        started = true
    }

    /**
     * Blocking, off the camera thread. Ends the stream, writes the index and says whether the file
     * holds a usable take. Call once the camera no longer feeds [surface].
     */
    fun finish(): Boolean {
        if (!started) {
            codec.release()
            thread.quitSafely()
            return false
        }
        val finishing = SystemClock.elapsedRealtime()
        runCatching { codec.signalEndOfInputStream() }.onFailure { Log.w(TAG, "end of stream failed", it) }
        if (!eos.await(EOS_TIMEOUT_MS, TimeUnit.MILLISECONDS)) Log.w(TAG, "encoder gave no end of stream")
        runCatching { codec.stop() }
        codec.release()
        val done = CountDownLatch(1)
        var ok = false
        handler.post {
            ok = runCatching {
                val w = writer ?: return@runCatching false
                w.finish()
                Log.i(
                    TAG,
                    "take: ${w.videoSampleCount} frames, $droppedFrames dropped by the camera, " +
                        "$leftovers leftovers skipped, finished in ${SystemClock.elapsedRealtime() - finishing} ms",
                )
                failure == null || w.videoSampleCount > 0
            }.onFailure { Log.e(TAG, "finishing the file failed", it) }.getOrDefault(false)
            done.countDown()
        }
        done.await(EOS_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        thread.quitSafely()
        return ok
    }

    private val callback = object : MediaCodec.Callback() {
        override fun onInputBufferAvailable(codec: MediaCodec, index: Int) = Unit

        override fun onOutputBufferAvailable(codec: MediaCodec, index: Int, info: MediaCodec.BufferInfo) {
            try {
                val buffer = codec.getOutputBuffer(index)
                if (buffer != null && info.size > 0) {
                    buffer.position(info.offset).limit(info.offset + info.size)
                    if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                        val bytes = ByteArray(info.size).also { buffer.get(it) }
                        hvcC = Hevc.configurationRecord(bytes, vuiFix())
                    } else {
                        onFrame(buffer, info)
                    }
                }
            } catch (e: Exception) {
                if (failure == null) Log.e(TAG, "writing a frame failed", e)
                failure = e
            } finally {
                runCatching { codec.releaseOutputBuffer(index, false) }
            }
            if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) eos.countDown()
        }

        override fun onError(codec: MediaCodec, e: MediaCodec.CodecException) {
            Log.e(TAG, "encoder error", e)
            failure = e
            eos.countDown()
        }

        override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) {
            Log.i(TAG, "encoder output $format")
            if (hvcC == null) {
                format.getByteBuffer("csd-0")?.let { csd ->
                    val bytes = ByteArray(csd.remaining()).also { csd.duplicate().get(it) }
                    hvcC = Hevc.configurationRecord(bytes, vuiFix())
                }
            }
        }
    }

    private fun onFrame(buffer: ByteBuffer, info: MediaCodec.BufferInfo) {
        val ptsNs = info.presentationTimeUs * 1000
        // The persistent surface keeps a few frames of the last take; they predate this one.
        if (ptsNs < startNs) {
            leftovers++
            return
        }
        val key = info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0
        var w = writer
        if (w == null) {
            if (!key) return
            val record = hvcC ?: throw IllegalStateException("frame before codec config")
            audio = awaitAudio()
            w = Mp4Writer(output.fd.fileDescriptor, trackFormat(record), audio?.format, metadata())
            writer = w
            Log.i(TAG, "first frame ${(ptsNs - startNs) / 1_000_000} ms after start; file $timescale/$frameTicks, sensor %.3f ms".format(sensorPeriodNs / 1e6))
        } else {
            val gap = ((ptsNs - lastPtsNs) / periodNs).roundToLong()
            if (gap > 1) {
                droppedFrames += (gap - 1).toInt()
                Log.w(TAG, "camera skipped ${gap - 1} frame(s)")
            }
        }
        lastPtsNs = ptsNs

        val (converted, bytes) = Hevc.toLengthPrefixed(buffer, scratch, nal)
        scratch = bytes
        nal = converted
        w.writeVideo(converted, key)
        writeAudioFor(ptsNs, w)
        if (w.videoSampleCount % config.fps == 0) w.checkpoint()
    }

    /** The capture may still be starting when a take begins right out of standby. */
    private fun awaitAudio(): AudioRing? {
        val source = audioSource ?: return null
        val until = SystemClock.elapsedRealtime() + AUDIO_START_WAIT_MS
        while (true) {
            source()?.let { return it }
            if (SystemClock.elapsedRealtime() > until) break
            Thread.sleep(5)
        }
        Log.e(TAG, "microphone not running, recording without sound")
        return null
    }

    /**
     * One file frame period of audio, resampled from what the microphone captured between this
     * frame's sensor time [ptsNs] and the next frame's.
     */
    private fun writeAudioFor(ptsNs: Long, w: Mp4Writer) {
        val ring = audio ?: return
        val format = ring.format
        val frameBytes = format.bytesPerFrame
        outputDue += format.sampleRate * periodNs / 1e9
        val n = floor(outputDue).toInt()
        outputDue -= n
        if (audioBuffer.size < n * frameBytes) audioBuffer = ByteArray(n * frameBytes)

        if (!ring.hasClock) {
            // The capture is still starting (e.g. right out of standby): silence until it runs.
            audioBuffer.fill(0, 0, n * frameBytes)
            w.writeAudio(audioBuffer, 0, n * frameBytes)
            return
        }
        val target = ring.frameAt(ptsNs + AUDIO_LATENCY_NS)
        val nominal = format.sampleRate * sensorPeriodNs / 1e9
        if (sourcePerFrame == 0.0) sourcePerFrame = nominal
        if (!lastTarget.isNaN()) {
            val step = target - lastTarget
            if (step > nominal * 0.5 && step < nominal * 1.5) sourcePerFrame = sourcePerFrame * 0.98 + step * 0.02
        }
        lastTarget = target
        // Off by most of a frame: the camera dropped one (or the capture restarted). Jump.
        if (position.isNaN() || abs(position - target) > sourcePerFrame * 0.75) {
            if (!position.isNaN()) Log.w(TAG, "audio realigned by %.1f ms".format((position - target) * 1000 / format.sampleRate))
            position = target
        }
        // Read this frame's span of the capture, steering the remaining offset gently to zero.
        val consume = sourcePerFrame - (position - target) * SYNC_GAIN
        val step = consume / n
        val first = floor(position).toLong()
        val count = (ceil(position + consume).toLong() - first + 2).toInt()
        if (span.size < count * frameBytes) span = ByteArray(count * frameBytes)
        val waitUntil = SystemClock.elapsedRealtime() + AUDIO_WAIT_MS
        while (ring.written < first + count && SystemClock.elapsedRealtime() < waitUntil) Thread.sleep(2)
        val real = ring.read(first, count, span, 0)
        if (real < count) Log.w(TAG, "${count - real} audio frames missing, filled with silence")
        resample(span, position - first, step, n, format, audioBuffer)
        w.writeAudio(audioBuffer, 0, n * frameBytes)
        position += consume
    }

    /** Linear interpolation of [n] frames from [src], starting at fractional frame [offset]. */
    private fun resample(src: ByteArray, offset: Double, step: Double, n: Int, format: AudioTrackFormat, dst: ByteArray) {
        val channels = format.channels
        val bytes = format.bitsPerSample / 8
        val frameBytes = format.bytesPerFrame
        val max = (1 shl (format.bitsPerSample - 1)) - 1
        for (i in 0 until n) {
            val p = offset + i * step
            val i0 = floor(p).toInt()
            val frac = p - i0
            for (ch in 0 until channels) {
                val a = sample(src, i0 * frameBytes + ch * bytes, bytes)
                val b = sample(src, (i0 + 1) * frameBytes + ch * bytes, bytes)
                val v = (a + (b - a) * frac).roundToInt().coerceIn(-max - 1, max)
                val at = i * frameBytes + ch * bytes
                for (k in 0 until bytes) dst[at + k] = (v shr (8 * k)).toByte()
            }
        }
    }

    /** Little-endian signed sample of [bytes] bytes. */
    private fun sample(src: ByteArray, at: Int, bytes: Int): Int {
        var v = 0
        for (k in 0 until bytes - 1) v = v or ((src[at + k].toInt() and 0xFF) shl (8 * k))
        return v or (src[at + bytes - 1].toInt() shl (8 * (bytes - 1)))
    }

    /** The SPS says what the data is (see [trackFormat]), and for Log the same transfer as 'colr'. */
    private fun vuiFix() = Hevc.VuiFix(fullRange = !tenBit, transfer = if (profile == ColorProfile.LOG) 2 else null)

    private fun trackFormat(record: ByteArray) = VideoTrackFormat(
        width = config.videoSize.width,
        height = config.videoSize.height,
        timescale = timescale,
        frameTicks = frameTicks,
        rotation = rotation,
        // Measured on raw codes: the 10-bit session delivers limited range (viewfinder level v at
        // code 64 + 876·v, the camera labels its buffers BT2020/HLG/limited), the 8-bit one full
        // range (our Rec.709 curve's top at 238 of 255). The encoder's own VUI says limited for both.
        color = when (profile) {
            // Apple's own Log files say BT.2020 with an unspecified transfer; the metadata key names the curve.
            ColorProfile.LOG -> ColorTag(primaries = 9, transfer = 2, matrix = 9, fullRange = false)
            ColorProfile.HLG -> ColorTag(primaries = 9, transfer = 18, matrix = 9, fullRange = false)
            ColorProfile.REC709 -> ColorTag(primaries = 1, transfer = 1, matrix = 1, fullRange = true)
        },
        hvcC = record,
    )

    private fun metadata(): List<Pair<String, String>> = buildList {
        // Makes Resolve pick "Apple Log" as input color space on import (measured on Resolve 21.1).
        if (profile == ColorProfile.LOG) add("com.apple.proapps.customgamma" to "com.apple.rec2020.apple-log")
        add("com.apple.quicktime.make" to Build.MANUFACTURER)
        add("com.apple.quicktime.model" to Build.MODEL)
        add("com.apple.quicktime.software" to "Latitude")
    }

    companion object {
        private const val ENCODER = "c2.qti.hevc.encoder"

        /** Rates that cameras record as their NTSC variant (x·1000/1001). */
        private val NTSC_RATES = setOf(24, 30, 60)

        fun createEncoder(): MediaCodec =
            runCatching { MediaCodec.createByCodecName(ENCODER) }.getOrElse {
                MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_HEVC)
            }

        /** HEVC from a surface: Main10 BT.2020 HLG limited in the 10-bit session, else BT.709 full range. */
        fun encoderFormat(config: VideoConfig, tenBit: Boolean, level: Int?): MediaFormat =
            MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_HEVC, config.videoSize.width, config.videoSize.height).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_BIT_RATE, config.bitrate)
                setInteger(MediaFormat.KEY_BITRATE_MODE, android.media.MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR)
                setInteger(MediaFormat.KEY_FRAME_RATE, config.fps)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
                setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0)
                setInteger(MediaFormat.KEY_PRIORITY, 0)
                setInteger(MediaFormat.KEY_PROFILE, if (tenBit) CodecProfileLevel.HEVCProfileMain10 else CodecProfileLevel.HEVCProfileMain)
                level?.let { setInteger(MediaFormat.KEY_LEVEL, it) }
                setInteger(MediaFormat.KEY_COLOR_STANDARD, if (tenBit) MediaFormat.COLOR_STANDARD_BT2020 else MediaFormat.COLOR_STANDARD_BT709)
                setInteger(MediaFormat.KEY_COLOR_TRANSFER, if (tenBit) MediaFormat.COLOR_TRANSFER_HLG else MediaFormat.COLOR_TRANSFER_SDR_VIDEO)
                // What the camera delivers. Only a label: the camera picks the range itself.
                setInteger(MediaFormat.KEY_COLOR_RANGE, if (tenBit) MediaFormat.COLOR_RANGE_LIMITED else MediaFormat.COLOR_RANGE_FULL)
            }
    }
}
