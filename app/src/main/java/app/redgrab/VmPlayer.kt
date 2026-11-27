package app.redgrab

import android.app.Application
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// The clip view, sharing, and editing a clip or its details. Part of MainViewModel, kept in a file of its own.

fun MainViewModel.editInfo(ids: kotlin.collections.Collection<String>) {
    val originals = ids.filter { id -> state.value.clips.any { it.id == id && !it.isCopy } }
    if (originals.isEmpty()) return say("Edited copies share their original's info; edit the original instead.")
    editingInfo = originals
}

fun MainViewModel.closeInfoEditor() { editingInfo = null }

/**
 * Applies info edits to the given clips. Null performer/category = leave as is. Tags and
 * niches: the "add" lists are added to every clip, the "remove" sets removed from every clip.
 */
fun MainViewModel.saveInfo(ids: List<String>, performer: String?, category: String?, addTags: List<String>, removeTags: Set<String>,
             addNiches: List<String> = emptyList(), removeNiches: Set<String> = emptySet()) {
    val byId = state.value.clips.associateBy { it.id }
    editingInfo = null
    selection = emptySet()
    viewModelScope.launch(Dispatchers.Default) {
        fun merge(current: List<String>, add: List<String>, remove: Set<String>): List<String> {
            val gone = remove.mapTo(HashSet()) { it.lowercase() }
            return (current.filter { it.lowercase() !in gone } + add).distinctBy { it.lowercase() }
        }
        val changes = ids.associateWith { id ->
            val c = byId[id] ?: return@associateWith null
            val old = library.override(id) ?: MetaOverride()
            MetaOverride(
                user = performer?.trim() ?: old.user,
                category = category?.trim() ?: old.category,
                tags = if (addTags.isEmpty() && removeTags.isEmpty()) old.tags else merge(c.tags, addTags, removeTags),
                niches = if (addNiches.isEmpty() && removeNiches.isEmpty()) old.niches else merge(c.niches, addNiches, removeNiches),
            )
        }
        withContext(Dispatchers.IO) { library.setOverrides(changes) }
        say("Updated ${ids.size} clip(s)")
    }
}

/** Drops the user's edits so the clips show RedGifs' own info again. */
fun MainViewModel.resetInfo(ids: List<String>) {
    editingInfo = null
    selection = emptySet()
    viewModelScope.launch(Dispatchers.IO) {
        library.setOverrides(ids.associateWith { null })
        say("Restored RedGifs info for ${ids.size} clip(s)")
    }
}

fun MainViewModel.openEditor(c: Clip) {
    if (c.id !in state.value.saved) return say("Save the clip first, then edit it.")
    player = null
    editing = c
}

fun MainViewModel.closeEditor() { editing = null }

fun MainViewModel.deleteCopy(c: Clip) {
    if (!c.isCopy) return
    viewModelScope.launch(Dispatchers.IO) {
        runCatching { library.folder()?.findFile(c.copyFile!!)?.delete() }
        library.removeCopy(c.id)
        AppLog.i("Deleted edited copy ${c.copyFile}")
        say("Edited copy deleted")
    }
    player = null
}

internal fun MainViewModel.startDownload(ids: List<String>) {
    when {
        ids.isEmpty() -> say("Nothing new to download.")
        !hasFolder || library.folder() == null -> say("Choose a save folder first (Settings → Save folder).")
        // Only RedGifs clips need the RedGifs sign-in.
        !prefs.signedIn && ids.any { OnlineSource.of(it.substringBefore(' ')) == OnlineSource.RedGifs } -> say("Sign in first.")
        // One clip you asked for is fetched on whatever connection there is; many at once follow the mobile-data setting.
        !DownloadWorker.start(getApplication(), ids, wifiOnly = ids.size > 1 && !mobileData) -> say("A download is already running. It will finish first.")
        else -> {}
    }
}

fun MainViewModel.stopDownload() = DownloadWorker.stop(getApplication())

fun MainViewModel.dismissDownloadMessage() = library.updateDownload { DownloadProgress() }

fun MainViewModel.openPlayer(list: List<Clip>, index: Int) {
    if (list.isEmpty()) return
    if (sfw) return say("SFW mode is on. Turn it off (eye icon) to play clips.")
    player = PlayerSession(list.map { it.id }, index.coerceIn(0, list.lastIndex))
}

/** Plays clips from online browsing; ones already in the library use their library entry. */
fun MainViewModel.openOnlinePlayer(list: List<Clip>, index: Int) {
    if (list.isEmpty()) return
    if (sfw) return say("SFW mode is on. Turn it off (eye icon) to play clips.")
    val known = state.value.clips.mapTo(HashSet()) { it.id }
    player = PlayerSession(list.map { it.id }, index.coerceIn(0, list.lastIndex), list.filter { it.id !in known }.associateBy { it.id })
}

fun MainViewModel.changeAskCategory(v: Boolean) { prefs.askCategory = v; askCategory = v }

fun MainViewModel.cancelSave() { saveRequest = null }

/** Your categories, most used first. */
fun MainViewModel.categories(): List<String> = state.value.clips.filter { it.category.isNotBlank() }.groupingBy { it.category }.eachCount()
    .entries.sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key.lowercase() }).map { it.key }

/**
 * Where the saving of a clip stands, for its Save button: null = not being saved, -1 = being
 * saved or waiting its turn with no known size, else 0..1.
 */
fun MainViewModel.saveProgress(id: String, dl: DownloadProgress, s: LibraryState): Float? = when {
    id in savingImages -> savingFraction[id] ?: -1f
    id in s.saved -> null
    dl.running && dl.currentId == id -> if (dl.currentTotal > 0) (dl.currentBytes.toFloat() / dl.currentTotal).coerceIn(0f, 1f) else -1f
    id in saveRequested && id !in s.failures && !dl.paused -> -1f
    else -> null
}

/**
 * Saves an online clip. Asks for a category first when that is switched on ([category] null);
 * "" = no category. Then adds the clip to the library and saves it (HD first). Pictures and
 * 4chan's files go straight into the save folder.
 */
fun MainViewModel.saveOnline(clip: Clip, q: Quality? = null, category: String? = null, collection: RgCollection? = null, newCollection: String? = null) {
    if (category == null && askCategory) { saveRequest = MainViewModel.SaveRequest(clip, q); return }
    saveRequest = null
    val cat = category?.trim().orEmpty()
    if (OnlineSource.keepsOwnFile(clip)) return saveOnlineFile(clip, cat)
    saveRequested = saveRequested + clip.id
    viewModelScope.launch(Dispatchers.IO) {
        // A RedGifs clip found on Reddit has no video addresses until RedGifs is asked for them.
        val c = if (clip.hd == null && clip.sd == null) resolveOnline(clip) else clip
        if (c == null) {
            withContext(Dispatchers.Main) { saveRequested = saveRequested - clip.id }
            return@launch say("Couldn't get this clip from RedGifs.")
        }
        library.addExtra(c)
        if (cat.isNotEmpty()) library.setCategory(c.id, cat)
        if (collection != null || !newCollection.isNullOrBlank()) addToCollection(c, collection, newCollection?.trim())
        withContext(Dispatchers.Main) { if (q == null) download(c.id) else downloadQuality(c.id, q) }
        settleSave(c.id)
    }
}

/** Saves a clip of the library from the player, with the progress on its Save button. */
fun MainViewModel.downloadShown(id: String) {
    saveRequested = saveRequested + id
    download(id)
    viewModelScope.launch { settleSave(id) }
}

/** Nothing started (no save folder, say): the button goes back to "Save". */
internal suspend fun MainViewModel.settleSave(id: String) {
    kotlinx.coroutines.delay(4_000)
    val dl = library.download.value
    if (!dl.running && !dl.waiting) withContext(Dispatchers.Main) { saveRequested = saveRequested - id }
}

/** Whether a clip can go into a RedGifs collection: only RedGifs' own clips, and only when signed in. */
fun MainViewModel.canCollect(clip: Clip) = signedIn && !clip.imported && !clip.isCopy && OnlineSource.of(clip.id) == OnlineSource.RedGifs

/**
 * Adds a clip to one of your RedGifs collections, creating the collection first when [newName]
 * is given. This changes your RedGifs account, exactly as "add to collection" on the site does.
 */
internal fun MainViewModel.addToCollection(clip: Clip, existing: RgCollection?, newName: String?) {
    if (!canCollect(clip)) return
    viewModelScope.launch(Dispatchers.IO) {
        runCatching {
            val col = existing
                ?: state.value.collections.firstOrNull { it.name.equals(newName, ignoreCase = true) }
                ?: withRedGifs(getApplication()) { it.createCollection(newName!!) }.also { library.addCollection(it); AppLog.i("Created the RedGifs collection ${it.name}") }
            withRedGifs(getApplication()) { it.addToCollection(col.id, clip.id) }
            library.noteInCollection(clip.id, col)
            col
        }.onSuccess { say("Added to ${it.name} on RedGifs") }.onFailure { e ->
            AppLog.w("Could not add ${clip.id} to a RedGifs collection: ${e.message}")
            say("Not added to the RedGifs collection: ${friendly(e, OnlineSource.RedGifs).removePrefix("Sync failed: ")}")
        }
    }
}

internal fun MainViewModel.saveOnlineFile(clip: Clip, category: String) {
    if (!hasFolder || library.folder() == null) return say("Choose a save folder first (Settings → Save folder).")
    if (clip.id in savingImages) return
    savingImages = savingImages + clip.id
    viewModelScope.launch(Dispatchers.IO) {
        val r = runCatching {
            val http = RedGifsApi.clientFor(getApplication<Application>().userAgent)
            // sex.com lists tags only on the picture's own page, and offers the real GIF behind a WebP.
            val sexCom = OnlineSource.of(clip.id) == OnlineSource.SexCom
            val tags = if (!sexCom || clip.tags.isNotEmpty()) clip.tags else
                runCatching { SexComApi(http).tags(clip.id.removePrefix(OnlineSource.SexCom.prefix)) }
                    .onFailure { AppLog.w("No tags for ${clip.id}: ${it.message}") }.getOrDefault(emptyList())
            var shown = -1
            library.addOnlineFile(clip.copy(tags = tags), http, if (sexCom) SexComApi.files(clip) else listOfNotNull(clip.hd)) { done, total ->
                // Whole percents only, so the screen isn't redrawn for every chunk.
                val pct = if (total > 0) (done * 100 / total).toInt().coerceIn(0, 100) else -1
                if (pct != shown) { shown = pct; savingFraction = savingFraction + (clip.id to if (pct < 0) -1f else pct / 100f) }
            }
            if (category.isNotEmpty()) library.setCategory(clip.id, category)
        }
        withContext(Dispatchers.Main) { savingImages = savingImages - clip.id; savingFraction = savingFraction - clip.id }
        r.onSuccess { say("Saved") }.onFailure { e ->
            AppLog.e("Saving ${clip.id} failed: ${e.message}", e.takeUnless { it is HttpException })
            say("Couldn't save: ${DownloadWorker.reasonFor(e, OnlineSource.of(clip.id).label)}")
        }
    }
}

/**
 * Looks up a RedGifs clip that was found on Reddit (only its ID is known there) and puts the
 * full clip in place of the bare one in the player and the feed. Null if RedGifs can't be asked.
 */
suspend fun MainViewModel.resolveOnline(c: Clip): Clip? = withContext(Dispatchers.IO) {
    if (!prefs.signedIn) return@withContext null
    val fresh = runCatching { withRedGifs(getApplication()) { it.clip(c.id) } }
        .onFailure { AppLog.w("Could not get ${c.id} from RedGifs: ${it.message}") }.getOrNull() ?: return@withContext null
    val full = library.resolveNiches(fresh.copy(sources = c.sources, thumb = fresh.thumb ?: c.thumb,
        description = fresh.description.ifBlank { c.description }))
    withContext(Dispatchers.Main) { putOnline(full) }
    full
}

/** A fresh copy of a clip from its own site, for when its media addresses have expired. */
suspend fun MainViewModel.refreshClip(c: Clip): Clip? = withContext(Dispatchers.IO) {
    runCatching {
        when (OnlineSource.of(c.id)) {
            OnlineSource.RedGifs -> withRedGifs(getApplication()) { it.clip(c.id) }
            OnlineSource.FikFap -> FikFapApi(RedGifsApi.clientFor(getApplication<Application>().userAgent)).post(c.id.removePrefix(OnlineSource.FikFap.prefix))
            else -> null
        }
    }.getOrNull()?.let { it.copy(sources = c.sources) }?.also { fresh ->
        library.replaceClip(fresh)
        withContext(Dispatchers.Main) { putOnline(fresh) }
    }
}

/** Swaps in a newer copy of an online clip wherever the old one is shown. */
internal fun MainViewModel.putOnline(c: Clip) {
    player?.let { p -> if (c.id in p.online) player = p.copy(online = p.online + (c.id to c)) }
    if (discover.items.any { it.id == c.id }) discover = discover.copy(items = discover.items.map { if (it.id == c.id) c else it })
}
