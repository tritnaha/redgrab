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
        assertEquals("5.0K", Format.count(5000)); assertEquals("961K", Format.count(961_776)); assertEquals("1.2M", Format.count(1_234_567)); assertEquals("12", Format.count(12))
    }
}
