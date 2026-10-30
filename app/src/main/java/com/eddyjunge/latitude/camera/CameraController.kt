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
import android.media.MediaCodecInfo.CodecProfileLevel
import android.media.MediaRecorder
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

/** High tier is needed above 40 Mbit/s; fall back to Main tier if the encoder refuses it. */
private val HEVC_10BIT_LEVELS = listOf(CodecProfileLevel.HEVCHighTierLevel51, CodecProfileLevel.HEVCMainTierLevel51)

sealed interface CameraState {
    data object Idle : CameraState
    data object Opening : CameraState
    data class Ready(val format: String) : CameraState
    data class Recording(val format: String, val startedAt: Long) : CameraState
    data class Error(val message: String) : CameraState
}

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

    /** Exposure, white balance and focus held by the user; always held while recording. */
    private val _locked = MutableStateFlow(false)
    val locked: StateFlow<Boolean> = _locked.asStateFlow()

    /** Live exposure readout, e.g. "ISO 100 · 1/50". */
    private val _exposure = MutableStateFlow("")
    val exposure: StateFlow<String> = _exposure.asStateFlow()

    /** Display rotation in degrees; read when a recording starts. */
    @Volatile
    var displayRotation: () -> Int = { 90 }

    private val config: VideoConfig by lazy { VideoConfig.select(manager) }

    /** Size the preview surface must have, or null if no usable camera exists. */
    val previewSize: Size? get() = runCatching { config.previewSize }.getOrNull()

    // Everything below is only touched on the camera thread.
    private var wantOpen = false
    private var opening = false
    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var sessionPending = false
    private var previewSurface: Surface? = null
    private var recorderSurface: Surface? = null
    private var tenBitAllowed = true
    private var hevcLevel: Int? = null
    private var lastFocusDistance: Float? = null
    private var lastExposureUpdate = 0L
    private var startupLogged = false
    private var recording: ActiveRecording? = null
    private var lastToggleAt = 0L

    private class ActiveRecording(val recorder: MediaRecorder, val output: VideoOutput)

    private val tenBit get() = tenBitAllowed && config.hlgSupported

    private val formatLabel
        get() = config.label + if (tenBit) " · HLG 10-bit" else " · SDR 8-bit"

    fun open() {
        handler.post {
            wantOpen = true
            openInternal()
        }
    }

    fun close() {
        handler.post {
            wantOpen = false
            closeInternal()
        }
    }

    fun release() {
        handler.post {
            wantOpen = false
            closeInternal()
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

    /** "Measure once, then lock": freezes the current auto values before framing a shot. */
    fun toggleLock() {
        handler.post {
            if (recording != null) return@post
            _locked.value = !_locked.value
            previewSurface?.let { runCatching { setRepeating(listOf(it), _locked.value) } }
        }
    }

    @SuppressLint("MissingPermission") // the activity only calls open() once permissions are granted
    private fun openInternal() {
        if (device != null || opening) return
        val cfg = try {
            config
        } catch (e: Exception) {
            fail("Keine Rückkamera gefunden", e)
            return
        }
        opening = true
        _state.value = CameraState.Opening
        try {
            manager.openCamera(cfg.cameraId, executor, deviceCallback)
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
        _state.value = CameraState.Idle
    }

    private val deviceCallback = object : CameraDevice.StateCallback() {
        override fun onOpened(camera: CameraDevice) {
            opening = false
            if (!wantOpen) {
                camera.close()
                return
            }
            device = camera
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
        val candidates = if (tenBit) HEVC_10BIT_LEVELS else listOf(null)
        val warmup = File(appContext.cacheDir, "warmup.mp4")
        for (level in candidates) {
            val surface = MediaCodec.createPersistentInputSurface()
            try {
                buildRecorder(appContext, surface, config, level, 0, null, warmup).apply {
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
        val encoder = ensureRecorderSurface() ?: return

        val profile = if (tenBit) DynamicRangeProfiles.HLG10 else DynamicRangeProfiles.STANDARD
        val outputs = listOf(preview, encoder).map { surface ->
            OutputConfiguration(surface).apply { dynamicRangeProfile = profile }
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
                    setRepeating(listOf(preview), _locked.value)
                    _state.value = CameraState.Ready(formatLabel)
                    logStartupTime()
                } catch (e: Exception) {
                    fail("Vorschau lässt sich nicht starten", e)
                }
            }

            override fun onConfigureFailed(s: CameraCaptureSession) {
                sessionPending = false
                if (device !== dev) return
                if (tenBit) {
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
            dev.createCaptureSession(
                SessionConfiguration(SessionConfiguration.SESSION_REGULAR, outputs, executor, callback)
            )
        } catch (e: Exception) {
            sessionPending = false
            fail("Kamera-Session konnte nicht erstellt werden", e)
        }
    }

    private fun closeSession() {
        stopRecording()
        session?.close()
        session = null
    }

    /**
     * TEMPLATE_RECORD for preview too, so nothing jumps when recording starts.
     * With [lock], auto exposure and white balance hold their current values and focus is
     * pinned to the last measured distance: nothing may change mid-clip.
     */
    private fun setRepeating(targets: List<Surface>, lock: Boolean) {
        val s = session ?: return
        val dev = device ?: return
        val request = dev.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
            targets.forEach(::addTarget)
            set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, Range(config.fps, config.fps))
            set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_OFF)
            if (config.opticalStabilization) {
                set(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE, CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_ON)
            }
            config.noiseReductionMode?.let { set(CaptureRequest.NOISE_REDUCTION_MODE, it) }
            config.edgeMode?.let { set(CaptureRequest.EDGE_MODE, it) }
            set(CaptureRequest.CONTROL_AE_LOCK, lock)
            set(CaptureRequest.CONTROL_AWB_LOCK, lock)
            val focus = lastFocusDistance
            if (lock && config.manualFocus && focus != null) {
                set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_OFF)
                set(CaptureRequest.LENS_FOCUS_DISTANCE, focus)
            } else {
                set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
            }
        }.build()
        s.setRepeatingRequest(request, captureCallback, handler)
    }

    private val captureCallback = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(s: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
            // While locked, keep the distance focus was pinned to rather than the lens readout.
            if (request[CaptureRequest.CONTROL_AF_MODE] != CameraMetadata.CONTROL_AF_MODE_OFF) {
                lastFocusDistance = result[CaptureResult.LENS_FOCUS_DISTANCE]
            }
            val now = SystemClock.elapsedRealtime()
            if (now - lastExposureUpdate < 250) return
            lastExposureUpdate = now
            val iso = result[CaptureResult.SENSOR_SENSITIVITY] ?: return
            val exposureNs = result[CaptureResult.SENSOR_EXPOSURE_TIME] ?: return
            _exposure.value = "ISO $iso · 1/${Math.round(1e9 / exposureNs)}"
        }
    }

    /** Time from process start to a live, record-ready preview (adb logcat -s CameraController). */
    private fun logStartupTime() {
        if (startupLogged) return
        startupLogged = true
        Log.i(TAG, "record-ready ${SystemClock.elapsedRealtime() - Process.getStartElapsedRealtime()} ms after process start")
    }

    private fun startRecording() {
        if (session == null) return
        val preview = previewSurface ?: return
        val encoder = recorderSurface ?: return

        val output = try {
            VideoOutput.create(appContext)
        } catch (e: Exception) {
            fail("Speicher nicht verfügbar", e)
            return
        }
        // Back camera: rotate by sensor orientation minus how far the display is turned.
        val hint = (config.sensorOrientation - displayRotation() + 360) % 360
        var recorder: MediaRecorder? = null
        try {
            recorder = buildRecorder(appContext, encoder, config, hevcLevel, hint, output.fd.fileDescriptor)
            recorder.prepare()
            recorder.start()
            // Feed the encoder only once it runs, otherwise the camera can stall on full buffers.
            setRepeating(listOf(preview, encoder), lock = true)
        } catch (e: Exception) {
            recorder?.release()
            output.discard()
            fail("Aufnahme konnte nicht gestartet werden", e)
            return
        }
        recording = ActiveRecording(recorder, output)
        _state.value = CameraState.Recording(formatLabel, SystemClock.elapsedRealtime())
    }

    private fun stopRecording() {
        val active = recording ?: return
        recording = null
        // Stop feeding the encoder first, then finalize the file.
        previewSurface?.let { preview -> runCatching { setRepeating(listOf(preview), _locked.value) } }
        val stopped = runCatching { active.recorder.stop() }
            .onFailure { Log.e(TAG, "MediaRecorder.stop failed", it) }
            .isSuccess
        active.recorder.release()
        if (stopped) active.output.publish() else active.output.discard()
        _state.value = if (session != null) CameraState.Ready(formatLabel) else CameraState.Opening
    }

    private fun fail(message: String, cause: Throwable? = null) {
        Log.e(TAG, message, cause)
        _state.value = CameraState.Error(message)
    }
}
