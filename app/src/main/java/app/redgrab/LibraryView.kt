package app.redgrab

/** What to show by state. New = not on the phone yet; Unseen = arrived in a sync and not opened yet. */
enum class Filter { All, New, Saved, HD, SD, Edited, Failed, Dead, Unseen }

/** Where a clip comes from. */
enum class Origin(val label: String) {
    // "From Online": where the clip was found, not where it is. Saved ones are on the phone like any other.
    All("All"), Liked("Liked"), Collections("Collections"), Synced("Synced"), Imported("Imported"), Online("From Online");

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
            Filter.Failed -> c.id in s.failed
            Filter.Dead -> c.id in s.dead
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

/**
 * Whether the last sync is recent enough that opening the app shouldn't sync again: the library on
 * the phone is shown as it is. [maxAgeHours] 0 = never fresh (sync every time). A clock set back
 * (a sync "in the future") doesn't count as fresh.
 */
fun syncIsFresh(lastSynced: Long, maxAgeHours: Int, now: Long = System.currentTimeMillis()): Boolean =
    maxAgeHours > 0 && lastSynced in 1..now && now - lastSynced < maxAgeHours * 3_600_000L

/** Whether the app has been out of use long enough (five minutes) for "start in SFW mode" to apply. Never left, or a clock set back, counts as long enough. */
fun awayLongEnough(lastLeftAt: Long, now: Long = System.currentTimeMillis()): Boolean =
    lastLeftAt <= 0 || now < lastLeftAt || now - lastLeftAt > 5 * 60_000L

/** Which tab and site the app opens on, from the "Open on" setting: (tab, site or null for the one used last). */
fun startViewOf(setting: String): Pair<Int, OnlineSource?> = when {
    setting == "online" -> 4 to null
    setting.startsWith("online:") -> 4 to OnlineSource.entries.firstOrNull { it.name == setting.removePrefix("online:") }
    else -> 0 to null
}

/** "My niches" was asked for, but you follow none. */
class NoNichesException : java.io.IOException("You follow no niches yet")

/** What the delete screen is asking about. */
data class DeleteRequest(val originals: List<Clip>, val copies: List<Clip>, val selectedCopies: Set<String>)

/** What the full-screen player is showing: a fixed list of clip IDs and the current one. */
data class PlayerSession(val ids: List<String>, val index: Int,
                         /** Clips from online browsing that aren't in the library, by ID. */
                         val online: Map<String, Clip> = emptyMap())
