package app.redgrab

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/** What to show by state. New = not on the phone yet; Unseen = arrived in a sync and not opened yet. */
enum class Filter { All, New, Saved, HD, SD, Edited, Failed, Unseen }

/** Where a clip comes from. */
enum class Origin(val label: String) {
    All("All"), Liked("Liked"), Collections("Collections"), Synced("Synced"), Imported("Imported"), Online("Online saves");

    fun matches(s: LibraryState, c: Clip): Boolean = when (this) {
        All -> true
        Liked -> c.liked
        Collections -> c.colIds.isNotEmpty()
        Synced -> (Library.WATCH_LIKES in s.watched && c.liked) || c.colIds.any { it in s.watched }
        Imported -> c.imported
        Online -> "Discover" in c.sources
    }
}

/** A Browse selection: show only clips with this tag or by this performer. */
data class Facet(val kind: Kind, val value: String) {
    enum class Kind(val label: String) { Tag("Tag"), Performer("Performer"), Category("Category"), Niche("Niche"), Collection("Collection") }
    fun matches(c: Clip) = when (kind) {
        Kind.Tag -> c.tags.any { it.equals(value, ignoreCase = true) }
        Kind.Performer -> c.user.equals(value, ignoreCase = true)
        Kind.Category -> c.category.equals(value, ignoreCase = true)
        Kind.Niche -> c.niches.any { it.equals(value, ignoreCase = true) }
        // value = collection ID, or Library.WATCH_LIKES for your likes
        Kind.Collection -> if (value == Library.WATCH_LIKES) c.liked else value in c.colIds
    }
}

enum class Sort(val label: String) {
    Liked("Liked order"), Newest("Newest upload"), Oldest("Oldest upload"), Views("Most viewed"),
    Likes("Most liked"), Longest("Longest"), Shortest("Shortest"),
}

enum class Length(val label: String, val range: ClosedFloatingPointRange<Double>) {
    Any("Any length", 0.0..Double.MAX_VALUE), Short("Under 15 s", 0.0..15.0),
    Medium("15–60 s", 15.0..60.0), Long("Over 1 min", 60.0..Double.MAX_VALUE),
}

/** Extra filters from the "Filters" sheet. Null/Any = don't filter. */
data class AdvFilters(
    val sort: Sort = Sort.Liked,
    val length: Length = Length.Any,
    val vertical: Boolean? = null,
    val sound: Boolean? = null,
    val verifiedOnly: Boolean = false,
    val sexuality: String? = null,
) {
    val activeCount get() = listOf(length != Length.Any, vertical != null, sound != null, sexuality != null).count { it }
}

/** Clips shown in the Library grid (and paged through in the player). */
fun visibleClips(s: LibraryState, filter: Filter, query: String, facet: Facet?, adv: AdvFilters = AdvFilters(),
                 origin: Origin = Origin.All): List<Clip> {
    val words = query.lowercase().split(" ").map { it.trim() }.filter { it.isNotEmpty() }
    return s.clips.filter { c ->
        val q = s.qualities[c.id] ?: emptySet()
        when (filter) {
            Filter.All -> true
            Filter.New -> c.id !in s.saved
            Filter.Saved -> c.id in s.saved // everything on the phone, edited copies included
            Filter.HD -> Quality.HD in q
            Filter.SD -> Quality.SD in q
            Filter.Edited -> c.isCopy || c.metaEdited
            Filter.Failed -> c.id in s.failures
            Filter.Unseen -> c.id in s.unseen
        } &&
            origin.matches(s, c) &&
            (facet == null || facet.matches(c)) &&
            c.duration in adv.length.range &&
            (adv.vertical == null || c.isVertical == adv.vertical) &&
            (adv.sound == null || c.hasAudio == adv.sound) &&
            (!adv.verifiedOnly || c.verified) &&
            (adv.sexuality == null || c.sexuality.any { it.equals(adv.sexuality, true) }) &&
            words.all { c.searchText.contains(it) }
    }.let { list ->
        when (adv.sort) {
            Sort.Liked -> list
            Sort.Newest -> list.sortedByDescending { it.created }
            Sort.Oldest -> list.sortedBy { it.created }
            Sort.Views -> list.sortedByDescending { it.views }
            Sort.Likes -> list.sortedByDescending { it.likes }
            Sort.Longest -> list.sortedByDescending { it.duration }
            Sort.Shortest -> list.sortedBy { it.duration }
        }
    }
}

/** What the delete screen is asking about. */
data class DeleteRequest(val originals: List<Clip>, val copies: List<Clip>, val selectedCopies: Set<String>)

/** What the full-screen player is showing: a fixed list of clip IDs and the current one. */
data class PlayerSession(val ids: List<String>, val index: Int,
                         /** Clips from online browsing that aren't in the library, by ID. */
                         val online: Map<String, Clip> = emptyMap())

/** What the Discover tab is showing. */
sealed interface Feed {
    val title: String
    data class Search(val query: String) : Feed { override val title get() = if (query.isBlank()) "All of RedGifs" else "Search: $query" }
    data class Niche(val id: String, val name: String) : Feed { override val title get() = name }
    data class Performer(val name: String) : Feed { override val title get() = name }
    data class Related(val clipId: String, val label: String) : Feed { override val title get() = "Related to $label" }
}

data class DiscoverState(
    val feed: Feed = Feed.Search(""),
    val order: OnlineOrder = OnlineOrder.Trending,
    val items: List<Clip> = emptyList(),
    val page: Int = 0,
    val hasMore: Boolean = true,
    val loading: Boolean = false,
    val error: String? = null,
    val niches: List<NicheInfo> = emptyList(),
    val nichesLoaded: Boolean = false,
)

class MainViewModel(app: Application) : AndroidViewModel(app) {

    val library = app.library
    private val prefs = library.prefs
    val state = library.state
    val download = library.download

    /** One-off messages for the snackbar. */
    private val _messages = Channel<String>(Channel.BUFFERED)
    val messages = _messages.receiveAsFlow()

    /** The Saved tab opens on what's actually on the phone. */
    var filter by mutableStateOf(Filter.Saved)
    /** Where from: likes, collections, synced, imported… */
    var origin by mutableStateOf(Origin.All)
    /** Progress text while a folder is being imported; null when idle. */
    var importStatus by mutableStateOf<String?>(null)
        private set
    var adv by mutableStateOf(AdvFilters())
    var discover by mutableStateOf(DiscoverState())
        private set
    /** Bumped to ask the UI to switch to the Discover tab (e.g. after tapping Related). */
    var discoverRequests by mutableStateOf(0)
        private set
    private var discoverJob: kotlinx.coroutines.Job? = null
    /** Layout of the clip grids: even grid or wall, and how many previews per row. */
    var previews by mutableStateOf(prefs.previews)
        private set
    var previewUnsaved by mutableStateOf(prefs.previewUnsaved)
        private set
    var previewCount by mutableStateOf(prefs.previewCount)
        private set
    /**
     * Whether tiles may animate right now: switched on, not in SFW mode, nothing covering the
     * grid (player, editor, delete screen), and the phone isn't in battery saver.
     */
    val previewsActive: Boolean
        get() = previews && !sfw && player == null && editing == null && deleteRequest == null && !powerSave()

    private fun powerSave(): Boolean =
        getApplication<Application>().getSystemService(android.os.PowerManager::class.java)?.isPowerSaveMode == true

    var wall by mutableStateOf(prefs.wall)
        private set
    var columns by mutableStateOf(prefs.columns)
        private set
    var autoSyncHours by mutableStateOf(prefs.autoSyncHours)
        private set
    var syncWifiOnly by mutableStateOf(prefs.syncWifiOnly)
        private set
    /** No internet right now: the Saved side keeps working, Online and syncing wait. */
    var offline by mutableStateOf(false)
        private set
    /** Partial downloads in the save folder: count and total bytes (for Settings). */
    var partialInfo by mutableStateOf(0 to 0L)
        private set
    var themeId by mutableStateOf(prefs.theme)
        private set
    var sfw by mutableStateOf(if (prefs.sfwOnStart) true.also { prefs.sfw = true } else prefs.sfw)
        private set
    var sfwOnStart by mutableStateOf(prefs.sfwOnStart)
        private set
    var volumeGuard by mutableStateOf(prefs.volumeGuard)
        private set
    var volumeLimit by mutableStateOf(prefs.volumeLimit)
        private set
    var deleteRequest by mutableStateOf<DeleteRequest?>(null)
        private set
    /** Set when RedGifs wants a human check; the UI opens the verification screen. */
    var verifyNeeded by mutableStateOf(prefs.pendingVerification != null)
        private set
    var sessionText by mutableStateOf(Session.describe(prefs))
        private set
    var sessionState by mutableStateOf(Session.state(prefs))
        private set
    var query by mutableStateOf("")
    var facet by mutableStateOf<Facet?>(null)
    var player by mutableStateOf<PlayerSession?>(null)
        private set
    var playMuted by mutableStateOf(prefs.playMuted)
        private set
    /** Set when both WhatsApp and WhatsApp Business are installed and the user must pick. */
    var shareChoice by mutableStateOf<Pair<List<Clip>, Map<String, String>>?>(null)
        private set
    var sharing by mutableStateOf(false)
        private set
    var selection by mutableStateOf<Set<String>>(emptySet())
        private set
    var signedIn by mutableStateOf(prefs.signedIn)
        private set
    var needsSignIn by mutableStateOf(false)
        private set
    var folderLabel by mutableStateOf(prefs.folderLabel)
        private set
    var hasFolder by mutableStateOf(prefs.folderUri != null)
        private set
    var hd by mutableStateOf(prefs.hd)
        private set
    var includeCollections by mutableStateOf(prefs.includeCollections)
        private set
    var refreshOnStart by mutableStateOf(prefs.refreshOnStart)
        private set
    var hiddenFromGallery by mutableStateOf(false)
        private set
    var lastCrash by mutableStateOf(AppLog.takeLastCrash())
        private set
    /** Clip open in the editor, if any. */
    var editing by mutableStateOf<Clip?>(null)
        private set
    /** Clip IDs whose info (performer/category/tags) is being edited. */
    var editingInfo by mutableStateOf<List<String>?>(null)
        private set

    init {
        refreshFolderInfo()
        // Re-check the save folder on every launch so files added, moved or deleted outside
        // the app (or saved by older versions) are reflected without a sync.
        watchNetwork()
        // Housekeeping off the main thread: re-check the folder, drop stale partial downloads,
        // and make sure every saved clip has a thumbnail stored on the phone.
        rescanFolder()
        if (!startupRefreshDone && prefs.refreshOnStart && prefs.signedIn) {
            startupRefreshDone = true
            // Offline at launch is normal, not an error: just show what's on the phone.
            if (isOnline()) refresh(quiet = true) else offline = true
        }
    }

    private fun isOnline(): Boolean {
        val cm = getApplication<Application>().getSystemService(android.net.ConnectivityManager::class.java) ?: return true
        val caps = cm.getNetworkCapabilities(cm.activeNetwork ?: return false) ?: return false
        return caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private fun watchNetwork() {
        val cm = getApplication<Application>().getSystemService(android.net.ConnectivityManager::class.java) ?: return
        offline = !isOnline()
        val cb = object : android.net.ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: android.net.Network) { offline = false }
            override fun onLost(network: android.net.Network) { offline = !isOnline() }
        }
        if (runCatching { cm.registerDefaultNetworkCallback(cb) }.isSuccess) netCallback = cb
    }

    private var netCallback: android.net.ConnectivityManager.NetworkCallback? = null

    override fun onCleared() {
        netCallback?.let { cb ->
            runCatching { getApplication<Application>().getSystemService(android.net.ConnectivityManager::class.java)?.unregisterNetworkCallback(cb) }
        }
        super.onCleared()
    }

    private fun refreshPartialInfo() {
        val parts = runCatching { library.partials() }.getOrDefault(emptyList())
        partialInfo = parts.size to parts.sumOf { it.size }
    }

    fun onResume() {
        signedIn = prefs.signedIn
        if (signedIn) needsSignIn = false
        sessionState = Session.state(prefs)
        sessionText = Session.describe(prefs)
        if (sessionState == Session.State.Expired) needsSignIn = true
        verifyNeeded = prefs.pendingVerification != null
    }

    // ---- sync -----------------------------------------------------------------

    fun refresh(quiet: Boolean = false) {
        if (!prefs.signedIn) {
            say("Sign in first (Settings → Account).")
            return
        }
        if (offline && !isOnline()) {
            if (!quiet) say("You're offline. Everything saved still works; syncing needs a connection.")
            return
        }
        viewModelScope.launch {
            library.refresh().onSuccess {
                needsSignIn = false
                val s = state.value
                // Auto-sync: anything new in the collections you marked gets downloaded right away.
                val pending = library.pendingWatched()
                if (pending.isNotEmpty() && hasFolder && DownloadWorker.start(getApplication(), pending)) {
                    say("Synced. Saving ${pending.size} new clip(s) from your synced collections.")
                } else if (!quiet) say("Synced: ${s.originals.size} clips, ${s.newCount} not saved")
            }.onFailure { e ->
                if (e is AuthException) needsSignIn = true
                if (e is VerificationRequiredException) { prefs.pendingVerification = e.subject; verifyNeeded = true }
                say(friendly(e))
            }
        }
    }

    // ---- selection & downloads -------------------------------------------------

    fun toggle(id: String) {
        selection = if (id in selection) selection - id else selection + id
    }

    fun clearSelection() { selection = emptySet() }

    fun selectAllNew() {
        val s = state.value
        selection = s.originals.filter { it.id !in s.saved }.map { it.id }.toSet()
    }

    fun downloadSelected() {
        val s = state.value
        val ids = s.originals.filter { it.id in selection && it.id !in s.saved }.map { it.id }
        selection = emptySet()
        startDownload(ids)
    }

    /** "Save all": everything not saved, except clips whose downloads have given up (retry those by hand). */
    fun downloadAllNew() {
        val s = state.value
        startDownload(s.originals.filter { it.id !in s.saved && s.failures[it.id]?.gaveUp != true }.map { it.id })
    }

    /** Tries the failed downloads again, with a fresh set of attempts. */
    fun retryFailed(ids: kotlin.collections.Collection<String> = state.value.failures.keys) {
        val list = ids.filter { it !in state.value.saved }
        if (list.isEmpty()) return say("Nothing to retry.")
        viewModelScope.launch(Dispatchers.IO) {
            library.clearFailures(list)
            withContext(Dispatchers.Main) { startDownload(list) }
        }
    }

    fun resumeDownload() {
        if (!DownloadWorker.resume(getApplication())) say("Nothing to resume.")
    }

    fun clearDownloadQueue() = DownloadWorker.clear(getApplication())

    fun download(id: String) = startDownload(listOf(id))

    /** Download the selection in a specific quality (SD only when asked for explicitly). */
    fun downloadSelectedAs(q: Quality) {
        val s = state.value
        val items = s.originals.filter { it.id in selection && q !in (s.qualities[it.id] ?: emptySet()) }.map { "${it.id} ${q.name}" }
        selection = emptySet()
        startDownload(items)
    }

    /** Save a specific quality of a clip (e.g. HD when only SD is saved). */
    fun downloadQuality(id: String, q: Quality) = startDownload(listOf("$id ${q.name}"))

    // ---- deleting -------------------------------------------------------------------

    /** Starts deleting the given clips; if any have edited copies, the user picks which copies go too. */
    fun requestDelete(ids: kotlin.collections.Collection<String>) {
        val byId = state.value.clips.associateBy { it.id }
        val picked = ids.mapNotNull(byId::get)
        val originals = picked.filter { !it.isCopy }
        val pickedCopies = picked.filter { it.isCopy }
        // Copies of the originals that weren't selected themselves: ask about these.
        val related = library.copiesOf(originals.map { it.id }).filter { c -> pickedCopies.none { it.id == c.id } }
        deleteRequest = DeleteRequest(originals, pickedCopies + related, pickedCopies.map { it.id }.toSet())
        player = null
    }

    fun toggleDeleteCopy(id: String) {
        val r = deleteRequest ?: return
        deleteRequest = r.copy(selectedCopies = if (id in r.selectedCopies) r.selectedCopies - id else r.selectedCopies + id)
    }

    fun cancelDelete() { deleteRequest = null }

    fun confirmDelete(hide: Boolean) {
        val r = deleteRequest ?: return
        deleteRequest = null
        selection = emptySet()
        viewModelScope.launch(Dispatchers.IO) {
            val n = runCatching { library.delete(r.originals.map { it.id }, r.selectedCopies, hide) }
                .onFailure { AppLog.e("Delete failed", it) }.getOrDefault(0)
            val kept = r.copies.size - r.selectedCopies.size
            say("Deleted $n file(s)" + (if (kept > 0) " · kept $kept edited cop${if (kept == 1) "y" else "ies"}" else ""))
        }
    }

    fun unhide(ids: List<String>) { viewModelScope.launch(Dispatchers.IO) { library.unhide(ids) } }

    // ---- SFW, volume, theme, session -----------------------------------------------

    fun changeSfw(v: Boolean) {
        prefs.sfw = v; sfw = v
        if (v) {
            player = null
            // Drop what was loaded online so no thumbnails stay in memory or on screen.
            discoverJob?.cancel()
            discover = DiscoverState(feed = discover.feed, order = discover.order)
        }
        AppLog.i("SFW mode ${if (v) "on" else "off"}")
    }

    fun changeSfwOnStart(v: Boolean) { prefs.sfwOnStart = v; sfwOnStart = v }
    fun changeVolumeGuard(v: Boolean) { prefs.volumeGuard = v; volumeGuard = v }
    fun changeVolumeLimit(v: Int) { prefs.volumeLimit = v; volumeLimit = v }
    fun changeTheme(id: String) { prefs.theme = id; themeId = id }

    // ---- layout ---------------------------------------------------------------------

    fun changeWall(v: Boolean) { prefs.wall = v; wall = v }
    fun changePreviews(v: Boolean) { prefs.previews = v; previews = v }
    fun changePreviewUnsaved(v: Boolean) { prefs.previewUnsaved = v; previewUnsaved = v }
    fun changePreviewCount(v: Int) { prefs.previewCount = v; previewCount = prefs.previewCount }
    fun changeColumns(v: Int) { prefs.columns = v; columns = prefs.columns }

    // ---- collections & auto-sync -----------------------------------------------------

    /** Marks a collection (or [Library.WATCH_LIKES]) to be kept in sync; new clips in it download automatically. */
    fun setWatched(key: String, on: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            library.setWatched(key, on)
            if (on) {
                val pending = library.pendingWatched()
                withContext(Dispatchers.Main) {
                    when {
                        pending.isEmpty() -> say("Syncing on. Everything in it is already saved.")
                        !hasFolder -> say("Syncing on. Choose a save folder (Settings) to start saving.")
                        DownloadWorker.start(getApplication(), pending) -> say("Syncing on. Saving ${pending.size} clip(s).")
                        else -> say("Syncing on. The clips are saved after the current download.")
                    }
                }
            }
        }
    }

    fun openCollection(key: String) = browse(Facet(Facet.Kind.Collection, key))

    fun changeAutoSyncHours(h: Int) { prefs.autoSyncHours = h; autoSyncHours = h; SyncWorker.schedule(getApplication()) }
    fun changeSyncWifiOnly(v: Boolean) { prefs.syncWifiOnly = v; syncWifiOnly = v; SyncWorker.schedule(getApplication()) }

    fun clearPartials() {
        viewModelScope.launch(Dispatchers.IO) {
            val freed = library.cleanPartials(all = true)
            refreshPartialInfo()
            say(if (freed > 0) "Removed partial downloads (${Format.size(freed)} freed)" else "No partial downloads to remove")
        }
    }

    fun verificationHandled() { verifyNeeded = false }

    // ---- info editing & clip editor ---------------------------------------------

    fun editInfo(ids: kotlin.collections.Collection<String>) {
        val originals = ids.filter { id -> state.value.clips.any { it.id == id && !it.isCopy } }
        if (originals.isEmpty()) return say("Edited copies share their original's info; edit the original instead.")
        editingInfo = originals
    }

    fun closeInfoEditor() { editingInfo = null }

    /**
     * Applies info edits to the given clips. Null performer/category = leave as is. Tags and
     * niches: the "add" lists are added to every clip, the "remove" sets removed from every clip.
     */
    fun saveInfo(ids: List<String>, performer: String?, category: String?, addTags: List<String>, removeTags: Set<String>,
                 addNiches: List<String> = emptyList(), removeNiches: Set<String> = emptySet()) {
        val byId = state.value.clips.associateBy { it.id }
        editingInfo = null
        selection = emptySet()
        viewModelScope.launch(Dispatchers.Default) {
            fun merge(current: List<String>, add: List<String>, remove: Set<String>): List<String> {
                val gone = remove.mapTo(HashSet()) { it.lowercase() }
                return (current.filter { it.lowercase() !in gone } + add).distinctBy { it.lowercase() }
            }
            val changes = ids.associateWith { id ->
                val c = byId[id] ?: return@associateWith null
                val old = library.override(id) ?: MetaOverride()
                MetaOverride(
                    user = performer?.trim() ?: old.user,
                    category = category?.trim() ?: old.category,
                    tags = if (addTags.isEmpty() && removeTags.isEmpty()) old.tags else merge(c.tags, addTags, removeTags),
                    niches = if (addNiches.isEmpty() && removeNiches.isEmpty()) old.niches else merge(c.niches, addNiches, removeNiches),
                )
            }
            withContext(Dispatchers.IO) { library.setOverrides(changes) }
            say("Updated ${ids.size} clip(s)")
        }
    }

    /** Drops the user's edits so the clips show RedGifs' own info again. */
    fun resetInfo(ids: List<String>) {
        editingInfo = null
        selection = emptySet()
        viewModelScope.launch(Dispatchers.IO) {
            library.setOverrides(ids.associateWith { null })
            say("Restored RedGifs info for ${ids.size} clip(s)")
        }
    }

    fun openEditor(c: Clip) {
        if (c.id !in state.value.saved) return say("Save the clip first, then edit it.")
        player = null
        editing = c
    }

    fun closeEditor() { editing = null }

    fun deleteCopy(c: Clip) {
        if (!c.isCopy) return
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { library.folder()?.findFile(c.copyFile!!)?.delete() }
            library.removeCopy(c.id)
            AppLog.i("Deleted edited copy ${c.copyFile}")
            say("Edited copy deleted")
        }
        player = null
    }

    private fun startDownload(ids: List<String>) {
        when {
            ids.isEmpty() -> say("Nothing new to download.")
            !hasFolder || library.folder() == null -> say("Choose a save folder first (Settings → Save folder).")
            !prefs.signedIn -> say("Sign in first.")
            !DownloadWorker.start(getApplication(), ids) -> say("A download is already running. It will finish first.")
            else -> {}
        }
    }

    fun stopDownload() = DownloadWorker.stop(getApplication())

    fun dismissDownloadMessage() = library.updateDownload { DownloadProgress() }

    // ---- player & sharing --------------------------------------------------------

    fun openPlayer(list: List<Clip>, index: Int) {
        if (list.isEmpty()) return
        if (sfw) return say("SFW mode is on. Turn it off (eye icon) to play clips.")
        player = PlayerSession(list.map { it.id }, index.coerceIn(0, list.lastIndex))
    }

    /** Plays clips from online browsing; ones already in the library use their library entry. */
    fun openOnlinePlayer(list: List<Clip>, index: Int) {
        if (list.isEmpty()) return
        if (sfw) return say("SFW mode is on. Turn it off (eye icon) to play clips.")
        val known = state.value.clips.mapTo(HashSet()) { it.id }
        player = PlayerSession(list.map { it.id }, index.coerceIn(0, list.lastIndex), list.filter { it.id !in known }.associateBy { it.id })
    }

    /** Adds an online clip to the library and saves it (HD first). */
    fun saveOnline(clip: Clip, q: Quality? = null) {
        viewModelScope.launch(Dispatchers.IO) {
            library.addExtra(clip)
            withContext(Dispatchers.Main) { if (q == null) download(clip.id) else downloadQuality(clip.id, q) }
        }
    }

    // ---- Discover (online browsing) ---------------------------------------------------

    fun openFeed(feed: Feed, order: OnlineOrder = discover.order) {
        discoverJob?.cancel()
        discover = discover.copy(feed = feed, order = order, items = emptyList(), page = 0, hasMore = true, loading = false, error = null)
        loadMoreDiscover()
    }

    fun changeDiscoverOrder(order: OnlineOrder) = openFeed(discover.feed, order)

    /** Shows RedGifs' related clips for [clip] in Discover. */
    fun openRelated(clip: Clip) {
        val id = clip.copyOf ?: clip.id
        player = null
        openFeed(Feed.Related(id, clip.user.ifBlank { "this clip" }))
        discoverRequests++
    }

    /** Opens a niche online, by its RedGifs name (as shown on a clip). */
    fun openNicheOnline(name: String) {
        val id = library.nicheId(name) ?: return say("“$name” is one of your own niches, so there's no RedGifs feed for it.")
        player = null
        openFeed(Feed.Niche(id, name))
        discoverRequests++
    }

    fun searchOnline(query: String) {
        player = null
        openFeed(Feed.Search(query))
        discoverRequests++
    }

    fun openPerformerOnline(name: String) {
        player = null
        openFeed(Feed.Performer(name))
        discoverRequests++
    }

    fun loadMoreDiscover() {
        val d = discover
        if (sfw) return // SFW mode: nothing is fetched from RedGifs
        if (d.loading || !d.hasMore) return
        val token = prefs.token; val ua = prefs.userAgent
        if (token == null || ua == null) { discover = d.copy(error = "Sign in to browse RedGifs."); return }
        discover = d.copy(loading = true, error = null)
        discoverJob = viewModelScope.launch {
            val next = d.page + 1
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val api = RedGifsApi(token, ua)
                    val extras = FeedExtras()
                    val page = when (val f = d.feed) {
                        is Feed.Search -> api.searchClips(f.query, d.order, next, extras)
                        is Feed.Niche -> api.nicheClips(f.id, d.order, next, extras)
                        is Feed.Performer -> api.performerClips(f.name, d.order, next, extras)
                        is Feed.Related -> api.relatedClips(f.clipId, next, extras)
                    }
                    library.learn(extras)
                    page.copy(clips = page.clips.map(library::resolveNiches))
                }
            }
            result.onSuccess { pg ->
                val seen = discover.items.mapTo(HashSet()) { it.id }
                discover = discover.copy(items = discover.items + pg.clips.filter { it.id !in seen }, page = next,
                    hasMore = pg.hasMore && pg.clips.isNotEmpty(), loading = false)
            }.onFailure { e ->
                if (e is kotlinx.coroutines.CancellationException) return@onFailure
                if (e is AuthException) needsSignIn = true
                if (e is VerificationRequiredException) { prefs.pendingVerification = e.subject; verifyNeeded = true }
                AppLog.e("Discover failed (${d.feed.title}): ${e.message}", e.takeUnless { it is AuthException || it is HttpException })
                discover = discover.copy(loading = false, error = friendly(e).replace("Sync failed", "Couldn't load"))
            }
        }
    }

    fun loadFollowedNiches(force: Boolean = false) {
        if (sfw) return
        if (discover.nichesLoaded && !force) return
        val token = prefs.token ?: return; val ua = prefs.userAgent ?: return
        viewModelScope.launch {
            val list = withContext(Dispatchers.IO) {
                runCatching { RedGifsApi(token, ua).followedNiches() }
                    .onFailure { AppLog.w("Could not read followed niches: ${it.message}") }.getOrNull()
            }
            discover = discover.copy(niches = list ?: discover.niches, nichesLoaded = true)
            if (list != null) withContext(Dispatchers.IO) { library.learnNiches(list.associate { it.id to it.name }) }
        }
    }

    fun movePlayer(delta: Int) {
        val p = player ?: return
        val next = p.index + delta
        if (next in p.ids.indices) player = p.copy(index = next)
    }

    fun closePlayer() { player = null }

    fun changePlayMuted(v: Boolean) { prefs.playMuted = v; playMuted = v }

    fun browse(f: Facet) {
        facet = f
        query = ""
        filter = Filter.All
        origin = Origin.All
        player = null
    }

    fun shareToWhatsApp(clip: Clip) = shareToWhatsApp(listOf(clip))

    /** Shares the selected clips (up to 30 at once) to WhatsApp. */
    fun shareSelectedToWhatsApp() {
        val byId = state.value.clips.associateBy { it.id }
        val clips = selection.mapNotNull(byId::get)
        selection = emptySet()
        shareToWhatsApp(clips)
    }

    fun shareToWhatsApp(clips: List<Clip>) {
        if (clips.isEmpty()) return
        if (clips.size > WhatsAppShare.MAX_CLIPS) return say("WhatsApp takes up to ${WhatsAppShare.MAX_CLIPS} clips at a time. Select fewer.")
        val apps = WhatsAppShare.installed(getApplication())
        when (apps.size) {
            0 -> say("WhatsApp isn't installed in this profile. Inside Secure Folder, add WhatsApp to Secure Folder too.")
            1 -> shareTo(clips, apps.keys.first())
            else -> shareChoice = clips to apps
        }
    }

    fun shareTo(clips: List<Clip>, pkg: String) {
        shareChoice = null
        if (sharing) return
        sharing = true
        viewModelScope.launch {
            runCatching {
                val uris = WhatsAppShare.prepare(getApplication(), clips)
                WhatsAppShare.send(getApplication(), pkg, uris)
            }.onFailure { e ->
                AppLog.e("Share to WhatsApp failed: ${e.message}", e.takeUnless { it is HttpException })
                say(friendly(e).replace("Sync failed", "Share failed"))
            }
            sharing = false
        }
    }

    fun cancelShareChoice() { shareChoice = null }

    // ---- settings --------------------------------------------------------------

    fun setFolder(uri: Uri) {
        // A running download writes to the folder it started with; switching under it would split the files.
        if (DownloadWorker.active) { say("Pause the download before changing the save folder."); return }
        val ctx = getApplication<Application>()
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        runCatching { ctx.contentResolver.takePersistableUriPermission(uri, flags) }
            .onFailure { AppLog.e("Could not keep folder permission", it) }
        prefs.folderUri = uri
        prefs.folderLabel = labelFor(uri)
        AppLog.i("Save folder set: ${prefs.folderLabel}")
        refreshFolderInfo()
        rescanFolder()
        // Keep clips out of the phone's Gallery by default; can be switched off in Settings.
        changeHiddenFromGallery(true)
    }

    /** Creates a subfolder (a leading dot makes it hidden) inside the current folder and switches to it. */
    fun createSubfolder(name: String) {
        val clean = name.trim().replace(Regex("[\\\\/:*?\"<>|]"), "_")
        if (clean.isEmpty() || clean == "." || clean == "..") return say("Enter a folder name.")
        if (DownloadWorker.active) return say("Pause the download before changing the save folder.")
        viewModelScope.launch {
            val created = withContext(Dispatchers.IO) {
                runCatching {
                    val parent = library.folder() ?: error("Choose a folder first")
                    parent.findFile(clean)?.takeIf { it.isDirectory } ?: parent.createDirectory(clean)
                        ?: error("The folder could not be created")
                }
            }
            created.onSuccess { dir ->
                prefs.folderUri = dir.uri
                prefs.folderLabel = (prefs.folderLabel ?: "") + "/" + clean
                AppLog.i("Created and switched to subfolder: ${prefs.folderLabel}")
                refreshFolderInfo()
                rescanFolder()
                changeHiddenFromGallery(true)
                say("Now saving to “$clean”")
            }.onFailure { e ->
                AppLog.e("Create subfolder failed", e)
                say("Couldn't create folder: ${e.message}")
            }
        }
    }

    fun changeHiddenFromGallery(hide: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            val folder = library.folder() ?: return@launch say("Choose a folder first.")
            runCatching {
                val existing = folder.findFile(".nomedia")
                if (hide && existing == null) folder.createFile("application/octet-stream", ".nomedia")
                if (!hide) existing?.delete()
            }.onFailure { AppLog.e("Changing .nomedia failed", it) }
            hiddenFromGallery = folder.findFile(".nomedia") != null
            AppLog.i("Hide from Gallery: $hiddenFromGallery")
        }
    }

    fun changeHd(v: Boolean) { prefs.hd = v; hd = v }
    fun changeIncludeCollections(v: Boolean) { prefs.includeCollections = v; includeCollections = v }
    fun changeRefreshOnStart(v: Boolean) { prefs.refreshOnStart = v; refreshOnStart = v }

    fun signOut() {
        prefs.signOut()
        android.webkit.CookieManager.getInstance().removeAllCookies(null)
        android.webkit.WebStorage.getInstance().deleteAllData()
        signedIn = false
        AppLog.i("Signed out")
    }

    fun forgetHistory() = viewModelScope.launch(Dispatchers.IO) { library.forgetHistory() }

    fun dismissCrash() { lastCrash = null }

    /** document URI of a saved clip, for opening it in a video player. */
    suspend fun savedFileUri(id: String): Uri? = withContext(Dispatchers.IO) {
        library.folder()?.findFile("$id.mp4")?.uri
    }

    /** Re-reads the save folder, then tidies it: stale partials out, missing thumbnails in. */
    private fun rescanFolder() = viewModelScope.launch(Dispatchers.IO) {
        runCatching { library.rescan() }.onFailure { AppLog.e("Folder scan failed", it) }
        runCatching { library.cleanPartials() }
        refreshPartialInfo()
        runCatching { library.adoptImported() }
        runCatching { library.backfillThumbs() }
    }

    /** Moves the GIFs and videos of a folder you picked into RedGrab and lists them as "Imported". */
    fun importFolder(uri: Uri) {
        if (importStatus != null) return
        if (!hasFolder) return say("Choose a save folder first.")
        importStatus = "Starting"
        viewModelScope.launch(Dispatchers.IO) {
            val r = runCatching { library.importFolder(uri) { importStatus = it } }
            importStatus = null
            r.onSuccess { (moved, failed) ->
                say("Imported %,d file(s)".format(moved) + if (failed > 0) ". %,d couldn't be moved (see the log).".format(failed) else "")
            }.onFailure { AppLog.e("Import failed", it); say("Import failed: ${it.message}") }
        }
    }

    fun markAllSeen() { viewModelScope.launch(Dispatchers.IO) { library.markAllSeen() } }

    private fun refreshFolderInfo() {
        hasFolder = prefs.folderUri != null
        folderLabel = prefs.folderLabel
        viewModelScope.launch(Dispatchers.IO) {
            val f = library.folder()
            if (prefs.folderUri != null && f == null) {
                AppLog.w("Save folder is no longer accessible: ${prefs.folderLabel}")
                say("Can't access the save folder any more. Choose it again in Settings.")
            }
            hiddenFromGallery = f?.findFile(".nomedia") != null
        }
    }

    private fun labelFor(uri: Uri): String = runCatching {
        val id = if (DocumentsContract.isDocumentUri(getApplication(), uri)) DocumentsContract.getDocumentId(uri)
        else DocumentsContract.getTreeDocumentId(uri)
        val (vol, path) = id.split(":", limit = 2).let { it[0] to it.getOrElse(1) { "" } }
        val volName = if (vol == "primary") "Internal storage" else vol
        if (path.isEmpty()) volName else "$volName/$path"
    }.getOrElse { DocumentFile.fromTreeUri(getApplication(), uri)?.name ?: uri.toString() }

    private fun say(msg: String) { _messages.trySend(msg) }

    private fun friendly(e: Throwable): String = when (e) {
        is AuthException -> "Your RedGifs session expired. Tap Renew to sign in again."
        is VerificationRequiredException -> "RedGifs wants a quick human check before continuing."
        is UnknownHostException -> "No internet connection."
        is SSLException -> "Secure connection failed. A network filter may be blocking RedGifs."
        is HttpException -> "RedGifs returned an error (HTTP ${e.code}). See Log."
        else -> "Sync failed: ${e.message ?: e.javaClass.simpleName}"
    }

    companion object {
        /** Refresh once per app launch, not on every screen rotation. */
        private var startupRefreshDone = false

        /** Set when the user presses the phone's volume keys: from then on this launch, the volume guard stays out of the way. */
        @Volatile var volumeOverride = false
    }
}
