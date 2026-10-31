package com.eddyjunge.latitude.camera

import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.params.ColorSpaceTransform
import android.hardware.camera2.params.RggbChannelVector
import kotlin.math.pow
import kotlin.math.roundToInt

/** Output primaries the color matrix maps into. */
enum class Primaries { BT709, BT2020 }

/** White balance gains and color matrix for one sensor, ready for a capture request. */
class ColorCorrection(val gains: RggbChannelVector, val transform: ColorSpaceTransform)

/**
 * Same colors on every lens. Each sensor ships its own DNG-style calibration (color matrices
 * for two reference illuminants, forward matrices, per-unit calibration). From that we build
 * white balance gains and a 3×3 matrix that take this sensor's raw RGB to one common space,
 * instead of the vendor's per-lens tuning. A white point given as chromaticity [xy] then means
 * the same thing on every lens.
 */
class SensorColor private constructor(
    private val colorMatrix1: Mat3,
    private val colorMatrix2: Mat3,
    private val forward1: Mat3,
    private val forward2: Mat3,
    private val calibration1: Mat3,
    private val calibration2: Mat3,
    private val cct1: Double,
    private val cct2: Double,
) {
    /** Raw RGB this sensor records for a white surface under light of chromaticity [xy], green = 1. */
    fun neutralFor(xy: Xy): DoubleArray {
        val n = cameraFromXyz(xy.cct) * xy.toXyz()
        return doubleArrayOf(n[0] / n[1], 1.0, n[2] / n[1])
    }

    /**
     * The light's chromaticity from a raw neutral (e.g. the camera's own AWB estimate). Iterative,
     * as in the DNG spec: the color matrix to use depends on the very temperature we look for.
     */
    fun xyFor(neutral: DoubleArray): Xy {
        var xy = Xy.D50
        repeat(20) {
            val xyz = cameraFromXyz(xy.cct).inverse() * neutral
            val next = Xy.ofXyz(xyz)
            if (kotlin.math.abs(next.x - xy.x) + kotlin.math.abs(next.y - xy.y) < 1e-6) return next
            xy = next
        }
        return xy
    }

    /** Gains that make a white surface under [light] neutral, and the matrix into [primaries]. */
    fun correction(light: Xy, primaries: Primaries): ColorCorrection {
        val t = light.cct
        val neutral = neutralFor(light)
        val calibration = interpolate(calibration1, calibration2, t)
        val inverseCalibration = calibration.inverse()
        val reference = inverseCalibration * neutral
        // White-balanced raw RGB -> XYZ (D50) -> linear RGB in the output primaries (D65 white).
        val toOutput = when (primaries) {
            Primaries.BT709 -> XYZ_TO_709
            Primaries.BT2020 -> XYZ_TO_2020
        } * D50_TO_D65
        val matrix = toOutput * interpolate(forward1, forward2, t) *
            Mat3.diagonal(1 / reference[0], 1 / reference[1], 1 / reference[2]) *
            inverseCalibration * Mat3.diagonal(neutral[0], neutral[1], neutral[2])
        val gains = RggbChannelVector(
            (1 / neutral[0]).toFloat(), 1f, 1f, (1 / neutral[2]).toFloat(),
        )
        return ColorCorrection(gains, matrix.toTransform())
    }

    private fun cameraFromXyz(cct: Double): Mat3 = interpolate(colorMatrix1, colorMatrix2, cct)

    /** DNG rule: weight by inverse color temperature, clamped to the two reference illuminants. */
    private fun interpolate(m1: Mat3, m2: Mat3, cct: Double): Mat3 {
        if (cct1 == cct2) return m1
        val w = ((1 / cct - 1 / cct2) / (1 / cct1 - 1 / cct2)).coerceIn(0.0, 1.0)
        return m1 * w + m2 * (1 - w)
    }

    companion object {
        /**
         * Null if the sensor doesn't publish a calibration; then the vendor's colors stay.
         * [rawScale] is our own measurement on top of the factory data (see [ColorCalibration]):
         * how much this unit's raw R, G, B differ from what its matrices predict.
         */
        fun of(chars: CameraCharacteristics, rawScale: DoubleArray? = null): SensorColor? {
            val cm1 = chars.get(CameraCharacteristics.SENSOR_COLOR_TRANSFORM1) ?: return null
            val fm1 = chars.get(CameraCharacteristics.SENSOR_FORWARD_MATRIX1) ?: return null
            val ill1 = chars.get(CameraCharacteristics.SENSOR_REFERENCE_ILLUMINANT1) ?: return null
            val scale = rawScale?.let { Mat3.diagonal(it[0], it[1], it[2]) } ?: Mat3.IDENTITY
            val cc1 = scale * (chars.get(CameraCharacteristics.SENSOR_CALIBRATION_TRANSFORM1)?.let(Mat3::of) ?: Mat3.IDENTITY)
            val cm2 = chars.get(CameraCharacteristics.SENSOR_COLOR_TRANSFORM2)
            val ill2 = chars.get(CameraCharacteristics.SENSOR_REFERENCE_ILLUMINANT2)?.toInt()
            val two = cm2 != null && ill2 != null
            val cc2 = chars.get(CameraCharacteristics.SENSOR_CALIBRATION_TRANSFORM2)?.let { scale * Mat3.of(it) } ?: cc1
            val c1 = cc1 * Mat3.of(cm1)
            return SensorColor(
                colorMatrix1 = c1,
                colorMatrix2 = if (two) cc2 * Mat3.of(cm2!!) else c1,
                forward1 = Mat3.of(fm1),
                forward2 = chars.get(CameraCharacteristics.SENSOR_FORWARD_MATRIX2)?.takeIf { two }?.let(Mat3::of)
                    ?: Mat3.of(fm1),
                calibration1 = cc1,
                calibration2 = if (two) cc2 else cc1,
                cct1 = illuminantCct(ill1),
                cct2 = if (two) illuminantCct(ill2!!) else illuminantCct(ill1),
            )
        }

        /** Color temperature of the EXIF LightSource values camera2 uses for reference illuminants. */
        private fun illuminantCct(illuminant: Int): Double = when (illuminant) {
            17, 3 -> 2856.0 // standard A, tungsten
            24 -> 3200.0 // ISO studio tungsten
            15 -> 3450.0 // white fluorescent
            2, 14 -> 4150.0 // fluorescent, cool white fluorescent
            18 -> 4874.0 // standard B
            13, 23 -> 5003.0 // day white fluorescent, D50
            20, 1, 4, 9 -> 5503.0 // D55, daylight, flash, fine weather
            12 -> 6430.0 // daylight fluorescent
            21, 10 -> 6504.0 // D65, cloudy
            19 -> 6774.0 // standard C
            22, 11 -> 7504.0 // D75, shade
            else -> 5003.0
        }

        private val D50_TO_D65 = bradford(Xy.D50, Xy.D65)

        private val XYZ_TO_709 = Mat3(
            3.2404542, -1.5371385, -0.4985314,
            -0.9692660, 1.8760108, 0.0415560,
            0.0556434, -0.2040259, 1.0572252,
        )
        private val XYZ_TO_2020 = Mat3(
            1.7166512, -0.3556708, -0.2533663,
            -0.6666844, 1.6164812, 0.0157685,
            0.0176399, -0.0427706, 0.9421031,
        )

        private fun bradford(from: Xy, to: Xy): Mat3 {
            val b = Mat3(0.8951, 0.2664, -0.1614, -0.7502, 1.7135, 0.0367, 0.0389, -0.0685, 1.0296)
            val s = b * from.toXyz()
            val d = b * to.toXyz()
            return b.inverse() * Mat3.diagonal(d[0] / s[0], d[1] / s[1], d[2] / s[2]) * b
        }
    }
}

/** CIE 1931 chromaticity of a light source. */
data class Xy(val x: Double, val y: Double) {
    fun toXyz() = doubleArrayOf(x / y, 1.0, (1 - x - y) / y)

    /** Correlated color temperature (McCamy); good enough to weight two calibration matrices. */
    val cct: Double
        get() {
            val n = (x - 0.3320) / (0.1858 - y)
            return (449 * n.pow(3) + 3525 * n * n + 6823.3 * n + 5520.33).coerceIn(1500.0, 20000.0)
        }

    companion object {
        val D50 = Xy(0.3457, 0.3585)
        val D65 = Xy(0.3127, 0.3290)

        /** CIE 1960 u, v: the space in which "distance from the Planckian locus" is measured. */
        private fun uv(xy: Xy): Pair<Double, Double> {
            val d = -2 * xy.x + 12 * xy.y + 3
            return 4 * xy.x / d to 6 * xy.y / d
        }

        private fun ofUv(u: Double, v: Double): Xy {
            val d = 2 * u - 8 * v + 4
            return Xy(3 * u / d, 2 * v / d)
        }

        /**
         * Keeps an automatic white point plausible: color temperature within [minK]..[maxK] and
         * at most [maxDuv] off the Planckian locus, so a green lawn can't turn the picture magenta.
         */
        fun plausible(xy: Xy, minK: Int = 2000, maxK: Int = 10000, maxDuv: Double = 0.008): Xy {
            val locus = ofKelvin(xy.cct.toInt().coerceIn(minK, maxK))
            val (u, v) = uv(xy)
            val (ul, vl) = uv(locus)
            val du = u - ul
            val dv = v - vl
            val length = kotlin.math.hypot(du, dv)
            val k = if (length > maxDuv) maxDuv / length else 1.0
            return ofUv(ul + du * k, vl + dv * k)
        }

        fun ofXyz(xyz: DoubleArray): Xy {
            val sum = xyz[0] + xyz[1] + xyz[2]
            return Xy(xyz[0] / sum, xyz[1] / sum)
        }

        /** A point on the Planckian locus (Kim et al. approximation), 1667–25000 K. */
        fun ofKelvin(kelvin: Int): Xy {
            val t = kelvin.toDouble().coerceIn(1667.0, 25000.0)
            val x = if (t <= 4000) {
                -0.2661239e9 / t.pow(3) - 0.2343589e6 / t.pow(2) + 0.8776956e3 / t + 0.179910
            } else {
                -3.0258469e9 / t.pow(3) + 2.1070379e6 / t.pow(2) + 0.2226347e3 / t + 0.240390
            }
            val y = when {
                t <= 2222 -> -1.1063814 * x.pow(3) - 1.34811020 * x * x + 2.18555832 * x - 0.20219683
                t <= 4000 -> -0.9549476 * x.pow(3) - 1.37418593 * x * x + 2.09137015 * x - 0.16748867
                else -> 3.0817580 * x.pow(3) - 5.87338670 * x * x + 3.75112997 * x - 0.37001483
            }
            return Xy(x, y)
        }
    }
}

/** Row-major 3×3 matrix, just what the color math needs. */
class Mat3(vararg values: Double) {
    private val m = values.copyOf().also { require(it.size == 9) }

    operator fun get(r: Int, c: Int) = m[r * 3 + c]

    operator fun times(o: Mat3) = Mat3(*DoubleArray(9) { i ->
        val r = i / 3
        val c = i % 3
        this[r, 0] * o[0, c] + this[r, 1] * o[1, c] + this[r, 2] * o[2, c]
    })

    operator fun times(v: DoubleArray) = DoubleArray(3) { r -> this[r, 0] * v[0] + this[r, 1] * v[1] + this[r, 2] * v[2] }

    operator fun times(s: Double) = Mat3(*DoubleArray(9) { m[it] * s })

    operator fun plus(o: Mat3) = Mat3(*DoubleArray(9) { m[it] + o.m[it] })

    fun inverse(): Mat3 {
        val a = this
        val det = a[0, 0] * (a[1, 1] * a[2, 2] - a[1, 2] * a[2, 1]) -
            a[0, 1] * (a[1, 0] * a[2, 2] - a[1, 2] * a[2, 0]) +
            a[0, 2] * (a[1, 0] * a[2, 1] - a[1, 1] * a[2, 0])
        return Mat3(
            a[1, 1] * a[2, 2] - a[1, 2] * a[2, 1], a[0, 2] * a[2, 1] - a[0, 1] * a[2, 2], a[0, 1] * a[1, 2] - a[0, 2] * a[1, 1],
            a[1, 2] * a[2, 0] - a[1, 0] * a[2, 2], a[0, 0] * a[2, 2] - a[0, 2] * a[2, 0], a[0, 2] * a[1, 0] - a[0, 0] * a[1, 2],
            a[1, 0] * a[2, 1] - a[1, 1] * a[2, 0], a[0, 1] * a[2, 0] - a[0, 0] * a[2, 1], a[0, 0] * a[1, 1] - a[0, 1] * a[1, 0],
        ) * (1 / det)
    }

    /** Camera2 wants rationals; 1/10000 is far below what the ISP resolves. */
    fun toTransform() = ColorSpaceTransform(IntArray(18) { i ->
        if (i % 2 == 0) (m[i / 2] * DENOMINATOR).roundToInt() else DENOMINATOR
    })

    override fun toString() = m.joinToString(prefix = "[", postfix = "]") { "%.3f".format(it) }

    companion object {
        private const val DENOMINATOR = 10000
        val IDENTITY = Mat3(1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0)

        fun diagonal(a: Double, b: Double, c: Double) = Mat3(a, 0.0, 0.0, 0.0, b, 0.0, 0.0, 0.0, c)

        fun of(t: ColorSpaceTransform) = Mat3(*DoubleArray(9) { i -> t.getElement(i % 3, i / 3).toDouble() })
    }
}
