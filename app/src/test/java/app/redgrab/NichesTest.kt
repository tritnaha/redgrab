package app.redgrab

import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Niches, the mixed feed, content preferences and collections. Answers are written by hand in the shape the site gives. */
class NichesTest {

    private class Fake(val answer: (Request) -> Pair<Int, String>) {
        val seen = ArrayList<Request>()
        val bodies = ArrayList<String>()
        val http: OkHttpClient = OkHttpClient.Builder().addInterceptor { chain ->
            val req = chain.request()
            seen += req
            bodies += req.body?.let { b -> Buffer().also { b.writeTo(it) }.readUtf8() }.orEmpty()
            val (code, body) = answer(req)
            Response.Builder().request(req).protocol(Protocol.HTTP_1_1).code(code).message("x").body(body.toResponseBody("application/json".toMediaType())).build()
        }.build()
        fun api() = RedGifsApi("token", "TestBrowser/1.0", http)
    }

    private val nicheJson = """{"page":1,"pages":4,"total":17,"niches":[
        {"id":"slow-dance","name":"Slow Dance","gifs":961776,"subscribers":637380,"tags":["Dancing","Music"],"preferences":["straight","lesbian"],"thumbnail":"https://userpic.redgifs.com/niches/thumbnails/a.jpg"},
        {"id":"other","name":"Other","gifs":5,"subscribers":2,"tags":[],"preferences":["gay"],"thumbnail":""}]}"""

    @Test fun mixTakesTurnsThroughFollowedNiches() {
        // Few niches: every page takes the next page of each.
        assertEquals(listOf("a" to 1, "b" to 1, "c" to 1), NicheMix.plan(1, listOf("a", "b", "c")))
        assertEquals(listOf("a" to 2, "b" to 2, "c" to 2), NicheMix.plan(2, listOf("a", "b", "c")))
        // More than fit on a page: the next page goes on with the next ones, then round again one page deeper.
        val ten = (1..10).map { "n$it" }
        assertEquals((1..6).map { "n$it" to 1 }, NicheMix.plan(1, ten))
        assertEquals(listOf("n7" to 1, "n8" to 1, "n9" to 1, "n10" to 1, "n1" to 2, "n2" to 2), NicheMix.plan(2, ten))
        // Over many pages every niche gets the same number of turns, and no (niche, page) is asked twice.
        val asked = (1..10).flatMap { NicheMix.plan(it, ten) }
        assertEquals(asked.size, asked.toSet().size)
        assertEquals(setOf(6), asked.groupingBy { it.first }.eachCount().values.toSet())
        assertTrue(NicheMix.plan(1, emptyList()).isEmpty())
        // One from each in turn; a short list doesn't stop the others.
        assertEquals(listOf(1, 10, 100, 2, 20, 3), NicheMix.interleave(listOf(listOf(1, 2, 3), listOf(10, 20), listOf(100))))
        assertTrue(NicheMix.interleave(listOf(emptyList<Int>(), emptyList())).isEmpty())
    }

    @Test fun contentPreferencesHideOnlyWhatIsLabelledAndSwitchedOff() {
        val noGayOrTrans = setOf("straight", "lesbian", "bisexual", "animated")
        assertTrue(ContentPrefs.allows(noGayOrTrans, listOf("straight")))
        assertFalse(ContentPrefs.allows(noGayOrTrans, listOf("gay")))
        assertFalse(ContentPrefs.allows(noGayOrTrans, listOf("Trans")))
        // Any switched-off label hides the clip, even next to a wanted one.
        assertFalse(ContentPrefs.allows(noGayOrTrans, listOf("straight", "trans")))
        // No label, or one the site doesn't use: can't be judged, so it is shown.
        assertTrue(ContentPrefs.allows(noGayOrTrans, emptyList()))
        assertTrue(ContentPrefs.allows(noGayOrTrans, listOf("name")))
        // Everything on (the default) hides nothing.
        assertTrue(ContentPrefs.ALL.all { ContentPrefs.allows(ContentPrefs.ALL.toSet(), listOf(it)) })
    }

    @Test fun nicheListIsAskedAndReadAsTheSiteGivesIt() = runBlocking {
        val fake = Fake { 200 to nicheJson }
        val page = fake.api().searchNiches("slow dance", "Fetish", NicheOrder.Posts, 2, count = 30)
        val url = fake.seen.single().url
        assertEquals("/v2/niches/search", url.encodedPath)
        assertEquals(listOf("posts", "2", "30", "slow dance", "Fetish"), listOf("order", "page", "count", "query", "category").map { url.queryParameter(it) })
        assertEquals(4, page.pages); assertEquals(17, page.total)
        val n = page.niches.first()
        assertEquals(NicheInfo("slow-dance", "Slow Dance", "https://userpic.redgifs.com/niches/thumbnails/a.jpg", 961776, 637380, listOf("Dancing", "Music"), listOf("straight", "lesbian")), n)
        assertNull(page.niches[1].thumb)
        // "Best match" without words to match isn't an order the site takes for the plain list.
        fake.api().searchNiches("", null, NicheOrder.Match, 1)
        assertEquals("subscribers", fake.seen.last().url.queryParameter("order"))
        assertNull(fake.seen.last().url.queryParameter("query"))
    }

    @Test fun followingAndUnfollowingANiche() = runBlocking {
        val fake = Fake { 200 to "{}" }
        fake.api().followNiche("slow-dance", true)
        fake.api().followNiche("slow-dance", false)
        assertEquals(listOf("POST /v2/niches/slow-dance/subscription", "DELETE /v2/niches/slow-dance/subscription"), fake.seen.map { it.method + " " + it.url.encodedPath })
        assertTrue(fake.seen.all { it.header("Authorization") == "Bearer token" })
    }

    @Test fun tagSuggestionsAreSortedByUse() = runBlocking {
        val fake = Fake { 200 to """[{"type":"tag","text":"Dancer","gifs":90},{"type":"tag","text":"Dancing","gifs":140725},{"type":"user","text":"dan"},{"type":"tag","text":"","gifs":1}]""" }
        assertEquals(listOf(TagSuggestion("Dancing", 140725), TagSuggestion("Dancer", 90)), fake.api().suggestTags("dan"))
        assertEquals("dan", fake.seen.single().url.queryParameter("query"))
        assertTrue(fake.api().suggestTags("  ").isEmpty())
    }

    @Test fun deletedClipsAreTakenOutOfACollectionAsTheSiteDoes() = runBlocking {
        val fake = Fake { 200 to "{}" }
        fake.api().removeFromCollection("abc123", listOf("clipone", "cliptwo"))
        val req = fake.seen.single()
        assertEquals("DELETE /v2/me/collections/abc123/gifs", req.method + " " + req.url.encodedPath)
        // The body is the list of clip IDs itself, as the site's own "remove selected" sends it.
        assertEquals("""["clipone","cliptwo"]""", fake.bodies.single())
        // Nothing to remove: nothing is asked.
        fake.api().removeFromCollection("abc123", emptyList())
        assertEquals(1, fake.seen.size)
    }

    @Test fun collectionsAreCreatedAndFilledAsTheSiteDoes() = runBlocking {
        val fake = Fake { req ->
            when {
                req.method == "POST" && req.url.encodedPath == "/v2/me/collections" -> 200 to """{"folderId":"abc123","folderName":"Evenings","contentCount":0}"""
                else -> 200 to "{}"
            }
        }
        val col = fake.api().createCollection(" Evenings ")
        assertEquals(RgCollection("abc123", "Evenings"), col)
        val made = JSONObject(fake.bodies[0])
        assertEquals("Evenings", made.getString("folderName"))
        assertFalse("a new collection must be private", made.getBoolean("published"))
        assertTrue(made.isNull("description"))
        fake.api().addToCollection(col.id, "clipid")
        assertEquals("POST /v2/me/collections/abc123/gifs", fake.seen[1].method + " " + fake.seen[1].url.encodedPath)
        assertEquals("clipid", JSONObject(fake.bodies[1]).getString("gifId"))
    }

    @Test fun preferencesAreStoredOnTheAccountAsTheSiteDoes() = runBlocking {
        val fake = Fake { req -> if (req.method == "GET") 200 to """{"username":"u","preferences":["straight","lesbian"]}""" else 200 to "{}" }
        fake.api().setAccountPrefs(listOf("straight", "lesbian"))
        assertEquals("PATCH /v1/me", fake.seen[0].method + " " + fake.seen[0].url.encodedPath)
        val op = JSONObject(fake.bodies[0]).getJSONArray("operations").getJSONObject(0)
        assertEquals("/preferences", op.getString("path"))
        assertEquals(listOf("straight", "lesbian"), op.getJSONArray("value").strings())
        assertEquals(listOf("straight", "lesbian"), fake.api().accountPrefs())
    }

    @Test fun savedOnlineClipsAreLabelledByWhereTheyCameFrom() {
        // The filter is about where a clip was found; it says nothing about it not being on the phone.
        assertEquals("From Online", Origin.Online.label)
        assertEquals(Feed.MyNiches, Feed.home(OnlineSource.RedGifs))
        assertEquals("My feed", Feed.MyNiches.title)
        assertEquals("5.0K", Format.count(5000)); assertEquals("961K", Format.count(961_776)); assertEquals("1.2M", Format.count(1_234_567)); assertEquals("12", Format.count(12))
    }

    @Test fun sfwOnStartOnlyAfterARealAbsence() {
        val now = 1_800_000_000_000L
        assertTrue(awayLongEnough(0, now))                  // never left: a first start
        assertFalse(awayLongEnough(now - 20_000, now))      // back from another app after twenty seconds
        assertFalse(awayLongEnough(now - 4 * 60_000, now))
        assertTrue(awayLongEnough(now - 6 * 60_000, now))
        assertTrue(awayLongEnough(now + 60_000, now))       // the clock was set back
    }

    @Test fun openOnSettingPicksTabAndSite() {
        assertEquals(0 to null, startViewOf("saved"))
        assertEquals(4 to null, startViewOf("online"))
        assertEquals(4 to OnlineSource.Reddit, startViewOf("online:Reddit"))
        assertEquals(4 to null, startViewOf("online:Gone"))  // a site that no longer exists: Online, last site
        assertEquals(0 to null, startViewOf(""))
    }

    @Test fun clipsDeletedOnTheirSiteAreNotCountedAsWaitingToBeSaved() {
        fun clip(id: String) = Clip(id = id, hd = "https://x/$id.mp4", sd = null, thumb = null, duration = 5.0, width = 1, height = 1, hasAudio = false,
            user = "", created = 0, sources = setOf("Liked"))
        val s = LibraryState(clips = listOf(clip("a"), clip("b"), clip("c")), qualities = mapOf("a" to setOf(Quality.HD)),
            failures = mapOf("b" to Failure("No longer on RedGifs", 1, 1, 1, dead = true)))
        assertEquals(1, s.newCount)   // only "c" can still be saved
        assertEquals(1, s.savedCount)
    }

    @Test fun followedTagsTakeTurnsWithNichesInTheMix() {
        val keys = listOf("n:a", "n:b", "t:sunset")
        assertEquals(listOf("n:a" to 1, "n:b" to 1, "t:sunset" to 1), NicheMix.plan(1, keys))
        assertEquals(listOf("n:a" to 2, "n:b" to 2, "t:sunset" to 2), NicheMix.plan(2, keys))
    }

    // ---- quick and full sync -------------------------------------------------------------

    private fun liked(id: String) = Clip(id = id, hd = "https://x/$id.mp4", sd = null, thumb = null, duration = 5.0, width = 1, height = 1, hasAudio = false,
        user = "", created = 0, sources = setOf("Liked"))

    @Test fun aFullSyncRunsWhenAskedNeverDoneOrAWeekOld() {
        val now = 1_800_000_000_000L
        val day = 86_400_000L
        assertTrue(SyncPlan.needsFull(asked = true, lastFull = now - day, now = now, knownClips = 50))
        assertTrue(SyncPlan.needsFull(false, 0, now, 50))             // never done
        assertTrue(SyncPlan.needsFull(false, now - day, now, 0))      // nothing on the phone yet
        assertTrue(SyncPlan.needsFull(false, now - 8 * day, now, 50)) // a week old
        assertTrue(SyncPlan.needsFull(false, now + day, now, 50))     // the clock went back
        assertFalse(SyncPlan.needsFull(false, now - 2 * day, now, 50))
    }

    @Test fun aQuickSyncAddsNewLikesAndNoticesWhenTheCountDoesNotAddUp() {
        val known = listOf(liked("c"), liked("b"), liked("a"))
        // Two new likes on top, and the site's total grew by two: they go in front, the rest stays.
        assertEquals(listOf("e", "d", "c", "b", "a"), SyncPlan.mergeLikes(known, listOf(liked("e"), liked("d"), liked("c")), siteTotal = 5, lastSiteTotal = 3)!!.map { it.id })
        // Nothing new and the same total: unchanged.
        assertEquals(listOf("c", "b", "a"), SyncPlan.mergeLikes(known, listOf(liked("c"), liked("b")), 3, 3)!!.map { it.id })
        // One new like but the total only says "same as before": something was unliked. Read everything.
        assertNull(SyncPlan.mergeLikes(known, listOf(liked("d"), liked("c")), 3, 3))
        // The total dropped: read everything.
        assertNull(SyncPlan.mergeLikes(known, listOf(liked("c"), liked("b")), 2, 3))
        // No total remembered from before: can't be checked, read everything.
        assertNull(SyncPlan.mergeLikes(known, listOf(liked("c")), 3, 0))
    }

    @Test fun aCollectionIsReadAgainOnlyWhenItsSizeOnTheSiteChanged() {
        assertTrue(SyncPlan.collectionUnchanged(siteCount = 12, countAtLastRead = 12, knownMembers = 11))
        assertFalse(SyncPlan.collectionUnchanged(13, 12, 11))   // a clip was added
        assertFalse(SyncPlan.collectionUnchanged(12, null, 0))  // never read
        assertFalse(SyncPlan.collectionUnchanged(12, 12, 0))    // read, but nothing of it is on the phone
        assertTrue(SyncPlan.collectionUnchanged(0, 0, 0))       // empty then and now
    }

    @Test fun likesAreReadFromTheTopOnlyUntilAPageHoldsNothingNew() = runBlocking {
        fun gif(id: String) = """{"id":"$id","urls":{"hd":"https://media.redgifs.com/$id.mp4","sd":"https://media.redgifs.com/$id-mobile.mp4"},"duration":5,"width":10,"height":20}"""
        val pages = listOf(listOf("n1", "k1"), listOf("k2", "k3"), listOf("k4", "k5"))
        val fake = Fake { req ->
            val p = req.url.queryParameter("page")!!.toInt()
            200 to """{"page":$p,"pages":3,"total":6,"gifs":[${pages[p - 1].joinToString(",") { gif(it) }}]}"""
        }
        val known = setOf("k1", "k2", "k3", "k4", "k5")
        var total = 0
        val quick = fake.api().likedClips(FeedExtras(), known) { _, _, t -> total = t }
        // Page 1 had something new, page 2 nothing: stop there. Page 3 is never asked for.
        assertEquals(listOf("1", "2"), fake.seen.map { it.url.queryParameter("page") })
        assertEquals(listOf("n1", "k1", "k2", "k3"), quick.map { it.id })
        assertEquals(6, total)
        // A full read asks for every page.
        fake.seen.clear()
        assertEquals(6, fake.api().likedClips(FeedExtras()) { _, _, _ -> }.size)
        assertEquals(listOf("1", "2", "3"), fake.seen.map { it.url.queryParameter("page") })
    }

    // ---- all sites ------------------------------------------------------------------------

    @Test fun allSitesAsksEachSiteThatCanAnswer() {
        // Nothing typed: only sites that have a feed without a search, and only those that are set up.
        assertEquals(listOf(Feed.MyNiches, Feed.RedditHome, Feed.ChanThreads("")), AllSites.feeds("", redGifs = true, reddit = true, personal = true))
        assertEquals(listOf(Feed.Search(""), Feed.ChanThreads("")), AllSites.feeds("", redGifs = true, reddit = false, personal = false))
        assertEquals(listOf(Feed.ChanThreads("")), AllSites.feeds(" ", redGifs = false, reddit = false, personal = false))
        // Something typed: every site is searched for it, each in its own way.
        assertEquals(
            listOf(Feed.Search("night sky"), Feed.Subreddit("nightsky"), Feed.FikFapTag("nightsky"), Feed.SexSearch("night sky"), Feed.EromeSearch("night sky"), Feed.ChanThreads("night sky")),
            AllSites.feeds("night sky", redGifs = true, reddit = true, personal = true))
        // No feed of "All sites" is itself "All sites".
        assertTrue(AllSites.feeds("x", true, true, true).none { it.source == OnlineSource.All })
        assertEquals(Feed.AllSites("x"), Feed.fromQuery(OnlineSource.All, " x "))
        assertEquals(Feed.AllSites(""), Feed.home(OnlineSource.All))
    }

    @Test fun allSitesRemembersEachSitesPlace() {
        val place = AllSites.Place(mapOf("Reddit" to "t3_abc", "EroMe" to "2|a,b"), setOf("SexCom"))
        assertEquals(place, AllSites.decode(AllSites.encode(place)))
        assertEquals(AllSites.Place(), AllSites.decode(null))
        assertEquals(AllSites.Place(), AllSites.decode("not json"))
        // "All sites" is never where a clip is from.
        assertEquals(OnlineSource.RedGifs, OnlineSource.of("allthings"))
        assertFalse(OnlineSource.All in OnlineSource.SITES)
        assertFalse("All sites" in OnlineSource.SAVED_LABELS)
    }
}
