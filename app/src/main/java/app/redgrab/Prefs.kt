package app.redgrab

import android.content.Context
import android.net.Uri
import androidx.core.content.edit
import kotlin.properties.ReadWriteProperty
import kotlin.reflect.KProperty

/**
 * The app's settings and small bits of remembered state. Each is one line: the name it is stored
 * under and what it is when nothing is stored. Sign-in secrets go to [SecretStore] instead.
 */
class Prefs(context: Context) : RedditStore {
    private val sp = context.getSharedPreferences("redgrab", Context.MODE_PRIVATE)

    /** Sign-in secrets are kept encrypted, apart from the ordinary settings. */
    private val secrets = SecretStore.open(context).also { s ->
        // Versions before 13.5 kept them with the settings: move them over once.
        val moved = s.adopt(sp, SECRET_NAMES)
        if (moved > 0) AppLog.i("Moved $moved stored sign-in(s) into encrypted storage")
    }

    // ---- how a setting is stored ----

    private fun <T> stored(get: () -> T, set: (T) -> Unit) = object : ReadWriteProperty<Any?, T> {
        override fun getValue(thisRef: Any?, property: KProperty<*>): T = get()
        override fun setValue(thisRef: Any?, property: KProperty<*>, value: T) = set(value)
    }
    private fun bool(key: String, default: Boolean) = stored({ sp.getBoolean(key, default) }) { v -> sp.edit { putBoolean(key, v) } }
    /** [within]: values written are kept inside this range. */
    private fun int(key: String, default: Int, within: IntRange? = null) =
        stored({ sp.getInt(key, default) }) { v -> sp.edit { putInt(key, if (within != null) v.coerceIn(within) else v) } }
    private fun long(key: String) = stored({ sp.getLong(key, 0) }) { v -> sp.edit { putLong(key, v) } }
    private fun float(key: String) = stored({ sp.getFloat(key, 0f) }) { v -> sp.edit { putFloat(key, v) } }
    private fun text(key: String) = stored<String?>({ sp.getString(key, null) }) { v -> sp.edit { putString(key, v) } }
    private fun secret(key: String) = stored<String?>({ secrets[key] }) { v -> secrets[key] = v }

    // ---- RedGifs sign-in ----

    /** The RedGifs session token. */
    var token: String? by secret("token")
    /** User agent of the WebView the token came from; all requests reuse it. */
    var userAgent: String? by text("ua")
    /** Expiry (epoch seconds) read from the token itself; 0 = unknown. */
    var tokenExp: Long by long("tokenExp")
    /** Subject of a human check RedGifs asked for and the user hasn't answered yet. */
    var pendingVerification: String? by text("verifySubject")
    val signedIn: Boolean get() = token != null && userAgent != null
    fun signOut() { secrets["token"] = null; sp.edit { remove("tokenExp") } }

    // ---- save folder and downloads ----

    /** Tree or child-document URI of the save folder (Storage Access Framework). */
    var folderUri: Uri?
        get() = sp.getString("folder", null)?.let(Uri::parse)
        set(v) = sp.edit { putString("folder", v?.toString()) }
    /** Human-readable folder path for display. */
    var folderLabel: String? by text("folderLabel")
    var hd: Boolean by bool("hd", true)
    var includeCollections: Boolean by bool("collections", true)
    /** A download run lost its connection and is waiting to continue with the saved queue. */
    var downloadWaiting: Boolean by bool("downloadWaiting", false)
    /** True when a download run was paused by a human check and should continue after it. */
    var resumeAfterVerify: Boolean by bool("resumeAfterVerify", false)
    /** Ask which category an online clip goes into when saving it. */
    var askCategory: Boolean by bool("askCategory", true)
    /** Post a notification when a download run finishes or sync needs you. (The one shown while saving is required by Android.) */
    var notifyDone: Boolean by bool("notifyDone", true)
    /** A clip RedGifs says is deleted is also taken out of your RedGifs collections, so it stops coming back with every sync. */
    var pruneCollections: Boolean by bool("pruneCollections", true)

    // ---- syncing ----

    var refreshOnStart: Boolean by bool("refreshOnStart", true)
    /** Opening the app syncs only when the last sync is older than this many hours; 0 = every time. */
    var startSyncHours: Int by int("startSyncHours", 6, within = 0..168)
    /** Epoch millis of the last successful refresh, 0 = never. */
    var lastSynced: Long by long("lastSynced")
    /** Background check for new clips in synced collections: every N hours, 0 = off. */
    var autoSyncHours: Int by int("autoSyncHours", 12)
    /** Background sync only on Wi-Fi (unmetered). */
    var syncWifiOnly: Boolean by bool("syncWifiOnly", true)
    /** When everything was last read from RedGifs (epoch millis); quick syncs in between only fetch what is new. */
    var lastFullSync: Long by long("lastFullSync")
    /** How many likes RedGifs reported at the last sync. */
    var likedSiteTotal: Int by int("likedSiteTotal", 0)
    /** For each collection, the number of clips RedGifs reported when it was last read. */
    var collectionCounts: Map<String, Int>
        get() = runCatching { org.json.JSONObject(sp.getString("collectionCounts", "{}") ?: "{}").let { j -> j.keys().asSequence().associateWith { j.optInt(it) } } }.getOrDefault(emptyMap())
        set(v) = sp.edit { putString("collectionCounts", org.json.JSONObject(v as Map<*, *>).toString()) }
    /** When the save folder was last listed. */
    var lastFolderScan: Long by long("lastFolderScan")
    /** True once the JSON/text files of version 10 and earlier have been moved into the database. */
    var legacyImported: Boolean by bool("legacyImported", false)

    // ---- viewing ----

    /** Start clips muted in the player. */
    var playMuted: Boolean by bool("playMuted", true)
    /** SFW mode: blur previews, block playback, mute. */
    var sfw: Boolean by bool("sfw", false)
    /** Turn SFW mode on every time the app starts. */
    var sfwOnStart: Boolean by bool("sfwOnStart", false)
    /** Lower loud volume when playing through the phone's own speaker. */
    var volumeGuard: Boolean by bool("volumeGuard", true)
    /** Max speaker volume (percent of max) the guard allows. */
    var volumeLimit: Int by int("volumeLimit", 40)
    /** Moving previews in the grids. */
    var previews: Boolean by bool("previews", true)
    /** Also animate clips that aren't saved, by streaming them (uses data). */
    var previewUnsaved: Boolean by bool("previewUnsaved", false)
    /** Make and play small preview copies of saved clips (see PreviewMaker). */
    var previewCopies: Boolean by bool("previewCopies", true)
    /** How many previews may play at the same time: 2, 4 or 6, or 0 for everything on screen. */
    var previewCount: Int by int("previewCount", 0, within = 0..6)
    /**
     * In the player, upright clips fill the whole screen (edges cropped). Off (the default): a
     * clip opens as a larger view over the grid, which stays visible around it.
     */
    var playerFill: Boolean by bool("playerFill", false)
    /** Library layout: false = even grid, true = wall (each preview keeps its own shape). */
    var wall: Boolean by bool("wall", false)
    /** Previews per row (2..6). */
    var columns: Int by int("columns", 3, within = 2..6)
    /** Selected design theme ID. */
    var theme: String? by text("theme")
    /** Where the page indicator was dragged to, in pixels from its usual place (bottom right). */
    var pillX: Float by float("pillX")
    var pillY: Float by float("pillY")

    // ---- navigation and coming back ----

    /** When the app was last left (epoch millis); "start in SFW mode" only counts after a real absence. */
    var lastLeftAt: Long by long("lastLeftAt")
    /** The clip at the top of the Saved grid when the app was left, to come back to. */
    var savedTop: String? by text("savedTop")
    /** Back goes to the tab you were on before; off = Back leaves the app from any tab. */
    var backToTabs: Boolean by bool("backToTabs", true)
    /** What the app opens on: "saved", "online" (the site used last) or "online:<site>". */
    var startView: String
        get() = sp.getString("startView", "saved") ?: "saved"
        set(v) = sp.edit { putString("startView", v) }
    /** The build whose "What's new" has been shown; 0 = none yet. */
    var lastSeenBuild: Int by int("lastSeenBuild", 0)

    // ---- Online tab ----

    /** The source last shown on the Online tab ([OnlineSource] name). */
    var onlineSource: String? by text("onlineSource")

    /** What was last opened on a source (subreddits, hashtags, searches), newest first. */
    fun recent(source: String): List<String> = sp.getString("recent.$source", "")!!.split('\n').filter { it.isNotBlank() }

    fun addRecent(source: String, query: String) {
        val q = query.trim().replace('\n', ' ')
        if (q.isEmpty()) return
        sp.edit { putString("recent.$source", (listOf(q) + recent(source).filter { !it.equals(q, ignoreCase = true) }).take(12).joinToString("\n")) }
    }

    /** A move or export that hasn't finished: "kind\nfolder\nlabel" (see StorageJob); null = none. */
    var storageJob: String? by text("storageJob")

    /** Where and when plain copies were last exported to ("Movies/backup · 2026-11-28"); null = never. */
    var lastExport: String? by text("lastExport")

    /** What you follow on a site that has no following of its own (hashtags, searches, boards). Kept in the app, newest first. */
    fun follows(source: String): List<String> = sp.getString("follow.$source", "")!!.split('\n').filter { it.isNotBlank() }

    fun setFollows(source: String, list: List<String>) =
        sp.edit { putString("follow.$source", list.map { it.trim().replace('\n', ' ') }.filter { it.isNotEmpty() }.distinctBy { it.lowercase() }.take(40).joinToString("\n")) }

    /** Which kinds of content the Online tab shows (see ContentPrefs); gay and trans off unless you choose. */
    var contentPrefs: Set<String>
        get() = sp.getString("contentPrefs", null)?.split(',')?.filterTo(LinkedHashSet()) { it in ContentPrefs.ALL } ?: ContentPrefs.DEFAULT
        set(v) = sp.edit { putString("contentPrefs", v.filter { it in ContentPrefs.ALL }.joinToString(",")) }
    /** The "what do you want to see?" question has been answered (asked once: on first use, or after the update that brought it). */
    var contentPrefsAsked: Boolean by bool("contentPrefsAsked", false)

    /** Tags you follow. RedGifs has no such thing on the account, so the list lives in the app. */
    var followedTags: List<String>
        get() = sp.getString("followedTags", null)?.split('\n')?.filter { it.isNotBlank() } ?: emptyList()
        set(v) = sp.edit { putString("followedTags", v.map { it.trim().replace('\n', ' ') }.filter { it.isNotEmpty() }.distinctBy { it.lowercase() }.joinToString("\n")) }
    /** When the lists behind the search suggestions (every tag and niche on RedGifs) were last read (epoch millis). */
    var searchIndexAt: Long by long("searchIndexAt")

    // ---- Reddit: a client ID and the tokens from signing in ----

    /** Client ID of the "installed app" created at reddit.com/prefs/apps: the one entered here, else the one the build carries. */
    override var redditClientId: String?
        get() = sp.getString("redditClientId", null) ?: BuildConfig.REDDIT_CLIENT_ID.ifEmpty { null }
        set(v) = sp.edit { putString("redditClientId", v) }
    /** Short-lived access token (the user's, or an app-only one when not signed in). */
    override var redditAccess: String? by secret("redditAccess")
    /** When [redditAccess] stops working, epoch seconds. */
    override var redditAccessExp: Long by long("redditAccessExp")
    /** Lasting token from signing in; null = not signed in to Reddit. */
    override var redditRefresh: String? by secret("redditRefresh")
    override var redditUser: String? by text("redditUser")

    fun redditSignOut() {
        secrets["redditAccess"] = null; secrets["redditRefresh"] = null
        sp.edit { remove("redditAccessExp"); remove("redditUser") }
    }

    // ---- updates and reports through GitHub ----

    /** A GitHub token: needed for updates only while the repository is private, and to file reports from the app. */
    var githubToken: String? by secret("githubToken")
    /** Look for new releases in the background and install them when the app isn't in use. */
    var autoUpdate: Boolean by bool("autoUpdate", true)
    /** When GitHub was last asked for the latest release, epoch millis. */
    var lastUpdateCheck: Long by long("lastUpdateCheck")

    // ---- app lock ----

    /** Ask for the phone's own unlock (fingerprint, face, PIN, pattern, password) before showing the app. */
    var appLock: Boolean by bool("appLock", false)
    /** Lock again after the app has been out of sight this many seconds; 0 = straight away. */
    var appLockAfterSec: Int by int("appLockAfterSec", 0, within = 0..3600)

    private companion object {
        /** What must never be stored readable: anything that lets someone act as you on a site. */
        val SECRET_NAMES = listOf("token", "redditAccess", "redditRefresh", "githubToken")
    }
}
