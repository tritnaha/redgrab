package app.redgrab

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import java.io.File

class Library(internal val ctx: Context) {
    val prefs = Prefs(ctx)
    internal val db = AppDb.open(ctx)
    internal val store = db.store()
    internal val thumbDir = File(ctx.filesDir, "thumbs")
    internal val previewDir = File(ctx.filesDir, "previews")
    /** Saved file name → (size in bytes, SHA-256), recorded when a download finishes. */
    internal var fileIndex: Map<String, Pair<Long, String>> = emptyMap()
    internal val refreshLock = Mutex()

    /** Guards raw/extras/overrides/copies/hidden and the history file: they're touched from UI, sync and the download worker. */
    internal val lock = Any()

    /** Clips exactly as RedGifs returned them (no user edits). */
    internal var raw: List<Clip> = emptyList()
    /** Clips saved from online browsing that aren't in your likes/collections. */
    internal var extras: List<Clip> = emptyList()
    /** Files you imported from a folder on the phone. */
    internal var imports: List<Clip> = emptyList()
    internal var overrides: Map<String, MetaOverride> = emptyMap()
    internal var copies: List<EditCopy> = emptyList()
    internal var hidden: Set<String> = emptySet()
    internal var nicheNames: Map<String, String> = emptyMap()

    internal val _state = MutableStateFlow(LibraryState())
    val state: StateFlow<LibraryState> = _state

    /** Download progress lives in its own stream so frequent updates don't recompute the library. */
    internal val _download = MutableStateFlow(DownloadProgress())
    val download: StateFlow<DownloadProgress> = _download

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

    // ---- refresh -------------------------------------------------------------

    /** One file in the save folder. */
    data class Entry(val name: String, val docId: String, val size: Long, val modified: Long)

    // ---- saved / history ------------------------------------------------------

    // ---- deleting & hiding ---------------------------------------------------------

    // ---- niche names & profiles learned while browsing online -------------------------

    // ---- clips saved from online browsing ------------------------------------------

    // ---- info edits ---------------------------------------------------------------

    fun override(id: String): MetaOverride? = synchronized(lock) { overrides[id] }

    /** Puts a clip in one of your categories, keeping its other edits. */
    fun setCategory(id: String, category: String) = setOverrides(mapOf(id to (override(id) ?: MetaOverride()).copy(category = category.trim())))

    // ---- edited copies -------------------------------------------------------------

    /** Held while a file is swapped for its edited version, so housekeeping doesn't interfere. */
    val swapLock = Any()

    // ---- download queue (persisted so the worker can pick it up) --------------

    // ---- thumbnails stored on the phone (so the library works offline) ---------------

    // ---- small preview copies for the grid (made by PreviewMaker) ----------------------------

    // ---- "New": clips that arrived in a sync and haven't been opened ------------------------

    // ---- files imported from a folder on the phone -------------------------------------------

    internal data class Doc(val uri: Uri, val name: String, val modified: Long, val isDir: Boolean)

    /** Held while files in "imported" are listed or added, so a file is never listed twice. */
    internal val importLock = Any()

    // ---- auto-sync: watched collections ------------------------------------------------

    // ---- file index: size + checksum of every finished download --------------------------

    // ---- storage helpers -----------------------------------------------------------

    // ---- one-time move of the files used up to version 10 into the database ------------

    companion object {
        /** Why a clip you chose not to download ("Don't save these") is set aside. */
        const val SKIPPED = "Set aside by you"
        internal val EDIT_RE = Regex(".+-edit-[0-9]+")
        internal val UNSAFE_NAME = Regex("[^A-Za-z0-9_-]")
        /** Subfolder of the save folder that holds imported files. */
        const val IMPORT_DIR = "imported"
        internal val LEGACY_FILES = listOf("library.json", "extras.json", "overrides.json", "copies.json", "hidden.txt",
            "downloaded.txt", "queue.txt", "sync_history.json", "collections.json", "watched.txt", "files.json",
            "failures.json", "niches.json", "performers.json")
        /** Watch-list key for "everything I like". */
        const val WATCH_LIKES = "liked"
    }
}
