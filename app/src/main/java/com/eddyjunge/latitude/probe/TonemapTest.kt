package com.eddyjunge.latitude.probe

import android.annotation.SuppressLint
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.params.DynamicRangeProfiles
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.hardware.camera2.params.TonemapCurve
import android.media.Image
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.util.Range
import android.util.Size
import com.eddyjunge.latitude.camera.AppleLog
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executor
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.abs
import kotlin.math.pow

/**
 * Live test for the key stage-2 question: does the ISP apply a custom tonemap curve
 * when the camera delivers 10-bit HLG? And are those 10 bits real or upscaled 8-bit?
 *
 * For each dynamic range profile it captures frames with exposure and white balance
 * locked, first with the default tonemap, then with three custom curves:
 * identity, flat (constant 0.5) and Apple Log. If the ISP honors the curve, the flat
 * curve collapses the image to a near-uniform grey.
 */
internal class TonemapTest(private val manager: CameraManager) {

    private class Frame(val timestamp: Long, val stats: JSONObject, val mean: Double, val spread: Double)

    private class Phase(val tag: String, val configure: CaptureRequest.Builder.() -> Unit)

    suspend fun run(cameraId: String): JSONObject {
        val thread = HandlerThread("probe-camera").apply { start() }
        val handler = Handler(thread.looper)
        val result = JSONObject().put("cameraId", cameraId)
        var device: CameraDevice? = null
        try {
            val chars = manager.getCameraCharacteristics(cameraId)
            val maxPoints = chars[CameraCharacteristics.TONEMAP_MAX_CURVE_POINTS] ?: 0
            val modes = chars[CameraCharacteristics.TONEMAP_AVAILABLE_TONE_MAP_MODES]?.toList().orEmpty()
            result.put("maxCurvePoints", maxPoints)
            if (CameraMetadata.TONEMAP_MODE_CONTRAST_CURVE !in modes) {
                return result.put("error", "TONEMAP_MODE_CONTRAST_CURVE not offered")
            }
            val map = chars[CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP]!!
            val hlg = chars[CameraCharacteristics.REQUEST_AVAILABLE_DYNAMIC_RANGE_PROFILES]
                ?.supportedProfiles?.contains(DynamicRangeProfiles.HLG10) == true
            val nr = chars[CameraCharacteristics.NOISE_REDUCTION_AVAILABLE_NOISE_REDUCTION_MODES]?.toList().orEmpty()
            val edge = chars[CameraCharacteristics.EDGE_AVAILABLE_EDGE_MODES]?.toList().orEmpty()

            device = openCamera(cameraId, handler)

            val variants = buildList {
                if (hlg) add(Triple("HLG10_P010", DynamicRangeProfiles.HLG10, ImageFormat.YCBCR_P010))
                add(Triple("STANDARD_YUV8", DynamicRangeProfiles.STANDARD, ImageFormat.YUV_420_888))
            }
            for ((name, profile, format) in variants) {
                val sizes = map.getOutputSizes(format)
                if (sizes.isNullOrEmpty()) {
                    result.put(name, JSONObject().put("error", "format not offered"))
                    continue
                }
                val size = sizes.firstOrNull { it.width == 1920 && it.height == 1080 }
                    ?: sizes.filter { it.width * 9 == it.height * 16 }.minByOrNull { abs(it.width - 1920) }
                    ?: sizes.first()
                result.put(name, runCatching {
                    testProfile(device, handler, profile, format, size, maxPoints, nr, edge)
                }.getOrElse { JSONObject().put("error", it.toString()) })
            }
        } catch (e: Exception) {
            result.put("error", e.toString())
        } finally {
            device?.close()
            thread.quitSafely()
        }
        return result
    }

    private suspend fun testProfile(
        device: CameraDevice,
        handler: Handler,
        profile: Long,
        format: Int,
        size: Size,
        maxPoints: Int,
        nrModes: List<Int>,
        edgeModes: List<Int>,
    ): JSONObject {
        val tenBit = format == ImageFormat.YCBCR_P010
        val frames = Channel<Frame>(Channel.UNLIMITED)
        val results = ConcurrentHashMap<Long, TotalCaptureResult>()
        val reader = ImageReader.newInstance(size.width, size.height, format, 6)
        reader.setOnImageAvailableListener({ r ->
            val image = r.acquireNextImage() ?: return@setOnImageAvailableListener
            try {
                frames.trySend(measure(image, tenBit))
            } finally {
                image.close()
            }
        }, handler)

        val output = OutputConfiguration(reader.surface).apply { dynamicRangeProfile = profile }
        val session = createSession(device, listOf(output), handler)
        val callback = object : CameraCaptureSession.CaptureCallback() {
            override fun onCaptureCompleted(s: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
                result[CaptureResult.SENSOR_TIMESTAMP]?.let { results[it] = result }
            }
        }

        fun repeat(phase: Phase) {
            val request = device.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                addTarget(reader.surface)
                set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, Range(30, 30))
                setTag(phase.tag)
                phase.configure(this)
            }.build()
            session.setRepeatingRequest(request, callback, handler)
        }

        val locked: CaptureRequest.Builder.() -> Unit = {
            set(CaptureRequest.CONTROL_AE_LOCK, true)
            set(CaptureRequest.CONTROL_AWB_LOCK, true)
        }
        val minimalProcessing: CaptureRequest.Builder.() -> Unit = {
            if (CameraMetadata.NOISE_REDUCTION_MODE_OFF in nrModes) {
                set(CaptureRequest.NOISE_REDUCTION_MODE, CameraMetadata.NOISE_REDUCTION_MODE_OFF)
            }
            if (CameraMetadata.EDGE_MODE_OFF in edgeModes) set(CaptureRequest.EDGE_MODE, CameraMetadata.EDGE_MODE_OFF)
        }
        fun curve(tonemap: TonemapCurve): CaptureRequest.Builder.() -> Unit = {
            locked()
            minimalProcessing()
            set(CaptureRequest.TONEMAP_MODE, CameraMetadata.TONEMAP_MODE_CONTRAST_CURVE)
            set(CaptureRequest.TONEMAP_CURVE, tonemap)
        }
        fun curve(points: FloatArray) = curve(TonemapCurve(points, points, points))

        val phases = listOf(
            Phase("default", locked),
            Phase("identity", curve(floatArrayOf(0f, 0f, 1f, 1f))),
            Phase("flat", curve(floatArrayOf(0f, 0.5f, 1f, 0.5f))),
            Phase("appleLogH5", curve(appleLogCurve(maxPoints.coerceIn(3, 128), headroom = 5.0))),
            // The app's curve: evenly spaced. Same stats as appleLogH5 means the ISP honors x.
            Phase("appleLogEven", curve(AppleLog.tonemapCurve(maxPoints))),
        )

        val report = JSONObject()
            .put("size", "${size.width}x${size.height}")
            .put("format", Names.imageFormat[format])
        try {
            // Let auto exposure and white balance settle, then lock them for every phase.
            repeat(Phase("converge") {})
            collect(frames, results, "converge", skip = 0, count = 25)
            val measured = mutableMapOf<String, Pair<Double, Double>>()
            for (phase in phases) {
                repeat(phase)
                val got = collect(frames, results, phase.tag, skip = 3, count = 6)
                measured[phase.tag] = got.map { it.mean }.average() to got.map { it.spread }.average()
                report.put(phase.tag, phaseReport(got, results))
            }
            report.put("verdict", verdict(measured))
        } finally {
            runCatching { session.stopRepeating() }
            session.close()
            reader.close()
            frames.close()
        }
        return report
    }

    /** Frames whose capture result carries [tag], after skipping the first [skip]. */
    private suspend fun collect(
        frames: Channel<Frame>,
        results: Map<Long, TotalCaptureResult>,
        tag: String,
        skip: Int,
        count: Int,
    ): List<Frame> = withTimeout(8_000) {
        val matched = mutableListOf<Frame>()
        var skipped = 0
        while (matched.size < count) {
            val frame = frames.receive()
            var result = results[frame.timestamp]
            var waited = 0
            while (result == null && waited < 500) {
                delay(10)
                waited += 10
                result = results[frame.timestamp]
            }
            if (result?.request?.tag != tag) continue
            if (skipped < skip) skipped++ else matched += frame
        }
        matched
    }

    private fun phaseReport(frames: List<Frame>, results: Map<Long, TotalCaptureResult>): JSONObject {
        val last = frames.last()
        val r = results[last.timestamp]
        val intervalsMs = frames.zipWithNext { a, b -> (b.timestamp - a.timestamp) / 1e6 }
        return JSONObject()
            .put("lastFrame", last.stats)
            .put("meanY", round(frames.map { it.mean }.average()))
            .put("spreadP1toP99", round(frames.map { it.spread }.average()))
            .put("frameIntervalsMs", JSONArray(intervalsMs.map(::round)))
            .put("result", JSONObject().apply {
                put("tonemapMode", Names.of(Names.tonemap, r?.get(CaptureResult.TONEMAP_MODE)))
                put("noiseReduction", Names.of(Names.noiseReduction, r?.get(CaptureResult.NOISE_REDUCTION_MODE)))
                put("edge", Names.of(Names.edge, r?.get(CaptureResult.EDGE_MODE)))
                put("iso", r?.get(CaptureResult.SENSOR_SENSITIVITY))
                put("exposureNs", r?.get(CaptureResult.SENSOR_EXPOSURE_TIME))
                put("frameDurationNs", r?.get(CaptureResult.SENSOR_FRAME_DURATION))
                put("aeState", r?.get(CaptureResult.CONTROL_AE_STATE))
                put("awbState", r?.get(CaptureResult.CONTROL_AWB_STATE))
                put("curvePointsEchoed", r?.get(CaptureResult.TONEMAP_CURVE)?.getPointCount(TonemapCurve.CHANNEL_RED))
            })
    }

    /** The flat curve must collapse the picture; the identity curve must not. */
    private fun verdict(m: Map<String, Pair<Double, Double>>): JSONObject {
        val defaultSpread = m.getValue("default").second
        val flatSpread = m.getValue("flat").second
        val sceneHasContrast = defaultSpread > 0.1
        val honored = sceneHasContrast && flatSpread < 0.25 * defaultSpread
        return JSONObject()
            .put("sceneHasEnoughContrast", sceneHasContrast)
            .put("tonemapCurveHonored", if (sceneHasContrast) honored else JSONObject.NULL)
            .put("note", if (sceneHasContrast) "flat spread ${round(flatSpread)} vs default ${round(defaultSpread)}"
                else "Point the camera at a scene with bright and dark areas and run again")
    }

    /**
     * Luma statistics over the whole frame (every 4th pixel). For 10-bit data, the share of
     * codes with the two lowest bits zero reveals upscaled 8-bit: ~0.25 is real 10-bit, ~1.0 is not.
     */
    private fun measure(image: Image, tenBit: Boolean): Frame {
        val plane = image.planes[0]
        val buffer = plane.buffer.order(ByteOrder.LITTLE_ENDIAN)
        val maxCode = if (tenBit) 1023 else 255
        val histogram = IntArray(maxCode + 1)
        var samples = 0
        var lowBitsZero = 0
        var y = 0
        while (y < image.height) {
            var x = 0
            val row = y * plane.rowStride
            while (x < image.width) {
                val code = if (tenBit) {
                    (buffer.getShort(row + x * plane.pixelStride).toInt() and 0xFFFF) ushr 6
                } else {
                    buffer.get(row + x * plane.pixelStride).toInt() and 0xFF
                }
                histogram[code]++
                if (code and 3 == 0) lowBitsZero++
                samples++
                x += 4
            }
            y += 4
        }
        fun percentile(p: Double): Int {
            val target = (samples * p).toLong()
            var sum = 0L
            for (i in histogram.indices) {
                sum += histogram[i]
                if (sum > target) return i
            }
            return maxCode
        }
        var total = 0.0
        for (i in histogram.indices) total += i.toDouble() * histogram[i]
        val mean = total / samples / maxCode
        val p1 = percentile(0.01)
        val p99 = percentile(0.99)
        val stats = JSONObject()
            .put("bits", if (tenBit) 10 else 8)
            .put("minCode", histogram.indexOfFirst { it > 0 })
            .put("maxCode", histogram.indexOfLast { it > 0 })
            .put("p1", p1)
            .put("p50", percentile(0.5))
            .put("p99", p99)
            .put("distinctCodes", histogram.count { it > 0 })
        if (tenBit) stats.put("lowTwoBitsZeroShare", round(lowBitsZero.toDouble() / samples))
        return Frame(image.timestamp, stats, mean, (p99 - p1).toDouble() / maxCode)
    }

    @SuppressLint("MissingPermission") // only reachable after the main screen got camera permission
    private suspend fun openCamera(id: String, handler: Handler): CameraDevice =
        suspendCancellableCoroutine { cont ->
            manager.openCamera(id, Executor(handler::post), object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    if (cont.isActive) cont.resume(camera) else camera.close()
                }

                override fun onDisconnected(camera: CameraDevice) {
                    camera.close()
                    if (cont.isActive) cont.resumeWithException(IllegalStateException("camera disconnected"))
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    camera.close()
                    if (cont.isActive) cont.resumeWithException(IllegalStateException("camera error $error"))
                }
            })
        }

    private suspend fun createSession(
        device: CameraDevice,
        outputs: List<OutputConfiguration>,
        handler: Handler,
    ): CameraCaptureSession = suspendCancellableCoroutine { cont ->
        val callback = object : CameraCaptureSession.StateCallback() {
            override fun onConfigured(session: CameraCaptureSession) = cont.resume(session)
            override fun onConfigureFailed(session: CameraCaptureSession) =
                cont.resumeWithException(IllegalStateException("session configuration rejected"))
        }
        device.createCaptureSession(
            SessionConfiguration(SessionConfiguration.SESSION_REGULAR, outputs, Executor(handler::post), callback)
        )
    }

    private fun round(v: Double) = Math.round(v * 1000) / 1000.0

    companion object {
        /**
         * Apple Log as a tonemap curve: ISP linear 1.0 (sensor clip) maps to scene
         * reflectance 0.18 * 2^headroom. Points are spaced logarithmically, dense in the shadows.
         */
        fun appleLogCurve(points: Int, headroom: Double): FloatArray {
            val scale = 0.18 * 2.0.pow(headroom)
            val out = FloatArray(points * 2)
            for (i in 0 until points) {
                val x = if (i == 0) 0.0 else 2.0.pow(-14.0 + 14.0 * (i - 1) / (points - 2))
                out[i * 2] = x.toFloat()
                out[i * 2 + 1] = AppleLog.encode(x * scale).coerceIn(0.0, 1.0).toFloat()
            }
            return out
        }
    }
}
