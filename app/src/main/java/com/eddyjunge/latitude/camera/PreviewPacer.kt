package com.eddyjunge.latitude.camera

import android.graphics.ImageFormat
import android.hardware.HardwareBuffer
import android.media.ImageReader
import android.media.ImageWriter
import android.os.Handler
import android.util.Log
import android.util.Size
import android.view.Surface

private const val TAG = "PreviewPacer"

/**
 * Evenly paced viewfinder. With the encoder stream in the session, the Xiaomi 15 Ultra's camera
 * HAL hands preview frames over 130–180 ms after exposure with ±20 ms jitter, so on a 60 Hz
 * display a frame stays up for one, two, three or four refreshes: the picture judders although
 * the sensor runs at a steady 30 fps.
 *
 * Frames pass through here without a copy and get a present time of exposure start plus one
 * constant delay, just above the slowest recent delivery. SurfaceFlinger then shows every frame
 * for the same number of refreshes. Costs about 20 ms of extra delay. Camera thread only.
 */
internal class PreviewPacer(target: Surface, size: Size, handler: Handler) {
    private val reader = ImageReader.Builder(size.width, size.height)
        .setImageFormat(ImageFormat.PRIVATE)
        .setMaxImages(MAX_IMAGES)
        .setUsage(HardwareBuffer.USAGE_COMPOSER_OVERLAY or HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE)
        .build()
    private val writer = ImageWriter.Builder(target)
        .setImageFormat(ImageFormat.PRIVATE)
        .setMaxImages(MAX_IMAGES)
        .build()

    /** What the camera renders into. Its timestamps must be CLOCK_MONOTONIC, like the display's. */
    val input: Surface get() = reader.surface

    private val delays = LongArray(WINDOW)
    private var count = 0
    private var delayNs = 0L
    private var stopped = false
    private var lastLog = 0L

    init {
        reader.setOnImageAvailableListener({ r ->
            val image = runCatching { r.acquireNextImage() }.getOrNull() ?: return@setOnImageAvailableListener
            if (stopped) {
                image.close()
                return@setOnImageAvailableListener
            }
            val now = System.nanoTime()
            val exposed = image.timestamp
            delays[count++ % WINDOW] = now - exposed
            // Every change of the delay can move one frame to another refresh, so it changes
            // rarely: up at once when a frame would come too late, down only by a clear margin.
            val worst = delays.max()
            if (worst + MARGIN_NS / 2 > delayNs || (count >= WINDOW && worst + MARGIN_NS + SLACK_NS < delayNs)) {
                delayNs = worst + MARGIN_NS
            }
            if (now - lastLog > LOG_INTERVAL_NS) {
                lastLog = now
                Log.d(TAG, "delivery up to %.0f ms, showing at %.0f ms".format(worst / 1e6, delayNs / 1e6))
            }
            image.timestamp = exposed + delayNs
            runCatching { writer.queueInputImage(image) }.onFailure {
                // The view's surface is going away; the next session brings a new one.
                image.close()
            }
        }, handler)
    }

    /**
     * Drop frames from now on and let go of the view's surface at once, so the next session's
     * pacer can connect to it. The camera may still deliver into [input] until its session has closed.
     */
    fun stop() {
        if (stopped) return
        stopped = true
        writer.close()
    }

    /** Only once the session using [input] has closed, or the camera device. */
    fun release() {
        stop()
        reader.close()
    }

    companion object {
        private const val MAX_IMAGES = 8

        /** About two seconds at 30 fps. */
        private const val WINDOW = 60
        /** Headroom above the slowest delivery of the last two seconds. */
        private const val MARGIN_NS = 8_000_000L

        /** How much shorter the delay must be able to get before it is lowered. */
        private const val SLACK_NS = 12_000_000L
        private const val LOG_INTERVAL_NS = 5_000_000_000L
    }
}
