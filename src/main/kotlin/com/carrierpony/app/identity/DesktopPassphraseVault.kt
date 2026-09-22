// DesktopPassphraseVault.kt
// CarrierPony Desktop. Twin of Android's PassphraseVault: stores the passphrase of an IMPORTED,
// passphrase-protected identity so the app can use that key while unlocked. Not to be confused
// with com.carrierpony.desktop.Vault, the launch passphrase that seals this store and the others.

package com.carrierpony.app.identity

import com.carrierpony.desktop.SecureKV

class PassphraseVault(private val kv: SecureKV) {

    fun store(passphrase: String, fingerprint: String): Boolean = try {
        kv.putString(entry(fingerprint), passphrase)
        true
    } catch (e: Exception) {
        false
    }

    fun read(fingerprint: String): String? = try {
        kv.getString(entry(fingerprint))
    } catch (e: Exception) {
        null
    }

    fun delete(fingerprint: String) {
        kv.remove(entry(fingerprint))
    }

    private fun entry(fingerprint: String) = "cp.passphrase.$fingerprint"
}
