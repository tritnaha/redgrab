package app.redgrab

import android.content.Context
import android.content.pm.ServiceInfo
import android.net.Uri
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Moving clips into the app, back out to a folder, or exporting copies. These can take a long
 * while, so they run as background work with a notification rather than only while the app is on
 * screen: closing the app doesn't stop them, and one that Android did stop (or that a restart cut
 * short) starts again by itself. Each is safe to start again: what is already done is recognised.
 */
object StorageJob {
    const val IN = "in"           // a folder into the app
    const val OUT = "out"         // everything out to a folder, which becomes the save folder
    const val EXPORT = "export"   // copies to a folder; nothing changes in the app

    /** "Moving 12 of 340" while one runs; null otherwise. */
    val status = MutableStateFlow<String?>(null)
    /** What the last one ended with, for the app to show once. */
    val finished = MutableStateFlow<String?>(null)

    private const val NAME = "storage"

    fun start(ctx: Context, kind: String, folder: Uri, label: String = "") {
        ctx.library.prefs.storageJob = "$kind\n$folder\n$label"
        status.value = "Starting"
        enqueue(ctx)
    }

    /** Called when the app starts: carries on with one that didn't finish. */
    fun resume(ctx: Context) { if (ctx.library.prefs.storageJob != null) { status.value = "Continuing"; enqueue(ctx) } }

    private fun enqueue(ctx: Context) {
        val req = OneTimeWorkRequestBuilder<StorageWorker>().setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST).build()
        WorkManager.getInstance(ctx).enqueueUniqueWork(NAME, ExistingWorkPolicy.KEEP, req)
    }
}

class StorageWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    private fun info(text: String): ForegroundInfo {
        // Counts only, as with every notification (see Notices).
        val n = Notices.builder(applicationContext, RedGrabApp.CH_PROGRESS).setContentTitle(Notices.MOVING).setContentText(text)
            .setOngoing(true).setOnlyAlertOnce(true).setSilent(true).build()
        return ForegroundInfo(9, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    }

    override suspend fun getForegroundInfo(): ForegroundInfo = info("")

    override suspend fun doWork(): Result {
        val lib = applicationContext.library
        val prefs = lib.prefs
        val parts = prefs.storageJob?.split('\n') ?: return Result.success().also { StorageJob.status.value = null }
        val kind = parts[0]
        val folder = runCatching { Uri.parse(parts[1]) }.getOrNull() ?: return done("")
        val label = parts.getOrElse(2) { "" }
        runCatching { setForeground(info("")) }
        var shown = 0L
        val progress: (String) -> Unit = { p ->
            StorageJob.status.value = (if (kind == StorageJob.EXPORT) "Exporting " else "Moving ") + p
            val now = android.os.SystemClock.elapsedRealtime()
            if (now - shown > 1500) { shown = now; runCatching { setForegroundAsync(info(p)) } }
        }
        val message = try {
            when (kind) {
                StorageJob.IN -> {
                    val (moved, failed) = lib.moveTreeIntoApp(folder, progress)
                    val wasSaveFolder = prefs.folderUri == folder
                    when {
                        failed > 0 -> "%,d moved, %,d couldn't be (see the log). Nothing is lost; run it again.".format(moved, failed)
                        // Nothing found although clips are known as saved: this isn't the folder they are in.
                        moved == 0 && wasSaveFolder && lib.state.value.saved.isNotEmpty() && PrivateDocs.dir(applicationContext).list().isNullOrEmpty() ->
                            "No files were found in the save folder. Nothing was changed."
                        else -> {
                            // From here on the app uses its own storage; only then is the old folder tidied.
                            prefs.folderUri = PrivateDocs.TREE; prefs.folderLabel = "Inside the app"
                            val empty = lib.tidyMovedFolder(folder)
                            "%,d file(s) moved into the app.".format(moved) + if (empty) "" else " Some files are still in the old folder."
                        }
                    }
                }
                StorageJob.OUT -> {
                    // Verified copies first (as an export), then the folder becomes the save folder, then the copies inside go.
                    val (copied, failed) = lib.exportTo(folder, progress, marker = true)
                    if (failed > 0) "%,d copied, %,d couldn't be (see the log). Nothing was removed; run it again.".format(copied, failed)
                    else {
                        prefs.folderUri = folder; prefs.folderLabel = label.ifBlank { "Folder" }
                        PrivateDocs.dir(applicationContext).listFiles()?.forEach { it.deleteRecursively() }
                        "Clips are in the folder now. Other apps can see them there."
                    }
                }
                else -> {
                    val (copied, failed) = lib.exportTo(folder, progress)
                    prefs.lastExport = "$label · ${java.time.LocalDate.now()}"
                    "%,d file(s) exported".format(copied) + if (failed > 0) ", %,d failed (see the log).".format(failed) else ". They are ordinary files now: other apps can open them."
                }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            // Stopped by Android: the marker stays, so it carries on later.
            throw e
        } catch (e: Exception) {
            AppLog.e("Storage work ($kind) failed", e)
            "Couldn't finish: ${e.message}. Nothing is lost."
        }
        // An export or bring-in folder is let go of; a folder that became the save folder is kept.
        if (prefs.folderUri != folder) runCatching {
            applicationContext.contentResolver.releasePersistableUriPermission(folder,
                android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        }
        return done(message)
    }

    private fun done(message: String): Result {
        applicationContext.library.prefs.storageJob = null
        StorageJob.status.value = null
        if (message.isNotEmpty()) { AppLog.i(message); StorageJob.finished.value = message }
        return Result.success()
    }
}
