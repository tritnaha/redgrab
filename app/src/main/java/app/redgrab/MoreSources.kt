package app.redgrab

import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import org.json.JSONArray
import org.json.JSONObject

// Two sites where clips sit one level down: EroMe (a search lists albums, an album holds the
// clips) and 4chan (a board lists threads, a thread holds the files). A page of the Online tab
// is made by opening the next few albums or threads; the ones not opened yet travel in the
// feed's cursor.

/** How many clips a page aims for before it stops opening more albums or threads. */
private const val ENOUGH = 12

// ---- EroMe -----------------------------------------------------------------------------------

/**
 * erome.com has no API; its pages are read as gallery-dl's erome.py reads them. No sign-in.
 *
 *  - /search?q=WORDS&page=N and /USER?t=posts&page=N list albums (links to /a/ID, 36 a page)
 *  - /a/ID is one album: title, uploader, tags, and its videos as `<source src="...mp4">`
 *
 * Only the videos are taken. They are MP4 files on v*.erome.com and want an erome.com Referer.
 * If the site answers with its "Please wait" check page, that is reported and nothing is retried.
 */
class EromeApi(private val http: OkHttpClient, private val pauseMs: Long = 300) {

    fun search(query: String, cursor: String?, page: Int) = page(listOf("search"), mapOf("q" to query.trim()), cursor, page)

    fun user(name: String, cursor: String?, page: Int) = page(listOf(name), mapOf("t" to "posts"), cursor, page)

    private fun html(path: List<String>, params: Map<String, String> = emptyMap()): String {
        val url = ROOT.toHttpUrl().newBuilder().apply { path.forEach { addPathSegment(it) } }
            .apply { params.forEach { (k, v) -> addQueryParameter(k, v) } }.build()
        val body = http.getText(url, mapOf("Accept" to "text/html"))
        if (CHECK in body.take(600)) throw SiteBlockedException("EroMe is showing a bot check. Try again later.")
        return body
    }

    /** The cursor is "<next list page, 0 = none>|<album IDs not opened yet>". */
    private fun page(listPath: List<String>, params: Map<String, String>, cursor: String?, page: Int): OnlinePage {
        var next = cursor?.substringBefore('|')?.toIntOrNull() ?: 1
        val pending = ArrayDeque(cursor?.substringAfter('|', "")?.split(',')?.filter { ALBUM_ID.matches(it) } ?: emptyList())
        val out = LinkedHashMap<String, Clip>()
        var requests = 0
        while (out.size < ENOUGH && requests < 5) {
            if (requests > 0) Thread.sleep(pauseMs)
            requests++
            if (pending.isEmpty()) {
                if (next == 0) break
                val ids = ALBUM_LINK.findAll(html(listPath, params + ("page" to next.toString()))).map { it.groupValues[1] }.distinct().toList()
                pending += ids
                next = if (ids.size < 36) 0 else next + 1
                if (ids.isEmpty()) break
                continue
            }
            val id = pending.removeFirst()
            try { album(id).forEach { out.putIfAbsent(it.id, it) } }
            catch (e: HttpException) { AppLog.w("EroMe album $id skipped (HTTP ${e.code})") } // removed albums answer 404 or 410
        }
        val rest = if (pending.isEmpty() && next == 0) null else "$next|${pending.joinToString(",")}"
        return OnlinePage(out.values.toList(), page, 0, cursor = rest, more = rest != null)
    }

    /** The videos of one album, each with the album's title, uploader and tags. */
    fun album(id: String): List<Clip> {
        val page = html(listOf("a", id))
        val title = unescapeHtml(page.between("property=\"og:title\" content=\"", "\"")).trim()
        val profile = page.substringAfter("<div class=\"user-profile", "")
        val user = runCatching { java.net.URLDecoder.decode(profile.between("href=\"https://www.erome.com/", "\""), "UTF-8") }.getOrDefault("")
        val tags = Regex("\\?q=([^\"]+)\"").findAll(profile.between("<p class=\"mt-10\"", "</p>"))
            .map { runCatching { java.net.URLDecoder.decode(it.groupValues[1], "UTF-8") }.getOrDefault(it.groupValues[1]).trim().trimStart('#') }
            .filter { it.isNotEmpty() && it.length <= 40 }.distinctBy { it.lowercase() }.take(40).toList()
        val groups = page.split("<div class=\"media-group\"").drop(1)
        // Any media address carries the upload time as "?v=<seconds>".
        val created = groups.firstNotNullOfOrNull { g -> g.between("?v=", "\"").toLongOrNull()?.takeIf { it > 0 } } ?: 0L
        return groups.mapNotNull { g ->
            val video = g.between("<source src=\"", "\"").takeIf { it.startsWith("http") } ?: return@mapNotNull null
            // Album IDs differ only by letter case sometimes, so the ID adds a short hash of the file's address.
            val path = video.substringBefore('?')
            val hash = java.security.MessageDigest.getInstance("SHA-1").digest(path.toByteArray()).take(5).joinToString("") { "%02x".format(it) }
            Clip(
                id = "${OnlineSource.EroMe.prefix}${id.lowercase()}-$hash".takeIf { Clip.validId(it) } ?: return@mapNotNull null,
                hd = video, sd = null, thumb = g.between("poster=\"", "\"").takeIf { it.startsWith("http") },
                duration = 0.0, width = 0, height = 0, hasAudio = true, user = user.substringBefore('?').substringBefore('/'),
                created = created, sources = setOf(OnlineSource.EroMe.label), tags = tags, description = title,
            )
        }
    }

    companion object {
        private const val ROOT = "https://www.erome.com"
        private const val CHECK = "<title>Please wait a few moments</title>"
        private val ALBUM_ID = Regex("\\w+")
        private val ALBUM_LINK = Regex("erome\\.com/a/(\\w+)")
        private fun String.between(start: String, end: String): String = substringAfter(start, "").substringBefore(end, "")
    }
}

// ---- 4chan -----------------------------------------------------------------------------------

/**
 * 4chan's public read-only API (a.4cdn.org), as in gallery-dl's 4chan.py. No sign-in.
 *
 *  - /{board}/catalog.json → [{page, threads:[{no, sub, com, images, ...}]}], most recently active first
 *  - /{board}/thread/{no}.json → {posts:[{no, tim, ext, w, h, time, sub, com, filedeleted}]}
 *
 * A post's file is https://i.4cdn.org/{board}/{tim}{ext}, its thumbnail .../{tim}s.jpg. Kept
 * are WebM and MP4 clips and animated GIFs. The API asks for at most one request a second.
 */
class FourChanApi(private val http: OkHttpClient, private val pauseMs: Long = 1100) {

    private fun get(path: String): String = http.getText("https://a.4cdn.org/$path".toHttpUrl(), mapOf("Accept" to "application/json"))

    /** Threads of [BOARD] whose subject or first post has every word of [query] (all threads if blank). The cursor is the thread numbers not opened yet. */
    fun threads(query: String, cursor: String?, page: Int): OnlinePage {
        var requests = 0
        val pending = ArrayDeque(if (cursor != null) cursor.split(',').filter { it.isNotEmpty() && it.all(Char::isDigit) } else {
            requests++
            val words = query.lowercase().split(' ').filter { it.isNotBlank() }
            val pages = JSONArray(get("$BOARD/catalog.json"))
            (0 until pages.length()).flatMap { i ->
                val list = pages.optJSONObject(i)?.optJSONArray("threads") ?: JSONArray()
                (0 until list.length()).mapNotNull { list.optJSONObject(it) }
            }.filter { t ->
                val text = (t.text("sub") + " " + plain(t.text("com"))).lowercase()
                // Without a search, threads with no files besides the first post's are still worth opening.
                words.all { it in text } && (t.optInt("images") > 0 || !t.isNull("tim")) && t.optInt("sticky") == 0
            }.mapNotNull { t -> t.opt("no")?.toString()?.takeIf { it.all(Char::isDigit) } }
        })
        val out = LinkedHashMap<String, Clip>()
        while (out.size < ENOUGH && requests < 4 && pending.isNotEmpty()) {
            if (requests > 0) Thread.sleep(pauseMs)
            requests++
            val no = pending.removeFirst()
            try { thread(no).forEach { out.putIfAbsent(it.id, it) } }
            catch (e: HttpException) { if (e.code != 404) throw e } // the thread fell off the board meanwhile
        }
        val rest = pending.joinToString(",").ifEmpty { null }
        return OnlinePage(out.values.toList(), page, 0, cursor = rest, more = rest != null)
    }

    /** The clips posted in one thread, named after the thread. */
    fun thread(no: String): List<Clip> {
        val posts = JSONObject(get("$BOARD/thread/$no.json")).optJSONArray("posts") ?: return emptyList()
        val first = posts.optJSONObject(0) ?: return emptyList()
        val title = unescapeHtml(first.text("sub").ifBlank { plain(first.text("com")) }).trim().take(80)
        return (0 until posts.length()).mapNotNull { i ->
            val p = posts.optJSONObject(i) ?: return@mapNotNull null
            val tim = p.opt("tim")?.toString()?.takeIf { it.isNotEmpty() && it.all(Char::isDigit) } ?: return@mapNotNull null
            val ext = p.text("ext").lowercase().takeIf { it in KEPT } ?: return@mapNotNull null
            if (p.optInt("filedeleted") == 1) return@mapNotNull null
            Clip(
                id = "${OnlineSource.FourChan.prefix}$BOARD-$tim", hd = "$FILES/$BOARD/$tim$ext", sd = null, thumb = "$FILES/$BOARD/${tim}s.jpg",
                duration = 0.0, width = p.optInt("w"), height = p.optInt("h"), hasAudio = ext != ".gif",
                user = "", created = p.optLong("time"), sources = setOf(OnlineSource.FourChan.label), description = title,
            )
        }
    }

    companion object {
        /** The board for adult GIFs and WebMs. */
        const val BOARD = "gif"
        private const val FILES = "https://i.4cdn.org"
        private val KEPT = setOf(".webm", ".mp4", ".gif")
        /** A post's text without its HTML. */
        private fun plain(html: String) = unescapeHtml(html.replace(Regex("<br\\s*/?>"), " ").replace(Regex("<[^>]+>"), ""))
    }
}
