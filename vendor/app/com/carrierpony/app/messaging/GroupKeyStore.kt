// GroupKeyStore.kt
// CarrierPony Android
//
// Per-epoch group symmetric keys, the Android counterpart of the iOS Keychain-backed
// GroupKeyStore. Each raw AES-256 group key is sealed with AES-GCM under a
// non-exportable Android Keystore key (same design as IdentityStore) and the sealed
// blob + IV live in a private SharedPreferences file, keyed by (groupID, epoch).
// Without the Keystore key the stored blobs are noise, and the key never leaves the
// device.

package com.carrierpony.app.messaging

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.io.encoding.Base64

class GroupKeyStore(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** A fresh random AES-256 group key. */
    fun newKey(): SecretKey {
        val raw = ByteArray(32)
        SecureRandom().nextBytes(raw)
        return SecretKeySpec(raw, "AES")
    }

    /** Wrap raw key bytes (as delivered in a group-key payload) into a SecretKey. */
    fun keyFromRaw(raw: ByteArray): SecretKey = SecretKeySpec(raw, "AES")

    fun store(key: SecretKey, groupID: String, epoch: Int) {
        val cipher = Cipher.getInstance(TRANSFORM)
        cipher.init(Cipher.ENCRYPT_MODE, master())
        val blob = cipher.doFinal(key.encoded)
        prefs.edit()
            .putString(ivKey(groupID, epoch), Base64.Default.encode(cipher.iv))
            .putString(blobKey(groupID, epoch), Base64.Default.encode(blob))
            .apply()
    }

    fun key(groupID: String, epoch: Int): SecretKey? {
        val iv = prefs.getString(ivKey(groupID, epoch), null) ?: return null
        val blob = prefs.getString(blobKey(groupID, epoch), null) ?: return null
        return try {
            val cipher = Cipher.getInstance(TRANSFORM)
            cipher.init(Cipher.DECRYPT_MODE, master(), GCMParameterSpec(TAG_BITS, Base64.Default.decode(iv)))
            SecretKeySpec(cipher.doFinal(Base64.Default.decode(blob)), "AES")
        } catch (e: Exception) {
            null
        }
    }

    fun deleteGroup(groupID: String, throughEpoch: Int) {
        val editor = prefs.edit()
        for (e in 0..throughEpoch) {
            editor.remove(ivKey(groupID, e)).remove(blobKey(groupID, e))
        }
        editor.apply()
    }

    /** Wipe every stored group key (sign-out). */
    fun purge() {
        prefs.edit().clear().apply()
    }

    private fun ivKey(g: String, e: Int) = "iv_${g}_$e"
    private fun blobKey(g: String, e: Int) = "gk_${g}_$e"

    private fun master(): SecretKey {
        val ks = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        gen.init(
            KeyGenParameterSpec.Builder(
                ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build()
        )
        return gen.generateKey()
    }

    companion object {
        private const val PREFS = "carrierpony.groupkeys"
        private const val KEYSTORE = "AndroidKeyStore"
        private const val ALIAS = "carrierpony.groupkeys.master"
        private const val TRANSFORM = "AES/GCM/NoPadding"
        private const val TAG_BITS = 128
    }
}
