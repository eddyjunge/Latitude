package com.eddyjunge.latitude.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.eddyjunge.latitude.camera.CaptureSettings
import com.eddyjunge.latitude.camera.ColorProfile
import com.eddyjunge.latitude.camera.FrameFormat
import com.eddyjunge.latitude.camera.ISO_AUTO
import com.eddyjunge.latitude.camera.MicProcessing
import com.eddyjunge.latitude.camera.MicSource
import com.eddyjunge.latitude.camera.SHUTTER_180
import com.eddyjunge.latitude.camera.SHUTTER_AUTO
import com.eddyjunge.latitude.camera.Stabilization
import com.eddyjunge.latitude.camera.VideoConfig
import com.eddyjunge.latitude.camera.WB_AUTO
import com.eddyjunge.latitude.camera.ZEBRA_CLIP
import com.eddyjunge.latitude.camera.ZEBRA_OFF
import com.eddyjunge.latitude.camera.ZebraMeter

/**
 * Everything that is set once and then left alone, each with a plain sentence about what
 * the current choice does. Slides in from the right over the controls.
 */
@Composable
internal fun SettingsSheet(
    settings: CaptureSettings,
    config: VideoConfig?,
    formatLabel: String?,
    mics: List<MicSource>,
    mic: MicSource,
    rawSupported: Boolean,
    /** Lens, format and stabilization rebuild the camera session. */
    canChangeCamera: Boolean,
    canChangeAudio: Boolean,
    onChange: ((CaptureSettings) -> CaptureSettings) -> Unit,
    onOpenProbe: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxHeight()
            .width(440.dp)
            .background(Color(0xF2141414), RoundedCornerShape(topStart = 28.dp, bottomStart = 28.dp))
            .clickable(enabled = false) {} // swallow taps so they don't close the sheet
            .verticalScroll(rememberScrollState())
            .padding(start = 28.dp, end = 24.dp, top = 20.dp, bottom = 28.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            BasicText("Einstellungen", style = ValueStyle.copy(fontSize = 22.sp))
            Spacer(Modifier.weight(1f))
            Box(
                modifier = Modifier.size(40.dp).clip(CircleShape).background(Color(0x26FFFFFF)).clickable(onClick = onClose),
                contentAlignment = Alignment.Center,
            ) {
                BasicText("✕", style = ValueStyle.copy(fontSize = 16.sp))
            }
        }

        Section("Bild")
        Setting(
            title = "Format",
            options = FrameFormat.entries,
            selected = settings.format,
            label = { if (it == FrameFormat.UHD) "16:9" else "Open Gate" },
            description = when (settings.format) {
                FrameFormat.UHD -> "16:9 in 4K – direkt fertig für Schnitt, YouTube und TV."
                FrameFormat.OPEN_GATE -> "Nutzt den ganzen 4:3-Sensor. Den Ausschnitt (z. B. 16:9 und 9:16 " +
                    "aus demselben Clip) wählst du erst im Schnitt."
            },
            enabled = canChangeCamera,
            onSelect = { f -> onChange { it.copy(format = f) } },
        )
        Setting(
            title = "Farbprofil",
            options = colorOptions(config),
            selected = settings.colorProfile,
            label = {
                when (it) {
                    ColorProfile.REC709 -> "Rec.709"
                    ColorProfile.HLG -> "HLG"
                    ColorProfile.LOG -> "Log"
                }
            },
            description = when (settings.colorProfile) {
                ColorProfile.REC709 -> "Normales Video (SDR, 8 Bit). Sieht sofort fertig aus, " +
                    "lässt im Grading aber wenig Spielraum."
                ColorProfile.HLG -> "HDR in 10 Bit. Sieht auf HDR-Bildschirmen direkt gut aus und lässt sich " +
                    "graden. Resolve erkennt es automatisch als Rec.2100 HLG."
                ColorProfile.LOG -> "Apple Log in 10 Bit: flaches Bild mit dem meisten Spielraum, nur zum Graden. " +
                    "Der Sucher wirkt blass – das ist normal. In Resolve bei den „_LOG“-Clips den " +
                    "Input Color Space auf Apple Log stellen."
            },
            enabled = canChangeCamera,
            onSelect = { p -> onChange { it.copy(colorProfile = p) } },
        )
        if (config?.sensorColor != null) {
            Setting(
                title = "Farben",
                options = listOf(true, false),
                selected = settings.matchColors,
                label = { if (it) "Einheitlich" else "Xiaomi" },
                description = if (settings.matchColors) {
                    "Jedes Objektiv liefert dieselben Farben und dieselbe Tonkurve – Clips von 14 bis 95 mm " +
                        "passen im Grading ohne Angleichen zusammen."
                } else {
                    "Xiaomis eigene Abstimmung: pro Objektiv etwas andere Farben und Kontraste, die sich mit " +
                        "der Szene ändern."
                },
                enabled = canChangeCamera,
                onSelect = { m -> onChange { it.copy(matchColors = m) } },
            )
            if (settings.matchColors) {
                Setting(
                    title = "Weißabgleich",
                    options = WB_OPTIONS,
                    selected = settings.whiteBalance,
                    label = { if (it == WB_AUTO) "Auto" else "${it}K" },
                    description = if (settings.whiteBalance == WB_AUTO) {
                        "Folgt dem Licht und bleibt beim Objektivwechsel gleich. AWB im Sucher hält ihn fest."
                    } else {
                        "Fest auf ${settings.whiteBalance} K, auf jedem Objektiv gleich. " +
                            "Glühbirne ≈ 2800 K, Halogen 3200 K, Tageslicht 5600 K, bewölkt 6500 K."
                    },
                    enabled = true,
                    onSelect = { wb -> onChange { it.copy(whiteBalance = wb) } },
                )
                Note("Einmal abgleichen: alle Objektive auf eine gleichmäßig beleuchtete weiße Fläche richten, " +
                    "dann Geräte-Test öffnen → Calibrate colors.")
            }
        }
        Setting(
            title = "Stabilisierung",
            options = Stabilization.entries,
            selected = settings.stabilization,
            label = {
                when (it) {
                    Stabilization.OFF -> "Aus"
                    Stabilization.OPTICAL -> "Optisch"
                    Stabilization.STANDARD -> "Optisch + digital"
                }
            },
            description = stabilizationText(settings.stabilization, config),
            enabled = canChangeCamera,
            onSelect = { s -> onChange { it.copy(stabilization = s) } },
        )

        Section("Belichtung")
        Setting(
            title = "Zebra",
            options = zebraOptions(settings),
            selected = settings.zebra,
            label = {
                when (it) {
                    ZEBRA_OFF -> "Aus"
                    ZEBRA_CLIP -> "Clipping"
                    else -> "$it %"
                }
            },
            description = when (settings.zebra) {
                ZEBRA_OFF -> "Streifen im Sucher, wo das Bild sehr hell ist. Nie in der Aufnahme."
                ZEBRA_CLIP -> "Streifen, wo das Bild ausbrennt – dort ist keine Zeichnung mehr."
                else -> "Streifen ab ${settings.zebra} % Signal."
            },
            enabled = true,
            onSelect = { z -> onChange { it.copy(zebra = z) } },
        )
        Setting(
            title = "Verschlusszeit (Shutter)",
            options = shutterOptions(config),
            selected = settings.shutter,
            label = { shutterText(it, config?.fps) },
            hint = ::shutterHint,
            description = shutterDescription(settings, config),
            enabled = true,
            onSelect = { s -> onChange { it.copy(shutter = s) } },
        )
        Setting(
            title = "ISO",
            options = isoOptions(config),
            selected = settings.iso,
            label = ::isoText,
            description = isoDescription(settings),
            enabled = true,
            onSelect = { iso -> onChange { it.copy(iso = iso) } },
        )
        Note("Beides lässt sich auch direkt im Sucher ändern: oben auf SHUTTER oder ISO tippen.")

        Section("Ton")
        Setting(
            title = "Mikrofon",
            options = mics,
            selected = mic,
            label = {
                when (it) {
                    MicSource.INTERNAL -> "Handy"
                    MicSource.USB -> "USB"
                    MicSource.BLUETOOTH -> "Bluetooth"
                    MicSource.OFF -> "Aus"
                }
            },
            description = micDescription(mic, settings.mic),
            enabled = canChangeAudio,
            onSelect = { m -> onChange { it.copy(mic = m) } },
        )
        if (mic != MicSource.OFF && mic != MicSource.BLUETOOTH && rawSupported) {
            Setting(
                title = "Tonverarbeitung",
                options = MicProcessing.entries,
                selected = settings.micProcessing,
                label = { if (it == MicProcessing.STANDARD) "Automatisch" else "Unbearbeitet" },
                description = when (settings.micProcessing) {
                    MicProcessing.STANDARD -> "Das Handy filtert Rauschen und regelt die Lautstärke – gut, " +
                        "wenn der Ton ohne Nachbearbeitung passen soll."
                    MicProcessing.RAW -> "Rohes Mikrofonsignal ohne Filter und Pegelautomatik. Klingt natürlicher, " +
                        "ist aber leiser – im Schnitt lauter machen."
                },
                enabled = canChangeAudio,
                onSelect = { p -> onChange { it.copy(micProcessing = p) } },
            )
        }

        Section("Aufnahme")
        formatLabel?.let { Info("Format", it) }
        config?.let {
            Info("Objektiv", it.lens.label)
            Info("Bitrate", "${it.bitrate / 1_000_000} Mbit/s")
        }

        Spacer(Modifier.height(20.dp))
        BasicText(
            text = "Geräte-Test öffnen",
            style = LabelStyle,
            modifier = Modifier.clip(RoundedCornerShape(50)).clickable(onClick = onOpenProbe).padding(vertical = 8.dp),
        )
    }
}

@Composable
private fun Section(title: String) {
    Spacer(Modifier.height(28.dp))
    BasicText(title.uppercase(), style = CaptionStyle.copy(color = Accent, fontSize = 11.sp))
}

@Composable
private fun <T> Setting(
    title: String,
    options: List<T>,
    selected: T,
    label: (T) -> String,
    description: String?,
    enabled: Boolean,
    onSelect: (T) -> Unit,
    hint: (T) -> String? = { null },
) {
    Spacer(Modifier.height(14.dp))
    BasicText(title, style = LabelStyle.copy(color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold))
    Spacer(Modifier.height(8.dp))
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        options.forEach { option ->
            Pill(label(option), selected = option == selected, enabled = enabled, onClick = { onSelect(option) }, hint = hint(option))
        }
    }
    if (description != null) {
        Spacer(Modifier.height(8.dp))
        BasicText(description, style = LabelStyle.copy(fontSize = 13.sp, lineHeight = 18.sp, letterSpacing = 0.sp))
    }
}

@Composable
private fun Note(text: String) {
    Spacer(Modifier.height(10.dp))
    BasicText(text, style = LabelStyle.copy(color = Disabled, fontSize = 12.sp, lineHeight = 16.sp, letterSpacing = 0.sp))
}

@Composable
private fun Info(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(top = 10.dp)) {
        BasicText(label, style = LabelStyle, modifier = Modifier.width(96.dp))
        BasicText(value, style = LabelStyle.copy(color = Color.White))
    }
}

/** Percent levels only below where the profile clips: Log tops out at about 90 %. */
private fun zebraOptions(settings: CaptureSettings): List<Int> {
    val clip = ZebraMeter.clipSignal(settings.colorProfile, settings.matchColors) * 100
    return listOf(ZEBRA_OFF) + listOf(70, 80, 90).filter { it < clip - 2 } + ZEBRA_CLIP
}

/** Log needs a tonemap curve in the 10-bit session; until the format is known, offer everything. */
private fun colorOptions(config: VideoConfig?): List<ColorProfile> = ColorProfile.entries.filter {
    config == null || when (it) {
        ColorProfile.REC709 -> true
        ColorProfile.HLG -> config.hlgSupported
        ColorProfile.LOG -> config.hlgSupported && config.toneCurves != null
    }
}

private val WB_OPTIONS = listOf(WB_AUTO, 2800, 3200, 4000, 5000, 5600, 6500, 7500)

private fun stabilizationText(stab: Stabilization, config: VideoConfig?): String {
    val noOis = config?.oisAvailable == false
    return when (stab) {
        Stabilization.OFF -> "Keine Stabilisierung – für Stativ und Gimbal."
        Stabilization.OPTICAL -> if (noOis) {
            "Dieses Objektiv hat keinen optischen Stabilisator – hier wird nicht stabilisiert."
        } else {
            "Die Linse gleicht Wackler mechanisch aus (OIS). Volles Bild, nichts wird beschnitten."
        }
        Stabilization.STANDARD -> if (noOis) {
            "Dieses Objektiv hat keinen optischen Stabilisator – nur digital (EIS), das Bild wird leicht beschnitten."
        } else {
            "Optisch plus digital (EIS): deutlich ruhiger beim Gehen, dafür wird das Bild leicht beschnitten."
        }
    }
}

private fun shutterDescription(settings: CaptureSettings, config: VideoConfig?): String {
    val fps = config?.fps
    val base = when (settings.shutter) {
        SHUTTER_AUTO -> "Die Kamera wählt selbst. Bei viel Licht wird die Zeit sehr kurz – Bewegungen wirken dann abgehackt."
        SHUTTER_180 -> "Faustregel fürs Filmen: doppelte Bildrate" +
            (fps?.let { " (bei $it fps 1/${it * 2} s)" } ?: "") +
            ". Bewegungen bekommen die natürliche Unschärfe wie im Kino."
        else -> "Feste Verschlusszeit."
    }
    val iso = if (settings.shutter != SHUTTER_AUTO && settings.iso == ISO_AUTO) {
        " Die ISO gleicht die Helligkeit automatisch aus."
    } else {
        ""
    }
    val flicker = if (settings.shutter != SHUTTER_AUTO && !flickerSafe(settings.shutter, fps)) {
        " Achtung: kann unter Kunstlicht flackern (Netzstrom 50 Hz) – 1/50 oder 1/100 sind sicher."
    } else {
        ""
    }
    return base + iso + flicker
}

private fun isoDescription(settings: CaptureSettings): String = when {
    settings.iso == ISO_AUTO -> "Die Helligkeit wird automatisch über die ISO geregelt."
    settings.shutter == SHUTTER_AUTO ->
        "Feste Empfindlichkeit: höher = heller, aber mehr Bildrauschen. Die Verschlusszeit gleicht aus."
    else -> "Feste Empfindlichkeit: höher = heller, aber mehr Bildrauschen. Shutter und ISO sind fest – " +
        "die Helligkeit ändert sich nur noch mit dem Licht."
}

private fun micDescription(active: MicSource, saved: MicSource): String {
    val text = when (active) {
        MicSource.INTERNAL -> "Die eingebauten Mikrofone, in Stereo."
        MicSource.USB -> "Mikrofon am USB-C-Anschluss, z. B. ein Funkempfänger. In Stereo."
        MicSource.BLUETOOTH -> "Bluetooth-Mikrofon. Nur Mono in Sprachqualität – für gute Qualität lieber USB."
        MicSource.OFF -> "Video ohne Ton."
    }
    return if (saved != active) "${micName(saved)} ist nicht verbunden – es wird das Handy-Mikrofon benutzt. $text" else text
}

private fun micName(mic: MicSource) = when (mic) {
    MicSource.INTERNAL -> "Handy-Mikrofon"
    MicSource.USB -> "USB-Mikrofon"
    MicSource.BLUETOOTH -> "Bluetooth-Mikrofon"
    MicSource.OFF -> "Ton aus"
}
