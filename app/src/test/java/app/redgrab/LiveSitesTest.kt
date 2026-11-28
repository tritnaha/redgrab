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
        if (e.code == 403 || e.code == 429 || e.code == 503) say("$site · $what: BLOCKED (HTTP ${e.code})") else { failures++; say("$site · $what: FAIL (HTTP ${e.code})") }
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
        val popular = page("sex.com", "most liked (no search)") { SexComApi(http).search("", 1) }
        val sx = page("sex.com", "search") { SexComApi(http).search("dance", 1) }
        page("sex.com", "search, page 2") { SexComApi(http).search("dance", 2) }
        media("sex.com", (sx ?: popular)?.clips?.firstOrNull())

        // FikFap
        val tag = page("FikFap", "hashtag") { FikFapApi(http).tagPosts("dance", null, 1) }
        if (tag?.cursor != null) page("FikFap", "hashtag, next page", min = 0) { FikFapApi(http).tagPosts("dance", tag.cursor, 2) }
        tag?.clips?.firstOrNull { it.user.isNotEmpty() }?.let { c -> page("FikFap", "a user") { FikFapApi(http).userPosts(c.user, null, 1) } }
        tag?.clips?.firstOrNull()?.let { c -> check("FikFap", "one post") { val p = FikFapApi(http).post(c.id.removePrefix(OnlineSource.FikFap.prefix)); say("FikFap · one post: ${if (p?.id == c.id) "ok" else "FAIL"}"); if (p?.id != c.id) failures++ } }
        media("FikFap", tag?.clips?.firstOrNull())

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
}
