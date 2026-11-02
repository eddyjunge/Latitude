package com.eddyjunge.latitude.camera

import android.content.ContentValues
import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.util.Log
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** A pending video file in [FOLDER]; invisible to other apps until [publish]. */
class VideoOutput private constructor(
    private val context: Context,
    private val uri: Uri,
    /** Read-write: [Mp4Writer] rewrites the index in place while recording. */
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

        /** Shorter than this, a take cut off by a crash holds no frame worth keeping. */
        private const val MIN_RECOVERED_BYTES = 1_000_000L

        /**
         * Takes a crash or a dead battery cut off stay pending, invisible to every app and deleted
         * by the system after a week. [Mp4Writer] keeps them playable up to the last second, so
         * they are published here. Call once per process, before anything records.
         */
        fun recoverPending(context: Context) {
            val resolver = context.contentResolver
            val collection = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            val args = Bundle().apply {
                putInt(MediaStore.QUERY_ARG_MATCH_PENDING, MediaStore.MATCH_ONLY)
                putString(android.content.ContentResolver.QUERY_ARG_SQL_SELECTION, "${MediaStore.Video.Media.RELATIVE_PATH} = ?")
                putStringArray(android.content.ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, arrayOf("$FOLDER/"))
            }
            val projection = arrayOf(MediaStore.Video.Media._ID, MediaStore.Video.Media.DISPLAY_NAME)
            runCatching {
                resolver.query(collection, projection, args, null)?.use { c ->
                    while (c.moveToNext()) {
                        val uri = ContentUris.withAppendedId(collection, c.getLong(0))
                        // The size column stays 0 until a file is closed properly; ask the file.
                        // Unknown size: leave it, never delete what can't be checked.
                        val size = runCatching { resolver.openFileDescriptor(uri, "r")?.use { it.statSize } }.getOrNull() ?: continue
                        if (size >= MIN_RECOVERED_BYTES) {
                            resolver.update(uri, ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }, null, null)
                            Log.i("VideoOutput", "recovered ${c.getString(1)} ($size bytes)")
                        } else {
                            resolver.delete(uri, null, null)
                        }
                    }
                }
            }.onFailure { Log.w("VideoOutput", "recovery failed", it) }
        }

        /** The profile goes into the name, so takes are told apart at a glance. */
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
