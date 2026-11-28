package app.redgrab

import android.net.Uri
import kotlinx.coroutines.flow.update

// What is saved, the download queue, synced collections and what is new. Part of Library, kept in a file of its own.

fun Library.markSaved(id: String, q: Quality) = synchronized(lock) {
    tx {
        store.putSaved(listOf(SavedRow(id, q.name, System.currentTimeMillis())))
        store.deleteFailures(listOf(id))
    }
    _state.update { s ->
        s.copy(qualities = s.qualities + (id to ((s.qualities[id] ?: emptySet()) + q)), failures = s.failures - id,
            savedAt = s.savedAt + (id to System.currentTimeMillis()))
    }
}

/** Records a failed download with a plain-language reason. */
fun Library.markFailed(id: String, reason: String, dead: Boolean = false) = synchronized(lock) {
    val now = System.currentTimeMillis()
    val old = _state.value.failures[id]
    val f = Failure(reason, old?.first ?: now, now, (old?.attempts ?: 0) + 1, dead)
    store.putFailures(listOf(FailureRow(id, f.reason, f.first, f.last, f.attempts, f.dead)))
    _state.update { it.copy(failures = it.failures + (id to f)) }
}

/** Forgets failures (e.g. before a manual retry, so the clip gets a fresh set of attempts). */
fun Library.clearFailures(ids: kotlin.collections.Collection<String>) = synchronized(lock) {
    if (ids.none { it in _state.value.failures }) return@synchronized
    AppDb.sliced(ids.toSet()) { store.deleteFailures(it) }
    _state.update { it.copy(failures = it.failures - ids.toSet()) }
}

fun Library.forgetHistory() {
    val scan = scanFolder()
    synchronized(lock) { store.clearSaved(); _state.update { it.copy(qualities = scan) } }
    AppLog.i("Download history cleared")
}

/** When each clip was last saved, from the download history. */
internal fun Library.readSavedAt(): Map<String, Long> {
    val out = HashMap<String, Long>()
    store.saved().forEach { r -> if (r.at > (out[r.id] ?: 0L)) out[r.id] = r.at }
    return out
}

internal fun Library.readHistory(): Map<String, Set<Quality>> {
    val out = HashMap<String, MutableSet<Quality>>()
    store.saved().forEach { r ->
        out.getOrPut(r.id) { mutableSetOf() } += runCatching { Quality.valueOf(r.quality) }.getOrDefault(Quality.LEGACY)
    }
    return out
}

internal fun Library.mergeQ(a: Map<String, Set<Quality>>, b: Map<String, Set<Quality>>): Map<String, Set<Quality>> {
    val out = HashMap<String, Set<Quality>>(a)
    b.forEach { (k, v) -> out[k] = (out[k] ?: emptySet()) + v }
    return out
}

/** Queue entries: "<id>" (preferred quality) or "<id> HD"/"<id> SD". */
fun Library.setQueue(items: List<String>) = tx {
    store.clearQueue()
    store.putQueue(items.filter { it.isNotBlank() }.mapIndexed { i, e -> QueueRow(i, e) })
}

fun Library.readQueue(): List<String> = store.queue()

fun Library.clearQueue() { store.clearQueue() }

fun Library.updateDownload(f: (DownloadProgress) -> DownloadProgress) = _download.update(f)

/** Stores a fresh copy of a clip (e.g. with new media URLs). */
fun Library.replaceClip(c: Clip) = synchronized(lock) {
    // Keep what only a full sync knows (where the clip came from, which collections hold it).
    raw = raw.map { if (it.id == c.id) c.copy(sources = it.sources, colIds = it.colIds) else it }
    extras = extras.map { if (it.id == c.id) c.copy(sources = it.sources, colIds = it.colIds) else it }
    // Keep the fresh addresses, so the next start doesn't begin with expired ones.
    val at = raw.indexOfFirst { it.id == c.id }
    if (at >= 0) store.putClips(listOf(ClipRow.of(raw[at], ClipRow.LIBRARY, at)))
    else extras.indexOfFirst { it.id == c.id }.takeIf { it >= 0 }?.let { i ->
        store.clips(ClipRow.EXTRA).firstOrNull { it.id == c.id }?.let { old -> store.putClips(listOf(ClipRow.of(extras[i], ClipRow.EXTRA, old.pos))) }
    }
    val clips = compose()
    _state.update { it.copy(clips = clips) }
}

/**
 * Fills in a clip's size, length and whether it has sound from its saved file, for clips
 * whose site didn't say (FikFap gives no size, Reddit no length for GIFs).
 */
fun Library.learnFromFile(id: String, file: Uri) {
    val c = synchronized(lock) { extras.firstOrNull { it.id == id } } ?: return
    if (c.width > 0 && c.height > 0 && c.duration > 0) return
    runCatching {
        val r = android.media.MediaMetadataRetriever()
        try {
            r.setDataSource(ctx, file)
            fun num(key: Int) = r.extractMetadata(key)?.toLongOrNull() ?: 0L
            var w = num(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH).toInt()
            var h = num(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT).toInt()
            // A video stored sideways is shown turned: swap so the tile gets the shape you see.
            if (num(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION).toInt() % 180 != 0) w = h.also { h = w }
            val sized = c.width > 0 && c.height > 0
            replaceClip(c.copy(
                width = if (sized) c.width else w, height = if (sized) c.height else h,
                duration = if (c.duration > 0) c.duration else num(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION) / 1000.0,
                hasAudio = r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO) == "yes",
            ))
        } finally { r.release() }
    }.onFailure { AppLog.w("Could not read size and length of $id: ${it.message}") }
}

fun Library.markSeen(ids: kotlin.collections.Collection<String>) = synchronized(lock) {
    val hit = ids.filter { it in _state.value.unseen }
    if (hit.isEmpty()) return@synchronized
    AppDb.sliced(hit) { store.deleteUnseen(it) }
    _state.update { it.copy(unseen = it.unseen - hit.toSet()) }
}

fun Library.markAllSeen() = synchronized(lock) {
    store.clearUnseen()
    _state.update { it.copy(unseen = emptySet()) }
}

fun Library.setWatched(key: String, on: Boolean) = synchronized(lock) {
    val w = if (on) _state.value.watched + key else _state.value.watched - key
    if (on) store.putWatched(listOf(WatchedRow(key))) else store.deleteWatched(key)
    _state.update { it.copy(watched = w) }
}

/** Clips in watched collections (or likes) that aren't on the phone yet. */
fun Library.pendingWatched(): List<String> {
    val s = _state.value
    if (s.watched.isEmpty()) return emptyList()
    return s.originals.filter { c ->
        c.id !in s.saved && s.failures[c.id]?.gaveUp != true &&
            ((Library.WATCH_LIKES in s.watched && c.liked) || c.colIds.any { it in s.watched })
    }.map { it.id }
}
