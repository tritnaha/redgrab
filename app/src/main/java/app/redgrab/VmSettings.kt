package app.redgrab

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.UnknownHostException
import javax.net.ssl.SSLException

// Settings, layout, SFW mode and coming back to where you were. Part of MainViewModel, kept in a file of its own.

fun MainViewModel.changeSfw(v: Boolean) {
    prefs.sfw = v; sfw = v
    if (v) {
        player = null
        // Drop what was loaded online so no thumbnails stay in memory or on screen.
        discoverJob?.cancel()
        discover = DiscoverState(feed = discover.feed, order = discover.order)
    }
    AppLog.i("SFW mode ${if (v) "on" else "off"}")
}

fun MainViewModel.changeSfwOnStart(v: Boolean) { prefs.sfwOnStart = v; sfwOnStart = v }

fun MainViewModel.changeBackToTabs(v: Boolean) { prefs.backToTabs = v; backToTabs = v }

fun MainViewModel.changeStartView(v: String) { prefs.startView = v; startView = v }

fun MainViewModel.changeMobileData(v: Boolean) { prefs.syncWifiOnly = !v; mobileData = v; syncWifiOnly = !v; SyncWorker.schedule(getApplication()) }

fun MainViewModel.changeVolumeGuard(v: Boolean) { prefs.volumeGuard = v; volumeGuard = v }

fun MainViewModel.changeVolumeLimit(v: Int) { prefs.volumeLimit = v; volumeLimit = v }

fun MainViewModel.changeTheme(id: String) { prefs.theme = id; themeId = id }

fun MainViewModel.changeWall(v: Boolean) { prefs.wall = v; wall = v }

fun MainViewModel.changePreviews(v: Boolean) { prefs.previews = v; previews = v }

fun MainViewModel.changePreviewUnsaved(v: Boolean) { prefs.previewUnsaved = v; previewUnsaved = v }

fun MainViewModel.changePreviewCount(v: Int) { prefs.previewCount = v; previewCount = prefs.previewCount }

fun MainViewModel.changePreviewCopies(v: Boolean) { prefs.previewCopies = v; previewCopies = v; if (!v) PreviewMaker.cancel() }

fun MainViewModel.measurePreviewCopies() { viewModelScope.launch(Dispatchers.IO) { previewCopyBytes = library.previewBytes() } }

fun MainViewModel.makeAllPreviewCopies() { viewModelScope.launch(Dispatchers.Default) { PreviewMaker.requestAll(getApplication<Application>()) } }

fun MainViewModel.clearPreviewCopies() { PreviewMaker.cancel(); viewModelScope.launch(Dispatchers.IO) { library.clearPreviews(); previewCopyBytes = 0 } }

fun MainViewModel.changeColumns(v: Int) { prefs.columns = v; columns = prefs.columns }

fun MainViewModel.setFolder(uri: Uri) {
    // A running download writes to the folder it started with; switching under it would split the files.
    if (DownloadWorker.active) { say("Pause the download before changing the save folder."); return }
    if (storageStatus != null) { say("Wait for the move or export to finish."); return }
    val ctx = getApplication<Application>()
    val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
    runCatching { ctx.contentResolver.takePersistableUriPermission(uri, flags) }
        .onFailure { AppLog.e("Could not keep folder permission", it) }
    prefs.folderUri = uri
    prefs.folderLabel = labelFor(uri)
    privateStorage = PrivateDocs.isPrivate(uri)
    AppLog.i("Save folder set: ${prefs.folderLabel}")
    refreshFolderInfo()
    rescanFolder()
    // Keep clips out of the phone's Gallery by default; can be switched off in Settings.
    changeHiddenFromGallery(true)
}

/** Creates a subfolder (a leading dot makes it hidden) inside the current folder and switches to it. */
fun MainViewModel.createSubfolder(name: String) {
    val clean = name.trim().replace(Regex("[\\\\/:*?\"<>|]"), "_")
    if (clean.isEmpty() || clean == "." || clean == "..") return say("Enter a folder name.")
    if (DownloadWorker.active) return say("Pause the download before changing the save folder.")
    viewModelScope.launch {
        val created = withContext(Dispatchers.IO) {
            runCatching {
                val parent = library.folder() ?: error("Choose a folder first")
                parent.findFile(clean)?.takeIf { it.isDirectory } ?: parent.createDirectory(clean)
                    ?: error("The folder could not be created")
            }
        }
        created.onSuccess { dir ->
            prefs.folderUri = dir.uri
            prefs.folderLabel = (prefs.folderLabel ?: "") + "/" + clean
            AppLog.i("Created and switched to subfolder: ${prefs.folderLabel}")
            refreshFolderInfo()
            rescanFolder()
            changeHiddenFromGallery(true)
            say("Now saving to “$clean”")
        }.onFailure { e ->
            AppLog.e("Create subfolder failed", e)
            say("Couldn't create folder: ${e.message}")
        }
    }
}

fun MainViewModel.changeHiddenFromGallery(hide: Boolean) {
    viewModelScope.launch(Dispatchers.IO) {
        val folder = library.folder() ?: return@launch say("Choose a folder first.")
        runCatching {
            val existing = folder.findFile(".nomedia")
            if (hide && existing == null) folder.createFile("application/octet-stream", ".nomedia")
            if (!hide) existing?.delete()
        }.onFailure { AppLog.e("Changing .nomedia failed", it) }
        hiddenFromGallery = folder.findFile(".nomedia") != null
        AppLog.i("Hide from Gallery: $hiddenFromGallery")
    }
}

fun MainViewModel.changeIncludeCollections(v: Boolean) { prefs.includeCollections = v; includeCollections = v }

fun MainViewModel.changeAppLock(v: Boolean) { prefs.appLock = v; appLock = v; AppLog.i(if (v) "App lock on" else "App lock off") }

fun MainViewModel.changeAppLockAfter(sec: Int) { prefs.appLockAfterSec = sec; appLockAfterSec = prefs.appLockAfterSec }

fun MainViewModel.changeRefreshOnStart(v: Boolean) { prefs.refreshOnStart = v; refreshOnStart = v }

fun MainViewModel.changeStartSyncHours(v: Int) { prefs.startSyncHours = v; startSyncHours = prefs.startSyncHours }

fun MainViewModel.signOut() {
    prefs.signOut()
    android.webkit.CookieManager.getInstance().removeAllCookies(null)
    android.webkit.WebStorage.getInstance().deleteAllData()
    signedIn = false
    AppLog.i("Signed out")
}

fun MainViewModel.forgetHistory() = viewModelScope.launch(Dispatchers.IO) { library.forgetHistory() }

fun MainViewModel.dismissCrash() { lastCrash = null }

/**
 * Housekeeping after the folder may have changed. Listing the whole save folder is slow with
 * many files, so on an ordinary start ([force] off) it is skipped when it was done in the last
 * twelve hours: the app's own record of what it saved is used meanwhile.
 */
internal fun MainViewModel.rescanFolder(force: Boolean = true) = viewModelScope.launch(Dispatchers.IO) {
    val due = force || System.currentTimeMillis() - prefs.lastFolderScan !in 0..12 * 3_600_000L
    if (due) runCatching { library.rescan() }.onFailure { AppLog.e("Folder scan failed", it) }
    runCatching { library.cleanPartials() }
    refreshPartialInfo()
    runCatching { library.adoptImported() }
    runCatching { library.backfillThumbs() }
    // Every saved clip gets its small preview copy without being asked for (they are made in the background, one at a time).
    if (prefs.previewCopies) runCatching { PreviewMaker.requestAll(getApplication<Application>()) }
}

/** Moves the GIFs and videos of a folder you picked into RedGrab and lists them as "Imported". */
fun MainViewModel.importFolder(uri: Uri) {
    if (importStatus != null) return
    if (storageStatus != null) return say("Wait for the move or export to finish.")
    if (!hasFolder) return say("Choose a save folder first.")
    importStatus = "Starting"
    viewModelScope.launch(Dispatchers.IO) {
        val r = runCatching { library.importFolder(uri) { importStatus = it } }
        importStatus = null
        r.onSuccess { (moved, failed) ->
            say("Imported %,d file(s)".format(moved) + if (failed > 0) ". %,d couldn't be moved (see the log).".format(failed) else "")
        }.onFailure { AppLog.e("Import failed", it); say("Import failed: ${it.message}") }
    }
}

fun MainViewModel.markAllSeen() { viewModelScope.launch(Dispatchers.IO) { library.markAllSeen() } }

internal fun MainViewModel.refreshFolderInfo() {
    hasFolder = prefs.folderUri != null
    folderLabel = prefs.folderLabel
    viewModelScope.launch(Dispatchers.IO) {
        val f = library.folder()
        if (prefs.folderUri != null && f == null) {
            AppLog.w("Save folder is no longer accessible: ${prefs.folderLabel}")
            say("Can't access the save folder any more. Choose it again in Settings.")
        }
        hiddenFromGallery = f?.findFile(".nomedia") != null
    }
}

internal fun MainViewModel.labelFor(uri: Uri): String = runCatching {
    val id = if (DocumentsContract.isDocumentUri(getApplication(), uri)) DocumentsContract.getDocumentId(uri)
    else DocumentsContract.getTreeDocumentId(uri)
    val (vol, path) = id.split(":", limit = 2).let { it[0] to it.getOrElse(1) { "" } }
    val volName = if (vol == "primary") "Internal storage" else vol
    if (path.isEmpty()) volName else "$volName/$path"
}.getOrElse { DocumentFile.fromTreeUri(getApplication(), uri)?.name ?: uri.toString() }

internal fun MainViewModel.say(msg: String) { _messages.trySend(msg) }

internal fun MainViewModel.friendly(e: Throwable, source: OnlineSource = OnlineSource.RedGifs): String = when (e) {
    is AuthException -> "Your RedGifs session expired. Tap Renew to sign in again."
    is VerificationRequiredException -> "RedGifs wants a quick human check before continuing."
    is RedditAuthException -> e.message ?: "Reddit sign-in failed."
    is SiteBlockedException -> e.message ?: "${source.label} is blocking the app."
    is UnknownHostException -> "No internet connection."
    is SSLException -> "Secure connection failed. A network filter may be blocking ${source.label}."
    is HttpException -> when {
        e.code == 404 && source != OnlineSource.RedGifs -> "Not found on ${source.label}."
        e.code == 403 && source == OnlineSource.Reddit -> "Reddit won't show this (HTTP 403)."
        else -> "${source.label} returned an error (HTTP ${e.code}). See Log."
    }
    else -> "Sync failed: ${e.message ?: e.javaClass.simpleName}"
}

/** Points the save folder at the app's own storage. */
internal fun MainViewModel.usePrivateStorage() {
    prefs.folderUri = PrivateDocs.TREE
    prefs.folderLabel = "Inside the app"
    privateStorage = true
    refreshFolderInfo()
}

/**
 * "Keep clips inside the app": moves everything from the save folder (or, already inside, from
 * another folder of RedGrab clips: [from]) into the app's own storage in one go, then uses that
 * from now on. Started again after an interruption, it carries on.
 */
fun MainViewModel.moveIntoApp(from: Uri? = null) {
    if (storageStatus != null || (privateStorage && from == null)) return
    if (DownloadWorker.active || importStatus != null) return say("Wait for the download or import to finish first.")
    val source = from ?: prefs.folderUri
    if (source == null) { usePrivateStorage(); rescanFolder(force = true); return }
    if (PrivateDocs.isPrivate(source)) return
    storageStatus = "Starting"
    viewModelScope.launch(Dispatchers.IO) {
        try {
            if (from != null) runCatching { getApplication<Application>().contentResolver.takePersistableUriPermission(from, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) }
            // The folder has to be there and readable; an unreachable one is not an empty one.
            if (from == null && library.folder() == null) { say("Can't reach the save folder. Nothing was changed."); return@launch }
            val (moved, failed) = library.moveTreeIntoApp(source) { storageStatus = "Moving $it" }
            val saved = state.value.saved.size
            when {
                failed > 0 -> say("%,d moved, %,d couldn't be (see the log). Nothing is lost; run it again.".format(moved, failed))
                // Nothing found although the library has saved clips: the folder isn't the one they are in.
                moved == 0 && from == null && saved > 0 && library.listNames().isEmpty() && PrivateDocs.dir(getApplication()).list().isNullOrEmpty() ->
                    say("No files were found in the save folder. Nothing was changed.")
                else -> {
                    // From here on the app uses its own storage; only then is the old folder tidied.
                    withContext(Dispatchers.Main) { usePrivateStorage() }
                    val empty = library.tidyMovedFolder(source)
                    withContext(Dispatchers.Main) { rescanFolder(force = true) }
                    say("%,d file(s) moved into the app.".format(moved) + if (empty) "" else " Some files are still in the old folder.")
                }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLog.e("Moving into the app failed", e); say("Couldn't move: ${e.message}. Nothing is lost.")
        } finally {
            storageStatus = null
            if (from != null) runCatching { getApplication<Application>().contentResolver.releasePersistableUriPermission(from, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) }
        }
    }
}

/** Copies every saved file into [to] as ordinary files. The folder gets a marker that keeps galleries from showing them. */
fun MainViewModel.exportAll(to: Uri) {
    if (storageStatus != null) return
    storageStatus = "Starting"
    viewModelScope.launch(Dispatchers.IO) {
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        val resolver = getApplication<Application>().contentResolver
        runCatching { resolver.takePersistableUriPermission(to, flags) }
        try {
            val (copied, failed) = library.exportTo(to) { storageStatus = "Exporting $it" }
            prefs.lastExport = "${labelFor(to)} · ${java.time.LocalDate.now()}"
            lastExport = prefs.lastExport
            say("%,d file(s) exported".format(copied) + if (failed > 0) ", %,d failed (see the log).".format(failed) else ". They are ordinary files now: other apps can open them.")
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLog.e("Export failed", e); say("Export failed: ${e.message}")
        } finally {
            storageStatus = null
            // The app keeps no hold on the folder it exported to.
            runCatching { resolver.releasePersistableUriPermission(to, flags) }
        }
    }
}
