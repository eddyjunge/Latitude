package com.eddyjunge.latitude.camera

import android.content.Context

/**
 * Our own white measurement per lens on top of the factory calibration. The factory matrices
 * of the Xiaomi 15 Ultra disagree between lenses: under one lamp the 14, 69 and 95 mm agreed on
 * ~2500 K, the main sensor read 3200 K and clearly green. So a white wall would still change
 * color with the lens. One shot of an evenly lit surface with every lens fixes that: each lens
 * gets the raw scale that makes it see the light the lenses agree on (the median).
 */
object ColorCalibration {
    private const val PREFS = "colorCalibration"

    fun load(context: Context, cameraId: String): DoubleArray? =
        prefs(context).getString(cameraId, null)
            ?.split(',')?.mapNotNull { it.toDoubleOrNull() }
            ?.takeIf { it.size == 3 }?.toDoubleArray()

    fun save(context: Context, scales: Map<String, DoubleArray>) {
        val edit = prefs(context).edit().clear()
        scales.forEach { (id, s) -> edit.putString(id, s.joinToString(",")) }
        edit.apply()
    }

    /**
     * [measured] holds each lens's raw neutral (R/G, 1, B/G) of the same surface. The light is the
     * median of what the lenses' factory calibrations make of it, so one bad calibration can't
     * pull the others. Returns the raw scale per camera id.
     */
    fun compute(measured: Map<String, DoubleArray>, factory: Map<String, SensorColor>): Map<String, DoubleArray> {
        val estimates = measured.mapNotNull { (id, raw) -> factory[id]?.xyFor(raw) }
        val light = Xy(median(estimates.map { it.x }), median(estimates.map { it.y }))
        return measured.mapNotNull { (id, raw) ->
            val model = factory[id]?.neutralFor(light) ?: return@mapNotNull null
            id to doubleArrayOf(raw[0] / model[0], 1.0, raw[2] / model[2])
        }.toMap()
    }

    private fun median(values: List<Double>): Double {
        val sorted = values.sorted()
        val mid = sorted.size / 2
        return if (sorted.size % 2 == 1) sorted[mid] else (sorted[mid - 1] + sorted[mid]) / 2
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
