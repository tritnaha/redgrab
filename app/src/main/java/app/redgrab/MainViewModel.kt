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
        Imported -> "Imported" in c.sources
        Online -> c.sources.any { it in OnlineSource.SAVED_LABELS }
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

/** What the Online tab is showing: one feed of one source. */
sealed interface Feed {
    val title: String
    val source: OnlineSource
    /** What the search box holds for this feed; also what's remembered as "recent". */
    val query: String get() = ""
    /** Nothing to load until something is typed (FikFap and sex.com have no general feed). */
    val needsQuery: Boolean get() = false
    /** Sort orders the feed offers; empty = it has just one order. */
    val orders: List<OnlineOrder> get() = emptyList()
    /** The site numbers this feed's pages, so any page can be opened directly (others only continue from the last one). */
    val numbered: Boolean get() = false

    // RedGifs
    data class Search(override val query: String) : Feed {
        override val title get() = if (query.isBlank()) "All of RedGifs" else "Search: $query"
        override val source get() = OnlineSource.RedGifs
        override val orders get() = OnlineOrder.entries
        override val numbered get() = true
    }
    data class Niche(val id: String, val name: String) : Feed {
        override val title get() = name
        override val source get() = OnlineSource.RedGifs
        // Niche feeds have no "top this week / month"; RedGifs rejects those.
        override val orders get() = listOf(OnlineOrder.Trending, OnlineOrder.New, OnlineOrder.TopAll)
        override val numbered get() = true
    }
    data class Performer(val name: String) : Feed {
        override val title get() = name
        override val source get() = OnlineSource.RedGifs
        // Creator feeds only sort by newest or top.
        override val orders get() = listOf(OnlineOrder.New, OnlineOrder.TopAll)
        override val numbered get() = true
    }
    data class Related(val clipId: String, val label: String) : Feed {
        override val title get() = "Related to $label"
        override val source get() = OnlineSource.RedGifs
        override val numbered get() = true
    }

    // Reddit
    data object RedditHome : Feed {
        override val title get() = "Front page"
        override val source get() = OnlineSource.Reddit
        override val orders get() = OnlineOrder.entries
    }
    data class Subreddit(val name: String) : Feed {
        override val title get() = "r/$name"
        override val source get() = OnlineSource.Reddit
        override val query get() = name
        override val orders get() = OnlineOrder.entries
    }
    data class RedditUser(val name: String) : Feed {
        override val title get() = "u/$name"
        override val source get() = OnlineSource.Reddit
        override val query get() = "u/$name"
        override val orders get() = OnlineOrder.entries
    }
    data object RedditSaved : Feed {
        override val title get() = "Saved"
        override val source get() = OnlineSource.Reddit
    }
    data object RedditUpvoted : Feed {
        override val title get() = "Upvoted"
        override val source get() = OnlineSource.Reddit
    }

    // FikFap
    data class FikFapTag(val tag: String) : Feed {
        override val title get() = "#$tag"
        override val source get() = OnlineSource.FikFap
        override val query get() = tag
        override val needsQuery get() = tag.isBlank()
    }
    data class FikFapUser(val name: String) : Feed {
        override val title get() = "@$name"
        override val source get() = OnlineSource.FikFap
        override val query get() = "@$name"
    }

    // sex.com (GIF search; an empty search isn't accepted)
    data class SexSearch(override val query: String) : Feed {
        override val title get() = query
        override val source get() = OnlineSource.SexCom
        override val needsQuery get() = query.isBlank()
        override val numbered get() = true
    }

    // EroMe (albums found by search, or one uploader's albums)
    data class EromeSearch(override val query: String) : Feed {
        override val title get() = query
        override val source get() = OnlineSource.EroMe
        override val needsQuery get() = query.isBlank()
    }
    data class EromeUser(val name: String) : Feed {
        override val title get() = "@$name"
        override val source get() = OnlineSource.EroMe
        override val query get() = "@$name"
    }

    // 4chan /gif/: every thread, or the threads whose subject has the words
    data class ChanThreads(override val query: String) : Feed {
        override val title get() = query.ifBlank { "/${FourChanApi.BOARD}/" }
        override val source get() = OnlineSource.FourChan
    }

    companion object {
        /** What a source shows first. */
        fun home(s: OnlineSource): Feed = when (s) {
            OnlineSource.RedGifs -> Search("")
            OnlineSource.Reddit -> RedditHome
            OnlineSource.FikFap -> FikFapTag("")
            OnlineSource.SexCom -> SexSearch("")
            OnlineSource.EroMe -> EromeSearch("")
            OnlineSource.FourChan -> ChanThreads("")
        }

        private val REDDIT_NAME = Regex("[^A-Za-z0-9_+-]")
        /** EroMe user names go into the page address: letters, digits, dash, underscore, dot. Anything after that is dropped. */
        private val EROME_NAME = Regex("^[A-Za-z0-9_.-]+")

        /** The feed for what was typed in the search box (or tapped in "recent"). */
        fun fromQuery(s: OnlineSource, text: String): Feed {
            val t = text.trim()
            return when (s) {
                OnlineSource.RedGifs -> Search(t)
                OnlineSource.Reddit -> {
                    val path = t.removePrefix("/")
                    val user = path.startsWith("u/", ignoreCase = true) || path.startsWith("user/", ignoreCase = true)
                    val name = path.substringAfter('/', path).replace(REDDIT_NAME, "")
                    when {
                        name.isEmpty() -> RedditHome
                        user -> RedditUser(name)
                        else -> Subreddit(name)
                    }
                }
                OnlineSource.FikFap -> if (t.startsWith("@")) t.drop(1).trim().let { if (it.isEmpty()) FikFapTag("") else FikFapUser(it) }
                    else FikFapTag(t.trimStart('#').trim())
                OnlineSource.SexCom -> SexSearch(t)
                OnlineSource.EroMe -> if (t.startsWith("@")) EROME_NAME.find(t.drop(1).trim())?.value?.let { EromeUser(it) } ?: EromeSearch("")
                    else EromeSearch(t)
                OnlineSource.FourChan -> ChanThreads(t)
            }
        }
    }
}

data class DiscoverState(
    val feed: Feed = Feed.Search(""),
    val order: OnlineOrder = OnlineOrder.Trending,
    val items: List<Clip> = emptyList(),
    val page: Int = 0,
    /** Where the next page starts, for feeds that continue from a marker instead of a page number. */
    val cursor: String? = null,
    /** The page the list starts at (not 1 after jumping straight to a page). */
    val firstPage: Int = 1,
    /** Where in [items] each loaded page begins, in order; the first belongs to [firstPage]. */
    val pageStarts: List<Int> = emptyList(),
    /** How many pages the site says there are; 0 = it doesn't say. */
    val pages: Int = 0,
    val hasMore: Boolean = true,
    val loading: Boolean = false,
    val error: String? = null,
    val niches: List<NicheInfo> = emptyList(),
    val nichesLoaded: Boolean = false,
    /** Subreddits the Reddit account has joined. */
    val subs: List<String> = emptyList(),
    val subsLoaded: Boolean = false,
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
    var discover by mutableStateOf(DiscoverState(feed = Feed.home(
        OnlineSource.entries.firstOrNull { it.name == prefs.onlineSource } ?: OnlineSource.RedGifs)))
        private set
    /** Recently opened subreddits / hashtags / searches of the source on screen. */
    var recents by mutableStateOf(prefs.recent(discover.feed.source.name))
        private set
    var redditClientId by mutableStateOf(prefs.redditClientId ?: "")
        private set
    /** Reddit account name when signed in to Reddit, else null. */
    var redditUser by mutableStateOf(if (prefs.redditRefresh != null) prefs.redditUser ?: "" else null)
        private set
    /** Online files being saved straight into the save folder right now (pictures, 4chan clips), by clip ID. */
    var savingImages by mutableStateOf<Set<String>>(emptySet())
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
    /** How many previews may really play at once: the chosen number, or as many as the phone can decode. */
    val previewLimit: Int get() = if (previewCount == 0) app.redgrab.ui.maxPreviews else previewCount
    var playerFill by mutableStateOf(prefs.playerFill)
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
        // A session that has run out is renewed out of sight; only if that fails are you asked to sign in.
        if (sessionState == Session.State.Expired || sessionState == Session.State.ExpiringSoon) keepSessionAlive()
        verifyNeeded = prefs.pendingVerification != null
        redditUser = if (prefs.redditRefresh != null) prefs.redditUser ?: "" else null
    }

    private var renewing = false

    private fun keepSessionAlive() {
        if (renewing || !isOnline()) return
        renewing = true
        viewModelScope.launch {
            val ok = SessionRenewer.renew(getApplication())
            renewing = false
            sessionState = Session.state(prefs)
            sessionText = Session.describe(prefs)
            if (ok) needsSignIn = false else if (sessionState == Session.State.Expired) needsSignIn = true
        }
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
            library.refresh().also { sessionState = Session.state(prefs); sessionText = Session.describe(prefs) }.onSuccess {
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
            // Only RedGifs clips need the RedGifs sign-in.
            !prefs.signedIn && ids.any { OnlineSource.of(it.substringBefore(' ')) == OnlineSource.RedGifs } -> say("Sign in first.")
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

    /** Adds an online clip to the library and saves it (HD first). Pictures and 4chan's files go straight into the save folder. */
    fun saveOnline(clip: Clip, q: Quality? = null) {
        if (OnlineSource.keepsOwnFile(clip)) return saveOnlineFile(clip)
        viewModelScope.launch(Dispatchers.IO) {
            // A RedGifs clip found on Reddit has no video addresses until RedGifs is asked for them.
            val c = if (clip.hd == null && clip.sd == null) resolveOnline(clip) ?: return@launch say("Couldn't get this clip from RedGifs.") else clip
            library.addExtra(c)
            withContext(Dispatchers.Main) { if (q == null) download(c.id) else downloadQuality(c.id, q) }
        }
    }

    private fun saveOnlineFile(clip: Clip) {
        if (!hasFolder || library.folder() == null) return say("Choose a save folder first (Settings → Save folder).")
        if (clip.id in savingImages) return
        savingImages = savingImages + clip.id
        viewModelScope.launch(Dispatchers.IO) {
            val r = runCatching {
                val http = RedGifsApi.clientFor(getApplication<Application>().userAgent)
                // sex.com lists tags only on the picture's own page, and offers the real GIF behind a WebP.
                val sexCom = OnlineSource.of(clip.id) == OnlineSource.SexCom
                val tags = if (!sexCom || clip.tags.isNotEmpty()) clip.tags else
                    runCatching { SexComApi(http).tags(clip.id.removePrefix(OnlineSource.SexCom.prefix)) }
                        .onFailure { AppLog.w("No tags for ${clip.id}: ${it.message}") }.getOrDefault(emptyList())
                library.addOnlineFile(clip.copy(tags = tags), http, if (sexCom) SexComApi.files(clip) else listOfNotNull(clip.hd))
            }
            withContext(Dispatchers.Main) { savingImages = savingImages - clip.id }
            r.onSuccess { say("Saved") }.onFailure { e ->
                AppLog.e("Saving ${clip.id} failed: ${e.message}", e.takeUnless { it is HttpException })
                say("Couldn't save: ${DownloadWorker.reasonFor(e, OnlineSource.of(clip.id).label)}")
            }
        }
    }

    /**
     * Looks up a RedGifs clip that was found on Reddit (only its ID is known there) and puts the
     * full clip in place of the bare one in the player and the feed. Null if RedGifs can't be asked.
     */
    suspend fun resolveOnline(c: Clip): Clip? = withContext(Dispatchers.IO) {
        if (!prefs.signedIn) return@withContext null
        val fresh = runCatching { withRedGifs(getApplication()) { it.clip(c.id) } }
            .onFailure { AppLog.w("Could not get ${c.id} from RedGifs: ${it.message}") }.getOrNull() ?: return@withContext null
        val full = library.resolveNiches(fresh.copy(sources = c.sources, thumb = fresh.thumb ?: c.thumb,
            description = fresh.description.ifBlank { c.description }))
        withContext(Dispatchers.Main) { putOnline(full) }
        full
    }

    /** A fresh copy of a clip from its own site, for when its media addresses have expired. */
    suspend fun refreshClip(c: Clip): Clip? = withContext(Dispatchers.IO) {
        runCatching {
            when (OnlineSource.of(c.id)) {
                OnlineSource.RedGifs -> withRedGifs(getApplication()) { it.clip(c.id) }
                OnlineSource.FikFap -> FikFapApi(RedGifsApi.clientFor(getApplication<Application>().userAgent)).post(c.id.removePrefix(OnlineSource.FikFap.prefix))
                else -> null
            }
        }.getOrNull()?.let { it.copy(sources = c.sources) }?.also { fresh ->
            library.replaceClip(fresh)
            withContext(Dispatchers.Main) { putOnline(fresh) }
        }
    }

    /** Swaps in a newer copy of an online clip wherever the old one is shown. */
    private fun putOnline(c: Clip) {
        player?.let { p -> if (c.id in p.online) player = p.copy(online = p.online + (c.id to c)) }
        if (discover.items.any { it.id == c.id }) discover = discover.copy(items = discover.items.map { if (it.id == c.id) c else it })
    }

    // ---- Discover (online browsing) ---------------------------------------------------

    /** [startPage]: open a numbered feed at that page instead of the first. */
    fun openFeed(feed: Feed, order: OnlineOrder = discover.order, startPage: Int = 1) {
        val first = if (feed.numbered) startPage.coerceAtLeast(1) else 1
        discoverJob?.cancel()
        if (feed.source != discover.feed.source) {
            prefs.onlineSource = feed.source.name
            recents = prefs.recent(feed.source.name)
        }
        discover = discover.copy(feed = feed, order = order, items = emptyList(), page = first - 1, cursor = null, firstPage = first,
            pageStarts = emptyList(), pages = 0, hasMore = true, loading = false, error = null)
        loadMoreDiscover()
    }

    /** Opens the feed on screen at page [n] (numbered feeds only). */
    fun jumpToOnlinePage(n: Int) = openFeed(discover.feed, discover.order, startPage = n)

    fun changeDiscoverOrder(order: OnlineOrder) = openFeed(discover.feed, order)

    /** Switches the Online tab to another site. */
    fun changeSource(s: OnlineSource) { if (s != discover.feed.source) openFeed(Feed.home(s)) }

    // ---- Reddit account ----------------------------------------------------------------

    /** A new client ID means a different Reddit app: the old sign-in doesn't belong to it. */
    fun changeRedditClientId(v: String) {
        val id = v.trim()
        if (id == redditClientId) return
        prefs.redditSignOut()
        prefs.redditClientId = id.ifEmpty { null }
        redditClientId = id
        redditUser = null
        discover = discover.copy(subs = emptyList(), subsLoaded = false)
        AppLog.i(if (id.isEmpty()) "Reddit client ID removed" else "Reddit client ID set")
    }

    fun redditSignOut() {
        prefs.redditSignOut()
        redditUser = null
        discover = discover.copy(subs = emptyList(), subsLoaded = false)
        AppLog.i("Signed out of Reddit")
    }

    /** Subreddits the Reddit account has joined, for the shortcuts row. */
    fun loadSubreddits(force: Boolean = false) {
        if (sfw || prefs.redditRefresh == null) return
        if (discover.subsLoaded && !force) return
        viewModelScope.launch {
            val list = withContext(Dispatchers.IO) {
                runCatching { RedditApi(prefs, RedGifsApi.clientFor(getApplication<Application>().userAgent)).mySubreddits() }
                    .onFailure { AppLog.w("Could not read your subreddits: ${it.message}") }.getOrNull()
            }
            discover = discover.copy(subs = list ?: discover.subs, subsLoaded = true)
        }
    }

    /** Shows RedGifs' related clips for [clip] in Discover. */
    fun openRelated(clip: Clip) {
        val id = clip.copyOf ?: clip.id
        if (OnlineSource.of(id) != OnlineSource.RedGifs) return
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

    /** Searches [source] for [query] (on Reddit: opens that subreddit; on FikFap: that hashtag). */
    fun searchOnline(query: String, source: OnlineSource = discover.feed.source) {
        player = null
        openFeed(Feed.fromQuery(source, query))
        discoverRequests++
    }

    /** Opens a creator's (on Reddit: a user's) clips on [source]. */
    fun openPerformerOnline(name: String, source: OnlineSource = OnlineSource.RedGifs) {
        player = null
        openFeed(when (source) {
            OnlineSource.RedGifs -> Feed.Performer(name)
            OnlineSource.Reddit -> Feed.fromQuery(source, "u/$name")
            OnlineSource.FikFap -> Feed.FikFapUser(name)
            OnlineSource.SexCom -> Feed.SexSearch(name)
            OnlineSource.EroMe -> Feed.fromQuery(source, "@$name")
            OnlineSource.FourChan -> Feed.ChanThreads(name)
        })
        discoverRequests++
    }

    fun loadMoreDiscover() {
        val d = discover
        if (sfw) return // SFW mode: nothing is fetched from any site
        if (d.loading || !d.hasMore || d.feed.needsQuery) return
        val source = d.feed.source
        // Each source needs its own thing first: RedGifs a sign-in, Reddit a client ID, the others nothing.
        // The Online tab asks for it on screen; loading starts by itself once it's there.
        if (source == OnlineSource.RedGifs && !prefs.signedIn) return
        if (source == OnlineSource.Reddit && prefs.redditClientId.isNullOrBlank()) return
        discover = d.copy(loading = true, error = null)
        discoverJob = viewModelScope.launch {
            val next = d.page + 1
            val result = withContext(Dispatchers.IO) { runCatching { fetchPage(d, next) } }
            result.onSuccess { pg ->
                val seen = discover.items.mapTo(HashSet()) { it.id }
                val fresh = pg.clips.filter { it.id !in seen }
                discover = discover.copy(items = discover.items + fresh, page = next, cursor = pg.cursor,
                    // A page that added nothing isn't a place you can be.
                    pageStarts = if (fresh.isEmpty()) discover.pageStarts else discover.pageStarts + discover.items.size,
                    pages = if (pg.pages > 0) pg.pages else discover.pages,
                    // A page can come back empty (no playable posts) and still have more behind it.
                    hasMore = pg.hasMore && (pg.clips.isNotEmpty() || pg.cursor != null), loading = false)
                if (source != OnlineSource.RedGifs && next == 1 && pg.clips.isNotEmpty() && d.feed.query.isNotBlank()) {
                    prefs.addRecent(source.name, d.feed.query)
                    recents = prefs.recent(source.name)
                }
            }.onFailure { e ->
                if (e is kotlinx.coroutines.CancellationException) return@onFailure
                if (e is AuthException) needsSignIn = true
                if (e is VerificationRequiredException) { prefs.pendingVerification = e.subject; verifyNeeded = true }
                AppLog.e("Online failed (${source.label}, ${d.feed.title}): ${e.message}",
                    e.takeUnless { it is AuthException || it is HttpException || it is RedditAuthException || it is SiteBlockedException })
                discover = discover.copy(loading = false, error = friendly(e, source).replace("Sync failed", "Couldn't load"))
            }
        }
    }

    /** Page [next] of the feed on screen, from whichever site it belongs to. */
    private suspend fun fetchPage(d: DiscoverState, next: Int): OnlinePage {
        suspend fun redgifs(load: suspend (RedGifsApi, FeedExtras) -> OnlinePage): OnlinePage {
            val extras = FeedExtras()
            val page = withRedGifs(getApplication()) { load(it, extras) }
            library.learn(extras)
            return page.copy(clips = page.clips.map(library::resolveNiches))
        }
        val http by lazy { RedGifsApi.clientFor(getApplication<Application>().userAgent) }
        return when (val f = d.feed) {
            is Feed.Search -> redgifs { api, x -> api.searchClips(f.query, d.order, next, x) }
            is Feed.Niche -> redgifs { api, x -> api.nicheClips(f.id, d.order, next, x) }
            is Feed.Performer -> redgifs { api, x -> api.performerClips(f.name, d.order, next, x) }
            is Feed.Related -> redgifs { api, x -> api.relatedClips(f.clipId, next, x) }
            is Feed.RedditHome, is Feed.Subreddit, is Feed.RedditUser, is Feed.RedditSaved, is Feed.RedditUpvoted ->
                RedditApi(prefs, http).listing(f, d.order, d.cursor, next)
            is Feed.FikFapTag -> FikFapApi(http).tagPosts(f.tag, d.cursor, next)
            is Feed.FikFapUser -> FikFapApi(http).userPosts(f.name, d.cursor, next)
            is Feed.SexSearch -> SexComApi(http).search(f.query, next)
            is Feed.EromeSearch -> EromeApi(http).search(f.query, d.cursor, next)
            is Feed.EromeUser -> EromeApi(http).user(f.name, d.cursor, next)
            is Feed.ChanThreads -> FourChanApi(http).threads(f.query, d.cursor, next)
        }
    }

    fun loadFollowedNiches(force: Boolean = false) {
        if (sfw) return
        if (discover.nichesLoaded && !force) return
        if (!prefs.signedIn) return
        viewModelScope.launch {
            val list = withContext(Dispatchers.IO) {
                runCatching { withRedGifs(getApplication()) { it.followedNiches() } }
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
    fun changePlayerFill(v: Boolean) { prefs.playerFill = v; playerFill = v }

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

    private fun friendly(e: Throwable, source: OnlineSource = OnlineSource.RedGifs): String = when (e) {
        is AuthException -> "Your RedGifs session expired. Tap Renew to sign in again."
        is VerificationRequiredException -> "RedGifs wants a quick human check before continuing."
        is RedditAuthException -> e.message ?: "Reddit sign-in failed."
        is SiteBlockedException -> e.message ?: "${source.label} is blocking the app."
        is UnknownHostException -> "No internet connection."
        is SSLException -> "Secure connection failed. A network filter may be blocking ${source.label}."
        is HttpException -> when {
            e.code == 404 && source != OnlineSource.RedGifs -> "Not found on ${source.label}."
            e.code == 403 && source == OnlineSource.Reddit -> "Reddit won't show this (HTTP 403)."
            else -> "${source.label} returned an error (HTTP ${e.code}). See Log."
        }
        else -> "Sync failed: ${e.message ?: e.javaClass.simpleName}"
    }

    companion object {
        /** Refresh once per app launch, not on every screen rotation. */
        private var startupRefreshDone = false

        /** Set when the user presses the phone's volume keys: from then on this launch, the volume guard stays out of the way. */
        @Volatile var volumeOverride = false
    }
}
