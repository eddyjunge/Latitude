package com.eddyjunge.latitude.camera

import android.graphics.Bitmap
import android.os.Handler
import android.util.Log
import android.view.PixelCopy
import android.view.Surface
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.pow

private const val TAG = "WhiteBalance"

/**
 * Automatic white balance for the matched-color pipeline. Once we set gains and matrix ourselves
 * the camera's AWB stops estimating, so we keep track of the light ourselves: a few times per
 * second a tiny copy of the preview is read back and the near-grey areas are pulled towards
 * neutral. The result is a chromaticity, not per-lens gains, so a lens switch keeps the same
 * white. The first estimate comes from the camera's own AWB (see CameraController).
 * Camera thread only.
 */
internal class WhiteBalanceLoop(private val handler: Handler, private val onChange: () -> Unit) {
    /** Current white point; null until the camera's AWB gave a first estimate. */
    var white: Xy? = null

    /** Calibration of the lens in use; the correction happens in its raw space. */
    var sensor: SensorColor? = null

    private var surface: Surface? = null
    private var copying = false
    private val bitmap = Bitmap.createBitmap(METER_W, METER_H, Bitmap.Config.ARGB_8888)
    private val pixels = IntArray(METER_W * METER_H)
    private var lastLog = 0L

    /** Meters [surface] while it is non-null; null holds the white where it is. */
    fun meter(surface: Surface?) {
        if (surface === this.surface) return
        this.surface = surface
        handler.removeCallbacks(tick)
        if (surface != null && !copying) handler.postDelayed(tick, FIRST_METER_DELAY_MS)
    }

    private val tick = Runnable { copy() }

    private fun copy() {
        val source = surface ?: return
        copying = true
        val requested = runCatching {
            PixelCopy.request(source, bitmap, { result ->
                copying = false
                if (surface === source && result == PixelCopy.SUCCESS) adjust()
                surface?.let { handler.postDelayed(tick, INTERVAL_MS) }
            }, handler)
        }
        if (requested.isFailure) {
            copying = false
            handler.postDelayed(tick, INTERVAL_MS)
        }
    }

    private fun adjust() {
        val sensor = sensor ?: return
        val current = white ?: return
        bitmap.getPixels(pixels, 0, METER_W, 0, 0, METER_W, METER_H)
        // Near-grey pixels decide; a scene without any falls back to the average of everything.
        var nr = 0.0; var ng = 0.0; var nb = 0.0; var nearCount = 0
        var ar = 0.0; var ag = 0.0; var ab = 0.0
        for (c in pixels) {
            val r = LINEAR[c shr 16 and 0xFF]
            val g = LINEAR[c shr 8 and 0xFF]
            val b = LINEAR[c and 0xFF]
            val lum = 0.2126 * r + 0.7152 * g + 0.0722 * b
            if (lum < MIN_LUM || maxOf(r, g, b) > MAX_CHANNEL) continue
            ar += r; ag += g; ab += b
            if (abs(ln(r / g)) < NEAR_GREY && abs(ln(b / g)) < NEAR_GREY) {
                nr += r; ng += g; nb += b
                nearCount++
            }
        }
        val near = nearCount >= pixels.size * MIN_NEAR_SHARE
        val (r, g, b) = if (near) Triple(nr, ng, nb) else Triple(ar, ag, ab)
        if (g <= 0 || r <= 0 || b <= 0) return
        val errR = ln(r / g)
        val errB = ln(b / g)
        if (abs(errR) < DEADBAND && abs(errB) < DEADBAND) return
        // Picture too red means the light is redder than assumed: raise the red of the neutral.
        val gain = if (near) GAIN else GAIN / 2
        val neutral = sensor.neutralFor(current)
        neutral[0] *= exp(gain * errR)
        neutral[2] *= exp(gain * errB)
        white = Xy.plausible(sensor.xyFor(neutral))
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastLog > 2000) {
            lastLog = now
            Log.d(TAG, "white=%d K near=%b err r=%.3f b=%.3f".format(white!!.cct.toInt(), near, errR, errB))
        }
        onChange()
    }

    companion object {
        private const val METER_W = 32
        private const val METER_H = 24
        private const val INTERVAL_MS = 250L
        private const val FIRST_METER_DELAY_MS = 600L
        private const val MIN_LUM = 0.03
        private const val MAX_CHANNEL = 0.95

        /** Pixels within ~30 % of neutral count as grey; a correction is never larger than that. */
        private const val NEAR_GREY = 0.3
        private const val MIN_NEAR_SHARE = 0.08
        private const val DEADBAND = 0.01

        /** Fraction of the error corrected per reading: settles in about two seconds. */
        private const val GAIN = 0.3

        private val LINEAR = DoubleArray(256) { i ->
            val v = i / 255.0
            (if (v <= 0.04045) v / 12.92 else ((v + 0.055) / 1.055).pow(2.4)).coerceAtLeast(1e-6)
        }
    }
}
