package com.eddyjunge.latitude.probe

import android.graphics.ImageFormat
import android.hardware.camera2.params.DynamicRangeProfiles
import org.json.JSONArray

/** Human-readable names for camera2 constants; the raw value is always kept in parentheses. */
internal object Names {
    val hardwareLevel = mapOf(0 to "LIMITED", 1 to "FULL", 2 to "LEGACY", 3 to "LEVEL_3", 4 to "EXTERNAL")

    val capability = mapOf(
        0 to "BACKWARD_COMPATIBLE", 1 to "MANUAL_SENSOR", 2 to "MANUAL_POST_PROCESSING", 3 to "RAW",
        4 to "PRIVATE_REPROCESSING", 5 to "READ_SENSOR_SETTINGS", 6 to "BURST_CAPTURE", 7 to "YUV_REPROCESSING",
        8 to "DEPTH_OUTPUT", 9 to "CONSTRAINED_HIGH_SPEED_VIDEO", 10 to "MOTION_TRACKING",
        11 to "LOGICAL_MULTI_CAMERA", 12 to "MONOCHROME", 13 to "SECURE_IMAGE_DATA", 14 to "SYSTEM_CAMERA",
        15 to "OFFLINE_PROCESSING", 16 to "ULTRA_HIGH_RESOLUTION_SENSOR", 17 to "REMOSAIC_REPROCESSING",
        18 to "DYNAMIC_RANGE_TEN_BIT", 19 to "STREAM_USE_CASE", 20 to "COLOR_SPACE_PROFILES",
    )

    val lensFacing = mapOf(0 to "FRONT", 1 to "BACK", 2 to "EXTERNAL")
    val noiseReduction = mapOf(0 to "OFF", 1 to "FAST", 2 to "HIGH_QUALITY", 3 to "MINIMAL", 4 to "ZERO_SHUTTER_LAG")
    val edge = mapOf(0 to "OFF", 1 to "FAST", 2 to "HIGH_QUALITY", 3 to "ZERO_SHUTTER_LAG")
    val offFastHq = mapOf(0 to "OFF", 1 to "FAST", 2 to "HIGH_QUALITY")
    val videoStabilization = mapOf(0 to "OFF", 1 to "ON", 2 to "PREVIEW_STABILIZATION")
    val onOff = mapOf(0 to "OFF", 1 to "ON")
    val tonemap = mapOf(0 to "CONTRAST_CURVE", 1 to "FAST", 2 to "HIGH_QUALITY", 3 to "GAMMA_VALUE", 4 to "PRESET_CURVE")
    val aeMode = mapOf(
        0 to "OFF", 1 to "ON", 2 to "ON_AUTO_FLASH", 3 to "ON_ALWAYS_FLASH", 4 to "ON_AUTO_FLASH_REDEYE",
        5 to "ON_EXTERNAL_FLASH", 6 to "ON_LOW_LIGHT_BOOST_BRIGHTNESS_PRIORITY",
    )
    val afMode = mapOf(
        0 to "OFF", 1 to "AUTO", 2 to "MACRO", 3 to "CONTINUOUS_VIDEO", 4 to "CONTINUOUS_PICTURE", 5 to "EDOF",
    )
    val awbMode = mapOf(
        0 to "OFF", 1 to "AUTO", 2 to "INCANDESCENT", 3 to "FLUORESCENT", 4 to "WARM_FLUORESCENT",
        5 to "DAYLIGHT", 6 to "CLOUDY_DAYLIGHT", 7 to "TWILIGHT", 8 to "SHADE",
    )
    val controlMode = mapOf(0 to "OFF", 1 to "AUTO", 2 to "USE_SCENE_MODE", 3 to "OFF_KEEP_STATE", 4 to "USE_EXTENDED_SCENE_MODE")
    val colorFilter = mapOf(0 to "RGGB", 1 to "GRBG", 2 to "GBRG", 3 to "BGGR", 4 to "RGB", 5 to "MONO", 6 to "NIR")
    val focusCalibration = mapOf(0 to "UNCALIBRATED", 1 to "APPROXIMATE", 2 to "CALIBRATED")

    val dynamicRange = mapOf(
        DynamicRangeProfiles.STANDARD to "STANDARD",
        DynamicRangeProfiles.HLG10 to "HLG10",
        DynamicRangeProfiles.HDR10 to "HDR10",
        DynamicRangeProfiles.HDR10_PLUS to "HDR10_PLUS",
        DynamicRangeProfiles.DOLBY_VISION_10B_HDR_REF to "DOLBY_VISION_10B_HDR_REF",
        DynamicRangeProfiles.DOLBY_VISION_10B_HDR_REF_PO to "DOLBY_VISION_10B_HDR_REF_PO",
        DynamicRangeProfiles.DOLBY_VISION_10B_HDR_OEM to "DOLBY_VISION_10B_HDR_OEM",
        DynamicRangeProfiles.DOLBY_VISION_10B_HDR_OEM_PO to "DOLBY_VISION_10B_HDR_OEM_PO",
        DynamicRangeProfiles.DOLBY_VISION_8B_HDR_REF to "DOLBY_VISION_8B_HDR_REF",
        DynamicRangeProfiles.DOLBY_VISION_8B_HDR_REF_PO to "DOLBY_VISION_8B_HDR_REF_PO",
        DynamicRangeProfiles.DOLBY_VISION_8B_HDR_OEM to "DOLBY_VISION_8B_HDR_OEM",
        DynamicRangeProfiles.DOLBY_VISION_8B_HDR_OEM_PO to "DOLBY_VISION_8B_HDR_OEM_PO",
    )

    val imageFormat = mapOf(
        ImageFormat.PRIVATE to "PRIVATE",
        ImageFormat.YCBCR_P010 to "YCBCR_P010",
        ImageFormat.YUV_420_888 to "YUV_420_888",
        ImageFormat.RAW_SENSOR to "RAW_SENSOR",
        ImageFormat.RAW10 to "RAW10",
        ImageFormat.RAW12 to "RAW12",
        ImageFormat.RAW_PRIVATE to "RAW_PRIVATE",
        ImageFormat.JPEG to "JPEG",
        ImageFormat.JPEG_R to "JPEG_R",
        ImageFormat.HEIC to "HEIC",
        ImageFormat.DEPTH16 to "DEPTH16",
    )

    fun of(map: Map<Int, String>, value: Int?): String =
        if (value == null) "null" else "${map[value] ?: "UNKNOWN"}($value)"

    fun of(map: Map<Long, String>, value: Long): String = "${map[value] ?: "UNKNOWN"}($value)"

    fun list(map: Map<Int, String>, values: IntArray?): JSONArray =
        JSONArray(values?.map { of(map, it) }.orEmpty())
}
