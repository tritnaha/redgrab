package app.redgrab

import android.net.Uri
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

// Reading likes and collections from RedGifs, and the save folder's contents. Part of Library, kept in a file of its own.

/** Runs several writes as one all-or-nothing step. */
internal fun Library.tx(f: () -> Unit) = db.runInTransaction(Runnable { f() })

/** Applies info edits and inserts each original's copies right after it. */
internal fun Library.compose(): List<Clip> {
    val byOrig = copies.groupBy { it.originalId }
    val out = ArrayList<Clip>(raw.size + copies.size)
    val ids = HashSet<String>()
    val rawIds = raw.mapTo(HashSet()) { it.id }
    for (c in raw + extras.filter { it.id !in rawIds } + imports) {
        val o = overrides[c.id]
        val base = if (c.nicheIds.isEmpty()) c else c.copy(niches = c.nicheIds.mapNotNull { nicheNames[it] })
        val eff = if (o == null) base else base.copy(
            user = o.user ?: c.user, category = o.category ?: c.category, tags = o.tags ?: c.tags,
            niches = o.niches ?: base.niches, metaEdited = true)
        if (c.id !in hidden) out += eff
        ids += c.id
        byOrig[c.id]?.forEach { out += copyClip(it, eff) }
    }
    // Copies whose original is no longer liked are kept too (they're your files).
    copies.filter { it.originalId !in ids }.forEach { out += copyClip(it, null) }
    return out
}

/** Every niche name known to the app (RedGifs' plus ones you created), for the niche picker. */
fun Library.allNiches(): List<String> = synchronized(lock) {
    (nicheNames.values + overrides.values.flatMap { it.niches ?: emptyList() }).distinctBy { it.lowercase() }.sortedBy { it.lowercase() }
}

internal fun Library.copyClip(e: EditCopy, orig: Clip?) = Clip(
    id = e.id, hd = null, sd = null, thumb = orig?.thumb, duration = e.duration,
    width = orig?.width ?: 0, height = orig?.height ?: 0, hasAudio = e.hasAudio,
    user = orig?.user ?: "", created = e.created, sources = setOf("Edited"),
    tags = orig?.tags ?: emptyList(), category = orig?.category ?: "", niches = orig?.niches ?: emptyList(),
    copyOf = e.originalId, copyFile = e.file, copyNote = e.note,
)

/** Re-reads likes and collections from RedGifs and re-checks the folder. */
/** Syncs with RedGifs. Quick unless [full] is asked for or due (see [SyncPlan]). */
suspend fun Library.refresh(full: Boolean = false): Result<Unit> = refreshLock.withLock {
    // A session that has run out, or is about to, is renewed out of sight first.
    if (prefs.signedIn && Session.state(prefs).let { it == Session.State.Expired || it == Session.State.ExpiringSoon }) SessionRenewer.renew(ctx)
    val first = sync(full)
    // RedGifs turned the session down mid-way (it also does when the network changed): renew once and start over.
    if (first.exceptionOrNull() is AuthException && SessionRenewer.renew(ctx)) sync(full) else first
}

internal suspend fun Library.sync(asked: Boolean): Result<Unit> =
    withContext(Dispatchers.IO) {
        val token = prefs.token
        val ua = prefs.userAgent
        if (token == null || ua == null) return@withContext Result.failure(IllegalStateException("Sign in first."))
        _state.update { it.copy(refreshing = true, refreshMessage = "Reading likes…") }
        try {
            val api = RedGifsApi(token, ua)
            val merged = LinkedHashMap<String, Clip>()
            fun add(c: Clip) {
                val old = merged[c.id]
                merged[c.id] = if (old == null) c else old.copy(sources = old.sources + c.sources, colIds = old.colIds + c.colIds)
            }
            var likedTotal = 0
            val extras = FeedExtras()
            val before0 = synchronized(lock) { raw }
            val started = System.currentTimeMillis()
            // Everything, or only what is new? (See SyncPlan.)
            val full = SyncPlan.needsFull(asked, prefs.lastFullSync, started, before0.size)
            suspend fun allLikes() = api.likedClips(extras) { p, pages, total ->
                likedTotal = total
                status("Reading likes… page $p${if (pages > 0) " of $pages" else ""}")
            }
            var likesReadInFull = full
            val likes = if (full) allLikes() else {
                val known = before0.filter { it.liked }.map { it.copy(sources = setOf("Liked"), colIds = emptySet()) }
                val top = api.likedClips(extras, known.mapTo(HashSet()) { it.id }) { _, _, total -> likedTotal = total; status("Checking for new likes…") }
                // The site's count has to agree with what was found; if not (something was unliked), read them all.
                SyncPlan.mergeLikes(known, top, likedTotal, prefs.likedSiteTotal) ?: run { likesReadInFull = true; allLikes() }
            }
            likes.forEach(::add)
            val likedSiteTotal = likedTotal
            if (likedTotal == 0) likedTotal = merged.size

            var collectionsRead = 0
            var collectionsTotal = 0
            if (prefs.includeCollections) {
                val cols = api.myCollections()
                collectionsTotal = cols.size
                val counted = ArrayList<RgCollection>()
                val countsBefore = prefs.collectionCounts
                val countsNow = HashMap<String, Int>()
                for ((i, c) in cols.withIndex()) {
                    val members = before0.filter { c.id in it.colIds }
                    // A collection with the same number of clips as when it was last read is taken as it is.
                    val inIt = if (!full && SyncPlan.collectionUnchanged(c.count, countsBefore[c.id], members.size))
                        members.map { it.copy(sources = setOf(c.name), colIds = setOf(c.id)) }
                    else {
                        status("Reading collection ${i + 1} of ${cols.size}: ${c.name}")
                        collectionsRead++
                        api.collectionClips(c, extras)
                    }
                    countsNow[c.id] = c.count
                    inIt.forEach(::add)
                    counted += c.copy(count = inIt.size, thumb = c.thumb ?: inIt.firstOrNull()?.thumb)
                }
                prefs.collectionCounts = countsNow
                saveCollections(counted)
                _state.update { it.copy(collections = counted) }
            }
            AppLog.i((if (full) "Full sync" else "Quick sync") + ": likes " + (if (likesReadInFull) "read in full" else "checked from the top") +
                ", $collectionsRead of $collectionsTotal collection(s) read")

            // Niche names: from the responses, then look up the rest (once; they're kept).
            val names = HashMap(nicheNames).apply { putAll(extras.niches) }
            val unknown = merged.values.flatMap { it.nicheIds }.distinct().filter { it !in names }.take(300)
            for ((i, id) in unknown.withIndex()) {
                status("Reading niche names… ${i + 1} of ${unknown.size}")
                api.nicheName(id)?.let { names[id] = it }
                kotlinx.coroutines.delay(150)
            }
            // Merge into whatever is current (online browsing may have learned more meanwhile).
            val performers = synchronized(lock) {
                names.putAll(nicheNames.filterKeys { it !in names })
                store.putNiches(names.map { NicheRow(it.key, it.value) })
                savePerformers(extras.performers)
                _state.value.performers + extras.performers
            }

            // The folder is listed in a full pass; in between, the record of what the app saved is used.
            val scan = if (full) { status("Checking folder…"); scanFolderFull().also { prefs.lastFolderScan = System.currentTimeMillis() } }
                else _state.value.let { it.qualities to it.partial }
            val now = System.currentTimeMillis()
            if (full) prefs.lastFullSync = now
            if (likedSiteTotal > 0) prefs.likedSiteTotal = likedSiteTotal
            var savedCount = 0
            // Swap in the new list and read the download history in one step, so clips saved
            // by a download running during this sync aren't lost.
            synchronized(lock) {
                nicheNames = names
                val before = raw.mapTo(HashSet()) { it.id }
                raw = merged.values.toList()
                saveLibrary()
                // A Discover clip you've since liked is now a normal library clip.
                if (this@sync.extras.any { it.id in merged }) {
                    this@sync.extras = this@sync.extras.filter { it.id !in merged }
                    saveExtras()
                }
                prefs.lastSynced = now
                val qualities = mergeQ(readHistory(), scan.first)
                val after = merged.keys
                savedCount = after.count { !qualities[it].isNullOrEmpty() }
                // "New" = arrived in this sync. The very first sync doesn't count: everything would be new.
                val arrived = if (before.isEmpty()) emptySet() else after - before
                val unseen = (_state.value.unseen + arrived).filterTo(HashSet()) { it in after }
                tx { store.clearUnseen(); store.putUnseen(unseen.map(::UnseenRow)) }
                val record = SyncRecord(now, raw.size, (after - before).size, (before - after).size, savedCount)
                val history = (listOf(record) + _state.value.history).take(100)
                store.putSyncs(listOf(SyncRow(record.time, record.total, record.added, record.removed, record.saved)))
                store.trimSyncs()
                val clips = compose()
                _state.update {
                    it.copy(clips = clips, qualities = qualities, savedAt = readSavedAt(), refreshing = false, refreshMessage = "",
                        lastSynced = now, likedTotal = likedTotal, history = history, performers = performers, partial = scan.second,
                        unseen = unseen)
                }
            }
            AppLog.i("Refresh done: ${raw.size} clips ($likedTotal liked), $savedCount saved, ${raw.size - savedCount} new")
            Result.success(Unit)
        } catch (e: Exception) {
            AppLog.e("Refresh failed: ${e.message}", e.takeUnless { it is AuthException })
            _state.update { it.copy(refreshing = false, refreshMessage = "") }
            Result.failure(e)
        }
    }

internal fun Library.status(msg: String) = _state.update { it.copy(refreshMessage = msg) }

/**
 * Saved qualities found in the save folder: `<id>-hd.mp4`, `<id>-sd.mp4`, or legacy `<id>.mp4`.
 * Edited copies (`<id>-edit-N.mp4`) are tracked separately and ignored here. Removes stale `.part` files.
 */
fun Library.scanFolder(): Map<String, Set<Quality>> = scanFolderFull().first

/** Saved qualities plus IDs that have a `.part` file (an interrupted download to resume). */
fun Library.scanFolderFull(): Pair<Map<String, Set<Quality>>, Set<String>> {
    val out = HashMap<String, MutableSet<Quality>>()
    val parts = HashSet<String>()
    for (name in listNames()) {
        if (name.endsWith(".part")) {
            parts += name.removeSuffix(".part").removeSuffix("-hd").removeSuffix("-sd").lowercase()
            continue
        }
        if (!name.endsWith(".mp4", ignoreCase = true)) continue
        val base = name.dropLast(4).lowercase()
        if (Library.EDIT_RE.matches(base)) continue
        val (id, q) = when {
            base.endsWith("-hd") -> base.dropLast(3) to Quality.HD
            base.endsWith("-sd") -> base.dropLast(3) to Quality.SD
            else -> base to Quality.LEGACY
        }
        out.getOrPut(id) { mutableSetOf() } += q
    }
    return out to parts
}

/** File names in the save folder, read with one provider query (fast even with thousands of files). */
fun Library.listNames(): List<String> {
    val folder = prefs.folderUri ?: return emptyList()
    return runCatching {
        val parentId = if (DocumentsContract.isDocumentUri(ctx, folder)) DocumentsContract.getDocumentId(folder)
        else DocumentsContract.getTreeDocumentId(folder)
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(folder, parentId)
        val names = ArrayList<String>()
        ctx.contentResolver.query(children, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { c ->
            while (c.moveToNext()) c.getString(0)?.let(names::add)
        }
        names
    }.onFailure { AppLog.w("Could not list the save folder: ${it.message}") }.getOrDefault(emptyList())
}

/** Files in the save folder with size and date, read with one provider query. */
fun Library.listEntries(): List<Library.Entry> {
    val folder = prefs.folderUri ?: return emptyList()
    return runCatching {
        val parentId = if (DocumentsContract.isDocumentUri(ctx, folder)) DocumentsContract.getDocumentId(folder)
        else DocumentsContract.getTreeDocumentId(folder)
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(folder, parentId)
        val cols = arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_SIZE, DocumentsContract.Document.COLUMN_LAST_MODIFIED)
        val out = ArrayList<Library.Entry>()
        ctx.contentResolver.query(children, cols, null, null, null)?.use { c ->
            while (c.moveToNext()) out += Library.Entry(c.getString(0) ?: continue, c.getString(1), c.getLong(2), c.getLong(3))
        }
        out
    }.getOrDefault(emptyList())
}

/** Partly downloaded files currently in the save folder. */
fun Library.partials(): List<Library.Entry> = listEntries().filter { it.name.endsWith(".part") || it.name.endsWith(".new") }

/**
 * Removes partial downloads that would otherwise sit there forever: ones older than
 * [maxAgeDays] days, and ones whose clip is no longer in the library (so nothing would ever
 * resume them). With [all], removes every partial. Returns the bytes freed.
 */
fun Library.cleanPartials(maxAgeDays: Int = 7, all: Boolean = false): Long = synchronized(swapLock) {
    val folder = prefs.folderUri ?: return@synchronized 0L
    val resolver = ctx.contentResolver
    val known = synchronized(lock) { (raw + extras).mapTo(HashSet()) { it.id } }
    val cutoff = System.currentTimeMillis() - maxAgeDays * 86_400_000L
    // Never touch what a download is writing, or what a queued run is about to resume.
    val busy = DownloadWorker.writing
    val queued = if (DownloadWorker.active) readQueue().mapTo(HashSet()) { it.substringBefore(' ').lowercase() } else emptySet()
    var freed = 0L
    var n = 0
    val entries = listEntries()
    val names = entries.mapTo(HashSet()) { it.name }
    // An overwrite that was interrupted mid-swap leaves "<name>.old": put the original back
    // (or drop the backup if the new file is already in place).
    for (e in entries.filter { it.name.endsWith(".old") }) {
        val uri = DocumentsContract.buildDocumentUriUsingTree(folder, e.docId)
        val target = e.name.removeSuffix(".old")
        if (target in names) runCatching { DocumentsContract.deleteDocument(resolver, uri) }
        else runCatching { DocumentsContract.renameDocument(resolver, uri, target) }
            .onSuccess { AppLog.w("Put $target back after an interrupted overwrite") }
    }
    for (e in entries.filter { it.name.endsWith(".part") || it.name.endsWith(".new") }) {
        if (e.name == busy) continue
        val id = e.name.removeSuffix(".part").removeSuffix(".new").removeSuffix("-hd").removeSuffix("-sd").lowercase()
        val stale = all || ((e.modified in 1 until cutoff) && id !in queued) || (e.name.endsWith(".part") && id !in known)
        if (!stale) continue
        val uri = DocumentsContract.buildDocumentUriUsingTree(folder, e.docId)
        if (runCatching { DocumentsContract.deleteDocument(resolver, uri) }.getOrDefault(false)) { freed += e.size; n++ }
    }
    if (n > 0) {
        AppLog.i("Removed $n partial download(s), freed ${Format.size(freed)}")
        rescan()
    }
    freed
}

fun Library.rescan() {
    val (q, parts) = scanFolderFull()
    prefs.lastFolderScan = System.currentTimeMillis()
    synchronized(lock) { val merged = mergeQ(readHistory(), q); _state.update { it.copy(qualities = merged, partial = parts) } }
}

fun Library.setPartial(id: String, has: Boolean) = _state.update { it.copy(partial = if (has) it.partial + id else it.partial - id) }

/** Deletes what a download left unfinished for [id] and clears its "incomplete" mark (the clip is deleted on its site, or set aside). */
fun Library.dropPartial(id: String) {
    Quality.entries.forEach { q ->
        fileUri("$id${q.suffix}.part")?.takeIf(::exists)?.let { runCatching { android.provider.DocumentsContract.deleteDocument(ctx.contentResolver, it) } }
    }
    setPartial(id, false)
}

fun Library.folder(): DocumentFile? {
    val uri: Uri = prefs.folderUri ?: return null
    // The app's own storage is always there and writable; a chosen folder may have been removed or its permission withdrawn.
    if (PrivateDocs.isPrivate(uri)) { PrivateDocs.dir(ctx); return runCatching { DocumentFile.fromTreeUri(ctx, uri) }.getOrNull() }
    return runCatching { DocumentFile.fromTreeUri(ctx, uri) }.getOrNull()?.takeIf { it.exists() && it.canWrite() }
}

/** Content URI for a file in the save folder, built without listing the folder (fast). */
fun Library.fileUri(name: String): Uri? {
    // Names stay inside the save folder ("imported/<file>" is the only subfolder used).
    if (name.isEmpty() || name.startsWith("/") || '\\' in name || name.split('/').any { it == ".." || it == "." }) return null
    val folder = prefs.folderUri ?: return null
    return runCatching {
        val parentId = if (DocumentsContract.isDocumentUri(ctx, folder)) DocumentsContract.getDocumentId(folder)
        else DocumentsContract.getTreeDocumentId(folder)
        DocumentsContract.buildDocumentUriUsingTree(folder, "$parentId/$name")
    }.getOrNull()
}

fun Library.exists(uri: Uri): Boolean =
    runCatching { ctx.contentResolver.openFileDescriptor(uri, "r")?.close(); true }.getOrDefault(false)

/** Best saved file for a clip: the copy's own file, else HD, SD, legacy. Null if none exists. */
/**
 * What a moving preview should play: a file on the phone if the clip is saved (the SD file
 * when there is one, because several previews decode at once), otherwise the SD stream when
 * [allowStream] is on. Second value: true = local file.
 */
fun Library.previewSource(c: Clip, allowStream: Boolean, verify: Boolean = true): Pair<Uri, Boolean>? {
    // [verify] = false trusts the library's record of what is saved instead of asking the storage provider.
    fun there(u: Uri) = !verify || exists(u)
    c.copyFile?.let { f -> return fileUri(f)?.takeIf(::there)?.let { it to true } }
    val have = _state.value.qualities[c.id] ?: emptySet()
    for (q in listOf(Quality.SD, Quality.HD, Quality.LEGACY)) {
        if (q in have) fileUri(q.fileName(c.id))?.takeIf(::there)?.let { return it to true }
    }
    if (!allowStream) return null
    return (c.sd ?: c.hd)?.let { Uri.parse(it) to false }
}

fun Library.localFile(c: Clip): Pair<Uri, Quality?>? {
    c.copyFile?.let { f -> return fileUri(f)?.takeIf(::exists)?.let { it to null } }
    val have = _state.value.qualities[c.id] ?: return null
    for (q in listOf(Quality.HD, Quality.SD, Quality.LEGACY)) {
        if (q in have) fileUri(q.fileName(c.id))?.takeIf(::exists)?.let { return it to q }
    }
    return null
}
