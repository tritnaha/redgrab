package app.redgrab

import android.content.Context
import android.net.Uri
import androidx.core.content.edit

class Prefs(context: Context) : RedditStore {
    private val sp = context.getSharedPreferences("redgrab", Context.MODE_PRIVATE)

    var token: String?
        get() = sp.getString("token", null)
        set(v) = sp.edit { putString("token", v) }

    /** User agent of the WebView the token came from; all requests reuse it. */
    var userAgent: String?
        get() = sp.getString("ua", null)
        set(v) = sp.edit { putString("ua", v) }

    /** Tree or child-document URI of the save folder (Storage Access Framework). */
    var folderUri: Uri?
        get() = sp.getString("folder", null)?.let(Uri::parse)
        set(v) = sp.edit { putString("folder", v?.toString()) }

    /** Human-readable folder path for display. */
    var folderLabel: String?
        get() = sp.getString("folderLabel", null)
        set(v) = sp.edit { putString("folderLabel", v) }

    var hd: Boolean
        get() = sp.getBoolean("hd", true)
        set(v) = sp.edit { putBoolean("hd", v) }

    var includeCollections: Boolean
        get() = sp.getBoolean("collections", true)
        set(v) = sp.edit { putBoolean("collections", v) }

    var refreshOnStart: Boolean
        get() = sp.getBoolean("refreshOnStart", true)
        set(v) = sp.edit { putBoolean("refreshOnStart", v) }

    /** Start clips muted in the player. */
    var playMuted: Boolean
        get() = sp.getBoolean("playMuted", true)
        set(v) = sp.edit { putBoolean("playMuted", v) }

    /** Epoch millis of the last successful refresh, 0 = never. */
    var lastSynced: Long
        get() = sp.getLong("lastSynced", 0)
        set(v) = sp.edit { putLong("lastSynced", v) }

    /** Expiry (epoch seconds) read from the token itself; 0 = unknown. */
    var tokenExp: Long
        get() = sp.getLong("tokenExp", 0)
        set(v) = sp.edit { putLong("tokenExp", v) }

    /** Subject of a human check RedGifs asked for and the user hasn't answered yet. */
    var pendingVerification: String?
        get() = sp.getString("verifySubject", null)
        set(v) = sp.edit { putString("verifySubject", v) }

    /** A download run lost its connection and is waiting to continue with the saved queue. */
    var downloadWaiting: Boolean
        get() = sp.getBoolean("downloadWaiting", false)
        set(v) = sp.edit { putBoolean("downloadWaiting", v) }

    /** True when a download run was paused by a human check and should continue after it. */
    var resumeAfterVerify: Boolean
        get() = sp.getBoolean("resumeAfterVerify", false)
        set(v) = sp.edit { putBoolean("resumeAfterVerify", v) }

    /** SFW mode: blur previews, block playback, mute. */
    var sfw: Boolean
        get() = sp.getBoolean("sfw", false)
        set(v) = sp.edit { putBoolean("sfw", v) }

    /** Turn SFW mode on every time the app starts. */
    var sfwOnStart: Boolean
        get() = sp.getBoolean("sfwOnStart", false)
        set(v) = sp.edit { putBoolean("sfwOnStart", v) }

    /** Lower loud volume when playing through the phone's own speaker. */
    var volumeGuard: Boolean
        get() = sp.getBoolean("volumeGuard", true)
        set(v) = sp.edit { putBoolean("volumeGuard", v) }

    /** Max speaker volume (percent of max) the guard allows. */
    var volumeLimit: Int
        get() = sp.getInt("volumeLimit", 40)
        set(v) = sp.edit { putInt("volumeLimit", v) }

    /** Moving previews in the grids. */
    var previews: Boolean
        get() = sp.getBoolean("previews", true)
        set(v) = sp.edit { putBoolean("previews", v) }

    /** Also animate clips that aren't saved, by streaming them (uses data). */
    var previewUnsaved: Boolean
        get() = sp.getBoolean("previewUnsaved", false)
        set(v) = sp.edit { putBoolean("previewUnsaved", v) }

    /** How many previews may play at the same time: 2, 4 or 6, or 0 for everything on screen. */
    /** Ask which category an online clip goes into when saving it. */
    var askCategory: Boolean
        get() = sp.getBoolean("askCategory", true)
        set(v) = sp.edit { putBoolean("askCategory", v) }
    /** Make and play small preview copies of saved clips (see PreviewMaker). */
    var previewCopies: Boolean
        get() = sp.getBoolean("previewCopies", true)
        set(v) = sp.edit { putBoolean("previewCopies", v) }
    var previewCount: Int
        get() = sp.getInt("previewCount", 0)
        set(v) = sp.edit { putInt("previewCount", v.coerceIn(0, 6)) }

    /**
     * In the player, upright clips fill the whole screen (edges cropped). Off (the default): a
     * clip opens as a larger view over the grid, which stays visible around it.
     */
    var playerFill: Boolean
        get() = sp.getBoolean("playerFill", false)
        set(v) = sp.edit { putBoolean("playerFill", v) }

    /** Library layout: false = even grid, true = wall (each preview keeps its own shape). */
    var wall: Boolean
        get() = sp.getBoolean("wall", false)
        set(v) = sp.edit { putBoolean("wall", v) }

    /** Previews per row (2..6). */
    var columns: Int
        get() = sp.getInt("columns", 3)
        set(v) = sp.edit { putInt("columns", v.coerceIn(2, 6)) }

    /** Background check for new clips in synced collections: every N hours, 0 = off. */
    var autoSyncHours: Int
        get() = sp.getInt("autoSyncHours", 0)
        set(v) = sp.edit { putInt("autoSyncHours", v) }

    /** Background sync only on Wi-Fi (unmetered). */
    var syncWifiOnly: Boolean
        get() = sp.getBoolean("syncWifiOnly", true)
        set(v) = sp.edit { putBoolean("syncWifiOnly", v) }

    /** Selected design theme ID. */
    var theme: String?
        get() = sp.getString("theme", null)
        set(v) = sp.edit { putString("theme", v) }

    val signedIn: Boolean get() = token != null && userAgent != null

    // ---- Reddit: the user's own client ID and the tokens from signing in ----

    /** Client ID of the "installed app" the user created at reddit.com/prefs/apps. */
    override var redditClientId: String?
        get() = sp.getString("redditClientId", null)
        set(v) = sp.edit { putString("redditClientId", v) }

    /** Short-lived access token (the user's, or an app-only one when not signed in). */
    override var redditAccess: String?
        get() = sp.getString("redditAccess", null)
        set(v) = sp.edit { putString("redditAccess", v) }

    /** When [redditAccess] stops working, epoch seconds. */
    override var redditAccessExp: Long
        get() = sp.getLong("redditAccessExp", 0)
        set(v) = sp.edit { putLong("redditAccessExp", v) }

    /** Lasting token from signing in; null = not signed in to Reddit. */
    override var redditRefresh: String?
        get() = sp.getString("redditRefresh", null)
        set(v) = sp.edit { putString("redditRefresh", v) }

    override var redditUser: String?
        get() = sp.getString("redditUser", null)
        set(v) = sp.edit { putString("redditUser", v) }

    fun redditSignOut() = sp.edit { remove("redditAccess"); remove("redditAccessExp"); remove("redditRefresh"); remove("redditUser") }

    // ---- updates and reports through GitHub ----

    /** A GitHub token that can read the app's repository (needed while it is private) and file reports. */
    var githubToken: String?
        get() = sp.getString("githubToken", null)
        set(v) = sp.edit { putString("githubToken", v) }

    /** Look for new releases in the background and install them when the app isn't in use. */
    var autoUpdate: Boolean
        get() = sp.getBoolean("autoUpdate", true)
        set(v) = sp.edit { putBoolean("autoUpdate", v) }

    /** When GitHub was last asked for the latest release, epoch millis. */
    var lastUpdateCheck: Long
        get() = sp.getLong("lastUpdateCheck", 0)
        set(v) = sp.edit { putLong("lastUpdateCheck", v) }

    /** Where the page indicator was dragged to, in pixels from its usual place (bottom right). */
    var pillX: Float
        get() = sp.getFloat("pillX", 0f)
        set(v) = sp.edit { putFloat("pillX", v) }
    var pillY: Float
        get() = sp.getFloat("pillY", 0f)
        set(v) = sp.edit { putFloat("pillY", v) }

    // ---- Online tab ----

    /** The source last shown on the Online tab ([OnlineSource] name). */
    var onlineSource: String?
        get() = sp.getString("onlineSource", null)
        set(v) = sp.edit { putString("onlineSource", v) }

    /** What was last opened on a source (subreddits, hashtags, searches), newest first. */
    fun recent(source: String): List<String> = sp.getString("recent.$source", "")!!.split('\n').filter { it.isNotBlank() }

    fun addRecent(source: String, query: String) {
        val q = query.trim().replace('\n', ' ')
        if (q.isEmpty()) return
        sp.edit { putString("recent.$source", (listOf(q) + recent(source).filter { !it.equals(q, ignoreCase = true) }).take(12).joinToString("\n")) }
    }

    fun signOut() = sp.edit { remove("token"); remove("tokenExp") }

    /** True once the JSON/text files of version 10 and earlier have been moved into the database. */
    var legacyImported: Boolean
        get() = sp.getBoolean("legacyImported", false)
        set(v) = sp.edit { putBoolean("legacyImported", v) }
}
