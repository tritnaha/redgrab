package app.redgrab

import android.content.Context
import androidx.core.app.NotificationCompat

/**
 * The wording of every notification the app posts. Nothing here names a site, a clip, a creator,
 * a tag or a title: a notification can be read by someone looking at the phone, so it only says
 * that the app is busy or needs you. They are also kept off the lock screen altogether.
 *
 * The details (which clip, which site, why) are shown inside the app.
 */
object Notices {
    const val SAVING = "Saving"
    const val CHECK_TITLE = "RedGrab paused"
    const val CHECK = "A quick check is needed. Tap to continue."
    const val SIGN_IN = "Sign in again to keep syncing."
    const val SYNC_CHECK = "A quick check is needed before syncing."

    /** "3 of 10 · 2.1 MB/s · 1 min left" */
    fun progress(done: Int, queued: Int, speed: String?, eta: String?): String =
        listOfNotNull("$done of $queued", speed, eta?.let { "$it left" }).joinToString(" · ")

    /** What the "finished" notification says after a download run. */
    fun done(saved: Int, failed: Int, needsCheck: Boolean, signedOut: Boolean): String = when {
        needsCheck -> CHECK
        signedOut -> "Paused. Sign in again to continue."
        failed > 0 -> "Saved $saved, $failed failed."
        else -> "Saved $saved."
    }

    /** A notification builder with the app's icon that never shows on the lock screen. */
    fun builder(ctx: Context, channel: String): NotificationCompat.Builder =
        NotificationCompat.Builder(ctx, channel).setSmallIcon(R.drawable.ic_notify).setVisibility(NotificationCompat.VISIBILITY_SECRET)
}
