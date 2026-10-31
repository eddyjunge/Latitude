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
import androidx.compose.foundation.layout.Column
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
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.eddyjunge.latitude.camera.CameraController
import com.eddyjunge.latitude.camera.CameraState
import com.eddyjunge.latitude.camera.FrameFormat
import com.eddyjunge.latitude.camera.Lens
import com.eddyjunge.latitude.camera.MicProcessing
import com.eddyjunge.latitude.camera.MicSource
import com.eddyjunge.latitude.camera.Stabilization
import com.eddyjunge.latitude.camera.VideoConfig
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
    onOpenGallery: () -> Unit,
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
        val locks by camera.locks.collectAsState()
        val settings by camera.settings.collectAsState()
        val lenses by camera.lenses.collectAsState()
        val config by camera.config.collectAsState()
        val exposure by camera.exposure.collectAsState()
        val mics by camera.audioInput.available.collectAsState()
        val recording = state is CameraState.Recording
        // Lens, format and stabilization rebuild the session, so they are frozen mid-clip.
        val canChange = !recording && state !is CameraState.Opening

        config?.let { cfg ->
            // A new preview size needs a new surface; key() throws the old view away.
            key(cfg.previewSize) {
                CameraPreview(camera, Modifier.align(Alignment.Center).fillMaxHeight().aspectRatio(cfg.aspectRatio))
            }
        }

        StatusBar(state, exposure, thermalStatus, Modifier.align(Alignment.TopCenter).padding(top = 16.dp))

        // Development entry point for the stage-0 capability probe; hidden while recording.
        if (state !is CameraState.Recording) {
            BasicText(
                text = "PROBE",
                style = LabelStyle,
                modifier = Modifier.align(Alignment.TopStart).clickable(onClick = onOpenProbe).padding(20.dp),
            )
            BasicText(
                text = "GALERIE",
                style = LabelStyle.copy(color = Color.White),
                modifier = Modifier.align(Alignment.TopEnd).clickable(onClick = onOpenGallery).padding(20.dp),
            )
        }

        LensPicker(
            lenses = lenses,
            selected = config?.lens?.key,
            enabled = canChange,
            onSelect = { lens -> camera.updateSettings { it.copy(lensKey = lens.key) } },
            modifier = Modifier.align(Alignment.TopStart).padding(start = 16.dp, top = 64.dp),
        )

        Row(
            modifier = Modifier.align(Alignment.BottomStart).padding(start = 20.dp, bottom = 32.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Chip(
                text = settings.format.label,
                active = settings.format == FrameFormat.OPEN_GATE,
                enabled = canChange,
                onClick = { camera.updateSettings { it.copy(format = it.format.next()) } },
            )
            Chip(
                text = stabilizationLabel(settings.stabilization, config),
                active = settings.stabilization != Stabilization.OFF,
                enabled = canChange,
                onClick = { camera.updateSettings { it.copy(stabilization = it.stabilization.next()) } },
            )
            // A saved source that is unplugged right now records from the internal mic.
            val mic = settings.mic.takeIf { it in mics } ?: MicSource.INTERNAL
            Chip(
                text = mic.label,
                active = mic != MicSource.INTERNAL,
                enabled = !recording,
                onClick = {
                    camera.updateSettings { it.copy(mic = mics[(mics.indexOf(mic) + 1) % mics.size]) }
                },
            )
            if (mic != MicSource.OFF && mic != MicSource.BLUETOOTH && camera.audioInput.rawSupported) {
                Chip(
                    text = settings.micProcessing.label,
                    active = settings.micProcessing == MicProcessing.RAW,
                    enabled = !recording,
                    onClick = { camera.updateSettings { it.copy(micProcessing = it.micProcessing.next()) } },
                )
            }
        }

        RecordButton(
            recording = recording,
            enabled = state is CameraState.Ready || recording,
            onClick = camera::toggleRecording,
            modifier = Modifier.align(Alignment.CenterEnd).padding(end = 28.dp),
        )

        val live = state is CameraState.Ready || recording
        Row(
            modifier = Modifier.align(Alignment.BottomEnd).padding(end = 24.dp, bottom = 32.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            LockChip("AE", locks.ae, live) { camera.setLocks { it.copy(ae = !it.ae) } }
            LockChip("AWB", locks.awb, live) { camera.setLocks { it.copy(awb = !it.awb) } }
            LockChip("AF", locks.af, live) { camera.setLocks { it.copy(af = !it.af) } }
        }

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
private fun StatusBar(state: CameraState, exposure: String, thermalStatus: Int, modifier: Modifier) {
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
        if (exposure.isNotEmpty() && (state is CameraState.Ready || state is CameraState.Recording)) {
            BasicText(exposure, style = LabelStyle)
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

private val LockYellow = Color(0xFFFFD60A)

/** One frozen auto value. Yellow like a camera's lock indicator. */
@Composable
private fun LockChip(text: String, locked: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val color = if (locked) LockYellow else Secondary
    BasicText(
        text = if (locked) "$text 🔒" else text,
        style = LabelStyle.copy(color = color, fontSize = 12.sp, fontWeight = FontWeight.SemiBold),
        modifier = Modifier
            .border(1.dp, color, RoundedCornerShape(50))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
    )
}

@Composable
private fun Chip(text: String, active: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val color = when {
        !enabled -> Color(0x55FFFFFF)
        active -> Color.White
        else -> Secondary
    }
    BasicText(
        text = text,
        style = LabelStyle.copy(color = color, fontSize = 12.sp, fontWeight = FontWeight.SemiBold),
        modifier = Modifier
            .background(Color(0x66000000), RoundedCornerShape(50))
            .border(1.dp, color, RoundedCornerShape(50))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
    )
}

/** Fixed lenses, no automatic switching: what you tap is what records. */
@Composable
private fun LensPicker(
    lenses: List<Lens>,
    selected: String?,
    enabled: Boolean,
    onSelect: (Lens) -> Unit,
    modifier: Modifier,
) {
    Column(
        modifier = modifier.background(Color(0x66000000), RoundedCornerShape(24.dp)).padding(6.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        lenses.forEach { lens ->
            val isSelected = lens.key == selected
            val color = when {
                isSelected -> LockYellow
                enabled -> Color.White
                else -> Color(0x55FFFFFF)
            }
            BasicText(
                text = lens.label,
                style = LabelStyle.copy(color = color, fontSize = 12.sp, fontWeight = FontWeight.SemiBold),
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(if (isSelected) Color(0x33FFFFFF) else Color.Transparent)
                    .clickable(enabled = enabled && !isSelected) { onSelect(lens) }
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            )
        }
    }
}

private fun stabilizationLabel(stab: Stabilization, config: VideoConfig?): String = when {
    stab == Stabilization.OPTICAL && config?.oisAvailable == false -> "KEIN OIS"
    stab == Stabilization.STANDARD && config?.oisAvailable == false -> "EIS"
    else -> stab.label
}

private inline fun <reified T : Enum<T>> T.next(): T = enumValues<T>().let { it[(ordinal + 1) % it.size] }
