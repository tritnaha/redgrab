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
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/**
 * Checks how the three added sites' answers are turned into clips, and which requests are sent.
 * The answers here are written by hand from the field names in gallery-dl's extractors and
 * tests; they are not recordings of the real sites.
 */
class SourcesTest {

    /** A client that answers from [answer] instead of the network and remembers each request as sent. */
    private class Fake(val answer: (Request) -> Pair<Int, String>) {
        val seen = ArrayList<Request>()
        val http: OkHttpClient = RedGifsApi.clientFor("TestBrowser/1.0").newBuilder().addInterceptor { chain ->
            val req = chain.request()
            seen += req
            val (code, body) = answer(req)
            Response.Builder().request(req).protocol(Protocol.HTTP_1_1).code(code).message("x")
                .body(body.toResponseBody("application/json".toMediaType())).build()
        }.build()
    }

    private class Store(
        override var redditClientId: String? = "client123", override var redditAccess: String? = null,
        override var redditAccessExp: Long = 0, override var redditRefresh: String? = null, override var redditUser: String? = null,
    ) : RedditStore

    // ---- IDs, picture detection, headers --------------------------------------------------

    @Test fun sourceIsReadFromTheIdPrefix() {
        assertEquals(OnlineSource.RedGifs, OnlineSource.of("happybluewhale"))
        assertEquals(OnlineSource.Reddit, OnlineSource.of("rd-1abc9z"))
        assertEquals(OnlineSource.FikFap, OnlineSource.of("ff-1429486"))
        assertEquals(OnlineSource.SexCom, OnlineSource.of("sx-778899"))
        assertEquals(OnlineSource.FikFap, OnlineSource.of("ff-1429486-edit-1"))
        assertEquals(OnlineSource.EroMe, OnlineSource.of("er-nqgdlwvk-0123456789"))
        assertEquals(OnlineSource.FourChan, OnlineSource.of("4c-gif-1700000000000002"))
    }

    private fun clip(id: String, hd: String?, copyFile: String? = null) = Clip(id = id, hd = hd, sd = null, thumb = null, duration = 0.0,
        width = 0, height = 0, hasAudio = false, user = "", created = 0, sources = emptySet(), copyFile = copyFile)

    @Test fun onlineGifsArePicturesButRedditMp4CopiesAreNot() {
        assertTrue(clip("sx-1", "https://imagex1.sx.cdn.live/images/pinporn/2024/01/31/1.webp").isImage)
        assertTrue(clip("sx-1", "https://imagex1.sx.cdn.live/images/pinporn/2024/01/31/1.gif?width=300").isImage)
        assertFalse(clip("rd-1", "https://preview.redd.it/abc.gif?format=mp4&s=0123").isImage)
        assertFalse(clip("rd-1", "https://v.redd.it/abc/DASH_720.mp4?source=fallback").isImage)
        assertFalse(clip("ff-1", "https://vz-1-178.b-cdn.net/bcdn_token=x&expires=1/id/playlist.m3u8").isImage)
        assertFalse(clip("abc", null).isImage)
        // The file on the phone decides once there is one.
        assertTrue(clip("imp-a", null, "imported/a.GIF").isImage)
        assertFalse(clip("imp-b", null, "imported/b.mp4").isImage)
    }

    @Test fun eachSiteGetsItsOwnOriginAndReferer() {
        assertEquals("https://fikfap.com" to "https://fikfap.com/", SourceHeaders.forHost("api.fikfap.com"))
        assertEquals("https://fikfap.com" to "https://fikfap.com/", SourceHeaders.forHost("vz-5d293dac-178.b-cdn.net"))
        assertEquals(null to "https://www.sex.com/", SourceHeaders.forHost("www.sex.com"))
        assertEquals(null to "https://www.sex.com/", SourceHeaders.forHost("imagex1.sx.cdn.live"))
        assertEquals(null to null, SourceHeaders.forHost("oauth.reddit.com"))
        assertEquals(null to null, SourceHeaders.forHost("v.redd.it"))
        assertEquals(null to "https://www.erome.com/", SourceHeaders.forHost("v11.erome.com"))
        assertEquals(null to null, SourceHeaders.forHost("i.4cdn.org"))
        assertEquals("https://www.redgifs.com" to "https://www.redgifs.com/", SourceHeaders.forHost("media.redgifs.com"))
        // A look-alike host isn't the site.
        assertEquals("https://www.redgifs.com" to "https://www.redgifs.com/", SourceHeaders.forHost("notsex.com"))
    }

    // ---- sex.com -------------------------------------------------------------------------

    @Test fun sexComSearchBecomesPictureClips() {
        val fake = Fake { 200 to """{"data":[
            {"id":778899,"externalId":31415926,"uri":"/images/pinporn/2024/01/31/31415926.webp","title":"In bed","width":480,"height":640},
            {"id":778900,"uri":"/images/pinporn/2023/12/05/27182818.gif","title":null,"width":300,"height":200},
            {"id":"bad id","uri":"/x.gif"}
          ],"paging":{"numberOfPages":3}}""" }
        val page = SexComApi(fake.http).search(" bed ", 1)

        val url = fake.seen.single().url
        assertEquals("/portal/api/gifs/search", url.encodedPath)
        assertEquals("bed", url.queryParameter("search"))
        assertEquals("likeCount", url.queryParameter("order"))
        assertEquals("straight", url.queryParameter("sexual-orientation"))
        assertEquals("1", url.queryParameter("page"))
        assertEquals("40", url.queryParameter("limit"))
        assertEquals("https://www.sex.com/", fake.seen.single().header("Referer"))
        assertNull(fake.seen.single().header("Origin"))

        assertEquals(listOf("sx-778899", "sx-778900"), page.clips.map { it.id })
        assertTrue(page.hasMore)
        val c = page.clips[0]
        assertEquals("https://imagex1.sx.cdn.live/images/pinporn/2024/01/31/31415926.webp", c.hd)
        assertEquals("https://imagex1.sx.cdn.live/images/pinporn/2024/01/31/31415926.webp?width=300", c.thumb)
        assertEquals("In bed", c.description)
        assertEquals(480, c.width); assertEquals(640, c.height)
        assertEquals(setOf("sex.com"), c.sources)
        assertEquals(1706659200L, c.created) // 2024-01-31 00:00 UTC
        assertTrue(c.isImage)
        assertEquals("", page.clips[1].description)
        // Saving tries the real GIF behind a WebP first, then the WebP.
        assertEquals(listOf("https://imagex1.sx.cdn.live/images/pinporn/2024/01/31/31415926.gif", c.hd), SexComApi.files(c))
        assertEquals(listOf(page.clips[1].hd), SexComApi.files(page.clips[1]))
        assertFalse(SexComApi(Fake { 200 to """{"data":[],"paging":{"numberOfPages":3}}""" }.http).search("bed", 3).hasMore)
    }

    @Test fun sexComTagsComeFromTheDetailPage() {
        val fake = Fake { 200 to """<html><h1>In bed</h1><div><a href="/en/gifs?search=bed">bed</a> <a href="/x">@Some One</a>
            <a href="/y">R&amp;B</a><a href="/z">bed</a></div></section><section><a>not a tag</a></section>""" }
        assertEquals(listOf("bed", "Some One", "R&B"), SexComApi(fake.http).tags("778899"))
        assertEquals("/en/gifs/778899", fake.seen.single().url.encodedPath)
    }

    // ---- FikFap --------------------------------------------------------------------------

    private fun fikfapPost(id: Int, extra: String = "", duration: String = "null") = """{"postId":$id,"label":"Label $id","duration":$duration,"likesCount":12,"viewsCount":3456,
        "createdAt":"2025-10-21T00:49:00.306Z","publishedAt":"2025-10-21T00:50:37.143Z","sexualOrientation":"STRAIGHT",
        "thumbnailStreamUrl":"https://vz-5d293dac-178.b-cdn.net/vid$id/thumbnail.jpg",
        "videoStreamUrl":"https://vz-5d293dac-178.b-cdn.net/bcdn_token=t&expires=9&token_path=%2Fvid$id%2F/vid$id/playlist.m3u8",
        "hashtags":[{"label":"lesbian"},{"label":"outercourse"}],
        "author":{"username":"alinevs","isVerified":true,"thumbnailUrl":"https://x/y.jpg"}$extra}"""

    @Test fun fikFapPostsBecomeVideoClipsAndContinueAfterTheLast() {
        val fake = Fake { 200 to (1..21).joinToString(",", "[", "]") { fikfapPost(1000 + it) } }
        val api = FikFapApi(fake.http)
        val page = api.tagPosts("foo bar", null, 1)

        val req = fake.seen.single()
        assertEquals("api.fikfap.com", req.url.host)
        assertEquals(listOf("hashtags", "label", "foo bar", "posts"), req.url.pathSegments)
        assertEquals("21", req.url.queryParameter("amount"))
        assertNull(req.url.queryParameter("afterId"))
        assertEquals("2527cc30-c3c5-41be-b8bb-104b6ea7a206", req.header("Authorization-Anonymous"))
        assertEquals("false", req.header("IsLoggedIn"))
        assertEquals("https://fikfap.com", req.header("Origin"))
        assertEquals("https://fikfap.com/", req.header("Referer"))

        assertEquals(21, page.clips.size)
        assertEquals("1021", page.cursor)
        assertTrue(page.hasMore)
        val c = page.clips[0]
        assertEquals("ff-1001", c.id)
        assertTrue(Clip.validId(c.id))
        assertTrue(HlsSaver.isHls(c.hd!!))
        assertEquals("https://vz-5d293dac-178.b-cdn.net/vid1001/thumbnail.jpg", c.thumb)
        assertEquals("alinevs", c.user)
        assertTrue(c.verified)
        assertEquals(listOf("lesbian", "outercourse"), c.tags)
        assertEquals("Label 1001", c.description)
        assertEquals(0.0, c.duration, 0.0)
        assertEquals(3456L, c.views); assertEquals(12L, c.likes)
        assertEquals(listOf("straight"), c.sexuality)
        assertEquals(setOf("FikFap"), c.sources)
        assertEquals(1761007837L, c.created)
        assertFalse(c.isImage)

        api.userPosts("alinevs", page.cursor, 2)
        assertEquals(listOf("profile", "username", "alinevs", "posts"), fake.seen[1].url.pathSegments)
        assertEquals("1021", fake.seen[1].url.queryParameter("afterId"))
    }

    @Test fun fikFapShortPageEndsTheFeedAndAnMp4IsPreferred() {
        val posts = "[" + fikfapPost(5, ""","videoFileOriginalUrl":"https://cdn.example/5.mp4"""", duration = "11.5") + "," +
            """{"postId":6,"label":"no video","hashtags":[],"author":null}""" + "]"
        val page = FikFapApi(Fake { 200 to posts }.http).tagPosts("x", "9", 2)
        assertEquals(listOf("ff-5"), page.clips.map { it.id })
        assertEquals("https://cdn.example/5.mp4", page.clips[0].hd)
        assertEquals(11.5, page.clips[0].duration, 0.0)
        assertFalse(page.hasMore)
        assertEquals("ff-7", FikFapApi(Fake { 200 to fikfapPost(7) }.http).post("7")?.id)
    }

    @Test fun siteErrorsCarryTheStatusCode() {
        try {
            FikFapApi(Fake { 404 to """{"message":"Not Found"}""" }.http).tagPosts("nope", null, 1)
            fail("expected an error")
        } catch (e: HttpException) { assertEquals(404, e.code) }
    }

    // ---- Reddit --------------------------------------------------------------------------

    private val listing = """{"data":{"after":"t3_next","children":[
      {"kind":"t3","data":{"id":"1AbC","title":"A RedGifs link","author":"poster1","subreddit":"clips","created_utc":1700000000.0,"ups":50,
        "is_video":false,"url":"https://www.redgifs.com/watch/HappyBlueWhale","domain":"redgifs.com",
        "preview":{"images":[{"source":{"url":"https://external-preview.redd.it/big.jpg","width":1080,"height":1920},
          "resolutions":[{"url":"https://external-preview.redd.it/108.jpg","width":108,"height":192},{"url":"https://external-preview.redd.it/320.jpg","width":320,"height":569}]}],
          "reddit_video_preview":{"fallback_url":"https://v.redd.it/prev/DASH_480.mp4","duration":9,"width":480,"height":854}}}},
      {"kind":"t3","data":{"id":"2def","title":"Hosted video","author":"poster2","subreddit":"videos","created_utc":1700000100.0,"ups":7,
        "is_video":true,"url":"https://v.redd.it/xyz",
        "secure_media":{"reddit_video":{"fallback_url":"https://v.redd.it/xyz/DASH_720.mp4?source=fallback","duration":14,"width":720,"height":1280,"has_audio":true}}}},
      {"kind":"t3","data":{"id":"3ghi","title":"A GIF","author":"[deleted]","subreddit":"gifs","created_utc":1700000200.0,"ups":3,
        "is_video":false,"url":"https://i.redd.it/pic.gif",
        "preview":{"images":[{"source":{"url":"https://preview.redd.it/pic.gif?format=png8&s=1","width":400,"height":300},"resolutions":[],
          "variants":{"mp4":{"source":{"url":"https://preview.redd.it/pic.gif?format=mp4&s=2","width":400,"height":300}}}}]}}},
      {"kind":"t3","data":{"id":"4jkl","title":"Just a photo","author":"p","subreddit":"pics","created_utc":1.0,"is_video":false,"url":"https://i.redd.it/photo.jpg",
        "preview":{"images":[{"source":{"url":"https://preview.redd.it/photo.jpg","width":1,"height":1},"resolutions":[]}]}}},
      {"kind":"t3","data":{"id":"5mno","title":"Text only","author":"p","subreddit":"ask","created_utc":1.0,"is_self":true,"is_video":false,"url":"https://www.reddit.com/r/ask/comments/5mno/"}},
      {"kind":"t3","data":{"id":"6pqr","title":"Crosspost","author":"poster6","subreddit":"reposts","created_utc":1700000300.0,"is_video":false,"url":"/r/videos/comments/2def/",
        "crosspost_parent_list":[{"id":"2def","is_video":true,"url":"https://v.redd.it/xyz",
          "secure_media":{"reddit_video":{"fallback_url":"https://v.redd.it/xyz/DASH_720.mp4?source=fallback","duration":14,"width":720,"height":1280}}}]}},
      {"kind":"t1","data":{"id":"comment"}}
    ]}}"""

    @Test fun redditListingKeepsOnlyPostsWithSomethingToPlay() {
        val store = Store()
        val fake = Fake { req ->
            if (req.url.encodedPath == "/api/v1/access_token") 200 to """{"access_token":"app-token","expires_in":3600}"""
            else 200 to (if (req.url.queryParameter("after") == null) listing else """{"data":{"after":null,"children":[]}}""")
        }
        val page = RedditApi(store, fake.http).listing(Feed.Subreddit("clips+videos"), OnlineOrder.TopWeek, null, 1)

        // Not signed in: an app-only token with the client ID as the user name and no password.
        val token = fake.seen[0]
        assertEquals("www.reddit.com", token.url.host)
        assertEquals(okhttp3.Credentials.basic("client123", ""), token.header("Authorization"))
        assertEquals("app-token", store.redditAccess)
        val get = fake.seen[1]
        assertEquals("oauth.reddit.com", get.url.host)
        assertEquals("/r/clips+videos/top", get.url.encodedPath)
        assertEquals("week", get.url.queryParameter("t"))
        assertEquals("1", get.url.queryParameter("raw_json"))
        assertEquals("100", get.url.queryParameter("limit"))
        assertEquals("Bearer app-token", get.header("Authorization"))
        assertEquals(RedditApi.USER_AGENT, get.header("User-Agent"))
        assertNull(get.header("Referer")); assertNull(get.header("Origin"))
        // Few playable posts: it reads on from where the first answer ended.
        assertEquals("t3_next", fake.seen[2].url.queryParameter("after"))

        assertEquals(listOf("happybluewhale", "rd-2def", "rd-3ghi", "rd-6pqr"), page.clips.map { it.id })
        assertFalse(page.hasMore)
        val rg = page.clips[0]
        assertNull(rg.hd); assertNull(rg.sd)
        assertEquals("https://external-preview.redd.it/320.jpg", rg.thumb)
        assertEquals("A RedGifs link", rg.description)
        assertEquals(setOf("Reddit"), rg.sources)
        val video = page.clips[1]
        assertEquals("https://v.redd.it/xyz/DASH_720.mp4?source=fallback", video.hd)
        assertEquals(14.0, video.duration, 0.0)
        assertEquals(720, video.width); assertEquals(1280, video.height)
        assertEquals("poster2", video.user)
        assertEquals(listOf("videos"), video.tags)
        assertEquals("Hosted video", video.description)
        assertEquals(1700000100L, video.created)
        assertFalse(video.hasAudio)
        val gif = page.clips[2]
        assertEquals("https://preview.redd.it/pic.gif?format=mp4&s=2", gif.hd)
        assertFalse(gif.isImage)
        assertEquals("", gif.user)
        assertEquals("https://v.redd.it/xyz/DASH_720.mp4?source=fallback", page.clips[3].hd)
    }

    @Test fun redditSignedInUsesTheRefreshTokenAndRetriesOnce() {
        val store = Store(redditAccess = "stale", redditAccessExp = Long.MAX_VALUE / 2, redditRefresh = "refresh-1", redditUser = "me_user")
        var tokens = 0
        val fake = Fake { req ->
            when {
                req.url.encodedPath == "/api/v1/access_token" -> { tokens++; 200 to """{"access_token":"fresh","expires_in":86400}""" }
                req.header("Authorization") == "Bearer stale" -> 401 to "{}"
                else -> 200 to """{"data":{"after":null,"children":[]}}"""
            }
        }
        val page = RedditApi(store, fake.http).listing(Feed.RedditSaved, OnlineOrder.Trending, null, 1)
        assertTrue(page.clips.isEmpty())
        assertEquals(1, tokens)
        assertEquals("fresh", store.redditAccess)
        assertEquals("refresh-1", store.redditRefresh)
        assertEquals("/user/me_user/saved", fake.seen.last().url.encodedPath)
        assertEquals("Bearer fresh", fake.seen.last().header("Authorization"))
    }

    @Test fun redditWithoutAClientIdSaysSo() {
        try {
            RedditApi(Store(redditClientId = null), Fake { 200 to "{}" }.http).listing(Feed.RedditHome, OnlineOrder.New, null, 1)
            fail("expected an error")
        } catch (e: RedditAuthException) { assertTrue(e.message!!.contains("client ID")) }
    }

    @Test fun redditSignInAddressAndCodeExchange() {
        val url = okhttp3.HttpUrl.Companion.run { RedditApi.authorizeUrl("client123", "st4te").toHttpUrl() }
        assertEquals("/api/v1/authorize.compact", url.encodedPath)
        assertEquals("client123", url.queryParameter("client_id"))
        assertEquals("code", url.queryParameter("response_type"))
        assertEquals("st4te", url.queryParameter("state"))
        assertEquals("redgrab://reddit", url.queryParameter("redirect_uri"))
        assertEquals("permanent", url.queryParameter("duration"))
        assertEquals("identity read history mysubreddits", url.queryParameter("scope"))

        val store = Store()
        val fake = Fake { req ->
            if (req.url.encodedPath == "/api/v1/access_token") 200 to """{"access_token":"a1","refresh_token":"r1","expires_in":86400}"""
            else 200 to """{"name":"me_user"}"""
        }
        assertEquals("me_user", RedditApi(store, fake.http).exchangeCode("the-code"))
        assertEquals("r1", store.redditRefresh)
        assertEquals("me_user", store.redditUser)
        val form = fake.seen[0].body as okhttp3.FormBody
        val sent = (0 until form.size).associate { form.name(it) to form.value(it) }
        assertEquals(mapOf("grant_type" to "authorization_code", "code" to "the-code", "redirect_uri" to "redgrab://reddit"), sent)
    }

    @Test fun redgifsIdsAreReadOnlyFromVideoLinks() {
        assertEquals("happybluewhale", RedditApi.redgifsId("https://www.redgifs.com/watch/HappyBlueWhale"))
        assertEquals("happybluewhale", RedditApi.redgifsId("https://v3.redgifs.com/watch/happybluewhale#rel=user"))
        assertEquals("happybluewhale", RedditApi.redgifsId("https://redgifs.com/ifr/happybluewhale"))
        assertNull(RedditApi.redgifsId("https://i.redgifs.com/i/happybluewhale.jpg"))
        assertNull(RedditApi.redgifsId("https://www.redgifs.com/users/someone"))
        assertNull(RedditApi.redgifsId("https://notredgifs.com/watch/happybluewhale"))
        assertNull(RedditApi.redgifsId("/r/videos/comments/2def/"))
    }

    // ---- EroMe -------------------------------------------------------------------------

    private val eromeAlbum = """<html><head><meta property="og:title" content="Rock &amp; roll"></head><body>
        <div class="user-profile clearfix"><a id="user_name" href="https://www.erome.com/Some%20One">Some One</a>
          <p class="mt-10" id="tags"><a href="https://www.erome.com/search?q=%23first+tag">#first tag</a> <a href="https://www.erome.com/search?q=second">second</a></p></div>
        <div class="media-group" id="1"><div class="video"><video id="video_1" class="video-js" controls preload="none" poster="https://s11.erome.com/1234/NQgdlWvk/j7jlzmYB.jpg?v=1710720116">
          <source src="https://v11.erome.com/1234/NQgdlWvk/j7jlzmYB_480p.mp4" type='video/mp4' label='SD' res='480'></video></div></div>
        <div class="media-group" id="2"><div class="img"><img class="img-front lasyload" data-src="https://s11.erome.com/1234/NQgdlWvk/photo.jpg?v=1710720116"></div></div>
        <div class="media-group" id="3"><div class="video"><video id="video_3"><source src="https://v11.erome.com/1234/NQgdlWvk/Other_720p.mp4"></video></div></div>
        </body></html>"""

    @Test fun eromeSearchOpensAlbumsAndKeepsTheirVideos() {
        // 36 albums on the first list page (each linked twice, as on the site), so a second list page is expected later.
        val ids = (1..36).map { "Album%02d".format(it) }
        val fake = Fake { req ->
            when (req.url.pathSegments.first()) {
                "search" -> 200 to ids.joinToString("") { """<a class="album-link" href="https://www.erome.com/a/$it"></a><a href="https://www.erome.com/a/$it">t</a>""" }
                "a" -> if (req.url.pathSegments[1] == "Album02") 410 to "<h1>Album deleted</h1>" else 200 to eromeAlbum
                else -> 404 to ""
            }
        }
        val page = EromeApi(fake.http, pauseMs = 0).search(" cute girl ", null, 1)

        assertEquals("/search", fake.seen[0].url.encodedPath)
        assertEquals("cute girl", fake.seen[0].url.queryParameter("q"))
        assertEquals("1", fake.seen[0].url.queryParameter("page"))
        assertEquals("https://www.erome.com/", fake.seen[0].header("Referer"))
        assertNull(fake.seen[0].header("Origin"))
        // One list page, then albums until there are enough clips or five requests were made.
        assertEquals(listOf("/search", "/a/Album01", "/a/Album02", "/a/Album03", "/a/Album04"), fake.seen.map { it.url.encodedPath })
        assertEquals(6, page.clips.size) // two videos in each of three albums; the deleted album and the photo are left out
        assertEquals("2|" + ids.drop(4).joinToString(","), page.cursor)
        assertTrue(page.hasMore)

        val c = page.clips[0]
        assertTrue(c.id, Regex("er-album01-[0-9a-f]{10}").matches(c.id))
        assertTrue(Clip.validId(c.id))
        assertEquals(OnlineSource.EroMe, OnlineSource.of(c.id))
        assertEquals("https://v11.erome.com/1234/NQgdlWvk/j7jlzmYB_480p.mp4", c.hd)
        assertEquals("https://s11.erome.com/1234/NQgdlWvk/j7jlzmYB.jpg?v=1710720116", c.thumb)
        assertEquals("Rock & roll", c.description)
        assertEquals("Some One", c.user)
        assertEquals(listOf("first tag", "second"), c.tags)
        assertEquals(1710720116L, c.created)
        assertEquals(setOf("EroMe"), c.sources)
        assertFalse(c.isImage)
        assertNull(page.clips[1].thumb)
        assertEquals(6, page.clips.map { it.id }.toSet().size)

        // The next page carries on with the albums that weren't opened.
        val more = EromeApi(fake.http, pauseMs = 0).search("cute girl", page.cursor, 2)
        assertEquals("/a/Album05", fake.seen[5].url.encodedPath)
        assertTrue(more.cursor!!.startsWith("2|Album"))
    }

    @Test fun eromeUserPageEndsWhenTheListDoes() {
        val fake = Fake { req ->
            if (req.url.pathSegments.first() == "a") 200 to eromeAlbum
            else 200 to """<a href="https://www.erome.com/a/OnlyOne"></a><a href="https://www.erome.com/a/OnlyOne"></a>"""
        }
        val page = EromeApi(fake.http, pauseMs = 0).user("Some.One-1", null, 1)
        assertEquals("/Some.One-1", fake.seen[0].url.encodedPath)
        assertEquals("posts", fake.seen[0].url.queryParameter("t"))
        assertEquals(2, page.clips.size)
        assertNull(page.cursor)
        assertFalse(page.hasMore)
    }

    @Test fun eromeBotCheckIsReportedNotRetried() {
        val fake = Fake { 200 to "<html><head><title>Please wait a few moments</title></head></html>" }
        try { EromeApi(fake.http, pauseMs = 0).search("x", null, 1); fail("expected the check to be reported") }
        catch (e: SiteBlockedException) { assertTrue(e.message!!.contains("bot check")) }
        assertEquals(1, fake.seen.size)
    }

    // ---- 4chan --------------------------------------------------------------------------

    private val catalog = """[
      {"page":1,"threads":[
        {"no":100,"sticky":1,"sub":"Rules","com":"Read them","tim":1700000000000001,"ext":".png","images":0},
        {"no":201,"sub":"Dance &amp; music","com":"post yours<br>please","tim":1700000000000002,"ext":".webm","images":12},
        {"no":202,"com":"<span class=\"quote\">&gt;dance</span> party thread","images":3},
        {"no":203,"sub":"Text only","com":"nothing here","images":0}]},
      {"page":2,"threads":[{"no":204,"sub":"Cats","com":"meow","tim":1700000000000003,"ext":".gif","images":1}]}
    ]"""

    private fun thread(no: Int) = """{"posts":[
      {"no":$no,"sub":"Dance &amp; music","com":"post yours","tim":${no}0001,"ext":".webm","w":640,"h":360,"time":1700001000,"filename":"a"},
      {"no":${no}1,"com":"reply without a file","time":1700001100},
      {"no":${no}2,"tim":${no}0002,"ext":".jpg","w":10,"h":10,"time":1700001200},
      {"no":${no}3,"tim":${no}0003,"ext":".gif","w":200,"h":100,"time":1700001300},
      {"no":${no}4,"tim":${no}0004,"ext":".mp4","w":720,"h":1280,"time":1700001400},
      {"no":${no}5,"tim":${no}0005,"ext":".webm","w":1,"h":1,"time":1700001500,"filedeleted":1}
    ]}"""

    @Test fun fourChanCatalogIsFilteredAndThreadsBecomeClips() {
        val fake = Fake { req ->
            val last = req.url.pathSegments.last()
            when {
                last == "catalog.json" -> 200 to catalog
                last == "202.json" -> 404 to "" // gone since the catalog was read
                else -> 200 to thread(last.removeSuffix(".json").toInt())
            }
        }
        val page = FourChanApi(fake.http, pauseMs = 0).threads(" DANCE ", null, 1)

        assertEquals("a.4cdn.org", fake.seen[0].url.host)
        assertEquals(listOf("/gif/catalog.json", "/gif/thread/201.json", "/gif/thread/202.json"), fake.seen.map { it.url.encodedPath })
        assertNull(fake.seen[0].header("Referer")); assertNull(fake.seen[0].header("Origin"))
        // Thread 201 has a WebM, a GIF and an MP4; the photo, the deleted file and the text reply are left out.
        assertEquals(listOf("4c-gif-2010001", "4c-gif-2010003", "4c-gif-2010004"), page.clips.map { it.id })
        assertNull(page.cursor)
        assertFalse(page.hasMore)
        val c = page.clips[0]
        assertTrue(Clip.validId(c.id))
        assertEquals(OnlineSource.FourChan, OnlineSource.of(c.id))
        assertEquals("https://i.4cdn.org/gif/2010001.webm", c.hd)
        assertEquals("https://i.4cdn.org/gif/2010001s.jpg", c.thumb)
        assertEquals("Dance & music", c.description)
        assertEquals(640, c.width); assertEquals(360, c.height)
        assertEquals(1700001000L, c.created)
        assertEquals(setOf("4chan"), c.sources)
        assertFalse(c.isImage)
        assertEquals("webm", c.fileExt)
        assertTrue(OnlineSource.keepsOwnFile(c))
        assertTrue(page.clips[1].isImage)
        assertFalse(page.clips[1].hasAudio)

        // No search: every thread with files, in the catalog's order, except pinned ones; the rest waits in the cursor.
        val all = FourChanApi(Fake { req -> if (req.url.pathSegments.last() == "catalog.json") 200 to catalog else 200 to """{"posts":[]}""" }.http, pauseMs = 0)
            .threads("", null, 1)
        assertTrue(all.clips.isEmpty())
        assertNull(all.cursor) // 201, 202 and 204 were all opened within the request limit
        // Continuing: threads are opened until there are enough clips (here four threads of three); the rest stays in the cursor.
        val next = FourChanApi(fake.http, pauseMs = 0).threads("", "204,205,206,207,208", 2)
        assertEquals(12, next.clips.size)
        assertEquals("208", next.cursor)
        assertTrue(next.hasMore)
    }

    @Test fun onlyPicturesAnd4chanFilesAreKeptAsTheirOwnFile() {
        assertTrue(OnlineSource.keepsOwnFile(clip("sx-1", "https://imagex1.sx.cdn.live/a/1.webp")))
        assertTrue(OnlineSource.keepsOwnFile(clip("4c-gif-1", "https://i.4cdn.org/gif/1.mp4")))
        assertFalse(OnlineSource.keepsOwnFile(clip("er-a-0123456789", "https://v1.erome.com/1/a/x_720p.mp4")))
        assertFalse(OnlineSource.keepsOwnFile(clip("ff-1", "https://x.b-cdn.net/v/playlist.m3u8")))
        assertFalse(OnlineSource.keepsOwnFile(clip("abc", "https://media.redgifs.com/Abc.mp4")))
        assertEquals(setOf("Discover", "Reddit", "FikFap", "sex.com", "EroMe", "4chan"), OnlineSource.SAVED_LABELS)
    }

    // ---- what's typed in the search box -------------------------------------------------

    @Test fun typedTextOpensTheRightFeed() {
        assertEquals(Feed.Search("big"), Feed.fromQuery(OnlineSource.RedGifs, " big "))
        assertEquals(Feed.Subreddit("videos"), Feed.fromQuery(OnlineSource.Reddit, "r/videos"))
        assertEquals(Feed.Subreddit("videos"), Feed.fromQuery(OnlineSource.Reddit, " /r/videos/ "))
        assertEquals(Feed.Subreddit("a+b_c"), Feed.fromQuery(OnlineSource.Reddit, "a+b_c"))
        assertEquals(Feed.RedditUser("some-one"), Feed.fromQuery(OnlineSource.Reddit, "u/some-one"))
        assertEquals(Feed.RedditHome, Feed.fromQuery(OnlineSource.Reddit, "  "))
        assertEquals(Feed.FikFapTag("foo-bar"), Feed.fromQuery(OnlineSource.FikFap, "#foo-bar"))
        assertEquals(Feed.FikFapUser("Hot-sauce-34"), Feed.fromQuery(OnlineSource.FikFap, "@Hot-sauce-34"))
        assertEquals(Feed.SexSearch("bed"), Feed.fromQuery(OnlineSource.SexCom, "bed"))
        assertEquals(Feed.EromeSearch("cute girl"), Feed.fromQuery(OnlineSource.EroMe, " cute girl "))
        assertEquals(Feed.EromeUser("Some.One-1"), Feed.fromQuery(OnlineSource.EroMe, "@Some.One-1/?x"))
        assertEquals(Feed.ChanThreads("dance"), Feed.fromQuery(OnlineSource.FourChan, "dance"))
        assertTrue(Feed.home(OnlineSource.EroMe).needsQuery)
        assertFalse(Feed.home(OnlineSource.FourChan).needsQuery)
        assertTrue(Feed.home(OnlineSource.FikFap).needsQuery)
        assertTrue(Feed.home(OnlineSource.SexCom).needsQuery)
        assertFalse(Feed.home(OnlineSource.Reddit).needsQuery)
        // What a feed puts in the search box opens the same feed again (that's how "recent" works).
        for (f in listOf(Feed.Subreddit("videos"), Feed.RedditUser("x"), Feed.FikFapTag("t"), Feed.FikFapUser("u"), Feed.SexSearch("q"),
            Feed.EromeSearch("q"), Feed.EromeUser("u"), Feed.ChanThreads("q"))) {
            assertEquals(f, Feed.fromQuery(f.source, f.query))
        }
    }

    // ---- streams ------------------------------------------------------------------------

    @Test fun streamSaverTakesTheBestVersionAndReadsItsPiecesInOrder() {
        val base = "https://vz-1-178.b-cdn.net/bcdn_token=t&expires=9/vid"
        val fake = Fake { req ->
            val name = req.url.pathSegments.takeLast(2).joinToString("/")
            when {
                name == "vid/playlist.m3u8" -> 200 to "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=800000,RESOLUTION=480x854\n480p/video.m3u8\n" +
                    "#EXT-X-STREAM-INF:BANDWIDTH=2500000,RESOLUTION=720x1280,CODECS=\"avc1.64001f,mp4a.40.2\"\n720p/video.m3u8\n"
                name == "720p/video.m3u8" -> 200 to "#EXTM3U\n#EXT-X-TARGETDURATION:4\n#EXTINF:4.0,\nvideo0.ts\n#EXTINF:2.5,\nvideo1.ts\n#EXT-X-ENDLIST\n"
                name == "720p/video0.ts" -> 200 to "AAAA"
                name == "720p/video1.ts" -> 200 to "BB"
                else -> 404 to ""
            }
        }
        val dir = File(System.getProperty("java.io.tmpdir"), "hls-test-${System.nanoTime()}")
        var bytes = 0
        try {
            // On a computer there's no Android to repack the pieces, so it stops there; everything before is real.
            runBlocking { HlsSaver.save(fake.http, "$base/playlist.m3u8", dir, "ff-1-hd") { bytes += it } }
            fail("expected the repacking step to be unavailable off the phone")
        } catch (e: HlsSaver.Unsupported) {
        } finally { dir.deleteRecursively() }
        assertEquals(listOf("vid/playlist.m3u8", "720p/video.m3u8", "720p/video0.ts", "720p/video1.ts"),
            fake.seen.map { it.url.pathSegments.takeLast(2).joinToString("/") })
        assertEquals(6, bytes)
        assertTrue(fake.seen.all { it.header("Referer") == "https://fikfap.com/" && it.header("Origin") == "https://fikfap.com" })
    }

    @Test fun protectedAndLiveStreamsAreRefused() {
        fun save(playlist: String) = runBlocking {
            HlsSaver.save(Fake { 200 to playlist }.http, "https://x.b-cdn.net/v/playlist.m3u8", File(System.getProperty("java.io.tmpdir"), "hls-test-x"), "t") { }
        }
        for (bad in listOf(
            "#EXTM3U\n#EXT-X-KEY:METHOD=AES-128,URI=\"key\"\n#EXTINF:4,\na.ts\n#EXT-X-ENDLIST\n",
            "#EXTM3U\n#EXTINF:4,\na.ts\n", // no end marker: still being broadcast
        )) {
            try { save(bad); fail("expected a refusal") } catch (e: HlsSaver.Unsupported) { }
        }
    }
}
