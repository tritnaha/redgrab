package app.redgrab

import android.annotation.SuppressLint
import android.os.Bundle
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.lifecycleScope
import app.redgrab.ui.RedGrabTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Shows RedGifs' own human check (Cloudflare Turnstile) when RedGifs pauses the app with one.
 * The user solves it themselves; the app only passes the resulting answer back to RedGifs,
 * exactly like the website does, and then continues any paused downloads.
 */
class VerifyActivity : ComponentActivity() {

    private var status by mutableStateOf("Solve the check below to continue.")
    private var busy by mutableStateOf(false)

    /** Locked while this screen was out of sight: back to the main screen, which asks you to unlock. */
    override fun onStart() { super.onStart(); if (AppLock.locked) finish() }

    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val subject = library.prefs.pendingVerification
        if (subject == null) { finish(); return }
        AppLog.i("Verification screen opened")

        setContent {
            RedGrabTheme(library.prefs.theme) {
                Scaffold(topBar = {
                    TopAppBar(
                        title = { Text("Quick check") },
                        navigationIcon = { IconButton(onClick = { finish() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Close") } },
                    )
                }) { pad ->
                    Column(Modifier.padding(pad).fillMaxSize().padding(16.dp)) {
                        Text("RedGifs asked for a human check before it continues. This is RedGifs' own check; RedGrab just shows it to you.",
                            style = MaterialTheme.typography.bodyMedium)
                        Text(status, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = 12.dp))
                        Box(Modifier.fillMaxWidth().height(320.dp), contentAlignment = Alignment.Center) {
                            AndroidView(modifier = Modifier.fillMaxSize(), factory = { ctx -> turnstileView(ctx, subject) })
                            if (busy) CircularProgressIndicator()
                        }
                    }
                }
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
    private fun turnstileView(ctx: android.content.Context, subject: String): WebView {
        val w = WebView(ctx)
        // Tell the page the real height (see LoginActivity): a browser view that "wraps its content" reports none.
        w.layoutParams = android.view.ViewGroup.LayoutParams(android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.MATCH_PARENT)
        w.settings.javaScriptEnabled = true
        w.settings.domStorageEnabled = true
        library.prefs.userAgent?.let { w.settings.userAgentString = it }
        // The page must stay on our own check: no navigation away (keeps the JS bridge private).
        w.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: android.webkit.WebResourceRequest): Boolean =
                request.isForMainFrame
        }
        web = w
        w.addJavascriptInterface(object {
            @JavascriptInterface fun onToken(token: String) = runOnUiThread { submit(subject, token) }
            @JavascriptInterface fun onError(code: String) = runOnUiThread { status = "The check failed ($code). It will retry; you can also close and try later." }
        }, "RG")
        // Served as if from www.redgifs.com so the check runs for RedGifs' own site key.
        val html = """
            <!doctype html><html><head><meta name="viewport" content="width=device-width,initial-scale=1">
            <script src="https://challenges.cloudflare.com/turnstile/v0/api.js?onload=go&render=explicit" async defer></script>
            <style>body{background:#111;margin:0;display:flex;justify-content:center;padding-top:24px}</style></head>
            <body><div id="c"></div><script>
              function go(){ turnstile.render('#c',{sitekey:'${RedGifsApi.TURNSTILE_SITE_KEY}',theme:'dark',
                callback:function(t){RG.onToken(t)}, 'error-callback':function(e){RG.onError(String(e))}}); }
            </script></body></html>
        """.trimIndent()
        w.loadDataWithBaseURL("https://www.redgifs.com/", html, "text/html", "utf-8", null)
        return w
    }

    private var web: WebView? = null

    override fun onDestroy() {
        web?.let { it.removeJavascriptInterface("RG"); it.destroy() }
        web = null
        super.onDestroy()
    }

    private fun submit(subject: String, captcha: String) {
        if (busy) return
        busy = true
        status = "Checking…"
        lifecycleScope.launch {
            val prefs = library.prefs
            val ok = withContext(Dispatchers.IO) {
                runCatching { RedGifsApi(prefs.token!!, prefs.userAgent!!).submitVerification(subject, captcha) }
                    .onFailure { AppLog.e("Verification not accepted: ${it.message}") }.isSuccess
            }
            busy = false
            if (ok) {
                prefs.pendingVerification = null
                AppLog.i("Verification accepted; continuing downloads")
                // Continue only a download run that this check interrupted (not an old, finished queue).
                if (prefs.resumeAfterVerify) {
                    prefs.resumeAfterVerify = false
                    val left = library.readQueue()
                    if (left.isNotEmpty()) DownloadWorker.start(this@VerifyActivity, left)
                }
                finish()
            } else {
                status = "RedGifs didn't accept that. Close this screen and try again from the app."
            }
        }
    }
}
