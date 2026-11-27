package app.redgrab

import android.provider.DocumentsContract
import kotlinx.coroutines.flow.update

// Deleting, hiding, editing and copies; clips saved from online; names learned while browsing. Part of Library, kept in a file of its own.

/**
 * Deletes the saved files of [originals] (every quality and any partial download) and the
 * chosen edited [copyIds]. With [hide], the originals are also hidden from the library so
 * "Save all" won't download them again. Returns how many files were removed.
 */
fun Library.delete(originals: kotlin.collections.Collection<String>, copyIds: kotlin.collections.Collection<String>, hide: Boolean): Int {
    var removed = 0
    fun rm(name: String) {
        val uri = fileUri(name) ?: return
        if (exists(uri) && runCatching { DocumentsContract.deleteDocument(ctx.contentResolver, uri) }.getOrDefault(false)) removed++
    }
    val origSet = originals.toSet()
    for (id in origSet) {
        Quality.entries.forEach { q -> rm(q.fileName(id)); rm("$id${q.suffix}.part") }
    }
    val gone: List<EditCopy>
    var goneImports: List<Clip> = emptyList()
    synchronized(lock) {
        gone = copies.filter { it.id in copyIds }
        val hadExtras = !hide && extras.any { it.id in origSet }
        copies = copies - gone.toSet()
        goneImports = imports.filter { it.id in origSet }
        if (goneImports.isNotEmpty()) imports = imports - goneImports.toSet()
        // Imported files are simply removed; "hide" only means something for RedGifs clips.
        if (hide) hidden = hidden + (origSet - goneImports.mapTo(HashSet()) { it.id })
        // A Discover clip that's deleted (not hidden) has no reason to stay listed.
        if (hadExtras) extras = extras.filter { it.id !in origSet }
        val goneIds = gone.map { it.id }
        tx {
            store.deleteCopies(goneIds)
            // Forget download history for deleted originals so their status is accurate.
            AppDb.sliced(origSet) { store.deleteSaved(it) }
            if (hide) store.putHidden(hidden.map(::HiddenRow))
            if (hadExtras) saveExtras()
            AppDb.sliced(goneImports.map { it.id }) { store.deleteClips(it) }
            AppDb.sliced(origSet) { store.deleteUnseen(it) }
        }
        val clips = compose()
        _state.update { s ->
            s.copy(clips = clips, qualities = s.qualities - origSet, partial = s.partial - origSet, hidden = hidden, unseen = s.unseen - origSet)
        }
    }
    gone.forEach { rm(it.file) }
    goneImports.forEach { c -> c.copyFile?.let(::rm) }
    dropThumbs(origSet + gone.map { it.id })
    clearFailures(origSet)
    synchronized(lock) {
        forgetFiles(origSet.flatMap { id -> Quality.entries.map { it.fileName(id) } } + gone.map { it.file })
        _state.update { it.copy(duplicates = findDuplicates()) }
    }
    AppLog.i("Deleted ${origSet.size} clip(s) and ${gone.size} edited copy(ies): $removed file(s)${if (hide) "; hidden from library" else ""}")
    return removed
}

fun Library.unhide(ids: kotlin.collections.Collection<String>) = synchronized(lock) {
    hidden = hidden - ids.toSet()
    AppDb.sliced(ids.toSet()) { store.deleteHidden(it) }
    val clips = compose()
    _state.update { it.copy(clips = clips, hidden = hidden) }
}

/** Hidden clips as RedGifs clips (for the "Hidden clips" list in Settings). */
fun Library.hiddenClips(): List<Clip> = synchronized(lock) { (raw + extras).filter { it.id in hidden } }

/** Edited copies made from any of [originalIds]. */
fun Library.copiesOf(originalIds: kotlin.collections.Collection<String>): List<Clip> {
    val set = originalIds.toSet()
    return _state.value.clips.filter { it.copyOf in set }
}

fun Library.learn(extras: FeedExtras) {
    if (extras.niches.isNotEmpty()) learnNiches(extras.niches)
    if (extras.performers.isNotEmpty()) synchronized(lock) {
        val merged = _state.value.performers + extras.performers
        _state.update { it.copy(performers = merged) }
        runCatching { savePerformers(extras.performers) }
    }
}

fun Library.learnNiches(names: Map<String, String>) = synchronized(lock) {
    if (names.all { nicheNames[it.key] == it.value }) return@synchronized
    nicheNames = nicheNames + names
    store.putNiches(names.map { NicheRow(it.key, it.value) })
}

/** RedGifs niche ID for a niche name, or null if it's one you made up yourself. */
fun Library.nicheId(name: String): String? = synchronized(lock) { nicheNames.entries.firstOrNull { it.value.equals(name, true) }?.key }

/** Fills in niche names for a clip from online browsing. */
fun Library.resolveNiches(c: Clip): Clip = synchronized(lock) {
    if (c.nicheIds.isEmpty()) c else c.copy(niches = c.nicheIds.mapNotNull { nicheNames[it] })
}

/** Adds a clip found online (not in your likes) to the library so it can be saved and kept. */
fun Library.addExtra(c: Clip) = synchronized(lock) {
    // Saving a clip you once hidden means you want it back.
    val wasHidden = c.id in hidden
    if (wasHidden) {
        hidden = hidden - c.id
        store.deleteHidden(listOf(c.id))
    }
    val known = raw.any { it.id == c.id } || extras.any { it.id == c.id }
    if (known && !wasHidden) return@synchronized
    if (!known) {
        // Keeps where it was found ("Reddit", "FikFap"); RedGifs clips are "Discover".
        extras = extras + c.copy(sources = c.sources.filterTo(LinkedHashSet()) { it in OnlineSource.SAVED_LABELS }.ifEmpty { setOf("Discover") })
        store.putClips(listOf(ClipRow.of(extras.last(), ClipRow.EXTRA, (store.clips(ClipRow.EXTRA).lastOrNull()?.pos ?: -1) + 1)))
    }
    val clips = compose()
    _state.update { it.copy(clips = clips, hidden = hidden) }
}

fun Library.setOverrides(changes: Map<String, MetaOverride?>) = synchronized(lock) {
    val m = overrides.toMutableMap()
    changes.forEach { (id, o) -> if (o == null) m.remove(id) else m[id] = o }
    overrides = m
    val removed = changes.filterValues { it == null }.keys
    val changed = changes.mapNotNull { (id, o) -> o?.let { OverrideRow.of(id, it) } }
    tx {
        AppDb.sliced(removed) { store.deleteOverrides(it) }
        if (changed.isNotEmpty()) store.putOverrides(changed)
    }
    val clips = compose()
    _state.update { it.copy(clips = clips) }
    AppLog.i("Edited info for ${changes.size} clip(s)")
}

/** Next free `<id>-edit-N.mp4` name for an original. */
fun Library.nextCopyName(originalId: String): Pair<String, String> = synchronized(lock) {
    val used = copies.filter { it.originalId == originalId }.map { it.id }.toSet()
    var n = 1
    while ("$originalId-edit-$n" in used) n++
    val id = "$originalId-edit-$n"
    id to "$id.mp4"
}

fun Library.updateCopy(id: String, f: (EditCopy) -> EditCopy) = synchronized(lock) {
    copies = copies.map { if (it.id == id) f(it) else it }
    copies.firstOrNull { it.id == id }?.let { store.putCopies(listOf(CopyRow.of(it))) }
    val clips = compose()
    _state.update { it.copy(clips = clips) }
}

fun Library.addCopy(c: EditCopy) = synchronized(lock) {
    copies = copies + c
    store.putCopies(listOf(CopyRow.of(c)))
    val clips = compose()
    _state.update { it.copy(clips = clips) }
}

fun Library.removeCopy(id: String) = synchronized(lock) {
    copies = copies.filter { it.id != id }
    store.deleteCopies(listOf(id))
    val clips = compose()
    _state.update { it.copy(clips = clips) }
}
