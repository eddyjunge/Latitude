package com.eddyjunge.latitude.probe

import android.media.MediaCodecInfo
import android.media.MediaCodecInfo.CodecProfileLevel
import android.media.MediaCodecInfo.EncoderCapabilities
import android.media.MediaCodecList
import org.json.JSONArray
import org.json.JSONObject

/** Video encoder capabilities: what bitrate, profile and frame rate the hardware really offers. */
internal object CodecProbe {
    private val testSizes = listOf(3840 to 2160, 4096 to 2160, 1920 to 1080)
    private val testFps = listOf(24, 25, 30, 50, 60, 120)

    private val hevcProfiles = mapOf(
        CodecProfileLevel.HEVCProfileMain to "Main",
        CodecProfileLevel.HEVCProfileMain10 to "Main10",
        CodecProfileLevel.HEVCProfileMain10HDR10 to "Main10HDR10",
        CodecProfileLevel.HEVCProfileMain10HDR10Plus to "Main10HDR10Plus",
        CodecProfileLevel.HEVCProfileMainStill to "MainStill",
    )

    private val hevcLevels = mapOf(
        CodecProfileLevel.HEVCMainTierLevel41 to "Main4.1", CodecProfileLevel.HEVCHighTierLevel41 to "High4.1",
        CodecProfileLevel.HEVCMainTierLevel5 to "Main5", CodecProfileLevel.HEVCHighTierLevel5 to "High5",
        CodecProfileLevel.HEVCMainTierLevel51 to "Main5.1", CodecProfileLevel.HEVCHighTierLevel51 to "High5.1",
        CodecProfileLevel.HEVCMainTierLevel52 to "Main5.2", CodecProfileLevel.HEVCHighTierLevel52 to "High5.2",
        CodecProfileLevel.HEVCMainTierLevel6 to "Main6", CodecProfileLevel.HEVCHighTierLevel6 to "High6",
        CodecProfileLevel.HEVCMainTierLevel61 to "Main6.1", CodecProfileLevel.HEVCHighTierLevel61 to "High6.1",
        CodecProfileLevel.HEVCMainTierLevel62 to "Main6.2", CodecProfileLevel.HEVCHighTierLevel62 to "High6.2",
    )

    private val bitrateModes = mapOf(
        EncoderCapabilities.BITRATE_MODE_CQ to "CQ",
        EncoderCapabilities.BITRATE_MODE_VBR to "VBR",
        EncoderCapabilities.BITRATE_MODE_CBR to "CBR",
        EncoderCapabilities.BITRATE_MODE_CBR_FD to "CBR_FD",
    )

    private val features = listOf(
        MediaCodecInfo.CodecCapabilities.FEATURE_IntraRefresh,
        MediaCodecInfo.CodecCapabilities.FEATURE_QpBounds,
        MediaCodecInfo.CodecCapabilities.FEATURE_EncodingStatistics,
        MediaCodecInfo.CodecCapabilities.FEATURE_HdrEditing,
    )

    fun run(): JSONArray {
        val result = JSONArray()
        for (info in MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos) {
            if (!info.isEncoder) continue
            for (type in info.supportedTypes) {
                if (!type.startsWith("video/")) continue
                result.put(runCatching { describe(info, type) }.getOrElse {
                    JSONObject().put("name", info.name).put("type", type).put("error", it.toString())
                })
            }
        }
        return result
    }

    private fun describe(info: MediaCodecInfo, type: String): JSONObject {
        val caps = info.getCapabilitiesForType(type)
        val video = requireNotNull(caps.videoCapabilities) { "no video capabilities" }
        val encoder = requireNotNull(caps.encoderCapabilities) { "no encoder capabilities" }
        val isHevc = type == "video/hevc"
        return JSONObject().apply {
            put("name", info.name)
            put("canonicalName", info.canonicalName)
            put("type", type)
            put("hardwareAccelerated", info.isHardwareAccelerated)
            put("softwareOnly", info.isSoftwareOnly)
            put("vendor", info.isVendor)
            put("maxInstances", caps.maxSupportedInstances)
            put("profileLevels", JSONArray(caps.profileLevels.map {
                if (isHevc) "${hevcProfiles[it.profile] ?: it.profile}@${hevcLevels[it.level] ?: it.level}"
                else "${it.profile}@${it.level}"
            }))
            put("colorFormats", JSONArray(caps.colorFormats.toList()))
            put("bitrateRange", video.bitrateRange.toString())
            put("bitrateModes", JSONArray(bitrateModes.filterKeys(encoder::isBitrateModeSupported).values))
            put("qualityRange", encoder.qualityRange.toString())
            put("complexityRange", encoder.complexityRange.toString())
            put("widths", video.supportedWidths.toString())
            put("heights", video.supportedHeights.toString())
            put("alignment", "${video.widthAlignment}x${video.heightAlignment}")
            put("features", JSONArray(features.filter(caps::isFeatureSupported)))
            put("performancePoints", JSONArray(video.supportedPerformancePoints?.map { it.toString() }.orEmpty()))
            put("sizes", JSONObject().apply {
                for ((w, h) in testSizes) {
                    if (!video.isSizeSupported(w, h)) {
                        put("${w}x$h", "unsupported")
                        continue
                    }
                    put("${w}x$h", JSONObject()
                        .put("fpsSupported", JSONArray(testFps.filter { video.areSizeAndRateSupported(w, h, it.toDouble()) }))
                        .put("achievableFps", video.getAchievableFrameRatesFor(w, h)?.toString()))
                }
            })
        }
    }
}
