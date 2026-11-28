package app.redgrab

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

// The one-time move of the files used up to version 10 into the database. Part of Library, kept in a file of its own.

internal fun Library.importLegacyFiles() {
    val dir = ctx.filesDir
    val files = Library.LEGACY_FILES.map { File(dir, it) }.filter { it.exists() }
    if (files.isEmpty()) return
    if (!prefs.legacyImported) {
        // A file that can't be read is skipped (and kept as <name>.bad); the rest still moves.
        fun <T> read(name: String, parse: (File) -> List<T>): List<T> {
            val f = File(dir, name)
            if (!f.exists()) return emptyList()
            return try { parse(f) } catch (e: Exception) {
                AppLog.e("Could not read ${f.name} while moving to the database; kept as ${f.name}.bad", e)
                runCatching { f.copyTo(File(f.path + ".bad"), overwrite = true) }
                emptyList()
            }
        }
        fun array(f: File): List<JSONObject> = JSONArray(f.readText()).let { a -> (0 until a.length()).map { a.getJSONObject(it) } }
        fun <T> obj(f: File, make: (String, JSONObject) -> T): List<T> =
            JSONObject(f.readText()).let { j -> j.keys().asSequence().toList().map { make(it, j) } }
        fun lines(f: File): List<String> = f.readLines().map { it.trim() }.filter { it.isNotEmpty() }

        val lib = read("library.json") { f -> array(f).map(Clip::fromJson) }.filter { Clip.validId(it.id) }.distinctBy { it.id }
        val ext = read("extras.json") { f -> array(f).map(Clip::fromJson) }.filter { Clip.validId(it.id) }.distinctBy { it.id }
        val ovr = read("overrides.json") { f -> obj(f) { k, j -> OverrideRow.of(k, MetaOverride.fromJson(j.getJSONObject(k))) } }
        val cps = read("copies.json") { f -> array(f).map { CopyRow.of(EditCopy.fromJson(it)) } }
        val hid = read("hidden.txt") { f -> lines(f).map(::HiddenRow) }
        val sav = read("downloaded.txt") { f ->
            lines(f).map { l ->
                val parts = l.split(" ")
                SavedRow(parts[0], parts.getOrNull(1)?.takeIf { q -> Quality.entries.any { it.name == q } } ?: Quality.LEGACY.name, 0)
            }
        }
        val que = read("queue.txt") { f -> lines(f) }
        val syn = read("sync_history.json") { f ->
            array(f).map { SyncRecord.fromJson(it) }.map { SyncRow(it.time, it.total, it.added, it.removed, it.saved) }
        }
        val col = read("collections.json") { f -> array(f).map(RgCollection::fromJson) }
        val wat = read("watched.txt") { f -> lines(f).map(::WatchedRow) }
        val fil = read("files.json") { f -> obj(f) { k, j -> j.getJSONObject(k).let { FileRow(k, it.optLong("size"), it.optString("sha")) } } }
        val fai = read("failures.json") { f ->
            obj(f) { k, j -> Failure.fromJson(j.getJSONObject(k)).let { FailureRow(k, it.reason, it.first, it.last, it.attempts) } }
        }
        val nic = read("niches.json") { f -> obj(f) { k, j -> NicheRow(k, j.getString(k)) } }
        val per = read("performers.json") { f -> array(f).map { PerformerRow.of(Performer.fromJson(it)) } }
        try {
            tx {
                val libIds = lib.mapTo(HashSet()) { it.id }
                store.putClips(ext.filter { it.id !in libIds }.mapIndexed { i, c -> ClipRow.of(c, ClipRow.EXTRA, i) })
                store.putClips(lib.mapIndexed { i, c -> ClipRow.of(c, ClipRow.LIBRARY, i) })
                store.putOverrides(ovr); store.putCopies(cps); store.putHidden(hid); store.putSaved(sav)
                store.putQueue(que.mapIndexed { i, e -> QueueRow(i, e) }); store.putSyncs(syn)
                store.putCollections(col.mapIndexed { i, c -> CollectionRow(c.id, i, c.name, c.count, c.thumb) })
                store.putWatched(wat); store.putFiles(fil); store.putFailures(fai); store.putNiches(nic); store.putPerformers(per)
            }
        } catch (e: Exception) {
            // Nothing was changed (the transaction rolled back) and the files stay where they are; tried again next start.
            AppLog.e("Moving the library into the database failed; will try again next time", e)
            return
        }
        prefs.legacyImported = true
        AppLog.i("Moved the library into the database: ${lib.size} clips, ${ext.size} saved from Online, ${sav.size} download records, " +
            "${cps.size} edited copies, ${ovr.size} info edits, ${col.size} collections")
    }
    // The old files are kept as a backup in files/legacy-json, out of the way.
    val backup = File(dir, "legacy-json").apply { mkdirs() }
    files.forEach { f -> if (!f.renameTo(File(backup, f.name))) AppLog.w("Could not move ${f.name} to the backup folder") }
}
