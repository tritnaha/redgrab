package app.redgrab

import org.json.JSONArray
import org.json.JSONObject

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
    /** Clips that can still be saved: not on the phone, and not deleted on their site. */
    /** Clips waiting to be saved: not on the phone, and not deleted on their site or set aside. */
    val pending: List<Clip> get() = originals.filter { it.id !in saved && it.id !in dead }
    val newCount get() = originals.count { it.id !in saved && it.id !in dead }
    val savedCount get() = originals.count { it.id in saved }
}

/**
 * Single source of truth for clips, saved files, edits and history. Everything is stored in
 * the app's private storage: the database redgrab.db (see Db.kt) and, as image files, the
 * offline thumbnails (files/thumbs). The lists are read into memory once at start; every change
 * updates memory and writes just the affected rows.
 */
