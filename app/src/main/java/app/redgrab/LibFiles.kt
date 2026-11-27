package app.redgrab

import android.net.Uri
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.flow.update
import java.io.File

// Thumbnails, preview copies, imported files, the file index and storage helpers. Part of Library, kept in a file of its own.

fun Library.thumbFile(id: String) = File(thumbDir, id.replace(Library.UNSAFE_NAME, "_") + ".jpg")

/** Saves a still frame of a saved video as its thumbnail. Returns true on success. */
fun Library.makeThumb(id: String, video: Uri): Boolean = runCatching {
    thumbDir.mkdirs()
    val r = android.media.MediaMetadataRetriever()
    try {
        r.setDataSource(ctx, video)
        val durMs = r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0
        val at = (durMs * 1000 / 4).coerceAtMost(2_000_000) // a quarter in, at most 2 s
        val bmp = r.getScaledFrameAtTime(at, android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 480, 480) ?: return@runCatching false
        val tmp = File(thumbFile(id).path + ".tmp")
        tmp.outputStream().use { bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 82, it) }
        bmp.recycle()
        tmp.renameTo(thumbFile(id))
    } finally { r.release() }
    _state.update { it.copy(localThumbs = it.localThumbs + id) }
    true
}.getOrDefault(false)

/** Creates missing thumbnails for clips already on the phone (older saves, files added by hand). */
fun Library.backfillThumbs() {
    val s = _state.value
    val todo = s.clips.filter { it.id in s.saved && it.id !in s.localThumbs }
    if (todo.isEmpty()) return
    var made = 0
    for (c in todo) {
        val file = localFile(c)?.first ?: continue
        if (if (c.isImage) makeImageThumb(c.id, file) != null else makeThumb(c.id, file)) made++
    }
    if (made > 0) AppLog.i("Created $made offline thumbnail(s)")
}

internal fun Library.dropThumbs(ids: kotlin.collections.Collection<String>) {
    ids.forEach { thumbFile(it).delete(); previewFile(it).delete() }
    _state.update { it.copy(localThumbs = it.localThumbs - ids.toSet(), localPreviews = it.localPreviews - ids.toSet()) }
}

fun Library.previewFile(id: String) = File(previewDir, id.replace(Library.UNSAFE_NAME, "_") + ".mp4")

/** The small preview copy of a clip, if the setting is on and one has been made. */
fun Library.previewCopy(c: Clip): Uri? =
    if (prefs.previewCopies && c.id in _state.value.localPreviews) Uri.fromFile(previewFile(c.id)) else null

fun Library.notePreview(id: String) = _state.update { it.copy(localPreviews = it.localPreviews + id) }

/** Forgets a copy that wouldn't play; the clip plays from its own file again. */
fun Library.dropPreview(id: String) {
    previewFile(id).delete()
    _state.update { it.copy(localPreviews = it.localPreviews - id) }
}

/** Bytes used by the preview copies. */
fun Library.previewBytes(): Long = previewDir.listFiles()?.sumOf { it.length() } ?: 0L

fun Library.clearPreviews() {
    previewDir.listFiles()?.forEach { it.delete() }
    _state.update { it.copy(localPreviews = emptySet()) }
}

/** Still picture for a GIF/WebP/photo: first frame, scaled down. Returns the picture's size. */
fun Library.makeImageThumb(id: String, image: Uri): Pair<Int, Int>? = runCatching {
    thumbDir.mkdirs()
    val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
    ctx.contentResolver.openInputStream(image)!!.use { android.graphics.BitmapFactory.decodeStream(it, null, bounds) }
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@runCatching null
    var sample = 1
    while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= 480) sample *= 2
    val opts = android.graphics.BitmapFactory.Options().apply { inSampleSize = sample }
    val bmp = ctx.contentResolver.openInputStream(image)!!.use { android.graphics.BitmapFactory.decodeStream(it, null, opts) }
        ?: return@runCatching null
    val tmp = File(thumbFile(id).path + ".tmp")
    tmp.outputStream().use { bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 82, it) }
    bmp.recycle()
    tmp.renameTo(thumbFile(id))
    _state.update { it.copy(localThumbs = it.localThumbs + id) }
    bounds.outWidth to bounds.outHeight
}.getOrNull()

internal fun Library.importedDir(create: Boolean): DocumentFile? {
    val root = folder() ?: return null
    return root.findFile(Library.IMPORT_DIR)?.takeIf { it.isDirectory } ?: if (create) root.createDirectory(Library.IMPORT_DIR) else null
}

internal fun Library.children(tree: Uri, parentDocId: String): List<Library.Doc> {
    val out = ArrayList<Library.Doc>()
    val cols = arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME,
        DocumentsContract.Document.COLUMN_LAST_MODIFIED, DocumentsContract.Document.COLUMN_MIME_TYPE)
    ctx.contentResolver.query(DocumentsContract.buildChildDocumentsUriUsingTree(tree, parentDocId), cols, null, null, null)?.use { c ->
        while (c.moveToNext()) {
            val name = c.getString(1) ?: continue
            out += Library.Doc(DocumentsContract.buildDocumentUriUsingTree(tree, c.getString(0)), name, c.getLong(2),
                c.getString(3) == DocumentsContract.Document.MIME_TYPE_DIR)
        }
    }
    return out
}

/**
 * Moves every GIF, WebP and video in [source] (a folder you picked, subfolders included) into
 * the save folder's "imported" subfolder, then lists them in the library. Returns how many
 * files were moved and how many couldn't be. Safe to run again: it carries on with what's left.
 */
fun Library.importFolder(source: Uri, progress: (String) -> Unit): Pair<Int, Int> {
    val dest = importedDir(create = true) ?: error("Choose a save folder first")
    val resolver = ctx.contentResolver
    val wanted = setOf("gif", "webp") + Clip.VIDEO_EXT
    // Collect the files first (so the count is known), folder by folder.
    val found = ArrayList<Pair<Library.Doc, Uri>>() // file, its parent folder
    val todo = ArrayDeque(listOf(DocumentsContract.buildDocumentUriUsingTree(source, DocumentsContract.getTreeDocumentId(source))))
    val destId = DocumentsContract.getDocumentId(dest.uri)
    while (todo.isNotEmpty() && found.size < 50_000) {
        val dir = todo.removeFirst()
        if (DocumentsContract.getDocumentId(dir) == destId) continue // never import from our own folder
        for (d in children(source, DocumentsContract.getDocumentId(dir))) {
            if (d.isDir) todo += d.uri
            else if (d.name.substringAfterLast('.', "").lowercase() in wanted) found += d to dir
        }
    }
    var moved = 0
    var failed = 0
    for ((i, pair) in found.withIndex()) {
        val (doc, parent) = pair
        if (i % 10 == 0) progress("Moving files: %,d of %,d".format(i, found.size))
        val ok = runCatching {
            // Same storage: a quick move. Otherwise copy, then remove the original.
            runCatching { DocumentsContract.moveDocument(resolver, doc.uri, parent, dest.uri) }.getOrNull() != null || run {
                val mime = if (doc.name.endsWith(".gif", true)) "image/gif" else "application/octet-stream"
                val copy = dest.createFile(mime, doc.name) ?: error("Could not create ${doc.name}")
                resolver.openInputStream(doc.uri)!!.use { input -> resolver.openOutputStream(copy.uri)!!.use { input.copyTo(it) } }
                DocumentsContract.deleteDocument(resolver, doc.uri)
                true
            }
        }.onFailure { AppLog.w("Could not import ${doc.name}: ${it.message}") }.getOrDefault(false)
        if (ok) moved++ else failed++
    }
    AppLog.i("Import: moved $moved file(s) into $Library.IMPORT_DIR" + if (failed > 0) ", $failed failed" else "")
    adoptImported(progress)
    return moved to failed
}

/**
 * Lists every file in the "imported" subfolder that the library doesn't know yet (after an
 * import, or files you put there yourself). Returns how many were added.
 */
fun Library.adoptImported(progress: (String) -> Unit = {}): Int = synchronized(importLock) { adoptNewFiles(progress) }

internal fun Library.adoptNewFiles(progress: (String) -> Unit): Int {
    val dir = importedDir(create = false) ?: return 0
    val tree = prefs.folderUri ?: return 0
    val known = synchronized(lock) { imports.mapNotNullTo(HashSet()) { it.copyFile } }
    val fresh = children(tree, DocumentsContract.getDocumentId(dir.uri))
        .filter { !it.isDir && "$Library.IMPORT_DIR/${it.name}" !in known }
        .filter { it.name.substringAfterLast('.', "").lowercase() in Clip.IMAGE_EXT + Clip.VIDEO_EXT }
        .sortedBy { it.name.lowercase() }
    if (fresh.isEmpty()) return 0
    val taken = synchronized(lock) { (raw + extras + imports).mapTo(HashSet()) { it.id } }
    var added = 0
    for (batch in fresh.chunked(40)) {
        progress("Adding to the library: %,d of %,d".format(added, fresh.size))
        val clips = batch.map { d ->
            val stem = d.name.substringBeforeLast('.').lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').take(40).ifEmpty { "file" }
            var id = "imp-$stem"
            var n = 2
            while (!taken.add(id)) id = "imp-$stem-${n++}"
            val file = "$Library.IMPORT_DIR/${d.name}"
            val isImage = d.name.substringAfterLast('.', "").lowercase() in Clip.IMAGE_EXT
            var w = 0; var h = 0; var dur = 0.0; var audio = false
            if (isImage) makeImageThumb(id, d.uri)?.let { w = it.first; h = it.second }
            else runCatching {
                val r = android.media.MediaMetadataRetriever()
                try {
                    r.setDataSource(ctx, d.uri)
                    w = r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
                    h = r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
                    dur = (r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0) / 1000.0
                    audio = r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO) == "yes"
                } finally { r.release() }
                makeThumb(id, d.uri)
            }
            Clip(id = id, hd = null, sd = null, thumb = null, duration = dur, width = w, height = h, hasAudio = audio,
                user = "", created = d.modified / 1000, sources = setOf("Imported"), copyFile = file)
        }
        synchronized(lock) {
            val start = imports.size
            imports = imports + clips
            store.putClips(clips.mapIndexed { i, c -> ClipRow.of(c, ClipRow.IMPORTED, start + i) })
            val all = compose()
            _state.update { it.copy(clips = all) }
        }
        added += clips.size
    }
    AppLog.i("Added $added imported file(s) to the library")
    return added
}

/**
 * Saves a file found online that keeps its own type (a sex.com GIF, a 4chan WebM) into the
 * "imported" subfolder as `<id>.<type>` and lists it in the library with its title and
 * tags. [urls] are tried in order; the first that turns out to be a real picture or video
 * is kept.
 */
fun Library.addOnlineFile(c: Clip, http: okhttp3.OkHttpClient, urls: List<String>, onProgress: (Long, Long) -> Unit = { _, _ -> }) {
    require(Clip.validId(c.id)) { "Bad clip ID" }
    if (synchronized(lock) { imports.any { it.id == c.id } }) return
    val dir = importedDir(create = true) ?: error("Choose a save folder first")
    val resolver = ctx.contentResolver
    var failure: Exception? = null
    for (url in urls) {
        val ext = url.substringBefore('?').substringAfterLast('.', "").lowercase().takeIf { it in Clip.IMAGE_EXT || it in Clip.VIDEO_EXT } ?: continue
        val name = "${c.id}.$ext"
        dir.findFile("$name.part")?.delete()
        val part = dir.createFile("application/octet-stream", "$name.part") ?: error("Could not create file in folder")
        try {
            RedGifsApi.download(http, url).use { r ->
                if (r.header("Content-Length") == "0" || r.body?.contentType()?.type == "text") throw java.io.IOException("Not a media file")
                val total = r.body!!.contentLength()
                resolver.openOutputStream(part.uri, "wt")!!.use { out ->
                    val input = r.body!!.byteStream()
                    val buf = ByteArray(64 * 1024)
                    var done = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        onProgress(done, total)
                    }
                }
            }
            // Reading its size doubles as the check that the file is what it says; also makes the offline thumbnail.
            var duration = 0.0
            var audio = false
            val size = if (ext in Clip.IMAGE_EXT) makeImageThumb(c.id, part.uri) ?: throw java.io.IOException("Not a picture")
            else {
                val r = android.media.MediaMetadataRetriever()
                try {
                    r.setDataSource(ctx, part.uri)
                    fun num(key: Int) = r.extractMetadata(key)?.toLongOrNull() ?: 0L
                    duration = num(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION) / 1000.0
                    audio = r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO) == "yes"
                    if (r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO) != "yes" || duration <= 0) throw java.io.IOException("Not a playable video")
                    var w = num(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH).toInt()
                    var h = num(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT).toInt()
                    if (num(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION).toInt() % 180 != 0) w = h.also { h = w }
                    w to h
                } catch (e: RuntimeException) {
                    throw java.io.IOException("Not a playable video")
                } finally { r.release() }.also { makeThumb(c.id, part.uri) }
            }
            synchronized(importLock) {
                val done = DocumentsContract.renameDocument(resolver, part.uri, name) ?: error("Could not rename the finished file")
                val finalName = DocumentFile.fromSingleUri(ctx, done)?.name ?: name
                val clip = c.copy(sd = null, width = size.first, height = size.second, hasAudio = audio, duration = duration,
                    created = c.created.takeIf { it > 0 } ?: (System.currentTimeMillis() / 1000),
                    sources = c.sources.ifEmpty { setOf(OnlineSource.of(c.id).label) }, copyFile = "$Library.IMPORT_DIR/$finalName")
                synchronized(lock) {
                    imports = imports + clip
                    store.putClips(listOf(ClipRow.of(clip, ClipRow.IMPORTED, (store.clips(ClipRow.IMPORTED).lastOrNull()?.pos ?: -1) + 1)))
                    val all = compose()
                    _state.update { it.copy(clips = all) }
                }
            }
            AppLog.i("Saved ${c.id} as $Library.IMPORT_DIR/$name (${c.tags.size} tag(s))")
            return
        } catch (e: Exception) {
            failure = e
            runCatching { part.delete() }
            dropThumbs(listOf(c.id))
            AppLog.w("Could not save ${c.id} as .$ext: ${e.message}")
        }
    }
    throw failure ?: java.io.IOException("No file address")
}

/**
 * Records a finished file. If another saved file has exactly the same content (same SHA-256),
 * it's noted as a duplicate; clips are normally told apart by their RedGifs ID, so this only
 * happens when the very same video exists under two IDs.
 */
fun Library.recordFile(name: String, size: Long, sha: String) = synchronized(lock) {
    fileIndex = fileIndex + (name to (size to sha))
    store.putFiles(listOf(FileRow(name, size, sha)))
    val dups = findDuplicates()
    dups[name]?.let { AppLog.w("$name has exactly the same content as $it") }
    _state.update { it.copy(duplicates = dups) }
}

internal fun Library.forgetFiles(names: kotlin.collections.Collection<String>) {
    if (names.none { it in fileIndex }) return
    fileIndex = fileIndex - names.toSet()
    AppDb.sliced(names.toSet()) { store.deleteFiles(it) }
}

internal fun Library.findDuplicates(): Map<String, String> {
    val first = HashMap<String, String>()
    val out = HashMap<String, String>()
    fileIndex.entries.sortedBy { it.key }.forEach { (name, v) ->
        val seen = first.putIfAbsent(v.second, name)
        if (seen != null) out[name] = seen
    }
    return out
}

/** Replaces the stored likes/collections list with [raw] (one transaction). */
internal fun Library.saveLibrary() = tx {
    store.clearClips(ClipRow.LIBRARY)
    store.putClips(raw.mapIndexed { i, c -> ClipRow.of(c, ClipRow.LIBRARY, i) })
}

internal fun Library.saveExtras() = tx {
    val inLibrary = raw.mapTo(HashSet()) { it.id }
    store.clearClips(ClipRow.EXTRA)
    store.putClips(extras.filter { it.id !in inLibrary }.mapIndexed { i, c -> ClipRow.of(c, ClipRow.EXTRA, i) })
}

/** A collection created from the app: listed straight away, without waiting for a sync. */
fun Library.addCollection(c: RgCollection) = synchronized(lock) {
    val list = _state.value.collections
    if (list.any { it.id == c.id }) return@synchronized
    saveCollections(list + c)
    _state.update { it.copy(collections = list + c) }
}

/**
 * Notes that a clip was added to one of your RedGifs collections, so it shows there before the
 * next sync confirms it.
 */
fun Library.noteInCollection(clipId: String, col: RgCollection) = synchronized(lock) {
    fun mark(c: Clip) = if (c.id == clipId && col.id !in c.colIds) c.copy(sources = c.sources + col.name, colIds = c.colIds + col.id) else c
    raw = raw.map(::mark)
    if (extras.any { it.id == clipId }) { extras = extras.map(::mark); saveExtras() }
    val cols = _state.value.collections.map { if (it.id == col.id) it.copy(count = it.count + 1) else it }
    saveCollections(cols)
    val clips = compose()
    _state.update { it.copy(clips = clips, collections = cols) }
}

internal fun Library.saveCollections(list: List<RgCollection>) = tx {
    store.clearCollections()
    store.putCollections(list.mapIndexed { i, c -> CollectionRow(c.id, i, c.name, c.count, c.thumb) })
}

internal fun Library.savePerformers(m: Map<String, Performer>) {
    if (m.isNotEmpty()) store.putPerformers(m.values.map(PerformerRow::of))
}
