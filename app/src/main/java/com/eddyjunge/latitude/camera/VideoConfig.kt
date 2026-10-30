package com.eddyjunge.latitude.camera

import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.params.DynamicRangeProfiles
import android.media.MediaRecorder
import android.util.Log
import android.util.Range
import android.util.Size
import android.view.SurfaceHolder

private const val TAG = "VideoConfig"

/** Everything the pipeline needs to know about one recording format. */
data class VideoConfig(
    val cameraId: String,
    val sensorOrientation: Int,
    val videoSize: Size,
    val previewSize: Size,
    val fps: Int,
    val bitrate: Int,
    /** True if the camera can deliver 10-bit HLG; may still be dropped if the session rejects it. */
    val hlgSupported: Boolean,
    /** Least processing the ISP offers; null keeps the template default. */
    val noiseReductionMode: Int?,
    val edgeMode: Int?,
    val opticalStabilization: Boolean,
    /** AF can be switched off and focus held at a fixed distance. */
    val manualFocus: Boolean,
) {
    val label: String
        get() = "${if (videoSize.width >= 3840) "4K" else "${videoSize.height}p"} · $fps"

    companion object {
        /** 25p first: matches 50 Hz mains lighting in Europe. */
        private val PREFERRED_FPS = listOf(25, 30)
        private const val BITRATE_4K = 120_000_000
        private val MAX_VIDEO = Size(3840, 2160)
        private val MAX_PREVIEW = Size(1920, 1080)

        /** Picks the main back camera and the best 16:9 format up to 4K. */
        fun select(manager: CameraManager): VideoConfig {
            val cameraId = manager.cameraIdList.first { id ->
                manager.getCameraCharacteristics(id)
                    .get(CameraCharacteristics.LENS_FACING) == CameraMetadata.LENS_FACING_BACK
            }
            val chars = manager.getCameraCharacteristics(cameraId)
            val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)!!

            val videoSize = largest16by9(map.getOutputSizes(MediaRecorder::class.java), MAX_VIDEO)
            val previewSize = largest16by9(map.getOutputSizes(SurfaceHolder::class.java), MAX_PREVIEW)

            val fpsRanges = chars.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES).orEmpty()
            val fixed = fpsRanges.filter { it.lower == it.upper }.map { it.upper }
            val fps = PREFERRED_FPS.firstOrNull { it in fixed } ?: fixed.max()

            val hlg = chars.get(CameraCharacteristics.REQUEST_AVAILABLE_DYNAMIC_RANGE_PROFILES)
                ?.supportedProfiles?.contains(DynamicRangeProfiles.HLG10) == true

            val nrModes = chars.get(CameraCharacteristics.NOISE_REDUCTION_AVAILABLE_NOISE_REDUCTION_MODES)?.toList().orEmpty()
            val edgeModes = chars.get(CameraCharacteristics.EDGE_AVAILABLE_EDGE_MODES)?.toList().orEmpty()
            val oisModes = chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION)?.toList().orEmpty()
            val afModes = chars.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES)?.toList().orEmpty()
            val minFocus = chars.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0f

            logCapabilities(cameraId, chars, fpsRanges)

            val pixels = videoSize.width.toLong() * videoSize.height
            val bitrate = (BITRATE_4K * pixels / (3840L * 2160)).toInt()

            return VideoConfig(
                cameraId = cameraId,
                sensorOrientation = chars.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90,
                videoSize = videoSize,
                previewSize = previewSize,
                fps = fps,
                bitrate = bitrate,
                hlgSupported = hlg,
                noiseReductionMode = listOf(
                    CameraMetadata.NOISE_REDUCTION_MODE_MINIMAL,
                    CameraMetadata.NOISE_REDUCTION_MODE_FAST,
                ).firstOrNull { it in nrModes },
                edgeMode = CameraMetadata.EDGE_MODE_OFF.takeIf { it in edgeModes },
                opticalStabilization = CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_ON in oisModes,
                manualFocus = CameraMetadata.CONTROL_AF_MODE_OFF in afModes && minFocus > 0f,
            )
        }

        private fun largest16by9(sizes: Array<Size>, max: Size): Size =
            sizes.filter { it.width * 9 == it.height * 16 && it.width <= max.width && it.height <= max.height }
                .maxByOrNull { it.width * it.height }
                ?: sizes.minBy { it.width * it.height }

        /** One-time dump so we can see what the device really offers (adb logcat -s VideoConfig). */
        private fun logCapabilities(id: String, chars: CameraCharacteristics, fps: Array<out Range<Int>>) {
            val level = chars.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL)
            val caps = chars.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)?.toList()
            val profiles = chars.get(CameraCharacteristics.REQUEST_AVAILABLE_DYNAMIC_RANGE_PROFILES)?.supportedProfiles
            val tonemap = chars.get(CameraCharacteristics.TONEMAP_AVAILABLE_TONE_MAP_MODES)?.toList()
            Log.i(TAG, "camera=$id level=$level caps=$caps")
            Log.i(TAG, "dynamicRange=$profiles tonemapModes=$tonemap fps=${fps.toList()}")
        }
    }
}
