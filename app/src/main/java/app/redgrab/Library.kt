package app.redgrab

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Quality of a saved file. LEGACY = `<id>.mp4` from older versions (quality not recorded). */
enum class Quality(val suffix: String, val label: String) {
    HD("-hd", "HD"), SD("-sd", "SD"), LEGACY("", "Saved");

    fun fileName(id: String) = "$id$suffix.mp4"
}

data class Clip(
    val id: String,
    val hd: String?,
    val sd: String?,
    val thumb: String?,
    val duration: Double,
    val width: Int,
    val height: Int,
    val hasAudio: Boolean,
    val user: String,
    val created: Long,
    /** "Liked" and/or collection names this clip came from. */
    val sources: Set<String>,
    /** Tags; RedGifs' uploader tags unless the user edited them. */
    val tags: List<String> = emptyList(),
    val views: Long = 0,
    val likes: Long = 0,
    /** User-set category (RedGifs has none of its own). */
    val category: String = "",
    /** True when performer/tags/category were edited in the app. */
    val metaEdited: Boolean = false,
    /** For edited copies: the original clip's ID. Null for RedGifs clips. */
    val copyOf: String? = null,
    /** For edited copies: file name in the save folder and what was done. */
    val copyFile: String? = null,
    val copyNote: String = "",
    /** RedGifs niches (communities) the clip is in: IDs as stored, names resolved for display. */
    val nicheIds: List<String> = emptyList(),
    val niches: List<String> = emptyList(),
    /** RedGifs audience labels, e.g. "straight", "gay", "lesbian", "trans", "bisexual". */
    val sexuality: List<String> = emptyList(),
    /** Uploaded by a verified creator. */
    val verified: Boolean = false,
    /** The uploader's caption. */
    val description: String = "",
    /** IDs of your RedGifs collections this clip is in. */
    val colIds: Set<String> = emptySet(),
) {
    val isVertical get() = height > width
    fun url(preferHd: Boolean): String? = if (preferHd) hd ?: sd else sd ?: hd
    fun url(q: Quality): String? = when (q) { Quality.HD -> hd; Quality.SD -> sd; Quality.LEGACY -> hd ?: sd }
    val isCopy get() = copyOf != null
    /** A file kept in the save folder's "imported" subfolder: one you imported, or a picture saved from online. */
    val imported get() = copyOf == null && copyFile != null
    /**
     * File type, lower case: of the file on the phone, else of the online address. Reddit's MP4
     * copies of GIFs keep ".gif" in their address (with "format=mp4"), so those count as MP4.
     */
    val fileExt: String by lazy {
        copyFile?.substringAfterLast('.', "")?.lowercase()
            ?: hd?.takeIf { "format=mp4" !in it }?.substringBefore('?')?.substringAfterLast('/')?.substringAfterLast('.', "")?.lowercase()
            ?: ""
    }
    /** A picture (GIF, WebP...) rather than a video; shown with the image viewer. Online GIFs count too. */
    val isImage get() = fileExt in IMAGE_EXT
    /** In your RedGifs likes. */
    val liked get() = "Liked" in sources

    fun toJson() = JSONObject().apply {
        put("id", id); put("hd", hd); put("sd", sd); put("thumb", thumb)
        put("duration", duration); put("w", width); put("h", height); put("audio", hasAudio)
        put("user", user); put("created", created); put("sources", JSONArray(sources.toList()))
        put("tags", JSONArray(tags)); put("views", views); put("likes", likes)
        put("niches", JSONArray(nicheIds)); put("sexuality", JSONArray(sexuality)); put("verified", verified); put("desc", description)
        put("cols", JSONArray(colIds.toList()))
    }

    /** Lower-cased text searched by the search box. */
    val searchText: String by lazy {
        (listOf(id, user, category, copyNote, description) + tags + sources + niches + sexuality).joinToString(" ").lowercase()
    }

    companion object {
        private val ID_RE = Regex("[a-z0-9_-]{1,80}")
        /** IDs are used in file names: letters, digits, dash and underscore only. */
        fun validId(id: String) = ID_RE.matches(id)
        val IMAGE_EXT = setOf("gif", "webp", "png", "jpg", "jpeg")
        val VIDEO_EXT = setOf("mp4", "webm", "m4v", "mov", "mkv")
        fun fromJson(j: JSONObject) = Clip(
            id = j.getString("id"),
            hd = j.optString("hd").takeIf { it.startsWith("http") },
            sd = j.optString("sd").takeIf { it.startsWith("http") },
            thumb = j.optString("thumb").takeIf { it.startsWith("http") },
            duration = j.optDouble("duration", 0.0),
            width = j.optInt("w"), height = j.optInt("h"),
            hasAudio = j.optBoolean("audio"),
            user = j.optString("user"),
            created = j.optLong("created"),
            sources = j.optJSONArray("sources")?.strings()?.toSet() ?: emptySet(),
            tags = j.optJSONArray("tags")?.strings() ?: emptyList(),
            views = j.optLong("views"),
            likes = j.optLong("likes"),
            nicheIds = j.optJSONArray("niches")?.strings() ?: emptyList(),
            sexuality = j.optJSONArray("sexuality")?.strings() ?: emptyList(),
            verified = j.optBoolean("verified"),
            description = j.optString("desc"),
            colIds = j.optJSONArray("cols")?.strings()?.toSet() ?: emptySet(),
        )
    }
}

fun JSONArray.strings(): List<String> = (0 until length()).map { optString(it) }.filter { it.isNotBlank() }

/** User edits to a clip's info. Null fields = keep RedGifs' value. */
data class MetaOverride(val user: String? = null, val category: String? = null, val tags: List<String>? = null,
                        val niches: List<String>? = null) {
    fun toJson() = JSONObject().apply {
        user?.let { put("user", it) }; category?.let { put("category", it) }; tags?.let { put("tags", JSONArray(it)) }
        niches?.let { put("niches", JSONArray(it)) }
    }
    companion object {
        fun fromJson(j: JSONObject) = MetaOverride(
            user = if (j.has("user")) j.getString("user") else null,
            category = if (j.has("category")) j.getString("category") else null,
            tags = j.optJSONArray("tags")?.strings(),
            niches = j.optJSONArray("niches")?.strings(),
        )
    }
}

/** An edited copy made in the app, linked to its original. */
data class EditCopy(val id: String, val originalId: String, val file: String, val note: String, val created: Long, val duration: Double, val hasAudio: Boolean) {
    fun toJson() = JSONObject().put("id", id).put("orig", originalId).put("file", file).put("note", note)
        .put("created", created).put("duration", duration).put("audio", hasAudio)
    companion object {
        fun fromJson(j: JSONObject) = EditCopy(j.getString("id"), j.getString("orig"), j.getString("file"), j.optString("note"),
            j.optLong("created"), j.optDouble("duration", 0.0), j.optBoolean("audio"))
    }
}

/** Live progress of a download run. */
/**
 * A download that didn't work, kept until it succeeds or you delete the clip.
 * [attempts] counts whole runs (each run already retries three times); [first] is when it first failed.
 */
data class Failure(val reason: String, val first: Long, val last: Long, val attempts: Int,
                   /** The site says the clip no longer exists: never retried automatically and not counted as a failed download. */
                   val dead: Boolean = false) {
    /** After this many failed runs, or this long, automatic retries stop; you can still retry by hand. A dead clip is never retried. */
    val gaveUp get() = dead || attempts >= MAX_ATTEMPTS || last - first > MAX_AGE_MS
    fun toJson() = JSONObject().put("reason", reason).put("first", first).put("last", last).put("attempts", attempts)
    companion object {
        const val MAX_ATTEMPTS = 5
        const val MAX_AGE_MS = 7 * 86_400_000L
        fun fromJson(j: JSONObject) = Failure(j.optString("reason"), j.optLong("first"), j.optLong("last"), j.optInt("attempts", 1))
    }
}

data class DownloadProgress(
    /** Waiting for a connection; the run continues by itself when one is back. */
    val waiting: Boolean = false,
    /** Stopped by you (or interrupted) with [left] clips still to do; "Resume" continues. */
    val paused: Boolean = false,
    val left: Int = 0,
    val running: Boolean = false,
    val queued: Int = 0,
    val done: Int = 0,
    val failed: Int = 0,
    val currentId: String? = null,
    /** Bytes of the clip being saved that are on the phone, and its full size (-1 = the server didn't say). */
    val currentBytes: Long = 0,
    val currentTotal: Long = -1,
    val bytesPerSec: Long = 0,
    val etaSec: Long = -1,
    val message: String = "",
)

/** One finished sync, kept for the history list. */
data class SyncRecord(val time: Long, val total: Int, val added: Int, val removed: Int, val saved: Int) {
    fun toJson() = JSONObject().put("t", time).put("total", total).put("added", added).put("removed", removed).put("saved", saved)
    companion object {
        fun fromJson(j: JSONObject) = SyncRecord(j.getLong("t"), j.optInt("total"), j.optInt("added"), j.optInt("removed"), j.optInt("saved"))
    }
}

data class LibraryState(
    /** RedGifs clips (with the user's info edits applied), each followed by its edited copies. */
    val clips: List<Clip> = emptyList(),
    /** Saved qualities per clip ID (from the folder plus download history). */
    val qualities: Map<String, Set<Quality>> = emptyMap(),
    /** Downloads that failed, by clip ID, with the reason. Kept across restarts. */
    val failures: Map<String, Failure> = emptyMap(),
    val refreshing: Boolean = false,
    val refreshMessage: String = "",
    val lastSynced: Long = 0,
    val likedTotal: Int = 0,
    val history: List<SyncRecord> = emptyList(),
    /** Creator profiles keyed by lower-case username. */
    val performers: Map<String, Performer> = emptyMap(),
    /** Clip IDs hidden by the user (deleted and not to be downloaded again). */
    val hidden: Set<String> = emptySet(),
    /** Clip IDs with a partly downloaded `.part` file waiting to be resumed. */
    val partial: Set<String> = emptySet(),
    /** Your RedGifs collections (cached, so they're listed offline too). */
    val collections: List<RgCollection> = emptyList(),
    /** What auto-sync watches: collection IDs, plus [Library.WATCH_LIKES] for your likes. */
    val watched: Set<String> = emptySet(),
    /** Clip IDs with a thumbnail stored on the phone (works offline). */
    val localThumbs: Set<String> = emptySet(),
    /** Clip IDs with a small preview copy on the phone (see [PreviewMaker]). */
    val localPreviews: Set<String> = emptySet(),
    /** Files that are byte-for-byte identical to another saved file: file name → the first file with that content. */
    val duplicates: Map<String, String> = emptyMap(),
    /** Clips that arrived in a sync and haven't been opened yet. */
    val unseen: Set<String> = emptySet(),
) {
    /** Downloads that failed and may still work. */
    val failed: Set<String> by lazy { failures.filterValues { !it.dead }.keys }
    /** Clips the site no longer has (deleted there): listed, but never downloaded again. */
    val dead: Set<String> by lazy { failures.filterValues { it.dead }.keys }

    /** IDs with at least one saved file. Edited copies always count as saved. */
    val saved: Set<String> by lazy { qualities.filterValues { it.isNotEmpty() }.keys + clips.filter { it.isCopy || it.imported }.map { it.id } }
    val originals: List<Clip> by lazy { clips.filter { !it.isCopy } }
    val newCount get() = originals.count { it.id !in saved }
    val savedCount get() = originals.count { it.id in saved }
}

/**
 * Single source of truth for clips, saved files, edits and history. Everything is stored in
 * the app's private storage: the database redgrab.db (see Db.kt) and, as image files, the
 * offline thumbnails (files/thumbs). The lists are read into memory once at start; every change
 * updates memory and writes just the affected rows.
 */
class Library(private val ctx: Context) {
    val prefs = Prefs(ctx)
    private val db = AppDb.open(ctx)
    private val store = db.store()
    private val thumbDir = File(ctx.filesDir, "thumbs")
    private val previewDir = File(ctx.filesDir, "previews")
    /** Saved file name → (size in bytes, SHA-256), recorded when a download finishes. */
    private var fileIndex: Map<String, Pair<Long, String>> = emptyMap()
    private val refreshLock = Mutex()

    /** Guards raw/extras/overrides/copies/hidden and the history file: they're touched from UI, sync and the download worker. */
    private val lock = Any()

    /** Clips exactly as RedGifs returned them (no user edits). */
    private var raw: List<Clip> = emptyList()
    /** Clips saved from online browsing that aren't in your likes/collections. */
    private var extras: List<Clip> = emptyList()
    /** Files you imported from a folder on the phone. */
    private var imports: List<Clip> = emptyList()
    private var overrides: Map<String, MetaOverride> = emptyMap()
    private var copies: List<EditCopy> = emptyList()
    private var hidden: Set<String> = emptySet()
    private var nicheNames: Map<String, String> = emptyMap()

    private val _state = MutableStateFlow(LibraryState())
    val state: StateFlow<LibraryState> = _state

    /** Download progress lives in its own stream so frequent updates don't recompute the library. */
    private val _download = MutableStateFlow(DownloadProgress())
    val download: StateFlow<DownloadProgress> = _download

    /** Runs several writes as one all-or-nothing step. */
    private fun tx(f: () -> Unit) = db.runInTransaction(Runnable { f() })

    init {
        importLegacyFiles()
        try {
            raw = store.clips(ClipRow.LIBRARY).map { it.toClip() }
            extras = store.clips(ClipRow.EXTRA).map { it.toClip() }
            imports = store.clips(ClipRow.IMPORTED).map { it.toClip() }
            extras.forEach { if (OnlineSource.of(it.id) == OnlineSource.FikFap) SourceHeaders.learnFikFap(it.hd) }
            overrides = store.overrides().associate { it.id to it.toOverride() }
            copies = store.copies().map { it.toCopy() }
            hidden = store.hidden().toSet()
            nicheNames = store.niches().associate { it.id to it.name }
            fileIndex = store.files().associate { it.name to (it.size to it.sha) }
            _state.value = LibraryState(
                clips = compose(), qualities = readHistory(), lastSynced = prefs.lastSynced,
                failures = store.failures().associate { it.id to Failure(it.reason, it.firstAt, it.lastAt, it.attempts, it.dead) },
                history = store.syncs().map { SyncRecord(it.time, it.total, it.added, it.removed, it.saved) },
                performers = store.performers().associate { it.key to it.toPerformer() },
                hidden = hidden,
                collections = store.collections().map { RgCollection(it.id, it.name, it.count, it.thumb) },
                watched = store.watched().toSet(),
                localThumbs = thumbDir.list()?.mapTo(HashSet()) { it.removeSuffix(".jpg") } ?: emptySet(),
                localPreviews = previewDir.list()?.filter { it.endsWith(".mp4") }?.mapTo(HashSet()) { it.removeSuffix(".mp4") } ?: emptySet(),
                duplicates = findDuplicates(),
                unseen = store.unseen().toSet(),
            )
            // A queue left over from a stopped or interrupted run: offer to resume it.
            readQueue().size.let { n -> if (n > 0) _download.value = DownloadProgress(paused = true, left = n, message = "$n clip(s) waiting from last time.") }
        } catch (e: Exception) {
            AppLog.e("Could not read the library database", e)
        }
    }

    /** Applies info edits and inserts each original's copies right after it. */
    private fun compose(): List<Clip> {
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
    fun allNiches(): List<String> = synchronized(lock) {
        (nicheNames.values + overrides.values.flatMap { it.niches ?: emptyList() }).distinctBy { it.lowercase() }.sortedBy { it.lowercase() }
    }

    private fun copyClip(e: EditCopy, orig: Clip?) = Clip(
        id = e.id, hd = null, sd = null, thumb = orig?.thumb, duration = e.duration,
        width = orig?.width ?: 0, height = orig?.height ?: 0, hasAudio = e.hasAudio,
        user = orig?.user ?: "", created = e.created, sources = setOf("Edited"),
        tags = orig?.tags ?: emptyList(), category = orig?.category ?: "", niches = orig?.niches ?: emptyList(),
        copyOf = e.originalId, copyFile = e.file, copyNote = e.note,
    )

    private fun recompose() { val clips = synchronized(lock) { compose() }; _state.update { it.copy(clips = clips) } }

    // ---- refresh -------------------------------------------------------------

    /** Re-reads likes and collections from RedGifs and re-checks the folder. */
    suspend fun refresh(): Result<Unit> = refreshLock.withLock {
        // A session that has run out, or is about to, is renewed out of sight first.
        if (prefs.signedIn && Session.state(prefs).let { it == Session.State.Expired || it == Session.State.ExpiringSoon }) SessionRenewer.renew(ctx)
        val first = sync()
        // RedGifs turned the session down mid-way (it also does when the network changed): renew once and start over.
        if (first.exceptionOrNull() is AuthException && SessionRenewer.renew(ctx)) sync() else first
    }

    private suspend fun sync(): Result<Unit> =
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
                api.likedClips(extras) { p, pages, total ->
                    likedTotal = total
                    status("Reading likes… page $p${if (pages > 0) " of $pages" else ""}")
                }.forEach(::add)
                if (likedTotal == 0) likedTotal = merged.size

                if (prefs.includeCollections) {
                    val cols = api.myCollections()
                    val counted = ArrayList<RgCollection>()
                    for ((i, c) in cols.withIndex()) {
                        status("Reading collection ${i + 1} of ${cols.size}: ${c.name}")
                        val inIt = api.collectionClips(c, extras)
                        inIt.forEach(::add)
                        counted += c.copy(count = inIt.size, thumb = c.thumb ?: inIt.firstOrNull()?.thumb)
                    }
                    saveCollections(counted)
                    _state.update { it.copy(collections = counted) }
                    AppLog.i("Collections: ${cols.size}")
                }

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

                status("Checking folder…")
                val scan = scanFolderFull()
                val now = System.currentTimeMillis()
                var savedCount = 0
                // Swap in the new list and read the download history in one step, so clips saved
                // by a download running during this sync aren't lost.
                synchronized(lock) {
                    nicheNames = names
                    val before = raw.mapTo(HashSet()) { it.id }
                    raw = merged.values.toList()
                    saveLibrary()
                    // A Discover clip you've since liked is now a normal library clip.
                    if (this@Library.extras.any { it.id in merged }) {
                        this@Library.extras = this@Library.extras.filter { it.id !in merged }
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
                        it.copy(clips = clips, qualities = qualities, refreshing = false, refreshMessage = "",
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

    private fun status(msg: String) = _state.update { it.copy(refreshMessage = msg) }

    /**
     * Saved qualities found in the save folder: `<id>-hd.mp4`, `<id>-sd.mp4`, or legacy `<id>.mp4`.
     * Edited copies (`<id>-edit-N.mp4`) are tracked separately and ignored here. Removes stale `.part` files.
     */
    fun scanFolder(): Map<String, Set<Quality>> = scanFolderFull().first

    /** Saved qualities plus IDs that have a `.part` file (an interrupted download to resume). */
    fun scanFolderFull(): Pair<Map<String, Set<Quality>>, Set<String>> {
        val out = HashMap<String, MutableSet<Quality>>()
        val parts = HashSet<String>()
        for (name in listNames()) {
            if (name.endsWith(".part")) {
                parts += name.removeSuffix(".part").removeSuffix("-hd").removeSuffix("-sd").lowercase()
                continue
            }
            if (!name.endsWith(".mp4", ignoreCase = true)) continue
            val base = name.dropLast(4).lowercase()
            if (EDIT_RE.matches(base)) continue
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
    fun listNames(): List<String> {
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

    /** One file in the save folder. */
    data class Entry(val name: String, val docId: String, val size: Long, val modified: Long)

    /** Files in the save folder with size and date, read with one provider query. */
    fun listEntries(): List<Entry> {
        val folder = prefs.folderUri ?: return emptyList()
        return runCatching {
            val parentId = if (DocumentsContract.isDocumentUri(ctx, folder)) DocumentsContract.getDocumentId(folder)
            else DocumentsContract.getTreeDocumentId(folder)
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(folder, parentId)
            val cols = arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_SIZE, DocumentsContract.Document.COLUMN_LAST_MODIFIED)
            val out = ArrayList<Entry>()
            ctx.contentResolver.query(children, cols, null, null, null)?.use { c ->
                while (c.moveToNext()) out += Entry(c.getString(0) ?: continue, c.getString(1), c.getLong(2), c.getLong(3))
            }
            out
        }.getOrDefault(emptyList())
    }

    /** Partly downloaded files currently in the save folder. */
    fun partials(): List<Entry> = listEntries().filter { it.name.endsWith(".part") || it.name.endsWith(".new") }

    /**
     * Removes partial downloads that would otherwise sit there forever: ones older than
     * [maxAgeDays] days, and ones whose clip is no longer in the library (so nothing would ever
     * resume them). With [all], removes every partial. Returns the bytes freed.
     */
    fun cleanPartials(maxAgeDays: Int = 7, all: Boolean = false): Long = synchronized(swapLock) {
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

    fun rescan() {
        val (q, parts) = scanFolderFull()
        synchronized(lock) { val merged = mergeQ(readHistory(), q); _state.update { it.copy(qualities = merged, partial = parts) } }
    }

    fun setPartial(id: String, has: Boolean) = _state.update { it.copy(partial = if (has) it.partial + id else it.partial - id) }

    fun folder(): DocumentFile? {
        val uri: Uri = prefs.folderUri ?: return null
        return runCatching { DocumentFile.fromTreeUri(ctx, uri) }.getOrNull()?.takeIf { it.exists() && it.canWrite() }
    }

    /** Content URI for a file in the save folder, built without listing the folder (fast). */
    fun fileUri(name: String): Uri? {
        // Names stay inside the save folder ("imported/<file>" is the only subfolder used).
        if (name.isEmpty() || name.startsWith("/") || '\\' in name || name.split('/').any { it == ".." || it == "." }) return null
        val folder = prefs.folderUri ?: return null
        return runCatching {
            val parentId = if (DocumentsContract.isDocumentUri(ctx, folder)) DocumentsContract.getDocumentId(folder)
            else DocumentsContract.getTreeDocumentId(folder)
            DocumentsContract.buildDocumentUriUsingTree(folder, "$parentId/$name")
        }.getOrNull()
    }

    fun exists(uri: Uri): Boolean =
        runCatching { ctx.contentResolver.openFileDescriptor(uri, "r")?.close(); true }.getOrDefault(false)

    /** Best saved file for a clip: the copy's own file, else HD, SD, legacy. Null if none exists. */
    /**
     * What a moving preview should play: a file on the phone if the clip is saved (the SD file
     * when there is one, because several previews decode at once), otherwise the SD stream when
     * [allowStream] is on. Second value: true = local file.
     */
    fun previewSource(c: Clip, allowStream: Boolean, verify: Boolean = true): Pair<Uri, Boolean>? {
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

    fun localFile(c: Clip): Pair<Uri, Quality?>? {
        c.copyFile?.let { f -> return fileUri(f)?.takeIf(::exists)?.let { it to null } }
        val have = _state.value.qualities[c.id] ?: return null
        for (q in listOf(Quality.HD, Quality.SD, Quality.LEGACY)) {
            if (q in have) fileUri(q.fileName(c.id))?.takeIf(::exists)?.let { return it to q }
        }
        return null
    }

    // ---- saved / history ------------------------------------------------------

    fun markSaved(id: String, q: Quality) = synchronized(lock) {
        tx {
            store.putSaved(listOf(SavedRow(id, q.name, System.currentTimeMillis())))
            store.deleteFailures(listOf(id))
        }
        _state.update { s ->
            s.copy(qualities = s.qualities + (id to ((s.qualities[id] ?: emptySet()) + q)), failures = s.failures - id)
        }
    }

    /** Records a failed download with a plain-language reason. */
    fun markFailed(id: String, reason: String, dead: Boolean = false) = synchronized(lock) {
        val now = System.currentTimeMillis()
        val old = _state.value.failures[id]
        val f = Failure(reason, old?.first ?: now, now, (old?.attempts ?: 0) + 1, dead)
        store.putFailures(listOf(FailureRow(id, f.reason, f.first, f.last, f.attempts, f.dead)))
        _state.update { it.copy(failures = it.failures + (id to f)) }
    }

    /** Forgets failures (e.g. before a manual retry, so the clip gets a fresh set of attempts). */
    fun clearFailures(ids: kotlin.collections.Collection<String>) = synchronized(lock) {
        if (ids.none { it in _state.value.failures }) return@synchronized
        AppDb.sliced(ids.toSet()) { store.deleteFailures(it) }
        _state.update { it.copy(failures = it.failures - ids.toSet()) }
    }

    fun forgetHistory() {
        val scan = scanFolder()
        synchronized(lock) { store.clearSaved(); _state.update { it.copy(qualities = scan) } }
        AppLog.i("Download history cleared")
    }

    private fun readHistory(): Map<String, Set<Quality>> {
        val out = HashMap<String, MutableSet<Quality>>()
        store.saved().forEach { r ->
            out.getOrPut(r.id) { mutableSetOf() } += runCatching { Quality.valueOf(r.quality) }.getOrDefault(Quality.LEGACY)
        }
        return out
    }

    private fun mergeQ(a: Map<String, Set<Quality>>, b: Map<String, Set<Quality>>): Map<String, Set<Quality>> {
        val out = HashMap<String, Set<Quality>>(a)
        b.forEach { (k, v) -> out[k] = (out[k] ?: emptySet()) + v }
        return out
    }

    // ---- deleting & hiding ---------------------------------------------------------

    /**
     * Deletes the saved files of [originals] (every quality and any partial download) and the
     * chosen edited [copyIds]. With [hide], the originals are also hidden from the library so
     * "Save all" won't download them again. Returns how many files were removed.
     */
    fun delete(originals: kotlin.collections.Collection<String>, copyIds: kotlin.collections.Collection<String>, hide: Boolean): Int {
        var removed = 0
        fun rm(name: String) {
            val uri = fileUri(name) ?: return
            if (exists(uri) && runCatching { DocumentsContract.deleteDocument(ctx.contentResolver, uri) }.getOrDefault(false)) removed++
        }
        val origSet = originals.toSet()
        for (id in origSet) {
            Quality.entries.forEach { q -> rm(q.fileName(id)); rm("$id${q.suffix}.part") }
        }
        val gone: List<EditCopy>
        var goneImports: List<Clip> = emptyList()
        synchronized(lock) {
            gone = copies.filter { it.id in copyIds }
            val hadExtras = !hide && extras.any { it.id in origSet }
            copies = copies - gone.toSet()
            goneImports = imports.filter { it.id in origSet }
            if (goneImports.isNotEmpty()) imports = imports - goneImports.toSet()
            // Imported files are simply removed; "hide" only means something for RedGifs clips.
            if (hide) hidden = hidden + (origSet - goneImports.mapTo(HashSet()) { it.id })
            // A Discover clip that's deleted (not hidden) has no reason to stay listed.
            if (hadExtras) extras = extras.filter { it.id !in origSet }
            val goneIds = gone.map { it.id }
            tx {
                store.deleteCopies(goneIds)
                // Forget download history for deleted originals so their status is accurate.
                AppDb.sliced(origSet) { store.deleteSaved(it) }
                if (hide) store.putHidden(hidden.map(::HiddenRow))
                if (hadExtras) saveExtras()
                AppDb.sliced(goneImports.map { it.id }) { store.deleteClips(it) }
                AppDb.sliced(origSet) { store.deleteUnseen(it) }
            }
            val clips = compose()
            _state.update { s ->
                s.copy(clips = clips, qualities = s.qualities - origSet, partial = s.partial - origSet, hidden = hidden, unseen = s.unseen - origSet)
            }
        }
        gone.forEach { rm(it.file) }
        goneImports.forEach { c -> c.copyFile?.let(::rm) }
        dropThumbs(origSet + gone.map { it.id })
        clearFailures(origSet)
        synchronized(lock) {
            forgetFiles(origSet.flatMap { id -> Quality.entries.map { it.fileName(id) } } + gone.map { it.file })
            _state.update { it.copy(duplicates = findDuplicates()) }
        }
        AppLog.i("Deleted ${origSet.size} clip(s) and ${gone.size} edited copy(ies): $removed file(s)${if (hide) "; hidden from library" else ""}")
        return removed
    }

    fun unhide(ids: kotlin.collections.Collection<String>) = synchronized(lock) {
        hidden = hidden - ids.toSet()
        AppDb.sliced(ids.toSet()) { store.deleteHidden(it) }
        val clips = compose()
        _state.update { it.copy(clips = clips, hidden = hidden) }
    }

    /** Hidden clips as RedGifs clips (for the "Hidden clips" list in Settings). */
    fun hiddenClips(): List<Clip> = synchronized(lock) { (raw + extras).filter { it.id in hidden } }

    /** Edited copies made from any of [originalIds]. */
    fun copiesOf(originalIds: kotlin.collections.Collection<String>): List<Clip> {
        val set = originalIds.toSet()
        return _state.value.clips.filter { it.copyOf in set }
    }

    // ---- niche names & profiles learned while browsing online -------------------------

    fun learn(extras: FeedExtras) {
        if (extras.niches.isNotEmpty()) learnNiches(extras.niches)
        if (extras.performers.isNotEmpty()) synchronized(lock) {
            val merged = _state.value.performers + extras.performers
            _state.update { it.copy(performers = merged) }
            runCatching { savePerformers(extras.performers) }
        }
    }

    fun learnNiches(names: Map<String, String>) = synchronized(lock) {
        if (names.all { nicheNames[it.key] == it.value }) return@synchronized
        nicheNames = nicheNames + names
        store.putNiches(names.map { NicheRow(it.key, it.value) })
    }

    /** RedGifs niche ID for a niche name, or null if it's one you made up yourself. */
    fun nicheId(name: String): String? = synchronized(lock) { nicheNames.entries.firstOrNull { it.value.equals(name, true) }?.key }

    /** Fills in niche names for a clip from online browsing. */
    fun resolveNiches(c: Clip): Clip = synchronized(lock) {
        if (c.nicheIds.isEmpty()) c else c.copy(niches = c.nicheIds.mapNotNull { nicheNames[it] })
    }

    // ---- clips saved from online browsing ------------------------------------------

    /** Adds a clip found online (not in your likes) to the library so it can be saved and kept. */
    fun addExtra(c: Clip) = synchronized(lock) {
        // Saving a clip you once hidden means you want it back.
        val wasHidden = c.id in hidden
        if (wasHidden) {
            hidden = hidden - c.id
            store.deleteHidden(listOf(c.id))
        }
        val known = raw.any { it.id == c.id } || extras.any { it.id == c.id }
        if (known && !wasHidden) return@synchronized
        if (!known) {
            // Keeps where it was found ("Reddit", "FikFap"); RedGifs clips are "Discover".
            extras = extras + c.copy(sources = c.sources.filterTo(LinkedHashSet()) { it in OnlineSource.SAVED_LABELS }.ifEmpty { setOf("Discover") })
            store.putClips(listOf(ClipRow.of(extras.last(), ClipRow.EXTRA, (store.clips(ClipRow.EXTRA).lastOrNull()?.pos ?: -1) + 1)))
        }
        val clips = compose()
        _state.update { it.copy(clips = clips, hidden = hidden) }
    }

    // ---- info edits ---------------------------------------------------------------

    fun setOverrides(changes: Map<String, MetaOverride?>) = synchronized(lock) {
        val m = overrides.toMutableMap()
        changes.forEach { (id, o) -> if (o == null) m.remove(id) else m[id] = o }
        overrides = m
        val removed = changes.filterValues { it == null }.keys
        val changed = changes.mapNotNull { (id, o) -> o?.let { OverrideRow.of(id, it) } }
        tx {
            AppDb.sliced(removed) { store.deleteOverrides(it) }
            if (changed.isNotEmpty()) store.putOverrides(changed)
        }
        val clips = compose()
        _state.update { it.copy(clips = clips) }
        AppLog.i("Edited info for ${changes.size} clip(s)")
    }

    fun override(id: String): MetaOverride? = synchronized(lock) { overrides[id] }

    /** Puts a clip in one of your categories, keeping its other edits. */
    fun setCategory(id: String, category: String) = setOverrides(mapOf(id to (override(id) ?: MetaOverride()).copy(category = category.trim())))

    // ---- edited copies -------------------------------------------------------------

    /** Next free `<id>-edit-N.mp4` name for an original. */
    fun nextCopyName(originalId: String): Pair<String, String> = synchronized(lock) {
        val used = copies.filter { it.originalId == originalId }.map { it.id }.toSet()
        var n = 1
        while ("$originalId-edit-$n" in used) n++
        val id = "$originalId-edit-$n"
        id to "$id.mp4"
    }

    /** Held while a file is swapped for its edited version, so housekeeping doesn't interfere. */
    val swapLock = Any()

    fun updateCopy(id: String, f: (EditCopy) -> EditCopy) = synchronized(lock) {
        copies = copies.map { if (it.id == id) f(it) else it }
        copies.firstOrNull { it.id == id }?.let { store.putCopies(listOf(CopyRow.of(it))) }
        val clips = compose()
        _state.update { it.copy(clips = clips) }
    }

    fun addCopy(c: EditCopy) = synchronized(lock) {
        copies = copies + c
        store.putCopies(listOf(CopyRow.of(c)))
        val clips = compose()
        _state.update { it.copy(clips = clips) }
    }

    fun removeCopy(id: String) = synchronized(lock) {
        copies = copies.filter { it.id != id }
        store.deleteCopies(listOf(id))
        val clips = compose()
        _state.update { it.copy(clips = clips) }
    }

    // ---- download queue (persisted so the worker can pick it up) --------------

    /** Queue entries: "<id>" (preferred quality) or "<id> HD"/"<id> SD". */
    fun setQueue(items: List<String>) = tx {
        store.clearQueue()
        store.putQueue(items.filter { it.isNotBlank() }.mapIndexed { i, e -> QueueRow(i, e) })
    }
    fun readQueue(): List<String> = store.queue()
    fun clearQueue() { store.clearQueue() }

    fun updateDownload(f: (DownloadProgress) -> DownloadProgress) = _download.update(f)

    /** Stores a fresh copy of a clip (e.g. with new media URLs). */
    fun replaceClip(c: Clip) = synchronized(lock) {
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
    fun learnFromFile(id: String, file: Uri) {
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

    // ---- thumbnails stored on the phone (so the library works offline) ---------------

    fun thumbFile(id: String) = File(thumbDir, id.replace(UNSAFE_NAME, "_") + ".jpg")

    /** Saves a still frame of a saved video as its thumbnail. Returns true on success. */
    fun makeThumb(id: String, video: Uri): Boolean = runCatching {
        thumbDir.mkdirs()
        val r = android.media.MediaMetadataRetriever()
        try {
            r.setDataSource(ctx, video)
            val durMs = r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0
            val at = (durMs * 1000 / 4).coerceAtMost(2_000_000) // a quarter in, at most 2 s
            val bmp = (if (android.os.Build.VERSION.SDK_INT >= 27)
                r.getScaledFrameAtTime(at, android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 480, 480)
            else r.getFrameAtTime(at)) ?: return@runCatching false
            val tmp = File(thumbFile(id).path + ".tmp")
            tmp.outputStream().use { bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 82, it) }
            bmp.recycle()
            tmp.renameTo(thumbFile(id))
        } finally { r.release() }
        _state.update { it.copy(localThumbs = it.localThumbs + id) }
        true
    }.getOrDefault(false)

    /** Creates missing thumbnails for clips already on the phone (older saves, files added by hand). */
    fun backfillThumbs() {
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

    private fun dropThumbs(ids: kotlin.collections.Collection<String>) {
        ids.forEach { thumbFile(it).delete(); previewFile(it).delete() }
        _state.update { it.copy(localThumbs = it.localThumbs - ids.toSet(), localPreviews = it.localPreviews - ids.toSet()) }
    }

    // ---- small preview copies for the grid (made by PreviewMaker) ----------------------------

    fun previewFile(id: String) = File(previewDir, id.replace(UNSAFE_NAME, "_") + ".mp4")

    /** The small preview copy of a clip, if the setting is on and one has been made. */
    fun previewCopy(c: Clip): Uri? =
        if (prefs.previewCopies && c.id in _state.value.localPreviews) Uri.fromFile(previewFile(c.id)) else null

    fun notePreview(id: String) = _state.update { it.copy(localPreviews = it.localPreviews + id) }

    /** Forgets a copy that wouldn't play; the clip plays from its own file again. */
    fun dropPreview(id: String) {
        previewFile(id).delete()
        _state.update { it.copy(localPreviews = it.localPreviews - id) }
    }

    /** Bytes used by the preview copies. */
    fun previewBytes(): Long = previewDir.listFiles()?.sumOf { it.length() } ?: 0L

    fun clearPreviews() {
        previewDir.listFiles()?.forEach { it.delete() }
        _state.update { it.copy(localPreviews = emptySet()) }
    }

    // ---- "New": clips that arrived in a sync and haven't been opened ------------------------

    fun markSeen(ids: kotlin.collections.Collection<String>) = synchronized(lock) {
        val hit = ids.filter { it in _state.value.unseen }
        if (hit.isEmpty()) return@synchronized
        AppDb.sliced(hit) { store.deleteUnseen(it) }
        _state.update { it.copy(unseen = it.unseen - hit.toSet()) }
    }

    fun markAllSeen() = synchronized(lock) {
        store.clearUnseen()
        _state.update { it.copy(unseen = emptySet()) }
    }

    // ---- files imported from a folder on the phone -------------------------------------------

    /** Still picture for a GIF/WebP/photo: first frame, scaled down. Returns the picture's size. */
    fun makeImageThumb(id: String, image: Uri): Pair<Int, Int>? = runCatching {
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

    private fun importedDir(create: Boolean): DocumentFile? {
        val root = folder() ?: return null
        return root.findFile(IMPORT_DIR)?.takeIf { it.isDirectory } ?: if (create) root.createDirectory(IMPORT_DIR) else null
    }

    private data class Doc(val uri: Uri, val name: String, val modified: Long, val isDir: Boolean)

    private fun children(tree: Uri, parentDocId: String): List<Doc> {
        val out = ArrayList<Doc>()
        val cols = arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED, DocumentsContract.Document.COLUMN_MIME_TYPE)
        ctx.contentResolver.query(DocumentsContract.buildChildDocumentsUriUsingTree(tree, parentDocId), cols, null, null, null)?.use { c ->
            while (c.moveToNext()) {
                val name = c.getString(1) ?: continue
                out += Doc(DocumentsContract.buildDocumentUriUsingTree(tree, c.getString(0)), name, c.getLong(2),
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
    fun importFolder(source: Uri, progress: (String) -> Unit): Pair<Int, Int> {
        val dest = importedDir(create = true) ?: error("Choose a save folder first")
        val resolver = ctx.contentResolver
        val wanted = setOf("gif", "webp") + Clip.VIDEO_EXT
        // Collect the files first (so the count is known), folder by folder.
        val found = ArrayList<Pair<Doc, Uri>>() // file, its parent folder
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
        AppLog.i("Import: moved $moved file(s) into $IMPORT_DIR" + if (failed > 0) ", $failed failed" else "")
        adoptImported(progress)
        return moved to failed
    }

    /**
     * Lists every file in the "imported" subfolder that the library doesn't know yet (after an
     * import, or files you put there yourself). Returns how many were added.
     */
    fun adoptImported(progress: (String) -> Unit = {}): Int = synchronized(importLock) { adoptNewFiles(progress) }

    /** Held while files in "imported" are listed or added, so a file is never listed twice. */
    private val importLock = Any()

    private fun adoptNewFiles(progress: (String) -> Unit): Int {
        val dir = importedDir(create = false) ?: return 0
        val tree = prefs.folderUri ?: return 0
        val known = synchronized(lock) { imports.mapNotNullTo(HashSet()) { it.copyFile } }
        val fresh = children(tree, DocumentsContract.getDocumentId(dir.uri))
            .filter { !it.isDir && "$IMPORT_DIR/${it.name}" !in known }
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
                val file = "$IMPORT_DIR/${d.name}"
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
    fun addOnlineFile(c: Clip, http: okhttp3.OkHttpClient, urls: List<String>, onProgress: (Long, Long) -> Unit = { _, _ -> }) {
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
                        sources = c.sources.ifEmpty { setOf(OnlineSource.of(c.id).label) }, copyFile = "$IMPORT_DIR/$finalName")
                    synchronized(lock) {
                        imports = imports + clip
                        store.putClips(listOf(ClipRow.of(clip, ClipRow.IMPORTED, (store.clips(ClipRow.IMPORTED).lastOrNull()?.pos ?: -1) + 1)))
                        val all = compose()
                        _state.update { it.copy(clips = all) }
                    }
                }
                AppLog.i("Saved ${c.id} as $IMPORT_DIR/$name (${c.tags.size} tag(s))")
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

    // ---- auto-sync: watched collections ------------------------------------------------

    fun setWatched(key: String, on: Boolean) = synchronized(lock) {
        val w = if (on) _state.value.watched + key else _state.value.watched - key
        if (on) store.putWatched(listOf(WatchedRow(key))) else store.deleteWatched(key)
        _state.update { it.copy(watched = w) }
    }

    /** Clips in watched collections (or likes) that aren't on the phone yet. */
    fun pendingWatched(): List<String> {
        val s = _state.value
        if (s.watched.isEmpty()) return emptyList()
        return s.originals.filter { c ->
            c.id !in s.saved && s.failures[c.id]?.gaveUp != true &&
                ((WATCH_LIKES in s.watched && c.liked) || c.colIds.any { it in s.watched })
        }.map { it.id }
    }

    // ---- file index: size + checksum of every finished download --------------------------

    /**
     * Records a finished file. If another saved file has exactly the same content (same SHA-256),
     * it's noted as a duplicate; clips are normally told apart by their RedGifs ID, so this only
     * happens when the very same video exists under two IDs.
     */
    fun recordFile(name: String, size: Long, sha: String) = synchronized(lock) {
        fileIndex = fileIndex + (name to (size to sha))
        store.putFiles(listOf(FileRow(name, size, sha)))
        val dups = findDuplicates()
        dups[name]?.let { AppLog.w("$name has exactly the same content as $it") }
        _state.update { it.copy(duplicates = dups) }
    }

    fun indexed(name: String): Pair<Long, String>? = synchronized(lock) { fileIndex[name] }

    private fun forgetFiles(names: kotlin.collections.Collection<String>) {
        if (names.none { it in fileIndex }) return
        fileIndex = fileIndex - names.toSet()
        AppDb.sliced(names.toSet()) { store.deleteFiles(it) }
    }

    private fun findDuplicates(): Map<String, String> {
        val first = HashMap<String, String>()
        val out = HashMap<String, String>()
        fileIndex.entries.sortedBy { it.key }.forEach { (name, v) ->
            val seen = first.putIfAbsent(v.second, name)
            if (seen != null) out[name] = seen
        }
        return out
    }

    // ---- storage helpers -----------------------------------------------------------

    /** Replaces the stored likes/collections list with [raw] (one transaction). */
    private fun saveLibrary() = tx {
        store.clearClips(ClipRow.LIBRARY)
        store.putClips(raw.mapIndexed { i, c -> ClipRow.of(c, ClipRow.LIBRARY, i) })
    }

    private fun saveExtras() = tx {
        val inLibrary = raw.mapTo(HashSet()) { it.id }
        store.clearClips(ClipRow.EXTRA)
        store.putClips(extras.filter { it.id !in inLibrary }.mapIndexed { i, c -> ClipRow.of(c, ClipRow.EXTRA, i) })
    }

    private fun saveCollections(list: List<RgCollection>) = tx {
        store.clearCollections()
        store.putCollections(list.mapIndexed { i, c -> CollectionRow(c.id, i, c.name, c.count, c.thumb) })
    }

    private fun savePerformers(m: Map<String, Performer>) {
        if (m.isNotEmpty()) store.putPerformers(m.values.map(PerformerRow::of))
    }

    // ---- one-time move of the files used up to version 10 into the database ------------

    private fun importLegacyFiles() {
        val dir = ctx.filesDir
        val files = LEGACY_FILES.map { File(dir, it) }.filter { it.exists() }
        if (files.isEmpty()) return
        if (!prefs.legacyImported) {
            // A file that can't be read is skipped (and kept as <name>.bad); the rest still moves.
            fun <T> read(name: String, parse: (File) -> List<T>): List<T> {
                val f = File(dir, name)
                if (!f.exists()) return emptyList()
                return try { parse(f) } catch (e: Exception) {
                    AppLog.e("Could not read ${f.name} while moving to the database; kept as ${f.name}.bad", e)
                    runCatching { f.copyTo(File(f.path + ".bad"), overwrite = true) }
                    emptyList()
                }
            }
            fun array(f: File): List<JSONObject> = JSONArray(f.readText()).let { a -> (0 until a.length()).map { a.getJSONObject(it) } }
            fun <T> obj(f: File, make: (String, JSONObject) -> T): List<T> =
                JSONObject(f.readText()).let { j -> j.keys().asSequence().toList().map { make(it, j) } }
            fun lines(f: File): List<String> = f.readLines().map { it.trim() }.filter { it.isNotEmpty() }

            val lib = read("library.json") { f -> array(f).map(Clip::fromJson) }.filter { Clip.validId(it.id) }.distinctBy { it.id }
            val ext = read("extras.json") { f -> array(f).map(Clip::fromJson) }.filter { Clip.validId(it.id) }.distinctBy { it.id }
            val ovr = read("overrides.json") { f -> obj(f) { k, j -> OverrideRow.of(k, MetaOverride.fromJson(j.getJSONObject(k))) } }
            val cps = read("copies.json") { f -> array(f).map { CopyRow.of(EditCopy.fromJson(it)) } }
            val hid = read("hidden.txt") { f -> lines(f).map(::HiddenRow) }
            val sav = read("downloaded.txt") { f ->
                lines(f).map { l ->
                    val parts = l.split(" ")
                    SavedRow(parts[0], parts.getOrNull(1)?.takeIf { q -> Quality.entries.any { it.name == q } } ?: Quality.LEGACY.name, 0)
                }
            }
            val que = read("queue.txt") { f -> lines(f) }
            val syn = read("sync_history.json") { f ->
                array(f).map { SyncRecord.fromJson(it) }.map { SyncRow(it.time, it.total, it.added, it.removed, it.saved) }
            }
            val col = read("collections.json") { f -> array(f).map(RgCollection::fromJson) }
            val wat = read("watched.txt") { f -> lines(f).map(::WatchedRow) }
            val fil = read("files.json") { f -> obj(f) { k, j -> j.getJSONObject(k).let { FileRow(k, it.optLong("size"), it.optString("sha")) } } }
            val fai = read("failures.json") { f ->
                obj(f) { k, j -> Failure.fromJson(j.getJSONObject(k)).let { FailureRow(k, it.reason, it.first, it.last, it.attempts) } }
            }
            val nic = read("niches.json") { f -> obj(f) { k, j -> NicheRow(k, j.getString(k)) } }
            val per = read("performers.json") { f -> array(f).map { PerformerRow.of(Performer.fromJson(it)) } }
            try {
                tx {
                    val libIds = lib.mapTo(HashSet()) { it.id }
                    store.putClips(ext.filter { it.id !in libIds }.mapIndexed { i, c -> ClipRow.of(c, ClipRow.EXTRA, i) })
                    store.putClips(lib.mapIndexed { i, c -> ClipRow.of(c, ClipRow.LIBRARY, i) })
                    store.putOverrides(ovr); store.putCopies(cps); store.putHidden(hid); store.putSaved(sav)
                    store.putQueue(que.mapIndexed { i, e -> QueueRow(i, e) }); store.putSyncs(syn)
                    store.putCollections(col.mapIndexed { i, c -> CollectionRow(c.id, i, c.name, c.count, c.thumb) })
                    store.putWatched(wat); store.putFiles(fil); store.putFailures(fai); store.putNiches(nic); store.putPerformers(per)
                }
            } catch (e: Exception) {
                // Nothing was changed (the transaction rolled back) and the files stay where they are; tried again next start.
                AppLog.e("Moving the library into the database failed; will try again next time", e)
                return
            }
            prefs.legacyImported = true
            AppLog.i("Moved the library into the database: ${lib.size} clips, ${ext.size} saved from Online, ${sav.size} download records, " +
                "${cps.size} edited copies, ${ovr.size} info edits, ${col.size} collections")
        }
        // The old files are kept as a backup in files/legacy-json, out of the way.
        val backup = File(dir, "legacy-json").apply { mkdirs() }
        files.forEach { f -> if (!f.renameTo(File(backup, f.name))) AppLog.w("Could not move ${f.name} to the backup folder") }
    }

    companion object {
        private val EDIT_RE = Regex(".+-edit-[0-9]+")
        private val UNSAFE_NAME = Regex("[^A-Za-z0-9_-]")
        /** Subfolder of the save folder that holds imported files. */
        const val IMPORT_DIR = "imported"
        private val LEGACY_FILES = listOf("library.json", "extras.json", "overrides.json", "copies.json", "hidden.txt",
            "downloaded.txt", "queue.txt", "sync_history.json", "collections.json", "watched.txt", "files.json",
            "failures.json", "niches.json", "performers.json")
        /** Watch-list key for "everything I like". */
        const val WATCH_LIKES = "liked"
    }
}
