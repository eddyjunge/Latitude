package com.eddyjunge.latitude.camera

import android.content.Context

enum class FrameFormat(val label: String) {
    /** 16:9 UHD, ready to edit. */
    UHD("16:9"),

    /** Full 4:3 sensor readout, crop to any aspect ratio in post. */
    OPEN_GATE("OPEN GATE"),
}

enum class Stabilization(val label: String) {
    OFF("STAB AUS"),

    /** Optical only: no crop, no warping. Falls back to OFF on lenses without OIS. */
    OPTICAL("OIS"),

    /** OIS plus the camera's electronic stabilization, which crops a little. */
    STANDARD("OIS + EIS"),
}

/** What the user picked. Survives app restarts. */
data class CaptureSettings(
    val lensKey: String? = null,
    val format: FrameFormat = FrameFormat.UHD,
    val stabilization: Stabilization = Stabilization.OPTICAL,
) {
    fun save(context: Context) {
        prefs(context).edit()
            .putString(KEY_LENS, lensKey)
            .putString(KEY_FORMAT, format.name)
            .putString(KEY_STAB, stabilization.name)
            .apply()
    }

    companion object {
        private const val KEY_LENS = "lens"
        private const val KEY_FORMAT = "format"
        private const val KEY_STAB = "stabilization"

        private fun prefs(context: Context) = context.getSharedPreferences("capture", Context.MODE_PRIVATE)

        fun load(context: Context): CaptureSettings {
            val p = prefs(context)
            return CaptureSettings(
                lensKey = p.getString(KEY_LENS, null),
                format = enumOrDefault(p.getString(KEY_FORMAT, null), FrameFormat.UHD),
                stabilization = enumOrDefault(p.getString(KEY_STAB, null), Stabilization.OPTICAL),
            )
        }

        private inline fun <reified T : Enum<T>> enumOrDefault(name: String?, default: T): T =
            enumValues<T>().firstOrNull { it.name == name } ?: default
    }
}
