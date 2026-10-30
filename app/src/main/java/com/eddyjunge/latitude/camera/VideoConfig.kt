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
) {
    val label: String
        get() = "${if (videoSize.width >= 3840) "4K" else "${videoSize.height}p"} · $fps"

    companion object {
        private const val TARGET_FPS = 30
        private const val BITRATE_4K = 100_000_000
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
            val fps = if (fpsRanges.any { it.lower == TARGET_FPS && it.upper == TARGET_FPS }) TARGET_FPS
            else fpsRanges.filter { it.lower == it.upper }.maxOf { it.upper }

            val hlg = chars.get(CameraCharacteristics.REQUEST_AVAILABLE_DYNAMIC_RANGE_PROFILES)
                ?.supportedProfiles?.contains(DynamicRangeProfiles.HLG10) == true

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
