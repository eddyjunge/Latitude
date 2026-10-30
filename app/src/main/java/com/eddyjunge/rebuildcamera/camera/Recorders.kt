package com.eddyjunge.rebuildcamera.camera

import android.content.Context
import android.media.MediaCodecInfo.CodecProfileLevel
import android.media.MediaRecorder
import android.view.Surface
import java.io.File
import java.io.FileDescriptor

/**
 * Builds a MediaRecorder that encodes from the persistent [surface].
 * HEVC, 10-bit Main10 when [tenBit], AAC 48 kHz stereo.
 */
fun buildRecorder(
    context: Context,
    surface: Surface,
    config: VideoConfig,
    tenBit: Boolean,
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
    if (tenBit) {
        setVideoEncodingProfileLevel(CodecProfileLevel.HEVCProfileMain10, CodecProfileLevel.HEVCMainTierLevel51)
    }
    if (withAudio) {
        setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
        setAudioSamplingRate(48_000)
        setAudioChannels(2)
        setAudioEncodingBitRate(256_000)
    }
    setInputSurface(surface)
    setOrientationHint(orientationHint)
}
