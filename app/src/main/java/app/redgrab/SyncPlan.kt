package app.redgrab

/**
 * What a sync has to read.
 *
 * RedGifs offers no "what changed since" list, so the app does what is usual for such a service:
 * most syncs are quick ones that only fetch what is new, checked against the counts the site
 * reports; a full pass that reads everything runs now and then to catch what the quick one can't
 * see (a like removed and another added in between, a collection whose size stayed the same).
 *
 *  - Likes come newest first. A quick sync reads from the top and stops at the first page it
 *    already knows entirely. If the site's total then differs from what was expected (something
 *    was unliked), the likes are read in full.
 *  - A collection is read again only when the site reports a different number of clips in it
 *    than at the last read.
 *  - The save folder is listed in a full pass only; in between, the app's own record of what it
 *    saved is used.
 */
object SyncPlan {
    /** A full pass runs by itself this often. */
    const val FULL_EVERY_MS = 7 * 86_400_000L

    /** Whether this sync must read everything: asked for, never done, the clock went back, or a week has passed. */
    fun needsFull(asked: Boolean, lastFull: Long, now: Long, knownClips: Int): Boolean =
        asked || knownClips == 0 || lastFull <= 0 || now < lastFull || now - lastFull >= FULL_EVERY_MS

    /**
     * The liked list after a quick read. [known] is the list from before (newest first), [fetched]
     * what was read from the top just now. Null when the numbers don't add up and everything has
     * to be read: the site's [siteTotal] must have grown from [lastSiteTotal] by exactly the
     * number of new likes found (or the last total isn't known).
     */
    fun mergeLikes(known: List<Clip>, fetched: List<Clip>, siteTotal: Int, lastSiteTotal: Int): List<Clip>? {
        val knownIds = known.mapTo(HashSet()) { it.id }
        val new = fetched.filter { it.id !in knownIds }
        if (lastSiteTotal <= 0 || siteTotal - new.size != lastSiteTotal) return null
        return new + known
    }

    /** Whether a collection can be taken from what is known: the site reports the same size as when it was last read, and it was read before. */
    fun collectionUnchanged(siteCount: Int, countAtLastRead: Int?, knownMembers: Int): Boolean =
        countAtLastRead != null && countAtLastRead == siteCount && (knownMembers > 0 || siteCount == 0)
}
