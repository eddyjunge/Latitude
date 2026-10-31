package com.eddyjunge.latitude.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.eddyjunge.latitude.camera.ISO_AUTO
import com.eddyjunge.latitude.camera.SHUTTER_180
import com.eddyjunge.latitude.camera.SHUTTER_AUTO
import com.eddyjunge.latitude.camera.VideoConfig

internal val RecordRed = Color(0xFFFF3B30)
internal val Accent = Color(0xFFFFD60A)
internal val Warning = Color(0xFFFF9F0A)
internal val Secondary = Color(0x99FFFFFF)
internal val Disabled = Color(0x55FFFFFF)
internal val Glass = Color(0x73000000)

internal val LabelStyle = TextStyle(
    color = Secondary,
    fontSize = 13.sp,
    fontWeight = FontWeight.Medium,
    letterSpacing = 0.5.sp,
    fontFeatureSettings = "tnum",
)

/** Tiny all-caps caption above a value. */
internal val CaptionStyle = LabelStyle.copy(fontSize = 10.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.sp)

internal val ValueStyle = LabelStyle.copy(color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)

/**
 * One choice in a row of choices. The picked one is solid white so it reads at a glance
 * over any image; an optional [hint] sits small underneath (e.g. "180°"). [compact] is the
 * smaller size for strips that lie on the image.
 */
@Composable
internal fun Pill(
    text: String,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    hint: String? = null,
    compact: Boolean = false,
) {
    val (bg, fg) = when {
        selected -> Color.White to Color.Black
        enabled -> Color(0x26FFFFFF) to Color.White
        else -> Color(0x14FFFFFF) to Disabled
    }
    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(if (selected && !enabled) Color(0x99FFFFFF) else bg)
            .clickable(enabled = enabled && !selected, onClick = onClick)
            .padding(
                horizontal = if (compact) 12.dp else 16.dp,
                vertical = when {
                    hint != null -> if (compact) 3.dp else 5.dp
                    else -> if (compact) 7.dp else 9.dp
                },
            ),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        BasicText(text, style = LabelStyle.copy(color = fg, fontSize = if (compact) 13.sp else 14.sp, fontWeight = FontWeight.SemiBold))
        if (hint != null) {
            BasicText(hint, style = CaptionStyle.copy(color = fg.copy(alpha = 0.7f), fontSize = 9.sp))
        }
    }
}

// Exposure choices shared by the quick strip and the settings sheet.

private val SHUTTER_STEPS = listOf(25, 30, 50, 60, 100, 120, 200, 250, 500, 1000, 2000)
private val ISO_STEPS = listOf(50, 100, 200, 400, 800, 1600, 3200, 6400, 12800)

/** Auto, then every fixed time that fits into one frame, longest first, with the 180° rule in its place. */
internal fun shutterOptions(config: VideoConfig?): List<Int> {
    val half = (config?.fps ?: 25) * 2
    val fixed = SHUTTER_STEPS.filter { s ->
        val ns = 1_000_000_000L / s
        s != half && (config == null || ns <= config.frameDurationNs && ns >= config.exposureRangeNs.lower)
    }
    val at = fixed.indexOfFirst { it > half }.let { if (it < 0) fixed.size else it }
    return listOf(SHUTTER_AUTO) + fixed.take(at) + SHUTTER_180 + fixed.drop(at)
}

internal fun isoOptions(config: VideoConfig?): List<Int> =
    listOf(ISO_AUTO) + ISO_STEPS.filter { config == null || it in config.isoRange }

internal fun shutterText(shutter: Int, fps: Int?): String = when (shutter) {
    SHUTTER_AUTO -> "AUTO"
    SHUTTER_180 -> fps?.let { "1/${it * 2}" } ?: "180°"
    else -> "1/$shutter"
}

internal fun shutterHint(shutter: Int): String? = if (shutter == SHUTTER_180) "180°" else null

internal fun isoText(iso: Int): String = if (iso == ISO_AUTO) "AUTO" else "$iso"

/** 1/25, 1/50 and 1/100 are whole multiples of the 10 ms flicker period of 50 Hz mains light. */
internal fun flickerSafe(shutter: Int, fps: Int?): Boolean {
    val denominator = if (shutter == SHUTTER_180) (fps ?: 25) * 2 else shutter
    return denominator <= 0 || 100 % denominator == 0
}

// Icons, drawn so the app needs no icon library.

/** Three sliders: the settings. */
@Composable
internal fun SettingsIcon(color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier.size(22.dp)) {
        val stroke = 2.dp.toPx()
        val knob = 3.2.dp.toPx()
        listOf(0.2f to 0.32f, 0.5f to 0.68f, 0.8f to 0.42f).forEach { (y, x) ->
            val cy = size.height * y
            drawLine(color, Offset(0f, cy), Offset(size.width, cy), stroke, StrokeCap.Round)
            drawCircle(Color.Black, knob + stroke, Offset(size.width * x, cy))
            drawCircle(color, knob, Offset(size.width * x, cy), style = Stroke(stroke))
        }
    }
}

/** A frame with a play triangle: the clips. */
@Composable
internal fun GalleryIcon(color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier.size(22.dp)) {
        val stroke = 2.dp.toPx()
        drawRoundRect(
            color,
            Offset(stroke / 2, stroke / 2),
            Size(size.width - stroke, size.height - stroke),
            CornerRadius(5.dp.toPx()),
            style = Stroke(stroke),
        )
        val play = Path().apply {
            moveTo(size.width * 0.40f, size.height * 0.30f)
            lineTo(size.width * 0.70f, size.height * 0.50f)
            lineTo(size.width * 0.40f, size.height * 0.70f)
            close()
        }
        drawPath(play, color)
    }
}

/** Padlock; the shackle lifts open when unlocked. */
@Composable
internal fun LockIcon(locked: Boolean, color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier.size(width = 10.dp, height = 13.dp)) {
        val stroke = 1.6.dp.toPx()
        val bodyTop = size.height * 0.45f
        drawRoundRect(color, Offset(0f, bodyTop), Size(size.width, size.height - bodyTop), CornerRadius(2.dp.toPx()))
        val left = size.width * 0.2f
        val right = size.width - left
        val radius = (right - left) / 2
        val top = if (locked) stroke / 2 else -size.height * 0.15f
        val shackle = Path().apply {
            moveTo(left, bodyTop)
            lineTo(left, top + radius)
            arcTo(Rect(left, top, right, top + 2 * radius), 180f, 180f, false)
            lineTo(right, if (locked) bodyTop else top + radius * 1.6f)
        }
        drawPath(shackle, color, style = Stroke(stroke, cap = StrokeCap.Round))
    }
}
