package app.redgrab

import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Runs the app's RedGifs code against the real site. Skipped unless asked for, because it needs
 * the internet:
 *
 *     REDGRAB_LIVE=1 ./gradlew testDebugUnitTest --tests '*LiveRedGifsTest*'
 *
 * It uses the visitor token RedGifs hands to anyone who isn't logged in, so it covers what the
 * Online tab does (search, niche, creator, related, one clip, downloading) but not your likes
 * and collections, which need your own sign-in.
 */
class LiveRedGifsTest {

    private val ua = "Mozilla/5.0 (Linux; Android 14; SM-S928B) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Mobile Safari/537.36"
    private val http = RedGifsApi.clientFor(ua)

    private fun visitorToken(): String {
        val req = Request.Builder().url("https://api.redgifs.com/v2/auth/temporary").build()
        http.newCall(req).execute().use { r ->
            assertTrue("visitor token: HTTP ${r.code}", r.isSuccessful)
            return JSONObject(r.body!!.string()).getString("token")
        }
    }

    private fun say(line: String) = println("LIVE  $line")

    private fun check(what: String, page: OnlinePage) {
        val c = page.clips
        say("$what: ${c.size} clips, hasMore=${page.hasMore}, pages=${page.pages}; with HD ${c.count { it.hd != null }}, SD ${c.count { it.sd != null }}, " +
            "thumbnail ${c.count { it.thumb != null }}, length ${c.count { it.duration > 0 }}, size ${c.count { it.width > 0 && it.height > 0 }}, " +
            "creator ${c.count { it.user.isNotEmpty() }}, tags ${c.count { it.tags.isNotEmpty() }}")
        assertTrue("$what returned no clips", c.isNotEmpty())
        assertTrue("$what: an ID that can't be a file name", c.all { Clip.validId(it.id) && OnlineSource.of(it.id) == OnlineSource.RedGifs })
        assertTrue("$what: a clip without any video address", c.all { it.hd != null || it.sd != null })
        assertTrue("$what: a clip was taken for a picture", c.none { it.isImage })
        assertEquals("$what: the same clip twice", c.size, c.map { it.id }.toSet().size)
    }

    @Test fun onlineBrowsingAndDownloadWorkAgainstTheRealSite() = runBlocking {
        assumeTrue("set REDGRAB_LIVE=1 to run against the real site", System.getenv("REDGRAB_LIVE") == "1")
        val api = RedGifsApi(visitorToken(), ua)
        val extras = FeedExtras()

        // A visitor must not pass for a signed-in user (the sign-in screen relies on this check).
        try { api.checkSignedIn(); org.junit.Assert.fail("a visitor token was accepted as a sign-in") }
        catch (e: AuthException) { say("sign-in check: a visitor token is refused, as it should be") }

        // The likes request itself is accepted (a visitor simply has none): the feed, then the list of liked IDs.
        assertTrue(api.likedClips(extras) { _, _, _ -> }.isEmpty())
        say("likes: the liked feed and the liked-ID list both answer (empty for a visitor)")

        // "For you" is for signed-in users only: a visitor must be refused (which also shows the address exists).
        try { api.forYou(1, extras); org.junit.Assert.fail("the For You feed answered a visitor") }
        catch (e: AuthException) { say("For you: refused for a visitor, as the site does (it needs your sign-in)") }

        // The general feed, a second page of it, and a search.
        val home = api.searchClips("", OnlineOrder.Trending, 1, extras)
        check("Home feed (trending)", home)
        val home2 = api.searchClips("", OnlineOrder.Trending, 2, extras)
        check("Home feed, page 2", home2)
        say("page 2 repeats ${home2.clips.count { c -> home.clips.any { it.id == c.id } }} clip(s) of page 1")
        // Jumping straight to a later page, as "Go to page" does.
        val far = api.searchClips("", OnlineOrder.Trending, 30, extras)
        check("Home feed, page 30 opened directly", far)
        assertEquals(30, far.page)
        for (order in OnlineOrder.entries) check("Search \"dance\", ${order.label}", api.searchClips("dance", order, 1, extras))

        // A creator's clips and the "related" list, starting from a clip of the home feed.
        val first = home.clips.first { it.user.isNotEmpty() }
        check("Creator feed (newest)", api.performerClips(first.user, OnlineOrder.New, 1, extras))
        check("Creator feed (top)", api.performerClips(first.user, OnlineOrder.TopAll, 1, extras))
        check("Related", api.relatedClips(first.id, 1, extras))
        say("learned ${extras.performers.size} creator profile(s) and ${extras.niches.size} niche name(s) on the way")

        // A niche feed, for a niche one of the clips is in.
        val niche = (home.clips + home2.clips).firstNotNullOfOrNull { it.nicheIds.firstOrNull() }
        if (niche == null) say("Niche feed: no clip on the first two pages is in a niche; not checked")
        else {
            // Every order, including the two a niche doesn't offer (they fall back to "top").
            for (order in OnlineOrder.entries) check("Niche feed \"$niche\", ${order.label}", api.nicheClips(niche, order, 1, extras))
            check("Niche feed, page 2", api.nicheClips(niche, OnlineOrder.Trending, 2, extras))
            say("niche name lookup: ${if (api.nicheName(niche) != null) "ok" else "none"}")
        }

        // Niches: the groups, the whole list in every order, a search, and the mixed feed's small pages.
        val cats = api.nicheCategories()
        say("niche groups: ${cats.size} (${cats.take(4).joinToString()}…)")
        assertTrue(cats.size >= 5)
        for (o in NicheOrder.entries) {
            val pg = api.searchNiches("", null, o, 1, count = 10)
            say("niche list, ${o.label}: ${pg.niches.size} of ${pg.total}, ${pg.pages} pages; labelled for an audience: ${pg.niches.count { it.preferences.isNotEmpty() }}")
            assertTrue(pg.niches.isNotEmpty() && pg.pages > 1 && pg.niches.all { it.id.isNotEmpty() && it.name.isNotEmpty() })
        }
        val inGroup = api.searchNiches("", cats.first(), NicheOrder.Subscribers, 1, count = 10)
        say("niche list, group \"${cats.first()}\": ${inGroup.total}")
        assertTrue(inGroup.niches.isNotEmpty() && inGroup.total < api.searchNiches("", null, NicheOrder.Subscribers, 1, count = 10).total)
        val found = api.searchNiches("dance", null, NicheOrder.Match, 1, count = 10)
        say("niche search \"dance\": ${found.total}")
        assertTrue(found.niches.isNotEmpty())
        val tags = api.suggestTags("dan")
        say("tag suggestions for \"dan\": ${tags.size}, most used has ${tags.firstOrNull()?.clips} clips")
        assertTrue(tags.isNotEmpty() && tags.first().clips >= tags.last().clips)
        // The mix: a few clips from each of several niches, as "My niches" asks for them.
        val some = api.searchNiches("", null, NicheOrder.Subscribers, 1, count = 10).niches.take(3).map { it.id }
        val parts = NicheMix.plan(1, some).map { (id, p) -> api.nicheClips(id, OnlineOrder.Trending, p, extras, count = NicheMix.PER_NICHE) }
        val mixed = NicheMix.interleave(parts.map { it.clips })
        say("mix of ${some.size} niches: ${parts.map { it.clips.size }} clips each, ${mixed.size} in all, ${mixed.map { it.id }.toSet().size} different; " +
            "labelled: ${mixed.count { it.sexuality.isNotEmpty() }}")
        assertTrue(parts.all { it.clips.size in 1..NicheMix.PER_NICHE } && mixed.size == parts.sumOf { it.clips.size })
        // Changing the account (follow, collections, preferences) needs your sign-in: a visitor must be refused.
        for ((what, call) in listOf<Pair<String, suspend () -> Unit>>(
            "follow a niche" to { api.followNiche(some.first(), true) },
            "account preferences" to { api.accountPrefs() },
        )) {
            try { call(); org.junit.Assert.fail("a visitor could $what") } catch (e: AuthException) { say("$what: refused for a visitor, as it should be") }
        }

        // One clip by ID: what a download uses to renew an expired address, and what a RedGifs link found on Reddit goes through.
        val again = api.clip(first.id)
        assertNotNull("clip by ID", again)
        assertEquals(first.id, again!!.id)
        assertEquals("the ID a Reddit link gives must be the one RedGifs uses", first.id, RedditApi.redgifsId("https://www.redgifs.com/watch/${first.id}"))

        // Where the files live, and which headers go out to those hosts.
        val hosts = (home.clips + home2.clips).flatMap { listOfNotNull(it.hd, it.sd, it.thumb) }.map { it.toHttpUrl().host }.groupingBy { it }.eachCount()
        say("file hosts: $hosts")
        for (h in hosts.keys) assertEquals("RedGifs headers for $h", "https://www.redgifs.com" to "https://www.redgifs.com/", SourceHeaders.forHost(h))

        // Downloading: the start of the file, then resuming from the middle as an interrupted download would.
        val url = again.hd ?: again.sd!!
        RedGifsApi.download(http, url).use { r ->
            val head = ByteArray(12).also { r.body!!.byteStream().read(it) }
            val size = r.header("Content-Length")?.toLongOrNull() ?: -1
            say("download: HTTP ${r.code}, ${r.header("Content-Type")}, ${Format.size(size)}, starts with \"${String(head, 4, 4, Charsets.ISO_8859_1)}\"")
            assertEquals("an MP4 file starts with an 'ftyp' box", "ftyp", String(head, 4, 4, Charsets.ISO_8859_1))
            assertTrue(size > 0)
            RedGifsApi.download(http, url, fromByte = size / 2).use { part ->
                say("resume from the middle: HTTP ${part.code}, Content-Range ${part.header("Content-Range")}")
                assertEquals("the server should continue from the byte asked for", 206, part.code)
                assertTrue(part.header("Content-Range")!!.startsWith("bytes ${size / 2}-"))
            }
        }
    }
}
