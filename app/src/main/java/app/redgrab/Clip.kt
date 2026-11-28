package app.redgrab

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
