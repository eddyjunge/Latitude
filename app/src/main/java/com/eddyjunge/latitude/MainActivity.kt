package com.eddyjunge.latitude

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.view.KeyEvent
import android.view.Surface
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.eddyjunge.latitude.camera.CameraController
import com.eddyjunge.latitude.camera.CameraState
import com.eddyjunge.latitude.gallery.GalleryActivity
import com.eddyjunge.latitude.probe.ProbeActivity
import com.eddyjunge.latitude.ui.CameraScreen

private val REQUIRED_PERMISSIONS = arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)

/** Keys that start/stop a recording. Consumed so the volume never changes in the app. */
private val RECORD_KEYS = setOf(KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent.KEYCODE_CAMERA)

/**
 * Untouched this long (and not recording), the camera switches off. A live preview alone keeps
 * the camera HAL at about 1.5 CPU cores, so an idle viewfinder heats the phone almost like filming.
 */
private const val STANDBY_AFTER_MS = 90_000L

class MainActivity : ComponentActivity() {
    private lateinit var camera: CameraController
    private val powerManager by lazy { getSystemService(PowerManager::class.java) }

    private var hasPermissions by mutableStateOf(false)
    private var thermalStatus by mutableIntStateOf(PowerManager.THERMAL_STATUS_NONE)
    private var standby by mutableStateOf(false)
    private val idle = Handler(Looper.getMainLooper())
    private val enterStandby = Runnable {
        if (camera.state.value is CameraState.Recording) {
            scheduleStandby()
        } else {
            standby = true
            camera.close()
            // The screen may sleep like anywhere else now.
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }
    private val thermalListener = PowerManager.OnThermalStatusChangedListener { thermalStatus = it }

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            hasPermissions = allPermissionsGranted()
            if (hasPermissions) camera.open()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        camera = CameraController(this)
        camera.displayRotation = { rotationDegrees(display.rotation) }
        hasPermissions = allPermissionsGranted()

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.getInsetsController(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        setContent {
            CameraScreen(
                camera = camera,
                hasPermissions = hasPermissions,
                thermalStatus = thermalStatus,
                standby = standby,
                onRequestPermissions = { permissionLauncher.launch(REQUIRED_PERMISSIONS) },
                onOpenProbe = { startActivity(Intent(this, ProbeActivity::class.java)) },
                onOpenGallery = { startActivity(Intent(this, GalleryActivity::class.java)) },
            )
        }
        if (!hasPermissions) permissionLauncher.launch(REQUIRED_PERMISSIONS)
    }

    override fun onStart() {
        super.onStart()
        standby = false
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        scheduleStandby()
        if (hasPermissions) camera.open()
        thermalStatus = powerManager.currentThermalStatus
        powerManager.addThermalStatusListener(mainExecutor, thermalListener)
    }

    override fun onStop() {
        idle.removeCallbacks(enterStandby)
        powerManager.removeThermalStatusListener(thermalListener)
        camera.close()
        super.onStop()
    }

    override fun onDestroy() {
        camera.release()
        super.onDestroy()
    }

    /** Any touch or key restarts the standby countdown, or ends standby. */
    override fun onUserInteraction() {
        super.onUserInteraction()
        if (standby) wake(record = false) else scheduleStandby()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        // In standby the record key still records: wake up and start as soon as the camera is live.
        if (standby && hasPermissions && event.keyCode in RECORD_KEYS) {
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) wake(record = true)
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    private fun wake(record: Boolean) {
        standby = false
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (record) camera.openAndRecord() else if (hasPermissions) camera.open()
        scheduleStandby()
    }

    private fun scheduleStandby() {
        idle.removeCallbacks(enterStandby)
        idle.postDelayed(enterStandby, STANDBY_AFTER_MS)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode !in RECORD_KEYS) return super.onKeyDown(keyCode, event)
        if (hasPermissions && event.repeatCount == 0) camera.toggleRecording()
        return true
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean =
        keyCode in RECORD_KEYS || super.onKeyUp(keyCode, event)

    private fun allPermissionsGranted() =
        REQUIRED_PERMISSIONS.all { checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }

    private fun rotationDegrees(rotation: Int) = when (rotation) {
        Surface.ROTATION_90 -> 90
        Surface.ROTATION_180 -> 180
        Surface.ROTATION_270 -> 270
        else -> 0
    }
}
