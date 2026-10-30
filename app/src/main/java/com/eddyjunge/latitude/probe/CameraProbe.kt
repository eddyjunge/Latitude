package com.eddyjunge.latitude.probe

import android.graphics.ImageFormat
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.params.DynamicRangeProfiles
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.hardware.camera2.params.StreamConfigurationMap
import android.media.MediaCodec
import android.os.Build
import android.util.Range
import android.util.Size
import android.view.SurfaceHolder
import org.json.JSONArray
import org.json.JSONObject

/** Static camera capabilities; never opens a camera. */
internal class CameraProbe(private val manager: CameraManager) {

    fun run(): JSONObject {
        val listed = manager.cameraIdList.toList()
        val seen = mutableSetOf<String>()
        val cameras = JSONArray()

        for (id in listed) {
            seen += id
            cameras.put(describe(id, "listed"))
            for (physical in manager.getCameraCharacteristics(id).physicalCameraIds) {
                if (seen.add(physical)) cameras.put(describe(physical, "physical-of-$id"))
            }
        }

        // HyperOS may hide lenses from cameraIdList while still answering direct queries.
        val hidden = JSONArray()
        for (n in 0 until 64) {
            val id = n.toString()
            if (id in seen) continue
            if (runCatching { manager.getCameraCharacteristics(id) }.isFailure) continue
            seen += id
            hidden.put(id)
            cameras.put(describe(id, "hidden"))
        }

        return JSONObject()
            .put("cameraIdList", JSONArray(listed))
            .put("hiddenIds", hidden)
            .put("cameras", cameras)
    }

    private fun describe(id: String, source: String): JSONObject = try {
        val c = manager.getCameraCharacteristics(id)
        JSONObject().apply {
            put("id", id)
            put("source", source)
            put("facing", Names.of(Names.lensFacing, c[CameraCharacteristics.LENS_FACING]))
            put("hardwareLevel", Names.of(Names.hardwareLevel, c[CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL]))
            put("capabilities", Names.list(Names.capability, c[CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES]))
            put("physicalCameraIds", JSONArray(c.physicalCameraIds.toList()))
            put("sensorOrientation", c[CameraCharacteristics.SENSOR_ORIENTATION])
            put("optics", optics(c))
            put("sensor", sensor(c))
            put("modes", modes(c))
            put("dynamicRange", dynamicRange(c))
            put("tonemap", JSONObject()
                .put("maxCurvePoints", c[CameraCharacteristics.TONEMAP_MAX_CURVE_POINTS])
                .put("modes", Names.list(Names.tonemap, c[CameraCharacteristics.TONEMAP_AVAILABLE_TONE_MAP_MODES])))
            put("fpsRanges", ranges(c[CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES]))
            put("streams", c[CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP]?.let(::streams))
            put("streamsMaxResolution",
                c[CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP_MAXIMUM_RESOLUTION]?.let(::streams))
            put("keys", keys(c))
            if (source == "listed" && Build.VERSION.SDK_INT >= 35) put("sessionSupport", sessionSupport(id, c))
        }
    } catch (e: Exception) {
        JSONObject().put("id", id).put("source", source).put("error", e.toString())
    }

    private fun optics(c: CameraCharacteristics) = JSONObject().apply {
        put("focalLengthsMm", floats(c[CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS]))
        put("apertures", floats(c[CameraCharacteristics.LENS_INFO_AVAILABLE_APERTURES]))
        put("ndFilters", floats(c[CameraCharacteristics.LENS_INFO_AVAILABLE_FILTER_DENSITIES]))
        put("minFocusDistanceDiopters", c[CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE])
        put("hyperfocalDiopters", c[CameraCharacteristics.LENS_INFO_HYPERFOCAL_DISTANCE])
        put("focusCalibration", Names.of(Names.focusCalibration, c[CameraCharacteristics.LENS_INFO_FOCUS_DISTANCE_CALIBRATION]))
        put("oisModes", Names.list(Names.onOff, c[CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION]))
        put("zoomRatioRange", c[CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE]?.toString())
        put("maxDigitalZoom", c[CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM])
        put("distortion", floats(c[CameraCharacteristics.LENS_DISTORTION]))
    }

    private fun sensor(c: CameraCharacteristics) = JSONObject().apply {
        put("isoRange", c[CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE]?.toString())
        put("maxAnalogIso", c[CameraCharacteristics.SENSOR_MAX_ANALOG_SENSITIVITY])
        put("exposureTimeRangeNs", c[CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE]?.toString())
        put("maxFrameDurationNs", c[CameraCharacteristics.SENSOR_INFO_MAX_FRAME_DURATION])
        put("physicalSizeMm", c[CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE]?.toString())
        put("pixelArray", c[CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE]?.toString())
        put("activeArray", c[CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE]?.toString())
        put("colorFilter", Names.of(Names.colorFilter, c[CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT]))
        put("whiteLevel", c[CameraCharacteristics.SENSOR_INFO_WHITE_LEVEL])
        put("blackLevelPattern", c[CameraCharacteristics.SENSOR_BLACK_LEVEL_PATTERN]?.toString())
        put("referenceIlluminant1", c[CameraCharacteristics.SENSOR_REFERENCE_ILLUMINANT1])
        put("referenceIlluminant2", c[CameraCharacteristics.SENSOR_REFERENCE_ILLUMINANT2]?.toInt())
        put("forwardMatrix1", c[CameraCharacteristics.SENSOR_FORWARD_MATRIX1]?.toString())
        put("forwardMatrix2", c[CameraCharacteristics.SENSOR_FORWARD_MATRIX2]?.toString())
        put("colorTransform1", c[CameraCharacteristics.SENSOR_COLOR_TRANSFORM1]?.toString())
        put("colorTransform2", c[CameraCharacteristics.SENSOR_COLOR_TRANSFORM2]?.toString())
        put("calibrationTransform1", c[CameraCharacteristics.SENSOR_CALIBRATION_TRANSFORM1]?.toString())
        put("calibrationTransform2", c[CameraCharacteristics.SENSOR_CALIBRATION_TRANSFORM2]?.toString())
        put("lensShadingMapModes", Names.list(Names.onOff, c[CameraCharacteristics.STATISTICS_INFO_AVAILABLE_LENS_SHADING_MAP_MODES]))
        put("timestampSource", c[CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE])
    }

    private fun modes(c: CameraCharacteristics) = JSONObject().apply {
        put("noiseReduction", Names.list(Names.noiseReduction, c[CameraCharacteristics.NOISE_REDUCTION_AVAILABLE_NOISE_REDUCTION_MODES]))
        put("edge", Names.list(Names.edge, c[CameraCharacteristics.EDGE_AVAILABLE_EDGE_MODES]))
        put("hotPixel", Names.list(Names.offFastHq, c[CameraCharacteristics.HOT_PIXEL_AVAILABLE_HOT_PIXEL_MODES]))
        put("shading", Names.list(Names.offFastHq, c[CameraCharacteristics.SHADING_AVAILABLE_MODES]))
        put("aberration", Names.list(Names.offFastHq, c[CameraCharacteristics.COLOR_CORRECTION_AVAILABLE_ABERRATION_MODES]))
        put("videoStabilization", Names.list(Names.videoStabilization, c[CameraCharacteristics.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES]))
        put("control", Names.list(Names.controlMode, c[CameraCharacteristics.CONTROL_AVAILABLE_MODES]))
        put("ae", Names.list(Names.aeMode, c[CameraCharacteristics.CONTROL_AE_AVAILABLE_MODES]))
        put("af", Names.list(Names.afMode, c[CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES]))
        put("awb", Names.list(Names.awbMode, c[CameraCharacteristics.CONTROL_AWB_AVAILABLE_MODES]))
        put("sceneModes", ints(c[CameraCharacteristics.CONTROL_AVAILABLE_SCENE_MODES]))
        put("effects", ints(c[CameraCharacteristics.CONTROL_AVAILABLE_EFFECTS]))
        put("aeLockAvailable", c[CameraCharacteristics.CONTROL_AE_LOCK_AVAILABLE])
        put("awbLockAvailable", c[CameraCharacteristics.CONTROL_AWB_LOCK_AVAILABLE])
        put("aeCompensationRange", c[CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE]?.toString())
        put("aeCompensationStep", c[CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP]?.toString())
        put("settingsOverrides", ints(c[CameraCharacteristics.CONTROL_AVAILABLE_SETTINGS_OVERRIDES]))
    }

    private fun dynamicRange(c: CameraCharacteristics) = JSONObject().apply {
        val profiles = c[CameraCharacteristics.REQUEST_AVAILABLE_DYNAMIC_RANGE_PROFILES]
        put("profiles", JSONArray(profiles?.supportedProfiles?.sorted()?.map { p ->
            JSONObject()
                .put("profile", Names.of(Names.dynamicRange, p))
                .put("extraLatency", profiles.isExtraLatencyPresent(p))
                .put("combinableWith", JSONArray(profiles.getProfileCaptureRequestConstraints(p).sorted().map { Names.of(Names.dynamicRange, it) }))
        }.orEmpty()))
        c[CameraCharacteristics.REQUEST_RECOMMENDED_TEN_BIT_DYNAMIC_RANGE_PROFILE]?.let {
            put("recommendedTenBit", Names.of(Names.dynamicRange, it))
        }
        c[CameraCharacteristics.REQUEST_AVAILABLE_COLOR_SPACE_PROFILES]?.let { spaces ->
            put("colorSpaces", JSONArray(spaces.getSupportedColorSpaces(ImageFormat.UNKNOWN).map { it.name }))
        }
    }

    private fun streams(map: StreamConfigurationMap) = JSONObject().apply {
        put("outputFormats", JSONArray(map.outputFormats.map { Names.of(Names.imageFormat, it) }))
        for ((format, name) in Names.imageFormat) {
            if (format !in map.outputFormats) continue
            val entries = JSONArray()
            map.getOutputSizes(format)?.forEach { entries.put(sizeEntry(it, map.getOutputMinFrameDuration(format, it), map.getOutputStallDuration(format, it))) }
            map.getHighResolutionOutputSizes(format)?.forEach {
                entries.put(sizeEntry(it, map.getOutputMinFrameDuration(format, it), map.getOutputStallDuration(format, it)).put("highResolution", true))
            }
            put(name, entries)
        }
        put("MediaCodec", JSONArray(map.getOutputSizes(MediaCodec::class.java)?.map {
            sizeEntry(it, map.getOutputMinFrameDuration(MediaCodec::class.java, it), 0)
        }.orEmpty()))
        put("highSpeedVideo", JSONArray(map.highSpeedVideoSizes.map { size ->
            JSONObject().put("size", size.str()).put("fpsRanges", ranges(map.getHighSpeedVideoFpsRangesFor(size)))
        }))
    }

    private fun sizeEntry(size: Size, minFrameNs: Long, stallNs: Long) = JSONObject().apply {
        put("size", size.str())
        put("minFrameDurationNs", minFrameNs)
        if (minFrameNs > 0) put("maxFps", Math.round(1e10 / minFrameNs) / 10.0)
        if (stallNs > 0) put("stallNs", stallNs)
    }

    /** Vendor keys reveal hidden OEM features (e.g. a Xiaomi log mode) that the public API does not. */
    private fun keys(c: CameraCharacteristics) = JSONObject().apply {
        put("vendorCharacteristics", JSONObject().apply {
            c.keys.filterNot { it.name.startsWith("android.") }.forEach { key ->
                put(key.name, runCatching { valueString(c[key]) }.getOrElse { "error: $it" })
            }
        })
        put("requestKeys", JSONArray(c.availableCaptureRequestKeys.map { it.name }.sorted()))
        put("resultKeys", JSONArray(c.availableCaptureResultKeys.map { it.name }.sorted()))
        put("sessionKeys", JSONArray(c.availableSessionKeys.map { it.name }.sorted()))
        put("physicalRequestKeys", JSONArray(c.availablePhysicalCameraRequestKeys?.map { it.name }?.sorted().orEmpty()))
    }

    /**
     * Asks the HAL which stream combinations it accepts, without opening the camera.
     * This is what decides whether 4K HLG10 + preview (stage 1), a P010 reader (shader path)
     * or RAW video (stage 3) is possible at all.
     */
    @androidx.annotation.RequiresApi(35)
    private fun sessionSupport(id: String, c: CameraCharacteristics): JSONObject {
        val result = JSONObject()
        if (!manager.isCameraDeviceSetupSupported(id)) return result.put("error", "CameraDeviceSetup not supported")
        val setup = manager.getCameraDeviceSetup(id)
        val map = c[CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP] ?: return result.put("error", "no stream map")
        val uhd = Size(3840, 2160)
        val preview = Size(1920, 1080)

        fun check(name: String, fps: Int, profile: Long, outputs: () -> List<OutputConfiguration>) {
            val verdict = try {
                val outs = outputs().onEach { it.dynamicRangeProfile = profile }
                val params = setup.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                    set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, Range(fps, fps))
                }.build()
                val session = SessionConfiguration(SessionConfiguration.SESSION_REGULAR, outs).apply { sessionParameters = params }
                setup.isSessionConfigurationSupported(session).toString()
            } catch (e: Exception) {
                "error: ${e.javaClass.simpleName}: ${e.message}"
            }
            result.put("$name@${fps}fps/${Names.dynamicRange[profile]}", verdict)
        }

        for (profile in listOf(DynamicRangeProfiles.STANDARD, DynamicRangeProfiles.HLG10)) {
            for (fps in listOf(24, 25, 30, 50, 60)) {
                check("preview1080+encoder4K", fps, profile) {
                    listOf(OutputConfiguration(preview, SurfaceHolder::class.java), OutputConfiguration(uhd, MediaCodec::class.java))
                }
            }
            check("preview1080+P010_4K", 30, profile) {
                listOf(OutputConfiguration(preview, SurfaceHolder::class.java), OutputConfiguration(ImageFormat.YCBCR_P010, uhd))
            }
        }
        for (format in listOf(ImageFormat.RAW_SENSOR, ImageFormat.RAW10)) {
            val sizes = map.getOutputSizes(format) ?: continue
            for (size in sizes.sortedBy { it.width * it.height }.take(2)) {
                for (fps in listOf(25, 30)) {
                    check("preview1080+${Names.imageFormat[format]}_${size.str()}", fps, DynamicRangeProfiles.STANDARD) {
                        listOf(OutputConfiguration(preview, SurfaceHolder::class.java), OutputConfiguration(format, size))
                    }
                }
            }
        }
        return result
    }

    private fun Size.str() = "${width}x$height"
    private fun floats(a: FloatArray?) = JSONArray(a?.map { it.toDouble() }.orEmpty())
    private fun ints(a: IntArray?) = JSONArray(a?.toList().orEmpty())
    private fun ranges(a: Array<out Range<Int>>?) = JSONArray(a?.map { "[${it.lower},${it.upper}]" }.orEmpty())

    private fun valueString(v: Any?): String {
        val s = when (v) {
            null -> "null"
            is IntArray -> v.contentToString()
            is LongArray -> v.contentToString()
            is FloatArray -> v.contentToString()
            is DoubleArray -> v.contentToString()
            is ByteArray -> v.contentToString()
            is BooleanArray -> v.contentToString()
            is Array<*> -> v.contentDeepToString()
            else -> v.toString()
        }
        return if (s.length > 400) s.take(400) + "…(${s.length})" else s
    }
}
