package app.redgrab

import android.app.Application
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.launch

// Updates, problem reports and "What's new". Part of MainViewModel, kept in a file of its own.

fun MainViewModel.checkRepoVisibility() { viewModelScope.launch { repoPublic = Updater.repoIsPublic(getApplication()) } }

fun MainViewModel.changeAutoUpdate(v: Boolean) { prefs.autoUpdate = v; autoUpdate = v; UpdateWorker.schedule(getApplication()) }

fun MainViewModel.changeGithubToken(v: String) {
    val t = v.trim()
    prefs.githubToken = t.ifEmpty { null }
    githubToken = t
    AppLog.i(if (t.isEmpty()) "GitHub token removed" else "GitHub token set")
    if (t.isNotEmpty()) checkForUpdate()
}

fun MainViewModel.checkForUpdate() { viewModelScope.launch { Updater.check(getApplication()) } }

/** Downloads the newer release (if that isn't done yet) and hands it to Android's installer. */
fun MainViewModel.installUpdate() {
    viewModelScope.launch {
        val app = getApplication<Application>()
        val release = when (val s = Updater.state.value) {
            is Updater.State.Available -> s.release
            is Updater.State.Ready -> s.release
            else -> Updater.check(app)
        } ?: return@launch
        if (Updater.download(app, release)) Updater.install(app, release)
    }
}

/**
 * On opening the app: look for a newer release (at most every six hours) and fetch it. Once it
 * is on the phone the main screen offers to install it (installing restarts the app, so that is
 * never done under your hands).
 */
internal fun MainViewModel.lookForUpdate() {
    if (!prefs.autoUpdate || !isOnline() || System.currentTimeMillis() - prefs.lastUpdateCheck < 3_600_000L) return
    viewModelScope.launch { UpdateWorker.updateNow(getApplication(), installIfIdle = false) }
}

fun MainViewModel.showChangelog() { whatsNew = changelog.ifEmpty { null } ?: return say("No changelog in this build.") }

fun MainViewModel.closeWhatsNew() { whatsNew = null }

/** After an update, the first start shows what changed since the build that was there before. */
internal fun MainViewModel.greetAfterUpdate() {
    val seen = prefs.lastSeenBuild
    if (seen >= BuildConfig.VERSION_CODE) return
    prefs.lastSeenBuild = BuildConfig.VERSION_CODE
    val app = getApplication<Application>()
    // A first install has nothing to compare with.
    val fresh = runCatching { app.packageManager.getPackageInfo(app.packageName, 0).let { it.firstInstallTime == it.lastUpdateTime } }.getOrDefault(false)
    if (fresh) return
    whatsNew = Changelog.since(changelog, seen, BuildConfig.VERSION_CODE).ifEmpty { null }
}

fun MainViewModel.changeNotifyDone(v: Boolean) { prefs.notifyDone = v; notifyDone = v }

fun MainViewModel.copyReport(description: String) {
    app.redgrab.ui.copyText(getApplication(), "RedGrab report", BugReport.build(getApplication(), description))
    say("Report copied")
}

/** Files the report as an issue on the app's GitHub repository. */
fun MainViewModel.sendReport(description: String) {
    if (sendingReport) return
    sendingReport = true
    viewModelScope.launch {
        val app = getApplication<Application>()
        val title = description.lineSequence().firstOrNull { it.isNotBlank() }?.trim()
            ?: "Report: RedGrab ${BuildConfig.VERSION_NAME} on ${android.os.Build.MODEL}"
        runCatching { Updater.sendReport(app, title, BugReport.build(app, description)) }
            .onSuccess { AppLog.i("Report sent: $it"); say("Report sent") }
            .onFailure { AppLog.w("Report not sent: ${it.message}"); say(it.message ?: "The report couldn't be sent.") }
        sendingReport = false
    }
}

internal fun MainViewModel.keepSessionAlive() {
    if (renewing || !isOnline()) return
    renewing = true
    viewModelScope.launch {
        val ok = SessionRenewer.renew(getApplication())
        renewing = false
        sessionState = Session.state(prefs)
        sessionText = Session.describe(prefs)
        if (ok) needsSignIn = false else if (sessionState == Session.State.Expired) needsSignIn = true
    }
}
