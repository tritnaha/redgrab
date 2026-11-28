package app.redgrab

import android.annotation.SuppressLint
import android.os.Bundle
import android.webkit.ConsoleMessage
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import app.redgrab.ui.RedGrabTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONTokener
import kotlin.coroutines.resume

/**
 * Shows the real redgifs.com site. You sign in yourself (username, emailed code,
 * captcha); the app only reads the resulting session token afterwards.
 */
class LoginActivity : ComponentActivity() {
    /** Host of the page the browser view is showing. */
    private var pageHost by androidx.compose.runtime.mutableStateOf("")

    companion object {
        /** Open in "renew" mode: close automatically once a fresh token shows up. */
        const val EXTRA_RENEW = "renew"
    }

    /** Last bearer token seen on a request to api.redgifs.com. */
    @Volatile private var seenToken: String? = null
    /** True while the page shown is on redgifs.com (tokens are only read then). */
    @Volatile private var onRedgifs = false

    private fun isRedgifs(url: String?): Boolean {
        val u = runCatching { android.net.Uri.parse(url ?: "") }.getOrNull() ?: return false
        val host = u.host?.lowercase() ?: return false
        return u.scheme == "https" && (host == "redgifs.com" || host.endsWith(".redgifs.com"))
    }
    private var web: WebView? = null

    /** Locked while this screen was out of sight: back to the main screen, which asks you to unlock. */
    override fun onStart() { super.onStart(); if (AppLock.locked) finish() }

    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        AppLog.i("Sign-in screen opened")
        setContent {
            RedGrabTheme(library.prefs.theme) {
                val snack = remember { SnackbarHostState() }
                val scope = rememberCoroutineScope()
                var loading by remember { mutableIntStateOf(0) }
                var checking by remember { mutableStateOf(false) }
                var canGoBack by remember { mutableStateOf(false) }

                BackHandler(enabled = canGoBack) { web?.goBack() }

                // Renew mode: if RedGifs still has you signed in, a fresh token appears by itself.
                if (renew) LaunchedEffect(Unit) {
                    repeat(20) {
                        kotlinx.coroutines.delay(2500)
                        if (tryToken(quiet = true) == null) return@LaunchedEffect
                    }
                    snack.showSnackbar("Couldn't renew automatically. Log in on the page, then tap Done.")
                }

                Scaffold(
                    topBar = {
                        TopAppBar(
                            // The address of the page on show, so a page that isn't RedGifs can be told apart.
                            title = { androidx.compose.foundation.layout.Column { Text("Sign in to RedGifs"); if (pageHost.isNotEmpty()) Text(pageHost, style = MaterialTheme.typography.labelSmall) } },
                            navigationIcon = {
                                IconButton(onClick = { finish() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Close") }
                            },
                            actions = {
                                Button(
                                    enabled = !checking,
                                    onClick = {
                                        checking = true
                                        scope.launch {
                                            val msg = tryToken(quiet = false)
                                            checking = false
                                            if (msg != null) snack.showSnackbar(msg)
                                        }
                                    },
                                    modifier = Modifier.padding(end = 8.dp),
                                ) {
                                    if (checking) CircularProgressIndicator(Modifier.padding(2.dp), strokeWidth = 2.dp)
                                    else Text("Done")
                                }
                            },
                        )
                    },
                    snackbarHost = { SnackbarHost(snack) },
                ) { pad ->
                    Column(Modifier.padding(pad).fillMaxSize()) {
                        Text(
                            "Log in below with your username and the code RedGifs emails you. " +
                                "When you can see your account, tap Done.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        )
                        Box(Modifier.fillMaxSize()) {
                            AndroidView(
                                modifier = Modifier.fillMaxSize(),
                                factory = { ctx ->
                                    createWebView(ctx, onProgress = { loading = it }, onNav = { canGoBack = it })
                                },
                            )
                            if (loading in 1..99) {
                                LinearProgressIndicator(
                                    progress = { loading / 100f },
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun createWebView(ctx: android.content.Context, onProgress: (Int) -> Unit, onNav: (Boolean) -> Unit): WebView {
        val w = WebView(ctx)
        web = w
        // Without this the page is told the screen has no height (Android does that for a browser
        // view that "wraps its content"), and sites laid out to fill the screen, like RedGifs, show nothing.
        w.layoutParams = android.view.ViewGroup.LayoutParams(android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.MATCH_PARENT)
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(w, true)
        w.settings.javaScriptEnabled = true
        w.settings.domStorageEnabled = true
        // A web page has no business reading files or other apps' content.
        w.settings.allowFileAccess = false
        w.settings.allowContentAccess = false
        w.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, req: WebResourceRequest): WebResourceResponse? {
                if (onRedgifs && req.url.host == "api.redgifs.com") {
                    req.requestHeaders.entries
                        .firstOrNull { it.key.equals("Authorization", ignoreCase = true) }
                        ?.value?.removePrefix("Bearer ")?.trim()
                        ?.takeIf { it.isNotEmpty() }
                        ?.let { seenToken = it }
                }
                return null
            }

            // The page stays on RedGifs: a link or redirect to another site is not followed here.
            override fun shouldOverrideUrlLoading(view: WebView, req: WebResourceRequest): Boolean =
                req.isForMainFrame && !isRedgifs(req.url.toString())

            override fun onPageStarted(view: WebView, url: String?, favicon: android.graphics.Bitmap?) {
                pageHost = runCatching { android.net.Uri.parse(url).host }.getOrNull().orEmpty()
                // A form can lead elsewhere without being asked about above: such a page is stopped and the sign-in page comes back.
                if (url != null && url.startsWith("http") && !isRedgifs(url)) { view.stopLoading(); if (view.canGoBack()) view.goBack() }
                onRedgifs = isRedgifs(url)
                if (!onRedgifs) seenToken = null // a token seen earlier doesn't carry over to another site
            }

            override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) {
                onRedgifs = isRedgifs(url)
                onNav(view.canGoBack())
            }

            override fun onReceivedError(view: WebView, req: WebResourceRequest, err: WebResourceError) {
                if (req.isForMainFrame) AppLog.w("Sign-in page failed to load: ${err.errorCode} ${err.description} (${req.url.host})")
            }

            override fun onReceivedHttpError(view: WebView, req: WebResourceRequest, resp: WebResourceResponse) {
                if (req.isForMainFrame) AppLog.w("Sign-in page HTTP ${resp.statusCode} (${req.url.host})")
            }
        }
        w.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, newProgress: Int) = onProgress(newProgress)
            override fun onConsoleMessage(m: ConsoleMessage): Boolean {
                if (m.messageLevel() == ConsoleMessage.MessageLevel.ERROR) AppLog.w("Page error: ${m.message().take(200)}")
                return true
            }
        }
        w.loadUrl("https://www.redgifs.com/")
        return w
    }

    private val renew by lazy { intent.getBooleanExtra(EXTRA_RENEW, false) }

    /** Returns an error message to show, or null after a successful sign-in (screen closes). */
    private suspend fun tryToken(quiet: Boolean): String? {
        val w = web ?: return "Page not ready yet."
        // Another site's page could hold a token of its own choosing: only redgifs.com counts.
        if (!isRedgifs(w.url)) return "Finish signing in on redgifs.com, then tap Done."
        // Where the site keeps its token. Seen on the site (2026-11): JSON {token} under
        // "session_data" in localStorage. Older places are still looked at: sessionStorage.auth_token,
        // and JSON {data:{access_token}} under "auth_data". Every one found is tried.
        val js = """
            (function(){
              function at(s, key, path){ try { var j = JSON.parse(s.getItem(key)||'null');
                for (var i = 0; i < path.length && j; i++) j = j[path[i]];
                return typeof j === 'string' ? j : null; } catch(e){ return null; } }
              var found = [at(localStorage,'session_data',['token']), at(sessionStorage,'session_data',['token']),
                sessionStorage.getItem('auth_token'), at(localStorage,'auth_data',['data','access_token']), at(sessionStorage,'auth_data',['data','access_token'])];
              return JSON.stringify(found.filter(function(t){ return !!t; }));
            })()
        """.trimIndent()
        val raw = suspendCancellableCoroutine { cont -> w.evaluateJavascript(js) { cont.resume(it) } }
        val stored = runCatching { org.json.JSONArray(JSONTokener(raw).nextValue() as String).strings() }.getOrDefault(emptyList())
        val candidates = (stored + listOfNotNull(seenToken)).distinct()
        val ua = w.settings.userAgentString
        val good = withContext(Dispatchers.IO) {
            candidates.firstOrNull { t ->
                runCatching { RedGifsApi(t, ua).checkSignedIn() }
                    .onFailure { if (it !is AuthException) AppLog.w("Sign-in check failed: ${it.message}") }
                    .isSuccess
            }
        }
        if (good == null) {
            if (!quiet) AppLog.w("Sign-in not complete (${candidates.size} token candidates, none accepted)")
            return "Not signed in yet. Finish logging in on the page, then tap Done."
        }
        val exp = Session.expiryOf(good)
        // While renewing automatically, wait for a token with real time left on it.
        if (quiet && exp > 0 && exp - System.currentTimeMillis() / 1000 < Session.RENEW_MARGIN_S) {
            return "Waiting for a fresh session…"
        }
        library.prefs.apply { token = good; userAgent = ua; tokenExp = exp }
        AppLog.i(if (renew) "Session renewed" else "Signed in" + if (exp > 0) " (token valid until ${java.util.Date(exp * 1000)})" else "")
        setResult(RESULT_OK)
        finish()
        return null
    }

    override fun onDestroy() {
        web?.destroy()
        web = null
        super.onDestroy()
    }
}
