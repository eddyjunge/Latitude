package com.eddyjunge.latitude.gallery

import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.util.LruCache
import android.util.Size
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.compose.PlayerSurface
import androidx.media3.ui.compose.SURFACE_TYPE_SURFACE_VIEW
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val Label = TextStyle(
    color = Color.White,
    fontSize = 13.sp,
    fontWeight = FontWeight.Medium,
    letterSpacing = 0.5.sp,
    fontFeatureSettings = "tnum",
)
private val Dim = Label.copy(color = Color(0x99FFFFFF))

/** One recorded clip in Movies/Latitude. */
data class Clip(
    val uri: Uri,
    val name: String,
    val durationMs: Long,
    val width: Int,
    val height: Int,
    val sizeBytes: Long,
)

/** Clips recorded by Latitude, newest first. Without media permission only our own files show. */
private fun loadClips(context: Context): List<Clip> {
    val collection = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
    val projection = arrayOf(
        MediaStore.Video.Media._ID,
        MediaStore.Video.Media.DISPLAY_NAME,
        MediaStore.Video.Media.DURATION,
        MediaStore.Video.Media.WIDTH,
        MediaStore.Video.Media.HEIGHT,
        MediaStore.Video.Media.SIZE,
    )
    val clips = mutableListOf<Clip>()
    context.contentResolver.query(
        collection,
        projection,
        "${MediaStore.Video.Media.RELATIVE_PATH} LIKE ?",
        arrayOf("Movies/Latitude%"),
        "${MediaStore.Video.Media.DATE_ADDED} DESC",
    )?.use { c ->
        while (c.moveToNext()) {
            clips += Clip(
                uri = ContentUris.withAppendedId(collection, c.getLong(0)),
                name = c.getString(1),
                durationMs = c.getLong(2),
                width = c.getInt(3),
                height = c.getInt(4),
                sizeBytes = c.getLong(5),
            )
        }
    }
    return clips
}

private fun formatDuration(ms: Long): String {
    val s = ms / 1000
    return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
}

private fun formatSize(bytes: Long): String =
    if (bytes >= 1_000_000_000) "%.1f GB".format(bytes / 1e9) else "%.0f MB".format(bytes / 1e6)

/** Grid of recorded clips; tap one to play it, share it or delete it. */
class GalleryActivity : ComponentActivity() {
    /** Null until the first query is back, so an empty gallery isn't announced too early. */
    private var clips by mutableStateOf<List<Clip>?>(null)
    private var open by mutableStateOf<Clip?>(null)

    private val deleteLauncher = registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) {
        if (it.resultCode == RESULT_OK) {
            open = null
            reload()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.getInsetsController(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        setContent {
            Box(Modifier.fillMaxSize().background(Color.Black)) {
                val clip = open
                if (clip == null) {
                    ClipGrid(clips.orEmpty(), loaded = clips != null, onOpen = { open = it }, onClose = ::finish)
                } else {
                    BackHandler { open = null }
                    ClipPlayer(clip, onClose = { open = null }, onShare = { share(clip) }, onDelete = { delete(clip) })
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        reload()
    }

    /** The MediaStore query runs off the main thread so the grid opens without a hitch. */
    private fun reload() {
        lifecycleScope.launch {
            clips = withContext(Dispatchers.IO) { loadClips(applicationContext) }
        }
    }

    private fun share(clip: Clip) {
        val send = Intent(Intent.ACTION_SEND)
            .setType("video/mp4")
            .putExtra(Intent.EXTRA_STREAM, clip.uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        startActivity(Intent.createChooser(send, clip.name))
    }

    /** The system asks the user to confirm, so a stray tap can't lose a clip. */
    private fun delete(clip: Clip) {
        val request = MediaStore.createDeleteRequest(contentResolver, listOf(clip.uri))
        deleteLauncher.launch(IntentSenderRequest.Builder(request.intentSender).build())
    }
}

@Composable
private fun ClipGrid(clips: List<Clip>, loaded: Boolean, onOpen: (Clip) -> Unit, onClose: () -> Unit) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(220.dp),
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BasicText("‹ KAMERA", style = Label, modifier = Modifier.clickable(onClick = onClose).padding(8.dp))
                BasicText("${clips.size} Clips · ${formatSize(clips.sumOf { it.sizeBytes })}", style = Dim)
            }
        }
        if (loaded && clips.isEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                BasicText("Noch keine Aufnahmen", style = Dim, modifier = Modifier.padding(32.dp))
            }
        }
        items(clips, key = { it.uri.toString() }) { clip -> ClipTile(clip, onClick = { onOpen(clip) }) }
    }
}

/** Decoded thumbnails, up to 48 MB, kept while the app runs. */
private val thumbnails = object : LruCache<Uri, Bitmap>(48 * 1024 * 1024) {
    override fun sizeOf(key: Uri, value: Bitmap) = value.allocationByteCount
}

@Composable
private fun ClipTile(clip: Clip, onClick: () -> Unit) {
    val context = LocalContext.current
    // Scrolling back to a tile shows the cached thumbnail at once instead of decoding again.
    val thumb by produceState(thumbnails.get(clip.uri), clip.uri) {
        if (value != null) return@produceState
        value = withContext(Dispatchers.IO) {
            runCatching { context.contentResolver.loadThumbnail(clip.uri, Size(512, 384), null) }.getOrNull()
        }?.also { thumbnails.put(clip.uri, it) }
    }
    Box(
        Modifier
            .aspectRatio(4f / 3f)
            .clip(RoundedCornerShape(8.dp))
            .background(Color(0xFF1C1C1E))
            .clickable(onClick = onClick),
    ) {
        thumb?.let {
            Image(it.asImageBitmap(), contentDescription = clip.name, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
        }
        Row(
            modifier = Modifier.align(Alignment.BottomStart).fillMaxWidth()
                .background(Color(0x88000000)).padding(horizontal = 8.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            BasicText(formatDuration(clip.durationMs), style = Label.copy(fontSize = 12.sp))
            BasicText("${clip.width}×${clip.height}", style = Dim.copy(fontSize = 11.sp))
        }
    }
}

/** SurfaceView output so 10-bit HLG clips play in HDR on the phone's display. */
@Composable
private fun ClipPlayer(clip: Clip, onClose: () -> Unit, onShare: () -> Unit, onDelete: () -> Unit) {
    val context = LocalContext.current
    val player = remember(clip.uri) {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(clip.uri))
            repeatMode = Player.REPEAT_MODE_ONE
            playWhenReady = true
            prepare()
        }
    }
    DisposableEffect(player) { onDispose { player.release() } }

    var playing by remember { mutableStateOf(true) }
    var position by remember { mutableLongStateOf(0L) }
    var progress by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(player) {
        while (true) {
            position = player.currentPosition
            progress = if (clip.durationMs > 0) position.toFloat() / clip.durationMs else 0f
            playing = player.isPlaying
            delay(100)
        }
    }

    Box(Modifier.fillMaxSize()) {
        val aspect = if (clip.height > 0) clip.width.toFloat() / clip.height else 16f / 9f
        PlayerSurface(
            player = player,
            surfaceType = SURFACE_TYPE_SURFACE_VIEW,
            modifier = Modifier.align(Alignment.Center).fillMaxHeight().aspectRatio(aspect)
                .clickable { if (player.isPlaying) player.pause() else player.play() },
        )
        Row(
            modifier = Modifier.align(Alignment.TopStart).fillMaxWidth().padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            BasicText("‹ GALERIE", style = Label, modifier = Modifier.clickable(onClick = onClose).padding(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                BasicText("TEILEN", style = Label, modifier = Modifier.clickable(onClick = onShare).padding(8.dp))
                BasicText(
                    "LÖSCHEN",
                    style = Label.copy(color = Color(0xFFFF453A)),
                    modifier = Modifier.clickable(onClick = onDelete).padding(8.dp),
                )
            }
        }
        Row(
            modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                .background(Color(0x66000000)).padding(horizontal = 24.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            BasicText(
                if (playing) "❚❚" else "▶",
                style = Label.copy(fontSize = 16.sp),
                modifier = Modifier.clickable { if (player.isPlaying) player.pause() else player.play() }.padding(4.dp),
            )
            BasicText(formatDuration(position), style = Label)
            SeekBar(progress, Modifier.weight(1f)) { player.seekTo((it * clip.durationMs).toLong()) }
            BasicText(formatDuration(clip.durationMs), style = Dim)
        }
    }
}

@Composable
private fun SeekBar(progress: Float, modifier: Modifier, onSeek: (Float) -> Unit) {
    Box(
        modifier
            .height(24.dp)
            .pointerInput(Unit) { detectTapGestures { onSeek((it.x / size.width).coerceIn(0f, 1f)) } },
        contentAlignment = Alignment.CenterStart,
    ) {
        Box(Modifier.fillMaxWidth().height(3.dp).background(Color(0x44FFFFFF), RoundedCornerShape(2.dp)))
        Box(Modifier.fillMaxWidth(progress.coerceIn(0f, 1f)).height(3.dp).background(Color.White, RoundedCornerShape(2.dp)))
    }
}
