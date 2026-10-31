package com.eddyjunge.latitude.camera

import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.params.DynamicRangeProfiles
import android.media.MediaCodecInfo.CodecProfileLevel
import android.media.MediaRecorder
import android.util.Log
import android.util.Range
import android.util.Size
import android.view.SurfaceHolder

private const val TAG = "VideoConfig"

/** Everything the pipeline needs to know about one recording format on one lens. */
data class VideoConfig(
    val lens: Lens,
    val format: FrameFormat,
    val sensorOrientation: Int,
    val videoSize: Size,
    val previewSize: Size,
    val fps: Int,
    val bitrate: Int,
    /** HEVC levels to try, best first; the level must cover the frame size. */
    val hevcLevels: List<Int>,
    /** True if the camera can deliver 10-bit HLG; may still be dropped if the session rejects it. */
    val hlgSupported: Boolean,
    /** Least processing the ISP offers; null keeps the template default. */
    val noiseReductionMode: Int?,
    val edgeMode: Int?,
    /** Lens has optical stabilization at all. */
    val oisAvailable: Boolean,
    /** Best electronic stabilization mode, or null if the camera has none. */
    val eisMode: Int?,
    /** AF can be switched off and focus held at a fixed distance. */
    val manualFocus: Boolean,
) {
    val label: String
        get() = when (format) {
            FrameFormat.OPEN_GATE -> "OPEN GATE ${videoSize.width}×${videoSize.height}"
            FrameFormat.UHD -> if (videoSize.width >= 3840) "4K" else "${videoSize.height}p"
        } + " · $fps"

    val aspectRatio: Float get() = videoSize.width.toFloat() / videoSize.height

    companion object {
        /** 25p first: matches 50 Hz mains lighting in Europe. */
        private val PREFERRED_FPS = listOf(25, 30)
        private const val BITRATE_4K = 120_000_000

        /** The SM8750 HEVC encoder tops out at 160 Mbit/s; stay clear of the edge. */
        private const val MAX_BITRATE = 150_000_000
        private const val UHD_PIXELS = 3840L * 2160
        private val MAX_UHD = Size(3840, 2160)
        private val MAX_OPEN_GATE = Size(4096, 3072)
        /** 1920x1440 previews render at 75% scale into a corner on the Xiaomi 15 Ultra; 1080 high is safe. */
        private val MAX_PREVIEW = Size(1920, 1080)

        /** HEVC level 5.1 covers up to 8,912,896 luma samples per frame (e.g. 4096x2176). */
        private const val LEVEL_51_MAX_PIXELS = 8_912_896L

        fun select(manager: CameraManager, lens: Lens, format: FrameFormat): VideoConfig {
            val opened = manager.getCameraCharacteristics(lens.openId)
            val chars = manager.getCameraCharacteristics(lens.characteristicsId)
            // A stream bound to a physical camera must use a size that camera supports.
            val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)!!

            val (aspectW, aspectH, max) = when (format) {
                FrameFormat.UHD -> Triple(16, 9, MAX_UHD)
                FrameFormat.OPEN_GATE -> Triple(4, 3, MAX_OPEN_GATE)
            }
            val videoSize = largest(map.getOutputSizes(MediaRecorder::class.java), aspectW, aspectH, max)
            val previewSize = largest(map.getOutputSizes(SurfaceHolder::class.java), aspectW, aspectH, MAX_PREVIEW)

            val fpsRanges = opened.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES).orEmpty()
            val fixed = fpsRanges.filter { it.lower == it.upper }.map { it.upper }
            val fps = PREFERRED_FPS.firstOrNull { it in fixed } ?: fixed.max()

            val hlg = listOf(opened, chars).all {
                it.get(CameraCharacteristics.REQUEST_AVAILABLE_DYNAMIC_RANGE_PROFILES)
                    ?.supportedProfiles?.contains(DynamicRangeProfiles.HLG10) == true
            }

            val nrModes = opened.get(CameraCharacteristics.NOISE_REDUCTION_AVAILABLE_NOISE_REDUCTION_MODES)?.toList().orEmpty()
            val edgeModes = opened.get(CameraCharacteristics.EDGE_AVAILABLE_EDGE_MODES)?.toList().orEmpty()
            val oisModes = chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION)?.toList().orEmpty()
            val eisModes = opened.get(CameraCharacteristics.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES)?.toList().orEmpty()
            val afModes = chars.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES)?.toList().orEmpty()
            val minFocus = chars.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0f

            logCapabilities(lens, chars, fpsRanges)

            val pixels = videoSize.width.toLong() * videoSize.height
            val bitrate = (BITRATE_4K * pixels / UHD_PIXELS).coerceAtMost(MAX_BITRATE.toLong()).toInt()
            val hevcLevels = if (pixels <= LEVEL_51_MAX_PIXELS) {
                listOf(CodecProfileLevel.HEVCHighTierLevel51, CodecProfileLevel.HEVCMainTierLevel51)
            } else {
                listOf(CodecProfileLevel.HEVCHighTierLevel6, CodecProfileLevel.HEVCMainTierLevel6)
            }

            return VideoConfig(
                lens = lens,
                format = format,
                sensorOrientation = chars.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90,
                videoSize = videoSize,
                previewSize = previewSize,
                fps = fps,
                bitrate = bitrate,
                hevcLevels = hevcLevels,
                hlgSupported = hlg,
                noiseReductionMode = listOf(
                    CameraMetadata.NOISE_REDUCTION_MODE_MINIMAL,
                    CameraMetadata.NOISE_REDUCTION_MODE_FAST,
                ).firstOrNull { it in nrModes },
                edgeMode = CameraMetadata.EDGE_MODE_OFF.takeIf { it in edgeModes },
                oisAvailable = CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_ON in oisModes,
                // Preview stabilization keeps viewfinder and recording identical; plain ON may not.
                eisMode = listOf(
                    CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_PREVIEW_STABILIZATION,
                    CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_ON,
                ).firstOrNull { it in eisModes },
                manualFocus = CameraMetadata.CONTROL_AF_MODE_OFF in afModes && minFocus > 0f,
            )
        }

        private fun largest(sizes: Array<Size>, aspectW: Int, aspectH: Int, max: Size): Size =
            sizes.filter { it.width * aspectH == it.height * aspectW && it.width <= max.width && it.height <= max.height }
                .maxByOrNull { it.width * it.height }
                ?: sizes.minBy { it.width * it.height }

        /** One-time dump so we can see what the device really offers (adb logcat -s VideoConfig). */
        private fun logCapabilities(lens: Lens, chars: CameraCharacteristics, fps: Array<out Range<Int>>) {
            val level = chars.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL)
            val profiles = chars.get(CameraCharacteristics.REQUEST_AVAILABLE_DYNAMIC_RANGE_PROFILES)?.supportedProfiles
            Log.i(TAG, "lens=${lens.key} ${lens.label} level=$level dynamicRange=$profiles fps=${fps.toList()}")
        }
    }
}
