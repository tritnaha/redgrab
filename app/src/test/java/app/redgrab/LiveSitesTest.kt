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
     * Questions about sex.com that reading open-source code couldn't settle: is there a listing with no
     * search term, which orders exist, what an item carries, how tags and boards are reached. Asks the
     * site's public pages and API without an account and prints shapes only (status, field names, counts,
     * API paths): never titles, names or file addresses. Nothing here can fail the run.
     */
    @Test fun sexComOpenQuestions() {
        assumeTrue("set REDGRAB_LIVE_SITES=1 to run against the real sites", System.getenv("REDGRAB_LIVE_SITES") == "1")
        val plain = http.newBuilder().followRedirects(false).followSslRedirects(false).build()
        val api = "https://www.sex.com/portal/api"
        class Got(val code: Int, val type: String, val body: String, val location: String?)
        fun get(url: String, json: Boolean = true, redirects: Boolean = true): Got? = runCatching {
            Thread.sleep(900)
            val req = Request.Builder().url(url).header("Accept", if (json) "application/json" else "text/html").build()
            (if (redirects) http else plain).newCall(req).execute().use { r ->
                val bytes = r.body?.byteStream()?.readNBytes(4_000_000) ?: ByteArray(0)
                Got(r.code, r.header("Content-Type").orEmpty().substringBefore(';'), String(bytes, Charsets.UTF_8), r.header("Location")?.substringBefore('?'))
            }
        }.onFailure { say("sx? $url → ${it.javaClass.simpleName}") }.getOrNull()
        fun ids(body: String): List<String> = runCatching {
            val a = org.json.JSONObject(body).optJSONArray("data") ?: return emptyList()
            (0 until a.length()).mapNotNull { a.optJSONObject(it)?.opt("id")?.toString() }
        }.getOrDefault(emptyList())
        fun dates(body: String): List<String> = runCatching {
            val a = org.json.JSONObject(body).optJSONArray("data") ?: return emptyList()
            (0 until a.length()).mapNotNull { i -> Regex("/(\\d{4})/(\\d{2})/(\\d{2})/").find(a.optJSONObject(i)?.optString("uri").orEmpty())?.let { m -> m.groupValues.drop(1).joinToString("-") } }
        }.getOrDefault(emptyList())
        /** One line about a JSON answer: status, keys, how many, pages, the first item's field names (with the kind of each value). */
        fun shape(label: String, url: String): Got? {
            val g = get(url) ?: return null
            val line = StringBuilder("sx? $label → ${g.code} ${g.type}")
            if (g.code in 200..299) runCatching {
                val j = org.json.JSONObject(g.body)
                val data = j.optJSONArray("data")
                line.append(" keys=${j.keys().asSequence().sorted().toList()}")
                if (data != null) {
                    line.append(" items=${data.length()} pages=${j.optJSONObject("paging")?.optInt("numberOfPages")}")
                    data.optJSONObject(0)?.let { o -> line.append(" fields=" + o.keys().asSequence().sorted().map { k -> "$k:${o.opt(k)?.javaClass?.simpleName?.take(6)}" }.toList()) }
                    val d = dates(g.body)
                    if (d.isNotEmpty()) line.append(" fileDates=${d.min()}..${d.max()} newestFirst=${d == d.sortedDescending()}")
                }
            }.onFailure { line.append(" (not JSON, ${g.body.length} chars)") }
            // A refusal's own words say what the site wants; they hold no titles or names.
            else line.append(" loc=${g.location} body=" + g.body.take(220).replace(Regex("\\s+"), " "))
            say(line.toString())
            return g
        }
        val q = "sexual-orientation=straight&page=1&limit=40"
        val base = shape("search=dance likeCount", "$api/gifs/search?search=dance&order=likeCount&$q")
        val baseIds = base?.let { ids(it.body) }.orEmpty()

        // 1. A listing with no search term
        shape("no search param", "$api/gifs/search?order=likeCount&$q")
        shape("only page+limit", "$api/gifs/search?page=1&limit=40")
        shape("no params", "$api/gifs/search")
        shape("empty search=", "$api/gifs/search?search=&order=likeCount&$q")
        shape("search=a", "$api/gifs/search?search=a&order=likeCount&$q")
        shape("search=ab", "$api/gifs/search?search=ab&order=likeCount&$q")
        // 2. Orders: the same search under other names; same first clips = the name was ignored
        for (o in listOf("createdAt", "newest", "latest", "recent", "date", "new", "viewCount", "views", "trending", "popular", "relevance", "id", "random", "bogus-order")) {
            val g = get("$api/gifs/search?search=dance&order=$o&$q") ?: continue
            val got = ids(g.body); val d = dates(g.body)
            say("sx? order=$o → ${g.code} items=${got.size} sameFirst10AsLikeCount=${got.take(10) == baseIds.take(10)} shared=${got.count { it in baseIds }}" +
                (if (d.isNotEmpty()) " fileDates=${d.min()}..${d.max()} newestFirst=${d == d.sortedDescending()}" else "") +
                if (g.code !in 200..299) " body=" + g.body.take(220).replace(Regex("\\s+"), " ") else "")
        }
        for (extra in listOf("sort=latest", "sort=popular", "period=week", "time=week", "sub=all&sort=popular")) {
            val g = get("$api/gifs/search?search=dance&$extra&$q") ?: continue
            val got = ids(g.body)
            say("sx? search=dance&$extra (no order) → ${g.code} items=${got.size} sameFirst10AsLikeCount=${got.take(10) == baseIds.take(10)}")
        }
        // 3. Orientation
        shape("orientation left out", "$api/gifs/search?search=dance&order=likeCount&page=1&limit=40")
        for (o in listOf("gay", "lesbian", "trans", "all", "bogus")) {
            val g = get("$api/gifs/search?search=dance&order=likeCount&sexual-orientation=$o&page=1&limit=40") ?: continue
            say("sx? orientation=$o → ${g.code} items=${ids(g.body).size} shared=${ids(g.body).count { it in baseIds }}" + if (g.code !in 200..299) " body=" + g.body.take(160).replace(Regex("\\s+"), " ") else "")
        }
        // 4. Pictures, videos, and guesses at other listings
        shape("pictures search", "$api/pictures/search?search=dance&order=likeCount&$q")
        shape("videos search", "$api/videos/search?search=dance&order=likeCount&$q")
        for (path in listOf("gifs", "gifs/trending", "gifs/popular", "gifs/latest", "gifs/new", "gifs/top", "gifs/feed", "tags", "tags/search?search=da", "gifs/tags", "categories", "gifs/categories",
            "boards/search?search=dance", "galleries/search?search=dance", "collections/search?search=dance", "users/search?search=dance", "search?search=dance", "search/suggestions?search=da", "autocomplete?search=da")) {
            shape("guess /$path", "$api/$path")
        }
        // 5. The site's own pages: which API paths do they and their scripts name?
        val seenPaths = sortedSetOf<String>()
        val words = listOf("galler", "board", "collection", "playlist", "favorite", "favourite", "bookmark", "likeCount", "createdAt", "trending", "sexual-orientation")
        fun scan(label: String, text: String) {
            Regex("/portal/api/[A-Za-z0-9_/\\-\\$\\{\\}\\.:]+").findAll(text).forEach { seenPaths += it.value.take(90) }
            Regex("[\"'`](/api/[A-Za-z0-9_/\\-]+)").findAll(text).forEach { seenPaths += it.groupValues[1].take(90) }
            say("sx? $label: ${text.length} chars, words " + words.associateWith { w -> Regex(w, RegexOption.IGNORE_CASE).findAll(text).count() }.filterValues { it > 0 })
        }
        val scripts = linkedSetOf<String>()
        for (pagePath in listOf("/en/gifs", "/en/categories", "/en")) {
            val g = get("https://www.sex.com$pagePath", json = false, redirects = false) ?: continue
            say("sx? page $pagePath → ${g.code} ${g.type} loc=${g.location}")
            if (g.code != 200) continue
            scan("page $pagePath", g.body)
            // How the page links onward: the first two parts of each link's path, counted.
            val links = Regex("href=\"(/[a-z]{2}/[a-z\\-]+)(?:[/?\"])").findAll(g.body).map { it.groupValues[1] }.groupingBy { it }.eachCount().entries.sortedByDescending { it.value }.take(25)
            say("sx? page $pagePath link kinds: " + links.joinToString { "${it.key}×${it.value}" })
            say("sx? page $pagePath query names in links: " + Regex("[?&]([a-zA-Z\\-]+)=").findAll(g.body).map { it.groupValues[1] }.groupingBy { it }.eachCount().entries.sortedByDescending { it.value }.take(25).joinToString { "${it.key}×${it.value}" })
            Regex("<script[^>]+src=\"([^\"]+\\.js[^\"]*)\"").findAll(g.body).forEach { scripts += it.groupValues[1] }
        }
        say("sx? scripts named by the pages: ${scripts.size}")
        var read = 0
        for (src in scripts.take(40)) {
            val url = when { src.startsWith("http") -> src; src.startsWith("//") -> "https:$src"; else -> "https://www.sex.com$src" }
            val g = get(url, json = false) ?: continue
            if (g.code == 200 && ("portal/api" in g.body || "likeCount" in g.body)) { read++; scan("script ${read}", g.body)
                // What stands next to the order and orientation names in the site's own code.
                for (key in listOf("likeCount", "sexual-orientation", "order:", "order=")) Regex("[A-Za-z0-9_\"'`:,\\[\\]\\-= ]{0,90}" + Regex.escape(key) + "[A-Za-z0-9_\"'`:,\\[\\]\\-= ]{0,140}").findAll(g.body).take(4)
                    .forEach { say("sx? script $read near $key: " + it.value) }
            }
        }
        say("sx? API paths named by pages and scripts (${seenPaths.size}): " + seenPaths.take(120).joinToString("  "))
        // 6. One picture's own page: tags, and how a tag links onward
        baseIds.take(3).forEach { id ->
            val g = get("https://www.sex.com/en/gifs/$id", json = false, redirects = false) ?: return@forEach
            val part = g.body.substringAfter("</h1>", "").substringBefore("</section>", "")
            val hrefs = Regex("href=\"(/[^\"]*)\"").findAll(part).map { it.groupValues[1] }.toList()
            say("sx? a picture's page → ${g.code} loc=${g.location} tag texts=${Regex(">([^<>]+)<").findAll(part).count { it.groupValues[1].isNotBlank() }} links=${hrefs.size} link shapes=" +
                hrefs.map { h -> h.substringBefore('?').split('/').take(4).joinToString("/") { seg -> if (seg.any(Char::isDigit) || seg.length > 14) "*" else seg } + if ('?' in h) "?" + h.substringAfter('?').substringBefore('=') + "=" else "" }
                    .groupingBy { it }.eachCount().entries.sortedByDescending { it.value }.take(8).joinToString { "${it.key}×${it.value}" })
        }
        // 7. The real GIF behind a WebP: is it there, how much bigger?
        runCatching {
            val a = org.json.JSONObject(base!!.body).getJSONArray("data")
            (0 until minOf(4, a.length())).forEach { i ->
                val uri = a.getJSONObject(i).optString("uri"); if (!uri.endsWith(".webp")) return@forEach
                fun size(u: String) = runCatching { Thread.sleep(600); http.newCall(Request.Builder().url(SexComApi.CDN + u).head().build()).execute().use { r -> "${r.code} ${r.header("Content-Type")} ${r.header("Content-Length") ?: "?"}" } }.getOrDefault("error")
                say("sx? file $i: webp ${size(uri)} · gif ${size(uri.dropLast(4) + "gif")}")
            }
        }
        // 8. The old site's listings, without following a redirect
        for ((label, url) in listOf("old popular GIFs" to "https://www.sex.com/gifs/?sort=popular&sub=all", "old latest GIFs" to "https://www.sex.com/gifs/?sort=latest&sub=all",
            "old board search" to "https://www.sex.com/search/boards?query=dance", "old GIF search" to "https://www.sex.com/search/gifs?query=dance&sort=latest")) {
            val g = get(url, json = false, redirects = false) ?: continue
            say("sx? $label → ${g.code} loc=${g.location} pins=${Regex("class=\"image_wrapper\"").findAll(g.body).count()} next=${"rel=\"next\"" in g.body}")
        }
    }

    /** Second round: the two listings the site's own pages name, /portal/api/gifs and /portal/api/tags/:tagId/gifs. Shapes only. */
    @Test fun sexComListings() {
        assumeTrue("set REDGRAB_LIVE_SITES=1 to run against the real sites", System.getenv("REDGRAB_LIVE_SITES") == "1")
        val api = "https://www.sex.com/portal/api"
        fun fetch(url: String, json: Boolean = true): Pair<Int, String>? = runCatching {
            Thread.sleep(900)
            http.newCall(Request.Builder().url(url).header("Accept", if (json) "application/json" else "text/html").build()).execute().use { r ->
                r.code to String(r.body?.byteStream()?.readNBytes(4_000_000) ?: ByteArray(0), Charsets.UTF_8) }
        }.onFailure { say("sx2 $url → ${it.javaClass.simpleName}") }.getOrNull()
        fun describe(label: String, url: String): List<String> {
            val (code, body) = fetch(url) ?: return emptyList()
            val line = StringBuilder("sx2 $label → $code")
            var ids = emptyList<String>()
            if (code in 200..299) runCatching {
                val j = org.json.JSONObject(body); val data = j.optJSONArray("data")
                line.append(" keys=${j.keys().asSequence().sorted().toList()} paging=${j.optJSONObject("paging")?.keys()?.asSequence()?.sorted()?.toList()} pages=${j.optJSONObject("paging")?.optInt("numberOfPages")}")
                if (data != null) {
                    ids = (0 until data.length()).mapNotNull { data.optJSONObject(it)?.opt("id")?.toString() }
                    val d = (0 until data.length()).mapNotNull { n -> Regex("/(\\d{4})/(\\d{2})/(\\d{2})/").find(data.optJSONObject(n)?.optString("uri").orEmpty())?.groupValues?.drop(1)?.joinToString("-") }
                    line.append(" items=${data.length()} fields=" + data.optJSONObject(0)?.let { o -> o.keys().asSequence().sorted().map { k -> "$k:${o.opt(k)?.javaClass?.simpleName?.take(6)}" }.toList() })
                    if (d.isNotEmpty()) line.append(" fileDates=${d.min()}..${d.max()} newestFirst=${d == d.sortedDescending()}")
                    line.append(" ext=" + (0 until data.length()).map { data.optJSONObject(it)?.optString("uri").orEmpty().substringAfterLast('.') }.groupingBy { it }.eachCount())
                }
            }.onFailure { line.append(" (not JSON, ${body.length} chars)") } else line.append(" body=" + body.take(160).replace(Regex("\\s+"), " "))
            say(line.toString())
            return ids
        }
        // What the pages say around those two paths (long quoted texts cut, so no titles come along).
        val home = fetch("https://www.sex.com/en/gifs", json = false)?.second.orEmpty()
        fun around(key: String, text: String, n: Int = 3) = Regex(".{0,220}" + Regex.escape(key) + ".{0,260}").findAll(text).take(n).forEach { m ->
            say("sx2 near $key: " + m.value.replace(Regex("https?://[^\"'\\\\ ]+"), "URL").replace(Regex("[A-Za-z0-9+/=_-]{40,}"), "…")) }
        around("/portal/api/gifs", home); around("tags/:tagId", home, 2); around("tagId", home, 4)
        say("sx2 words for saved lists: " + Regex("[A-Za-z]*(?:ollection|laylist|avorite|aller|oard)[A-Za-z]*").findAll(home).map { it.value }.groupingBy { it }.eachCount().entries.sortedByDescending { it.value }.take(40).joinToString { "${it.key}×${it.value}" })

        // 1. The plain listing: which parameters does it want?
        val o = "sexual-orientation=straight"
        var first = emptyList<String>()
        for ((label, query) in listOf("orientation+page+limit" to "$o&page=1&limit=40", "orientation only" to o, "page+limit" to "page=1&limit=40", "order=likeCount" to "order=likeCount&$o&page=1&limit=40",
            "order=createdAt" to "order=createdAt&$o&page=1&limit=40", "order=trending" to "order=trending&$o&page=1&limit=40", "order=newest" to "order=newest&$o&page=1&limit=40",
            "order=viewCount" to "order=viewCount&$o&page=1&limit=40", "sort=latest" to "sort=latest&$o&page=1&limit=40", "page 2" to "$o&page=2&limit=40", "orientation=gay" to "sexual-orientation=gay&page=1&limit=40")) {
            val ids = describe("gifs?$label", "$api/gifs?$query")
            if (ids.isNotEmpty()) { if (first.isEmpty()) first = ids else say("sx2   …shares ${ids.count { it in first }} of ${ids.size} with the first listing, same first 10: ${ids.take(10) == first.take(10)}") }
        }
        // 2. Tags by ID: where do IDs come from? A category page and a picture's page may carry them.
        val cats = fetch("https://www.sex.com/en/categories", json = false)?.second.orEmpty()
        say("sx2 categories page: ${Regex("href=\"/en/categories/([a-z0-9\\-]+)\"").findAll(cats).map { it.groupValues[1] }.distinct().count()} category links; id-like fields: " +
            Regex("\\\\?\"([A-Za-z]*[iI]d)\\\\?\":").findAll(cats).map { it.groupValues[1] }.groupingBy { it }.eachCount().entries.sortedByDescending { it.value }.take(15).joinToString { "${it.key}×${it.value}" })
        around("tagId", cats, 2); around("/portal/api/", cats, 3)
        val slug = Regex("href=\"/en/categories/([a-z0-9\\-]+)\"").find(cats)?.groupValues?.get(1)
        if (slug != null) {
            val page = fetch("https://www.sex.com/en/categories/$slug", json = false)?.second.orEmpty()
            say("sx2 one category page: ${page.length} chars; API paths: " + Regex("/portal/api/[A-Za-z0-9_/\\-:]+").findAll(page).map { it.value }.distinct().take(12).toList() +
                "; id-like fields: " + Regex("\\\\?\"([A-Za-z]*[iI]d)\\\\?\":").findAll(page).map { it.groupValues[1] }.groupingBy { it }.eachCount().entries.sortedByDescending { it.value }.take(12).joinToString { "${it.key}×${it.value}" })
            around("tagId", page, 3)
            val tagIds = Regex("\\\\?\"tagId\\\\?\":\\\\?\"?(\\d+)").findAll(page).map { it.groupValues[1] }.distinct().take(3).toList() +
                Regex("tags/(\\d+)/gifs").findAll(page).map { it.groupValues[1] }.distinct().take(2).toList()
            say("sx2 tag IDs found on that page: ${tagIds.size}")
            tagIds.distinct().take(2).forEach { id -> describe("tags/{id}/gifs", "$api/tags/$id/gifs?$o&page=1&limit=40"); describe("tags/{id}/gifs no params", "$api/tags/$id/gifs") }
        }
        // 3. Small guesses for a tag by its name or a low number
        for (path in listOf("tags/1/gifs?$o&page=1&limit=40", "tags/dance/gifs?$o&page=1&limit=40", "tags/1", "tags?search=da&$o", "gifs/tags?search=da", "categories/gifs?$o", "gifs/1")) describe("guess $path", "$api/$path")
        // 4. A signed-out look at what the site says an account has (no sign-in is made)
        for (path in listOf("user/my-content/gifs", "user/my-content/collections", "user/my-content/playlists", "user/my-content/favorites", "user/my-content/likes", "user/me")) {
            val (code, body) = fetch("$api/$path") ?: continue
            say("sx2 account path $path → $code ${body.take(40).replace(Regex("\\s+"), " ")}")
        }
    }
}
