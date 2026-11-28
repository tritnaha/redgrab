package app.redgrab

import android.app.Activity
import android.app.Application
import android.app.KeyguardManager
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.os.CancellationSignal
import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Optional lock on the app itself. When it is on, the app's screens stay covered until you unlock
 * with whatever the phone is set up with: fingerprint, face, PIN, pattern or password. The check
 * is done by Android's own unlock prompt; the app never sees a fingerprint or a PIN, only "yes"
 * or "no".
 *
 * The app locks when it starts, and again after it has been out of sight for the chosen time.
 * "Out of sight" means none of its screens is showing: moving between its own screens doesn't
 * count, switching to another app or turning the screen off does.
 *
 * It guards the screens, not the files: clips in the save folder are ordinary files, and
 * background sync and downloads carry on while the app is locked.
 */
object AppLock {
    /** True while the app's screens must stay covered. */
    var locked by mutableStateOf(false)
        private set

    /** Android's prompt is up; the lock screen then doesn't offer its own button. */
    var asking by mutableStateOf(false)
        private set

    private var visible = 0
    private var shownBefore = false  // false until the first screen of this run has come up
    private var hiddenAt = 0L        // elapsedRealtime when the last screen went out of sight
    private var rebuilding = false   // the screen is only being rebuilt (the phone was turned)
    private var autoAsked = false
    private var deferred = false     // a lock that fell due while a sign-in page was in front
    private var unlockedAt = -10_000L
    private var signal: CancellationSignal? = null

    /** Whether the phone has a screen lock at all; without one there is nothing to unlock with. */
    fun available(ctx: Context): Boolean = ctx.getSystemService(KeyguardManager::class.java)?.isDeviceSecure == true

    /**
     * Whether coming back into view should lock. [hiddenForMs] = how long the app was out of sight,
     * negative when it has just started (always locks). [sinceUnlockMs]: a moment after unlocking
     * nothing locks again, because on some phones the unlock screen itself puts the app out of sight.
     */
    fun shouldLock(enabled: Boolean, hiddenForMs: Long, afterSec: Int, sinceUnlockMs: Long): Boolean = when {
        !enabled -> false
        hiddenForMs < 0 -> true
        sinceUnlockMs in 0 until 3_000 -> false
        else -> hiddenForMs >= afterSec * 1000L
    }

    /** Watches every screen of the app coming into and going out of view. Call once from the application. */
    fun install(app: Application, prefs: Prefs) {
        // A fresh start is locked from the very first frame, not from the moment the screen reports itself shown.
        if (prefs.appLock && available(app)) locked = true
        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityStarted(a: Activity) {
                val signIn = a is LoginActivity || a is RedditLoginActivity || a is VerifyActivity
                // A lock that was due while a sign-in page was open applies now that the app itself is back.
                if (deferred && !signIn) { deferred = false; if (!locked) { locked = true; autoAsked = false; AppLog.i("App locked") } }
                if (visible++ > 0) return
                if (rebuilding) { rebuilding = false; return }
                val now = SystemClock.elapsedRealtime()
                val on = prefs.appLock && available(a)
                if (!locked && shouldLock(on, if (shownBefore) now - hiddenAt else -1, prefs.appLockAfterSec, now - unlockedAt)) {
                    // Signing in means leaving for a moment (a code by e-mail, a password manager). A sign-in page shows
                    // none of your clips, so it stays open; the lock comes down when you return to the app.
                    if (signIn) deferred = true
                    else { locked = true; autoAsked = false; AppLog.i("App locked") }
                }
                shownBefore = true
            }
            override fun onActivityStopped(a: Activity) {
                visible = (visible - 1).coerceAtLeast(0)
                if (visible > 0) return
                // Turning the phone rebuilds the screen; that isn't leaving.
                if (a.isChangingConfigurations) rebuilding = true else hiddenAt = SystemClock.elapsedRealtime()
            }
            override fun onActivityCreated(a: Activity, b: Bundle?) {
                // While the lock is on, the app's content isn't shown in the list of recent apps either.
                if (prefs.appLock) hideFromRecents(a, true)
            }
            override fun onActivityResumed(a: Activity) {}
            override fun onActivityPaused(a: Activity) {}
            override fun onActivitySaveInstanceState(a: Activity, b: Bundle) {}
            override fun onActivityDestroyed(a: Activity) {}
        })
    }

    /** With the lock on, the app's screens can't be captured: no screenshots, no screen recording or casting, no picture in the recent-apps list. */
    fun hideFromRecents(a: Activity, hide: Boolean) {
        a.setRecentsScreenshotEnabled(!hide)
        if (hide) a.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
        else a.window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
    }

    /** Turned on in Settings: locks from the next time the app comes back; off: nothing stays covered. */
    fun settingChanged(a: Activity, on: Boolean) {
        hideFromRecents(a, on)
        if (!on) locked = false
    }

    /** True once per lock: the prompt comes up by itself the first time, after that only from the Unlock button. */
    fun takeAutoAsk(): Boolean = (locked && !autoAsked && !asking).also { if (it) autoAsked = true }

    private fun unlocked() {
        unlockedAt = SystemClock.elapsedRealtime()
        if (locked) AppLog.i("App unlocked")
        locked = false
    }

    /**
     * Shows Android's unlock prompt. [onResult] gets true when you proved it's you. Used both to
     * unlock and to confirm switching the lock on or off.
     */
    fun ask(a: Activity, title: String, onResult: (Boolean) -> Unit = {}) {
        if (asking) return
        if (!available(a)) { unlocked(); onResult(true); return } // the screen lock was removed: nothing to check against
        pending = onResult
        asking = true
        try {
            val b = android.hardware.biometrics.BiometricPrompt.Builder(a).setTitle(title)
            b.setAllowedAuthenticators(
                android.hardware.biometrics.BiometricManager.Authenticators.BIOMETRIC_WEAK or
                    android.hardware.biometrics.BiometricManager.Authenticators.DEVICE_CREDENTIAL)
            b.setConfirmationRequired(false)
            val s = CancellationSignal().also { signal = it }
            b.build().authenticate(s, a.mainExecutor, object : android.hardware.biometrics.BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: android.hardware.biometrics.BiometricPrompt.AuthenticationResult?) = done(true)
                // Cancelled, too many tries, and so on: stay locked; the lock screen offers another go.
                override fun onAuthenticationError(errorCode: Int, errString: CharSequence?) = done(false)
            })
        } catch (e: Exception) {
            AppLog.e("The unlock prompt couldn't be shown: ${e.message}")
            done(false)
        }
    }

    private var pending: ((Boolean) -> Unit)? = null

    private fun done(ok: Boolean) {
        asking = false
        signal = null
        val cb = pending
        pending = null
        if (ok) unlocked()
        cb?.invoke(ok)
    }
}
