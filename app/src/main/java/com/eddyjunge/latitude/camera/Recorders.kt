package com.eddyjunge.latitude.camera

import android.content.Context
import android.media.MediaCodecInfo.CodecProfileLevel
import android.media.MediaRecorder
import android.view.Surface
import java.io.File
import java.io.FileDescriptor

/**
 * Builds a MediaRecorder that encodes from the persistent [surface].
 * HEVC Main10 at [hevcLevel] for 10-bit, or 8-bit Main when [hevcLevel] is null; AAC 48 kHz stereo.
 */
fun buildRecorder(
    context: Context,
    surface: Surface,
    config: VideoConfig,
    hevcLevel: Int?,
    orientationHint: Int,
    output: FileDescriptor?,
    warmupFile: File? = null,
): MediaRecorder = MediaRecorder(context).apply {
    val withAudio = warmupFile == null
    if (withAudio) setAudioSource(MediaRecorder.AudioSource.CAMCORDER)
    setVideoSource(MediaRecorder.VideoSource.SURFACE)
    setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
    if (output != null) setOutputFile(output) else setOutputFile(warmupFile)
    setVideoEncodingBitRate(config.bitrate)
    setVideoFrameRate(config.fps)
    setVideoSize(config.videoSize.width, config.videoSize.height)
    setVideoEncoder(MediaRecorder.VideoEncoder.HEVC)
    if (hevcLevel != null) setVideoEncodingProfileLevel(CodecProfileLevel.HEVCProfileMain10, hevcLevel)
    if (withAudio) {
        setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
        setAudioSamplingRate(48_000)
        setAudioChannels(2)
        setAudioEncodingBitRate(320_000)
    }
    setInputSurface(surface)
    setOrientationHint(orientationHint)
}
