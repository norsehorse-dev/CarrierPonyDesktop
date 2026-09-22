// DesktopGroupKeyStore.kt
// CarrierPony Desktop. Twin of Android's GroupKeyStore: the AES-256 group keys, one per group
// and epoch, sealed in a SecureKV. Same class name and public API as the Android store.

package com.carrierpony.app.messaging

import com.carrierpony.desktop.SecureKV
import java.security.SecureRandom
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec

class GroupKeyStore(private val kv: SecureKV) {

    /** A fresh random AES-256 group key. */
    fun newKey(): SecretKey {
        val raw = ByteArray(32)
        SecureRandom().nextBytes(raw)
        return SecretKeySpec(raw, "AES")
    }

    /** Wrap raw key bytes (as delivered in a group-key payload) into a SecretKey. */
    fun keyFromRaw(raw: ByteArray): SecretKey = SecretKeySpec(raw, "AES")

    fun store(key: SecretKey, groupID: String, epoch: Int) {
        kv.putBytes(name(groupID, epoch), key.encoded)
    }

    fun key(groupID: String, epoch: Int): SecretKey? = try {
        kv.getBytes(name(groupID, epoch))?.let { SecretKeySpec(it, "AES") }
    } catch (e: Exception) {
        null
    }

    fun deleteGroup(groupID: String, throughEpoch: Int) {
        kv.removeAll((0..throughEpoch).map { name(groupID, it) })
    }

    /** Wipe every stored group key (sign-out). */
    fun purge() {
        kv.clear()
    }

    private fun name(g: String, e: Int) = "gk_${g}_$e"
}
