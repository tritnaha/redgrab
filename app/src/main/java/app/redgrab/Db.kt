package app.redgrab

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase

/*
 * The app's database (files/../databases/redgrab.db). It replaces the JSON and text files the
 * app used up to version 10: every change is now one small, atomic write instead of rewriting a
 * whole file, and changes that belong together (a sync, a delete) happen in one transaction.
 *
 * The rows are plain storage records. The app keeps working with Clip, EditCopy, Failure etc.;
 * Library converts at the edge.
 */

/** Lists are stored as one text column, items separated by the ASCII "unit separator". */
private const val SEP = "\u001F"
fun kotlin.collections.Collection<String>.pack(): String = joinToString(SEP)
fun String.unpack(): List<String> = if (isEmpty()) emptyList() else split(SEP)

/** A clip. kind 0 = from your likes/collections, 1 = saved from Online, 2 = a file you imported. */
@Entity(tableName = "clip", indices = [Index("kind", "pos")])
data class ClipRow(
    @PrimaryKey val id: String,
    val kind: Int,
    /** Order within its kind (likes keep RedGifs' order). */
    val pos: Int,
    val hd: String?, val sd: String?, val thumb: String?,
    val duration: Double, val width: Int, val height: Int, val hasAudio: Boolean,
    val user: String, val created: Long,
    val sources: String, val tags: String,
    val views: Long, val likes: Long,
    val nicheIds: String, val sexuality: String,
    val verified: Boolean, val description: String, val colIds: String,
    /** Imported files only: where the file is, relative to the save folder. */
    val file: String? = null,
) {
    fun toClip() = Clip(
        id = id, hd = hd, sd = sd, thumb = thumb, duration = duration, width = width, height = height, hasAudio = hasAudio,
        user = user, created = created, sources = sources.unpack().toSet(), tags = tags.unpack(), views = views, likes = likes,
        nicheIds = nicheIds.unpack(), sexuality = sexuality.unpack(), verified = verified, description = description,
        colIds = colIds.unpack().toSet(), copyFile = file,
    )

    companion object {
        const val LIBRARY = 0
        const val EXTRA = 1
        const val IMPORTED = 2
        fun of(c: Clip, kind: Int, pos: Int) = ClipRow(
            id = c.id, kind = kind, pos = pos, hd = c.hd, sd = c.sd, thumb = c.thumb, duration = c.duration,
            width = c.width, height = c.height, hasAudio = c.hasAudio, user = c.user, created = c.created,
            sources = c.sources.pack(), tags = c.tags.pack(), views = c.views, likes = c.likes,
            nicheIds = c.nicheIds.pack(), sexuality = c.sexuality.pack(), verified = c.verified,
            description = c.description, colIds = c.colIds.pack(), file = if (kind == IMPORTED) c.copyFile else null,
        )
    }
}

/** Your edits to a clip's info. A null column means "keep RedGifs' value". */
@Entity(tableName = "meta_override")
data class OverrideRow(@PrimaryKey val id: String, val user: String?, val category: String?, val tags: String?, val niches: String?) {
    fun toOverride() = MetaOverride(user, category, tags?.unpack(), niches?.unpack())
    companion object {
        fun of(id: String, o: MetaOverride) = OverrideRow(id, o.user, o.category, o.tags?.pack(), o.niches?.pack())
    }
}

/** An edited copy made in the app. */
@Entity(tableName = "edit_copy", indices = [Index("originalId")])
data class CopyRow(@PrimaryKey val id: String, val originalId: String, val file: String, val note: String,
                   val created: Long, val duration: Double, val hasAudio: Boolean) {
    fun toCopy() = EditCopy(id, originalId, file, note, created, duration, hasAudio)
    companion object { fun of(c: EditCopy) = CopyRow(c.id, c.originalId, c.file, c.note, c.created, c.duration, c.hasAudio) }
}

/** Clips you deleted and don't want downloaded again. */
@Entity(tableName = "hidden")
data class HiddenRow(@PrimaryKey val id: String)

/** Download history: one row per clip and quality that was ever saved. */
@Entity(tableName = "saved", primaryKeys = ["id", "quality"])
data class SavedRow(val id: String, val quality: String, val at: Long)

/** A download that failed, with the reason shown in the app. */
@Entity(tableName = "failure")
data class FailureRow(@PrimaryKey val id: String, val reason: String, val firstAt: Long, val lastAt: Long, val attempts: Int)

/** Size and SHA-256 of each finished file (finds byte-identical duplicates). */
@Entity(tableName = "file_index", indices = [Index("sha")])
data class FileRow(@PrimaryKey val name: String, val size: Long, val sha: String)

@Entity(tableName = "niche")
data class NicheRow(@PrimaryKey val id: String, val name: String)

/** Creator profile; [key] is the lower-case username. */
@Entity(tableName = "performer")
data class PerformerRow(@PrimaryKey val key: String, val username: String, val name: String, val verified: Boolean,
                        val followers: Long, val avatar: String?) {
    fun toPerformer() = Performer(username, name, verified, followers, avatar)
    companion object { fun of(p: Performer) = PerformerRow(p.username.lowercase(), p.username, p.name, p.verified, p.followers, p.avatar) }
}

/** Your RedGifs collections, cached so they're listed offline. */
@Entity(tableName = "collection")
data class CollectionRow(@PrimaryKey val id: String, val pos: Int, val name: String, val count: Int, val thumb: String?)

/** Collections (or "liked") that are kept in sync automatically. */
@Entity(tableName = "watched")
data class WatchedRow(@PrimaryKey val key: String)

/** One finished sync, for the history list. */
@Entity(tableName = "sync_record")
data class SyncRow(@PrimaryKey val time: Long, val total: Int, val added: Int, val removed: Int, val saved: Int)

/** Clips that arrived in a sync and that you haven't opened yet ("New"). */
@Entity(tableName = "unseen")
data class UnseenRow(@PrimaryKey val id: String)

/** The download queue the worker picks up ("<id>" or "<id> HD"/"<id> SD"). */
@Entity(tableName = "queue")
data class QueueRow(@PrimaryKey val pos: Int, val entry: String)

@Dao
interface Store {
    // clips
    @Query("SELECT * FROM clip WHERE kind = :kind ORDER BY pos") fun clips(kind: Int): List<ClipRow>
    @Insert(onConflict = OnConflictStrategy.REPLACE) fun putClips(rows: List<ClipRow>)
    @Query("DELETE FROM clip WHERE kind = :kind") fun clearClips(kind: Int)
    @Query("DELETE FROM clip WHERE id IN (:ids)") fun deleteClips(ids: List<String>)

    // new (unseen) clips
    @Query("SELECT id FROM unseen") fun unseen(): List<String>
    @Insert(onConflict = OnConflictStrategy.IGNORE) fun putUnseen(rows: List<UnseenRow>)
    @Query("DELETE FROM unseen WHERE id IN (:ids)") fun deleteUnseen(ids: List<String>)
    @Query("DELETE FROM unseen") fun clearUnseen()

    // info edits
    @Query("SELECT * FROM meta_override") fun overrides(): List<OverrideRow>
    @Insert(onConflict = OnConflictStrategy.REPLACE) fun putOverrides(rows: List<OverrideRow>)
    @Query("DELETE FROM meta_override WHERE id IN (:ids)") fun deleteOverrides(ids: List<String>)

    // edited copies
    @Query("SELECT * FROM edit_copy ORDER BY created") fun copies(): List<CopyRow>
    @Insert(onConflict = OnConflictStrategy.REPLACE) fun putCopies(rows: List<CopyRow>)
    @Query("DELETE FROM edit_copy WHERE id IN (:ids)") fun deleteCopies(ids: List<String>)

    // hidden
    @Query("SELECT id FROM hidden") fun hidden(): List<String>
    @Insert(onConflict = OnConflictStrategy.IGNORE) fun putHidden(rows: List<HiddenRow>)
    @Query("DELETE FROM hidden WHERE id IN (:ids)") fun deleteHidden(ids: List<String>)

    // download history
    @Query("SELECT * FROM saved") fun saved(): List<SavedRow>
    @Insert(onConflict = OnConflictStrategy.IGNORE) fun putSaved(rows: List<SavedRow>)
    @Query("DELETE FROM saved WHERE id IN (:ids)") fun deleteSaved(ids: List<String>)
    @Query("DELETE FROM saved") fun clearSaved()

    // failures
    @Query("SELECT * FROM failure") fun failures(): List<FailureRow>
    @Insert(onConflict = OnConflictStrategy.REPLACE) fun putFailures(rows: List<FailureRow>)
    @Query("DELETE FROM failure WHERE id IN (:ids)") fun deleteFailures(ids: List<String>)

    // file index
    @Query("SELECT * FROM file_index") fun files(): List<FileRow>
    @Insert(onConflict = OnConflictStrategy.REPLACE) fun putFiles(rows: List<FileRow>)
    @Query("DELETE FROM file_index WHERE name IN (:names)") fun deleteFiles(names: List<String>)

    // niches, performers, collections, watch list
    @Query("SELECT * FROM niche") fun niches(): List<NicheRow>
    @Insert(onConflict = OnConflictStrategy.REPLACE) fun putNiches(rows: List<NicheRow>)
    @Query("SELECT * FROM performer") fun performers(): List<PerformerRow>
    @Insert(onConflict = OnConflictStrategy.REPLACE) fun putPerformers(rows: List<PerformerRow>)
    @Query("SELECT * FROM collection ORDER BY pos") fun collections(): List<CollectionRow>
    @Insert(onConflict = OnConflictStrategy.REPLACE) fun putCollections(rows: List<CollectionRow>)
    @Query("DELETE FROM collection") fun clearCollections()
    @Query("SELECT `key` FROM watched") fun watched(): List<String>
    @Insert(onConflict = OnConflictStrategy.IGNORE) fun putWatched(rows: List<WatchedRow>)
    @Query("DELETE FROM watched WHERE `key` = :key") fun deleteWatched(key: String)

    // sync history (newest first, the last 100 are kept)
    @Query("SELECT * FROM sync_record ORDER BY time DESC") fun syncs(): List<SyncRow>
    @Insert(onConflict = OnConflictStrategy.REPLACE) fun putSyncs(rows: List<SyncRow>)
    @Query("DELETE FROM sync_record WHERE time NOT IN (SELECT time FROM sync_record ORDER BY time DESC LIMIT 100)") fun trimSyncs()

    // download queue
    @Query("SELECT entry FROM queue ORDER BY pos") fun queue(): List<String>
    @Insert(onConflict = OnConflictStrategy.REPLACE) fun putQueue(rows: List<QueueRow>)
    @Query("DELETE FROM queue") fun clearQueue()
}

@Database(
    version = 2, exportSchema = false,
    entities = [ClipRow::class, OverrideRow::class, CopyRow::class, HiddenRow::class, SavedRow::class, FailureRow::class,
        FileRow::class, NicheRow::class, PerformerRow::class, CollectionRow::class, WatchedRow::class, SyncRow::class, QueueRow::class,
        UnseenRow::class],
)
abstract class AppDb : RoomDatabase() {
    abstract fun store(): Store

    companion object {
        /** SQLite accepts a limited number of values per statement; long ID lists go in slices. */
        fun <T> sliced(items: kotlin.collections.Collection<T>, f: (List<T>) -> Unit) = items.chunked(500).forEach(f)

        /** 11.0 to 12.0: imported files (clip.file) and the "New" list. */
        private val V1_2 = object : androidx.room.migration.Migration(1, 2) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE clip ADD COLUMN file TEXT")
                db.execSQL("CREATE TABLE IF NOT EXISTS `unseen` (`id` TEXT NOT NULL, PRIMARY KEY(`id`))")
            }
        }

        fun open(ctx: Context): AppDb =
            Room.databaseBuilder(ctx.applicationContext, AppDb::class.java, "redgrab.db")
                // The library is small and is read once at start, like the files were; writes are single rows.
                .allowMainThreadQueries()
                .addMigrations(V1_2)
                .build()
    }
}
