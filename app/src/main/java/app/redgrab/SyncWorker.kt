package app.redgrab

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/**
 * Background auto-sync: every few hours, re-reads your likes and collections and downloads
 * anything new in the collections you've marked to sync. Runs only with a connection (Wi-Fi
 * only if you say so). If the RedGifs session has expired or RedGifs wants a human check, it
 * can't fix that by itself: it tells you with a notification and waits.
 */
class SyncWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    companion object {
        private const val NAME = "auto-sync"

        /** Applies the current settings: schedules, reschedules or cancels the background check. */
        fun schedule(ctx: Context) {
            val prefs = ctx.library.prefs
            val wm = WorkManager.getInstance(ctx)
            val hours = prefs.autoSyncHours
            if (hours <= 0) { wm.cancelUniqueWork(NAME); return }
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(if (prefs.syncWifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
                .setRequiresBatteryNotLow(true)
                .build()
            val req = PeriodicWorkRequestBuilder<SyncWorker>(hours.toLong(), TimeUnit.HOURS)
                .setConstraints(constraints)
                .build()
            wm.enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.UPDATE, req)
        }
    }

    override suspend fun doWork(): Result {
        val lib = applicationContext.library
        if (!lib.prefs.signedIn || lib.state.value.watched.isEmpty()) return Result.success()
        // An expired session is renewed out of sight; only when that doesn't work are you asked to sign in.
        if (Session.state(lib.prefs) == Session.State.Expired && !SessionRenewer.renew(applicationContext)) {
            notify("Sign in again to keep syncing"); return Result.success()
        }
        AppLog.i("Background sync started")
        val result = lib.refresh()
        result.exceptionOrNull()?.let { e ->
            when (e) {
                is AuthException -> notify("Sign in again to keep syncing")
                is VerificationRequiredException -> { lib.prefs.pendingVerification = e.subject; notify("RedGifs wants a quick check before syncing") }
                else -> AppLog.w("Background sync failed: ${e.message}")
            }
            return Result.success() // try again at the next scheduled time
        }
        val pending = lib.pendingWatched()
        if (pending.isNotEmpty()) {
            AppLog.i("Background sync: ${pending.size} new clip(s) in synced collections")
            DownloadWorker.start(applicationContext, pending, wifiOnly = lib.prefs.syncWifiOnly)
        }
        return Result.success()
    }

    private fun notify(text: String) = runCatching {
        val pi = PendingIntent.getActivity(applicationContext, 5,
            Intent(applicationContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE)
        val n = NotificationCompat.Builder(applicationContext, RedGrabApp.CH_DONE)
            .setSmallIcon(R.drawable.ic_notify).setContentTitle("RedGrab").setContentText(text)
            .setContentIntent(pi).setAutoCancel(true).build()
        applicationContext.getSystemService(NotificationManager::class.java).notify(6, n)
    }
}
