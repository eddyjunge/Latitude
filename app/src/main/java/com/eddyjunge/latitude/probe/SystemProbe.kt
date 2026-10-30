package com.eddyjunge.latitude.probe

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Environment
import android.os.PowerManager
import android.os.StatFs
import org.json.JSONArray
import org.json.JSONObject

/** Device, OS, storage, thermal and audio-input facts. */
internal object SystemProbe {
    fun run(context: Context): JSONObject = JSONObject().apply {
        val pkg = context.packageManager.getPackageInfo(context.packageName, 0)
        put("app", JSONObject().put("version", pkg.versionName).put("build", pkg.longVersionCode))
        put("device", JSONObject()
            .put("manufacturer", Build.MANUFACTURER)
            .put("model", Build.MODEL)
            .put("device", Build.DEVICE)
            .put("product", Build.PRODUCT)
            .put("soc", "${Build.SOC_MANUFACTURER} ${Build.SOC_MODEL}")
            .put("android", Build.VERSION.RELEASE)
            .put("sdk", Build.VERSION.SDK_INT)
            .put("securityPatch", Build.VERSION.SECURITY_PATCH)
            .put("display", Build.DISPLAY)
            .put("incremental", Build.VERSION.INCREMENTAL))

        val stat = StatFs(Environment.getExternalStorageDirectory().path)
        put("storage", JSONObject()
            .put("freeBytes", stat.availableBytes)
            .put("totalBytes", stat.totalBytes))

        val power = context.getSystemService(PowerManager::class.java)
        put("thermal", JSONObject()
            .put("status", power.currentThermalStatus)
            .put("headroom10s", power.getThermalHeadroom(10).toDouble().takeUnless { it.isNaN() }))

        val audio = context.getSystemService(AudioManager::class.java)
        put("audioInputs", JSONArray(audio.getDevices(AudioManager.GET_DEVICES_INPUTS).map { d ->
            JSONObject()
                .put("type", d.type)
                .put("usb", d.type == AudioDeviceInfo.TYPE_USB_DEVICE || d.type == AudioDeviceInfo.TYPE_USB_HEADSET)
                .put("name", d.productName.toString())
                .put("address", d.address)
                .put("sampleRates", JSONArray(d.sampleRates.toList()))
                .put("channelCounts", JSONArray(d.channelCounts.toList()))
                .put("encodings", JSONArray(d.encodings.toList()))
        }))
    }
}
