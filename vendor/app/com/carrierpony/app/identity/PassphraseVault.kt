// PassphraseVault.kt
// CarrierPony Android
//
// Stores the passphrase for an imported (passphrase-protected) identity,
// sealed under an Android Keystore AES-GCM key — the same mechanism as
// IdentityStore. The key material itself stays encrypted under this
// passphrase at rest; the app holds a session copy only while unlocked
// (LockManager drops it on lock).
//
// Platform difference vs iOS, deliberately accepted: the iOS vault binds
// each Keychain read to a biometric check (.userPresence with an LAContext).
// Android's equivalent (Keystore user-auth-bound keys + CryptoObject) adds
// substantial ceremony; here the app lock gates the UI and the session copy
// is dropped on lock, while the vault entry itself is Keystore-sealed and
// unreadable off-device. Revisit if a hardware-bound read is ever required.

package com.carrierpony.app.identity

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlin.io.encoding.Base64

class PassphraseVault(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences("cp.vault", Context.MODE_PRIVATE)

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build()
        )
        return generator.generateKey()
    }

    fun store(passphrase: String, fingerprint: String): Boolean {
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key())
            val sealed = cipher.doFinal(passphrase.toByteArray(Charsets.UTF_8))
            prefs.edit()
                .putString(entry(fingerprint), Base64.Default.encode(cipher.iv) + ":" + Base64.Default.encode(sealed))
                .apply()
            true
        } catch (e: Exception) {
            false
        }
    }

    fun read(fingerprint: String): String? {
        return try {
            val stored = prefs.getString(entry(fingerprint), null) ?: return null
            val parts = stored.split(":")
            if (parts.size != 2) return null
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, Base64.Default.decode(parts[0])))
            String(cipher.doFinal(Base64.Default.decode(parts[1])), Charsets.UTF_8)
        } catch (e: Exception) {
            null
        }
    }

    fun delete(fingerprint: String) {
        prefs.edit().remove(entry(fingerprint)).apply()
    }

    private fun entry(fingerprint: String) = "cp.passphrase.$fingerprint"

    private companion object {
        const val ALIAS = "com.carrierpony.passphrase.wrap"
    }
}
