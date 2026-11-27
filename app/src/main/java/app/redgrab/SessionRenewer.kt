package app.redgrab

import android.annotation.SuppressLint
import android.content.Context
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayInputStream

/**
 * Keeps the RedGifs sign-in going without asking you to log in again.
 *
 * RedGifs' access token only lasts a short while (and is tied to the network address it was
 * issued for). The site itself copes by quietly fetching a new one whenever it's opened, using
 * the longer-lived login it keeps in the browser's own storage. This does the same thing out of
 * sight: it opens redgifs.com in a WebView that is never shown (the same browser storage the
 * sign-in screen used), waits for the site to start using a fresh token, checks that token
 * really belongs to a signed-in user, and stores it. No password or code is involved, and no
 * videos or pictures are loaded.
 *
 * It only stops working when RedGifs' own login has ended (after weeks without use, or after
 * signing out); then the app asks you to sign in as before.
 */
object SessionRenewer {
    private val lock = Mutex()
    @Volatile private var renewedAt = 0L
    @Volatile private var failedAt = 0L

    private fun isRedgifs(host: String?) = host != null && (host == "redgifs.com" || host.endsWith(".redgifs.com"))

    /** Fetches a fresh token. True when a working signed-in token is stored afterwards. */
    suspend fun renew(ctx: Context): Boolean = lock.withLock {
        val app = ctx.applicationContext
        val prefs = app.library.prefs
        val ua = prefs.userAgent ?: return@withLock false
        if (prefs.token == null) return@withLock false // signed out: nothing to keep going
        val now = System.currentTimeMillis()
        // Several things can ask at once (a sync, the Online tab); one renewal serves them all.
        if (now - renewedAt < 20_000) return@withLock true
        // After a failure, don't load the site again for every request that follows.
        if (now - failedAt < 120_000) return@withLock false
        AppLog.i("Renewing the RedGifs session in the background")
        val fresh = withTimeoutOrNull(35_000) { capture(app, ua) }
        if (fresh == null) {
            failedAt = System.currentTimeMillis()
            AppLog.w("The RedGifs session couldn't be renewed in the background; signing in again is needed")
            return@withLock false
        }
        val exp = Session.expiryOf(fresh)
        prefs.token = fresh
        prefs.tokenExp = exp
        renewedAt = System.currentTimeMillis()
        AppLog.i("RedGifs session renewed" + if (exp > 0) " (valid until ${java.util.Date(exp * 1000)})" else "")
        true
    }

    /** Opens the site unseen and returns the first token it uses that passes the signed-in check. */
    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun capture(app: Context, ua: String): String? {
        val seen = Channel<String>(Channel.UNLIMITED)
        var web: WebView? = null
        try {
            withContext(Dispatchers.Main) {
                val w = WebView(app)
                web = w
                CookieManager.getInstance().setAcceptCookie(true)
                CookieManager.getInstance().setAcceptThirdPartyCookies(w, true)
                w.settings.javaScriptEnabled = true
                w.settings.domStorageEnabled = true
                w.settings.allowFileAccess = false
                w.settings.allowContentAccess = false
                // The token is tied to the browser it was issued to: keep the one from sign-in.
                w.settings.userAgentString = ua
                w.settings.blockNetworkImage = true
                w.webViewClient = object : WebViewClient() {
                    override fun shouldInterceptRequest(view: WebView, req: WebResourceRequest): WebResourceResponse? {
                        val host = req.url.host
                        if (host == "api.redgifs.com") {
                            req.requestHeaders.entries.firstOrNull { it.key.equals("Authorization", ignoreCase = true) }
                                ?.value?.removePrefix("Bearer ")?.trim()?.takeIf { it.isNotEmpty() }?.let { seen.trySend(it) }
                        }
                        // Nothing is shown, so no clip or picture needs to be fetched.
                        if (host == "media.redgifs.com" || host == "userpic.redgifs.com") {
                            return WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))
                        }
                        return null
                    }

                    // The page stays on redgifs.com; a token seen there can only be RedGifs' own.
                    override fun shouldOverrideUrlLoading(view: WebView, req: WebResourceRequest): Boolean =
                        req.isForMainFrame && !(req.url.scheme == "https" && isRedgifs(req.url.host))
                }
                // Not attached to any screen; give it a size anyway so the page lays out as on a phone.
                w.measure(android.view.View.MeasureSpec.makeMeasureSpec(1080, android.view.View.MeasureSpec.EXACTLY),
                    android.view.View.MeasureSpec.makeMeasureSpec(1920, android.view.View.MeasureSpec.EXACTLY))
                w.layout(0, 0, 1080, 1920)
                w.loadUrl("https://www.redgifs.com/")
            }
            val tried = HashSet<String>()
            for (token in seen) {
                if (!tried.add(token)) continue
                val exp = Session.expiryOf(token)
                // Skip one that is about to run out (the old one, if the site still had it).
                if (exp > 0 && exp - System.currentTimeMillis() / 1000 < Session.RENEW_MARGIN_S) continue
                // Before its own login is restored the site uses a visitor token; that one fails this check.
                val ok = withContext(Dispatchers.IO) { runCatching { RedGifsApi(token, ua).checkSignedIn() }.isSuccess }
                if (ok) return token
            }
            return null
        } finally {
            withContext(NonCancellable + Dispatchers.Main) {
                web?.let { runCatching { it.stopLoading(); it.destroy() } }
            }
        }
    }
}

/**
 * Runs [block] with the RedGifs API as the signed-in user. A session that has run out (or that
 * RedGifs no longer accepts) is renewed in the background once and [block] is tried again.
 */
suspend fun <T> withRedGifs(ctx: Context, block: suspend (RedGifsApi) -> T): T {
    val prefs = ctx.library.prefs
    fun api() = RedGifsApi(prefs.token ?: throw AuthException(), prefs.userAgent ?: throw AuthException())
    if (Session.state(prefs) == Session.State.Expired) SessionRenewer.renew(ctx)
    return try {
        block(api())
    } catch (e: AuthException) {
        if (!SessionRenewer.renew(ctx)) throw e
        block(api())
    }
}
