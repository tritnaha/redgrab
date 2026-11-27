package app.redgrab

import android.content.Context
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.FrameDropEffect
import androidx.media3.effect.Presentation
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import androidx.media3.transformer.VideoEncoderSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Makes a small copy of a saved clip for the grid previews: a few seconds long, about 360
 * pixels on its short side, no sound. A grid tile is far smaller than a full-HD picture, so
 * playing the original means decoding roughly ten times more than the tile can show; with these
 * copies a screenful of previews costs about as much as one HD clip did.
 *
 * Copies live in the app's private storage (files/previews), not in the save folder, and can be
 * cleared at any time: a clip without one simply plays from its own file as before. They are
 * made one at a time, in the background, for the clips that come up on screen (or for the whole
 * library when asked), and not in battery saver. Nothing leaves the phone.
 */
@OptIn(UnstableApi::class)
object PreviewMaker {
    /** Length of a preview loop. */
    const val LENGTH_MS = 6_000L
    /** The short side of the small picture, in pixels. */
    const val SHORT_SIDE = 360
    /** What a preview copy costs to decode next to a full-HD stream (360×640 against 1080×1920). */
    const val COST = 0.12f
    /** While copies are being made, this much decoding is left free for the conversion (in full-HD streams). */
    const val RESERVE = 1.5f
    private const val URGENT_MAX = 40
    private const val GIVE_UP_AFTER = 6

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private class Job(val clip: Clip, val source: Uri)
    private val lock = Any()
    /** Clips that came up on screen: the newest is made first, since that is what you are looking at. */
    private val urgent = ArrayDeque<Job>()
    /** "Make all": in library order, whenever nothing on screen is waiting. */
    private val bulk = ArrayDeque<Job>()
    private val wake = Channel<Unit>(Channel.CONFLATED)
    /** Waiting or being made, so a clip isn't queued twice. */
    private val pending = HashSet<String>()
    /** Couldn't be made (odd format, unreadable): not tried again until the app restarts. */
    private val failed = ConcurrentHashMap.newKeySet<String>()
    @Volatile private var failuresInARow = 0
    private var started = false

    private val _waiting = MutableStateFlow(0)
    /** How many clips are waiting for their copy (for the progress line in Settings). */
    val waiting: StateFlow<Int> = _waiting

    /** Where a preview loop starts in a clip of [durationMs]: a little way in for longer clips, so it isn't the intro. */
    fun startMs(durationMs: Long): Long = if (durationMs > 2 * LENGTH_MS) (durationMs * 15 / 100).coerceAtMost(5_000) else 0

    /** The height to scale to so that the short side becomes [SHORT_SIDE] (never larger than the original). */
    fun targetHeight(width: Int, height: Int): Int {
        if (width <= 0 || height <= 0) return SHORT_SIDE
        val h = if (width < height) SHORT_SIDE.toLong() * height / width else SHORT_SIDE.toLong()
        // Encoders want even sizes.
        return (h.toInt().coerceAtMost(height) / 2 * 2).coerceAtLeast(2)
    }

    /**
     * Asks for a copy of [clip] to be made from its saved file [source], unless there is one, it
     * is queued, or it failed before. [onScreen] = it is showing now and goes ahead of the rest.
     */
    fun request(ctx: Context, clip: Clip, source: Uri, onScreen: Boolean = true): Boolean {
        val lib = ctx.library
        if (!lib.prefs.previewCopies || clip.isImage || failuresInARow >= GIVE_UP_AFTER) return false
        if (clip.id in lib.state.value.localPreviews || clip.id in failed) return false
        synchronized(lock) {
            if (!pending.add(clip.id)) return false
            if (onScreen) {
                urgent.addFirst(Job(clip, source))
                // Clips scrolled past long ago aren't worth the battery; they are asked for again when they next show.
                while (urgent.size > URGENT_MAX) pending -= urgent.removeLast().clip.id
            } else bulk.addLast(Job(clip, source))
            _waiting.value = pending.size
            if (!started) { started = true; scope.launch { work(ctx.applicationContext) } }
        }
        wake.trySend(Unit)
        return true
    }

    /** Queues every saved clip that has no copy yet. Returns how many were added. */
    fun requestAll(ctx: Context): Int {
        val lib = ctx.library
        val s = lib.state.value
        var n = 0
        for (c in s.clips) {
            if (c.isImage || c.id !in s.saved || c.id in s.localPreviews) continue
            val (uri, local) = lib.previewSource(c, allowStream = false, verify = false) ?: continue
            if (!local) continue
            if (request(ctx, c, uri, onScreen = false)) n++
        }
        return n
    }

    /** Drops everything that is waiting (the one being made finishes). */
    fun cancel() = synchronized(lock) {
        (urgent + bulk).forEach { pending -= it.clip.id }
        urgent.clear(); bulk.clear()
        _waiting.value = pending.size
    }

    private fun next(): Job? = synchronized(lock) { urgent.removeFirstOrNull() ?: bulk.removeFirstOrNull() }

    private suspend fun work(app: Context) {
        val lib = app.library
        // Half-made copies left by a run that was cut short.
        runCatching { lib.previewFile("x").parentFile?.listFiles()?.filter { it.name.endsWith(".tmp") }?.forEach { it.delete() } }
        while (true) {
            val job = next() ?: run { wake.receive(); null } ?: continue
            val clip = job.clip
            try {
                val saving = app.getSystemService(android.os.PowerManager::class.java)?.isPowerSaveMode == true
                if (!lib.prefs.previewCopies || saving || clip.id in lib.state.value.localPreviews || failuresInARow >= GIVE_UP_AFTER) continue
                // Two minutes is far more than a few seconds of small video needs; a stuck conversion is abandoned.
                val result = withTimeoutOrNull(120_000) { runCatching { make(app, clip, job.source) } }
                if (result?.isSuccess == true) { failuresInARow = 0; lib.notePreview(clip.id); continue }
                File(lib.previewFile(clip.id).path + ".tmp").delete()
                val e = result?.exceptionOrNull()
                if (e is kotlinx.coroutines.CancellationException) throw e
                // No free decoder or encoder (previews and the player use them too): not this clip's fault, so it may be tried again later.
                val busy = e is ExportException && (e.errorCode == ExportException.ERROR_CODE_DECODER_INIT_FAILED || e.errorCode == ExportException.ERROR_CODE_ENCODER_INIT_FAILED)
                if (!busy) failed += clip.id
                AppLog.w("No preview copy for ${clip.id}: ${e?.message ?: "took too long"}")
                if (++failuresInARow == GIVE_UP_AFTER) { AppLog.e("$GIVE_UP_AFTER preview copies in a row failed; no more are made until the app restarts"); cancel() }
                else if (busy) delay(3_000)
            } finally {
                synchronized(lock) { pending -= clip.id; _waiting.value = pending.size }
            }
        }
    }

    /** Converts one clip. Throws when the phone can't read or convert it. */
    private suspend fun make(app: Context, clip: Clip, source: Uri) {
        val lib = app.library
        // The file itself says how long and how large it is (sites don't always).
        var durationMs = (clip.duration * 1000).toLong()
        var w = clip.width
        var h = clip.height
        withContext(Dispatchers.IO) {
            runCatching {
                val r = android.media.MediaMetadataRetriever()
                try {
                    r.setDataSource(app, source)
                    fun num(key: Int) = r.extractMetadata(key)?.toLongOrNull() ?: 0L
                    num(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION).takeIf { it > 0 }?.let { durationMs = it }
                    val fw = num(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH).toInt()
                    val fh = num(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT).toInt()
                    if (fw > 0 && fh > 0) {
                        val turned = num(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION).toInt() % 180 != 0
                        w = if (turned) fh else fw
                        h = if (turned) fw else fh
                    }
                } finally { r.release() }
            }
        }
        val out = lib.previewFile(clip.id)
        val tmp = File(out.path + ".tmp")
        out.parentFile?.mkdirs()
        tmp.delete()

        val start = startMs(durationMs)
        val clipping = MediaItem.ClippingConfiguration.Builder().setStartPositionMs(start)
            // Only cut the end when the clip really is longer than a loop.
            .apply { if (durationMs > start + LENGTH_MS) setEndPositionMs(start + LENGTH_MS) }.build()
        val item = MediaItem.Builder().setUri(source).setClippingConfiguration(clipping).build()
        val edited = EditedMediaItem.Builder(item)
            .setRemoveAudio(true)
            .setEffects(Effects(emptyList(), listOf(FrameDropEffect.createDefaultFrameDropEffect(30f), Presentation.createForHeight(targetHeight(w, h)))))
            .build()

        // Transformer wants a thread with a message loop; the conversion itself runs on its own threads.
        withContext(Dispatchers.Main) {
            val transformer = Transformer.Builder(app)
                .setVideoMimeType(MimeTypes.VIDEO_H264)
                .setEncoderFactory(DefaultEncoderFactory.Builder(app)
                    .setRequestedVideoEncoderSettings(VideoEncoderSettings.Builder().setBitrate(450_000).build()).build())
                .build()
            suspendCancellableCoroutine { cont ->
                transformer.addListener(object : Transformer.Listener {
                    override fun onCompleted(composition: Composition, result: ExportResult) { cont.resume(Unit) }
                    override fun onError(composition: Composition, result: ExportResult, e: ExportException) { cont.resumeWithException(e) }
                })
                cont.invokeOnCancellation { transformer.cancel() }
                transformer.start(edited, tmp.path)
            }
        }
        if (tmp.length() < 1_000 || !tmp.renameTo(out)) { tmp.delete(); throw java.io.IOException("the copy came out empty") }
    }
}
