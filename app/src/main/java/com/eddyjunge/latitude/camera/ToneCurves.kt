package com.eddyjunge.latitude.camera

import android.hardware.camera2.params.TonemapCurve
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Fixed tone curves for every profile. The vendor's own curves differ per lens and follow the
 * scene (measured: 10 % input gives 0.23 on the tele, 0.35 on the front camera), so two lenses
 * never cut together. With our curve the ISP output is a pure function of scene light.
 * Input is linear light, 1.0 = sensor clip.
 */
class ToneCurves(maxPoints: Int) {
    private val points = maxPoints.coerceAtMost(MAX_POINTS)

    val log: TonemapCurve = build { AppleLog.encode(it * 0.18 / GREY) }

    /** BT.2100 HLG straight from scene light, the full sensor range up to peak. */
    val hlg: TonemapCurve = build(::hlgOetf)

    /** Rec.709 with a soft shoulder that brings the sensor clip down to white. */
    val rec709: TonemapCurve = build { rec709Oetf(shoulder(it * 0.18 / GREY_709)) }

    fun of(profile: ColorProfile): TonemapCurve = when (profile) {
        ColorProfile.REC709 -> rec709
        ColorProfile.HLG -> hlg
        ColorProfile.LOG -> log
    }

    /** Evenly spaced points: right whether or not the ISP honors the x values (see [AppleLog]). */
    private fun build(f: (Double) -> Double): TonemapCurve {
        val curve = FloatArray(points * 2)
        for (i in 0 until points) {
            val x = i.toDouble() / (points - 1)
            curve[i * 2] = x.toFloat()
            curve[i * 2 + 1] = f(x).coerceIn(0.0, 1.0).toFloat()
        }
        return TonemapCurve(curve, curve, curve)
    }

    companion object {
        private const val MAX_POINTS = 512

        /** Apple Log mapping: 1.0 (sensor clip) is 5 stops over this grey, as in [AppleLog]. */
        const val GREY = 0.18 / 5.76

        /**
         * Where Rec.709 puts 18 % grey. Measured: with grey at [GREY] the camera's own AE gave a
         * picture 2.1 stops brighter than Xiaomi's Rec.709 at the same exposure; this matches it.
         */
        const val GREY_709 = GREY * 4.4

        private const val SHOULDER_START = 0.4

        fun hlgOetf(e: Double): Double {
            val a = 0.17883277
            val b = 1 - 4 * a
            val c = 0.5 - a * ln(4 * a)
            return if (e <= 1.0 / 12) sqrt(3 * e) else a * ln(12 * e - b) + c
        }

        fun rec709Oetf(l: Double): Double = if (l < 0.018) 4.5 * l else 1.099 * l.pow(0.45) - 0.099

        /** Linear below [SHOULDER_START], then an exponential roll-off towards 1. */
        fun shoulder(s: Double): Double {
            if (s <= SHOULDER_START) return s
            val span = 1 - SHOULDER_START
            return SHOULDER_START + span * (1 - exp(-(s - SHOULDER_START) / span))
        }
    }
}
