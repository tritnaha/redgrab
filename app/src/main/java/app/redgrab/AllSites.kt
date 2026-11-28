package app.redgrab

import org.json.JSONArray
import org.json.JSONObject

/**
 * "All sites": one feed made from every site that is set up, a few clips from each in turn.
 *
 * With nothing typed it is your feed across the sites: the RedGifs niches and tags you follow,
 * Reddit's front page (the subreddits you joined, when signed in), and whatever you follow on
 * the other sites (hashtags, searches, boards). A site where you follow nothing shows what it
 * offers by itself, if it offers anything. With something typed, every site is asked for the
 * same words. Each list keeps its own place; those places are carried together in one cursor.
 */
object AllSites {
    /** One list that goes into the mix: [key] names it in the cursor (the site, plus what is followed there). */
    data class Sub(val key: String, val feed: Feed)

    /** How many of a site's follows go into one page; the next page goes on with the next ones. */
    const val FOLLOWS_PER_PAGE = 2

    /**
     * The lists to ask for [page]. [personal] = you follow RedGifs niches or tags, so its mixed
     * feed can be used when nothing is typed. [follows]: what you follow on the other sites.
     */
    fun feeds(query: String, redGifs: Boolean, reddit: Boolean, personal: Boolean,
              follows: Map<OnlineSource, List<String>> = emptyMap(), page: Int = 1): List<Sub> {
        val q = query.trim()
        return OnlineSource.SITES.flatMap { s ->
            val name = s.name
            when {
                s == OnlineSource.RedGifs -> if (!redGifs) emptyList() else listOf(Sub(name, if (q.isEmpty()) (if (personal) Feed.MyNiches else Feed.Search("")) else Feed.Search(q)))
                s == OnlineSource.Reddit -> if (!reddit) emptyList() else listOf(Sub(name, Feed.fromQuery(s, q.replace(" ", ""))))
                q.isNotEmpty() -> listOf(Sub(name, Feed.fromQuery(s, if (s == OnlineSource.FikFap) q.replace(" ", "") else q))) // a hashtag has no spaces
                else -> {
                    val mine = follows[s].orEmpty()
                    if (mine.isEmpty()) listOf(Sub(name, Feed.home(s)))
                    // A couple of the things you follow there each page, taking turns.
                    else (0 until minOf(FOLLOWS_PER_PAGE, mine.size)).map { i -> mine[((page - 1).coerceAtLeast(0) * FOLLOWS_PER_PAGE + i) % mine.size] }
                        .distinct().map { Sub("$name|$it", Feed.fromQuery(s, it)) }
                }
            }
        }.filter { !it.feed.needsQuery }
    }

    /** Where each site's list has got to ([cursors], by site name) and which sites have run out ([done]). */
    data class Place(val cursors: Map<String, String> = emptyMap(), val done: Set<String> = emptySet())

    fun encode(p: Place): String = JSONObject().put("c", JSONObject(p.cursors as Map<*, *>)).put("d", JSONArray(p.done.toList())).toString()

    fun decode(cursor: String?): Place = runCatching {
        val j = JSONObject(cursor ?: return Place())
        val c = j.optJSONObject("c") ?: JSONObject()
        Place(c.keys().asSequence().associateWith { c.optString(it) }, j.optJSONArray("d")?.strings()?.toSet() ?: emptySet())
    }.getOrDefault(Place())
}
