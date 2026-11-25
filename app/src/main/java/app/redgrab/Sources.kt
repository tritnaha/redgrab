package app.redgrab

import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Where an online clip comes from. [prefix] starts the clip's ID (and so its file name);
 * RedGifs IDs have none. [label] is what's kept in [Clip.sources] for clips saved from that site.
 */
enum class OnlineSource(val label: String, val prefix: String) {
    RedGifs("RedGifs", ""), Reddit("Reddit", "rd-"), FikFap("FikFap", "ff-"), SexCom("sex.com", "sx-");

    companion object {
        fun of(id: String): OnlineSource = entries.firstOrNull { it.prefix.isNotEmpty() && id.startsWith(it.prefix) } ?: RedGifs

        /** [Clip.sources] values that mean "saved from the Online tab" ("Discover" = RedGifs). */
        val SAVED_LABELS: Set<String> = setOf("Discover", Reddit.label, FikFap.label, SexCom.label)
    }
}

/** Reddit needs a client ID, or the saved sign-in no longer works. The message is shown as is. */
class RedditAuthException(msg: String) : IOException(msg)

/** Where the Reddit client ID and tokens are kept ([Prefs] in the app). */
interface RedditStore {
    var redditClientId: String?
    var redditAccess: String?
    var redditAccessExp: Long
    var redditRefresh: String?
    var redditUser: String?
}

/** The Origin and Referer each site expects on its API, pictures and video files. */
object SourceHeaders {
    private const val FIKFAP = "https://fikfap.com"
    /** Hosts seen in FikFap responses (its video CDN), so files there get FikFap's headers too. */
    private val fikfapHosts = ConcurrentHashMap.newKeySet<String>()

    fun learnFikFap(url: String?) { url?.toHttpUrlOrNull()?.host?.let(fikfapHosts::add) }

    private fun String.on(domain: String) = this == domain || endsWith(".$domain")

    fun isReddit(host: String) = host.on("reddit.com") || host.on("redd.it") || host.on("redditmedia.com") || host.on("redditstatic.com")

    /** (Origin, Referer) for a request to [host]; null = leave that header out. */
    fun forHost(host: String): Pair<String?, String?> = when {
        // b-cdn.net is the video CDN FikFap streams from.
        host.on("fikfap.com") || host.on("b-cdn.net") || host in fikfapHosts -> FIKFAP to "$FIKFAP/"
        host.on("sex.com") || host.on("sx.cdn.live") -> null to "https://www.sex.com/"
        isReddit(host) -> null to null
        else -> "https://www.redgifs.com" to "https://www.redgifs.com/"
    }
}

private fun JSONObject.http(key: String): String? = optString(key).takeIf { it.startsWith("http") }
private fun JSONObject.text(key: String): String = if (isNull(key)) "" else optString(key)
private fun JSONObject.num(key: String): Double = optDouble(key, 0.0).takeIf { !it.isNaN() } ?: 0.0

/** GET that returns the body, or throws [HttpException] with the status code. */
private fun OkHttpClient.getText(url: HttpUrl, headers: Map<String, String> = emptyMap()): String {
    val req = Request.Builder().url(url).apply { headers.forEach { (k, v) -> header(k, v) } }.build()
    newCall(req).execute().use { r ->
        val body = r.body?.string().orEmpty()
        if (!r.isSuccessful) throw HttpException(r.code, "HTTP ${r.code} for ${url.host}${url.encodedPath}: ${body.take(200)}")
        return body
    }
}

// ---- sex.com ---------------------------------------------------------------------------------

/**
 * sex.com GIF search, as its own site calls it (taken from gallery-dl's sexcom.py):
 * GET /portal/api/gifs/search?search&order&sexual-orientation&page&limit → {data:[{id, uri, title, width, height}], paging:{numberOfPages}}.
 * The files are pictures (GIF/WebP) on imagex1.sx.cdn.live. No sign-in.
 */
class SexComApi(private val http: OkHttpClient) {

    fun search(query: String, page: Int): OnlinePage {
        val url = "https://www.sex.com/portal/api/gifs/search".toHttpUrl().newBuilder()
            .addQueryParameter("search", query.trim())
            .addQueryParameter("order", "likeCount")
            .addQueryParameter("sexual-orientation", "straight")
            .addQueryParameter("page", page.toString())
            .addQueryParameter("limit", "40")
            .build()
        val j = JSONObject(http.getText(url, mapOf("Accept" to "application/json")))
        val arr = j.optJSONArray("data") ?: JSONArray()
        if (arr.length() > 0 && logged.compareAndSet(false, true)) {
            AppLog.i("sex.com item fields: " + arr.optJSONObject(0)?.keys()?.asSequence()?.sorted()?.joinToString(", "))
        }
        val clips = (0 until arr.length()).mapNotNull { arr.optJSONObject(it)?.let(::parse) }.distinctBy { it.id }
        return OnlinePage(clips, page, j.optJSONObject("paging")?.optInt("numberOfPages", 0) ?: 0)
    }

    private fun parse(p: JSONObject): Clip? {
        val id = p.opt("id")?.toString()?.takeIf { it.isNotEmpty() && it.all(Char::isLetterOrDigit) }?.lowercase() ?: return null
        val uri = p.text("uri").takeIf { it.startsWith("/") } ?: return null
        // The path holds the upload date: /images/pinporn/2024/01/31/12345.webp
        val created = runCatching {
            val d = uri.split('/').dropLast(1).takeLast(3).map { it.toInt() }
            java.time.LocalDate.of(d[0], d[1], d[2]).atStartOfDay(java.time.ZoneOffset.UTC).toEpochSecond()
        }.getOrDefault(0L)
        // The search answer isn't known to carry tags; they're used if it ever does.
        val tags = p.optJSONArray("tags")?.let { t -> (0 until t.length()).mapNotNull { i -> (t.opt(i) as? String) ?: t.optJSONObject(i)?.text("name") } }
            ?.filter { it.isNotBlank() } ?: emptyList()
        return Clip(
            id = "${OnlineSource.SexCom.prefix}$id".takeIf { Clip.validId(it) } ?: return null,
            hd = CDN + uri, sd = null, thumb = "$CDN$uri?width=300",
            duration = 0.0, width = p.optInt("width"), height = p.optInt("height"), hasAudio = false,
            user = "", created = created, sources = setOf(OnlineSource.SexCom.label),
            tags = tags, description = p.text("title"),
        )
    }

    /** Tags are only on the picture's own page, between the heading and the end of its section. Best effort. */
    fun tags(id: String): List<String> {
        val html = http.getText("https://www.sex.com/en/gifs/$id".toHttpUrl(), mapOf("Accept" to "text/html"))
        val part = html.substringAfter("</h1>", "").substringBefore("</section>", "")
        return TAG_RE.findAll(part).map { m -> unescape(m.groupValues[1]).trim().trimStart('@', '#') }
            .filter { it.isNotEmpty() && it.length <= 40 }.distinctBy { it.lowercase() }.take(40).toList()
    }

    companion object {
        const val CDN = "https://imagex1.sx.cdn.live"
        private val logged = AtomicBoolean(false)
        /** Text between two HTML tags. */
        private val TAG_RE = Regex(">([^<>]+)<")

        private fun unescape(s: String) = s.replace("&amp;", "&").replace("&#39;", "'").replace("&#x27;", "'")
            .replace("&quot;", "\"").replace("&lt;", "<").replace("&gt;", ">").replace("&nbsp;", " ")

        /** Addresses to try when saving, best first: the real GIF behind a WebP, then the file as listed. */
        fun files(c: Clip): List<String> {
            val url = c.hd ?: return emptyList()
            return if (url.endsWith(".webp", ignoreCase = true)) listOf(url.dropLast(4) + "gif", url) else listOf(url)
        }
    }
}

// ---- FikFap ----------------------------------------------------------------------------------

/**
 * FikFap's own web API, used without an account (taken from gallery-dl's fikfap.py; unofficial,
 * may change). Posts come 21 at a time, continued with afterId = the last post's ID.
 *
 *  - GET /hashtags/label/{tag}/posts?amount=21&afterId=
 *  - GET /profile/username/{user}/posts?amount=21&afterId=
 *  - GET /posts/{id}
 *
 * A post: postId, label, hashtags[].label, author.username, createdAt, duration, likesCount,
 * viewsCount, thumbnailStreamUrl, and the video as videoFileOriginalUrl (MP4, not always
 * there) or videoStreamUrl (HLS playlist on b-cdn.net, with a token that expires).
 */
class FikFapApi(private val http: OkHttpClient) {

    private fun get(vararg path: String, params: Map<String, String?> = emptyMap()): String {
        val url = "https://api.fikfap.com".toHttpUrl().newBuilder()
            .apply { path.forEach { addPathSegment(it) } }
            .apply { params.forEach { (k, v) -> if (v != null) addQueryParameter(k, v) } }
            .build()
        return http.getText(url, mapOf(
            "Authorization-Anonymous" to ANONYMOUS, "IsLoggedIn" to "false", "IsPWA" to "false",
            "Accept" to "application/json", "Sec-Fetch-Dest" to "empty", "Sec-Fetch-Mode" to "cors", "Sec-Fetch-Site" to "same-site",
        ))
    }

    fun tagPosts(tag: String, afterId: String?, page: Int) = page(get("hashtags", "label", tag, "posts", params = mapOf("amount" to "$AMOUNT", "afterId" to afterId)), page)

    fun userPosts(user: String, afterId: String?, page: Int) = page(get("profile", "username", user, "posts", params = mapOf("amount" to "$AMOUNT", "afterId" to afterId)), page)

    /** Fresh copy of one post (its stream address expires). */
    fun post(id: String): Clip? = parse(JSONObject(get("posts", id)))

    private fun page(body: String, page: Int): OnlinePage {
        val t = body.trimStart()
        val arr = if (t.startsWith("[")) JSONArray(t)
        else JSONObject(t).let { j -> j.optJSONArray("posts") ?: j.optJSONArray("data") ?: j.optJSONArray("items") ?: JSONArray() }
        val posts = (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }
        if (posts.isNotEmpty() && logged.compareAndSet(false, true)) {
            val p = posts[0]
            AppLog.i("FikFap post fields: " + p.keys().asSequence().sorted().joinToString(", "))
            p.optJSONObject("author")?.let { a -> AppLog.i("FikFap author fields: " + a.keys().asSequence().sorted().joinToString(", ")) }
        }
        val clips = posts.mapNotNull(::parse).distinctBy { it.id }
        if (clips.size < posts.size) AppLog.w("FikFap: ${posts.size - clips.size} of ${posts.size} post(s) had no video address")
        val last = posts.lastOrNull()?.opt("postId")?.toString()
        return OnlinePage(clips, page, 0, cursor = last, more = posts.size >= AMOUNT && last != null)
    }

    private fun parse(p: JSONObject): Clip? {
        val id = p.opt("postId")?.toString()?.takeIf { it.isNotEmpty() && it.all(Char::isDigit) } ?: return null
        val video = p.http("videoFileOriginalUrl") ?: p.http("videoStreamUrl") ?: return null
        // Known name first; otherwise any address whose field name says thumbnail or poster.
        val thumb = p.http("thumbnailStreamUrl") ?: p.keys().asSequence()
            .filter { k -> THUMB_WORDS.any { k.contains(it, ignoreCase = true) } }.firstNotNullOfOrNull { p.http(it) }
        SourceHeaders.learnFikFap(video); SourceHeaders.learnFikFap(thumb)
        val author = p.optJSONObject("author")
        val tags = p.optJSONArray("hashtags")?.let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it)?.text("label") } }
            ?.filter { it.isNotBlank() } ?: emptyList()
        val created = runCatching { java.time.Instant.parse(p.text("publishedAt").ifEmpty { p.text("createdAt") }).epochSecond }.getOrDefault(0L)
        return Clip(
            id = "${OnlineSource.FikFap.prefix}$id", hd = video, sd = null, thumb = thumb,
            duration = p.num("duration"),
            width = p.optInt("width").takeIf { it > 0 } ?: p.optInt("videoWidth"),
            height = p.optInt("height").takeIf { it > 0 } ?: p.optInt("videoHeight"),
            hasAudio = true, user = author?.text("username") ?: "", created = created,
            sources = setOf(OnlineSource.FikFap.label), tags = tags,
            views = p.optLong("viewsCount"), likes = p.optLong("likesCount"),
            sexuality = listOf(p.text("sexualOrientation").lowercase()).filter { it.isNotBlank() },
            verified = author?.optBoolean("isVerified") == true, description = p.text("label"),
        )
    }

    companion object {
        /** The fixed key fikfap.com sends for visitors who aren't logged in. */
        private const val ANONYMOUS = "2527cc30-c3c5-41be-b8bb-104b6ea7a206"
        private const val AMOUNT = 21
        private val THUMB_WORDS = listOf("thumb", "poster", "preview")
        private val logged = AtomicBoolean(false)
    }
}

// ---- Reddit ----------------------------------------------------------------------------------

/**
 * Reddit's official API with the user's own "installed app" client ID (reddit.com/prefs/apps,
 * redirect URI redgrab://reddit). Signed in: the user's token (scopes identity, read, history,
 * mysubreddits), renewed with the refresh token. Not signed in: an app-only token.
 *
 * Listings: /{sort}, /r/{sub}/{sort}, /user/{name}/saved|upvoted|submitted → {data:{children:[{kind:"t3", data}], after}}.
 * Only posts with something to play are kept: Reddit-hosted video (its video-only MP4, so no
 * sound), GIFs (Reddit's MP4 copy), and links to RedGifs (looked up on RedGifs when opened).
 */
class RedditApi(private val prefs: RedditStore, private val http: OkHttpClient) {

    companion object {
        const val REDIRECT = "redgrab://reddit"
        private const val SCOPE = "identity read history mysubreddits"
        private const val TOKEN_URL = "https://www.reddit.com/api/v1/access_token"
        private const val API = "https://oauth.reddit.com"
        /** Reddit asks API clients to name themselves like this rather than pose as a browser. */
        val USER_AGENT = "android:app.redgrab:v${BuildConfig.VERSION_NAME}"
        private val NAME_RE = Regex("[A-Za-z0-9_+-]{1,100}")
        private val tokenLock = Any()

        /** The page where the user allows the app; Reddit then redirects to [REDIRECT] with a code. */
        fun authorizeUrl(clientId: String, state: String): String = "https://www.reddit.com/api/v1/authorize.compact".toHttpUrl().newBuilder()
            .addQueryParameter("client_id", clientId).addQueryParameter("response_type", "code")
            .addQueryParameter("state", state).addQueryParameter("redirect_uri", REDIRECT)
            .addQueryParameter("duration", "permanent").addQueryParameter("scope", SCOPE)
            .build().toString()

        /** RedGifs clip ID from a link to a RedGifs video page, or null for anything else. */
        fun redgifsId(url: String): String? {
            val u = url.toHttpUrlOrNull() ?: return null
            if (!(u.host == "redgifs.com" || u.host.endsWith(".redgifs.com"))) return null
            val seg = u.pathSegments
            val at = seg.indexOfFirst { it == "watch" || it == "ifr" }
            if (at < 0) return null
            return seg.getOrNull(at + 1)?.substringBefore('.')?.substringBefore('-')?.lowercase()
                ?.takeIf { it.isNotEmpty() && it.all(Char::isLetterOrDigit) && Clip.validId(it) }
        }
    }

    private fun clientId(): String = prefs.redditClientId?.takeIf { it.isNotBlank() }
        ?: throw RedditAuthException("Add a Reddit client ID in Settings.")

    private fun tokenRequest(form: FormBody): JSONObject {
        val req = Request.Builder().url(TOKEN_URL)
            .header("Authorization", okhttp3.Credentials.basic(clientId(), ""))
            .header("User-Agent", USER_AGENT).post(form).build()
        http.newCall(req).execute().use { r ->
            val body = r.body?.string().orEmpty()
            val j = runCatching { JSONObject(body) }.getOrNull()
            if (!r.isSuccessful || j == null || j.text("access_token").isEmpty()) {
                val why = j?.text("error")?.ifEmpty { null } ?: "HTTP ${r.code}"
                AppLog.w("Reddit token request failed: $why")
                throw RedditAuthException(if (r.code == 401) "Reddit didn't accept the client ID." else "Reddit sign-in failed ($why).")
            }
            return j
        }
    }

    private fun store(j: JSONObject) {
        prefs.redditAccess = j.text("access_token")
        prefs.redditAccessExp = System.currentTimeMillis() / 1000 + j.optLong("expires_in", 3600)
        j.text("refresh_token").takeIf { it.isNotEmpty() }?.let { prefs.redditRefresh = it }
    }

    /** Finishes sign-in: swaps the code from the redirect for tokens and reads the account name. */
    fun exchangeCode(code: String): String = synchronized(tokenLock) {
        val j = tokenRequest(FormBody.Builder().add("grant_type", "authorization_code").add("code", code).add("redirect_uri", REDIRECT).build())
        if (j.text("refresh_token").isEmpty()) throw RedditAuthException("Reddit didn't return a lasting sign-in.")
        store(j)
        val name = runCatching { me() }.getOrDefault("")
        prefs.redditUser = name
        name
    }

    private fun accessToken(): String = synchronized(tokenLock) {
        prefs.redditAccess?.takeIf { System.currentTimeMillis() / 1000 < prefs.redditAccessExp - 60 }?.let { return it }
        val refresh = prefs.redditRefresh
        val form = if (refresh != null) FormBody.Builder().add("grant_type", "refresh_token").add("refresh_token", refresh).build()
        else FormBody.Builder().add("grant_type", "https://oauth.reddit.com/grants/installed_client").add("device_id", "DO_NOT_TRACK_THIS_DEVICE").build()
        store(tokenRequest(form))
        prefs.redditAccess!!
    }

    private fun get(path: String, params: Map<String, String?> = emptyMap()): JSONObject {
        var attempt = 0
        while (true) {
            val url = (API + path).toHttpUrl().newBuilder().addQueryParameter("raw_json", "1")
                .apply { params.forEach { (k, v) -> if (v != null) addQueryParameter(k, v) } }.build()
            val req = Request.Builder().url(url).header("Authorization", "Bearer ${accessToken()}").header("User-Agent", USER_AGENT).build()
            http.newCall(req).execute().use { r ->
                val body = r.body?.string().orEmpty()
                when {
                    r.code == 401 && attempt == 0 -> prefs.redditAccess = null // token no longer valid: get a new one once
                    r.code == 401 -> throw RedditAuthException("Reddit sign-in expired. Sign in again in Settings.")
                    !r.isSuccessful -> throw HttpException(r.code, "HTTP ${r.code} for reddit $path: ${body.take(200)}")
                    else -> return JSONObject(body)
                }
            }
            attempt++
        }
    }

    fun me(): String = get("/api/v1/me").text("name")

    private fun myName(): String = prefs.redditUser?.takeIf { it.isNotBlank() }
        ?: (if (prefs.redditRefresh != null) me().takeIf { it.isNotBlank() }?.also { prefs.redditUser = it } else null)
        ?: throw RedditAuthException("Sign in to Reddit in Settings.")

    /** Subreddits the account has joined, mature ones first. */
    fun mySubreddits(): List<String> {
        val out = ArrayList<Pair<String, Boolean>>()
        var after: String? = null
        repeat(5) {
            val d = get("/subreddits/mine/subscriber", mapOf("limit" to "100", "after" to after)).optJSONObject("data") ?: return out.named()
            val kids = d.optJSONArray("children") ?: JSONArray()
            for (i in 0 until kids.length()) kids.optJSONObject(i)?.optJSONObject("data")?.let { s ->
                s.text("display_name").takeIf { NAME_RE.matches(it) }?.let { out += it to s.optBoolean("over18") }
            }
            after = d.text("after").ifEmpty { null } ?: return out.named()
        }
        return out.named()
    }

    private fun List<Pair<String, Boolean>>.named() = sortedWith(compareByDescending<Pair<String, Boolean>> { it.second }.thenBy { it.first.lowercase() }).map { it.first }

    /** One screenful of a Reddit feed. Reads on (up to three requests) when few posts have something to play. */
    fun listing(feed: Feed, order: OnlineOrder, cursor: String?, page: Int): OnlinePage {
        val sort = when (order) { OnlineOrder.Trending -> "hot"; OnlineOrder.New -> "new"; else -> "top" }
        val span = when (order) { OnlineOrder.TopWeek -> "week"; OnlineOrder.TopMonth -> "month"; OnlineOrder.TopAll -> "all"; else -> null }
        fun name(s: String) = s.takeIf { NAME_RE.matches(it) } ?: throw HttpException(404, "Not a Reddit name: $s")
        val params = HashMap<String, String?>().apply { put("limit", "100"); put("t", span) }
        val path = when (feed) {
            is Feed.RedditHome -> "/$sort"
            is Feed.Subreddit -> "/r/${name(feed.name)}/$sort"
            is Feed.RedditUser -> "/user/${name(feed.name)}/submitted".also { params["sort"] = sort }
            is Feed.RedditSaved -> "/user/${name(myName())}/saved".also { params["type"] = "links"; params["t"] = null }
            is Feed.RedditUpvoted -> "/user/${name(myName())}/upvoted".also { params["t"] = null }
            else -> error("Not a Reddit feed")
        }
        val out = LinkedHashMap<String, Clip>()
        var after = cursor
        var posts = 0
        var rounds = 0
        do {
            val d = get(path, params + ("after" to after)).optJSONObject("data") ?: break
            val kids = d.optJSONArray("children") ?: JSONArray()
            for (i in 0 until kids.length()) {
                val k = kids.optJSONObject(i) ?: continue
                if (k.text("kind") != "t3") continue
                posts++
                k.optJSONObject("data")?.let(::parse)?.let { out.putIfAbsent(it.id, it) }
            }
            after = d.text("after").ifEmpty { null }
            rounds++
        } while (out.size < 12 && after != null && rounds < 3)
        AppLog.i("Reddit ${feed.title}: $posts post(s), ${out.size} to play (${out.values.count { it.hd == null }} on RedGifs)")
        return OnlinePage(out.values.toList(), page, 0, cursor = after, more = after != null)
    }

    private fun parse(d: JSONObject): Clip? {
        val postId = d.text("id").lowercase().takeIf { it.isNotEmpty() && it.all(Char::isLetterOrDigit) } ?: return null
        // A crosspost carries its media in the post it points to.
        val media = d.optJSONArray("crosspost_parent_list")?.let { it.optJSONObject(it.length() - 1) } ?: d
        val preview = media.optJSONObject("preview") ?: d.optJSONObject("preview")
        val image = preview?.optJSONArray("images")?.optJSONObject(0)
        val source = image?.optJSONObject("source")
        // A still for the tile: the smallest preview at least 300 px wide, else the full one, else the thumbnail.
        val thumb = image?.optJSONArray("resolutions")?.let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it) } }
            ?.firstOrNull { it.optInt("width") >= 300 }?.http("url") ?: source?.http("url") ?: d.http("thumbnail")
        val title = d.text("title")
        val created = d.optDouble("created_utc", 0.0).toLong()
        val label = setOf(OnlineSource.Reddit.label)

        // 1. A link to a RedGifs video: the clip itself, under its RedGifs ID. Its video is looked up when opened.
        redgifsId(media.text("url"))?.let { rg ->
            return Clip(id = rg, hd = null, sd = null, thumb = thumb, duration = 0.0, width = source?.optInt("width") ?: 0,
                height = source?.optInt("height") ?: 0, hasAudio = false, user = "", created = created, sources = label, description = title)
        }
        // 2. Reddit-hosted video, 3. a GIF (Reddit's MP4 copy), 4. Reddit's MP4 preview of an embedded video.
        val video = (media.optJSONObject("secure_media") ?: media.optJSONObject("media"))?.optJSONObject("reddit_video")
        val gif = image?.optJSONObject("variants")?.optJSONObject("mp4")?.optJSONObject("source")
        val embedded = preview?.optJSONObject("reddit_video_preview")
        val (url, meta) = listOf(video?.http("fallback_url") to video, gif?.http("url") to gif, embedded?.http("fallback_url") to embedded)
            .firstOrNull { it.first != null } ?: return null
        return Clip(
            id = "${OnlineSource.Reddit.prefix}$postId".takeIf { Clip.validId(it) } ?: return null,
            hd = url, sd = null, thumb = thumb,
            duration = meta?.num("duration") ?: 0.0, width = meta?.optInt("width") ?: 0, height = meta?.optInt("height") ?: 0,
            hasAudio = false, // the MP4 Reddit hands out has no sound track
            user = d.text("author").takeIf { it != "[deleted]" } ?: "", created = created, sources = label,
            tags = listOf(d.text("subreddit")).filter { it.isNotBlank() }, likes = d.optLong("ups"), description = title,
        )
    }
}
