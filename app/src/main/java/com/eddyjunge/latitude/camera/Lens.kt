package com.eddyjunge.latitude.camera

import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import kotlin.math.hypot
import kotlin.math.roundToInt

/** Diagonal of a 36x24 mm full-frame sensor, for 35mm-equivalent focal lengths. */
private const val FULL_FRAME_DIAGONAL_MM = 43.27

/**
 * One physical lens the user can pick.
 *
 * Back lenses are streamed through the logical multi-camera ([openId]) with every output
 * bound to [physicalId]. That pins the stream to this lens: the vendor's automatic lens
 * switching (e.g. jumping to the ultra-wide for close focus) can no longer kick in.
 */
data class Lens(
    /** Stable key for saved settings. */
    val key: String,
    val openId: String,
    val physicalId: String?,
    val equivalentMm: Int,
    val front: Boolean,
    /** f-number; phone lenses have one fixed aperture. */
    val aperture: Float?,
) {
    /** The characteristics of the lens itself (physical camera if bound, else the opened one). */
    val characteristicsId: String get() = physicalId ?: openId

    val label: String get() = if (front) "FRONT" else "${equivalentMm}mm"

    /** How the lens is named in the clip's metadata, e.g. "23mm f/1.63" or "Front 21mm f/2.0". */
    val description: String
        get() = buildString {
            if (front) append("Front ")
            append("${equivalentMm}mm")
            aperture?.let { append(" f/").append(fNumber(it)) }
        }

    companion object {
        /** Back lenses sorted wide to tele, then front cameras. */
        fun discover(manager: CameraManager): List<Lens> {
            val lenses = mutableListOf<Lens>()
            val seenPhysical = mutableSetOf<String>()
            for (id in manager.cameraIdList) {
                val chars = manager.getCameraCharacteristics(id)
                val front = chars.get(CameraCharacteristics.LENS_FACING) == CameraMetadata.LENS_FACING_FRONT
                val caps = chars.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)?.toList().orEmpty()
                val physical = if (CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA in caps) {
                    chars.physicalCameraIds.filter { seenPhysical.add(it) }
                } else {
                    emptyList()
                }
                if (physical.isEmpty()) {
                    lenses += Lens("cam$id", id, null, equivalentMm(chars), front, aperture(chars))
                } else {
                    physical.forEach { pid ->
                        val pchars = manager.getCameraCharacteristics(pid)
                        lenses += Lens("cam$id/$pid", id, pid, equivalentMm(pchars), front, aperture(pchars))
                    }
                }
            }
            // Two physical cameras with the same focal length (e.g. a 2x crop mode of the main
            // sensor) would look identical in the picker; keep the first.
            return lenses.distinctBy { it.front to it.equivalentMm }
                .sortedWith(compareBy({ it.front }, { it.equivalentMm }))
        }

        private fun aperture(chars: CameraCharacteristics): Float? =
            chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_APERTURES)?.firstOrNull()

        /** "1.63", "2.2", "2.0": two decimals at most, one at least. */
        fun fNumber(f: Float): String =
            "%.2f".format(java.util.Locale.ROOT, f).trimEnd('0').let { if (it.endsWith('.')) it + "0" else it }

        private fun equivalentMm(chars: CameraCharacteristics): Int {
            val focal = chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.firstOrNull() ?: return 0
            val size = chars.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE) ?: return 0
            return (focal * FULL_FRAME_DIAGONAL_MM / hypot(size.width.toDouble(), size.height.toDouble())).roundToInt()
        }
    }
}
