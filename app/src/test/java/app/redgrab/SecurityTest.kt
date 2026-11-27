package app.redgrab

import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Stored sign-ins, the app lock's timing, the start-up sync rule and deleted clips. */
class SecurityTest {

    /** Preferences kept in memory, standing in for a file on the phone. */
    private class MemoryPrefs : SharedPreferences {
        val map = HashMap<String, Any?>()
        override fun getAll(): MutableMap<String, *> = map
        override fun getString(key: String, def: String?): String? = map[key] as? String ?: def
        override fun getStringSet(key: String, def: MutableSet<String>?): MutableSet<String>? = def
        override fun getInt(key: String, def: Int) = map[key] as? Int ?: def
        override fun getLong(key: String, def: Long) = map[key] as? Long ?: def
        override fun getFloat(key: String, def: Float) = map[key] as? Float ?: def
        override fun getBoolean(key: String, def: Boolean) = map[key] as? Boolean ?: def
        override fun contains(key: String) = key in map
        override fun registerOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) {}
        override fun unregisterOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) {}
        override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
            val todo = ArrayList<() -> Unit>()
            override fun putString(k: String, v: String?) = apply { todo += { map[k] = v } }
            override fun putStringSet(k: String, v: MutableSet<String>?) = this
            override fun putInt(k: String, v: Int) = apply { todo += { map[k] = v } }
            override fun putLong(k: String, v: Long) = apply { todo += { map[k] = v } }
            override fun putFloat(k: String, v: Float) = apply { todo += { map[k] = v } }
            override fun putBoolean(k: String, v: Boolean) = apply { todo += { map[k] = v } }
            override fun remove(k: String) = apply { todo += { map.remove(k) } }
            override fun clear() = apply { todo += { map.clear() } }
            override fun commit(): Boolean { todo.forEach { it() }; return true }
            override fun apply() { commit() }
        }
    }

    /** Stands in for the Keystore: reversible, tied to the entry's name, and can be made to fail. */
    private class FakeBox(var broken: Boolean = false) : SecretBox {
        override fun seal(name: String, plain: ByteArray): ByteArray {
            if (broken) error("no keystore")
            return SecretStore.pack(ByteArray(12) { 7 }, (name + "|").toByteArray() + plain.map { (it.toInt() xor 0x5A).toByte() })
        }
        override fun open(name: String, sealed: ByteArray): ByteArray {
            if (broken) error("no keystore")
            val (_, body) = SecretStore.unpack(sealed)
            val prefix = (name + "|").toByteArray()
            require(body.size >= prefix.size && body.copyOfRange(0, prefix.size).contentEquals(prefix)) { "wrong entry" }
            return body.copyOfRange(prefix.size, body.size).map { (it.toInt() xor 0x5A).toByte() }.toByteArray()
        }
    }

    @Test fun secretsAreStoredEncryptedAndReadBack() {
        val file = MemoryPrefs()
        SecretStore(file, FakeBox())["token"] = "eyJ.secret.value"
        // What is on "disk" is not the token.
        assertFalse(file.map.values.any { (it as? String)?.contains("secret") == true })
        // A new run (nothing in memory) reads it back.
        assertEquals("eyJ.secret.value", SecretStore(file, FakeBox())["token"])
        // Removing it removes the stored copy too.
        SecretStore(file, FakeBox()).also { it["token"] = null; assertNull(it["token"]) }
        assertTrue(file.map.isEmpty())
    }

    @Test fun oldReadableSecretsAreMovedAndErased() {
        val plain = MemoryPrefs().apply { map["token"] = "old-token"; map["redditRefresh"] = "old-refresh"; map["columns"] = 3 }
        val file = MemoryPrefs()
        val store = SecretStore(file, FakeBox())
        assertEquals(2, store.adopt(plain, listOf("token", "redditAccess", "redditRefresh", "githubToken")))
        assertEquals("old-token", store["token"])
        assertEquals("old-refresh", store["redditRefresh"])
        // Nothing readable is left behind; ordinary settings stay.
        assertEquals(setOf("columns"), plain.map.keys)
        // A second run finds nothing to move.
        assertEquals(0, SecretStore(file, FakeBox()).adopt(plain, listOf("token")))
    }

    @Test fun anUnreadableSecretIsDroppedNotGuessed() {
        val file = MemoryPrefs()
        SecretStore(file, FakeBox())["token"] = "abc"
        // An entry copied under another name doesn't open.
        file.map["githubToken"] = file.map["token"]
        assertNull(SecretStore(file, FakeBox())["githubToken"])
        assertFalse("githubToken" in file.map)
        // Rubbish in the file doesn't crash.
        file.map["redditAccess"] = "not base64 !!"
        assertNull(SecretStore(file, FakeBox())["redditAccess"])
    }

    @Test fun withoutAKeystoreNothingIsWrittenReadable() {
        val file = MemoryPrefs()
        val store = SecretStore(file, FakeBox(broken = true))
        store["token"] = "abc"
        assertEquals("abc", store["token"]) // usable for this run
        assertTrue(file.map.isEmpty())      // but never written out
    }

    @Test fun appLockTiming() {
        // Off: never.
        assertFalse(AppLock.shouldLock(false, -1, 0, 100_000))
        // Just started: always.
        assertTrue(AppLock.shouldLock(true, -1, 900, 100_000))
        // Back after less than the chosen time: no. After it: yes.
        assertFalse(AppLock.shouldLock(true, 30_000, 60, 100_000))
        assertTrue(AppLock.shouldLock(true, 60_000, 60, 100_000))
        // "At once" locks on any return, except right after unlocking (the unlock screen itself hides the app).
        assertTrue(AppLock.shouldLock(true, 10, 0, 100_000))
        assertFalse(AppLock.shouldLock(true, 10, 0, 500))
    }

    @Test fun startUpSyncOnlyWhenTheLastOneIsOld() {
        val now = 1_000_000_000_000L
        val hour = 3_600_000L
        assertTrue(syncIsFresh(now - 2 * hour, 6, now))
        assertFalse(syncIsFresh(now - 7 * hour, 6, now))
        assertFalse(syncIsFresh(0, 6, now))             // never synced
        assertFalse(syncIsFresh(now - hour, 0, now))    // "Always"
        assertFalse(syncIsFresh(now + hour, 6, now))    // the clock was set back
    }

    @Test fun deletedClipsAreNotRetriedOrCountedAsFailed() {
        val gone = Failure(DownloadWorker.reasonFor(GoneException("RedGifs")), 1, 1, 1, dead = true)
        assertEquals("No longer on RedGifs", gone.reason)
        assertTrue(gone.gaveUp)
        val s = LibraryState(failures = mapOf("a" to gone, "b" to Failure("Connection lost", 1, 1, 1)))
        assertEquals(setOf("b"), s.failed)
        assertEquals(setOf("a"), s.dead)
    }
}
