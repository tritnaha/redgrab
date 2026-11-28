package app.redgrab

import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
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

internal fun Library.children(tree: Uri, parentDocId: String, strict: Boolean = false): List<Library.Doc> {
    val out = ArrayList<Library.Doc>()
    val cols = arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME,
        DocumentsContract.Document.COLUMN_LAST_MODIFIED, DocumentsContract.Document.COLUMN_MIME_TYPE)
    val cursor = ctx.contentResolver.query(DocumentsContract.buildChildDocumentsUriUsingTree(tree, parentDocId), cols, null, null, null)
    // No answer is not "empty": a move or export must not take a folder it couldn't read for one with nothing in it.
    if (cursor == null && strict) throw java.io.IOException("The folder couldn't be read")
    cursor?.use { c ->
        while (c.moveToNext()) {
            val name = c.getString(1) ?: continue
            // A name is one path element; anything else is not taken along.
            if (strict && (name.isEmpty() || name == "." || name == ".." || '/' in name || '\\' in name || '\u0000' in name)) continue
            out += Library.Doc(DocumentsContract.buildDocumentUriUsingTree(tree, c.getString(0)), name, c.getLong(2),
                c.getString(3) == DocumentsContract.Document.MIME_TYPE_DIR)
        }
    }
    return out
}

/**
 * The document a folder address stands for. A folder made with "New subfolder" is addressed as a
 * document inside the folder that was picked; taking the picked folder for it would reach far too much.
 */
internal fun folderDocId(uri: Uri): String = uri.pathSegments.let { p ->
    if (p.size >= 4 && p[0] == "tree" && p[2] == "document") p[3] else DocumentsContract.getTreeDocumentId(uri)
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
                resolver.openInputStream(doc.uri)!!.use { input -> resolver.openOutputStream(copy.uri, "wt")!!.use { input.copyTo(it) } }
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
    if (prefs.storageJob != null) throw java.io.IOException("Clips are being moved. Try again when that is done.")
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

/** What is in a folder, sub-folders included: (document, path below the folder). Fails rather than skip a folder it can't read. */
internal fun Library.walk(tree: Uri, skipDocId: String? = null): List<Pair<Library.Doc, String>> {
    val out = ArrayList<Pair<Library.Doc, String>>()
    val todo = ArrayDeque(listOf(folderDocId(tree) to ""))
    while (todo.isNotEmpty()) {
        val (id, path) = todo.removeFirst()
        for (d in children(tree, id, strict = true)) {
            val docId = DocumentsContract.getDocumentId(d.uri)
            if (docId == skipDocId) continue // an export folder inside the folder being walked
            if (d.isDir) todo += docId to "$path${d.name}/" else out += d to path + d.name
        }
    }
    return out
}

private fun sha256(input: java.io.InputStream, also: java.io.OutputStream? = null): String {
    val md = java.security.MessageDigest.getInstance("SHA-256")
    val buf = ByteArray(256 * 1024)
    while (true) { val n = input.read(buf); if (n < 0) break; md.update(buf, 0, n); also?.write(buf, 0, n) }
    return md.digest().joinToString("") { "%02x".format(it) }
}

/**
 * Moves everything in the save folder into the app's own storage, in one go. Each file is copied
 * and written through to storage, the copy is read back and compared with the original byte for
 * byte (by checksum), and only then is the original deleted. A file that is already inside with
 * the same content is not copied again, so an interrupted move carries on when started again; one
 * with the same name and other content is left where it is and counted as not moved.
 * Returns (moved, failed).
 */
suspend fun Library.moveIntoApp(onProgress: (String) -> Unit): Pair<Int, Int> {
    val from = prefs.folderUri ?: return 0 to 0
    if (PrivateDocs.isPrivate(from)) return 0 to 0 // already inside
    return moveTreeIntoApp(from, onProgress)
}

/** The move itself, from the folder [from]. */
internal suspend fun Library.moveTreeIntoApp(from: Uri, onProgress: (String) -> Unit): Pair<Int, Int> = withContext(Dispatchers.IO) {
    val resolver = ctx.contentResolver
    val dest = PrivateDocs.dir(ctx)
    val root = dest.canonicalPath + java.io.File.separator
    // Half-written copies of an earlier, interrupted run.
    dest.walkTopDown().filter { it.isFile && it.name.endsWith(".moving") }.toList().forEach { it.delete() }
    val files = walk(from).filter { (d, _) -> d.name != ".nomedia" }
    var moved = 0; var failed = 0
    files.forEachIndexed { i, (doc, path) ->
        onProgress("%,d of %,d".format(i + 1, files.size))
        kotlinx.coroutines.currentCoroutineContext().ensureActive()
        var tmp: java.io.File? = null
        try {
            val target = java.io.File(dest, path)
            // Checked before anything is created: nothing is ever made outside the private folder.
            if (!target.canonicalPath.startsWith(root)) error("the name leads outside the folder")
            target.parentFile?.mkdirs()
            if (target.exists()) {
                // Already inside (an earlier run)? Only if it is the same content; otherwise both are kept.
                val same = resolver.openInputStream(doc.uri)!!.use { sha256(it) } == target.inputStream().use { sha256(it) }
                if (!same) error("another file with this name is already inside; left in the folder")
            } else {
                val part = java.io.File(target.path + ".moving").also { tmp = it }
                val sum = resolver.openInputStream(doc.uri)!!.use { input ->
                    java.io.FileOutputStream(part).use { out -> sha256(input, out).also { out.flush(); out.fd.sync() } }
                }
                if (part.inputStream().use { sha256(it) } != sum) error("the copy doesn't match the original")
                if (!part.renameTo(target)) error("could not put the copy in place")
                tmp = null
                if (doc.modified > 0) target.setLastModified(doc.modified)
            }
            // The original goes only once a verified copy is in place.
            DocumentsContract.deleteDocument(resolver, doc.uri)
            moved++
        } catch (e: kotlinx.coroutines.CancellationException) {
            tmp?.delete(); throw e
        } catch (e: Exception) {
            tmp?.delete(); failed++
            AppLog.w("Not moved into the app: ${doc.name}: ${e.message}")
        }
    }
    AppLog.i("Moved into the app: $moved file(s), $failed failed")
    moved to failed
}

/** After a complete move: true when a fresh look at [from] finds no file left. Then its Gallery marker and empty sub-folders are removed. */
internal suspend fun Library.tidyMovedFolder(from: Uri): Boolean = withContext(Dispatchers.IO) {
    runCatching {
        if (walk(from).any { (d, _) -> d.name != ".nomedia" }) return@runCatching false
        val resolver = ctx.contentResolver
        for (d in children(from, folderDocId(from), strict = true)) {
            // A sub-folder goes only when listing it worked and showed nothing but the marker.
            if (d.isDir && children(from, DocumentsContract.getDocumentId(d.uri), strict = true).all { it.name == ".nomedia" }) runCatching { DocumentsContract.deleteDocument(resolver, d.uri) }
            else if (!d.isDir && d.name == ".nomedia") runCatching { DocumentsContract.deleteDocument(resolver, d.uri) }
        }
        true
    }.getOrDefault(false)
}

/**
 * Copies every finished file of the save folder into [to] as ordinary files (sub-folders kept), for
 * a backup or to take them elsewhere. Nothing is removed from the app. A ".nomedia" marker is put
 * in [to] first, so galleries don't pick the copies up. A file already there under the same name is
 * replaced only once the new copy is complete. Returns (copied, failed).
 */
suspend fun Library.exportTo(to: Uri, onProgress: (String) -> Unit): Pair<Int, Int> = withContext(Dispatchers.IO) {
    val from = prefs.folderUri ?: return@withContext 0 to 0
    val resolver = ctx.contentResolver
    val root = DocumentFile.fromTreeUri(ctx, to) ?: error("Can't write to that folder")
    val toId = runCatching { DocumentsContract.getDocumentId(root.uri) }.getOrNull()
    if (from.authority == to.authority && toId == folderDocId(from)) error("That is the save folder itself")
    val files = walk(from, skipDocId = toId.takeIf { from.authority == to.authority })
        .filter { (d, _) -> !d.name.endsWith(".part") && !d.name.endsWith(".new") && !d.name.endsWith(".moving") && !d.name.endsWith(".exporting") && d.name != ".nomedia" }
    if (root.findFile(".nomedia") == null) root.createFile("application/octet-stream", ".nomedia")
    var copied = 0; var failed = 0
    val dirs = HashMap<String, DocumentFile>()
    files.forEachIndexed { i, (doc, path) ->
        onProgress("%,d of %,d".format(i + 1, files.size))
        kotlinx.coroutines.currentCoroutineContext().ensureActive()
        var tmp: DocumentFile? = null
        try {
            val sub = path.substringBeforeLast('/', "")
            val dir = if (sub.isEmpty()) root else dirs.getOrPut(sub) {
                sub.split('/').fold(root) { d, name -> d.findFile(name)?.takeIf { it.isDirectory } ?: d.createDirectory(name) ?: error("Could not create $name") }
            }
            val there = dir.findFile(doc.name)
            if (there != null && there.isDirectory) error("a folder with this name is in the way")
            val sum = resolver.openInputStream(doc.uri)!!.use { sha256(it) }
            if (there != null && resolver.openInputStream(there.uri)!!.use { sha256(it) } == sum) return@forEachIndexed // already exported
            val out = (dir.createFile("application/octet-stream", doc.name + ".exporting") ?: error("Could not create ${doc.name}")).also { tmp = it }
            resolver.openInputStream(doc.uri)!!.use { input -> resolver.openOutputStream(out.uri, "wt")!!.use { input.copyTo(it, 256 * 1024) } }
            if (resolver.openInputStream(out.uri)!!.use { sha256(it) } != sum) error("the copy doesn't match")
            there?.delete()
            if (!out.renameTo(doc.name)) error("could not name the copy")
            tmp = null
            copied++
        } catch (e: kotlinx.coroutines.CancellationException) {
            runCatching { tmp?.delete() }; throw e
        } catch (e: Exception) {
            runCatching { tmp?.delete() }; failed++
            AppLog.w("Not exported: ${doc.name}: ${e.message}")
        }
    }
    AppLog.i("Exported $copied file(s), $failed failed")
    copied to failed
}

/**
 * After everything was copied out to [folder]: removes each file inside the app whose copy in
 * [folder] has the same content, and nothing else. Returns how many files are still inside
 * (something saved meanwhile, or a copy that doesn't match): those are kept.
 */
internal suspend fun Library.dropCopiesAlsoIn(folder: Uri): Int = withContext(Dispatchers.IO) {
    val dir = PrivateDocs.dir(ctx)
    val resolver = ctx.contentResolver
    val there = runCatching { walk(folder).associate { (d, path) -> path to d.uri } }.getOrDefault(emptyMap())
    var left = 0
    dir.walkTopDown().filter { it.isFile }.toList().forEach { f ->
        val path = f.relativeTo(dir).path
        val temp = f.name.endsWith(".moving") || f.name.endsWith(".exporting") || f.name == ".nomedia"
        val same = !temp && there[path]?.let { u -> runCatching { resolver.openInputStream(u)!!.use { sha256(it) } == f.inputStream().use { sha256(it) } }.getOrDefault(false) } == true
        if (temp || same) f.delete() else left++
    }
    // Sub-folders that are empty now.
    dir.walkBottomUp().filter { it.isDirectory && it != dir && it.list().isNullOrEmpty() }.toList().forEach { it.delete() }
    left
}

