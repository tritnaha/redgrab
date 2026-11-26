package app.redgrab

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.util.Base64
import androidx.core.content.edit
import java.security.KeyStore
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Encrypts and decrypts one named secret. The name is bound to the result, so one entry can't be passed off as another. */
interface SecretBox {
    fun seal(name: String, plain: ByteArray): ByteArray
    fun open(name: String, sealed: ByteArray): ByteArray
}

/**
 * AES-256 in GCM mode with a key that is created in, and never leaves, the Android Keystore (the
 * phone's secure hardware where it has one). The app can ask the Keystore to encrypt and decrypt
 * but can't read the key itself, so the stored secrets are useless to anything that only gets
 * hold of the app's files. Each value gets a fresh random nonce, stored in front of it.
 *
 * The key isn't tied to the screen lock: background sync has to be able to use the sign-in while
 * the phone is locked.
 */
class KeystoreBox(private val alias: String = "redgrab.secrets.v1") : SecretBox {
    private fun key(): SecretKey {
        val ks = KeyStore.getInstance(STORE).apply { load(null) }
        (ks.getKey(alias, null) as? SecretKey)?.let { return it }
        val spec = KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build()
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, STORE).apply { init(spec) }.generateKey()
    }

    @Synchronized override fun seal(name: String, plain: ByteArray): ByteArray {
        val c = Cipher.getInstance(MODE).apply { init(Cipher.ENCRYPT_MODE, key()); updateAAD(name.toByteArray()) }
        return SecretStore.pack(c.iv, c.doFinal(plain))
    }

    @Synchronized override fun open(name: String, sealed: ByteArray): ByteArray {
        val (iv, body) = SecretStore.unpack(sealed)
        val c = Cipher.getInstance(MODE).apply { init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv)); updateAAD(name.toByteArray()) }
        return c.doFinal(body)
    }

    private companion object {
        const val STORE = "AndroidKeyStore"
        const val MODE = "AES/GCM/NoPadding"
    }
}

/**
 * Where sign-in secrets are kept: the RedGifs session token, Reddit's tokens and the GitHub token.
 * Each is stored encrypted (see [KeystoreBox]) in its own preferences file; nothing readable is
 * written to the phone. Decrypted values are held in memory while the app runs.
 *
 * If a value can't be decrypted (the key was lost, say after the app's data was copied from another
 * phone) it is dropped: you sign in again. If the Keystore can't be used at all, a secret is kept
 * only in memory for this run and never written out unencrypted.
 */
class SecretStore(private val sp: SharedPreferences, private val box: SecretBox) {
    private val cache = ConcurrentHashMap<String, String>()
    /** Names known to have no value, so the store isn't asked again for each "am I signed in?". */
    private val absent = ConcurrentHashMap.newKeySet<String>()

    operator fun get(name: String): String? {
        cache[name]?.let { return it }
        if (name in absent) return null
        val stored = sp.getString(name, null)
        val value = stored?.let {
            runCatching { String(box.open(name, Base64.getDecoder().decode(it))) }
                .onFailure { e -> AppLog.e("A stored sign-in ($name) couldn't be read and was dropped: ${e.javaClass.simpleName}"); sp.edit { remove(name) } }
                .getOrNull()
        }
        if (value == null) absent += name else cache[name] = value
        return value
    }

    operator fun set(name: String, value: String?) {
        if (value == null) {
            cache -= name; absent += name
            sp.edit { remove(name) }
            return
        }
        absent -= name
        cache[name] = value
        runCatching { Base64.getEncoder().encodeToString(box.seal(name, value.toByteArray())) }
            .onSuccess { sealed -> sp.edit { putString(name, sealed) } }
            .onFailure { e ->
                // Never fall back to writing it out readable.
                AppLog.e("A sign-in ($name) couldn't be stored securely and is kept for this run only: ${e.javaClass.simpleName}")
                sp.edit { remove(name) }
            }
    }

    /**
     * Moves secrets an older version stored readable in [plain] into this store and removes them
     * there. A value this store already holds wins. Returns how many were moved.
     */
    fun adopt(plain: SharedPreferences, names: List<String>): Int {
        var moved = 0
        for (name in names) {
            val old = plain.getString(name, null) ?: continue
            if (get(name) == null) { set(name, old); moved++ }
            // commit(), not apply(): the readable copy must be gone from the file before anything else happens.
            plain.edit(commit = true) { remove(name) }
        }
        return moved
    }

    companion object {
        /** Stored form: one byte with the nonce's length, the nonce, then the encrypted value with its check tag. */
        fun pack(iv: ByteArray, body: ByteArray): ByteArray = byteArrayOf(iv.size.toByte()) + iv + body

        fun unpack(sealed: ByteArray): Pair<ByteArray, ByteArray> {
            require(sealed.isNotEmpty()) { "empty" }
            val n = sealed[0].toInt()
            require(n in 12..16 && sealed.size > 1 + n) { "not a stored secret" }
            return sealed.copyOfRange(1, 1 + n) to sealed.copyOfRange(1 + n, sealed.size)
        }

        fun open(ctx: Context): SecretStore = SecretStore(ctx.getSharedPreferences("secrets", Context.MODE_PRIVATE), KeystoreBox())
    }
}
