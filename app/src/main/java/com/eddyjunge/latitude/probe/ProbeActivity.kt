package com.eddyjunge.latitude.probe

import android.content.ContentValues
import android.content.Intent
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val Body = TextStyle(color = Color.White, fontSize = 14.sp)
private val Mono = TextStyle(color = Color(0xCCFFFFFF), fontSize = 12.sp, fontFamily = FontFamily.Monospace)

/** Stage 0: dumps everything the device offers to a JSON file in Download/LAT. */
class ProbeActivity : ComponentActivity() {
    private var status by mutableStateOf("Point the camera at a scene with bright and dark areas, hold still, then start.")
    private var summary by mutableStateOf("")
    private var running by mutableStateOf(false)
    private var report by mutableStateOf<JSONObject?>(null)
    private var savedUri by mutableStateOf<Uri?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // The tests run for minutes; the screen must not lock in between.
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContent { Screen() }
    }

    @Composable
    private fun Screen() {
        Column(
            Modifier.fillMaxSize().background(Color.Black).padding(24.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            BasicText("Capability Probe", style = Body.copy(fontSize = 22.sp))
            BasicText(status, style = Body.copy(color = Color(0x99FFFFFF)))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(if (report == null) "Start probe" else "Run again", enabled = !running) { start() }
                Button("Color test", enabled = !running) { startColorTest() }
                Button("Calibrate colors", enabled = !running) { startCalibration() }
                if (savedUri != null) Button("Share JSON", enabled = !running) { share() }
            }
            if (summary.isNotEmpty()) BasicText(summary, style = Mono)
        }
    }

    @Composable
    private fun Button(label: String, enabled: Boolean, onClick: () -> Unit) {
        BasicText(
            label,
            style = Body.copy(color = if (enabled) Color.Black else Color(0x66000000)),
            modifier = Modifier
                .background(if (enabled) Color.White else Color(0x66FFFFFF), RoundedCornerShape(50))
                .clickable(enabled = enabled, onClick = onClick)
                .padding(horizontal = 20.dp, vertical = 10.dp),
        )
    }

    private fun start() {
        running = true
        lifecycleScope.launch {
            val manager = getSystemService(CameraManager::class.java)
            val json = JSONObject().put("probeVersion", 1).put("createdAt", Date().toString())
            try {
                status = "Reading capabilities…"
                withContext(Dispatchers.Default) {
                    json.put("system", SystemProbe.run(this@ProbeActivity))
                    json.put("camera", CameraProbe(manager).run())
                    json.put("encoders", CodecProbe.run())
                }
                val liveTests = JSONArray()
                for (id in backCameras(manager)) {
                    status = "Live tonemap test on camera $id…"
                    liveTests.put(TonemapTest(manager).run(id))
                }
                json.put("tonemapLiveTest", liveTests)
                report = json
                savedUri = withContext(Dispatchers.IO) { save(json) }
                summary = summarize(json)
                status = "Saved to Download/LAT. Share it, or pull it via USB later."
            } catch (e: Exception) {
                status = "Probe failed: $e"
            } finally {
                running = false
            }
        }
    }

    /** Only the cross-lens color test: quick enough to repeat for several test colors. */
    private fun startColorTest() {
        running = true
        lifecycleScope.launch {
            try {
                status = "Color test on every lens…"
                val json = JSONObject().put("probeVersion", 1).put("createdAt", Date().toString())
                    .put("colorTest", ColorTest(getSystemService(CameraManager::class.java)).run())
                savedUri = withContext(Dispatchers.IO) { save(json, "color") }
                status = "Color test saved to Download/LAT."
            } catch (e: Exception) {
                status = "Color test failed: $e"
            } finally {
                running = false
            }
        }
    }

    /** Point every back lens at the same evenly lit white or grey surface first. */
    private fun startCalibration() {
        running = true
        lifecycleScope.launch {
            try {
                status = "Calibrating: keep the phone pointed at the white surface…"
                val result = ColorTest(getSystemService(CameraManager::class.java)).calibrate(this@ProbeActivity)
                val json = JSONObject().put("probeVersion", 1).put("createdAt", Date().toString())
                    .put("colorCalibration", result)
                savedUri = withContext(Dispatchers.IO) { save(json, "calibration") }
                summary = result.toString(2)
                status = "Calibration saved. All lenses now see the same white."
            } catch (e: Exception) {
                status = "Calibration failed: $e"
            } finally {
                running = false
            }
        }
    }

    private fun backCameras(manager: CameraManager) = manager.cameraIdList.filter {
        manager.getCameraCharacteristics(it)[CameraCharacteristics.LENS_FACING] == CameraMetadata.LENS_FACING_BACK
    }

    private fun save(json: JSONObject, kind: String = "probe"): Uri {
        val model = android.os.Build.MODEL.replace(Regex("[^A-Za-z0-9]+"), "-")
        val name = "${kind}_${model}_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".json"
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            put(MediaStore.Downloads.MIME_TYPE, "application/json")
            put(MediaStore.Downloads.RELATIVE_PATH, "Download/LAT")
        }
        val uri = requireNotNull(contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values))
        contentResolver.openOutputStream(uri)!!.use { it.write(json.toString(2).toByteArray()) }
        return uri
    }

    private fun share() {
        val uri = savedUri ?: return
        val send = Intent(Intent.ACTION_SEND)
            .setType("application/json")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        startActivity(Intent.createChooser(send, "Share probe"))
    }

    /** The handful of answers that decide the next stage, readable on the phone. */
    private fun summarize(json: JSONObject): String = buildString {
        val cameras = json.getJSONObject("camera").getJSONArray("cameras")
        appendLine("CAMERAS")
        for (i in 0 until cameras.length()) {
            val c = cameras.getJSONObject(i)
            if (c.has("error")) {
                appendLine("${c.getString("id")} (${c.optString("source")}): ${c.getString("error")}")
                continue
            }
            val caps = c.getJSONArray("capabilities").toString()
            val focal = c.getJSONObject("optics").getJSONArray("focalLengthsMm").toString()
            val profiles = c.getJSONObject("dynamicRange").getJSONArray("profiles").toString()
            appendLine("${c.getString("id")} ${c.optString("facing")} ${c.optString("source")} f=$focal")
            appendLine("  ${c.optString("hardwareLevel")}")
            appendLine("  manualSensor=${"MANUAL_SENSOR" in caps} manualPost=${"MANUAL_POST_PROCESSING" in caps} " +
                "raw=${"\"RAW(" in caps} 10bit=${"DYNAMIC_RANGE_TEN_BIT" in caps} hlg10=${"HLG10" in profiles}")
            appendLine("  tonemapPoints=${c.getJSONObject("tonemap").opt("maxCurvePoints")} " +
                "vendorKeys=${c.getJSONObject("keys").getJSONObject("vendorCharacteristics").length()}")
        }
        appendLine()
        appendLine("TONEMAP LIVE TEST")
        val live = json.getJSONArray("tonemapLiveTest")
        for (i in 0 until live.length()) {
            val t = live.getJSONObject(i)
            appendLine("camera ${t.getString("cameraId")}${t.optString("error").let { if (it.isEmpty()) "" else ": $it" }}")
            for (key in listOf("HLG10_P010", "STANDARD_YUV8")) {
                val v = t.optJSONObject(key) ?: continue
                val verdict = v.optJSONObject("verdict")
                val bits = v.optJSONObject("default")?.optJSONObject("lastFrame")?.opt("lowTwoBitsZeroShare")
                appendLine("  $key: curveHonored=${verdict?.opt("tonemapCurveHonored") ?: v.optString("error")}" +
                    (bits?.let { " lowBitsZero=$it" } ?: ""))
            }
        }
        appendLine()
        appendLine("HEVC ENCODERS")
        val encoders = json.getJSONArray("encoders")
        for (i in 0 until encoders.length()) {
            val e = encoders.getJSONObject(i)
            if (e.optString("type") != "video/hevc") continue
            val main10 = e.optJSONArray("profileLevels")?.toString()?.contains("Main10@") == true
            appendLine("${e.getString("name")} hw=${e.opt("hardwareAccelerated")} main10=$main10 " +
                "bitrate=${e.opt("bitrateRange")} modes=${e.opt("bitrateModes")}")
        }
    }
}
