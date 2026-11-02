package com.eddyjunge.latitude.camera

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** A pending video file in [FOLDER]; invisible to other apps until [publish]. */
class VideoOutput private constructor(
    private val context: Context,
    private val uri: Uri,
    /** Read-write: [FullRange] patches the finished file in place. */
    val fd: ParcelFileDescriptor,
) {
    fun publish() {
        fd.close()
        val values = ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }
        context.contentResolver.update(uri, values, null, null)
    }

    fun discard() {
        runCatching { fd.close() }
        context.contentResolver.delete(uri, null, null)
    }

    companion object {
        /**
         * Not "Movies/Latitude": Android 16 returns nothing for any MediaStore query whose
         * selection contains the text "latitude" (it guards the location columns). Apps that look
         * a file up by its path, like the Xiaomi gallery, then can't find our clips and can't
         * delete them.
         */
        const val FOLDER = "Movies/LAT"

        /**
         * The profile goes into the name: the file is tagged HLG even in Log (MediaRecorder sets
         * no transfer of its own), so in Resolve the "_LOG" clips get Apple Log assigned by hand.
         */
        fun create(context: Context, profile: ColorProfile): VideoOutput {
            val tag = when (profile) {
                ColorProfile.REC709 -> "709"
                ColorProfile.HLG -> "HLG"
                ColorProfile.LOG -> "LOG"
            }
            val name = "LAT_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + "_$tag.mp4"
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, name)
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                put(MediaStore.Video.Media.RELATIVE_PATH, FOLDER)
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
            val resolver = context.contentResolver
            val collection = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            val uri = requireNotNull(resolver.insert(collection, values)) { "MediaStore insert failed" }
            val fd = try {
                requireNotNull(resolver.openFileDescriptor(uri, "rw"))
            } catch (e: Exception) {
                resolver.delete(uri, null, null)
                throw e
            }
            return VideoOutput(context, uri, fd)
        }
    }
}
