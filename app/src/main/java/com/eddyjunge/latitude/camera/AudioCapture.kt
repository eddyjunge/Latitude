package com.eddyjunge.latitude.camera

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTimestamp
import android.os.Process
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.max

private const val TAG = "AudioCapture"

private const val SAMPLE_RATE = 48_000

/** One read is one meter update: 800 frames = 17 ms. */
private const val FRAMES_PER_READ = 800

/** Newest audio kept for the take, which runs a few hundred ms behind real time. */
private const val RING_SECONDS = 4

/** Peak meter release, like a PPM: falls 20 dB per second after a peak. */
private const val RELEASE_DB_PER_S = 20f

/** The peak marker stays this long before it falls with the bar. */
private const val HOLD_MS = 1500L

/** A clip keeps the channel red this long, so a single overload is never missed. */
private const val CLIP_HOLD_MS = 1500L

/** -0.5 dBFS on the 16-bit scale the meter works in. */
private const val CLIP_SAMPLE = 30_940

const val METER_FLOOR_DB = -60f

/** One channel in dBFS. [level] is the falling bar, [hold] the peak marker. */
data class ChannelLevel(val level: Float, val hold: Float, val clipped: Boolean)

/**
 * The newest captured PCM plus the clock that maps frame indices to CLOCK_MONOTONIC (the base
 * of the camera's encoder timestamps). The capture thread appends, a take reads behind it.
 */
class AudioRing(val format: AudioTrackFormat) {
    private val capacity = format.sampleRate * RING_SECONDS
    private val frameBytes = format.bytesPerFrame
    private val data = ByteArray(capacity * frameBytes)

    /** Frames captured so far; frame i lives at slot i % capacity. */
    @Volatile
    var written = 0L
        private set

    private class Clock(val frame: Long, val nanos: Long)

    @Volatile
    private var clock: Clock? = null

    val hasClock: Boolean get() = clock != null

    /** Frame index (fractional) captured at [nanos] on CLOCK_MONOTONIC; needs [hasClock]. */
    fun frameAt(nanos: Long): Double {
        val c = clock!!
        return c.frame + (nanos - c.nanos) * format.sampleRate / 1e9
    }

    internal fun append(src: ByteArray, frames: Int) {
        var from = 0
        var slot = (written % capacity).toInt()
        var left = frames
        while (left > 0) {
            val n = minOf(left, capacity - slot)
            src.copyInto(data, slot * frameBytes, from * frameBytes, (from + n) * frameBytes)
            from += n
            left -= n
            slot = 0
        }
        written += frames
    }

    internal fun setClock(frame: Long, nanos: Long) {
        clock = Clock(frame, nanos)
    }

    /**
     * Copies [frames] frames from absolute index [from] into [dst]. Frames not captured (yet, or
     * any more) come out as silence. Returns how many were real.
     */
    fun read(from: Long, frames: Int, dst: ByteArray, dstOffset: Int): Int {
        val newest = written
        var real = 0
        for (i in 0 until frames) {
            val index = from + i
            val at = dstOffset + i * frameBytes
            if (index < 0 || index >= newest || index < newest - capacity + 1) {
                dst.fill(0, at, at + frameBytes)
            } else {
                val slot = (index % capacity).toInt() * frameBytes
                data.copyInto(dst, at, slot, slot + frameBytes)
                real++
            }
        }
        return real
    }
}

/**
 * The one microphone client of the app: meters the chosen input while the camera is open and
 * keeps the newest audio in an [AudioRing] that a take records from. One client also means no
 * fight over inputs HyperOS hands out exclusively (UNPROCESSED silences a second client).
 *
 * Asks for 24-bit samples (the internal mics deliver them) and falls back to 16-bit.
 */
class AudioCapture {
    private val _levels = MutableStateFlow<List<ChannelLevel>?>(null)
    /** Null while no mic is metered. */
    val levels: StateFlow<List<ChannelLevel>?> = _levels.asStateFlow()

    private var worker: Worker? = null

    /** Audio of the running input for a take; null while nothing is captured. */
    val ring: AudioRing? get() = worker?.ring

    val running: Boolean get() = worker != null

    /** Capture [choice], or nothing when null. Restarts only if the input actually changed. */
    fun start(choice: AudioChoice?) {
        val current = worker
        if (current != null && choice != null && current.choice.sameInput(choice)) return
        stop()
        if (choice == null) return
        worker = Worker(choice).also { it.start() }
    }

    /** With [join] it returns only once the mic is released. */
    fun stop(join: Boolean = false) {
        val current = worker
        worker = null
        _levels.value = null
        if (current == null) return
        current.stopped = true
        if (join) current.join(500)
    }

    private fun AudioChoice.sameInput(other: AudioChoice) =
        audioSource == other.audioSource && device?.id == other.device?.id && channels == other.channels

    /** Instant attack, slow release, peak hold and clip latch per channel. */
    private class Ballistics(channels: Int) {
        private val level = FloatArray(channels) { METER_FLOOR_DB }
        private val hold = FloatArray(channels) { METER_FLOOR_DB }
        private val holdAt = LongArray(channels)
        private val clipUntil = LongArray(channels)

        /** [peaks] are the largest absolute samples of the last [seconds], on a 16-bit scale. */
        fun update(peaks: IntArray, seconds: Float): List<ChannelLevel> {
            val now = SystemClock.elapsedRealtime()
            val fall = RELEASE_DB_PER_S * seconds
            return List(peaks.size) { ch ->
                val db = max(20f * log10(peaks[ch] / 32768f + 1e-6f), METER_FLOOR_DB)
                level[ch] = max(db, level[ch] - fall)
                if (db >= hold[ch]) {
                    hold[ch] = db
                    holdAt[ch] = now
                } else if (now - holdAt[ch] > HOLD_MS) {
                    hold[ch] = max(level[ch], hold[ch] - fall)
                }
                if (peaks[ch] >= CLIP_SAMPLE) clipUntil[ch] = now + CLIP_HOLD_MS
                ChannelLevel(level[ch], hold[ch], now < clipUntil[ch])
            }
        }
    }

    private inner class Worker(val choice: AudioChoice) : Thread("audio-capture") {
        @Volatile
        var stopped = false

        @Volatile
        var ring: AudioRing? = null

        @SuppressLint("MissingPermission") // the camera, and so the capture, only opens with permissions
        override fun run() {
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
            val channels = choice.channels
            val mask = if (channels == 2) AudioFormat.CHANNEL_IN_STEREO else AudioFormat.CHANNEL_IN_MONO
            val (record, bits) = open(mask, AudioFormat.ENCODING_PCM_24BIT_PACKED, 24)
                ?: open(mask, AudioFormat.ENCODING_PCM_16BIT, 16)
                ?: return
            try {
                choice.device?.let { record.setPreferredDevice(it) }
                record.startRecording()
                val format = AudioTrackFormat(SAMPLE_RATE, channels, bits)
                ring = AudioRing(format)
                Log.i(TAG, "capturing source ${choice.audioSource}, $channels ch, $bits bit")
                loop(record, format, ring!!)
            } catch (e: Exception) {
                Log.w(TAG, "capture stopped", e)
            } finally {
                runCatching { record.stop() }
                record.release()
            }
        }

        @SuppressLint("MissingPermission")
        private fun open(mask: Int, encoding: Int, bits: Int): Pair<AudioRecord, Int>? = try {
            val frameBytes = Integer.bitCount(mask) * bits / 8
            val record = AudioRecord.Builder()
                .setAudioSource(choice.audioSource)
                .setAudioFormat(
                    AudioFormat.Builder().setSampleRate(SAMPLE_RATE).setChannelMask(mask).setEncoding(encoding).build(),
                )
                .setBufferSizeInBytes(
                    max(AudioRecord.getMinBufferSize(SAMPLE_RATE, mask, encoding), FRAMES_PER_READ * frameBytes * 8),
                )
                .build()
            if (record.state == AudioRecord.STATE_INITIALIZED) {
                record to bits
            } else {
                record.release()
                null
            }
        } catch (e: Exception) {
            Log.w(TAG, "no $bits-bit capture for source ${choice.audioSource}", e)
            null
        }

        private fun loop(record: AudioRecord, format: AudioTrackFormat, ring: AudioRing) {
            val frameBytes = format.bytesPerFrame
            val buffer = ByteArray(FRAMES_PER_READ * frameBytes)
            val channels = format.channels
            val bytesPerSample = format.bitsPerSample / 8
            val peaks = IntArray(channels)
            val ballistics = Ballistics(channels)
            val timestamp = AudioTimestamp()
            while (!stopped) {
                val read = record.read(buffer, 0, buffer.size, AudioRecord.READ_BLOCKING)
                if (read < 0) {
                    Log.w(TAG, "read failed: $read")
                    return
                }
                val frames = read / frameBytes
                if (frames == 0) continue
                ring.append(buffer, frames)
                if (record.getTimestamp(timestamp, AudioTimestamp.TIMEBASE_MONOTONIC) == AudioRecord.SUCCESS) {
                    ring.setClock(timestamp.framePosition, timestamp.nanoTime)
                }
                peaks.fill(0)
                var at = 0
                for (f in 0 until frames) {
                    for (ch in 0 until channels) {
                        // Little endian; the top 16 bits are enough for a meter.
                        val hi = buffer[at + bytesPerSample - 1].toInt()
                        val lo = buffer[at + bytesPerSample - 2].toInt() and 0xFF
                        val s = abs((hi shl 8) or lo)
                        if (s > peaks[ch]) peaks[ch] = s
                        at += bytesPerSample
                    }
                }
                val out = ballistics.update(peaks, frames.toFloat() / format.sampleRate)
                if (!stopped) _levels.value = out
            }
        }
    }
}
