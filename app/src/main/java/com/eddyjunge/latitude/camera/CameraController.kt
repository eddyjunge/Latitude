package com.eddyjunge.latitude.camera

import android.annotation.SuppressLint
import android.content.Context
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.params.DynamicRangeProfiles
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.media.MediaCodec
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import android.os.SystemClock
import android.util.Log
import android.util.Range
import android.util.Size
import android.view.Surface
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit

private const val TAG = "CameraController"

/** Ignore a second trigger within this window so one press never starts and stops at once. */
private const val TOGGLE_DEBOUNCE_MS = 600L

/** Frames the camera's AWB gets for the first white estimate: at least ~0.3 s, at most ~0.7 s. */
private const val MIN_WHITE_FRAMES = 8
private const val MAX_WHITE_FRAMES = 20


sealed interface CameraState {
    data object Idle : CameraState
    data object Opening : CameraState
    data class Ready(val format: String) : CameraState
    data class Recording(val format: String, val startedAt: Long) : CameraState
    data class Error(val message: String) : CameraState
}

/** Auto exposure, white balance and focus, each frozen independently. */
data class Locks(val ae: Boolean = false, val awb: Boolean = false, val af: Boolean = false)

/**
 * Owns the camera, the capture session and the recorder. All camera work runs on one
 * dedicated thread; public methods only post to it.
 *
 * Instant-record design: the encoder's input surface is created once (persistent) and is
 * part of the capture session from the start. Starting a recording therefore never
 * reconfigures the session — it only adds that surface to the repeating request.
 */
class CameraController(context: Context) {
    private val appContext = context.applicationContext
    private val manager = appContext.getSystemService(CameraManager::class.java)
    private val thread = HandlerThread("camera", Process.THREAD_PRIORITY_DISPLAY).apply { start() }
    private val handler = Handler(thread.looper)
    private val executor = Executor { handler.post(it) }

    private val _state = MutableStateFlow<CameraState>(CameraState.Idle)
    val state: StateFlow<CameraState> = _state.asStateFlow()

    /** Held by the user only; starting a recording never changes them. */
    private val _locks = MutableStateFlow(Locks())
    val locks: StateFlow<Locks> = _locks.asStateFlow()

    private val _settings = MutableStateFlow(CaptureSettings.load(appContext))
    val settings: StateFlow<CaptureSettings> = _settings.asStateFlow()

    /** Lenses the user can pick; empty until the camera thread has looked them up. */
    private val _lenses = MutableStateFlow<List<Lens>>(emptyList())
    val lenses: StateFlow<List<Lens>> = _lenses.asStateFlow()

    val audioInput = AudioInput(appContext, handler) { handler.post { updateMeter() } }

    private val meter = AudioMeter()
    /** Live mic level per channel; null while the mic is off or the camera is closed. */
    val audioLevels: StateFlow<List<ChannelLevel>?> = meter.levels

    /** Active format; null until resolved. The preview surface must match its preview size. */
    private val _config = MutableStateFlow<VideoConfig?>(null)
    val config: StateFlow<VideoConfig?> = _config.asStateFlow()

    /** What the sensor is doing right now; null until the first frame. */
    private val _exposure = MutableStateFlow<Exposure?>(null)
    val exposure: StateFlow<Exposure?> = _exposure.asStateFlow()

    /** Display rotation in degrees; read when a recording starts. */
    @Volatile
    var displayRotation: () -> Int = { 90 }

    /** Size the preview surface must have, or null while no format is resolved. */
    val previewSize: Size? get() = _config.value?.previewSize

    // Everything below is only touched on the camera thread.
    private var wantOpen = false
    private var opening = false
    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var sessionPending = false
    private var previewSurface: Surface? = null
    private var recorderSurface: Surface? = null
    private var openedId: String? = null
    private var tenBitAllowed = true
    private var eisAllowed = true
    private var hevcLevel: Int? = null
    private var lastFocusDistance: Float? = null
    private var lastExposureUpdate = 0L
    private var startupLogged = false
    private var recording: ActiveRecording? = null
    private var lastToggleAt = 0L
    private val brightnessLoop = BrightnessLoop(handler)

    /** Automatic white for matched colors; its white point survives lens switches. */
    private val whiteLoop = WhiteBalanceLoop(handler) { applyColor() }
    private var whiteFrames = 0

    /** The request currently repeating, kept so the exposure glide only touches two values. */
    private var repeating: CaptureRequest.Builder? = null

    private class ActiveRecording(val recorder: MediaRecorder, val output: VideoOutput)

    private val tenBit
        get() = tenBitAllowed && _config.value?.hlgSupported == true && _settings.value.colorProfile.tenBit

    /** What actually gets recorded: Log needs the 10-bit session and a curve the ISP accepts. */
    private val colorProfile: ColorProfile
        get() = when {
            !tenBit -> ColorProfile.REC709
            _settings.value.colorProfile == ColorProfile.LOG && _config.value?.toneCurves != null -> ColorProfile.LOG
            else -> ColorProfile.HLG
        }

    /** Our own gains, matrix and tone curve, identical on every lens, instead of the vendor's. */
    private val matched: Boolean
        get() = _settings.value.matchColors && _config.value?.let { it.sensorColor != null && it.toneCurves != null } == true

    /** White point to render with; null while the camera's AWB still measures the first one. */
    private val white: Xy?
        get() = when (val kelvin = _settings.value.whiteBalance) {
            WB_AUTO -> whiteLoop.white
            else -> Xy.ofKelvin(kelvin)
        }

    /** Auto white without a first estimate yet: the camera's own AWB runs for a few frames. */
    private val measuringWhite: Boolean
        get() = matched && _settings.value.whiteBalance == WB_AUTO && whiteLoop.white == null

    /** Gains and matrix for the lens in use, both in BT.709: the ISP converts to BT.2020 itself. */
    private fun colorCorrection(config: VideoConfig): ColorCorrection? =
        config.sensorColor?.let { sensor -> white?.let { sensor.correction(it, Primaries.BT709) } }

    private val formatLabel
        get() = _config.value?.label.orEmpty() + " · " + when (colorProfile) {
            ColorProfile.REC709 -> "Rec.709 8-bit"
            ColorProfile.HLG -> "HLG 10-bit"
            ColorProfile.LOG -> "Apple Log 10-bit"
        }

    init {
        handler.post { resolveConfig() }
    }

    /** Camera thread. Picks the saved lens (or the main one) and resolves its format. */
    private fun resolveConfig(): VideoConfig? = try {
        val lenses = _lenses.value.ifEmpty { Lens.discover(manager).also { _lenses.value = it } }
        val wanted = _settings.value
        // Default: the back lens closest to a classic 24mm main camera.
        val lens = lenses.firstOrNull { it.key == wanted.lensKey }
            ?: lenses.filter { !it.front }.minByOrNull { kotlin.math.abs(it.equivalentMm - 24) }
            ?: lenses.first()
        VideoConfig.select(manager, lens, wanted.format, ColorCalibration.load(appContext, lens.characteristicsId)).also {
            whiteLoop.sensor = it.sensorColor
            tenBitAllowed = true
            eisAllowed = true
            if (it.lens != _config.value?.lens) {
                brightnessLoop.reset()
                _exposure.value = null
            }
            _config.value = it
        }
    } catch (e: Exception) {
        fail("Keine Kamera gefunden", e)
        null
    }

    /** Change what the user picked. Only exposure may change while recording. */
    fun updateSettings(change: (CaptureSettings) -> CaptureSettings) {
        handler.post {
            val prev = _settings.value
            val next = change(prev)
            if (next == prev) return@post
            if (recording != null && !next.onlyExposureDiffers(prev)) return@post
            _settings.value = next
            next.save(appContext)
            if (next.onlyExposureDiffers(prev)) {
                runCatching { setRepeating() }
                return@post
            }
            audioInput.route(next.mic, active = wantOpen)
            updateMeter()
            if (next.sameCamera(prev)) {
                // HLG and Log share the session; only the tonemap curve of the request changes.
                if ((next.colorProfile != prev.colorProfile || next.matchColors != prev.matchColors) && session != null) {
                    runCatching { setRepeating() }
                    _state.value = CameraState.Ready(formatLabel)
                }
                return@post
            }

            closeSession()
            recorderSurface?.release()
            recorderSurface = null
            val old = _config.value
            val oldPreview = old?.previewSize
            val cfg = resolveConfig() ?: return@post
            // Rebinding a session of the open logical camera to another physical lens crashes the
            // Xiaomi 15 Ultra camera HAL (sensor acquire fails); a fresh open is reliable.
            if (cfg.lens.openId != openedId || cfg.lens.key != old?.lens?.key) {
                device?.close()
                device = null
                openedId = null
            }
            // A new preview size means the UI builds a new surface and hands it to us.
            if (cfg.previewSize != oldPreview) previewSurface = null
            if (wantOpen) openInternal()
            createSessionIfReady()
        }
    }

    fun open() {
        handler.post {
            wantOpen = true
            audioInput.route(_settings.value.mic, active = true)
            openInternal()
        }
    }

    fun close() {
        handler.post {
            wantOpen = false
            closeInternal()
            audioInput.route(_settings.value.mic, active = false)
            updateMeter()
        }
    }

    fun release() {
        handler.post {
            wantOpen = false
            closeInternal()
            meter.stop()
            audioInput.release()
            recorderSurface?.release()
            recorderSurface = null
            thread.quitSafely()
        }
    }

    fun setPreviewSurface(surface: Surface) {
        handler.post {
            if (previewSurface === surface) return@post
            closeSession()
            previewSurface = surface
            createSessionIfReady()
        }
    }

    /** Blocks briefly: the surface is invalid once SurfaceHolder.surfaceDestroyed returns. */
    fun clearPreviewSurface() {
        val done = CountDownLatch(1)
        handler.post {
            closeSession()
            previewSurface = null
            if (device != null) _state.value = CameraState.Opening
            done.countDown()
        }
        done.await(1, TimeUnit.SECONDS)
    }

    fun toggleRecording() {
        handler.post {
            val now = SystemClock.elapsedRealtime()
            if (now - lastToggleAt < TOGGLE_DEBOUNCE_MS) return@post
            lastToggleAt = now
            if (recording == null) startRecording() else stopRecording()
        }
    }

    /** "Measure once, then lock". Works during a recording too. */
    fun setLocks(change: (Locks) -> Locks) {
        handler.post {
            _locks.value = change(_locks.value)
            runCatching { setRepeating() }
        }
    }

    @SuppressLint("MissingPermission") // the activity only calls open() once permissions are granted
    private fun openInternal() {
        if (device != null || opening) return
        val cfg = _config.value ?: resolveConfig() ?: return
        opening = true
        _state.value = CameraState.Opening
        try {
            manager.openCamera(cfg.lens.openId, executor, deviceCallback)
        } catch (e: Exception) {
            opening = false
            fail("Kamera lässt sich nicht öffnen", e)
            return
        }
        // Runs while the camera HAL is still opening, so it costs no startup time.
        ensureRecorderSurface()
    }

    private fun closeInternal() {
        closeSession()
        device?.close()
        device = null
        openedId = null
        _state.value = CameraState.Idle
    }

    private val deviceCallback = object : CameraDevice.StateCallback() {
        override fun onOpened(camera: CameraDevice) {
            opening = false
            if (!wantOpen) {
                camera.close()
                return
            }
            // Settings may have switched to another camera while this one was opening.
            if (camera.id != _config.value?.lens?.openId) {
                camera.close()
                openInternal()
                return
            }
            device = camera
            openedId = camera.id
            createSessionIfReady()
        }

        override fun onDisconnected(camera: CameraDevice) {
            opening = false
            if (device === camera) closeInternal() else camera.close()
            _state.value = CameraState.Error("Kamera wurde von einer anderen App übernommen")
        }

        override fun onError(camera: CameraDevice, error: Int) {
            opening = false
            if (device === camera) closeInternal() else camera.close()
            fail("Kamerafehler ($error)")
        }
    }

    /**
     * The persistent surface must be backed by a prepared encoder once before the camera
     * may use it, so we prepare and immediately release a throwaway recorder.
     */
    private fun ensureRecorderSurface(): Surface? {
        recorderSurface?.let { return it }
        val config = _config.value ?: return null
        val candidates = if (tenBit) config.hevcLevels else listOf(null)
        val warmup = File(appContext.cacheDir, "warmup.mp4")
        for (level in candidates) {
            val surface = MediaCodec.createPersistentInputSurface()
            try {
                buildRecorder(appContext, surface, config, level, 0, null, null, warmup).apply {
                    try {
                        prepare()
                    } finally {
                        release()
                    }
                }
                hevcLevel = level
                recorderSurface = surface
                Log.i(TAG, "encoder ready: level=$level bitrate=${config.bitrate}")
                return surface
            } catch (e: Exception) {
                surface.release()
                Log.w(TAG, "encoder rejected level=$level", e)
            } finally {
                warmup.delete()
            }
        }
        if (tenBit) {
            Log.w(TAG, "10-bit HEVC encoder unavailable, falling back to 8-bit")
            tenBitAllowed = false
            return ensureRecorderSurface()
        }
        fail("Encoder lässt sich nicht starten")
        return null
    }

    private fun createSessionIfReady() {
        val dev = device ?: return
        val preview = previewSurface ?: return
        if (session != null || sessionPending) return
        val config = _config.value ?: return
        if (dev.id != config.lens.openId) return
        val encoder = ensureRecorderSurface() ?: return

        val profile = if (tenBit) DynamicRangeProfiles.HLG10 else DynamicRangeProfiles.STANDARD
        val outputs = listOf(preview, encoder).map { surface ->
            OutputConfiguration(surface).apply {
                dynamicRangeProfile = profile
                config.lens.physicalId?.let { setPhysicalCameraId(it) }
            }
        }
        val callback = object : CameraCaptureSession.StateCallback() {
            override fun onConfigured(s: CameraCaptureSession) {
                sessionPending = false
                if (device !== dev || previewSurface !== preview) {
                    s.close()
                    return
                }
                session = s
                try {
                    setRepeating()
                    _state.value = CameraState.Ready(formatLabel)
                    logStartupTime()
                    // Only now: opening the mic next to the camera HAL costs startup time.
                    updateMeter()
                } catch (e: Exception) {
                    fail("Vorschau lässt sich nicht starten", e)
                }
            }

            override fun onConfigureFailed(s: CameraCaptureSession) {
                sessionPending = false
                if (device !== dev) return
                if (eisActive) {
                    Log.w(TAG, "session with electronic stabilization rejected, retrying without")
                    eisAllowed = false
                    createSessionIfReady()
                } else if (tenBit) {
                    Log.w(TAG, "HLG10 session rejected, retrying in 8-bit")
                    tenBitAllowed = false
                    recorderSurface?.release()
                    recorderSurface = null
                    createSessionIfReady()
                } else {
                    fail("Kamera-Session konnte nicht erstellt werden")
                }
            }
        }
        sessionPending = true
        try {
            val sessionConfig = SessionConfiguration(SessionConfiguration.SESSION_REGULAR, outputs, executor, callback)
            // Stabilization can change the stream pipeline, so tell the HAL up front.
            sessionConfig.sessionParameters = buildRequest(dev, listOf(preview)).build()
            dev.createCaptureSession(sessionConfig)
        } catch (e: Exception) {
            sessionPending = false
            fail("Kamera-Session konnte nicht erstellt werden", e)
        }
    }

    private fun closeSession() {
        stopRecording()
        brightnessLoop.meter(null)
        whiteLoop.meter(null)
        repeating = null
        session?.close()
        session = null
    }

    private val eisActive: Boolean
        get() = eisAllowed && _settings.value.stabilization == Stabilization.STANDARD && _config.value?.eisMode != null

    /**
     * TEMPLATE_RECORD for preview too, so nothing jumps when recording starts.
     * Each lock freezes its auto value; a focus lock pins the last measured distance.
     */
    private fun buildRequest(dev: CameraDevice, targets: List<Surface>): CaptureRequest.Builder {
        val config = _config.value!!
        val locks = _locks.value
        val stab = _settings.value.stabilization
        return dev.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
            targets.forEach(::addTarget)
            set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, Range(config.fps, config.fps))
            set(
                CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE,
                if (eisActive) config.eisMode else CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_OFF,
            )
            if (config.oisAvailable) {
                set(
                    CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE,
                    if (stab == Stabilization.OFF) CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_OFF
                    else CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_ON,
                )
            }
            config.noiseReductionMode?.let { set(CaptureRequest.NOISE_REDUCTION_MODE, it) }
            config.edgeMode?.let { set(CaptureRequest.EDGE_MODE, it) }
            val correction = if (matched) colorCorrection(config) else null
            if (correction != null) {
                set(CaptureRequest.CONTROL_AWB_MODE, CameraMetadata.CONTROL_AWB_MODE_OFF)
                set(CaptureRequest.COLOR_CORRECTION_MODE, CameraMetadata.COLOR_CORRECTION_MODE_TRANSFORM_MATRIX)
                set(CaptureRequest.COLOR_CORRECTION_GAINS, correction.gains)
                set(CaptureRequest.COLOR_CORRECTION_TRANSFORM, correction.transform)
            } else {
                set(CaptureRequest.CONTROL_AWB_LOCK, locks.awb)
            }
            // Log always needs our curve; matched colors use ours for HLG and Rec.709 too.
            val curves = config.toneCurves
            if (curves != null && (matched || colorProfile == ColorProfile.LOG)) {
                set(CaptureRequest.TONEMAP_MODE, CameraMetadata.TONEMAP_MODE_CONTRAST_CURVE)
                set(CaptureRequest.TONEMAP_CURVE, curves.of(colorProfile))
            }
            applyExposure(this, config, locks)
            val focus = lastFocusDistance
            if (locks.af && config.manualFocus && focus != null) {
                set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_OFF)
                set(CaptureRequest.LENS_FOCUS_DISTANCE, focus)
            } else {
                set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
            }
        }
    }

    /**
     * Fixed values go straight to the sensor. A fixed shutter with auto ISO (or the reverse)
     * uses the camera's priority AE where it exists, otherwise our [BrightnessLoop].
     * The AE lock freezes whatever is automatic.
     */
    private fun applyExposure(request: CaptureRequest.Builder, config: VideoConfig, locks: Locks) = request.apply {
        val target = ExposureTarget.of(_settings.value, config)
        // Re-entering the loop starts from what the sensor does then, not from an old scene.
        if (target.mode != AeMode.LOOP) brightnessLoop.reset()
        when (target.mode) {
            AeMode.CAMERA -> set(CaptureRequest.CONTROL_AE_LOCK, locks.ae)
            AeMode.PRIORITY -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA) {
                if (target.exposureNs != null) {
                    set(CaptureRequest.CONTROL_AE_PRIORITY_MODE, CameraMetadata.CONTROL_AE_PRIORITY_MODE_SENSOR_EXPOSURE_TIME_PRIORITY)
                    set(CaptureRequest.SENSOR_EXPOSURE_TIME, target.exposureNs)
                } else {
                    set(CaptureRequest.CONTROL_AE_PRIORITY_MODE, CameraMetadata.CONTROL_AE_PRIORITY_MODE_SENSOR_SENSITIVITY_PRIORITY)
                    set(CaptureRequest.SENSOR_SENSITIVITY, target.iso)
                }
                set(CaptureRequest.CONTROL_AE_LOCK, locks.ae)
            }
            AeMode.MANUAL, AeMode.LOOP -> {
                val (ns, iso) = if (target.mode == AeMode.LOOP) {
                    brightnessLoop.resolve(target, config, _exposure.value)
                } else {
                    target.exposureNs!! to target.iso!!
                }
                set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_OFF)
                set(CaptureRequest.SENSOR_EXPOSURE_TIME, ns)
                set(CaptureRequest.SENSOR_SENSITIVITY, iso)
                set(CaptureRequest.SENSOR_FRAME_DURATION, config.frameDurationNs)
            }
        }
    }

    /** Preview always; the encoder only while recording. */
    private fun setRepeating() {
        val s = session ?: return
        val dev = device ?: return
        val targets = listOfNotNull(previewSurface, recorderSurface.takeIf { recording != null || startingRecording })
        if (targets.isEmpty()) return
        val builder = buildRequest(dev, targets)
        s.setRepeatingRequest(builder.build(), captureCallback, handler)
        repeating = builder
        val config = _config.value ?: return
        val looping = ExposureTarget.of(_settings.value, config).mode == AeMode.LOOP && !_locks.value.ae
        brightnessLoop.profile = colorProfile
        brightnessLoop.ownCurves = matched
        brightnessLoop.meter(previewSurface.takeIf { looping })
        val followLight = matched && _settings.value.whiteBalance == WB_AUTO && !_locks.value.awb && !measuringWhite
        whiteLoop.meter(previewSurface.takeIf { followLight })
    }

    /** New white from [whiteLoop]: same request, new gains and matrix. */
    private fun applyColor() {
        val s = session ?: return
        val builder = repeating ?: return
        val correction = colorCorrection(_config.value ?: return) ?: return
        builder.set(CaptureRequest.COLOR_CORRECTION_GAINS, correction.gains)
        builder.set(CaptureRequest.COLOR_CORRECTION_TRANSFORM, correction.transform)
        runCatching { s.setRepeatingRequest(builder.build(), captureCallback, handler) }
    }

    /**
     * Camera thread. The camera's AWB converged (or had its chance): turn its raw neutral into
     * a lens-independent white point and switch to our own colors.
     */
    private fun takeFirstWhite(result: TotalCaptureResult) {
        // "Converged" on the very first frames is the AWB's start value, not a measurement.
        val converged = result[CaptureResult.CONTROL_AWB_STATE] == CameraMetadata.CONTROL_AWB_STATE_CONVERGED
        whiteFrames++
        if (whiteFrames < MIN_WHITE_FRAMES || (!converged && whiteFrames < MAX_WHITE_FRAMES)) return
        val n = result[CaptureResult.SENSOR_NEUTRAL_COLOR_POINT] ?: return
        val sensor = _config.value?.sensorColor ?: return
        whiteFrames = 0
        val g = n[1].toDouble()
        whiteLoop.white = Xy.plausible(sensor.xyFor(doubleArrayOf(n[0].toDouble() / g, 1.0, n[2].toDouble() / g)))
        Log.i(TAG, "first white ${whiteLoop.white?.cct?.toInt()} K from the camera's AWB")
        runCatching { setRepeating() }
    }

    /** One frame of the brightness glide: same request, new exposure values. */
    private fun glideExposure(config: VideoConfig) {
        val s = session ?: return
        val builder = repeating ?: return
        val (ns, iso) = brightnessLoop.resolve(ExposureTarget.of(_settings.value, config), config, _exposure.value)
        if (builder[CaptureRequest.SENSOR_EXPOSURE_TIME] == ns && builder[CaptureRequest.SENSOR_SENSITIVITY] == iso) return
        builder.set(CaptureRequest.SENSOR_EXPOSURE_TIME, ns)
        builder.set(CaptureRequest.SENSOR_SENSITIVITY, iso)
        runCatching { s.setRepeatingRequest(builder.build(), captureCallback, handler) }
    }

    private val captureCallback = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(s: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
            if (s === session && measuringWhite) takeFirstWhite(result)
            // While locked, keep the distance focus was pinned to rather than the lens readout.
            if (request[CaptureRequest.CONTROL_AF_MODE] != CameraMetadata.CONTROL_AF_MODE_OFF) {
                lastFocusDistance = result[CaptureResult.LENS_FOCUS_DISTANCE]
            }
            val iso = result[CaptureResult.SENSOR_SENSITIVITY] ?: return
            val exposureNs = result[CaptureResult.SENSOR_EXPOSURE_TIME] ?: return
            val config = _config.value ?: return
            val looping = request[CaptureRequest.CONTROL_AE_MODE] == CameraMetadata.CONTROL_AE_MODE_OFF &&
                ExposureTarget.of(_settings.value, config).mode == AeMode.LOOP
            if (looping && s === session && brightnessLoop.step(iso.toDouble() * exposureNs, config.frameDurationNs)) {
                glideExposure(config)
            }
            val now = SystemClock.elapsedRealtime()
            if (now - lastExposureUpdate < 250) return
            lastExposureUpdate = now
            _exposure.value = Exposure(iso, exposureNs, atLimit = looping && brightnessLoop.atLimit)
        }
    }

    /** Time from process start to a live, record-ready preview (adb logcat -s CameraController). */
    private fun logStartupTime() {
        if (startupLogged) return
        startupLogged = true
        Log.i(TAG, "record-ready ${SystemClock.elapsedRealtime() - Process.getStartElapsedRealtime()} ms after process start")
    }

    private var startingRecording = false

    private fun startRecording() {
        if (session == null || previewSurface == null) return
        val encoder = recorderSurface ?: return
        val config = _config.value ?: return

        val output = try {
            VideoOutput.create(appContext, colorProfile)
        } catch (e: Exception) {
            fail("Speicher nicht verfügbar", e)
            return
        }
        // Back camera: sensor orientation minus how far the display is turned; front is mirrored.
        val hint = if (config.lens.front) {
            (config.sensorOrientation + displayRotation()) % 360
        } else {
            (config.sensorOrientation - displayRotation() + 360) % 360
        }
        var recorder: MediaRecorder? = null
        try {
            val audio = audioInput.choose(_settings.value.mic, _settings.value.micProcessing)
            // HyperOS hands an UNPROCESSED input to one client only and silences the rest.
            val exclusive = audio?.audioSource == MediaRecorder.AudioSource.UNPROCESSED
            if (exclusive) meter.stop(join = true)
            recorder = buildRecorder(appContext, encoder, config, hevcLevel, hint, audio, output.fd.fileDescriptor)
            try {
                recorder.prepare()
                recorder.start()
            } catch (e: Exception) {
                // A phone that can't share the mic with the meter still has to record: the take wins.
                if (audio == null || !meter.running) throw e
                Log.w(TAG, "recorder failed next to the level meter, retrying without it", e)
                recorder.release()
                recorder = null
                meter.stop(join = true)
                recorder = buildRecorder(appContext, encoder, config, hevcLevel, hint, audio, output.fd.fileDescriptor)
                recorder.prepare()
                recorder.start()
            }
            if (audio != null && !meter.running) meter.follow(recorder, audio.channels, handler)
            // Feed the encoder only once it runs, otherwise the camera can stall on full buffers.
            startingRecording = true
            try {
                setRepeating()
            } finally {
                startingRecording = false
            }
        } catch (e: Exception) {
            if (meter.following) meter.stop()
            recorder?.release()
            output.discard()
            fail("Aufnahme konnte nicht gestartet werden", e)
            updateMeter()
            return
        }
        recording = ActiveRecording(recorder, output)
        _state.value = CameraState.Recording(formatLabel, SystemClock.elapsedRealtime())
    }

    private fun stopRecording() {
        val active = recording ?: return
        recording = null
        // Stop feeding the encoder first, then finalize the file.
        runCatching { setRepeating() }
        if (meter.following) meter.stop()
        val stopped = runCatching { active.recorder.stop() }
            .onFailure { Log.e(TAG, "MediaRecorder.stop failed", it) }
            .isSuccess
        active.recorder.release()
        if (stopped) active.output.publish() else active.output.discard()
        _state.value = if (session != null) CameraState.Ready(formatLabel) else CameraState.Opening
        updateMeter()
    }

    /**
     * Camera thread. Meters the mic the next recording will use while the camera is open.
     * Left alone during a take so the recording's input is never touched.
     */
    private fun updateMeter() {
        if (recording != null) return
        val s = _settings.value
        meter.meter(if (wantOpen) audioInput.choose(s.mic, s.micProcessing) else null)
    }

    private fun fail(message: String, cause: Throwable? = null) {
        Log.e(TAG, message, cause)
        _state.value = CameraState.Error(message)
    }
}
