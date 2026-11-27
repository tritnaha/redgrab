package app.redgrab

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// Syncing, selecting, downloading and deleting. Part of MainViewModel, kept in a file of its own.

internal fun MainViewModel.powerSave(): Boolean =
    getApplication<Application>().getSystemService(android.os.PowerManager::class.java)?.isPowerSaveMode == true

internal fun MainViewModel.isOnline(): Boolean {
    val cm = getApplication<Application>().getSystemService(android.net.ConnectivityManager::class.java) ?: return true
    val caps = cm.getNetworkCapabilities(cm.activeNetwork ?: return false) ?: return false
    return caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)
}

internal fun MainViewModel.watchNetwork() {
    val cm = getApplication<Application>().getSystemService(android.net.ConnectivityManager::class.java) ?: return
    offline = !isOnline()
    val cb = object : android.net.ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: android.net.Network) { offline = false }
        override fun onLost(network: android.net.Network) { offline = !isOnline() }
    }
    if (runCatching { cm.registerDefaultNetworkCallback(cb) }.isSuccess) netCallback = cb
}

internal fun MainViewModel.refreshPartialInfo() {
    val parts = runCatching { library.partials() }.getOrDefault(emptyList())
    partialInfo = parts.size to parts.sumOf { it.size }
}

fun MainViewModel.onResume() {
    signedIn = prefs.signedIn
    if (signedIn) needsSignIn = false
    sessionState = Session.state(prefs)
    sessionText = Session.describe(prefs)
    // A session that has run out is renewed out of sight; only if that fails are you asked to sign in.
    if (sessionState == Session.State.Expired || sessionState == Session.State.ExpiringSoon) keepSessionAlive()
    verifyNeeded = prefs.pendingVerification != null
    redditUser = if (prefs.redditRefresh != null) prefs.redditUser ?: "" else null
}

/** Syncs with RedGifs: a quick one that fetches what is new, or with [full] everything again. */
fun MainViewModel.refresh(quiet: Boolean = false, full: Boolean = false) {
    if (!prefs.signedIn) {
        say("Sign in first (Settings → Account).")
        return
    }
    if (offline && !isOnline()) {
        if (!quiet) say("You're offline. Everything saved still works; syncing needs a connection.")
        return
    }
    viewModelScope.launch {
        library.refresh(full).also { sessionState = Session.state(prefs); sessionText = Session.describe(prefs) }.onSuccess {
            needsSignIn = false
            val s = state.value
            // Auto-sync: anything new in the collections you marked gets downloaded right away.
            val pending = library.pendingWatched()
            if (pending.isNotEmpty() && hasFolder && DownloadWorker.start(getApplication(), pending)) {
                say("Synced. Saving ${pending.size} new clip(s) from your synced collections.")
            } else if (!quiet) say("Synced: ${s.originals.size} clips, ${s.newCount} not saved")
        }.onFailure { e ->
            if (e is AuthException) needsSignIn = true
            if (e is VerificationRequiredException) { prefs.pendingVerification = e.subject; verifyNeeded = true }
            say(friendly(e))
        }
    }
}

fun MainViewModel.toggle(id: String) {
    selection = if (id in selection) selection - id else selection + id
}

fun MainViewModel.clearSelection() { selection = emptySet() }

fun MainViewModel.selectAllNew() {
    val s = state.value
    selection = s.originals.filter { it.id !in s.saved }.map { it.id }.toSet()
}

fun MainViewModel.downloadSelected() {
    val s = state.value
    val ids = s.originals.filter { it.id in selection && it.id !in s.saved }.map { it.id }
    selection = emptySet()
    startDownload(ids)
}

/** "Save all": everything not saved, except clips whose downloads have given up (retry those by hand). */
fun MainViewModel.downloadAllNew() {
    val s = state.value
    startDownload(s.originals.filter { it.id !in s.saved && s.failures[it.id]?.gaveUp != true }.map { it.id })
}

/** Tries the failed downloads again, with a fresh set of attempts. */
fun MainViewModel.retryFailed(ids: kotlin.collections.Collection<String> = state.value.failed) {
    val list = ids.filter { it !in state.value.saved }
    if (list.isEmpty()) return say("Nothing to retry.")
    viewModelScope.launch(Dispatchers.IO) {
        library.clearFailures(list)
        withContext(Dispatchers.Main) { startDownload(list) }
    }
}

fun MainViewModel.resumeDownload() {
    if (!DownloadWorker.resume(getApplication())) say("Nothing to resume.")
}

fun MainViewModel.clearDownloadQueue() = DownloadWorker.clear(getApplication())

/**
 * "Don't save": sets clips aside so they are not counted, downloaded or retried again. They
 * stay in the library under "Removed", where "Check again" brings them back.
 */
fun MainViewModel.skip(ids: kotlin.collections.Collection<String>) {
    val set = ids.toSet()
    if (set.isEmpty()) return
    viewModelScope.launch(Dispatchers.IO) {
        library.setQueue(library.readQueue().filter { it.substringBefore(' ') !in set })
        set.forEach { library.markFailed(it, Library.SKIPPED, dead = true); library.dropPartial(it) }
        AppLog.i("${set.size} unsaved clip(s) set aside; they won't be downloaded")
    }
}

/** Everything that is waiting to be saved: stops what is running, empties the queue and sets all of it aside. */
fun MainViewModel.skipPending() {
    val s = state.value
    val ids = s.pending.map { it.id }
    DownloadWorker.stop(getApplication())
    DownloadWorker.clear(getApplication())
    skip(ids)
    if (ids.isNotEmpty()) say("%,d set aside. Find them under Removed.".format(ids.size))
}

fun MainViewModel.download(id: String) = startDownload(listOf(id))

/** Download the selection in a specific quality (SD only when asked for explicitly). */
fun MainViewModel.downloadSelectedAs(q: Quality) {
    val s = state.value
    val items = s.originals.filter { it.id in selection && q !in (s.qualities[it.id] ?: emptySet()) }.map { "${it.id} ${q.name}" }
    selection = emptySet()
    startDownload(items)
}

/** Save a specific quality of a clip (e.g. HD when only SD is saved). */
fun MainViewModel.downloadQuality(id: String, q: Quality) = startDownload(listOf("$id ${q.name}"))

/** Starts deleting the given clips; if any have edited copies, the user picks which copies go too. */
fun MainViewModel.requestDelete(ids: kotlin.collections.Collection<String>) {
    val byId = state.value.clips.associateBy { it.id }
    val picked = ids.mapNotNull(byId::get)
    val originals = picked.filter { !it.isCopy }
    val pickedCopies = picked.filter { it.isCopy }
    // Copies of the originals that weren't selected themselves: ask about these.
    val related = library.copiesOf(originals.map { it.id }).filter { c -> pickedCopies.none { it.id == c.id } }
    deleteRequest = DeleteRequest(originals, pickedCopies + related, pickedCopies.map { it.id }.toSet())
    player = null
}

fun MainViewModel.toggleDeleteCopy(id: String) {
    val r = deleteRequest ?: return
    deleteRequest = r.copy(selectedCopies = if (id in r.selectedCopies) r.selectedCopies - id else r.selectedCopies + id)
}

fun MainViewModel.cancelDelete() { deleteRequest = null }

fun MainViewModel.confirmDelete(hide: Boolean) {
    val r = deleteRequest ?: return
    deleteRequest = null
    selection = emptySet()
    viewModelScope.launch(Dispatchers.IO) {
        val n = runCatching { library.delete(r.originals.map { it.id }, r.selectedCopies, hide) }
            .onFailure { AppLog.e("Delete failed", it) }.getOrDefault(0)
        val kept = r.copies.size - r.selectedCopies.size
        say("Deleted $n file(s)" + (if (kept > 0) " · kept $kept edited cop${if (kept == 1) "y" else "ies"}" else ""))
    }
}

fun MainViewModel.unhide(ids: List<String>) { viewModelScope.launch(Dispatchers.IO) { library.unhide(ids) } }

/** Marks a collection (or [Library.WATCH_LIKES]) to be kept in sync; new clips in it download automatically. */
fun MainViewModel.setWatched(key: String, on: Boolean) {
    viewModelScope.launch(Dispatchers.IO) {
        library.setWatched(key, on)
        if (on) {
            val pending = library.pendingWatched()
            withContext(Dispatchers.Main) {
                when {
                    pending.isEmpty() -> say("Syncing on. Everything in it is already saved.")
                    !hasFolder -> say("Syncing on. Choose a save folder (Settings) to start saving.")
                    DownloadWorker.start(getApplication(), pending) -> say("Syncing on. Saving ${pending.size} clip(s).")
                    else -> say("Syncing on. The clips are saved after the current download.")
                }
            }
        }
    }
}

fun MainViewModel.openCollection(key: String) = browse(Facet(Facet.Kind.Collection, key))

fun MainViewModel.changeAutoSyncHours(h: Int) { prefs.autoSyncHours = h; autoSyncHours = h; SyncWorker.schedule(getApplication()) }

fun MainViewModel.clearPartials() {
    viewModelScope.launch(Dispatchers.IO) {
        val freed = library.cleanPartials(all = true)
        refreshPartialInfo()
        say(if (freed > 0) "Removed partial downloads (${Format.size(freed)} freed)" else "No partial downloads to remove")
    }
}

fun MainViewModel.verificationHandled() { verifyNeeded = false }
