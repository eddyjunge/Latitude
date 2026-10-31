package com.eddyjunge.latitude.camera

import android.hardware.camera2.params.TonemapCurve
import kotlin.math.log2
import kotlin.math.pow

/**
 * Apple Log through the ISP's tonemap curve. In the HLG10 session the curve replaces the HLG
 * encoding and its input is linear light up to the sensor clip at 1.0 (probe: an identity curve
 * gives a linear image, a flat one a uniform grey), so the stream carries real Apple Log in
 * 10 bit and Rec.2020 primaries.
 */
object AppleLog {
    /**
     * Stops between 18 % grey and the sensor clip. With 5, clipped highlights land at 0.91,
     * like on an iPhone, and the camera's usual exposure puts grey near the nominal 0.49.
     */
    private const val HEADROOM_STOPS = 5.0

    /** Enough points that linear interpolation stays below one 10-bit code everywhere. */
    private const val MAX_POINTS = 512

    /**
     * Evenly spaced points: correct whether or not the ISP honors the x values. Its LUT may
     * assume an even grid; the probe's log-spaced curve came out much darker than predicted.
     */
    fun tonemapCurve(maxPoints: Int): TonemapCurve {
        val points = maxPoints.coerceAtMost(MAX_POINTS)
        val scale = 0.18 * 2.0.pow(HEADROOM_STOPS)
        val curve = FloatArray(points * 2)
        for (i in 0 until points) {
            val x = i.toDouble() / (points - 1)
            curve[i * 2] = x.toFloat()
            curve[i * 2 + 1] = encode(x * scale).coerceIn(0.0, 1.0).toFloat()
        }
        return TonemapCurve(curve, curve, curve)
    }

    /** Apple Log encoding (Apple Log Profile white paper); input is scene-linear reflectance. */
    fun encode(r: Double): Double {
        val r0 = -0.05641088
        val rt = 0.01
        val c = 47.28711236
        val beta = 0.00964052
        val gamma = 0.08550479
        val delta = 0.69336945
        return when {
            r < r0 -> 0.0
            r < rt -> c * (r - r0) * (r - r0)
            else -> gamma * log2(r + beta) + delta
        }
    }
}
