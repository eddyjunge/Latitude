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
    val mic: MicSource = MicSource.INTERNAL,
    val micProcessing: MicProcessing = MicProcessing.STANDARD,
) {
    /** Only these need a new camera session; audio is picked per recording. */
    fun sameCamera(other: CaptureSettings) =
        lensKey == other.lensKey && format == other.format && stabilization == other.stabilization

    fun save(context: Context) {
        prefs(context).edit()
            .putString(KEY_LENS, lensKey)
            .putString(KEY_FORMAT, format.name)
            .putString(KEY_STAB, stabilization.name)
            .putString(KEY_MIC, mic.name)
            .putString(KEY_MIC_PROCESSING, micProcessing.name)
            .apply()
    }

    companion object {
        private const val KEY_LENS = "lens"
        private const val KEY_FORMAT = "format"
        private const val KEY_STAB = "stabilization"
        private const val KEY_MIC = "mic"
        private const val KEY_MIC_PROCESSING = "micProcessing"

        private fun prefs(context: Context) = context.getSharedPreferences("capture", Context.MODE_PRIVATE)

        fun load(context: Context): CaptureSettings {
            val p = prefs(context)
            return CaptureSettings(
                lensKey = p.getString(KEY_LENS, null),
                format = enumOrDefault(p.getString(KEY_FORMAT, null), FrameFormat.UHD),
                stabilization = enumOrDefault(p.getString(KEY_STAB, null), Stabilization.OPTICAL),
                mic = enumOrDefault(p.getString(KEY_MIC, null), MicSource.INTERNAL),
                micProcessing = enumOrDefault(p.getString(KEY_MIC_PROCESSING, null), MicProcessing.STANDARD),
            )
        }

        private inline fun <reified T : Enum<T>> enumOrDefault(name: String?, default: T): T =
            enumValues<T>().firstOrNull { it.name == name } ?: default
    }
}
