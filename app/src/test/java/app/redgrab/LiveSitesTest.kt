package app.redgrab

import okhttp3.Request
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Runs the app's code for the sites other than RedGifs against the real sites. Skipped unless
 * asked for (REDGRAB_LIVE_SITES=1); the workflow "Live sites" runs it on GitHub's runner.
 *
 * It prints counts only: no titles, names or addresses. A site that answers with a bot check or
 * refuses the runner is reported as BLOCKED and is not a failure of the code; nothing is done to
 * get around it. Reddit needs a client ID (REDDIT_CLIENT_ID) and is skipped without one.
 */
class LiveSitesTest {
    private val ua = "Mozilla/5.0 (Linux; Android 16; Pixel 9) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/141.0.0.0 Mobile Safari/537.36"
    private val http = RedGifsApi.clientFor(ua)
    private val lines = ArrayList<String>()
    private var failures = 0

    private fun say(s: String) { println("LIVE  $s"); lines += s }

    /** Runs one check; tells a refusal by the site from a fault in the code. */
    private fun <T> check(site: String, what: String, body: () -> T): T? = try {
        body()
    } catch (e: SiteBlockedException) { say("$site · $what: BLOCKED (bot check)"); null
    } catch (e: HttpException) {
        if (e.code == 403 || e.code == 429 || e.code == 503) say("$site · $what: BLOCKED (HTTP ${e.code})") else { failures++; say("$site · $what: FAIL (HTTP ${e.code}: ${e.message?.take(160)})") }
        null
    } catch (e: Exception) { failures++; say("$site · $what: FAIL (${e.javaClass.simpleName}: ${e.message?.take(120)})"); null }

    private fun page(site: String, what: String, min: Int = 1, load: () -> OnlinePage): OnlinePage? = check(site, what) {
        val p = load()
        val c = p.clips
        val ok = c.size >= min && c.all { Clip.validId(it.id) && (it.hd != null || it.sd != null) } && c.map { it.id }.toSet().size == c.size
        say("$site · $what: ${if (ok) "ok" else "FAIL"} · ${c.size} clips, more=${p.hasMore}, thumbnail ${c.count { it.thumb != null }}, size ${c.count { it.width > 0 }}, " +
            "length ${c.count { it.duration > 0 }}, creator ${c.count { it.user.isNotEmpty() }}, tags ${c.count { it.tags.isNotEmpty() }}, labelled ${c.count { it.sexuality.isNotEmpty() }}")
        if (!ok) failures++
        p
    }

    /** The first bytes of a clip's file, with the headers the app sends: is it really a video, picture or playlist? */
    private fun media(site: String, clip: Clip?) {
        val url = clip?.hd ?: clip?.sd ?: return
        check(site, "file") {
            RedGifsApi.download(http, url).use { r ->
                val head = ByteArray(16).also { r.body!!.byteStream().read(it) }
                val text = String(head, Charsets.ISO_8859_1)
                val kind = when {
                    "ftyp" in text -> "MP4"; text.startsWith("#EXTM3U") -> "stream playlist"; text.startsWith("GIF8") -> "GIF"
                    head[0] == 0x1A.toByte() && head[1] == 0x45.toByte() -> "WebM"; text.startsWith("RIFF") -> "WebP"; else -> "unknown"
                }
                say("$site · file: ${if (kind == "unknown") "FAIL" else "ok"} · $kind, type ${r.header("Content-Type")}, ${r.header("Content-Length") ?: "?"} bytes")
                if (kind == "unknown") failures++
            }
        }
        clip?.thumb?.let { t -> check(site, "thumbnail") { http.newCall(Request.Builder().url(t).build()).execute().use { r -> say("$site · thumbnail: ${if (r.isSuccessful) "ok" else "FAIL (HTTP ${r.code})"}"); if (!r.isSuccessful) failures++ } } }
    }

    @Test fun otherSitesAnswerAsTheCodeExpects() {
        assumeTrue("set REDGRAB_LIVE_SITES=1 to run against the real sites", System.getenv("REDGRAB_LIVE_SITES") == "1")

        // 4chan
        val boards = check("4chan", "boards") { FourChanApi(http).boards().also { b -> say("4chan · boards: ${if (b.size > 20 && b.any { it.name == "gif" && it.adult }) "ok" else "FAIL"} · ${b.size}, ${b.count { it.adult }} not work-safe"); if (b.size <= 20) failures++ } }
        val chan = page("4chan", "/gif/ threads") { FourChanApi(http).threads("", null, 1) }
        if (chan?.cursor != null) page("4chan", "/gif/ next page", min = 0) { FourChanApi(http).threads("", chan.cursor, 2) }
        if (boards != null) page("4chan", "another board (wsg)") { FourChanApi(http, "wsg").threads("", null, 1) }
        media("4chan", chan?.clips?.firstOrNull())

        // EroMe
        val hot = page("EroMe", "Hot") { EromeApi(http).explore(false, null, 1) }
        page("EroMe", "New") { EromeApi(http).explore(true, null, 1) }
        val found = page("EroMe", "search") { EromeApi(http).search("dance", null, 1) }
        if (found?.cursor != null) page("EroMe", "search, next page", min = 0) { EromeApi(http).search("dance", found.cursor, 2) }
        (hot ?: found)?.clips?.firstOrNull { it.user.isNotEmpty() }?.let { c -> page("EroMe", "an uploader") { EromeApi(http).user(c.user, null, 1) } }
        media("EroMe", (hot ?: found)?.clips?.firstOrNull())

        // sex.com
        val sx = page("sex.com", "search") { SexComApi(http).search("dance", 1) }
        page("sex.com", "search, page 2") { SexComApi(http).search("dance", 2) }
        media("sex.com", sx?.clips?.firstOrNull())

        // FikFap (a post and a user known from gallery-dl's own tests, then hashtags)
        check("FikFap", "one post") { val p = FikFapApi(http).post("1429486"); say("FikFap · one post: ${if (p != null) "ok" else "FAIL (no playable post)"}"); if (p == null) failures++ }
        val user = page("FikFap", "a user") { FikFapApi(http).userPosts("alinevs", null, 1) }
        if (user?.cursor != null) page("FikFap", "a user, next page", min = 0) { FikFapApi(http).userPosts("alinevs", user.cursor, 2) }
        // A hashtag from a real post, so a 404 can't be the test's own guess at a name.
        val label = user?.clips?.flatMap { it.tags }?.groupingBy { it }?.eachCount()?.maxByOrNull { it.value }?.key
        say("FikFap · a hashtag was found on the user's posts: ${label != null}")
        val tag = page("FikFap", "hashtag") { FikFapApi(http).tagPosts(label ?: "amateur", null, 1) }
        if (tag?.cursor != null) page("FikFap", "hashtag, next page", min = 0) { FikFapApi(http).tagPosts(label ?: "amateur", tag.cursor, 2) }
        media("FikFap", (tag ?: user)?.clips?.firstOrNull())

        // Reddit
        val id = System.getenv("REDDIT_CLIENT_ID").orEmpty()
        if (id.isBlank()) say("Reddit: SKIPPED (no client ID set)") else {
            val store = object : RedditStore {
                override var redditClientId: String? = id; override var redditAccess: String? = null
                override var redditAccessExp: Long = 0; override var redditRefresh: String? = null; override var redditUser: String? = null
            }
            val api = RedditApi(store, http)
            val sub = page("Reddit", "a subreddit") { api.listing(Feed.Subreddit("gifs"), OnlineOrder.Trending, null, 1) }
            if (sub?.cursor != null) page("Reddit", "subreddit, next page", min = 0) { api.listing(Feed.Subreddit("gifs"), OnlineOrder.Trending, sub.cursor, 2) }
            page("Reddit", "front page", min = 0) { api.listing(Feed.RedditHome, OnlineOrder.Trending, null, 1) }
            check("Reddit", "subreddit search") { val s = api.searchSubreddits("gif"); say("Reddit · subreddit search: ${if (s.isNotEmpty()) "ok" else "FAIL"} · ${s.size}"); if (s.isEmpty()) failures++ }
            media("Reddit", sub?.clips?.firstOrNull { OnlineSource.of(it.id) == OnlineSource.Reddit && it.hd != null })
        }

        say("summary: ${lines.count { ": ok" in it }} ok, ${lines.count { "BLOCKED" in it }} blocked, ${lines.count { "FAIL" in it }} failed")
        if (failures > 0) fail("$failures check(s) failed:\n" + lines.filter { "FAIL" in it }.joinToString("\n"))
    }

    /**
     * Open questions about sex.com's own listings, asked without an account. Prints shapes only (status, counts,
     * field names, API paths): never titles, names or file addresses. Nothing here can fail the run.
     * Settled so far: the search needs two letters or more and ignores "order"; orientation is straight, gay or trans;
     * GET /portal/api/gifs?order=likeCount&sexual-orientation=… lists GIFs with no search term.
     */
    @Test fun sexComListings() {
        assumeTrue("set REDGRAB_LIVE_SITES=1 to run against the real sites", System.getenv("REDGRAB_LIVE_SITES") == "1")
        val api = "https://www.sex.com/portal/api"
        fun fetch(url: String, json: Boolean = true, pause: Long = 800): Pair<Int, String>? = runCatching {
            Thread.sleep(pause)
            http.newCall(Request.Builder().url(url).header("Accept", if (json) "application/json" else "*/*").build()).execute().use { r ->
                r.code to String(r.body?.byteStream()?.readNBytes(6_000_000) ?: ByteArray(0), Charsets.UTF_8) }
        }.getOrNull()
        fun ids(body: String): List<String> = runCatching {
            val a = org.json.JSONObject(body).optJSONArray("data") ?: return emptyList()
            (0 until a.length()).mapNotNull { a.optJSONObject(it)?.opt("id")?.toString() }
        }.getOrDefault(emptyList())
        fun list(label: String, url: String): List<String> {
            val (code, body) = fetch(url) ?: return emptyList<String>().also { say("sx3 $label → no answer") }
            val got = ids(body)
            say("sx3 $label → $code items=${got.size}" + (if (code in 200..299) " keys=" + runCatching { org.json.JSONObject(body).keys().asSequence().sorted().toList() }.getOrNull() else ""))
            return got
        }
        val base = "order=likeCount&sexual-orientation=straight"
        // 1. Paging of the plain listing
        val p1 = list("gifs", "$api/gifs?$base")
        for ((label, extra) in listOf("page=2" to "page=2", "page=2&limit=40" to "page=2&limit=40", "offset=40" to "offset=40", "limit=80" to "limit=80", "page=3&limit=40" to "page=3&limit=40")) {
            val got = list("gifs &$extra", "$api/gifs?$base&$extra")
            if (got.isNotEmpty()) say("sx3   …${got.count { it in p1 }} of ${got.size} are also on page 1")
        }
        list("gifs gay", "$api/gifs?order=likeCount&sexual-orientation=gay"); list("gifs trans", "$api/gifs?order=likeCount&sexual-orientation=trans")
        list("pictures", "$api/pictures?$base")
        // 2. Categories: does the page carry (id, name) pairs, and does a tag listing answer for such an id?
        val cats = fetch("https://www.sex.com/en/categories", json = false)?.second.orEmpty()
        val pairs = Regex("\\\\?\"id\\\\?\":(\\d+),\\\\?\"name\\\\?\":\\\\?\"([^\"\\\\]{1,40})").findAll(cats).map { it.groupValues[1] to it.groupValues[2] }.distinctBy { it.first }.toList()
        val around = Regex("\\\\?\"id\\\\?\":\\d+,((?:\\\\?\"[A-Za-z]+\\\\?\":[^,]{0,60},){0,6})").find(cats)?.groupValues?.get(1).orEmpty()
        say("sx3 categories: ${pairs.size} (id, name) pairs; fields after an id: " + Regex("\\\\?\"([A-Za-z]+)\\\\?\":").findAll(around).map { it.groupValues[1] }.toList())
        say("sx3 categories: fields in the page " + Regex("\\\\?\"([A-Za-z]{2,24})\\\\?\":").findAll(cats).map { it.groupValues[1] }.groupingBy { it }.eachCount().entries.filter { it.value in 100..140 }.joinToString { "${it.key}×${it.value}" })
        val anyId = pairs.firstOrNull()?.first ?: Regex("\\\\?\"id\\\\?\":(\\d+)").find(cats)?.groupValues?.get(1)
        if (anyId != null) {
            val t1 = list("tags/{id}/gifs", "$api/tags/$anyId/gifs?$base")
            val t2 = list("tags/{id}/gifs &page=2", "$api/tags/$anyId/gifs?$base&page=2")
            if (t2.isNotEmpty()) say("sx3   …${t2.count { it in t1 }} of ${t2.size} are also on page 1")
            list("tags/{id}/gifs &page=2&limit=40", "$api/tags/$anyId/gifs?$base&page=2&limit=40")
            list("tags/{id}/pictures", "$api/tags/$anyId/pictures?$base")
        }
        // 3. Which API paths do the site's scripts name? (favourites, playlists and other account lists)
        val page = fetch("https://www.sex.com/en/gifs", json = false)?.second.orEmpty()
        val scripts = Regex("(?:src|href)=\"([^\"]+\\.js)[\"?]").findAll(page).map { it.groupValues[1] }.distinct().toList()
        val paths = sortedSetOf<String>()
        var read = 0
        for (src in scripts.take(140)) {
            val url = when { src.startsWith("http") -> src; src.startsWith("//") -> "https:$src"; else -> "https://www.sex.com$src" }
            val (code, body) = fetch(url, json = false, pause = 120) ?: continue
            if (code != 200) continue
            read++
            Regex("/portal/api/[A-Za-z0-9_/\\-:\\$\\{\\}\\.]{2,80}").findAll(body).forEach { paths += it.value }
            Regex("[\"'`](/?(?:api|user|me|my-content|favorites|playlists)/[A-Za-z0-9_/\\-:\\$\\{\\}\\.]{2,70})[\"'`]").findAll(body).forEach { paths += it.groupValues[1] }
        }
        say("sx3 scripts: ${scripts.size} named, $read read, ${paths.size} API-like paths")
        paths.chunked(12).forEach { say("sx3 paths: " + it.joinToString("  ")) }
    }
}
