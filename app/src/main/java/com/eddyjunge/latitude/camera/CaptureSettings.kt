package com.eddyjunge.latitude.camera

import android.content.Context

enum class FrameFormat {
    /** 16:9 UHD, ready to edit. */
    UHD,

    /** Full 4:3 sensor readout, crop to any aspect ratio in post. */
    OPEN_GATE,
}

enum class ColorProfile {
    /** 8-bit SDR: looks finished straight away, little room for grading. */
    REC709,

    /** 10-bit HLG: HDR that looks right on HDR screens and still grades well. */
    HLG,

    /** 10-bit Apple Log in Rec.2020: flat, the most room for grading. */
    LOG,
    ;

    val tenBit: Boolean get() = this != REC709
}

enum class Stabilization {
    OFF,

    /** Optical only: no crop, no warping. Falls back to OFF on lenses without OIS. */
    OPTICAL,

    /** OIS plus the camera's electronic stabilization, which crops a little. */
    STANDARD,
}

/** Shutter value: the camera picks the exposure time. */
const val SHUTTER_AUTO = 0

/** Shutter value: 180° rule, always half the frame time (1/50 at 25 fps). */
const val SHUTTER_180 = -1

/** ISO value: brightness is regulated through the ISO. */
const val ISO_AUTO = 0

/** White balance value: follows the light. Otherwise a color temperature in Kelvin. */
const val WB_AUTO = 0

/** What the user picked. Survives app restarts. */
data class CaptureSettings(
    val lensKey: String? = null,
    val format: FrameFormat = FrameFormat.UHD,
    val colorProfile: ColorProfile = ColorProfile.HLG,
    val stabilization: Stabilization = Stabilization.OPTICAL,
    val mic: MicSource = MicSource.INTERNAL,
    val micProcessing: MicProcessing = MicProcessing.STANDARD,
    /** 1/x seconds, or [SHUTTER_AUTO] / [SHUTTER_180]. */
    val shutter: Int = SHUTTER_180,
    /** Fixed sensitivity, or [ISO_AUTO]. */
    val iso: Int = ISO_AUTO,
    /** Kelvin, or [WB_AUTO]. */
    val whiteBalance: Int = WB_AUTO,
    /** Our own colors, identical on every lens, instead of the vendor's per-lens tuning. */
    val matchColors: Boolean = true,
) {
    /**
     * Only these need a new camera session; audio is picked per recording. HLG and Log share
     * the 10-bit session and differ only in the tonemap curve of the request.
     */
    fun sameCamera(other: CaptureSettings) =
        lensKey == other.lensKey && format == other.format && stabilization == other.stabilization &&
            colorProfile.tenBit == other.colorProfile.tenBit

    /** Exposure and white balance are only request parameters, so they may change mid-take. */
    fun onlyExposureDiffers(other: CaptureSettings) =
        copy(shutter = other.shutter, iso = other.iso, whiteBalance = other.whiteBalance) == other

    fun save(context: Context) {
        prefs(context).edit()
            .putString(KEY_LENS, lensKey)
            .putString(KEY_FORMAT, format.name)
            .putString(KEY_COLOR, colorProfile.name)
            .putString(KEY_STAB, stabilization.name)
            .putString(KEY_MIC, mic.name)
            .putString(KEY_MIC_PROCESSING, micProcessing.name)
            .putInt(KEY_SHUTTER, shutter)
            .putInt(KEY_ISO, iso)
            .putInt(KEY_WB, whiteBalance)
            .putBoolean(KEY_MATCH, matchColors)
            .apply()
    }

    companion object {
        private const val KEY_LENS = "lens"
        private const val KEY_FORMAT = "format"
        private const val KEY_COLOR = "colorProfile"
        private const val KEY_STAB = "stabilization"
        private const val KEY_MIC = "mic"
        private const val KEY_MIC_PROCESSING = "micProcessing"
        private const val KEY_SHUTTER = "shutter"
        private const val KEY_ISO = "iso"
        private const val KEY_WB = "whiteBalance"
        private const val KEY_MATCH = "matchColors"

        private fun prefs(context: Context) = context.getSharedPreferences("capture", Context.MODE_PRIVATE)

        fun load(context: Context): CaptureSettings {
            val p = prefs(context)
            return CaptureSettings(
                lensKey = p.getString(KEY_LENS, null),
                format = enumOrDefault(p.getString(KEY_FORMAT, null), FrameFormat.UHD),
                colorProfile = enumOrDefault(p.getString(KEY_COLOR, null), ColorProfile.HLG),
                stabilization = enumOrDefault(p.getString(KEY_STAB, null), Stabilization.OPTICAL),
                mic = enumOrDefault(p.getString(KEY_MIC, null), MicSource.INTERNAL),
                micProcessing = enumOrDefault(p.getString(KEY_MIC_PROCESSING, null), MicProcessing.STANDARD),
                shutter = p.getInt(KEY_SHUTTER, SHUTTER_180),
                iso = p.getInt(KEY_ISO, ISO_AUTO),
                whiteBalance = p.getInt(KEY_WB, WB_AUTO),
                matchColors = p.getBoolean(KEY_MATCH, true),
            )
        }

        private inline fun <reified T : Enum<T>> enumOrDefault(name: String?, default: T): T =
            enumValues<T>().firstOrNull { it.name == name } ?: default
    }
}
