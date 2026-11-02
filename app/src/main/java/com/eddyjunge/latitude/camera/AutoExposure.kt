package com.eddyjunge.latitude.camera

import android.graphics.Bitmap
import android.os.Handler
import android.util.Log
import android.view.PixelCopy
import android.view.Surface
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.log2
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.roundToLong

private const val TAG = "AutoExposure"

/** Live sensor values for the readout. */
data class Exposure(
    val iso: Int,
    val exposureNs: Long,
    /** Our automatic value is pinned at the sensor limit: the scene is too bright or too dark for it. */
    val atLimit: Boolean = false,
)

internal enum class AeMode {
    /** The camera's AE picks both values. */
    CAMERA,

    /** The camera's AE holds one value fixed and adjusts the other (Android 16 priority modes). */
    PRIORITY,

    /** Both values fixed by the user. */
    MANUAL,

    /** One value fixed; [BrightnessLoop] adjusts the other because the camera can't. */
    LOOP,
}

/** What the user's shutter/ISO choice means on this lens. A null value is automatic. */
internal data class ExposureTarget(val exposureNs: Long?, val iso: Int?, val mode: AeMode) {
    companion object {
        fun of(settings: CaptureSettings, config: VideoConfig): ExposureTarget {
            val range = config.exposureRangeNs
            val exposureNs = when (settings.shutter) {
                SHUTTER_AUTO -> null
                SHUTTER_180 -> config.frameDurationNs / 2
                else -> 1_000_000_000L / settings.shutter
            }?.coerceIn(range.lower, minOf(range.upper, config.frameDurationNs))
            val iso = settings.iso.takeIf { it != ISO_AUTO }?.coerceIn(config.isoRange.lower, config.isoRange.upper)
            val mode = when {
                exposureNs == null && iso == null -> AeMode.CAMERA
                exposureNs != null && iso != null -> AeMode.MANUAL
                exposureNs != null && config.shutterPriority -> AeMode.PRIORITY
                iso != null && config.isoPriority -> AeMode.PRIORITY
                else -> AeMode.LOOP
            }
            return ExposureTarget(exposureNs, iso, mode)
        }
    }
}

/**
 * Our own auto exposure for a fixed shutter (or ISO), because the Xiaomi 15 Ultra's camera
 * offers no priority mode: once AE is off, nothing meters the scene any more.
 *
 * A few times per second a tiny downscaled copy of the preview is read back (PixelCopy, no
 * extra camera stream, so the session stays exactly as it is) and sets a new goal for the free
 * value, so the center-weighted brightness lands on [TARGET]. Every frame then glides a little
 * toward that goal ([step]), so the picture never jumps. Camera thread only.
 */
internal class BrightnessLoop(private val handler: Handler) {
    // ISO × exposure time, i.e. how much light: what metering asks for, what the current
    // request sends, and what the newest finished frame had. 0 until seeded.
    private var goal = 0.0
    private var applied = 0.0
    private var seen = 0.0

    /** What the fixed value allows the product to be. */
    private var lowest = 0.0
    private var highest = Double.MAX_VALUE

    private var surface: Surface? = null
    private var copying = false
    private val bitmap = Bitmap.createBitmap(METER_W, METER_H, Bitmap.Config.ARGB_8888)
    private val pixels = IntArray(METER_W * METER_H)
    private var lastLog = 0L

    /** The free value is at the end of its range. */
    var atLimit = false
        private set

    /** The preview shows this profile's encoding, which sets what correct exposure reads as. */
    var profile = ColorProfile.HLG

    /** Matched colors: our fixed tone curves instead of the vendor's, see [ToneCurves]. */
    var ownCurves = false

    /** Forget the scene, e.g. after a lens change (other aperture, other light). */
    fun reset() {
        goal = 0.0
        applied = 0.0
        seen = 0.0
        atLimit = false
    }

    /**
     * Sensor values for [target]: the fixed one as chosen, the free one from where the glide
     * is right now. Starts from [seed] (what the sensor did last) so switching modes doesn't jump.
     *
     * With a fixed shutter the ISO does the work. Where it runs out in bright light the shutter
     * gets shorter, because a clipped sky can't be graded back. Not longer in the dark: indoors
     * any time between 1/60 and 1/30 flickers under 50 Hz light.
     */
    fun resolve(target: ExposureTarget, config: VideoConfig, seed: Exposure?): Pair<Long, Int> {
        val isoRange = config.isoRange
        val nsRange = config.exposureRangeNs
        val maxNs = minOf(nsRange.upper, config.frameDurationNs)
        if (target.exposureNs != null) {
            lowest = isoRange.lower.toDouble() * nsRange.lower
            highest = isoRange.upper.toDouble() * target.exposureNs
        } else {
            val iso = (target.iso ?: isoRange.lower).toDouble()
            lowest = iso * nsRange.lower
            highest = iso * maxNs
        }
        if (applied == 0.0) {
            applied = seed?.let { it.iso.toDouble() * it.exposureNs } ?: DEFAULT_PRODUCT
            goal = applied
        }
        // No wind-up: past the limit the loop would otherwise keep asking for more and lag on the way back.
        applied = applied.coerceIn(lowest, highest)
        goal = goal.coerceIn(lowest, highest)
        return if (target.exposureNs != null) {
            if (applied < isoRange.lower.toDouble() * target.exposureNs) {
                (applied / isoRange.lower).roundToLong().coerceIn(nsRange.lower, target.exposureNs) to isoRange.lower
            } else {
                target.exposureNs to (applied / target.exposureNs).roundToInt().coerceIn(isoRange.lower, isoRange.upper)
            }
        } else {
            val iso = target.iso ?: isoRange.lower
            (applied / iso).roundToLong().coerceIn(nsRange.lower, maxNs) to iso
        }
    }

    /**
     * Every finished frame: remembers the light it had and moves the request one frame's worth
     * toward the goal, easing in and never faster than [MAX_STOPS_PER_S].
     * True when the request has to be updated.
     */
    fun step(frameProduct: Double, frameDurationNs: Long): Boolean {
        seen = frameProduct
        if (applied == 0.0 || applied == goal) return false
        val stops = log2(goal / applied)
        val dt = frameDurationNs / 1e9
        applied = if (abs(stops) < SNAP_STOPS) {
            goal
        } else {
            val eased = stops * (1 - exp(-dt / EASE_S))
            applied * 2.0.pow(eased.coerceIn(-MAX_STOPS_PER_S * dt, MAX_STOPS_PER_S * dt))
        }
        return true
    }

    /** Meters [surface] while it is non-null; null stops metering and holds the value where it is. */
    fun meter(surface: Surface?) {
        if (surface == null) goal = applied
        if (surface === this.surface) return
        this.surface = surface
        handler.removeCallbacks(tick)
        // The first frames of a new session are black; metering them would crank the ISO up.
        if (surface != null && !copying) handler.postDelayed(tick, FIRST_METER_DELAY_MS)
    }

    private val tick = Runnable { copy() }

    private fun copy() {
        val source = surface ?: return
        copying = true
        val requested = runCatching {
            PixelCopy.request(source, bitmap, { result ->
                copying = false
                if (surface !== source) {
                    surface?.let { handler.postDelayed(tick, INTERVAL_MS) }
                    return@request
                }
                if (result == PixelCopy.SUCCESS) adjust(brightness())
                handler.postDelayed(tick, INTERVAL_MS)
            }, handler)
        }
        if (requested.isFailure) {
            // The surface is being torn down; the next session hands us a new one.
            copying = false
            handler.postDelayed(tick, INTERVAL_MS)
        }
    }

    private fun adjust(measured: Double) {
        val (target, stopsPerStop) = when (profile) {
            ColorProfile.HLG -> (if (ownCurves) TARGET_HLG_OWN else TARGET_HLG) to 1.0
            ColorProfile.LOG -> TARGET_LOG * 2.0.pow(LOG_OFFSET_STOPS * LOG_STOPS_PER_STOP) to LOG_STOPS_PER_STOP
            ColorProfile.REC709 -> (if (ownCurves) TARGET_709_OWN else TARGET_709) to 1.0
        }
        val stops = log2(target / measured.coerceAtLeast(1e-4)) / stopsPerStop
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastLog > 2000) {
            lastLog = now
            Log.d(TAG, "brightness=%.3f off by %.2f stops, limit=$atLimit".format(measured, stops))
        }
        if (abs(stops) < DEADBAND_STOPS) {
            atLimit = false
            return
        }
        // Relative to the frame that was measured, not to the request: the preview shows the
        // sensor a few frames late, and correcting the request again would overshoot.
        val base = if (seen > 0) seen else applied
        if (base == 0.0) return
        val wanted = base * 2.0.pow((stops * GAIN).coerceIn(-MAX_STEP_STOPS, MAX_STEP_STOPS))
        goal = wanted.coerceIn(lowest, highest)
        atLimit = goal != wanted
    }
    /** Center-weighted mean in linear light, like a camera's default metering. */
    private fun brightness(): Double {
        bitmap.getPixels(pixels, 0, METER_W, 0, 0, METER_W, METER_H)
        var sum = 0.0
        var weights = 0.0
        for (y in 0 until METER_H) {
            val dy = (y + 0.5) / METER_H - 0.5
            for (x in 0 until METER_W) {
                val dx = (x + 0.5) / METER_W - 0.5
                val w = WEIGHT_EDGE + exp(-(dx * dx + dy * dy) / WEIGHT_SPREAD)
                val c = pixels[y * METER_W + x]
                val lum = 0.2126 * LINEAR[c shr 16 and 0xFF] + 0.7152 * LINEAR[c shr 8 and 0xFF] + 0.0722 * LINEAR[c and 0xFF]
                sum += w * lum
                weights += w
            }
        }
        return sum / weights
    }

    companion object {
        private const val METER_W = 32
        private const val METER_H = 24
        private const val INTERVAL_MS = 100L
        private const val FIRST_METER_DELAY_MS = 600L

        /**
         * Linear mean the camera's own AE lands on in the HLG preview. Measured on the 15 Ultra:
         * AE chose 1/33 · ISO 1687, the same light as 1/60 · ISO 3200, which read 0.11 here.
         */
        private const val TARGET_HLG = 0.11

        /**
         * Apple Log, shown as if it were HLG, reads brighter, and one stop moves the reading only
         * ~0.6 stops. Measured like [TARGET_HLG]: the camera's AE exposes Log exactly like HLG.
         */
        private const val TARGET_LOG = 0.22
        private const val LOG_STOPS_PER_STOP = 0.6

        /**
         * Log gets this much less light than the camera's AE would give it. Exposed like HLG, a
         * white wall read 0.75 instead of Apple Log's 0.68, an average room put grey ~2 stops over
         * the nominal 0.49, and skies and lamps hit the curve's top at 0.91. One stop less keeps
         * 4 of the 5 stops of headroom; the shadows still have plenty of 10-bit codes.
         */
        const val LOG_OFFSET_STOPS = -1.0

        /** The camera's AE gives SDR about 0.4 stops less light than HLG; this matches it. */
        private const val TARGET_709 = 0.24

        /**
         * Same measurement with our own curves. Pure BT.2100 HLG reads brighter than Xiaomi's
         * (0.11 left the loop 0.87 stops under the camera's AE); our Rec.709 is matched to Xiaomi's.
         */
        private const val TARGET_HLG_OWN = 0.20
        private const val TARGET_709_OWN = 0.24
        private const val DEADBAND_STOPS = 0.12

        /** Below 1: the preview isn't linear light, so a full correction would overshoot. */
        private const val GAIN = 0.8
        private const val MAX_STEP_STOPS = 2.0

        /**
         * Fast but stepless: the glide covers about 63 % of the way in this time, a 2-stop change
         * settles in about a third of a second, yet each frame moves only a fraction of a stop.
         */
        private const val EASE_S = 0.12
        private const val MAX_STOPS_PER_S = 6.0
        private const val SNAP_STOPS = 0.01

        /** Edges still count a little; the center counts about five times as much. */
        private const val WEIGHT_EDGE = 0.25
        private const val WEIGHT_SPREAD = 0.06

        /** ISO 400 at 1/50 s: a guess for indoor light until the first frame. */
        private const val DEFAULT_PRODUCT = 400.0 * 20_000_000

        private val LINEAR = DoubleArray(256) { i ->
            val v = i / 255.0
            if (v <= 0.04045) v / 12.92 else ((v + 0.055) / 1.055).pow(2.4)
        }
    }
}
