package app.redgrab

import org.json.JSONArray
import org.json.JSONObject

/**
 * "All sites": one feed made from every site that is set up, a few clips from each in turn.
 *
 * With nothing typed it shows what each site offers without a search (your RedGifs feed, the
 * Reddit front page, 4chan's threads); sites that only work with a search (FikFap, sex.com,
 * EroMe) join in once something is typed, and then every site is asked for the same words.
 * Each site keeps its own place in its own list; those places are carried together in one
 * cursor.
 */
object AllSites {
    /**
     * The feed to ask each site for. [personal] = you follow RedGifs niches or tags, so its mixed
     * feed can be used when nothing is typed.
     */
    fun feeds(query: String, redGifs: Boolean, reddit: Boolean, personal: Boolean): List<Feed> {
        val q = query.trim()
        return OnlineSource.SITES.mapNotNull { s ->
            when (s) {
                OnlineSource.RedGifs -> if (!redGifs) null else if (q.isEmpty()) (if (personal) Feed.MyNiches else Feed.Search("")) else Feed.Search(q)
                OnlineSource.Reddit -> if (!reddit) null else Feed.fromQuery(s, q.replace(" ", ""))
                // A hashtag has no spaces.
                OnlineSource.FikFap -> Feed.fromQuery(s, q.replace(" ", ""))
                else -> Feed.fromQuery(s, q)
            }?.takeIf { !it.needsQuery }
        }
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
