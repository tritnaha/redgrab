package app.redgrab

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

class MainViewModel(app: Application) : AndroidViewModel(app) {

    val library = app.library
    internal val prefs = library.prefs
    val state = library.state
    val download = library.download

    /** One-off messages for the snackbar. */
    internal val _messages = Channel<String>(Channel.BUFFERED)
    val messages = _messages.receiveAsFlow()

    /** The Saved tab opens on what's actually on the phone. */
    var filter by mutableStateOf(Filter.Saved)
    /** Where from: likes, collections, synced, imported… */
    var origin by mutableStateOf(Origin.All)
    /** Progress text while a folder is being imported; null when idle. */
    var importStatus by mutableStateOf<String?>(null)
        internal set
    var adv by mutableStateOf(AdvFilters())
    var discover by mutableStateOf(DiscoverState(feed = Feed.home(
        startViewOf(prefs.startView).second ?: OnlineSource.entries.firstOrNull { it.name == prefs.onlineSource } ?: OnlineSource.RedGifs)))
        internal set
    /** Recently opened subreddits / hashtags / searches of the source on screen. */
    var recents by mutableStateOf(prefs.recent(discover.feed.source.name))
        internal set
    var redditClientId by mutableStateOf(prefs.redditClientId ?: "")
        internal set
    /** Reddit account name when signed in to Reddit, else null. */
    var redditUser by mutableStateOf(if (prefs.redditRefresh != null) prefs.redditUser ?: "" else null)
        internal set
    /** Online files being saved straight into the save folder right now (pictures, 4chan clips), by clip ID. */
    var savingImages by mutableStateOf<Set<String>>(emptySet())
        internal set
    /** Bumped to ask the UI to switch to the Discover tab (e.g. after tapping Related). */
    var discoverRequests by mutableStateOf(0)
        internal set
    internal var discoverJob: kotlinx.coroutines.Job? = null
    /** Layout of the clip grids: even grid or wall, and how many previews per row. */
    var previews by mutableStateOf(prefs.previews)
        internal set
    var previewUnsaved by mutableStateOf(prefs.previewUnsaved)
        internal set
    var previewCount by mutableStateOf(prefs.previewCount)
        internal set
    var previewCopies by mutableStateOf(prefs.previewCopies)
        internal set
    /** Bytes the preview copies take up (read when Settings asks). */
    var previewCopyBytes by mutableStateOf(0L)
        internal set
    /** How many previews may really play at once: the chosen number, or as many as the phone can decode. */
    val previewLimit: Int get() = if (previewCount == 0) app.redgrab.ui.maxPreviews else previewCount
    var playerFill by mutableStateOf(prefs.playerFill)
        internal set
    /**
     * Whether tiles may animate right now: switched on, not in SFW mode, nothing covering the
     * grid (player, editor, delete screen), and the phone isn't in battery saver.
     */
    val previewsActive: Boolean
        get() = previews && !sfw && player == null && editing == null && deleteRequest == null && !AppLock.locked && !powerSave()

    var wall by mutableStateOf(prefs.wall)
        internal set
    var columns by mutableStateOf(prefs.columns)
        internal set
    var autoSyncHours by mutableStateOf(prefs.autoSyncHours)
        internal set
    var syncWifiOnly by mutableStateOf(prefs.syncWifiOnly)
        internal set
    /** No internet right now: the Saved side keeps working, Online and syncing wait. */
    var offline by mutableStateOf(false)
        internal set
    /** Partial downloads in the save folder: count and total bytes (for Settings). */
    var partialInfo by mutableStateOf(0 to 0L)
        internal set
    var themeId by mutableStateOf(prefs.theme)
        internal set
    // "Start in SFW mode" is for opening the app after being away, not for coming straight back from
    // another app: Android may rebuild the screen in between, which used to switch SFW on each time.
    var sfw by mutableStateOf(if (prefs.sfwOnStart && awayLongEnough(prefs.lastLeftAt)) true.also { prefs.sfw = true } else prefs.sfw)
        internal set
    var sfwOnStart by mutableStateOf(prefs.sfwOnStart)
        internal set
    var volumeGuard by mutableStateOf(prefs.volumeGuard)
        internal set
    var volumeLimit by mutableStateOf(prefs.volumeLimit)
        internal set
    var deleteRequest by mutableStateOf<DeleteRequest?>(null)
        internal set
    /** Set when RedGifs wants a human check; the UI opens the verification screen. */
    var verifyNeeded by mutableStateOf(prefs.pendingVerification != null)
        internal set
    var sessionText by mutableStateOf(Session.describe(prefs))
        internal set
    var sessionState by mutableStateOf(Session.state(prefs))
        internal set
    var query by mutableStateOf("")
    var facet by mutableStateOf<Facet?>(null)
    var player by mutableStateOf<PlayerSession?>(null)
        internal set
    var playMuted by mutableStateOf(prefs.playMuted)
        internal set
    /** Set when both WhatsApp and WhatsApp Business are installed and the user must pick. */
    var shareChoice by mutableStateOf<Pair<List<Clip>, Map<String, String>>?>(null)
        internal set
    var sharing by mutableStateOf(false)
        internal set
    var selection by mutableStateOf<Set<String>>(emptySet())
        internal set
    var signedIn by mutableStateOf(prefs.signedIn)
        internal set
    var needsSignIn by mutableStateOf(false)
        internal set
    var folderLabel by mutableStateOf(prefs.folderLabel)
        internal set
    var hasFolder by mutableStateOf(prefs.folderUri != null)
        internal set
    var hd by mutableStateOf(prefs.hd)
        internal set
    var includeCollections by mutableStateOf(prefs.includeCollections)
        internal set
    var refreshOnStart by mutableStateOf(prefs.refreshOnStart)
        internal set
    var hiddenFromGallery by mutableStateOf(false)
        internal set
    var lastCrash by mutableStateOf(AppLog.takeLastCrash())
        internal set
    /** Clip open in the editor, if any. */
    var editing by mutableStateOf<Clip?>(null)
        internal set
    /** Clip IDs whose info (performer/category/tags) is being edited. */
    var editingInfo by mutableStateOf<List<String>?>(null)
        internal set

    internal var netCallback: android.net.ConnectivityManager.NetworkCallback? = null

    override fun onCleared() {
        netCallback?.let { cb ->
            runCatching { getApplication<Application>().getSystemService(android.net.ConnectivityManager::class.java)?.unregisterNetworkCallback(cb) }
        }
        super.onCleared()
    }

    // ---- updates and problem reports ---------------------------------------------------

    var autoUpdate by mutableStateOf(prefs.autoUpdate)
        internal set
    var githubToken by mutableStateOf(prefs.githubToken ?: "")
        internal set
    var sendingReport by mutableStateOf(false)
        internal set
    /** Whether the app's GitHub repository is public (then anyone can read a report sent there); null = not known. */
    var repoPublic by mutableStateOf<Boolean?>(null)
        internal set

    // ---- what's new ---------------------------------------------------------------------

    val changelog: List<ChangelogEntry> by lazy { Changelog.load(getApplication()) }
    /** Versions to show in "What's new" right now; null = nothing to show. */
    var whatsNew by mutableStateOf<List<ChangelogEntry>?>(null)
        internal set
    var notifyDone by mutableStateOf(prefs.notifyDone)
        internal set
    internal var renewing = false

    // ---- sync -----------------------------------------------------------------

    // ---- selection & downloads -------------------------------------------------

    // ---- deleting -------------------------------------------------------------------

    // ---- SFW, volume, theme, session -----------------------------------------------

    // ---- where you were ------------------------------------------------------------------

    /** The grids' scroll positions live here, so they survive going to another tab and back. */
    val savedGrid = androidx.compose.foundation.lazy.staggeredgrid.LazyStaggeredGridState()
    val onlineGrid = androidx.compose.foundation.lazy.staggeredgrid.LazyStaggeredGridState()
    /** The feed the Online grid was last scrolled for; it only jumps to the top when this changes. */
    var onlineGridFor: Any? = null
    /** True until the Saved grid has gone back to where it was when the app was last left. */
    var restoreSavedTop = true

    var backToTabs by mutableStateOf(prefs.backToTabs)
        internal set
    var startView by mutableStateOf(prefs.startView)
        internal set
    /** Mobile data may be used for syncing and saving many clips at once (off = those wait for Wi-Fi). */
    var mobileData by mutableStateOf(!prefs.syncWifiOnly)
        internal set
    // ---- layout ---------------------------------------------------------------------

    // ---- collections & auto-sync -----------------------------------------------------

    // ---- info editing & clip editor ---------------------------------------------

    // ---- player & sharing --------------------------------------------------------

    /** An online clip waiting for "which category?" before it is saved. */
    data class SaveRequest(val clip: Clip, val quality: Quality?)
    var saveRequest by mutableStateOf<SaveRequest?>(null)
        internal set
    var askCategory by mutableStateOf(prefs.askCategory)
        internal set
    /** Clips whose save was asked for in this run and hasn't finished: their Save button shows the progress. */
    var saveRequested by mutableStateOf<Set<String>>(emptySet())
        internal set
    /** How far a file saved directly (pictures, 4chan) has come: 0..1, or -1 while the size isn't known. */
    var savingFraction by mutableStateOf<Map<String, Float>>(emptyMap())
        internal set

    // ---- Discover (online browsing) ---------------------------------------------------

    internal var emptyPages = 0

    /** RedGifs refused "For you" or had nothing in it: the general feed is the home for the rest of this run. */
    internal var forYouUnavailable by mutableStateOf(false)
    /** You follow no niches (yet), so there is nothing to mix: "For you" is the home until you follow one. */
    internal var noNiches by mutableStateOf(false)

    // ---- what to show: content preferences ------------------------------------------------

    var contentPrefs by mutableStateOf(prefs.contentPrefs)
        internal set

    /** Asked once: the first time the app runs, or the first time after the update that brought the filter. */
    var askContentPrefs by mutableStateOf(!prefs.contentPrefsAsked)
        internal set
    var pruneCollections by mutableStateOf(prefs.pruneCollections)
        internal set
    // ---- niches: browse, search, follow -----------------------------------------------------

    /** The niche list on screen; null = not open. */
    data class NicheBrowse(val query: String = "", val category: String? = null, val order: NicheOrder = NicheOrder.Subscribers,
                           val items: List<NicheInfo> = emptyList(), val page: Int = 0, val pages: Int = 1, val total: Int = 0,
                           val loading: Boolean = false, val error: String? = null, val categories: List<String> = emptyList())
    var nicheBrowse by mutableStateOf<NicheBrowse?>(null)
        internal set
    /** The niche list is showing. (What it held stays when it is closed, so you come back to the same place.) */
    var nicheBrowserOpen by mutableStateOf(false)
        internal set
    internal var nicheJob: kotlinx.coroutines.Job? = null
    /** Niches whose follow or unfollow is on its way to RedGifs. */
    var followBusy by mutableStateOf<Set<String>>(emptySet())
        internal set

    /** Tags you follow (kept in the app; they are part of "My feed"). */
    var followedTags by mutableStateOf(prefs.followedTags)
        internal set
    // ---- suggestions while typing in the Online search box ------------------------------------

    /** Every tag and niche on RedGifs, kept on the phone (see SearchIndex). */
    internal val searchIndex by lazy { SearchIndex(java.io.File(getApplication<Application>().filesDir, "search-index.tsv")) }
    internal var indexJob: kotlinx.coroutines.Job? = null

    var suggestions by mutableStateOf<List<Suggestion>>(emptyList())
        internal set
    internal var suggestJob: kotlinx.coroutines.Job? = null

    // ---- Reddit account ----------------------------------------------------------------

    /** Where the page indicator sits, as dragged (pixels from its usual place). */
    var pillOffset by mutableStateOf(androidx.compose.ui.geometry.Offset(prefs.pillX, prefs.pillY))
        internal set
    // ---- settings --------------------------------------------------------------

    var appLock by mutableStateOf(prefs.appLock)
        internal set
    var appLockAfterSec by mutableStateOf(prefs.appLockAfterSec)
        internal set
    var startSyncHours by mutableStateOf(prefs.startSyncHours)
        internal set
    // Start-up work. This block is deliberately the last thing in the class: Kotlin sets up a class's
    // properties from top to bottom, so anything here may use every property above it. (In 13.6 it sat
    // further up and used the changelog before that existed, which stopped the app on the first start
    // after an update.)
    /** What you follow on the sites that have no following of their own, by site. */
    var siteFollows by mutableStateOf(OnlineSource.SITES.associateWith { prefs.follows(it.name) })
        internal set
    /** 4chan's boards, once asked for. */
    var chanBoards by mutableStateOf<List<FourChanApi.Board>>(emptyList())
        internal set

    init {
        refreshFolderInfo()
        // When a download run ends, no Save button is left showing progress.
        viewModelScope.launch {
            var was = false
            library.download.collect { p -> if (was && !p.running && !p.waiting) saveRequested = emptySet(); was = p.running }
        }
        // Re-check the save folder on every launch so files added, moved or deleted outside
        // the app (or saved by older versions) are reflected without a sync.
        watchNetwork()
        // Housekeeping off the main thread: re-check the folder, drop stale partial downloads,
        // and make sure every saved clip has a thumbnail stored on the phone.
        rescanFolder(force = false)
        lookForUpdate()
        // Showing what's new must never be able to stop the app from starting.
        runCatching { greetAfterUpdate() }.onFailure { AppLog.e("What's new couldn't be prepared", it) }
        if (!startupRefreshDone && prefs.refreshOnStart && prefs.signedIn && !syncIsFresh(prefs.lastSynced, prefs.startSyncHours)) {
            startupRefreshDone = true
            // Offline at launch is normal, not an error: just show what's on the phone.
            if (isOnline()) refresh(quiet = true) else offline = true
        }
    }

    companion object {
        /** Refresh once per app launch, not on every screen rotation. */
        private var startupRefreshDone = false

        /** Set when the user presses the phone's volume keys: from then on this launch, the volume guard stays out of the way. */
        @Volatile var volumeOverride = false
    }
}
