package com.eddyjunge.latitude.camera

import android.graphics.Bitmap
import android.graphics.ColorSpace
import android.os.Handler
import android.os.SystemClock
import android.util.Log
import android.view.PixelCopy
import android.view.Surface
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.nio.ByteBuffer
import java.nio.IntBuffer
import kotlin.math.pow

private const val TAG = "Zebra"

/** Zebra setting: no stripes. Otherwise the signal level in percent, or [ZEBRA_CLIP]. */
const val ZEBRA_OFF = 0

/** Zebra setting: stripes just below where this profile clips, whatever signal value that is. */
const val ZEBRA_CLIP = 100

/**
 * Low-resolution map of the striped area in screen orientation: an alpha-only bitmap, opaque
 * where stripes go. The meter reuses its bitmaps in turn, so draw it right away, don't keep it.
 */
class ZebraMask(val bitmap: Bitmap)

/**
 * Zebra stripes for the viewfinder. A few times per second the preview is read back at a low
 * resolution (PixelCopy, no extra camera stream) in the color space it was encoded in, so the
 * pixel values are the signal the encoder gets: Apple Log, HLG or Rec.709 code values. Cells
 * whose brightest channel reaches the level's signal value go into [mask], turned the way the
 * screen shows the picture. Camera thread only.
 */
internal class ZebraMeter(
    private val handler: Handler,
    /** Display rotation in degrees: the readback comes without it, the screen shows it applied. */
    private val displayRotation: () -> Int,
) {
    private val _mask = MutableStateFlow<ZebraMask?>(null)
    val mask: StateFlow<ZebraMask?> = _mask.asStateFlow()

    private var surface: Surface? = null
    private var copying = false
    private var bitmap: Bitmap? = null
    private var pixels = IntBuffer.allocate(0)
    private var threshold = 1.0
    private var aspect = 4f / 3
    private var profile = ColorProfile.HLG
    private var lastLog = 0L
    private var copyNs = 0L

    /** Masks handed out in turn: one being drawn, one waiting to be, one being written. */
    private val masks = arrayOfNulls<Bitmap>(MASKS)
    private var nextMask = 0
    private var cells = ByteArray(0)

    /**
     * Meters [surface] for [level] in [profile]; a null surface or [ZEBRA_OFF] stops and
     * clears the stripes. [aspect] is the preview's width / height.
     */
    fun meter(surface: Surface?, level: Int, profile: ColorProfile, ownCurves: Boolean, aspect: Float) {
        if (surface == null || level == ZEBRA_OFF) return stop()
        val clip = clipSignal(profile, ownCurves)
        threshold = if (level == ZEBRA_CLIP) clip else minOf(level / 100.0, clip)
        this.aspect = aspect
        this.profile = profile
        if (!prepareBitmap()) return stop()
        if (surface === this.surface) return
        this.surface = surface
        handler.removeCallbacks(tick)
        if (!copying) handler.postDelayed(tick, FIRST_DELAY_MS)
    }

    /** A readback bitmap for [profile] in the orientation the readback comes in; false if there is none. */
    private fun prepareBitmap(): Boolean {
        val height = (MASK_W / aspect).toInt().coerceAtLeast(1)
        val sideways = displayRotation() % 180 != 0
        val (w, h) = if (sideways) height to MASK_W else MASK_W to height
        val space = ColorSpace.get(if (profile.tenBit) ColorSpace.Named.BT2020_HLG else ColorSpace.Named.BT709)
        val current = bitmap
        if (current != null && current.width == w && current.height == h && current.colorSpace == space) return true
        // HLG and Log share the HLG-tagged session: read back without any conversion.
        val config = if (profile.tenBit) Bitmap.Config.RGBA_1010102 else Bitmap.Config.ARGB_8888
        bitmap = runCatching { Bitmap.createBitmap(w, h, config, false, space) }
            .onFailure { Log.w(TAG, "no readback bitmap for $profile, zebra off", it) }
            .getOrNull()
        pixels = IntBuffer.allocate(w * h)
        return bitmap != null
    }

    fun stop() {
        surface = null
        handler.removeCallbacks(tick)
        _mask.value = null
    }

    private val tick = Runnable { copy() }

    private fun copy() {
        val source = surface ?: return
        // The screen may have turned since the last frame.
        if (!prepareBitmap()) return stop()
        val target = bitmap ?: return
        copying = true
        val requestedAt = SystemClock.elapsedRealtimeNanos()
        val requested = runCatching {
            PixelCopy.request(source, target, { result ->
                copying = false
                copyNs = SystemClock.elapsedRealtimeNanos() - requestedAt
                if (surface !== source) {
                    surface?.let { handler.postDelayed(tick, INTERVAL_MS) }
                    return@request
                }
                if (result == PixelCopy.SUCCESS && target === bitmap) _mask.value = evaluate(target)
                handler.postDelayed(tick, INTERVAL_MS)
            }, handler)
        }
        if (requested.isFailure) {
            copying = false
            handler.postDelayed(tick, INTERVAL_MS)
        }
    }

    /** Null when nothing reaches the level: the overlay then has nothing to redraw. */
    private fun evaluate(source: Bitmap): ZebraMask? {
        val start = SystemClock.elapsedRealtimeNanos()
        pixels.rewind()
        source.copyPixelsToBuffer(pixels)
        val data = pixels.array()
        val tenBit = source.config == Bitmap.Config.RGBA_1010102
        val rw = source.width
        val rh = source.height
        val rotation = displayRotation()
        // The mask in screen orientation; a quarter turn swaps the readback's sides.
        val (w, h) = if (rotation % 180 != 0) rh to rw else rw to rh
        if (cells.size != w * h) cells = ByteArray(w * h) else cells.fill(0)
        var peak = 0.0
        var hits = 0
        for (y in 0 until h) {
            for (x in 0 until w) {
                // The readback is the picture before the display rotation; turn it back.
                val c = data[
                    when (rotation) {
                        90 -> x * rw + (rw - 1 - y)
                        180 -> (rh - 1 - y) * rw + (rw - 1 - x)
                        270 -> (rh - 1 - x) * rw + y
                        else -> y * rw + x
                    },
                ]
                // copyPixelsToBuffer keeps the native byte order: R in the lowest bits for both configs.
                val max = if (tenBit) {
                    maxOf(c and 0x3FF, c shr 10 and 0x3FF, c shr 20 and 0x3FF) / 1023.0
                } else {
                    maxOf(c and 0xFF, c shr 8 and 0xFF, c shr 16 and 0xFF) / 255.0
                }
                if (max > peak) peak = max
                if (max >= threshold) {
                    cells[y * w + x] = -1
                    hits++
                }
            }
        }
        val now = SystemClock.elapsedRealtime()
        if (now - lastLog > LOG_INTERVAL_MS) {
            lastLog = now
            Log.d(
                TAG,
                "peak %.3f, threshold %.3f, striped %.1f %%, rotation $rotation, copy %.1f ms, evaluate %.1f ms".format(
                    peak, threshold, hits * 100.0 / cells.size, copyNs / 1e6, (SystemClock.elapsedRealtimeNanos() - start) / 1e6,
                ),
            )
        }
        if (hits == 0) return null
        val mask = masks[nextMask]?.takeIf { it.width == w && it.height == h }
            ?: Bitmap.createBitmap(w, h, Bitmap.Config.ALPHA_8).also { masks[nextMask] = it }
        nextMask = (nextMask + 1) % MASKS
        mask.copyPixelsFromBuffer(ByteBuffer.wrap(cells))
        return ZebraMask(mask)
    }

    companion object {
        private const val MASK_W = 192
        /** Every frame at 30 fps: at 10 per second the stripes visibly trailed a pan. */
        private const val INTERVAL_MS = 33L
        private const val MASKS = 3
        private const val FIRST_DELAY_MS = 600L
        private const val LOG_INTERVAL_MS = 2000L

        /** A fifth of a stop below the sensor clip: the stripes come just before detail is lost. */
        private const val CLIP_MARGIN_STOPS = 0.2

        fun clipSignal(profile: ColorProfile, ownCurves: Boolean) =
            signalAt(2.0.pow(-CLIP_MARGIN_STOPS), profile, ownCurves)

        /**
         * Signal value of [linear] light (1.0 = sensor clip) in [profile]. Without our curves the
         * vendor's shape is unknown; standard HLG and Rec.709 are close enough near the top.
         */
        fun signalAt(linear: Double, profile: ColorProfile, ownCurves: Boolean): Double = when (profile) {
            ColorProfile.LOG -> AppleLog.encode(linear * 0.18 / ToneCurves.GREY)
            ColorProfile.HLG -> ToneCurves.hlgOetf(linear)
            ColorProfile.REC709 -> if (ownCurves) {
                ToneCurves.rec709Oetf(ToneCurves.shoulder(linear * 0.18 / ToneCurves.GREY_709))
            } else {
                ToneCurves.rec709Oetf(linear)
            }
        }
    }
}
