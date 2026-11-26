package app.redgrab

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Updates the app from the releases published on its GitHub repository.
 *
 * A release is a tag "v<version>" with the APK attached as "RedGrab-<version>-<build>.apk"
 * (the release workflow makes these). The latest release is asked for through GitHub's API;
 * when its build number is higher than this build's, the APK is downloaded and handed to
 * Android's installer. Android only accepts it if it is signed with the same key as the app
 * that is installed, so nothing but a genuine build can replace the app.
 *
 * While the repository is private, GitHub answers only with a token (Settings → About).
 */
object Updater {

    data class Release(val version: String, val build: Int, val assetUrl: String, val size: Long, val notes: String)

    sealed interface State {
        data object Idle : State
        data object Checking : State
        data object UpToDate : State
        /** The repository has no release at all yet. */
        data object NoRelease : State
        data class Available(val release: Release) : State
        data class Downloading(val release: Release, val percent: Int) : State
        /** Downloaded and waiting to be installed. */
        data class Ready(val release: Release) : State
        data class Installing(val release: Release) : State
        data class Failed(val message: String) : State
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state
    private val lock = Mutex()

    private const val API = "https://api.github.com/repos/${BuildConfig.GITHUB_REPO}"
    const val PAGE = "https://github.com/${BuildConfig.GITHUB_REPO}"
    private val ASSET_BUILD = Regex("-(\\d+)\\.apk$", RegexOption.IGNORE_CASE)

    /** The build number in an APK's file name ("RedGrab-13.1-14.apk" → 14), or null. */
    fun buildOf(assetName: String): Int? = ASSET_BUILD.find(assetName)?.groupValues?.get(1)?.toIntOrNull()

    /** Reads GitHub's "latest release" answer. Null when it has no APK with a build number. */
    fun parseRelease(json: String): Release? {
        val j = JSONObject(json)
        val assets = j.optJSONArray("assets") ?: return null
        for (i in 0 until assets.length()) {
            val a = assets.optJSONObject(i) ?: continue
            val build = buildOf(a.optString("name")) ?: continue
            return Release(
                version = j.optString("tag_name").removePrefix("v").ifEmpty { j.optString("name") }, build = build,
                assetUrl = a.optString("url"), size = a.optLong("size"),
                notes = (if (j.isNull("body")) "" else j.optString("body")).trim().take(2000),
            )
        }
        return null
    }

    private fun request(url: String, token: String?, accept: String) = Request.Builder().url(url)
        .header("Accept", accept).header("X-GitHub-Api-Version", "2022-11-28")
        .apply { if (!token.isNullOrBlank()) header("Authorization", "Bearer $token") }

    private fun explain(code: Int, hasToken: Boolean) = when {
        code == 404 && !hasToken -> "No release found. If the repository is private, GitHub needs a token (Settings → About)."
        code == 404 -> "No release found, or the token can't read this repository."
        code == 401 -> "GitHub didn't accept the token."
        code == 403 || code == 429 -> "GitHub refused the request (HTTP $code). Try later."
        else -> "GitHub returned an error (HTTP $code)."
    }

    /** Asks GitHub for the latest release. Returns it when it is newer than this build, else null. */
    suspend fun check(ctx: Context): Release? = withContext(Dispatchers.IO) {
        val prefs = ctx.library.prefs
        _state.value = State.Checking
        try {
            val http = RedGifsApi.clientFor(ctx.userAgent)
            val token = prefs.githubToken
            http.newCall(request("$API/releases/latest", token, "application/vnd.github+json").build()).execute().use { r ->
                val body = r.body?.string().orEmpty()
                if (r.code == 404 && repoIsPublic(ctx) == true) {
                    // The repository can be read, so "not found" means nothing has been published yet.
                    prefs.lastUpdateCheck = System.currentTimeMillis()
                    AppLog.i("Update check: no release has been published yet")
                    _state.value = State.NoRelease
                    return@withContext null
                }
                if (!r.isSuccessful) throw HttpException(r.code, explain(r.code, !token.isNullOrBlank()))
                prefs.lastUpdateCheck = System.currentTimeMillis()
                val release = parseRelease(body)
                if (release == null || release.build <= BuildConfig.VERSION_CODE) {
                    _state.value = State.UpToDate
                    return@withContext null
                }
                AppLog.i("Update available: ${release.version} (build ${release.build}); this is build ${BuildConfig.VERSION_CODE}")
                _state.value = if (file(ctx, release).let { it.exists() && it.length() == release.size }) State.Ready(release) else State.Available(release)
                release
            }
        } catch (e: Exception) {
            AppLog.w("Update check failed: ${e.message}")
            _state.value = State.Failed(if (e is HttpException) e.message ?: "GitHub returned an error." else "Couldn't reach GitHub.")
            null
        }
    }

    private fun file(ctx: Context, r: Release) = File(File(ctx.cacheDir, "update"), "RedGrab-${r.build}.apk")

    /** Downloads the release's APK (once). True when it is there and complete. */
    suspend fun download(ctx: Context, r: Release): Boolean = withContext(Dispatchers.IO) {
        lock.withLock {
            val out = file(ctx, r)
            if (out.exists() && out.length() == r.size) { _state.value = State.Ready(r); return@withLock true }
            try {
                out.parentFile?.let { dir -> dir.mkdirs(); dir.listFiles()?.forEach { it.delete() } } // older downloads
                _state.value = State.Downloading(r, 0)
                val http = RedGifsApi.clientFor(ctx.userAgent)
                // The asset's API address answers with the file itself when asked for "octet-stream".
                http.newCall(request(r.assetUrl, ctx.library.prefs.githubToken, "application/octet-stream").build()).execute().use { resp ->
                    if (!resp.isSuccessful) throw HttpException(resp.code, explain(resp.code, !ctx.library.prefs.githubToken.isNullOrBlank()))
                    val part = File(out.path + ".part")
                    part.outputStream().use { o ->
                        val input = resp.body!!.byteStream()
                        val buf = ByteArray(128 * 1024)
                        var done = 0L
                        var shown = -1
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            o.write(buf, 0, n)
                            done += n
                            val pct = if (r.size > 0) (done * 100 / r.size).toInt().coerceIn(0, 100) else 0
                            if (pct != shown) { shown = pct; _state.value = State.Downloading(r, pct) }
                        }
                    }
                    if (r.size > 0 && part.length() != r.size) throw java.io.IOException("The download was cut short")
                    // It must be this app, and a newer build, before it is offered to the installer.
                    val info = ctx.packageManager.getPackageArchiveInfo(part.path, 0)
                    if (info == null || info.packageName != ctx.packageName) throw java.io.IOException("The file isn't a RedGrab build")
                    if (!part.renameTo(out)) throw java.io.IOException("Couldn't store the update")
                }
                AppLog.i("Update ${r.version} downloaded (${Format.size(out.length())})")
                _state.value = State.Ready(r)
                true
            } catch (e: Exception) {
                AppLog.w("Update download failed: ${e.message}")
                _state.value = State.Failed(if (e is HttpException) e.message ?: "GitHub returned an error." else "Download failed: ${e.message}")
                false
            }
        }
    }

    /**
     * Hands the downloaded APK to Android's installer. On Android 12 and later an app may update
     * itself without a confirmation each time; otherwise (and the first time) Android asks.
     */
    suspend fun install(ctx: Context, r: Release): Boolean = withContext(Dispatchers.IO) {
        val apk = file(ctx, r)
        if (!apk.exists()) { _state.value = State.Failed("The update file is gone; check again."); return@withContext false }
        try {
            val installer = ctx.packageManager.packageInstaller
            val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
                setAppPackageName(ctx.packageName)
                setSize(apk.length())
                if (Build.VERSION.SDK_INT >= 31) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
            }
            val id = installer.createSession(params)
            installer.openSession(id).use { session ->
                session.openWrite("redgrab.apk", 0, apk.length()).use { out ->
                    apk.inputStream().use { it.copyTo(out, 256 * 1024) }
                    session.fsync(out)
                }
                val result = Intent(ctx, UpdateReceiver::class.java).setAction(UpdateReceiver.ACTION).putExtra(UpdateReceiver.VERSION, r.version)
                val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
                session.commit(PendingIntent.getBroadcast(ctx, id, result, flags).intentSender)
            }
            AppLog.i("Installing update ${r.version} (build ${r.build})")
            _state.value = State.Installing(r)
            true
        } catch (e: Exception) {
            AppLog.e("Could not start the update", e)
            _state.value = State.Failed("Couldn't start the update: ${e.message}")
            false
        }
    }

    internal fun failed(message: String) { _state.value = State.Failed(message) }

    /** Whether the repository is public (anyone can read its issues and releases); null when GitHub can't be asked. */
    suspend fun repoIsPublic(ctx: Context): Boolean? = withContext(Dispatchers.IO) {
        runCatching {
            RedGifsApi.clientFor(ctx.userAgent).newCall(request(API, ctx.library.prefs.githubToken, "application/vnd.github+json").build()).execute().use { r ->
                // Without a token a private repository answers "not found".
                if (r.code == 404 && ctx.library.prefs.githubToken.isNullOrBlank()) return@runCatching false
                if (!r.isSuccessful) return@runCatching null
                !JSONObject(r.body?.string().orEmpty()).optBoolean("private", true)
            }
        }.getOrNull()
    }

    /** Files a report as an issue on the repository. Returns the issue's address. */
    suspend fun sendReport(ctx: Context, title: String, body: String): String = withContext(Dispatchers.IO) {
        val token = ctx.library.prefs.githubToken?.takeIf { it.isNotBlank() } ?: throw java.io.IOException("Add a GitHub token first (Settings → About).")
        val json = JSONObject().put("title", title.take(120)).put("body", body).toString().toRequestBody("application/json".toMediaType())
        RedGifsApi.clientFor(ctx.userAgent).newCall(request("$API/issues", token, "application/vnd.github+json").post(json).build()).execute().use { r ->
            val text = r.body?.string().orEmpty()
            if (!r.isSuccessful) throw java.io.IOException(
                if (r.code == 403 || r.code == 404) "The token can't create issues on this repository (it needs Issues: read and write)."
                else "GitHub returned an error (HTTP ${r.code}).")
            JSONObject(text).optString("html_url")
        }
    }
}

/** Looks for a newer release twice a day and, with "Update automatically" on, installs it when the app isn't in use. */
class UpdateWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    companion object {
        private const val NAME = "update-check"

        fun schedule(ctx: Context) {
            val wm = WorkManager.getInstance(ctx)
            if (!ctx.library.prefs.autoUpdate) { wm.cancelUniqueWork(NAME); return }
            val req = PeriodicWorkRequestBuilder<UpdateWorker>(12, TimeUnit.HOURS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).setRequiresBatteryNotLow(true).build())
                .build()
            wm.enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.KEEP, req)
        }

        /** Checks, downloads and (when the app isn't in front and nothing is downloading) installs. */
        suspend fun updateNow(ctx: Context, installIfIdle: Boolean): Updater.Release? {
            val release = Updater.check(ctx) ?: return null
            if (!Updater.download(ctx, release)) return release
            if (installIfIdle && !RedGrabApp.inFront && !DownloadWorker.active) Updater.install(ctx, release)
            return release
        }
    }

    override suspend fun doWork(): Result {
        if (!applicationContext.library.prefs.autoUpdate) return Result.success()
        runCatching { updateNow(applicationContext, installIfIdle = true) }.onFailure { AppLog.w("Background update check failed: ${it.message}") }
        return Result.success()
    }
}

/** Hears back from Android's installer: it wants a confirmation, or the update failed. (On success the app is replaced and restarted.) */
class UpdateReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION = "app.redgrab.UPDATE_RESULT"
        const val VERSION = "version"
        private const val NOTIFICATION = 7
    }

    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action != ACTION) return
        val version = intent.getStringExtra(VERSION).orEmpty()
        when (val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                // Android wants you to confirm. Shown straight away when the app is open, otherwise from a notification.
                @Suppress("DEPRECATION")
                val confirm = (if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                else intent.getParcelableExtra(Intent.EXTRA_INTENT)) ?: return
                confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                if (RedGrabApp.inFront && runCatching { ctx.startActivity(confirm) }.isSuccess) return
                val tap = PendingIntent.getActivity(ctx, NOTIFICATION, confirm, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
                notify(ctx, "Update $version is ready", "Tap to install.", tap)
            }
            PackageInstaller.STATUS_SUCCESS -> AppLog.i("Update $version installed")
            else -> {
                val why = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "status $status"
                AppLog.e("Update $version was not installed: $why")
                Updater.failed(if (status == PackageInstaller.STATUS_FAILURE_ABORTED) "The update was cancelled." else "Android didn't install the update ($why).")
            }
        }
    }

    private fun notify(ctx: Context, title: String, text: String, tap: PendingIntent) = runCatching {
        val n = Notices.builder(ctx, RedGrabApp.CH_DONE)
            .setContentTitle(title).setContentText(text).setContentIntent(tap).setAutoCancel(true).build()
        ctx.getSystemService(NotificationManager::class.java).notify(NOTIFICATION, n)
    }
}

/** Gathers what's needed to look into a problem: the app and phone details, a few settings, and the recent log. */
object BugReport {

    /** Things that must never leave the phone: anything that looks like a sign-in token. */
    private val TOKEN = Regex("(eyJ[A-Za-z0-9_-]{10,}\\.[A-Za-z0-9_-]{10,}\\.[A-Za-z0-9_-]{5,}|gh[pousr]_[A-Za-z0-9]{20,}|github_pat_[A-Za-z0-9_]{20,})")

    fun redact(text: String) = TOKEN.replace(text, "[token removed]")

    /** The app, phone and state details as "name: value" lines. */
    fun details(ctx: Context): String {
        val lib = ctx.library
        val prefs = lib.prefs
        val s = lib.state.value
        val webView = runCatching { android.webkit.WebView.getCurrentWebViewPackage()?.let { "${it.packageName} ${it.versionName}" } }.getOrNull()
        val dm = ctx.resources.displayMetrics
        return listOf(
            "App" to "RedGrab ${BuildConfig.VERSION_NAME} (build ${BuildConfig.VERSION_CODE}, ${BuildConfig.GIT_SHA}, ${BuildConfig.BUILD_DATE})",
            "Phone" to "${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE})",
            "Android" to "${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT}), security patch ${Build.VERSION.SECURITY_PATCH}",
            "Build" to Build.DISPLAY,
            "WebView" to (webView ?: "unknown"),
            "Screen" to "${dm.widthPixels}×${dm.heightPixels}, density ${dm.density}",
            "Language" to java.util.Locale.getDefault().toLanguageTag(),
            "RedGifs" to Session.describe(prefs),
            "Reddit" to (if (prefs.redditRefresh != null) "signed in" else if (prefs.redditClientId != null) "client ID set" else "not set up"),
            "Save folder" to (if (prefs.folderUri != null) "chosen" else "none"),
            "Library" to "${s.originals.size} clips, ${s.savedCount} saved, ${s.failed.size} failed, ${s.dead.size} removed, ${s.collections.size} collections, ${s.likedTotal} liked",
            "Online source" to (prefs.onlineSource ?: "RedGifs"),
            "Layout" to "${if (prefs.wall) "wall" else "grid"}, ${prefs.columns} per row; previews ${if (prefs.previews) "on" else "off"}" +
                " (${if (prefs.previewCount == 0) "all" else prefs.previewCount.toString()}), unsaved ${if (prefs.previewUnsaved) "on" else "off"}, copies ${if (prefs.previewCopies) "on" else "off"} (${s.localPreviews.size}); fill screen ${if (prefs.playerFill) "on" else "off"}",
            "Auto-update" to (if (prefs.autoUpdate) "on" else "off"),
        ).joinToString("\n") { (k, v) -> "$k: $v" }
    }

    /** The whole report as text: what you wrote, the details, and the end of the Activity log. */
    fun build(ctx: Context, description: String, logChars: Int = 40_000): String {
        val log = AppLog.read().takeLast(logChars)
        return redact(buildString {
            if (description.isNotBlank()) append(description.trim()).append("\n\n")
            append("### Details\n```\n").append(details(ctx)).append("\n```\n\n")
            append("### Activity log (most recent last)\n```\n").append(log.ifBlank { "(empty)" }).append("\n```\n")
        })
    }
}
