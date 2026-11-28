package app.redgrab

import android.annotation.SuppressLint
import android.net.Uri
import android.os.Bundle
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.lifecycleScope
import app.redgrab.ui.RedGrabTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Shows Reddit's own page where you log in and allow the app (OAuth, with the client ID from
 * Settings). The app never sees the password: when you tap Allow, Reddit redirects to
 * redgrab://reddit with a one-time code, which is swapped for the tokens. The redirect is read
 * only inside this WebView, and only when it carries the random `state` this screen sent.
 */
class RedditLoginActivity : ComponentActivity() {

    private var web: WebView? = null
    /** Random value sent along and expected back, so a redirect that isn't ours is ignored. */
    private val state: String = ByteArray(16).also { java.security.SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
    private var handled = false
    private var busy by mutableStateOf(false)
    /** Host of the page the browser view is showing. */
    private var pageHost by mutableStateOf("")
    private var message by mutableStateOf<String?>(null)

    /** Locked while this screen was out of sight: back to the main screen, which asks you to unlock. */
    override fun onStart() { super.onStart(); if (AppLock.locked) finish() }

    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val clientId = library.prefs.redditClientId?.takeIf { it.isNotBlank() } ?: run { finish(); return }
        AppLog.i("Reddit sign-in opened")
        setContent {
            RedGrabTheme(library.prefs.theme) {
                val snack = remember { SnackbarHostState() }
                var loading by remember { mutableIntStateOf(0) }
                var canGoBack by remember { mutableStateOf(false) }
                BackHandler(enabled = canGoBack) { web?.goBack() }
                LaunchedEffect(message) { message?.let { snack.showSnackbar(it); message = null } }
                Scaffold(
                    topBar = {
                        TopAppBar(
                            title = { androidx.compose.foundation.layout.Column { Text("Sign in to Reddit"); if (pageHost.isNotEmpty()) Text(pageHost, style = MaterialTheme.typography.labelSmall) } },
                            navigationIcon = { IconButton(onClick = { finish() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Close") } },
                        )
                    },
                    snackbarHost = { SnackbarHost(snack) },
                ) { pad ->
                    Box(Modifier.padding(pad).fillMaxSize()) {
                        AndroidView(modifier = Modifier.fillMaxSize(), factory = { ctx ->
                            createWebView(ctx, RedditApi.authorizeUrl(clientId, state), onProgress = { loading = it }, onNav = { canGoBack = it })
                        })
                        if (loading in 1..99) LinearProgressIndicator(progress = { loading / 100f }, modifier = Modifier.fillMaxWidth())
                        if (busy) CircularProgressIndicator(Modifier.align(Alignment.Center))
                    }
                }
            }
        }
    }

    private fun isRedirect(u: Uri) = u.scheme == "redgrab" && u.host == "reddit"

    @SuppressLint("SetJavaScriptEnabled")
    private fun createWebView(ctx: android.content.Context, url: String, onProgress: (Int) -> Unit, onNav: (Boolean) -> Unit): WebView {
        val w = WebView(ctx)
        web = w
        // Without this the page is told the screen has no height (Android does that for a browser
        // view that "wraps its content"), and sites laid out to fill the screen, like RedGifs, show nothing.
        w.layoutParams = android.view.ViewGroup.LayoutParams(android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.MATCH_PARENT)
        CookieManager.getInstance().setAcceptCookie(true)
        w.settings.javaScriptEnabled = true
        w.settings.domStorageEnabled = true
        w.settings.allowFileAccess = false
        w.settings.allowContentAccess = false
        w.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, req: WebResourceRequest): Boolean {
                if (isRedirect(req.url)) { onRedirect(req.url); return true }
                // Only Reddit's own pages load here; other sites and other link types (app links, intents) are not followed.
                val host = req.url.host?.lowercase().orEmpty()
                val reddit = req.url.scheme == "https" && (host == "reddit.com" || host.endsWith(".reddit.com"))
                if (req.isForMainFrame && !reddit) message = "Only Reddit's pages open here."
                return req.isForMainFrame && !reddit
            }

            override fun onPageStarted(view: WebView, url: String?, favicon: android.graphics.Bitmap?) {
                pageHost = runCatching { android.net.Uri.parse(url).host }.getOrNull().orEmpty()
                // A form can lead elsewhere without being asked about above: such a page is stopped.
                val h = pageHost.lowercase()
                if (url != null && url.startsWith("http") && !(url.startsWith("https://") && (h == "reddit.com" || h.endsWith(".reddit.com")))) {
                    view.stopLoading(); if (view.canGoBack()) view.goBack(); message = "Only Reddit's pages open here."
                }
            }

            override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) = onNav(view.canGoBack())

            override fun onReceivedError(view: WebView, req: WebResourceRequest, err: WebResourceError) {
                if (!req.isForMainFrame) return
                // Some WebView versions report the app's redirect as an unknown address instead of asking first.
                if (isRedirect(req.url)) onRedirect(req.url)
                else AppLog.w("Reddit sign-in page failed to load: ${err.errorCode} ${err.description} (${req.url.host})")
            }
        }
        w.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, newProgress: Int) = onProgress(newProgress)
        }
        w.loadUrl(url)
        return w
    }

    /** Reddit sent the browser back to the app: check it's the answer to our request, then finish signing in. */
    private fun onRedirect(u: Uri) {
        if (handled) return
        if (u.getQueryParameter("state") != state) {
            AppLog.w("Reddit redirect ignored: state didn't match")
            message = "That didn't match this sign-in. Try again."
            return
        }
        handled = true
        val code = u.getQueryParameter("code")
        if (code.isNullOrEmpty()) {
            AppLog.w("Reddit sign-in not completed: ${u.getQueryParameter("error") ?: "no code"}")
            finish()
            return
        }
        busy = true
        lifecycleScope.launch {
            val r = withContext(Dispatchers.IO) {
                runCatching { RedditApi(library.prefs, RedGifsApi.clientFor(userAgent)).exchangeCode(code) }
            }
            busy = false
            r.onSuccess { name ->
                AppLog.i("Signed in to Reddit" + if (name.isNotEmpty()) " as $name" else "")
                // The token is all the app needs from here on; Reddit's web sign-in isn't kept.
                forgetCookies("reddit.com", listOf("https://www.reddit.com/", "https://reddit.com/", "https://old.reddit.com/", "https://accounts.reddit.com/"))
                setResult(RESULT_OK)
                finish()
            }.onFailure { e ->
                AppLog.e("Reddit sign-in failed: ${e.message}", e.takeUnless { it is RedditAuthException })
                handled = false
                message = (e as? RedditAuthException)?.message ?: "Reddit sign-in failed. See Log."
            }
        }
    }

    override fun onDestroy() {
        web?.destroy()
        web = null
        super.onDestroy()
    }

    /**
     * Removes one site's cookies from the app's built-in browser, leaving other sites' alone
     * (RedGifs' are needed to renew its session).
     */
    private fun forgetCookies(domain: String, urls: List<String>) = runCatching {
        val cm = CookieManager.getInstance()
        for (url in urls) {
            val names = cm.getCookie(url)?.split(';')?.mapNotNull { it.substringBefore('=').trim().takeIf(String::isNotEmpty) } ?: continue
            for (n in names) {
                // A cookie is replaced only by one with the same scope, so both common scopes are cleared.
                cm.setCookie(url, "$n=; Max-Age=0; Path=/; Secure")
                cm.setCookie(url, "$n=; Max-Age=0; Path=/; Domain=.$domain; Secure")
            }
        }
        cm.flush()
    }
}
