package com.eddyjunge.latitude.ui

import android.os.PowerManager
import android.os.SystemClock
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.max
import androidx.compose.ui.unit.min
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.eddyjunge.latitude.camera.CameraController
import com.eddyjunge.latitude.camera.CameraState
import com.eddyjunge.latitude.camera.CaptureSettings
import com.eddyjunge.latitude.camera.ChannelLevel
import com.eddyjunge.latitude.camera.ColorProfile
import com.eddyjunge.latitude.camera.Exposure
import com.eddyjunge.latitude.camera.ISO_AUTO
import com.eddyjunge.latitude.camera.Lens
import com.eddyjunge.latitude.camera.METER_FLOOR_DB
import com.eddyjunge.latitude.camera.MicSource
import com.eddyjunge.latitude.camera.SHUTTER_180
import com.eddyjunge.latitude.camera.SHUTTER_AUTO
import com.eddyjunge.latitude.camera.VideoConfig
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow

/** What is open on top of the viewfinder; at most one at a time. */
private enum class Panel { SHUTTER, ISO, SETTINGS }

/**
 * The viewfinder keeps only what is touched while filming: exposure readout on top,
 * lenses and the record button on the right, locks bottom left. Everything set once
 * lives in the settings sheet.
 */
@Composable
fun CameraScreen(
    camera: CameraController,
    hasPermissions: Boolean,
    thermalStatus: Int,
    standby: Boolean,
    onRequestPermissions: () -> Unit,
    onOpenProbe: () -> Unit,
    onOpenGallery: () -> Unit,
) {
    BoxWithConstraints(Modifier.fillMaxSize().background(Color.Black)) {
        if (!hasPermissions) {
            BasicText(
                text = "Kamera und Mikrofon erlauben",
                style = LabelStyle.copy(color = Color.White, fontSize = 17.sp),
                modifier = Modifier.align(Alignment.Center).clickable(onClick = onRequestPermissions).padding(24.dp),
            )
            return@BoxWithConstraints
        }

        val state by camera.state.collectAsState()
        val locks by camera.locks.collectAsState()
        val settings by camera.settings.collectAsState()
        val lenses by camera.lenses.collectAsState()
        val config by camera.config.collectAsState()
        val mics by camera.audioInput.available.collectAsState()
        val recording = state is CameraState.Recording
        val live = state is CameraState.Ready || recording
        // Lens, format and stabilization rebuild the session, so they are frozen mid-clip.
        val canChange = !recording && state !is CameraState.Opening
        // A saved source that is unplugged right now records from the internal mic.
        val mic = settings.mic.takeIf { it in mics } ?: MicSource.INTERNAL

        var panel by remember { mutableStateOf<Panel?>(null) }
        BackHandler(enabled = panel != null) { panel = null }
        LaunchedEffect(recording) { if (recording && panel == Panel.SETTINGS) panel = null }

        // The image fills the height; the black bars beside it carry the controls, so
        // only the exposure readout lies on the picture itself.
        val aspect = config?.aspectRatio ?: (16f / 9f)
        val previewWidth = min(maxWidth, maxHeight * aspect)
        val rail = max((maxWidth - previewWidth) / 2, RAIL_MIN)
        val screenHeight = maxHeight
        val density = LocalDensity.current
        val cutoutRight = with(density) { WindowInsets.displayCutout.getRight(density, LayoutDirection.Ltr).toDp() }

        config?.let { cfg ->
            // A new preview size needs a new surface; key() throws the old view away.
            key(cfg.previewSize) {
                CameraPreview(camera, Modifier.align(Alignment.Center).fillMaxHeight().aspectRatio(cfg.aspectRatio))
            }
        }

        // Tapping the image closes a quick picker.
        if (panel == Panel.SHUTTER || panel == Panel.ISO) {
            Box(
                Modifier.fillMaxSize().clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                ) { panel = null },
            )
        }

        // Left bar, everything set up before a take: settings, sound, locks.
        Box(Modifier.align(Alignment.CenterStart).width(rail).fillMaxHeight().padding(vertical = RAIL_EDGE)) {
            Column(Modifier.align(Alignment.TopCenter), horizontalAlignment = Alignment.CenterHorizontally) {
                if (!recording) {
                    RoundButton(onClick = { panel = Panel.SETTINGS }) { SettingsIcon(Color.White) }
                } else {
                    Spacer(Modifier.size(ROUND_BUTTON))
                }
                Spacer(Modifier.height(14.dp))
                AudioIndicator(
                    camera = camera,
                    mic = mic,
                    onClick = { if (!recording) panel = Panel.SETTINGS },
                )
            }
            Column(
                modifier = Modifier.align(Alignment.BottomCenter),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                // With shutter and ISO both fixed there is no automatic exposure left to hold.
                if (settings.shutter == SHUTTER_AUTO || settings.iso == ISO_AUTO) {
                    LockChip("AE", locks.ae, live) { camera.setLocks { it.copy(ae = !it.ae) } }
                }
                LockChip("AWB", locks.awb, live) { camera.setLocks { it.copy(awb = !it.awb) } }
                LockChip("AF", locks.af, live) { camera.setLocks { it.copy(af = !it.af) } }
            }
        }

        // Right bar, everything touched while filming: lenses, record, clips.
        Box(Modifier.align(Alignment.CenterEnd).width(rail).fillMaxHeight().padding(vertical = RAIL_EDGE)) {
            LensPicker(
                lenses = lenses,
                selected = config?.lens?.key,
                enabled = canChange,
                onSelect = { lens -> camera.updateSettings { it.copy(lensKey = lens.key) } },
                modifier = Modifier.align(Alignment.TopCenter),
            )
            // Centered, unless a long lens list reaches down to it. A punch-hole camera on
            // this side sits at mid-height too, so the button also steps away from that.
            val down = (RAIL_EDGE + lensPickerHeight(lenses.size) + 10.dp - (screenHeight - RECORD_SIZE) / 2)
                .coerceAtLeast(0.dp)
            val left = (cutoutRight + RECORD_SIZE / 2 + 4.dp - rail / 2).coerceAtLeast(0.dp)
            RecordButton(
                recording = recording,
                enabled = live,
                onClick = camera::toggleRecording,
                modifier = Modifier.align(Alignment.Center).offset(x = -left, y = down),
            )
            if (!recording) {
                RoundButton(onClick = onOpenGallery, modifier = Modifier.align(Alignment.BottomCenter)) {
                    GalleryIcon(Color.White)
                }
            }
        }

        // After the bars so an open picker lies on top of them.
        Column(
            modifier = Modifier.align(Alignment.TopCenter).padding(top = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            TopBar(
                state = state,
                settings = settings,
                config = config,
                exposureFlow = camera.exposure,
                live = live,
                thermalStatus = thermalStatus,
                open = panel,
                onToggle = { p -> panel = if (panel == p) null else p },
            )
            AnimatedVisibility(
                visible = panel == Panel.SHUTTER || panel == Panel.ISO,
                enter = fadeIn(tween(120)) + slideInVertically(tween(160)) { -it / 3 },
                exit = fadeOut(tween(100)) + slideOutVertically(tween(120)) { -it / 3 },
            ) {
                ExposurePicker(
                    shutter = panel == Panel.SHUTTER,
                    settings = settings,
                    config = config,
                    exposureFlow = camera.exposure,
                    live = live,
                    onChange = camera::updateSettings,
                )
            }
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

        AnimatedVisibility(visible = panel == Panel.SETTINGS, enter = fadeIn(), exit = fadeOut()) {
            Box(
                Modifier.fillMaxSize().background(Color(0x80000000)).clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                ) { panel = null },
            )
        }
        AnimatedVisibility(
            visible = panel == Panel.SETTINGS,
            modifier = Modifier.align(Alignment.CenterEnd),
            enter = slideInHorizontally(tween(220)) { it },
            exit = slideOutHorizontally(tween(180)) { it },
        ) {
            SettingsSheet(
                settings = settings,
                config = config,
                formatLabel = (state as? CameraState.Ready)?.format,
                mics = mics,
                mic = mic,
                rawSupported = camera.audioInput.rawSupported,
                canChangeCamera = canChange,
                canChangeAudio = !recording,
                onChange = camera::updateSettings,
                onOpenProbe = onOpenProbe,
                onClose = { panel = null },
            )
        }

        // The activity ends standby on any touch; this only has to cover the controls.
        if (standby) {
            Column(
                modifier = Modifier.fillMaxSize().background(Color.Black).clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                ) {},
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                BasicText("STANDBY", style = CaptionStyle.copy(color = Color.White, fontSize = 13.sp))
                Spacer(Modifier.height(8.dp))
                BasicText(
                    "Kamera aus, damit das Handy kühl bleibt. Tippen zum Fortsetzen, Lautstärketaste nimmt sofort auf.",
                    style = LabelStyle.copy(color = Secondary, fontSize = 13.sp, textAlign = TextAlign.Center),
                    modifier = Modifier.widthIn(max = 320.dp),
                )
            }
        }
    }
}

/** Narrowest a side bar gets; on wide formats the controls then overlap the image a little. */
private val RAIL_MIN = 76.dp
private val RAIL_EDGE = 14.dp
private val ROUND_BUTTON = 44.dp
private val RECORD_SIZE = 66.dp

/**
 * Timer while recording, then what decides the look of the motion: frame rate, profile,
 * shutter and ISO, in one slim line. Shutter and ISO open their picker; "A" marks a value
 * the camera picks itself.
 */
@Composable
private fun TopBar(
    state: CameraState,
    settings: CaptureSettings,
    config: VideoConfig?,
    exposureFlow: StateFlow<Exposure?>,
    live: Boolean,
    thermalStatus: Int,
    open: Panel?,
    onToggle: (Panel) -> Unit,
) {
    // Read here so the camera's 4 readouts a second recompose only this line, not the screen.
    val exposure = exposureFlow.collectAsState().value.takeIf { live }
    Row(
        modifier = Modifier.background(Glass, RoundedCornerShape(50)).padding(horizontal = 4.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (state is CameraState.Recording) {
            Row(Modifier.padding(start = 10.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(7.dp).background(RecordRed, CircleShape))
                Spacer(Modifier.width(6.dp))
                RecordingTimer(state.startedAt)
            }
        }
        Readout(value = config?.fps?.let { "${it}p" } ?: "–")
        Readout(
            value = when (settings.colorProfile) {
                ColorProfile.REC709 -> "709"
                ColorProfile.HLG -> "HLG"
                ColorProfile.LOG -> "LOG"
            },
        )
        Readout(
            value = exposure?.let { "1/${Math.round(1e9 / it.exposureNs)}" } ?: shutterText(settings.shutter, config?.fps),
            tag = when (settings.shutter) {
                SHUTTER_AUTO -> "A"
                SHUTTER_180 -> "180°"
                else -> null
            },
            warn = exposure?.atLimit == true && settings.shutter == SHUTTER_AUTO,
            open = open == Panel.SHUTTER,
            onClick = { onToggle(Panel.SHUTTER) },
        )
        Readout(
            caption = "ISO",
            value = exposure?.iso?.toString() ?: isoText(settings.iso),
            tag = if (settings.iso == ISO_AUTO) "A" else null,
            warn = exposure?.atLimit == true && settings.iso == ISO_AUTO,
            open = open == Panel.ISO,
            onClick = { onToggle(Panel.ISO) },
        )
        thermalLabel(thermalStatus)?.let { (text, color) ->
            BasicText(
                text,
                style = CaptionStyle.copy(color = color, fontSize = 11.sp),
                modifier = Modifier.padding(horizontal = 8.dp),
            )
        }
    }
}

/** One value in the top line: optional caption before it, optional tag ("A", "180°") after it. */
@Composable
private fun Readout(
    value: String,
    caption: String? = null,
    tag: String? = null,
    warn: Boolean = false,
    open: Boolean = false,
    onClick: (() -> Unit)? = null,
) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(if (open) Color(0x33FFFFFF) else Color.Transparent)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (caption != null) {
            BasicText(caption, style = CaptionStyle.copy(color = if (open) Accent else Secondary))
            Spacer(Modifier.width(4.dp))
        }
        BasicText(value, style = ValueStyle.copy(fontSize = 14.sp, color = if (warn) Warning else Color.White))
        if (tag != null) {
            Spacer(Modifier.width(3.dp))
            BasicText(tag, style = CaptionStyle.copy(color = if (open) Accent else Secondary))
        }
    }
}

/** Quick choice right under the readout, usable while recording. */
@Composable
private fun ExposurePicker(
    shutter: Boolean,
    settings: CaptureSettings,
    config: VideoConfig?,
    exposureFlow: StateFlow<Exposure?>,
    live: Boolean,
    onChange: ((CaptureSettings) -> CaptureSettings) -> Unit,
) {
    val exposure = exposureFlow.collectAsState().value.takeIf { live }
    Column(
        modifier = Modifier.padding(top = 8.dp).widthIn(max = 460.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(
            modifier = Modifier
                .background(Glass, RoundedCornerShape(50))
                .horizontalScroll(rememberScrollState())
                .padding(4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            if (shutter) {
                shutterOptions(config).forEach { s ->
                    Pill(
                        text = shutterText(s, config?.fps),
                        hint = shutterHint(s),
                        selected = s == settings.shutter,
                        enabled = true,
                        onClick = { onChange { it.copy(shutter = s) } },
                        compact = true,
                    )
                }
            } else {
                isoOptions(config).forEach { iso ->
                    Pill(isoText(iso), iso == settings.iso, true, { onChange { it.copy(iso = iso) } }, compact = true)
                }
            }
        }
        BasicText(
            text = limitHint(settings, config, exposure) ?: exposureHint(shutter, settings, config?.fps),
            style = LabelStyle.copy(color = Color.White, fontSize = 11.sp, textAlign = TextAlign.Center),
            modifier = Modifier.padding(top = 5.dp).background(Glass, RoundedCornerShape(50))
                .padding(horizontal = 10.dp, vertical = 4.dp),
        )
    }
}

/** The automatic value ran out of range: say why the image is too dark or too bright. */
private fun limitHint(settings: CaptureSettings, config: VideoConfig?, exposure: Exposure?): String? {
    if (exposure?.atLimit != true || config == null) return null
    return if (settings.iso == ISO_AUTO) {
        if (exposure.iso >= config.isoRange.upper) "Zu dunkel für diese Verschlusszeit – ISO ist am Maximum"
        else "Zu hell für diese Verschlusszeit – ISO ist am Minimum (ND-Filter hilft)"
    } else {
        if (exposure.exposureNs >= config.frameDurationNs) "Zu dunkel für diese ISO – Verschlusszeit ist am Maximum"
        else "Zu hell für diese ISO – kürzer geht nicht"
    }
}

private fun exposureHint(shutter: Boolean, settings: CaptureSettings, fps: Int?): String = if (shutter) {
    when {
        settings.shutter == SHUTTER_AUTO -> "Automatisch – bei viel Licht wirken Bewegungen abgehackt"
        !flickerSafe(settings.shutter, fps) -> "Kann unter Kunstlicht flackern – 1/50 oder 1/100 sind sicher"
        settings.shutter == SHUTTER_180 -> "Doppelte Bildrate: natürliche Bewegungsunschärfe"
        else -> "Feste Verschlusszeit"
    } + if (settings.shutter != SHUTTER_AUTO && settings.iso == ISO_AUTO && flickerSafe(settings.shutter, fps)) {
        " · ISO regelt die Helligkeit"
    } else {
        ""
    }
} else {
    when {
        settings.iso == ISO_AUTO -> "Automatisch – die Helligkeit wird über die ISO geregelt"
        settings.shutter == SHUTTER_AUTO -> "Fest – die Verschlusszeit gleicht aus · höher = mehr Rauschen"
        else -> "Fest – Helligkeit ändert sich nur mit dem Licht · höher = mehr Rauschen"
    }
}

@Composable
private fun RoundButton(onClick: () -> Unit, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Box(
        modifier = modifier.size(ROUND_BUTTON).clip(CircleShape).background(Glass).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { content() }
}

/** Live level meter with the input underneath, or a clear warning when the clip would have no sound. */
@Composable
private fun AudioIndicator(camera: CameraController, mic: MicSource, onClick: () -> Unit) {
    Column(
        modifier = Modifier.clip(RoundedCornerShape(10.dp)).clickable(onClick = onClick).padding(4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (mic == MicSource.OFF) {
            BasicText(
                text = "KEIN\nTON",
                style = CaptionStyle.copy(color = Warning, fontSize = 11.sp, textAlign = TextAlign.Center),
                modifier = Modifier.background(Glass, RoundedCornerShape(10.dp)).padding(horizontal = 8.dp, vertical = 6.dp),
            )
        } else {
            LevelMeter(camera)
            Spacer(Modifier.height(6.dp))
            BasicText(
                text = when (mic) {
                    MicSource.USB -> "USB"
                    MicSource.BLUETOOTH -> "BT"
                    else -> "INT"
                },
                style = CaptionStyle,
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
    BasicText(text, style = ValueStyle.copy(fontSize = 14.sp))
}

private fun thermalLabel(status: Int): Pair<String, Color>? = when {
    status >= PowerManager.THERMAL_STATUS_SEVERE -> "HEISS" to RecordRed
    status >= PowerManager.THERMAL_STATUS_MODERATE -> "WARM" to Warning
    else -> null
}

@Composable
private fun RecordButton(recording: Boolean, enabled: Boolean, onClick: () -> Unit, modifier: Modifier) {
    val innerSize by animateDpAsState(if (recording) 26.dp else RECORD_SIZE - 14.dp, tween(180), label = "record-size")
    val corner by animateDpAsState(if (recording) 6.dp else RECORD_SIZE / 2, tween(180), label = "record-corner")
    Box(
        modifier = modifier
            .size(RECORD_SIZE)
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

/** Loud enough to watch: the last 6 dB before the track overloads. */
private const val METER_HOT_DB = -6f

private fun meterColor(db: Float, clipped: Boolean, base: Color): Color = when {
    clipped -> RecordRed
    db >= METER_HOT_DB -> Accent
    else -> base
}

/** Scale marks on the meter, in dBFS. */
private val METER_MARKS = listOf(0, -12, -24, -48)

/**
 * Peak meter: one bar per recorded channel, 0 dBFS at the top and -60 at the bottom, with a
 * small scale beside it. White is fine, yellow is close to the top, red means it clipped.
 */
@Composable
private fun LevelMeter(camera: CameraController) {
    val levels = camera.audioLevels.collectAsState()
    val measurer = rememberTextMeasurer()
    val marks = remember(measurer) {
        METER_MARKS.map { it to measurer.measure("${-it}", CaptionStyle.copy(fontSize = 8.sp, letterSpacing = 0.sp)) }
    }
    // The 30 updates per second only redraw the canvas, they never recompose.
    Canvas(Modifier.width(METER_BAR * 2 + METER_GAP + 18.dp).height(140.dp)) {
        val current = levels.value
        val barWidth = METER_BAR.toPx()
        val step = barWidth + METER_GAP.toPx()
        val radius = CornerRadius(barWidth / 2)
        fun heightOf(db: Float) = size.height * ((db - METER_FLOOR_DB) / -METER_FLOOR_DB).coerceIn(0f, 1f)
        // Stereo by default; a mono source draws one bar in the middle of the same space.
        val channels = current?.size?.coerceIn(1, 2) ?: 2
        val left = if (channels == 1) step / 2 else 0f
        for (i in 0 until channels) {
            val x = left + i * step
            drawRoundRect(Color(0x33FFFFFF), Offset(x, 0f), Size(barWidth, size.height), radius)
            val ch: ChannelLevel = current?.getOrNull(i) ?: continue
            val h = heightOf(ch.level)
            if (h > 0f) {
                drawRoundRect(
                    meterColor(ch.level, ch.clipped, Color(0xE6FFFFFF)),
                    Offset(x, size.height - h),
                    Size(barWidth, h),
                    radius,
                )
            }
            if (ch.hold > ch.level + 1f) {
                val y = size.height - heightOf(ch.hold)
                drawRect(meterColor(ch.hold, ch.clipped, Secondary), Offset(x, y), Size(barWidth, 2.dp.toPx()))
            }
        }
        val scaleX = 2 * step + 3.dp.toPx()
        marks.forEach { (db, text) ->
            val y = (size.height - heightOf(db.toFloat()) - text.size.height / 2f)
                .coerceIn(0f, size.height - text.size.height)
            drawText(text, topLeft = Offset(scaleX, y))
        }
    }
}

private val METER_BAR = 7.dp
private val METER_GAP = 4.dp

/** Holds an automatic value. Yellow like a camera's lock indicator. */
@Composable
private fun LockChip(text: String, locked: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val color = when {
        locked -> Accent
        enabled -> Color.White
        else -> Disabled
    }
    Row(
        modifier = Modifier
            .size(width = 64.dp, height = 32.dp)
            .clip(RoundedCornerShape(50))
            .background(if (locked) Color(0x33FFD60A) else Glass)
            .clickable(enabled = enabled, onClick = onClick),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LockIcon(locked, color)
        Spacer(Modifier.width(6.dp))
        BasicText(text, style = LabelStyle.copy(color = color, fontSize = 12.sp, fontWeight = FontWeight.SemiBold))
    }
}

private val LENS_HEIGHT = 32.dp
private val LENS_GAP = 2.dp
private val LENS_PADDING = 4.dp

private fun lensPickerHeight(count: Int) =
    if (count == 0) 0.dp else LENS_PADDING * 2 + LENS_HEIGHT * count + LENS_GAP * (count - 1)

/** Fixed lenses, no automatic switching: what you tap is what records. */
@Composable
private fun LensPicker(
    lenses: List<Lens>,
    selected: String?,
    enabled: Boolean,
    onSelect: (Lens) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.background(Glass, RoundedCornerShape(20.dp)).padding(LENS_PADDING),
        verticalArrangement = Arrangement.spacedBy(LENS_GAP),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        lenses.forEach { lens ->
            val isSelected = lens.key == selected
            val color = when {
                isSelected -> Accent
                enabled -> Color.White
                else -> Disabled
            }
            Box(
                modifier = Modifier
                    .size(width = 56.dp, height = LENS_HEIGHT)
                    .clip(RoundedCornerShape(50))
                    .background(if (isSelected) Color(0x33FFFFFF) else Color.Transparent)
                    .clickable(enabled = enabled && !isSelected) { onSelect(lens) },
                contentAlignment = Alignment.Center,
            ) {
                BasicText(lens.label, style = LabelStyle.copy(color = color, fontSize = 12.sp, fontWeight = FontWeight.SemiBold))
            }
        }
    }
}
