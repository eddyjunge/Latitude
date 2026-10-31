package com.eddyjunge.latitude.camera

import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.MediaRecorder
import android.os.Handler
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

private const val TAG = "AudioInput"

enum class MicSource(val label: String) {
    INTERNAL("MIC INTERN"),

    /** USB-C receivers (e.g. DJI Mic receiver) and USB interfaces: full quality. */
    USB("MIC USB"),

    /**
     * Bluetooth hands-free (HFP/SCO). Phones only take voice-grade mono (16 kHz at best) from
     * Bluetooth mics, which is how the DJI Mic Mini connects without its receiver.
     */
    BLUETOOTH("MIC BT"),
    OFF("TON AUS"),
}

enum class MicProcessing(val label: String) {
    /** The phone's camcorder tuning: wind and noise reduction, automatic gain. */
    STANDARD("TON STANDARD"),

    /** No noise reduction or automatic gain; falls back to STANDARD if the phone can't. */
    RAW("TON ROH"),
}

/** Resolved choice for one recording; [device] null means the system default mic. */
class AudioChoice(val audioSource: Int, val device: AudioDeviceInfo?, val channels: Int)

/**
 * Tracks which microphones are plugged in or paired and routes Bluetooth.
 * Bluetooth mics only deliver audio while they are the "communication device", which also
 * opens the SCO link; that takes about a second, so it is done as soon as the user picks it.
 */
class AudioInput(context: Context, private val handler: Handler, private val onChange: () -> Unit) {
    private val audioManager = context.getSystemService(AudioManager::class.java)

    private val _available = MutableStateFlow(listOf(MicSource.INTERNAL, MicSource.OFF))
    /** Sources the user can pick right now. */
    val available: StateFlow<List<MicSource>> = _available.asStateFlow()

    val rawSupported: Boolean =
        audioManager.getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED) == "true"

    private var bluetoothRouted = false

    private val deviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(added: Array<out AudioDeviceInfo>) = refresh()
        override fun onAudioDevicesRemoved(removed: Array<out AudioDeviceInfo>) = refresh()
    }

    init {
        audioManager.registerAudioDeviceCallback(deviceCallback, handler)
        refresh()
    }

    private fun refresh() {
        val inputs = audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)
        _available.value = buildList {
            add(MicSource.INTERNAL)
            if (inputs.any { it.type in USB_TYPES }) add(MicSource.USB)
            if (bluetoothDevice() != null) add(MicSource.BLUETOOTH)
            add(MicSource.OFF)
        }
        onChange()
        Log.i(TAG, "inputs: " + inputs.joinToString { "${it.type}:${it.productName}:${it.address}" })
    }

    private fun bluetoothDevice(): AudioDeviceInfo? =
        audioManager.availableCommunicationDevices.firstOrNull { it.type in BLUETOOTH_TYPES }

    /** Call when the selected source changes and when the camera opens or closes. */
    fun route(source: MicSource, active: Boolean) {
        val wantBluetooth = active && source == MicSource.BLUETOOTH
        if (wantBluetooth && !bluetoothRouted) {
            val device = bluetoothDevice() ?: return
            bluetoothRouted = audioManager.setCommunicationDevice(device)
            Log.i(TAG, "bluetooth routed=$bluetoothRouted to ${device.productName}")
        } else if (!wantBluetooth && bluetoothRouted) {
            audioManager.clearCommunicationDevice()
            bluetoothRouted = false
        }
    }

    /** Null means record without sound. Unavailable sources fall back to the internal mic. */
    fun choose(source: MicSource, processing: MicProcessing): AudioChoice? {
        val inputs = audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)
        val raw = processing == MicProcessing.RAW && rawSupported
        val internalSource = if (raw) MediaRecorder.AudioSource.UNPROCESSED else MediaRecorder.AudioSource.CAMCORDER
        return when (source) {
            MicSource.OFF -> null
            MicSource.USB -> inputs.firstOrNull { it.type in USB_TYPES }
                ?.let { AudioChoice(if (raw) MediaRecorder.AudioSource.UNPROCESSED else MediaRecorder.AudioSource.MIC, it, 2) }
            MicSource.BLUETOOTH -> bluetoothDevice()?.takeIf { bluetoothRouted }
                ?.let { AudioChoice(MediaRecorder.AudioSource.VOICE_COMMUNICATION, it, 1) }
            MicSource.INTERNAL -> null
        } ?: AudioChoice(internalSource, null, 2).takeIf { source != MicSource.OFF }
    }

    fun release() {
        audioManager.unregisterAudioDeviceCallback(deviceCallback)
        route(MicSource.OFF, active = false)
    }

    companion object {
        private val USB_TYPES = setOf(AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_HEADSET)
        private val BLUETOOTH_TYPES = setOf(
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
            AudioDeviceInfo.TYPE_BLE_HEADSET,
        )
    }
}
