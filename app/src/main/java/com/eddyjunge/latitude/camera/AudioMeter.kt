package com.eddyjunge.latitude.camera

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.max

private const val TAG = "AudioMeter"

private const val SAMPLE_RATE = 48_000

/** One read is one meter update: 1600 frames = 33 ms, about the display's pace. */
private const val FRAMES_PER_READ = 1600
private const val UPDATE_MS = 33L

/** Peak meter release, like a PPM: falls 20 dB per second after a peak. */
private const val RELEASE_DB_PER_S = 20f

/** The peak marker stays this long before it falls with the bar. */
private const val HOLD_MS = 1500L

/** A clip keeps the channel red this long, so a single overload is never missed. */
private const val CLIP_HOLD_MS = 1500L

/** -0.5 dBFS in 16-bit: the AAC track overloads here. */
private const val CLIP_SAMPLE = 30_940

const val METER_FLOOR_DB = -60f

/** One channel in dBFS. [level] is the falling bar, [hold] the peak marker. */
data class ChannelLevel(val level: Float, val hold: Float, val clipped: Boolean)

/**
 * Live input level of the chosen mic, one entry per recorded channel.
 *
 * Normally reads its own AudioRecord with the same source and device as the recording.
 * Android shares that input between both within one app, so the meter shows what is
 * recorded, before and during a take. Inputs the phone keeps exclusive (UNPROCESSED on
 * HyperOS silences the second client) are followed via the recorder's own peak instead.
 */
class AudioMeter {
    private val _levels = MutableStateFlow<List<ChannelLevel>?>(null)
    /** Null while no mic is metered. */
    val levels: StateFlow<List<ChannelLevel>?> = _levels.asStateFlow()

    private var worker: Worker? = null
    private var poll: Runnable? = null
    private var pollHandler: Handler? = null

    val running: Boolean get() = worker != null || poll != null

    /** True while showing a recorder's peak via [follow]. */
    val following: Boolean get() = poll != null

    /** Meter [choice], or nothing when null. Restarts only if the input actually changed. */
    fun meter(choice: AudioChoice?) {
        val current = worker
        if (current != null && choice != null && current.choice.sameInput(choice)) return
        stop()
        if (choice == null) return
        worker = Worker(choice).also { it.start() }
    }

    /**
     * Camera thread, with [handler] on it. Shows [recorder]'s peak on every channel; it
     * only reports one value across all channels. Call [stop] before releasing the recorder.
     */
    fun follow(recorder: MediaRecorder, channels: Int, handler: Handler) {
        stop()
        val ballistics = Ballistics(channels)
        val peaks = IntArray(channels)
        recorder.maxAmplitude // starts the "since last call" window
        val task = object : Runnable {
            override fun run() {
                peaks.fill(recorder.maxAmplitude)
                _levels.value = ballistics.update(peaks, UPDATE_MS / 1000f)
                handler.postDelayed(this, UPDATE_MS)
            }
        }
        poll = task
        pollHandler = handler
        handler.postDelayed(task, UPDATE_MS)
    }

    /** With [join] it returns only once the mic is released. */
    fun stop(join: Boolean = false) {
        poll?.let { pollHandler?.removeCallbacks(it) }
        poll = null
        pollHandler = null
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

        /** [peaks] are the largest absolute 16-bit samples of the last [seconds]. */
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

    private inner class Worker(val choice: AudioChoice) : Thread("audio-meter") {
        @Volatile
        var stopped = false

        @SuppressLint("MissingPermission") // the camera, and so the meter, only opens with permissions
        override fun run() {
            val channels = choice.channels
            val mask = if (channels == 2) AudioFormat.CHANNEL_IN_STEREO else AudioFormat.CHANNEL_IN_MONO
            val record = try {
                AudioRecord.Builder()
                    .setAudioSource(choice.audioSource)
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setSampleRate(SAMPLE_RATE)
                            .setChannelMask(mask)
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .build(),
                    )
                    .setBufferSizeInBytes(
                        max(AudioRecord.getMinBufferSize(SAMPLE_RATE, mask, AudioFormat.ENCODING_PCM_16BIT), FRAMES_PER_READ * channels * 2 * 2),
                    )
                    .build()
            } catch (e: Exception) {
                Log.w(TAG, "no meter for source ${choice.audioSource}", e)
                return
            }
            try {
                choice.device?.let { record.setPreferredDevice(it) }
                record.startRecording()
                loop(record, channels)
            } catch (e: Exception) {
                Log.w(TAG, "meter stopped", e)
            } finally {
                runCatching { record.stop() }
                record.release()
            }
        }

        private fun loop(record: AudioRecord, channels: Int) {
            val buffer = ShortArray(FRAMES_PER_READ * channels)
            val peaks = IntArray(channels)
            val ballistics = Ballistics(channels)
            while (!stopped) {
                val read = record.read(buffer, 0, buffer.size, AudioRecord.READ_BLOCKING)
                if (read < 0) {
                    Log.w(TAG, "read failed: $read")
                    return
                }
                if (read == 0) continue
                peaks.fill(0)
                for (i in 0 until read) {
                    val ch = i % channels
                    val s = abs(buffer[i].toInt())
                    if (s > peaks[ch]) peaks[ch] = s
                }
                val out = ballistics.update(peaks, (read / channels).toFloat() / SAMPLE_RATE)
                if (!stopped) _levels.value = out
            }
        }
    }
}
