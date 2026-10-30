package com.eddyjunge.latitude.ui

import android.os.PowerManager
import android.os.SystemClock
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.eddyjunge.latitude.camera.CameraController
import com.eddyjunge.latitude.camera.CameraState
import kotlinx.coroutines.delay

private val RecordRed = Color(0xFFFF3B30)
private val Secondary = Color(0x99FFFFFF)
private val Warning = Color(0xFFFF9F0A)

private val LabelStyle = TextStyle(
    color = Secondary,
    fontSize = 13.sp,
    fontWeight = FontWeight.Medium,
    letterSpacing = 0.5.sp,
    fontFeatureSettings = "tnum",
)

@Composable
fun CameraScreen(
    camera: CameraController,
    hasPermissions: Boolean,
    thermalStatus: Int,
    onRequestPermissions: () -> Unit,
    onOpenProbe: () -> Unit,
) {
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (!hasPermissions) {
            BasicText(
                text = "Kamera und Mikrofon erlauben",
                style = LabelStyle.copy(color = Color.White, fontSize = 17.sp),
                modifier = Modifier.align(Alignment.Center).clickable(onClick = onRequestPermissions).padding(24.dp),
            )
            return@Box
        }

        val state by camera.state.collectAsState()

        CameraPreview(camera, Modifier.align(Alignment.Center).fillMaxHeight().aspectRatio(16f / 9f))

        StatusBar(state, thermalStatus, Modifier.align(Alignment.TopCenter).padding(top = 16.dp))

        // Development entry point for the stage-0 capability probe; hidden while recording.
        if (state !is CameraState.Recording) {
            BasicText(
                text = "PROBE",
                style = LabelStyle,
                modifier = Modifier.align(Alignment.TopStart).clickable(onClick = onOpenProbe).padding(20.dp),
            )
        }

        RecordButton(
            recording = state is CameraState.Recording,
            enabled = state is CameraState.Ready || state is CameraState.Recording,
            onClick = camera::toggleRecording,
            modifier = Modifier.align(Alignment.CenterEnd).padding(end = 28.dp),
        )

        (state as? CameraState.Error)?.let {
            BasicText(
                text = it.message,
                style = LabelStyle.copy(color = Color.White, fontSize = 15.sp),
                modifier = Modifier.align(Alignment.Center)
                    .background(Color(0xCC000000), RoundedCornerShape(12.dp))
                    .padding(horizontal = 20.dp, vertical = 12.dp),
            )
        }
    }
}

@Composable
private fun CameraPreview(camera: CameraController, modifier: Modifier) {
    AndroidView(
        modifier = modifier,
        factory = { context ->
            SurfaceView(context).apply {
                holder.addCallback(object : SurfaceHolder.Callback {
                    override fun surfaceCreated(holder: SurfaceHolder) {
                        // The camera can only render into a size it supports, not the view size.
                        camera.previewSize?.let { holder.setFixedSize(it.width, it.height) }
                    }

                    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                        val size = camera.previewSize ?: return
                        if (width == size.width && height == size.height) camera.setPreviewSurface(holder.surface)
                    }

                    override fun surfaceDestroyed(holder: SurfaceHolder) = camera.clearPreviewSurface()
                })
            }
        },
    )
}

@Composable
private fun StatusBar(state: CameraState, thermalStatus: Int, modifier: Modifier) {
    Row(
        modifier = modifier
            .background(Color(0x66000000), RoundedCornerShape(50))
            .padding(horizontal = 14.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        when (state) {
            is CameraState.Recording -> {
                Box(Modifier.size(8.dp).background(RecordRed, CircleShape))
                RecordingTimer(state.startedAt)
                BasicText(state.format, style = LabelStyle)
            }
            is CameraState.Ready -> BasicText(state.format, style = LabelStyle)
            else -> BasicText("…", style = LabelStyle)
        }
        thermalLabel(thermalStatus)?.let { (text, color) ->
            Spacer(Modifier.width(4.dp))
            BasicText(text, style = LabelStyle.copy(color = color, fontWeight = FontWeight.SemiBold))
        }
    }
}

@Composable
private fun RecordingTimer(startedAt: Long) {
    var now by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(startedAt) {
        while (true) {
            now = SystemClock.elapsedRealtime()
            delay(250)
        }
    }
    val total = ((now - startedAt) / 1000).coerceAtLeast(0)
    val text = "%02d:%02d:%02d".format(total / 3600, total / 60 % 60, total % 60)
    BasicText(text, style = LabelStyle.copy(color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold))
}

private fun thermalLabel(status: Int): Pair<String, Color>? = when {
    status >= PowerManager.THERMAL_STATUS_SEVERE -> "HEISS" to RecordRed
    status >= PowerManager.THERMAL_STATUS_MODERATE -> "WARM" to Warning
    else -> null
}

@Composable
private fun RecordButton(recording: Boolean, enabled: Boolean, onClick: () -> Unit, modifier: Modifier) {
    val innerSize by animateDpAsState(if (recording) 28.dp else 58.dp, tween(180), label = "record-size")
    val corner by animateDpAsState(if (recording) 6.dp else 29.dp, tween(180), label = "record-corner")
    Box(
        modifier = modifier
            .size(72.dp)
            .border(3.dp, if (enabled) Color.White else Secondary, CircleShape)
            .clickable(
                enabled = enabled,
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.size(innerSize).background(RecordRed, RoundedCornerShape(corner)))
    }
}
