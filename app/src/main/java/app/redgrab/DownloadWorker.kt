package app.redgrab

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.SystemClock
import android.provider.DocumentsContract
import androidx.core.app.NotificationCompat
import androidx.documentfile.provider.DocumentFile
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** Downloads the queued clip IDs into the save folder, in the background. */
/** The site says the clip no longer exists (deleted by its uploader or the site). */
class GoneException(site: String) : java.io.IOException("No longer on $site")

class DownloadWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    companion object {
        private const val NAME = "download"
        private const val NOTIF_ID = 1
        private const val NOTIF_DONE_ID = 2

        /** True from the moment a run is requested until its worker finishes. */
        @Volatile var active = false
            private set

        /** True only while a worker is really executing (not while it waits for a connection). */
        @Volatile private var executing = false

        /** True when the stop came from the Pause button rather than from Android (connection lost). */
        @Volatile private var userPaused = false

        /** The partial file being written right now; housekeeping must leave it alone. */
        @Volatile var writing: String? = null
            private set

        private fun networkOk(ctx: Context, wifiOnly: Boolean): Boolean {
            val cm = ctx.getSystemService(android.net.ConnectivityManager::class.java) ?: return true
            val caps = cm.getNetworkCapabilities(cm.activeNetwork ?: return false) ?: return false
            return caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                (!wifiOnly || caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_NOT_METERED))
        }

        /**
         * [items]: clip IDs (HD, falling back to SD) or "<id> HD" / "<id> SD" for a specific quality.
         * Returns false (and changes nothing) if a run is executing. [wifiOnly]: wait for an
         * unmetered network (background sync with "Wi-Fi only"). [chain]: run after the current one.
         */
        @Synchronized
        fun start(ctx: Context, items: List<String>, wifiOnly: Boolean = false, chain: Boolean = false): Boolean {
            if (active && executing && !chain) return false
            val lib = ctx.library
            // A run that hasn't started yet (waiting for a connection or for Wi-Fi) keeps its clips; new ones go behind them.
            val pendingRun = active && !executing && !chain
            val queue = if (lib.prefs.downloadWaiting || pendingRun) (lib.readQueue() + items).distinct() else items
            active = true
            userPaused = false
            lib.setQueue(queue)
            val net = if (wifiOnly) androidx.work.NetworkType.UNMETERED else androidx.work.NetworkType.CONNECTED
            val req = OneTimeWorkRequestBuilder<DownloadWorker>()
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                // Only run with a connection; if it drops, Android restarts the run when it's back.
                .setConstraints(androidx.work.Constraints.Builder().setRequiredNetworkType(net).build())
                .setBackoffCriteria(androidx.work.BackoffPolicy.LINEAR, 30, java.util.concurrent.TimeUnit.SECONDS)
                .setInputData(androidx.work.workDataOf("wifi" to wifiOnly))
                .build()
            val policy = when {
                chain -> ExistingWorkPolicy.APPEND_OR_REPLACE
                // A request from you replaces one that is still waiting (e.g. for Wi-Fi), keeping its clips.
                pendingRun -> ExistingWorkPolicy.REPLACE
                // KEEP: if Android already restored a queued run after a restart, it simply picks up this queue.
                else -> ExistingWorkPolicy.KEEP
            }
            WorkManager.getInstance(ctx).enqueueUniqueWork(NAME, policy, req)
            if (!chain && !networkOk(ctx, wifiOnly)) {
                lib.updateDownload {
                    DownloadProgress(waiting = true, left = queue.size,
                        message = (if (wifiOnly) "Waiting for Wi-Fi. " else "Waiting for a connection. ") + "${queue.size} clip(s) queued; starts automatically.")
                }
            }
            AppLog.i("Download queued: ${items.size} clips" + if (wifiOnly) " (Wi-Fi only)" else "")
            return true
        }

        /** Pauses: the run stops after the current chunk; what's left stays queued for "Resume". */
        fun stop(ctx: Context) {
            userPaused = true
            WorkManager.getInstance(ctx).cancelUniqueWork(NAME)
            ctx.library.prefs.downloadWaiting = false
            if (!executing) {
                // Nothing was running (it was waiting for a connection), so no worker will report back: do it here.
                active = false
                val left = ctx.library.readQueue().size
                ctx.library.updateDownload {
                    if (left > 0) DownloadProgress(paused = true, left = left, message = "Paused. $left clip(s) left.") else DownloadProgress()
                }
            }
            AppLog.i("Download paused by user")
        }

        /** Continues a paused or interrupted run from its saved queue. */
        fun resume(ctx: Context): Boolean {
            val left = ctx.library.readQueue()
            return left.isNotEmpty() && start(ctx, left)
        }

        /** Drops a paused run's queue. */
        fun clear(ctx: Context) {
            ctx.library.clearQueue()
            ctx.library.prefs.downloadWaiting = false
            ctx.library.updateDownload { DownloadProgress() }
        }

        /** True when the failure is "no usable connection", not something wrong with the clip. */
        fun isNetworkError(e: Throwable): Boolean =
            e is java.net.UnknownHostException || e is java.net.ConnectException || e is java.net.SocketException ||
                e is java.net.SocketTimeoutException || e is javax.net.ssl.SSLException ||
                (e is java.io.IOException && e.message?.contains("unexpected end of stream", true) == true)

        /** Plain-language reason shown next to a failed clip. [site]: where the clip is from. */
        fun reasonFor(e: Throwable, site: String = "RedGifs"): String = when {
            e is GoneException -> e.message ?: "No longer on $site"
            e is HttpException && e.code == 404 -> "Not found on $site (it may have been removed)"
            e is HttpException && e.code == 410 -> "Removed from $site"
            e is HttpException && e.code == 403 -> "$site refused the download (HTTP 403)"
            e is HttpException && e.code == 429 -> "$site is rate limiting; try later"
            e is HttpException -> "$site error (HTTP ${e.code})"
            e is HlsSaver.Unsupported -> e.message ?: "The stream can't be saved"
            e is javax.net.ssl.SSLHandshakeException || e is javax.net.ssl.SSLPeerUnverifiedException ->
                "Secure connection refused (this network may be blocking or inspecting it)"
            isNetworkError(e) -> "Connection lost"
            e.message?.contains("ENOSPC") == true || e.message?.contains("No space", true) == true -> "The phone's storage is full"
            e.message?.contains("playable", true) == true -> "The file wasn't a playable video"
            e.message?.contains("incomplete", true) == true -> "The download was cut short"
            e.message?.contains("folder", true) == true || e.message?.contains("create file", true) == true -> "Couldn't write to the save folder"
            else -> e.message?.take(80) ?: e.javaClass.simpleName
        }
    }

    private val lib = ctx.library
    private var lastNotify = 0L
    private fun isGone(code: Int) = code == 404 || code == 410

    override suspend fun getForegroundInfo(): ForegroundInfo = foreground(lib.download.value)

    /** Clips this run tried, so a follow-up run doesn't immediately retry its failures. */
    private var attempted: Set<String> = emptySet()

    override suspend fun doWork(): Result {
        executing = true
        val result = try { run() } finally { executing = false; active = false; writing = null }
        if (result == Result.success()) {
            // Collections switched to "sync" while this was running: save their clips next.
            val more = lib.pendingWatched().filter { it !in attempted }
            if (more.isNotEmpty()) {
                AppLog.i("${more.size} more clip(s) from synced collections; continuing")
                start(applicationContext, more, wifiOnly = inputData.getBoolean("wifi", false), chain = true)
            }
        }
        return result
    }

    private suspend fun run(): Result = withContext(Dispatchers.IO) {
        active = true // also covers a run Android restarted after the app was killed
        val prefs = lib.prefs
        val folder = lib.folder()
        val token = prefs.token
        val ua = applicationContext.userAgent
        // The RedGifs sign-in is only needed for RedGifs clips; other sites' clips download without it.
        val (queuedRedGifs, queuedOther) = lib.readQueue().partition { OnlineSource.of(it.substringBefore(' ')) == OnlineSource.RedGifs }
        val api = if (token != null && prefs.userAgent != null) RedGifsApi(token, ua) else null
        if (folder == null || (api == null && queuedOther.isEmpty())) {
            val why = if (folder == null) "Save folder is missing or not writable. Choose it again in Settings." else "Not signed in."
            AppLog.e("Download not started: $why")
            lib.updateDownload { DownloadProgress(message = why) }
            return@withContext Result.failure()
        }
        if (api == null && queuedRedGifs.isNotEmpty()) AppLog.w("Not signed in to RedGifs: its clips in this run are skipped")
        // Leftovers of a stream download that was cut off (only one run works at a time).
        java.io.File(applicationContext.cacheDir, "hls").deleteRecursively()
        val http = api?.http ?: RedGifsApi.clientFor(ua)
        val byId = lib.state.value.clips.associateBy { it.id }
        // HD by default; SD only when HD doesn't exist or the queue entry asks for SD.
        val preferred = Quality.HD
        // Resolve each queue entry to (clip, quality), skipping qualities that are already saved.
        val todo = lib.readQueue().mapNotNull { line ->
            val parts = line.split(" ")
            val clip = byId[parts[0]]?.takeIf { !it.isCopy && !it.imported && !it.isImage } ?: return@mapNotNull null
            if (api == null && OnlineSource.of(clip.id) == OnlineSource.RedGifs) return@mapNotNull null
            val asked = parts.getOrNull(1)?.let { runCatching { Quality.valueOf(it) }.getOrNull() }
            val have = lib.state.value.qualities[clip.id] ?: emptySet()
            if (asked == null && have.isNotEmpty()) return@mapNotNull null // already saved in some quality
            val q = asked ?: preferred
            // Fall back to the other quality if RedGifs has no file for this one.
            val actual = if (clip.url(q) != null) q else if (q == Quality.HD) Quality.SD else Quality.HD
            if (actual in have || clip.url(actual) == null) null else clip to actual
        }
        attempted = todo.mapTo(HashSet()) { it.first.id }
        AppLog.i("Download started: ${todo.size} clips")

        var progress = DownloadProgress(running = true, queued = todo.size, message = "Starting…")
        lib.updateDownload { progress }
        runCatching { setForeground(foreground(progress)) }

        var runBytes = 0L
        var finishedOk = 0
        var gone = 0
        var speed = 0.0
        var windowStart = SystemClock.elapsedRealtime()
        var windowBytes = 0L
        var stoppedForAuth = false
        var needsCheck: String? = null
        var lostNetwork = false
        prefs.downloadWaiting = false

        try {
            for ((i, item) in todo.withIndex()) {
                val (clip, quality) = item
                currentCoroutineContext().ensureActive()
                fileHave = 0; fileTotal = -1
                progress = progress.copy(currentId = clip.id, currentBytes = 0, currentTotal = -1, message = "Saving clip ${i + 1} of ${todo.size}")
                publish(progress, force = true)
                try {
                    saveWithRetry(http, api, folder, clip, quality) { n ->
                        fileHave += n
                        progress = progress.copy(currentBytes = fileHave, currentTotal = fileTotal)
                        runBytes += n
                        windowBytes += n
                        val now = SystemClock.elapsedRealtime()
                        val dt = now - windowStart
                        if (dt >= 1000) {
                            val inst = windowBytes * 1000.0 / dt
                            speed = if (speed == 0.0) inst else speed * 0.7 + inst * 0.3
                            windowStart = now
                            windowBytes = 0
                        }
                        // Time left = clips left × average clip size so far ÷ speed.
                        val avg = if (finishedOk > 0) runBytes.toDouble() / finishedOk else 0.0
                        val eta = if (avg > 0 && speed > 0) ((todo.size - i) * avg / speed).toLong() else -1
                        progress = progress.copy(bytesPerSec = speed.toLong(), etaSec = eta)
                        publish(progress)
                    }
                    lib.markSaved(clip.id, quality)
                    finishedOk++
                } catch (e: CancellationException) {
                    throw e
                } catch (e: AuthException) {
                    stoppedForAuth = true
                    throw e
                } catch (e: VerificationRequiredException) {
                    needsCheck = e.subject
                    throw e
                } catch (e: Exception) {
                    // No connection at all is not this clip's fault: stop here and wait for the network.
                    if (isNetworkError(e) && !online()) {
                        lostNetwork = true
                        AppLog.w("Connection lost during ${clip.id}; the run will continue when it's back")
                        break
                    }
                    if (e is GoneException) {
                        // Deleted on the site: remembered as such, never retried, and not reported as a failed download.
                        AppLog.w("${clip.id}: ${e.message}; marked as removed and not tried again")
                        lib.markFailed(clip.id, reasonFor(e), dead = true)
                        gone++
                    } else {
                        AppLog.e("Failed to save ${clip.id}: ${e.message}", e.takeUnless { it is HttpException || isNetworkError(it) })
                        lib.markFailed(clip.id, reasonFor(e, OnlineSource.of(clip.id).label))
                        progress = progress.copy(failed = progress.failed + 1)
                    }
                }
                progress = progress.copy(done = i + 1)
            }
        } catch (e: AuthException) {
            AppLog.e("Download stopped: signed out or session expired")
        } catch (e: VerificationRequiredException) {
            AppLog.w("Download paused: RedGifs asks for a human check")
        } catch (e: CancellationException) {
            // Paused by you (or stopped by Android): remember what's left so "Resume" continues from here.
            val left = todo.drop(progress.done).map { (c, q) -> "${c.id} ${q.name}" }
            lib.setQueue(left)
            if (userPaused || left.isEmpty()) {
                lib.updateDownload {
                    progress.copy(running = false, paused = left.isNotEmpty(), left = left.size, currentId = null, etaSec = -1,
                        message = "Paused. ${progress.done - progress.failed} saved, ${left.size} left.")
                }
            } else {
                // Android stopped the run because the connection went away; it starts it again when it's back.
                prefs.downloadWaiting = true
                AppLog.w("Connection lost; ${left.size} clip(s) left. The run continues when it's back")
                lib.updateDownload {
                    progress.copy(running = false, waiting = true, left = left.size, currentId = null, etaSec = -1,
                        message = "Waiting for a connection. ${progress.done - progress.failed} saved, ${left.size} left; continues automatically.")
                }
            }
            throw e
        }

        if (lostNetwork) {
            val left = todo.drop(progress.done).map { (c, q) -> "${c.id} ${q.name}" }
            lib.setQueue(left)
            prefs.downloadWaiting = true
            lib.updateDownload {
                progress.copy(running = false, waiting = true, left = left.size, currentId = null, etaSec = -1,
                    message = "Waiting for a connection. ${left.size} clip(s) left; continues automatically.")
            }
            return@withContext Result.retry() // WorkManager runs it again once a network is available
        }

        // Keep what's left in the queue so "Continue" picks up exactly where this stopped.
        if (stoppedForAuth || needsCheck != null) {
            val left = todo.drop(progress.done).map { (c, q) -> "${c.id} ${q.name}" }
            lib.setQueue(left)
        } else {
            lib.clearQueue() // finished: nothing left to resume
        }
        needsCheck?.let {
            lib.prefs.pendingVerification = it
            lib.prefs.resumeAfterVerify = true
            notifyVerify()
        }
        val msg = when {
            needsCheck != null -> "Paused: RedGifs wants a quick human check. Tap Verify to continue."
            stoppedForAuth -> "Session expired. Sign in again, then continue."
            progress.failed > 0 -> "Saved $finishedOk clips, ${progress.failed} failed. Open the Failed filter to see why and retry."
            else -> "Saved $finishedOk clips."
        } + if (gone > 0 && needsCheck == null && !stoppedForAuth) " $gone no longer exist." else ""
        AppLog.i("Download finished: $msg (${Format.size(runBytes)})")
        lib.updateDownload { progress.copy(running = false, currentId = null, etaSec = -1, message = msg) }
        // A run that only found deleted clips has nothing worth a notification.
        if (finishedOk > 0 || progress.failed > 0 || stoppedForAuth || needsCheck != null || gone == 0)
            notifyDone(Notices.done(finishedOk, progress.failed, needsCheck != null, stoppedForAuth))
        if (stoppedForAuth || needsCheck != null) Result.failure() else Result.success()
    }

    /**
     * Tries a clip up to three times when the network drops or the server hiccups (waiting a
     * little longer each time). Thanks to the `.part` file each retry continues where the last
     * one stopped. Sign-in problems, human checks and "not found" are not retried.
     */
    /** Bytes of the current clip on the phone and its full size (-1 = unknown, e.g. a stream), for the progress shown on its Save button. */
    private var fileHave = 0L
    private var fileTotal = -1L

    private suspend fun saveWithRetry(http: okhttp3.OkHttpClient, api: RedGifsApi?, folder: DocumentFile, clip: Clip, q: Quality, onBytes: suspend (Int) -> Unit) {
        var attempt = 0
        writing = "${clip.id}${q.suffix}.part"
        while (true) {
            try {
                save(http, api, folder, clip, q, onBytes)
                return
            } catch (e: java.io.IOException) {
                val permanent = e is AuthException || e is VerificationRequiredException || e is HlsSaver.Unsupported || e is GoneException ||
                    (e is HttpException && e.code in 400..499 && e.code != 408 && e.code != 429)
                if (permanent || attempt >= 2) {
                    // Nothing was downloaded at all: don't leave an empty partial file lying around.
                    lib.fileUri("${clip.id}${q.suffix}.part")?.takeIf(lib::exists)?.let { part ->
                        if ((DocumentFile.fromSingleUri(applicationContext, part)?.length() ?: 1L) == 0L) {
                            runCatching { DocumentsContract.deleteDocument(applicationContext.contentResolver, part) }
                            lib.setPartial(clip.id, false)
                        }
                    }
                    throw e
                }
                attempt++
                AppLog.w("Download of ${clip.id} interrupted (${e.message}); retry $attempt of 2")
                kotlinx.coroutines.delay(2500L * attempt)
            }
        }
    }

    /**
     * Downloads into `<id>-<q>.part`, resuming an earlier partial file with an HTTP Range request
     * when the server supports it, then renames it to `<id>-hd.mp4` / `<id>-sd.mp4`. Never
     * overwrites an existing file. Refreshes media URLs once on 403/404/410. A failed or stopped
     * download keeps its `.part` file so the next run continues from there. A stream (FikFap's
     * HLS) is fetched whole and repacked as MP4 first; it can't be resumed part-way.
     */
    private suspend fun save(http: okhttp3.OkHttpClient, api: RedGifsApi?, folder: DocumentFile, clip: Clip, q: Quality, onBytes: suspend (Int) -> Unit) {
        val target = q.fileName(clip.id)
        val partName = "${clip.id}${q.suffix}.part"
        val resolver = applicationContext.contentResolver
        // Already there (e.g. saved outside this run): keep it, never overwrite.
        if (lib.fileUri(target)?.let(lib::exists) == true) {
            lib.fileUri(partName)?.takeIf(lib::exists)?.let { runCatching { DocumentsContract.deleteDocument(resolver, it) } }
            lib.setPartial(clip.id, false)
            return
        }
        val existingUri = lib.fileUri(partName)?.takeIf(lib::exists)
        val partUri = existingUri ?: (folder.createFile("application/octet-stream", partName)?.uri ?: error("Could not create file in folder"))
        lib.setPartial(clip.id, true)
        var have = if (existingUri != null) DocumentFile.fromSingleUri(applicationContext, partUri)?.length() ?: 0L else 0L

        /** A fresh copy of the clip from its own site (new media addresses), where the site offers that. */
        val site = OnlineSource.of(clip.id)
        suspend fun fresh(): Clip? = try {
            when (site) {
                // The session may have run out during a long run; it is renewed out of sight when needed.
                OnlineSource.RedGifs -> if (api == null) null else withRedGifs(applicationContext) { it.clip(clip.id) }
                OnlineSource.FikFap -> FikFapApi(http).post(clip.id.removePrefix(OnlineSource.FikFap.prefix))
                else -> null
            }
        } catch (e: HttpException) {
            // The site itself says the clip is gone (not just its old file address).
            if (isGone(e.code)) throw GoneException(site.label) else throw e
        }?.also { lib.replaceClip(it) }
        /** A site that can't be asked again: a file that is "not found" there has been deleted. */
        suspend fun freshOrGone(e: HttpException): Clip {
            val c = fresh()
            if (c == null && isGone(e.code) && site != OnlineSource.RedGifs && site != OnlineSource.FikFap) throw GoneException(site.label)
            return c ?: throw e
        }

        val firstUrl = clip.url(q) ?: error("No ${q.label} video URL")
        if (HlsSaver.isHls(firstUrl)) {
            val work = java.io.File(applicationContext.cacheDir, "hls")
            val name = clip.id + q.suffix
            val mp4 = try {
                HlsSaver.save(http, firstUrl, work, name, onTotal = { fileTotal = it }, onBytes = onBytes)
            } catch (e: HttpException) {
                if (e.code !in setOf(401, 403, 404, 410)) throw e
                AppLog.w("Stream address for ${clip.id} expired (HTTP ${e.code}); fetching a fresh one")
                fileHave = 0
                HlsSaver.save(http, fresh()?.url(q) ?: throw e, work, name, onTotal = { fileTotal = it }, onBytes = onBytes)
            }
            try {
                resolver.openOutputStream(partUri, "wt")!!.use { out -> mp4.inputStream().use { it.copyTo(out, 256 * 1024) } }
            } finally { mp4.delete() }
            have = 0
        }
        fun open(url: String) = RedGifsApi.download(http, url, have)
        val response = if (HlsSaver.isHls(firstUrl)) null else try {
            open(firstUrl)
        } catch (e: HttpException) {
            when {
                // "Range not satisfiable": only trust the partial file if it's exactly the real size.
                e.code == 416 && have > 0 && e.rangeTotal == have -> null
                e.code == 416 && have > 0 -> {
                    AppLog.w("Partial file for ${clip.id} doesn't match the real size (${have} vs ${e.rangeTotal}); starting over")
                    have = 0
                    open(firstUrl)
                }
                e.code in setOf(403, 404, 410) -> {
                    AppLog.w("Media URL for ${clip.id} expired (HTTP ${e.code}); fetching a fresh one")
                    open(freshOrGone(e).url(q) ?: throw e)
                }
                else -> throw e
            }
        }
        var expected = -1L // full size of the finished file, when the server tells us
        response?.use { r ->
            // Only append when the server really continues from our byte offset.
            val range = r.header("Content-Range")
            val rangeStart = range?.substringAfter("bytes ", "")?.substringBefore('-')?.toLongOrNull()
            val resumed = have > 0 && r.code == 206 && rangeStart == have
            expected = if (r.code == 206) range?.substringAfter('/', "")?.toLongOrNull() ?: -1
            else r.header("Content-Length")?.toLongOrNull() ?: -1
            if (have > 0 && r.code == 206 && !resumed) throw java.io.IOException("Server resumed at the wrong position; will retry from the start")
            // For the progress ring: where this file starts and how large it will be.
            fileHave = if (resumed) have else 0
            fileTotal = expected
            if (have > 0) AppLog.i(if (resumed) "Resuming ${clip.id} from ${Format.size(have)}" else "Server can't resume ${clip.id}; starting over")
            resolver.openOutputStream(partUri, if (resumed) "wa" else "wt")!!.use { out ->
                val input = r.body!!.byteStream()
                val buf = ByteArray(64 * 1024)
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    onBytes(n)
                }
            }
        }
        // A connection that drops early must not be saved as a finished clip.
        val written = DocumentFile.fromSingleUri(applicationContext, partUri)?.length() ?: -1
        if (expected > 0 && written >= 0 && written != expected) {
            if (written > expected) runCatching { DocumentsContract.deleteDocument(resolver, partUri) } // corrupt: start clean next time
            throw java.io.IOException("Download incomplete (${Format.size(written)} of ${Format.size(expected)}); it will continue next time")
        }
        // Rename through the documents provider (DocumentFile for a single URI can't rename).
        val targetExisted = lib.fileUri(target)?.let(lib::exists) == true
        val renamed = runCatching { DocumentsContract.renameDocument(resolver, partUri, target) }.getOrNull()
            ?: error("Could not rename the finished file")
        val finalName = DocumentFile.fromSingleUri(applicationContext, renamed)?.name
        if (finalName != null && !finalName.equals(target, ignoreCase = true)) {
            if (targetExisted) {
                // The target appeared meanwhile and the provider chose another name: keep the existing file.
                runCatching { DocumentsContract.deleteDocument(resolver, renamed) }
            } else {
                AppLog.w("Saved ${clip.id} as \"$finalName\" (the storage provider changed the name)")
            }
        }
        lib.setPartial(clip.id, false)

        // The finished file must really be a video; a corrupt one is removed so it gets fetched again.
        val saved = lib.fileUri(target)?.takeIf(lib::exists) ?: renamed
        if (!isPlayable(saved)) {
            runCatching { DocumentsContract.deleteDocument(resolver, saved) }
            throw java.io.IOException("The downloaded file isn't a playable video; removed it")
        }
        // Checksum + size for the file index (integrity, and spotting byte-identical duplicates).
        runCatching {
            val md = java.security.MessageDigest.getInstance("SHA-256")
            var size = 0L
            resolver.openInputStream(saved)!!.use { input ->
                val buf = ByteArray(256 * 1024)
                while (true) { val n = input.read(buf); if (n < 0) break; md.update(buf, 0, n); size += n }
            }
            lib.recordFile(target, size, md.digest().joinToString("") { "%02x".format(it) })
        }.onFailure { AppLog.w("Could not checksum $target: ${it.message}") }
        // A thumbnail kept on the phone, so the library looks right with no connection.
        lib.makeThumb(clip.id, saved)
        // Size and length, for sites that don't say (FikFap, Reddit GIFs).
        if (clip.width == 0 || clip.duration == 0.0) lib.learnFromFile(clip.id, saved)
    }

    private fun online(): Boolean {
        val cm = applicationContext.getSystemService(android.net.ConnectivityManager::class.java) ?: return true
        val caps = cm.getNetworkCapabilities(cm.activeNetwork ?: return false) ?: return false
        return caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    private fun isPlayable(uri: android.net.Uri): Boolean = runCatching {
        val r = android.media.MediaMetadataRetriever()
        try {
            r.setDataSource(applicationContext, uri)
            val hasVideo = r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO) == "yes"
            val dur = r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0
            hasVideo && dur > 0
        } finally { r.release() }
    }.getOrDefault(false)

    private fun notifyVerify() = runCatching {
        val i = Intent(applicationContext, VerifyActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val pi = PendingIntent.getActivity(applicationContext, 3, i, PendingIntent.FLAG_IMMUTABLE)
        val n = Notices.builder(applicationContext, RedGrabApp.CH_DONE)
            .setContentTitle(Notices.CHECK_TITLE)
            .setContentText(Notices.CHECK)
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()
        applicationContext.getSystemService(NotificationManager::class.java).notify(4, n)
    }

    private var lastPublish = 0L

    /** Publishes progress at most ~4 times a second (and the notification once a second) unless forced. */
    private suspend fun publish(p: DownloadProgress, force: Boolean = false) {
        val now = SystemClock.elapsedRealtime()
        if (!force && now - lastPublish < 250) return
        lastPublish = now
        lib.updateDownload { p }
        if (!force && now - lastNotify < 1000) return
        lastNotify = now
        runCatching { setForeground(foreground(p)) }
    }

    private fun openAppIntent(): PendingIntent = PendingIntent.getActivity(
        applicationContext, 0,
        Intent(applicationContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE,
    )

    private fun foreground(p: DownloadProgress): ForegroundInfo {
        // Counts and speed only: never which clip or which site (see Notices).
        val detail = Notices.progress(p.done, p.queued, Format.speed(p.bytesPerSec).takeIf { p.bytesPerSec > 0 }, Format.eta(p.etaSec).takeIf { p.etaSec >= 0 })
        val n = Notices.builder(applicationContext, RedGrabApp.CH_PROGRESS)
            .setContentTitle(Notices.SAVING)
            .setContentText(detail)
            .setContentIntent(openAppIntent())
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setProgress(p.queued.coerceAtLeast(1), p.done, p.queued == 0)
            .build()
        return if (Build.VERSION.SDK_INT >= 29) ForegroundInfo(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else ForegroundInfo(NOTIF_ID, n)
    }

    private fun notifyDone(msg: String) = runCatching {
        if (!lib.prefs.notifyDone) return@runCatching
        val n = Notices.builder(applicationContext, RedGrabApp.CH_DONE)
            .setContentTitle("RedGrab")
            .setContentText(msg)
            .setContentIntent(openAppIntent())
            .setAutoCancel(true)
            .build()
        applicationContext.getSystemService(NotificationManager::class.java).notify(NOTIF_DONE_ID, n)
    }
}

object Format {
    /** "961K", "1.2M": a large count in a few characters. */
    fun count(n: Long): String = when {
        n >= 10_000_000 -> "%dM".format(n / 1_000_000)
        n >= 1_000_000 -> "%.1fM".format(n / 1e6)
        n >= 10_000 -> "%dK".format(n / 1000)
        n >= 1000 -> "%.1fK".format(n / 1e3)
        else -> n.toString()
    }

    fun speed(bps: Long): String = when {
        bps >= 1_000_000 -> "%.1f MB/s".format(bps / 1_000_000.0)
        bps >= 1_000 -> "%.0f KB/s".format(bps / 1_000.0)
        else -> "$bps B/s"
    }

    fun size(b: Long): String = when {
        b >= 1_000_000_000 -> "%.2f GB".format(b / 1e9)
        b >= 1_000_000 -> "%.1f MB".format(b / 1e6)
        else -> "%.0f KB".format(b / 1e3)
    }

    fun eta(sec: Long): String = when {
        sec >= 3600 -> "%dh %02dm".format(sec / 3600, (sec % 3600) / 60)
        sec >= 60 -> "%dm %02ds".format(sec / 60, sec % 60)
        else -> "${sec}s"
    }

    fun duration(sec: Double): String {
        val s = sec.toInt()
        return "%d:%02d".format(s / 60, s % 60)
    }

    fun ago(epochMs: Long, now: Long = System.currentTimeMillis()): String {
        if (epochMs <= 0) return "never"
        val d = (now - epochMs) / 1000
        return when {
            d < 45 -> "just now"
            d < 3600 -> "${d / 60} min ago"
            d < 86400 -> "${d / 3600} h ago"
            else -> "${d / 86400} d ago"
        }
    }
}
