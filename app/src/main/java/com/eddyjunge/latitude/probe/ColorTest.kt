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
import com.eddyjunge.latitude.camera.ColorCalibration
import com.eddyjunge.latitude.camera.ColorCorrection
import com.eddyjunge.latitude.camera.Lens
import com.eddyjunge.latitude.camera.Mat3
import com.eddyjunge.latitude.camera.Primaries
import com.eddyjunge.latitude.camera.SensorColor
import com.eddyjunge.latitude.camera.Xy
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import java.nio.ByteOrder
import java.util.concurrent.Executor
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.abs
import kotlin.math.pow

/**
 * Does the ISP take our own white balance gains and color matrix, and do the lenses then
 * agree? For every lens and both dynamic ranges: the vendor's auto colors, then ours with the
 * vendor's white point, then ours with one fixed white point (D65) for all lenses. Point all
 * lenses at the same evenly colored surface (a monitor showing one color works) and compare
 * the center patch.
 */
internal class ColorTest(private val manager: CameraManager) {

    private class Variant(val name: String, val profile: Long, val format: Int, val primaries: Primaries)

    private val variants = listOf(
        Variant("HLG10_P010", DynamicRangeProfiles.HLG10, ImageFormat.YCBCR_P010, Primaries.BT709),
        Variant("STANDARD_YUV8", DynamicRangeProfiles.STANDARD, ImageFormat.YUV_420_888, Primaries.BT709),
    )

    suspend fun run(): JSONArray {
        val out = JSONArray()
        for (lens in Lens.discover(manager)) {
            val entry = JSONObject().put("lens", lens.label).put("cameraId", lens.characteristicsId)
            val chars = manager.getCameraCharacteristics(lens.characteristicsId)
            val color = SensorColor.of(chars)
            if (color == null) {
                out.put(entry.put("error", "no sensor calibration"))
                continue
            }
            for (v in variants) {
                entry.put(v.name, runCatching { testLens(lens, chars, color, v) }
                    .getOrElse { JSONObject().put("error", it.toString()) })
            }
            out.put(entry)
        }
        return out
    }

    /**
     * White calibration: every back lens looks at the same evenly lit surface (white or grey
     * paper, a wall) and reports its raw R:G:B. Each lens then gets the raw scale that makes it
     * read the light all lenses agree on, saved for the camera screen.
     */
    suspend fun calibrate(context: android.content.Context): JSONObject {
        val lenses = Lens.discover(manager).filter { !it.front }
        val measured = mutableMapOf<String, DoubleArray>()
        val factory = mutableMapOf<String, SensorColor>()
        val report = JSONObject()
        val perLens = JSONObject()
        for (lens in lenses) {
            val chars = manager.getCameraCharacteristics(lens.characteristicsId)
            val color = SensorColor.of(chars) ?: continue
            val raw = runCatching { rawNeutral(lens, chars) }.getOrElse {
                perLens.put(lens.label, JSONObject().put("error", it.toString()))
                continue
            }
            measured[lens.characteristicsId] = raw
            factory[lens.characteristicsId] = color
            val xy = color.xyFor(raw)
            perLens.put(lens.label, JSONObject()
                .put("raw", raw.joinToString(" ") { "%.4f".format(it) })
                .put("factoryCct", xy.cct.toInt())
                .put("factoryXy", "%.4f, %.4f".format(xy.x, xy.y)))
        }
        if (measured.size < 2) error("fewer than two lenses measured")
        val scales = ColorCalibration.compute(measured, factory)
        ColorCalibration.save(context, scales)
        for (lens in lenses) {
            val scale = scales[lens.characteristicsId] ?: continue
            perLens.optJSONObject(lens.label)?.put("scale", scale.joinToString(" ") { "%.4f".format(it) })
        }
        return report.put("lenses", perLens)
    }

    /** Raw R/G, 1, B/G of the center patch: unity gains, identity matrix, square-root curve. */
    private suspend fun rawNeutral(lens: Lens, chars: CameraCharacteristics): DoubleArray {
        val thread = HandlerThread("color-calibration").apply { start() }
        val handler = Handler(thread.looper)
        val map = chars[CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP]!!
        val size = map.getOutputSizes(ImageFormat.YUV_420_888).filter { it.width * 9 == it.height * 16 }
            .minBy { abs(it.width - 1920) }
        val frames = Channel<JSONObject>(Channel.CONFLATED)
        val reader = ImageReader.newInstance(size.width, size.height, ImageFormat.YUV_420_888, 4)
        reader.setOnImageAvailableListener({ r ->
            val image = r.acquireLatestImage() ?: return@setOnImageAvailableListener
            try {
                frames.trySend(centerPatch(image, tenBit = false))
            } finally {
                image.close()
            }
        }, handler)
        var device: CameraDevice? = null
        try {
            device = openCamera(lens.openId, handler)
            val output = OutputConfiguration(reader.surface).apply { lens.physicalId?.let { setPhysicalCameraId(it) } }
            val session = createSession(device, listOf(output), handler)
            val sqrt = FloatArray(64 * 2) { i ->
                val x = (i / 2) / 63f
                if (i % 2 == 0) x else kotlin.math.sqrt(x)
            }
            val request = device.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                addTarget(reader.surface)
                set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, Range(30, 30))
                set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, 6) // +1 EV: more raw signal
                set(CaptureRequest.CONTROL_AWB_MODE, CameraMetadata.CONTROL_AWB_MODE_OFF)
                set(CaptureRequest.COLOR_CORRECTION_MODE, CameraMetadata.COLOR_CORRECTION_MODE_TRANSFORM_MATRIX)
                set(CaptureRequest.COLOR_CORRECTION_GAINS, android.hardware.camera2.params.RggbChannelVector(1f, 1f, 1f, 1f))
                set(CaptureRequest.COLOR_CORRECTION_TRANSFORM, Mat3.IDENTITY.toTransform())
                set(CaptureRequest.TONEMAP_MODE, CameraMetadata.TONEMAP_MODE_CONTRAST_CURVE)
                set(CaptureRequest.TONEMAP_CURVE, TonemapCurve(sqrt, sqrt, sqrt))
            }.build()
            session.setRepeatingRequest(request, null, handler)
            val patches = withTimeout(8_000) {
                repeat(30) { frames.receive() }
                List(8) { frames.receive() }
            }
            runCatching { session.stopRepeating() }
            session.close()
            val y = patches.map { it.getDouble("Y") }.average()
            val cb = patches.map { it.getDouble("Cb") }.average()
            val cr = patches.map { it.getDouble("Cr") }.average()
            require(y in 0.15..0.95) { "center patch too dark or bright (Y=%.2f)".format(y) }
            // Camera YUV is JFIF (BT.601, full range); undo the square-root curve per channel.
            val r = (y + 1.402 * cr).pow(2)
            val g = (y - 0.344136 * cb - 0.714136 * cr).pow(2)
            val b = (y + 1.772 * cb).pow(2)
            return doubleArrayOf(r / g, 1.0, b / g)
        } finally {
            device?.close()
            reader.close()
            frames.close()
            thread.quitSafely()
        }
    }

    private suspend fun testLens(lens: Lens, chars: CameraCharacteristics, color: SensorColor, v: Variant): JSONObject {
        val thread = HandlerThread("color-test").apply { start() }
        val handler = Handler(thread.looper)
        val map = chars[CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP]!!
        val size = map.getOutputSizes(v.format).filter { it.width * 9 == it.height * 16 }
            .minByOrNull { abs(it.width - 1920) } ?: return JSONObject().put("error", "format not offered")
        val frames = Channel<Pair<Long, JSONObject>>(Channel.CONFLATED)
        val reader = ImageReader.newInstance(size.width, size.height, v.format, 4)
        reader.setOnImageAvailableListener({ r ->
            val image = r.acquireLatestImage() ?: return@setOnImageAvailableListener
            try {
                frames.trySend(image.timestamp to centerPatch(image, v.format == ImageFormat.YCBCR_P010))
            } finally {
                image.close()
            }
        }, handler)
        val results = java.util.concurrent.ConcurrentHashMap<Long, TotalCaptureResult>()
        val callback = object : CameraCaptureSession.CaptureCallback() {
            override fun onCaptureCompleted(s: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
                result[CaptureResult.SENSOR_TIMESTAMP]?.let { results[it] = result }
            }
        }
        var device: CameraDevice? = null
        try {
            device = openCamera(lens.openId, handler)
            val output = OutputConfiguration(reader.surface).apply {
                dynamicRangeProfile = v.profile
                lens.physicalId?.let { setPhysicalCameraId(it) }
            }
            val session = createSession(device, listOf(output), handler)
            fun send(configure: CaptureRequest.Builder.() -> Unit) {
                val request = device.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                    addTarget(reader.surface)
                    set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, Range(30, 30))
                    configure()
                }.build()
                session.setRepeatingRequest(request, callback, handler)
            }
            suspend fun settle(frameCount: Int): Pair<JSONObject, TotalCaptureResult?> {
                var last: Pair<Long, JSONObject>? = null
                withTimeout(6_000) { repeat(frameCount) { last = frames.receive() } }
                val (ts, patch) = last!!
                return patch to results[ts]
            }

            val report = JSONObject().put("size", "${size.width}x${size.height}")
            send {}
            val (autoPatch, autoResult) = settle(30)
            val neutral = autoResult?.get(CaptureResult.SENSOR_NEUTRAL_COLOR_POINT)?.map { it.toDouble() }
            report.put("auto", phase(autoPatch, autoResult).apply {
                neutral?.let { n ->
                    val xy = color.xyFor(doubleArrayOf(n[0] / n[1], 1.0, n[2] / n[1]))
                    put("estimatedXy", "%.4f, %.4f".format(xy.x, xy.y)).put("estimatedCct", xy.cct.toInt())
                    put("ours709", matrix(color.correction(xy, Primaries.BT709)))
                    put("ours2020", matrix(color.correction(xy, Primaries.BT2020)))
                }
            })
            val vendorXy = neutral?.let { n -> color.xyFor(doubleArrayOf(n[0] / n[1], 1.0, n[2] / n[1])) } ?: Xy.D65

            fun manual(correction: ColorCorrection): CaptureRequest.Builder.() -> Unit = {
                set(CaptureRequest.CONTROL_AE_LOCK, true)
                set(CaptureRequest.CONTROL_AWB_MODE, CameraMetadata.CONTROL_AWB_MODE_OFF)
                set(CaptureRequest.COLOR_CORRECTION_MODE, CameraMetadata.COLOR_CORRECTION_MODE_TRANSFORM_MATRIX)
                set(CaptureRequest.COLOR_CORRECTION_GAINS, correction.gains)
                set(CaptureRequest.COLOR_CORRECTION_TRANSFORM, correction.transform)
            }
            for ((tag, xy) in listOf("oursVendorWhite" to vendorXy, "oursD65" to Xy.D65)) {
                val correction = color.correction(xy, v.primaries)
                send(manual(correction))
                val (patch, result) = settle(12)
                report.put(tag, phase(patch, result).put("sent", matrix(correction)))
            }
            // Does the ISP keep our matrix while its own AWB runs? The docs say it falls back.
            send {
                set(CaptureRequest.CONTROL_AE_LOCK, true)
                set(CaptureRequest.COLOR_CORRECTION_MODE, CameraMetadata.COLOR_CORRECTION_MODE_TRANSFORM_MATRIX)
                set(CaptureRequest.COLOR_CORRECTION_TRANSFORM, color.correction(vendorXy, v.primaries).transform)
            }
            val (autoOursPatch, autoOursResult) = settle(12)
            report.put("autoAwbOurMatrix", phase(autoOursPatch, autoOursResult))

            runCatching { session.stopRepeating() }
            session.close()
            return report
        } finally {
            device?.close()
            reader.close()
            frames.close()
            thread.quitSafely()
        }
    }

    private fun phase(patch: JSONObject, r: TotalCaptureResult?) = JSONObject().put("center", patch).apply {
        if (r == null) return@apply
        put("awbMode", r[CaptureResult.CONTROL_AWB_MODE])
        put("ccMode", r[CaptureResult.COLOR_CORRECTION_MODE])
        r[CaptureResult.COLOR_CORRECTION_GAINS]?.let {
            put("gains", "%.3f %.3f %.3f %.3f".format(it.red, it.greenEven, it.greenOdd, it.blue))
        }
        r[CaptureResult.COLOR_CORRECTION_TRANSFORM]?.let { put("transform", Mat3.of(it).toString()) }
        r[CaptureResult.SENSOR_NEUTRAL_COLOR_POINT]?.let { n ->
            put("neutral", n.joinToString(" ") { "%.4f".format(it.toDouble()) })
        }
        // The vendor's tone curve at a few inputs, to see whether it differs between lenses.
        r[CaptureResult.TONEMAP_CURVE]?.let { curve ->
            val n = curve.getPointCount(TonemapCurve.CHANNEL_GREEN)
            val points = (0 until n).map { curve.getPoint(TonemapCurve.CHANNEL_GREEN, it) }
            fun at(x: Float): Float {
                val i = points.indexOfFirst { it.x >= x }.coerceAtLeast(1)
                val a = points[i - 1]
                val b = points[i]
                return if (b.x == a.x) b.y else a.y + (b.y - a.y) * (x - a.x) / (b.x - a.x)
            }
            put("tonemap", JSONObject().put("mode", r[CaptureResult.TONEMAP_MODE]).put("points", n)
                .put("curve", listOf(0.01f, 0.03f, 0.1f, 0.2f, 0.4f, 0.7f, 1f).joinToString(" ") { "%.3f".format(at(it)) }))
        }
        put("iso", r[CaptureResult.SENSOR_SENSITIVITY])
        put("exposureNs", r[CaptureResult.SENSOR_EXPOSURE_TIME])
    }

    private fun matrix(c: ColorCorrection) = JSONObject()
        .put("gains", "%.3f %.3f %.3f %.3f".format(c.gains.red, c.gains.greenEven, c.gains.greenOdd, c.gains.blue))
        .put("transform", Mat3.of(c.transform).toString())

    /** Mean Y, Cb, Cr of the central 10 % of the frame, normalized (Cb/Cr 0 = neutral). */
    private fun centerPatch(image: Image, tenBit: Boolean): JSONObject {
        fun mean(plane: Image.Plane, w: Int, h: Int, offset: Double): Double {
            val buffer = plane.buffer.order(ByteOrder.LITTLE_ENDIAN)
            var sum = 0.0
            var n = 0
            for (y in (h * 0.45).toInt() until (h * 0.55).toInt()) {
                for (x in (w * 0.45).toInt() until (w * 0.55).toInt()) {
                    val i = y * plane.rowStride + x * plane.pixelStride
                    val code = if (tenBit) (buffer.getShort(i).toInt() and 0xFFFF) ushr 6 else buffer.get(i).toInt() and 0xFF
                    sum += code
                    n++
                }
            }
            val max = if (tenBit) 1023.0 else 255.0
            return sum / n / max - offset
        }
        val w = image.width
        val h = image.height
        return JSONObject()
            .put("Y", round(mean(image.planes[0], w, h, 0.0)))
            .put("Cb", round(mean(image.planes[1], w / 2, h / 2, 0.5)))
            .put("Cr", round(mean(image.planes[2], w / 2, h / 2, 0.5)))
    }

    private fun round(v: Double) = Math.round(v * 10000) / 10000.0

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
}
