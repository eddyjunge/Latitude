package com.eddyjunge.latitude

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
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
import com.eddyjunge.latitude.gallery.GalleryActivity
import com.eddyjunge.latitude.probe.ProbeActivity
import com.eddyjunge.latitude.ui.CameraScreen

private val REQUIRED_PERMISSIONS = arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)

/** Keys that start/stop a recording. Consumed so the volume never changes in the app. */
private val RECORD_KEYS = setOf(KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent.KEYCODE_CAMERA)

class MainActivity : ComponentActivity() {
    private lateinit var camera: CameraController
    private val powerManager by lazy { getSystemService(PowerManager::class.java) }

    private var hasPermissions by mutableStateOf(false)
    private var thermalStatus by mutableIntStateOf(PowerManager.THERMAL_STATUS_NONE)
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
                onRequestPermissions = { permissionLauncher.launch(REQUIRED_PERMISSIONS) },
                onOpenProbe = { startActivity(Intent(this, ProbeActivity::class.java)) },
                onOpenGallery = { startActivity(Intent(this, GalleryActivity::class.java)) },
            )
        }
        if (!hasPermissions) permissionLauncher.launch(REQUIRED_PERMISSIONS)
    }

    override fun onStart() {
        super.onStart()
        if (hasPermissions) camera.open()
        thermalStatus = powerManager.currentThermalStatus
        powerManager.addThermalStatusListener(mainExecutor, thermalListener)
    }

    override fun onStop() {
        powerManager.removeThermalStatusListener(thermalListener)
        camera.close()
        super.onStop()
    }

    override fun onDestroy() {
        camera.release()
        super.onDestroy()
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
