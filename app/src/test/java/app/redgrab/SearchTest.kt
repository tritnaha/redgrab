package app.redgrab

import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Search suggestions from the lists kept on the phone, the every-tag search and the content filter. */
class SearchTest {

    private class Fake(val answer: (Request) -> Pair<Int, String>) {
        val seen = ArrayList<Request>()
        val http: OkHttpClient = OkHttpClient.Builder().addInterceptor { chain ->
            val req = chain.request()
            seen += req
            val (code, body) = answer(req)
            Response.Builder().request(req).protocol(Protocol.HTTP_1_1).code(code).message("x").body(body.toResponseBody("application/json".toMediaType())).build()
        }.build()
        fun api() = RedGifsApi("token", "TestBrowser/1.0", http)
    }

    private val tags = listOf(
        Known("Amateur", 10_000_000), Known("BDSM", 900_000), Known("Sensual", 400_000), Known("Big Tits", 6_800_000), Known("Big Ass", 3_000_000),
        Known("Blonde", 2_000_000), Known("Blowjob", 5_000_000), Known("Sensual Blowjob", 20_000), Known("Dancing", 140_000), Known("Pole Dancing", 9_000),
        Known("Gay", 700_000), Known("Abs", 50_000),
    )
    private val names = tags.associate { Suggest.norm(it.name) to it.name }

    @Test fun suggestionsAreOnlyThingsThatExistClosestFirst() {
        // Names that start with what was typed come before names that only have it in a later word; the most used first.
        assertEquals(listOf("Blowjob", "Blonde", "Sensual Blowjob"), Suggest.rank(tags, "bl", 5).map { it.name })
        assertEquals(listOf("Dancing", "Pole Dancing"), Suggest.rank(tags, " DAN ", 5).map { it.name })
        // The name itself wins over longer names.
        assertEquals(listOf("Sensual", "Sensual Blowjob"), Suggest.rank(tags, "sensual", 5).map { it.name })
        // Inside a word only from three letters on ("ab" must not bring up everything with "ab" in it).
        assertEquals(listOf("Abs"), Suggest.rank(tags, "ab", 5).map { it.name })
        assertEquals(listOf("Amateur"), Suggest.rank(tags, "ateu", 5).map { it.name })
        // Nothing made up: no match, no suggestion.
        assertTrue(Suggest.rank(tags, "zzz", 5).isEmpty())
        assertTrue(Suggest.rank(tags, "", 5).isEmpty())
        assertEquals(2, Suggest.rank(tags, "b", 2).size)
    }

    @Test fun aQueryOfSeveralWordsIsReadAsTags() {
        assertEquals(listOf("BDSM", "Sensual"), Suggest.asTags(names, "bdsm sensual"))
        assertEquals(listOf("BDSM", "Sensual"), Suggest.asTags(names, "  #BDSM,  sensual "))
        // The longest tag wins: "big tits", not "big" + "tits".
        assertEquals(listOf("Big Tits", "Blonde"), Suggest.asTags(names, "big tits blonde"))
        assertEquals(listOf("Sensual Blowjob", "Blonde"), Suggest.asTags(names, "sensual blowjob blonde"))
        // One tag alone is an ordinary search; a word that is no tag means it isn't a row of tags.
        assertNull(Suggest.asTags(names, "big tits"))
        assertNull(Suggest.asTags(names, "bdsm candlelight"))
        assertNull(Suggest.asTags(names, ""))
        // The same tag twice counts once.
        assertNull(Suggest.asTags(names, "bdsm bdsm"))
    }

    @Test fun halfTypedSearchesAreCompletedWithTags() {
        // The words that already are tags stay; the last one is finished with tags that exist.
        assertEquals(listOf(listOf("BDSM", "Sensual"), listOf("BDSM", "Sensual Blowjob")), Suggest.combos(names, tags, "bdsm sen", 3))
        assertEquals(listOf(listOf("Big Tits", "Blowjob"), listOf("Big Tits", "Blonde"), listOf("Big Tits", "Sensual Blowjob")), Suggest.combos(names, tags, "big tits bl", 3))
        // A finished row of tags is offered as it stands, first.
        assertEquals(listOf("BDSM", "Sensual"), Suggest.combos(names, tags, "bdsm sensual", 3).first())
        // One word, or a first word that is no tag: nothing to combine.
        assertTrue(Suggest.combos(names, tags, "bdsm", 3).isEmpty())
        assertTrue(Suggest.combos(names, tags, "candle sen", 3).isEmpty())
    }

    @Test fun theListsAreStoredAndReadBack() {
        val file = File.createTempFile("index", ".tsv").also { it.delete() }
        try {
            val index = SearchIndex(file)
            index.load()
            assertFalse(index.ready)
            val niches = listOf(Known("Slow Dance", 637_380, "slow-dance", listOf("straight", "lesbian")), Known("Odd\tName", 3, "odd", emptyList()))
            index.store(tags, niches)
            assertTrue(index.ready)
            val again = SearchIndex(file).also { it.load() }
            assertEquals(tags, again.tags)
            assertEquals(listOf(niches[0], Known("Odd Name", 3, "odd", emptyList())), again.niches)
            assertEquals("Big Tits", again.tagNames["big tits"])
            // A failed read of one list (empty) keeps what was stored.
            again.store(emptyList(), emptyList())
            assertEquals(tags.size, SearchIndex(file).also { it.load() }.tags.size)
            again.store(listOf(Known("Only", 1)), emptyList())
            val third = SearchIndex(file).also { it.load() }
            assertEquals(listOf(Known("Only", 1)), third.tags)
            assertEquals(2, third.niches.size)
        } finally { file.delete(); File(file.path + ".tmp").delete() }
    }

    @Test fun theTagAndNicheListsAreReadFromTheSite() = runBlocking {
        val fake = Fake { req ->
            when (req.url.encodedPath) {
                "/v1/tags" -> 200 to """{"tags":[{"name":"Amateur","count":10803232},{"name":" Ass ","count":7808765},{"name":"","count":4}]}"""
                "/v2/niches/search" -> {
                    val page = req.url.queryParameter("page")!!.toInt()
                    200 to """{"page":$page,"pages":2,"total":3,"niches":[{"id":"n$page","name":"Niche $page","gifs":5,"subscribers":${10 - page},"tags":[],"preferences":["straight"]}]}"""
                }
                "/v1/tags/suggest" -> 200 to """[{"type":"tag","text":"Mia Melano"},{"type":"creator","text":"@miamia","username":"miamia"},{"type":"creator","text":"@other"}]"""
                else -> 404 to "{}"
            }
        }
        assertEquals(listOf(Known("Amateur", 10803232), Known("Ass", 7808765)), fake.api().allTags())
        assertEquals(listOf(Known("Niche 1", 9, "n1", listOf("straight")), Known("Niche 2", 8, "n2", listOf("straight"))), fake.api().allNiches())
        val nicheCalls = fake.seen.filter { it.url.encodedPath == "/v2/niches/search" }
        assertEquals(listOf("1", "2"), nicheCalls.map { it.url.queryParameter("page") })
        assertTrue(nicheCalls.all { it.url.queryParameter("count") == "100" })
        assertEquals(listOf("miamia", "other"), fake.api().suggestCreators("mia"))
    }

    @Test fun everyTagSearchAsksForAllTagsAtOnce() = runBlocking {
        val fake = Fake { 200 to """{"page":2,"pages":10,"total":364,"gifs":[]}""" }
        fake.api().tagClips(listOf("Big Tits", "BDSM"), OnlineOrder.New, 2, FeedExtras())
        val url = fake.seen.single().url
        assertEquals("/v2/gifs/search", url.encodedPath)
        assertEquals("Big Tits,BDSM", url.queryParameter("tags"))
        assertEquals("latest", url.queryParameter("order"))
        assertEquals("2", url.queryParameter("page"))
        assertNull(url.queryParameter("query"))
    }

    @Test fun theFilterHidesGayAndTransUnlessAskedFor() {
        val d = ContentPrefs.DEFAULT
        assertEquals(setOf("straight", "lesbian", "bisexual", "animated"), d)
        // By label.
        assertTrue(ContentPrefs.allows(d, listOf("straight")))
        assertFalse(ContentPrefs.allows(d, listOf("gay")))
        assertFalse(ContentPrefs.allows(d, listOf("trans")))
        // By what the tags, niches or title say, when there is no label or the label says otherwise.
        assertFalse(ContentPrefs.allows(d, emptyList(), listOf("Gay", "Amateur")))
        assertFalse(ContentPrefs.allows(d, listOf("straight"), listOf("Trans Woman")))
        assertFalse(ContentPrefs.allows(d, emptyList(), listOf("gay-porn")))
        assertFalse(ContentPrefs.allows(d, emptyList(), listOf("My first femboy video")))
        assertTrue(ContentPrefs.allows(d, emptyList(), listOf("Amateur", "Transparent Dress", "Gayle", "Translucent")))
        assertTrue(ContentPrefs.allows(d, emptyList()))
        // Straight only.
        val straight = setOf("straight")
        assertTrue(ContentPrefs.allows(straight, listOf("straight"), listOf("Blonde")))
        assertFalse(ContentPrefs.allows(straight, listOf("lesbian")))
        assertFalse(ContentPrefs.allows(straight, listOf("straight"), listOf("Lesbians")))
        assertFalse(ContentPrefs.allows(straight, emptyList(), listOf("Hentai")))
        // No filter: everything, whatever it is labelled or tagged.
        val all = ContentPrefs.ALL.toSet()
        assertTrue(ContentPrefs.unfiltered(all))
        assertFalse(ContentPrefs.unfiltered(d))
        assertTrue(ContentPrefs.allows(all, listOf("gay", "trans"), listOf("Gay", "Shemale")))
        assertEquals("No filter", ContentPrefs.summary(all))
        assertEquals("Straight, Lesbian, Bisexual, Animated", ContentPrefs.summary(d))
    }
}
