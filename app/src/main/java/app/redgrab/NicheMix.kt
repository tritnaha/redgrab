package app.redgrab

/**
 * The "My niches" feed: one stream made from the niches you follow, a few clips from each in
 * turn, so no single niche fills the screen.
 *
 * RedGifs has no such feed of its own; each page here is put together from the "hot" list of up
 * to [PER_PAGE] niches. With more niches than that, the next page continues with the next ones,
 * and once all have had a turn it goes on to their second pages, and so on.
 */
object NicheMix {
    /** Niches asked on each page. */
    const val PER_PAGE = 6
    /** Clips asked from each (RedGifs gives at least ten at a time). */
    const val PER_NICHE = 12

    /** Which niches, and which page of each, make up page [page] (from 1) of the mix. */
    fun plan(page: Int, ids: List<String>): List<Pair<String, Int>> {
        if (ids.isEmpty() || page < 1) return emptyList()
        val k = minOf(PER_PAGE, ids.size)
        val start = (page - 1) * k
        return (0 until k).map { i -> val n = start + i; ids[n % ids.size] to n / ids.size + 1 }
    }

    /** One from each list in turn, until all are used up. */
    fun <T> interleave(lists: List<List<T>>): List<T> {
        val out = ArrayList<T>(lists.sumOf { it.size })
        var i = 0
        while (true) {
            var any = false
            for (l in lists) if (i < l.size) { out += l[i]; any = true }
            if (!any) return out
            i++
        }
    }
}
