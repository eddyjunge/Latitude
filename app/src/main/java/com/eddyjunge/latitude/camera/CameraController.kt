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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

private const val TAG = "CameraController"

/** Ignore a second trigger within this window so one press never starts and stops at once. */
private const val TOGGLE_DEBOUNCE_MS = 600L

/** Frames the camera's AWB gets for the first white estimate: at least ~0.3 s, at most ~0.7 s. */
private const val MIN_WHITE_FRAMES = 8
private const val MAX_WHITE_FRAMES = 20

/** Longest wait at stop for frames still on their way to the encoder (~5 frames are in flight). */
private const val DRAIN_TIMEOUT_MS = 500L

/** Longest wait for the last take to let go of the encoder before a new one may use it. */
private const val FINALIZE_TIMEOUT_MS = 3000L

/** Crash recovery runs once per process; a second controller must not publish a running take. */
@Volatile
private var recovered = false


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

    /**
     * Finishes stopped takes: draining the encoder, writing the index and publishing take about
     * half a second, and on the camera thread they would freeze the viewfinder meanwhile.
     */
    private val finisher = HandlerThread("finisher").apply { start() }
    private val finisherHandler = Handler(finisher.looper)

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

    // Before audioInput: its device callback can run updateMeter() on the camera thread while
    // this constructor is still running.
    private val capture = AudioCapture()

    val audioInput = AudioInput(appContext, handler) { handler.post { updateMeter() } }

    /** Live mic level per channel; null while the mic is off or the camera is closed. */
    val audioLevels: StateFlow<List<ChannelLevel>?> = capture.levels

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
    /** Sits between camera and [previewSurface] while a session runs. */
    private var pacer: PreviewPacer? = null
    /** Stopped pacers whose session hasn't confirmed it closed yet. */
    private val retiredPacers = mutableListOf<PreviewPacer>()
    private var recorderSurface: Surface? = null
    private var openedId: String? = null
    private var tenBitAllowed = true
    private var eisAllowed = true
    private var hevcLevel: Int? = null
    private var lastFocusDistance: Float? = null
    /**
     * Real frame period from the sensor's timestamps, the take's first guess for how much audio
     * belongs to one frame. SENSOR_FRAME_DURATION only echoes the request (33.333 ms); the main
     * lens delivers a frame every 33.27 ms.
     */
    private var framePeriodNs = 0.0
    private var lastSensorTimestamp = 0L
    private var lastExposureUpdate = 0L
    private var startupLogged = false
    /** When the camera was asked to open, e.g. back from the gallery; 0 once it is live. */
    private var openRequestedAt = 0L
    private var recording: ActiveRecording? = null
    private var lastToggleAt = 0L
    private var recordWhenReady = false
    private val brightnessLoop = BrightnessLoop(handler)
    private val zebraMeter = ZebraMeter(handler) { displayRotation() }

    /** Where the viewfinder gets zebra stripes; null while they are off. */
    val zebra: StateFlow<ZebraMask?> = zebraMeter.mask

    /** Automatic white for matched colors; its white point survives lens switches. */
    private val whiteLoop = WhiteBalanceLoop(handler) { applyColor() }
    private var whiteFrames = 0

    /** The request currently repeating, kept so the exposure glide only touches two values. */
    private var repeating: CaptureRequest.Builder? = null

    private class ActiveRecording(val take: Take, val output: VideoOutput)

    /** Sequence id of the latest repeating request; at stop, the last one that fed the encoder. */
    private var lastSequence = -1

    /** A stopped take waiting for the camera to finish the frames already meant for its encoder. */
    private var finishing: ActiveRecording? = null
    private var drainSequence = -1
    private var drainStartedAt = 0L

    /** Counted down once the take handed to [finisher] has let go of the encoder surface. */
    private var finalizing: CountDownLatch? = null

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
        if (!recovered) {
            recovered = true
            finisherHandler.post { VideoOutput.recoverPending(appContext) }
        }
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
            awaitFinalized()
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
            if (!wantOpen) openRequestedAt = SystemClock.elapsedRealtime()
            wantOpen = true
            audioInput.route(_settings.value.mic, active = true)
            openInternal()
        }
    }

    /** Open (if needed) and start recording the moment the preview is live, e.g. out of standby. */
    fun openAndRecord() {
        handler.post {
            wantOpen = true
            audioInput.route(_settings.value.mic, active = true)
            lastToggleAt = SystemClock.elapsedRealtime()
            if (session != null) {
                if (recording == null) startRecording()
            } else {
                recordWhenReady = true
                openInternal()
            }
        }
    }

    fun close() {
        handler.post {
            wantOpen = false
            recordWhenReady = false
            closeInternal()
            audioInput.route(_settings.value.mic, active = false)
            updateMeter()
        }
    }

    fun release() {
        handler.post {
            wantOpen = false
            closeInternal()
            capture.stop()
            audioInput.release()
            awaitFinalized()
            recorderSurface?.release()
            recorderSurface = null
            thread.quitSafely()
            finisher.quitSafely()
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
        // A closed device delivers nothing more, whether or not onClosed came.
        retiredPacers.forEach { it.release() }
        retiredPacers.clear()
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
     * The persistent surface must be backed by a configured encoder once before the camera
     * may use it, so a throwaway encoder is configured on it and released. That also finds the
     * HEVC level the encoder accepts for this size.
     */
    private fun ensureRecorderSurface(): Surface? {
        recorderSurface?.let { return it }
        val config = _config.value ?: return null
        for (level in config.hevcLevels) {
            val surface = MediaCodec.createPersistentInputSurface()
            var codec: MediaCodec? = null
            try {
                codec = Take.createEncoder()
                codec.configure(Take.encoderFormat(config, tenBit, level), null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                codec.setInputSurface(surface)
                hevcLevel = level
                recorderSurface = surface
                Log.i(TAG, "encoder ready: ${codec.name} level=$level bitrate=${config.bitrate} tenBit=$tenBit")
                return surface
            } catch (e: Exception) {
                surface.release()
                Log.w(TAG, "encoder rejected level=$level", e)
            } finally {
                codec?.release()
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
        // Without the pacer the preview still works, only less smoothly.
        val paced = runCatching { PreviewPacer(preview, config.previewSize, handler) }
            .onFailure { Log.w(TAG, "preview pacing unavailable", it) }
            .getOrNull()
        val cameraPreview = paced?.input ?: preview
        val outputs = listOf(cameraPreview, encoder).map { surface ->
            OutputConfiguration(surface).apply {
                dynamicRangeProfile = profile
                config.lens.physicalId?.let { setPhysicalCameraId(it) }
                // The pacer schedules frames on the display's clock.
                if (surface === paced?.input) timestampBase = OutputConfiguration.TIMESTAMP_BASE_MONOTONIC
            }
        }
        val callback = object : CameraCaptureSession.StateCallback() {
            override fun onConfigured(s: CameraCaptureSession) {
                sessionPending = false
                if (device !== dev || previewSurface !== preview) {
                    paced?.stop()
                    s.close()
                    return
                }
                session = s
                pacer = paced
                try {
                    setRepeating()
                    _state.value = CameraState.Ready(formatLabel)
                    logStartupTime()
                    // Only now: opening the mic next to the camera HAL costs startup time.
                    updateMeter()
                    if (recordWhenReady) {
                        recordWhenReady = false
                        startRecording()
                    }
                } catch (e: Exception) {
                    fail("Vorschau lässt sich nicht starten", e)
                }
            }

            override fun onClosed(s: CameraCaptureSession) {
                paced?.let { retiredPacers.remove(it); it.release() }
            }

            override fun onConfigureFailed(s: CameraCaptureSession) {
                sessionPending = false
                paced?.release()
                if (device !== dev) return
                if (eisActive) {
                    Log.w(TAG, "session with electronic stabilization rejected, retrying without")
                    eisAllowed = false
                    createSessionIfReady()
                } else if (tenBit) {
                    Log.w(TAG, "HLG10 session rejected, retrying in 8-bit")
                    tenBitAllowed = false
                    awaitFinalized()
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
            sessionConfig.sessionParameters = buildRequest(dev, listOf(cameraPreview)).build()
            dev.createCaptureSession(sessionConfig)
        } catch (e: Exception) {
            sessionPending = false
            paced?.release()
            fail("Kamera-Session konnte nicht erstellt werden", e)
        }
    }

    private fun closeSession() {
        framePeriodNs = 0.0
        lastSensorTimestamp = 0L
        stopRecording(drain = false)
        finishTake()
        brightnessLoop.meter(null)
        whiteLoop.meter(null)
        zebraMeter.stop()
        repeating = null
        // Released in onClosed: the camera may still deliver until then.
        pacer?.let { it.stop(); retiredPacers += it }
        pacer = null
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
        // The camera's AE exposes Log like HLG, which is too bright for it (see LOG_OFFSET_STOPS).
        if (colorProfile == ColorProfile.LOG && (target.mode == AeMode.CAMERA || target.mode == AeMode.PRIORITY)) {
            val steps = (BrightnessLoop.LOG_OFFSET_STOPS / config.aeCompensationStep).roundToInt()
            set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, steps.coerceIn(config.aeCompensationRange.lower, config.aeCompensationRange.upper))
        }
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
        val targets = listOfNotNull(pacer?.input ?: previewSurface, recorderSurface.takeIf { recording != null || startingRecording })
        if (targets.isEmpty()) return
        val builder = buildRequest(dev, targets)
        lastSequence = s.setRepeatingRequest(builder.build(), captureCallback, handler)
        repeating = builder
        val config = _config.value ?: return
        val looping = ExposureTarget.of(_settings.value, config).mode == AeMode.LOOP && !_locks.value.ae
        brightnessLoop.profile = colorProfile
        brightnessLoop.ownCurves = matched
        brightnessLoop.meter(previewSurface.takeIf { looping })
        val followLight = matched && _settings.value.whiteBalance == WB_AUTO && !_locks.value.awb && !measuringWhite
        whiteLoop.meter(previewSurface.takeIf { followLight })
        zebraMeter.meter(previewSurface, _settings.value.zebra, colorProfile, matched, config.aspectRatio)
    }

    /** New white from [whiteLoop]: same request, new gains and matrix. */
    private fun applyColor() {
        val s = session ?: return
        val builder = repeating ?: return
        val correction = colorCorrection(_config.value ?: return) ?: return
        builder.set(CaptureRequest.COLOR_CORRECTION_GAINS, correction.gains)
        builder.set(CaptureRequest.COLOR_CORRECTION_TRANSFORM, correction.transform)
        runCatching { lastSequence = s.setRepeatingRequest(builder.build(), captureCallback, handler) }
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
        runCatching { lastSequence = s.setRepeatingRequest(builder.build(), captureCallback, handler) }
    }

    private val captureCallback = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureSequenceCompleted(s: CameraCaptureSession, sequenceId: Int, frameNumber: Long) {
            if (sequenceId == drainSequence) finishTake()
        }

        override fun onCaptureSequenceAborted(s: CameraCaptureSession, sequenceId: Int) {
            if (sequenceId == drainSequence) finishTake()
        }

        override fun onCaptureCompleted(s: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
            if (s === session && measuringWhite) takeFirstWhite(result)
            // While locked, keep the distance focus was pinned to rather than the lens readout.
            if (request[CaptureRequest.CONTROL_AF_MODE] != CameraMetadata.CONTROL_AF_MODE_OFF) {
                lastFocusDistance = result[CaptureResult.LENS_FOCUS_DISTANCE]
            }
            if (s === session) measureFramePeriod(result)
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

    /** Averages the interval between frames, ignoring gaps (dropped frames) and doubles. */
    private fun measureFramePeriod(result: TotalCaptureResult) {
        val ts = result[CaptureResult.SENSOR_TIMESTAMP] ?: return
        val last = lastSensorTimestamp
        lastSensorTimestamp = ts
        if (last == 0L) return
        val nominal = (_config.value ?: return).frameDurationNs.toDouble()
        val d = (ts - last).toDouble()
        if (d < nominal * 0.5 || d > nominal * 1.5) return
        framePeriodNs = if (framePeriodNs == 0.0) d else framePeriodNs * 0.98 + d * 0.02
    }

    /** Time from process start to a live, record-ready preview (adb logcat -s CameraController). */
    private fun logStartupTime() {
        val now = SystemClock.elapsedRealtime()
        if (openRequestedAt > 0) Log.i(TAG, "record-ready ${now - openRequestedAt} ms after open")
        openRequestedAt = 0
        if (startupLogged) return
        startupLogged = true
        Log.i(TAG, "record-ready ${now - Process.getStartElapsedRealtime()} ms after process start")
    }

    private var startingRecording = false

    private fun startRecording() {
        if (session == null || previewSurface == null) return
        // Pressed again right after a stop: the last take must let go of the encoder first.
        awaitFinalized()
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
        var take: Take? = null
        try {
            val audio = audioInput.choose(_settings.value.mic, _settings.value.micProcessing)
            // Normally already running for the meter; right out of standby it may be starting.
            if (audio != null) capture.start(audio)
            val startNs = System.nanoTime()
            take = Take(
                surface = encoder,
                config = config,
                tenBit = tenBit,
                hevcLevel = hevcLevel,
                profile = colorProfile,
                rotation = hint,
                audioSource = if (audio != null) ({ capture.ring }) else null,
                output = output,
                shot = ShotInfo(
                    lens = config.lens,
                    iso = _exposure.value?.iso,
                    exposureNs = _exposure.value?.exposureNs,
                    // Rounded like a camera dial; only when the picture is rendered with our own white.
                    kelvin = white?.takeIf { matched }?.let { (it.cct / 50).roundToInt() * 50 },
                ),
                measuredFramePeriodNs = framePeriodNs.takeIf { it > 0 }?.toLong(),
            )
            take.start(startNs)
            // Feed the encoder only once it runs, otherwise the camera can stall on full buffers.
            startingRecording = true
            try {
                setRepeating()
            } finally {
                startingRecording = false
            }
        } catch (e: Exception) {
            take?.let { t -> runCatching { t.finish() } }
            output.discard()
            fail("Aufnahme konnte nicht gestartet werden", e)
            return
        }
        recording = ActiveRecording(take, output)
        _state.value = CameraState.Recording(formatLabel, SystemClock.elapsedRealtime())
    }

    /**
     * Stops feeding the encoder, then finalizes the file once the camera has delivered the
     * frames it already had in flight for it (~150 ms). Stopping the encoder before that leaves
     * them queued in the persistent surface for the next take's encoder; suspected cause of a
     * take started 1.5 s after the last one whose first GOP was undecodable (green).
     * The button turns back at once; the file is finished in the background.
     * [drain] false finalizes at once, for a closing session.
     */
    private fun stopRecording(drain: Boolean = true) {
        val active = recording ?: return
        recording = null
        val fed = lastSequence
        val replaced = runCatching { setRepeating() }.isSuccess
        finishing = active
        _state.value = if (session != null) CameraState.Ready(formatLabel) else CameraState.Opening
        if (drain && replaced && fed >= 0) {
            drainSequence = fed
            drainStartedAt = SystemClock.elapsedRealtime()
            handler.postDelayed(::finishTake, finishTakeToken, DRAIN_TIMEOUT_MS)
        } else {
            finishTake()
        }
    }

    private val finishTakeToken = Any()

    /** Camera thread. The drained take goes to [finisher]. */
    private fun finishTake() {
        handler.removeCallbacksAndMessages(finishTakeToken)
        val drained = drainSequence >= 0
        drainSequence = -1
        val active = finishing ?: return
        finishing = null
        if (drained) Log.i(TAG, "encoder drained after ${SystemClock.elapsedRealtime() - drainStartedAt} ms")
        val done = CountDownLatch(1)
        finalizing = done
        finisherHandler.post {
            val started = SystemClock.elapsedRealtime()
            finalize(active)
            Log.i(TAG, "take finalized in ${SystemClock.elapsedRealtime() - started} ms")
            done.countDown()
            handler.post {
                if (finalizing === done) finalizing = null
                updateMeter()
            }
        }
    }

    /** Finisher thread. Only this thread touches the take once it is handed over. */
    private fun finalize(active: ActiveRecording) {
        val usable = runCatching { active.take.finish() }
            .onFailure { Log.e(TAG, "finishing the take failed", it) }
            .getOrDefault(false)
        if (usable) {
            runCatching { active.output.publish() }.onFailure { Log.e(TAG, "publish failed", it) }
        } else {
            runCatching { active.output.discard() }
        }
    }

    /**
     * Camera thread. Blocks until the last take has released the encoder surface, before that
     * surface gets a new recorder or is released. Only a stop right before costs any time.
     */
    private fun awaitFinalized() {
        finishTake()
        val done = finalizing ?: return
        if (!done.await(FINALIZE_TIMEOUT_MS, TimeUnit.MILLISECONDS)) Log.w(TAG, "last take still finalizing")
        finalizing = null
    }

    /**
     * Camera thread. Captures the mic the next recording will use while the camera is open.
     * Left alone until a take has written its last frame, so its input is never switched.
     */
    private fun updateMeter() {
        if (recording != null || finishing != null || finalizing != null) return
        val s = _settings.value
        capture.start(if (wantOpen) audioInput.choose(s.mic, s.micProcessing) else null)
    }

    private fun fail(message: String, cause: Throwable? = null) {
        Log.e(TAG, message, cause)
        _state.value = CameraState.Error(message)
    }
}
