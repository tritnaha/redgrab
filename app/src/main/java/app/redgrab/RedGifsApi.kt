package app.redgrab

import kotlinx.coroutines.delay
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

class AuthException : IOException("RedGifs rejected the sign-in (expired or signed out)")
class HttpException(val code: Int, msg: String,
                    /** For HTTP 416: the file's real size, from the Content-Range header ("bytes star/total"), or -1. */
                    val rangeTotal: Long = -1) : IOException(msg)

/**
 * RedGifs wants a human check (Cloudflare Turnstile) before continuing. The app shows the
 * check to the user, then posts the answer with [RedGifsApi.submitVerification] using [subject].
 */
class VerificationRequiredException(val subject: String) : IOException("RedGifs asks for a quick human check")

data class RgCollection(val id: String, val name: String, val count: Int = 0, val thumb: String? = null) {
    fun toJson() = JSONObject().put("id", id).put("name", name).put("count", count).put("thumb", thumb)
    companion object {
        fun fromJson(j: JSONObject) = RgCollection(j.getString("id"), j.optString("name"), j.optInt("count"),
            j.optString("thumb").takeIf { it.startsWith("http") })
    }
}

/** A creator profile as returned alongside clips ("users" in feed responses). */
data class Performer(val username: String, val name: String, val verified: Boolean, val followers: Long, val avatar: String?) {
    fun toJson() = JSONObject().put("u", username).put("n", name).put("v", verified).put("f", followers).put("a", avatar)
    companion object {
        fun fromJson(j: JSONObject) = Performer(j.getString("u"), j.optString("n"), j.optBoolean("v"), j.optLong("f"),
            j.optString("a").takeIf { it.startsWith("http") })
    }
}

/** A RedGifs niche (community), e.g. from the list of niches you follow. */
data class NicheInfo(val id: String, val name: String, val thumb: String?, val clips: Long, val subscribers: Long,
                     val tags: List<String> = emptyList(),
                     /** Audiences the niche is for ("straight", "gay", ...), as RedGifs labels it; empty = not labelled. */
                     val preferences: List<String> = emptyList())

/** One page of the niche list. */
data class NichePage(val niches: List<NicheInfo>, val page: Int, val pages: Int, val total: Int)

/** How the niche list can be ordered (the names RedGifs accepts). */
enum class NicheOrder(val api: String, val label: String) {
    Subscribers("subscribers", "Most followed"), Posts("posts", "Most clips"), Name("alphabetical_asc", "A–Z"), Match("best_match", "Best match"),
}

/** A search suggestion: a tag and how many clips carry it. */
data class TagSuggestion(val text: String, val clips: Long)

/**
 * What kinds of content to show. RedGifs labels clips and niches with an audience
 * ("sexuality"); these are its six labels. A clip is hidden when it carries a label that is
 * switched off, or when its tags say as much (not every clip is labelled, and other sites have
 * no labels at all). With all six on nothing is filtered.
 */
object ContentPrefs {
    val ALL = listOf("straight", "lesbian", "gay", "trans", "bisexual", "animated")
    /** What is shown until you choose otherwise: everything except gay and trans. */
    val DEFAULT = setOf("straight", "lesbian", "bisexual", "animated")
    fun label(v: String) = v.replaceFirstChar { it.uppercase() }

    /** Words in tags, niches and titles that put a clip under a label, whatever the site labelled it. */
    private val WORDS = mapOf(
        "gay" to setOf("gay", "gays", "twink", "twinks", "yaoi"),
        "trans" to setOf("trans", "transgender", "transexual", "transsexual", "transgirl", "transwoman", "tranny", "shemale", "shemales",
            "tgirl", "tgirls", "ladyboy", "ladyboys", "femboy", "femboys", "sissy", "sissies", "crossdressing", "crossdresser", "futanari", "futa"),
        "lesbian" to setOf("lesbian", "lesbians", "yuri"),
        "bisexual" to setOf("bisexual"),
        "animated" to setOf("animated", "animation", "hentai", "anime", "cartoon", "sfm", "rule34"),
    )
    private val BY_WORD: Map<String, String> = WORDS.flatMap { (label, words) -> words.map { it to label } }.toMap()
    private val SPLIT = Regex("[^a-z0-9]+")

    /** The labels that [texts] (tags, niche names, a title) point to. */
    fun labelsIn(texts: Iterable<String>): Set<String> {
        val out = HashSet<String>()
        for (t in texts) for (w in t.lowercase().split(SPLIT)) BY_WORD[w]?.let { out += it }
        return out
    }

    /** True when nothing is filtered: every label is on. */
    fun unfiltered(allowed: Set<String>) = allowed.containsAll(ALL)

    /** True when something labelled [labels] and described by [texts] passes with [allowed] switched on. */
    fun allows(allowed: Set<String>, labels: List<String>, texts: Iterable<String> = emptyList()): Boolean {
        if (unfiltered(allowed)) return true
        if (labels.any { l -> l.lowercase().let { it in ALL && it !in allowed } }) return false
        return labelsIn(texts).all { it in allowed }
    }

    /** One line saying what is shown, for Settings. */
    fun summary(allowed: Set<String>) = if (unfiltered(allowed)) "No filter" else ALL.filter { it in allowed }.joinToString(", ") { label(it) }.ifEmpty { "Nothing" }
}

/**
 * One page of an online feed. Feeds without page numbers (Reddit, FikFap) continue from
 * [cursor] and say in [more] whether anything follows.
 */
data class OnlinePage(val clips: List<Clip>, val page: Int, val pages: Int, val cursor: String? = null, val more: Boolean? = null) {
    val hasMore get() = more ?: if (pages > 0) page < pages else clips.isNotEmpty()
}

/** Sort orders RedGifs' own site uses for online feeds. */
enum class OnlineOrder(val api: String, val label: String) {
    Trending("trending", "Trending"), New("new", "Newest"), TopWeek("top7", "Top this week"),
    TopMonth("top28", "Top this month"), TopAll("top", "Top all time"),
}

/** Extra data collected while paging feeds: niche names and creator profiles. */
class FeedExtras {
    val niches = HashMap<String, String>()        // niche id → display name
    val performers = HashMap<String, Performer>() // lower-case username → profile
}

/**
 * The private web API that www.redgifs.com itself uses. RedGifs no longer offers public
 * third-party API access, so endpoints and fields were taken from the site's own JavaScript
 * (2026-11) and may change without notice.
 *
 *  - GET  v2/feeds/for-you?page&count                  → {gifs, page, pages?, …} (user token only)
 *  - GET  v2/feeds/liked?type=g&page&count(10..100)   → {gifs, users, niches?, page, pages, total}
 *  - GET  v2/me/collections?page&count                 → {collections:[{folderId, folderName}], pages}
 *  - GET  v2/me/collections/{id}/gifs?page&count       → {gifs, users, page, pages, total}
 *  - GET  v2/gifs/{id}                                 → {gif, user}
 *  - GET  v2/niches/{id}                               → {niche:{id, name, …}}
 *  - POST v2/auth/rate-limit {subject, captcha}        ← answer to a 429 {error:{subject}} check
 */
class RedGifsApi(private val token: String, private val userAgent: String,
                 /** Only tests pass their own client (one that answers from canned replies). */
                 client: OkHttpClient? = null) {

    companion object {
        /** One shared client: connection reuse for API, thumbnails and downloads. */
        private val base: OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()

        /**
         * Adds the user agent and the Origin/Referer the request's site expects (see
         * [SourceHeaders]) to every request: API calls, downloads, thumbnails and streaming.
         */
        fun clientFor(userAgent: String): OkHttpClient = base.newBuilder()
            .addInterceptor { chain ->
                val req = chain.request()
                val host = req.url.host
                val b = req.newBuilder()
                // Reddit's API gets the app's own name (set on the request); everything else the browser's.
                if (req.header("User-Agent") == null || !SourceHeaders.isReddit(host)) b.header("User-Agent", userAgent)
                val (origin, referer) = SourceHeaders.forHost(host)
                origin?.let { b.header("Origin", it) }
                referer?.let { b.header("Referer", it) }
                chain.proceed(b.build())
            }
            .build()

        /**
         * Opens a media download. With [fromByte] > 0 asks for the rest of a partly saved file
         * (HTTP Range); check [Response.code] == 206 to know whether the server resumed.
         */
        fun download(http: OkHttpClient, url: String, fromByte: Long = 0): Response {
            val req = Request.Builder().url(url).apply { if (fromByte > 0) header("Range", "bytes=$fromByte-") }.build()
            val r = http.newCall(req).execute()
            if (!r.isSuccessful) {
                val code = r.code
                val total = r.header("Content-Range")?.substringAfter('/', "")?.toLongOrNull() ?: -1
                r.close()
                throw HttpException(code, "HTTP $code downloading clip", total)
            }
            return r
        }

        /** The Likes page of the site asks for 30 at a time. */
        private const val LIKES_PER_PAGE = 30
        /** When likes have to be looked up one by one, stop at this many. */
        private const val MAX_LIKES_BY_ID = 600

        /** Cloudflare Turnstile site key used by www.redgifs.com for its human check. */
        const val TURNSTILE_SITE_KEY = "0x4AAAAAAAVj1T69HYyETNAA"
    }

    val http: OkHttpClient = client ?: clientFor(userAgent)

    private suspend fun get(path: String): JSONObject = JSONObject(getText(path))

    /** A request that changes something on your RedGifs account (follow a niche, add to a collection...). Returns the answer's text. */
    private suspend fun send(method: String, path: String, body: JSONObject? = null): String =
        getText(path, method, (body?.toString() ?: "").toRequestBody("application/json".toMediaType()).takeIf { body != null || method != "DELETE" })

    private suspend fun getText(path: String, method: String = "GET", body: okhttp3.RequestBody? = null): String {
        var attempt = 0
        while (true) {
            val req = Request.Builder()
                .url("https://api.redgifs.com/$path")
                .header("Authorization", "Bearer $token")
                .header("Accept", "application/json")
                .method(method, if (method == "GET") null else body)
                .build()
            http.newCall(req).execute().use { r ->
                val body = r.body?.string().orEmpty()
                when {
                    r.code == 401 || r.code == 403 -> throw AuthException()
                    r.code == 429 -> {
                        // With a "subject", RedGifs wants a human check; without, it's plain rate limiting.
                        val subject = runCatching { JSONObject(body).optJSONObject("error")?.optString("subject") }.getOrNull()
                        if (!subject.isNullOrEmpty()) throw VerificationRequiredException(subject)
                        if (attempt >= 3) throw HttpException(429, "Rate limited by RedGifs; try again in a few minutes")
                        AppLog.w("Rate limited on ${path.substringBefore('?')}, waiting before retry")
                    }
                    !r.isSuccessful -> throw HttpException(r.code, "HTTP ${r.code} for ${path.substringBefore('?')}: ${body.take(200)}")
                    else -> return body
                }
            }
            attempt++
            delay(2000L * attempt)
        }
    }

    /**
     * Passes only for a logged-in user's token. RedGifs also hands a token to visitors who
     * aren't logged in, and most endpoints (the liked feed included, which just comes back empty)
     * accept it; the collections list answers 401 "UserTokenRequired" to those.
     */
    suspend fun checkSignedIn() {
        get("v2/me/collections?count=10&page=1")
    }

    /** Sends the user's Turnstile answer so RedGifs lets requests through again. */
    fun submitVerification(subject: String, captcha: String) {
        val body = JSONObject().put("subject", subject).put("captcha", captcha).toString()
            .toRequestBody("application/json".toMediaType())
        val req = Request.Builder().url("https://api.redgifs.com/v2/auth/rate-limit")
            .header("Authorization", "Bearer $token").post(body).build()
        http.newCall(req).execute().use { r ->
            if (!r.isSuccessful) throw HttpException(r.code, "Verification was not accepted (HTTP ${r.code})")
        }
    }

    /**
     * Everything you've liked. Asked for exactly as the site's own Likes page asks (30 at a
     * time). If that feed comes back empty, the list of liked IDs is used instead and each clip
     * is looked up on its own.
     */
    /**
     * Your likes, newest first. With [known] (the IDs already on the phone) reading stops at the
     * first page that holds nothing new: a quick sync. Without it, all pages are read.
     */
    suspend fun likedClips(extras: FeedExtras, known: Set<String>? = null, onPage: suspend (page: Int, pages: Int, total: Int) -> Unit): List<Clip> {
        val fromFeed = paged("v2/feeds/liked?count=$LIKES_PER_PAGE&type=g", "Liked", extras, LIKES_PER_PAGE, onPage) { page ->
            known != null && page.isNotEmpty() && page.all { it.id in known }
        }
        if (fromFeed.isNotEmpty() || known != null) return fromFeed
        val ids = runCatching {
            val t = getText("v2/likes").trim()
            val arr = if (t.startsWith("[")) JSONArray(t)
            else JSONObject(t).let { it.optJSONArray("likes") ?: it.optJSONArray("gifs") ?: it.optJSONArray("items") ?: JSONArray() }
            (0 until arr.length()).mapNotNull { i ->
                when (val v = arr.opt(i)) { is String -> v; is JSONObject -> v.optString("id").ifEmpty { v.optString("gifId") }; else -> null }
            }.map { it.lowercase() }.filter { Clip.validId(it) }.distinct()
        }.onFailure { if (it is AuthException) throw it; AppLog.w("Could not read the list of liked IDs: ${it.message}") }.getOrDefault(emptyList())
        AppLog.i("The liked feed came back empty; RedGifs' list of liked IDs has ${ids.size}")
        val out = ArrayList<Clip>()
        for ((i, id) in ids.take(MAX_LIKES_BY_ID).withIndex()) {
            onPage(i + 1, ids.size.coerceAtMost(MAX_LIKES_BY_ID), ids.size)
            try { clip(id)?.let { out += it.copy(sources = setOf("Liked")) } }
            catch (e: HttpException) { if (e.code == 429) throw e } // a removed clip: leave it out
            delay(120)
        }
        return out
    }

    suspend fun myCollections(): List<RgCollection> {
        val out = mutableListOf<RgCollection>()
        var page = 1
        while (true) {
            val j = get("v2/me/collections?count=50&page=$page")
            val arr = j.optJSONArray("collections") ?: j.optJSONArray("items") ?: JSONArray()
            for (i in 0 until arr.length()) {
                val c = arr.getJSONObject(i)
                val id = c.optString("folderId").ifEmpty { c.optString("id") }
                if (id.isNotEmpty()) out += RgCollection(
                    id, c.optString("folderName").ifEmpty { "Collection" }, c.optInt("contentCount"),
                    (c.optString("thumbnail").ifEmpty { c.optString("thumb") }).takeIf { it.startsWith("http") },
                )
            }
            val pages = j.optInt("pages", 0)
            if (arr.length() == 0 || pages in 1..page || (pages == 0 && arr.length() < 50)) break
            page++
        }
        return out
    }

    suspend fun collectionClips(c: RgCollection, extras: FeedExtras): List<Clip> =
        paged("v2/me/collections/${c.id}/gifs?count=100", c.name, extras, 100, onPage = { _, _, _ -> }).map { it.copy(colIds = setOf(c.id)) }

    /** Fresh copy of one clip (new media URLs if the old ones stopped working). */
    suspend fun clip(id: String): Clip? =
        get("v2/gifs/$id").optJSONObject("gif")?.let { parseClip(it, "") }

    // ---- online browsing ---------------------------------------------------------------

    private fun niches(j: JSONObject): List<NicheInfo> {
        val arr = j.optJSONArray("niches") ?: j.optJSONArray("items") ?: JSONArray()
        return (0 until arr.length()).mapNotNull { i ->
            val n = arr.optJSONObject(i) ?: return@mapNotNull null
            val id = n.optString("id").ifEmpty { return@mapNotNull null }
            NicheInfo(id, n.optString("name").ifEmpty { id }, n.optString("thumbnail").takeIf { it.startsWith("http") },
                n.optLong("gifs"), n.optLong("subscribers"),
                n.optJSONArray("tags")?.strings() ?: emptyList(), n.optJSONArray("preferences")?.strings() ?: emptyList())
        }
    }

    /** Niches you follow on RedGifs. */
    suspend fun followedNiches(): List<NicheInfo> = niches(get("v2/niches/following"))

    /** The groups RedGifs sorts its niches into ("Amateur", "Fetish", ...). */
    suspend fun nicheCategories(): List<String> = get("v2/niches/categories").optJSONArray("categories")?.strings() ?: emptyList()

    /**
     * The niche list: all of them, or those matching [query] and/or in [category]. Checked against
     * the site (2026-11): it accepts the orders in [NicheOrder] and answers "bad sorting order" to others.
     */
    suspend fun searchNiches(query: String, category: String?, order: NicheOrder, page: Int, count: Int = 30): NichePage {
        val q = (if (query.isBlank()) "" else "&query=${enc(query.trim())}") + (if (category.isNullOrBlank()) "" else "&category=${enc(category)}")
        // "Best match" only means something with words to match.
        val o = if (order == NicheOrder.Match && query.isBlank()) NicheOrder.Subscribers else order
        val j = get("v2/niches/search?order=${o.api}&page=$page&count=$count$q")
        return NichePage(niches(j), j.optInt("page", page), j.optInt("pages", 0), j.optInt("total", 0))
    }

    /** Follow or stop following a niche on your RedGifs account. */
    suspend fun followNiche(id: String, follow: Boolean) { send(if (follow) "POST" else "DELETE", "v2/niches/${enc(id)}/subscription") }

    /** Every tag the site has, with how many clips carry it (one request, about 7,500 tags). */
    suspend fun allTags(): List<Known> {
        val arr = get("v1/tags").optJSONArray("tags") ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            o.optString("name").trim().takeIf { it.isNotEmpty() }?.let { Known(it, o.optLong("count")) }
        }
    }

    /** Every niche the site has (about 1,900, a hundred per request), most followed first. */
    suspend fun allNiches(): List<Known> {
        val out = ArrayList<Known>()
        var page = 1
        while (page <= 40) {
            val pg = searchNiches("", null, NicheOrder.Subscribers, page, count = 100)
            out += pg.niches.map { Known(it.name, it.subscribers, it.id, it.preferences) }
            if (pg.niches.isEmpty() || page >= pg.pages) break
            page++
            kotlinx.coroutines.delay(150)
        }
        return out.distinctBy { it.id }
    }

    /** Creators whose name matches what has been typed. */
    suspend fun suggestCreators(query: String): List<String> {
        if (query.isBlank()) return emptyList()
        val arr = JSONArray(getText("v1/tags/suggest?query=${enc(query.trim())}"))
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            if (o.optString("type") != "creator") return@mapNotNull null
            o.optString("username").ifEmpty { o.optString("text").removePrefix("@") }.takeIf { it.isNotBlank() }
        }
    }

    /** Tags matching what has been typed so far, most used first. */
    suspend fun suggestTags(query: String): List<TagSuggestion> {
        if (query.isBlank()) return emptyList()
        val arr = JSONArray(getText("v2/search/suggest?query=${enc(query.trim())}"))
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            if (o.optString("type", "tag") != "tag") return@mapNotNull null
            o.optString("text").takeIf { it.isNotBlank() }?.let { TagSuggestion(it, o.optLong("gifs")) }
        }.sortedByDescending { it.clips }
    }

    // ---- changes to your RedGifs account, as the site's own pages make them ---------------

    /** Adds a clip to one of your collections. */
    suspend fun addToCollection(collectionId: String, clipId: String) {
        send("POST", "v2/me/collections/${enc(collectionId)}/gifs", JSONObject().put("gifId", clipId))
    }

    /** Creates a private collection and returns it (its ID is read from the answer, else looked up by name). */
    suspend fun createCollection(name: String): RgCollection {
        val text = send("POST", "v2/me/collections", JSONObject().put("folderName", name.trim()).put("description", JSONObject.NULL).put("published", false))
        val j = runCatching { JSONObject(text) }.getOrNull()
        val c = j?.optJSONObject("collection") ?: j
        val id = c?.optString("folderId").orEmpty().ifEmpty { c?.optString("id").orEmpty() }
        if (id.isNotEmpty()) return RgCollection(id, c?.optString("folderName").orEmpty().ifEmpty { name.trim() })
        return myCollections().firstOrNull { it.name.equals(name.trim(), ignoreCase = true) } ?: throw IOException("RedGifs didn't return the new collection")
    }

    /** The content preferences stored on your RedGifs account, or null when it has none. */
    suspend fun accountPrefs(): List<String>? = get("v1/me").optJSONArray("preferences")?.strings()

    /** Stores the content preferences on your RedGifs account, which its own feeds follow. */
    suspend fun setAccountPrefs(allowed: List<String>) {
        send("PATCH", "v1/me", JSONObject().put("operations", JSONArray().put(JSONObject().put("path", "/preferences").put("value", JSONArray(allowed)))))
    }

    private suspend fun onlinePage(path: String, page: Int, extras: FeedExtras): OnlinePage {
        val j = get(path)
        val arr = j.optJSONArray("gifs") ?: j.optJSONArray("items") ?: JSONArray()
        val clips = (0 until arr.length()).mapNotNull { parseClip(arr.getJSONObject(it), "Discover") }
        collectExtras(j, extras)
        return OnlinePage(clips, page, j.optInt("pages", 0))
    }

    /**
     * Clips in a niche. Niche feeds have their own sort names: checked against the site
     * (2026-11), it accepts hot, latest, best and oldest, and answers HTTP 400 "BadOrder" to
     * the trending / top7 / top28 that search uses. So: Trending → hot, Newest → latest, any Top → best.
     */
    suspend fun nicheClips(nicheId: String, order: OnlineOrder, page: Int, extras: FeedExtras, count: Int = 40): OnlinePage {
        val o = when (order) { OnlineOrder.Trending -> "hot"; OnlineOrder.New -> "latest"; else -> "best" }
        return onlinePage("v2/niches/${enc(nicheId)}/gifs?count=${count.coerceIn(10, 100)}&page=$page&order=$o&type=g", page, extras)
    }

    /**
     * Your "For You" feed: what the site's home page shows a signed-in user. Needs a user's token
     * (a visitor gets 401 "UserTokenRequired"). The site also sends an analytics session ID with
     * this request; it is left out here.
     */
    suspend fun forYou(page: Int, extras: FeedExtras): OnlinePage = onlinePage("v2/feeds/for-you?page=$page&count=40", page, extras)

    /** Site-wide search by free text; an empty query gives the general feed in that order. */
    suspend fun searchClips(query: String, order: OnlineOrder, page: Int, extras: FeedExtras, count: Int = 40): OnlinePage {
        val q = if (query.isBlank()) "" else "&query=${enc(query.trim())}"
        // The search endpoint calls "newest" `latest`.
        val o = if (order == OnlineOrder.New) "latest" else order.api
        return onlinePage("v2/gifs/search?type=g&count=${count.coerceIn(10, 100)}&page=$page&order=$o$q", page, extras)
    }

    /**
     * Clips that carry every one of [tags]. Checked against the site (2026-11): "tags=a,b" returns
     * only clips with both, where the free-text "query=a b" also returns clips with just one.
     */
    suspend fun tagClips(tags: List<String>, order: OnlineOrder, page: Int, extras: FeedExtras, count: Int = 40): OnlinePage {
        val o = if (order == OnlineOrder.New) "latest" else order.api
        return onlinePage("v2/gifs/search?type=g&count=${count.coerceIn(10, 100)}&page=$page&order=$o&tags=${tags.joinToString(",") { enc(it.trim()) }}", page, extras)
    }

    /** A creator's clips. */
    suspend fun performerClips(username: String, order: OnlineOrder, page: Int, extras: FeedExtras): OnlinePage {
        val o = if (order == OnlineOrder.New || order == OnlineOrder.Trending) "new" else "top"
        return onlinePage("v2/users/${enc(username)}/search?type=g&count=40&page=$page&order=$o", page, extras)
    }

    /** RedGifs' own "related" list for a clip (based on its tags). */
    suspend fun relatedClips(clipId: String, page: Int, extras: FeedExtras) =
        onlinePage("v2/recommend/tags/${enc(clipId)}?count=40&page=$page", page, extras)

    private fun enc(s: String) = java.net.URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    /** Name of a niche (RedGifs community) by ID, or null if unknown. */
    suspend fun nicheName(id: String): String? = runCatching {
        get("v2/niches/$id").let { j -> (j.optJSONObject("niche") ?: j).optString("name").takeIf { it.isNotBlank() } }
    }.getOrNull()

    private suspend fun paged(base: String, source: String, extras: FeedExtras, perPage: Int, onPage: suspend (Int, Int, Int) -> Unit,
                              /** Given the clips of the page just read: true = nothing further is needed. */
                              stop: (List<Clip>) -> Boolean = { false }): List<Clip> {
        val out = mutableListOf<Clip>()
        var page = 1
        while (true) {
            val j = get("$base&page=$page")
            val arr = j.optJSONArray("gifs") ?: j.optJSONArray("items") ?: JSONArray()
            val before = out.size
            for (i in 0 until arr.length()) parseClip(arr.getJSONObject(i), source)?.let(out::add)
            collectExtras(j, extras)
            val pages = j.optInt("pages", 0)
            if (page == 1) {
                // What RedGifs answered, so an empty list can be told from a list the app couldn't read.
                AppLog.i("$source: first page has ${arr.length()} item(s), ${out.size - before} usable; RedGifs reports ${j.optInt("total", 0)} in $pages page(s)")
                if (arr.length() == 0) AppLog.i("$source: the empty answer holds " + j.keys().asSequence().sorted().joinToString(", ") { k ->
                    val v: Any? = j.opt(k)
                    "$k=" + when (v) { is JSONArray -> "[${v.length()}]"; is JSONObject -> "{${v.length()}}"; null -> "null"; else -> v.toString().take(20) }
                })
            }
            onPage(page, pages, j.optInt("total", 0))
            if (arr.length() == 0 || pages in 1..page || (pages == 0 && arr.length() < perPage)) break
            if (stop(out.subList(before, out.size))) break
            page++
            delay(250)
        }
        return out
    }

    /** Responses carry "users" (array or map) and sometimes "niches"; keep what's useful. */
    private fun collectExtras(j: JSONObject, extras: FeedExtras) {
        fun user(u: JSONObject) {
            val name = u.optString("username").ifEmpty { u.optString("userName") }.ifEmpty { return }
            extras.performers[name.lowercase()] = Performer(
                username = name, name = u.optString("name"), verified = u.optBoolean("verified"),
                followers = u.optLong("followers"), avatar = u.optString("profileImageUrl").takeIf { it.startsWith("http") },
            )
        }
        when (val users = j.opt("users")) {
            is JSONArray -> for (i in 0 until users.length()) users.optJSONObject(i)?.let(::user)
            is JSONObject -> users.keys().forEach { k -> users.optJSONObject(k)?.let(::user) }
            null -> {}
            else -> {}
        }
        j.optJSONArray("niches")?.let { a ->
            for (i in 0 until a.length()) a.optJSONObject(i)?.let { n ->
                val id = n.optString("id"); val name = n.optString("name")
                if (id.isNotEmpty() && name.isNotEmpty()) extras.niches[id] = name
            }
        }
    }

    private fun parseClip(g: JSONObject, source: String): Clip? {
        // The ID becomes part of file names, so only plain IDs are accepted.
        val id = g.optString("id").lowercase().takeIf { Clip.validId(it) } ?: return null
        val urls = g.optJSONObject("urls") ?: return null
        fun url(k: String) = urls.optString(k).takeIf { it.startsWith("http") }
        val hd = url("hd")
        val sd = url("sd")
        if (hd == null && sd == null) return null // images or removed clips
        val w = g.optInt("width"); val h = g.optInt("height")
        val sexuality = when (val sx = g.opt("sexuality")) {
            is JSONArray -> sx.strings()
            is String -> listOf(sx).filter { it.isNotBlank() }
            null -> emptyList()
            else -> emptyList()
        }
        return Clip(
            id = id,
            hd = hd,
            sd = sd,
            thumb = url("thumbnail") ?: url("poster"),
            duration = g.optDouble("duration", 0.0).takeIf { !it.isNaN() } ?: 0.0,
            width = w,
            height = h,
            hasAudio = g.optBoolean("hasAudio"),
            user = g.optString("userName").takeIf { it != "null" } ?: "",
            created = g.optLong("createDate"),
            sources = if (source.isEmpty()) emptySet() else setOf(source),
            tags = g.optJSONArray("tags")?.strings() ?: emptyList(),
            views = g.optLong("views"),
            likes = g.optLong("likes"),
            nicheIds = g.optJSONArray("niches")?.strings() ?: emptyList(),
            sexuality = sexuality,
            verified = g.optBoolean("verified"),
            description = g.optString("description").takeIf { it != "null" } ?: "",
        )
    }

}
