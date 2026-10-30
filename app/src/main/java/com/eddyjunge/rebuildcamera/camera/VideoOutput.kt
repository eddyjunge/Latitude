package com.eddyjunge.rebuildcamera.camera

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** A pending video file in Movies/RebuildCamera; invisible to other apps until [publish]. */
class VideoOutput private constructor(
    private val context: Context,
    private val uri: Uri,
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
        fun create(context: Context): VideoOutput {
            val name = "RC_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".mp4"
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, name)
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/RebuildCamera")
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
            val resolver = context.contentResolver
            val collection = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            val uri = requireNotNull(resolver.insert(collection, values)) { "MediaStore insert failed" }
            val fd = try {
                requireNotNull(resolver.openFileDescriptor(uri, "w"))
            } catch (e: Exception) {
                resolver.delete(uri, null, null)
                throw e
            }
            return VideoOutput(context, uri, fd)
        }
    }
}
