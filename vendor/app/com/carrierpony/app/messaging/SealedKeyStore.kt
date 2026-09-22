// SealedKeyStore.kt
// CarrierPony Android
//
// Keychain-equivalent for sealed sender, the Android counterpart of the iOS
// SealedKeyStore. The device_key and each per-pair state are sealed with
// AES-GCM under a non-exportable Android Keystore master (same design as
// GroupKeyStore / IdentityStore) and stored in a private SharedPreferences.

package com.carrierpony.app.messaging

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.carrierpony.app.AppConfig
import com.carrierpony.app.relay.MailboxCrypto
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlin.io.encoding.Base64

class SealedKeyStore(context: Context, private val identityHex: String) {

    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    init { migrateLegacyToNamespace() }

    // Every stored blob is namespaced by the active identity fingerprint, so each
    // account holds its OWN sealed device (device_id/device_key) and pair/group
    // state. Without this, two accounts sharing one sealed device would let the
    // relay link them directly. The first time this runs it moves the pre-account
    // sealed state (stored under un-namespaced keys) into the active identity's
    // namespace and removes the legacy keys, so the primary account keeps its
    // working sealed device and a later second account starts fresh.
    private fun ns(name: String) = "$identityHex.$name"

    private fun migrateLegacyToNamespace() {
        val editor = prefs.edit()
        var changed = false
        for (key in prefs.all.keys.toList()) {
            val prefix = when {
                key.startsWith("iv_") -> "iv_"
                key.startsWith("v_")  -> "v_"
                else -> continue
            }
            val name = key.removePrefix(prefix)
            if (name.contains('.')) continue            // already namespaced
            val target = "$prefix${ns(name)}"
            if (!prefs.contains(target)) {
                prefs.getString(key, null)?.let { editor.putString(target, it) }
            }
            editor.remove(key)                          // legacy key gone either way
            changed = true
        }
        if (changed) editor.apply()
    }

    /** This device's sealed auth key (64 hex), minted once and persisted. */
    fun deviceKey(): String {
        readString(DEVICE_KEY)?.let { return it }
        val hex = MailboxCrypto.hex(MailboxCrypto.randomBytes(32))
        writeString(DEVICE_KEY, hex)
        return hex
    }

    /** This device's sealed device_id (32 hex), independent of the push/legacy
     *  device id and stored in the SAME sealed store as device_key, so the two
     *  always share a lifecycle: if the store is wiped or the wrapped key becomes
     *  unreadable, both regenerate together and the relay sees a fresh device,
     *  instead of a new key colliding with a stale first-claimed device_id.
     *  Keeping it separate from the push id also stops the relay linking this
     *  "anonymous" sealed device to the fingerprint-routed one. */
    fun deviceId(): String {
        readString(DEVICE_ID)?.let { return it }
        // Seed from the existing push/legacy id the FIRST time, so a device that
        // already registered sealed mailboxes under that id keeps owning them (the
        // mailbox addresses are key-derived and first-claimed by whichever
        // device_id registered them; switching ids would orphan those claims).
        // From here on it lives in this store, sharing device_key's lifecycle.
        val seed = if (anyOtherAccountHasSealedDevice()) MailboxCrypto.hex(MailboxCrypto.randomBytes(16))
                   else AppConfig.deviceID(appContext)
        writeString(DEVICE_ID, seed)
        return seed
    }

    // True when another account already holds a sealed device_id on this install.
    // Only the first sealed account may seed its id from the shared push id; a
    // later account must start with a fresh random id, or two accounts would
    // present the same sealed device to the relay and be linkable.
    private fun anyOtherAccountHasSealedDevice(): Boolean {
        val mine = "iv_" + ns(DEVICE_ID)
        val suffix = ".$DEVICE_ID"
        for (key in prefs.all.keys) {
            if (key.startsWith("iv_") && key.endsWith(suffix) && key != mine) return true
        }
        return false
    }

    /** Mint a FRESH random sealed device_id (keeping device_key), so the next
     *  register-device lands on a device row the relay has never claimed. Recovers
     *  from a relay 401 (the seeded id was first-claimed under a key this device
     *  no longer holds); safe because a device hitting 401 never registered any
     *  mailboxes, so there are no claims to orphan. Also unlinks the sealed id
     *  from the push id going forward. Pair/group state untouched. */
    fun rotateDeviceIdentity() {
        writeString(DEVICE_ID, MailboxCrypto.hex(MailboxCrypto.randomBytes(16)))
    }

    fun newPairKey(): ByteArray = MailboxCrypto.randomBytes(32)

    fun pair(peerHex: String): SealedPairState? =
        readString(pairKey(peerHex))?.let { SealedPairState.fromJson(it) }

    fun setPair(peerHex: String, state: SealedPairState) =
        writeString(pairKey(peerHex), state.toJson())

    fun group(groupID: String): SealedGroupState? =
        readString(groupKey(groupID))?.let { SealedGroupState.fromJson(it) }

    fun setGroup(groupID: String, state: SealedGroupState) =
        writeString(groupKey(groupID), state.toJson())

    fun deleteGroupState(groupID: String) {
        val n = ns(groupKey(groupID))
        prefs.edit().remove("iv_$n").remove("v_$n").apply()
    }

    private fun groupKey(g: String) = "group_$g"

    fun wakeToken(): String? = AppConfig.storedWakeToken(appContext, identityHex)

    /** Wipe only THIS identity's sealed state (device, pairs, groups), leaving
     *  any other account's namespace intact. */
    fun purge() {
        val editor = prefs.edit()
        val mine = "$identityHex."
        for (key in prefs.all.keys.toList()) {
            if (key.startsWith("iv_$mine") || key.startsWith("v_$mine")) editor.remove(key)
        }
        editor.apply()
    }

    // ── Encrypted string storage under the Keystore master ─────────────

    private fun writeString(name: String, value: String) {
        val n = ns(name)
        val cipher = Cipher.getInstance(TRANSFORM)
        cipher.init(Cipher.ENCRYPT_MODE, master())
        val blob = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        prefs.edit()
            .putString("iv_$n", Base64.Default.encode(cipher.iv))
            .putString("v_$n", Base64.Default.encode(blob))
            .apply()
    }

    private fun readString(name: String): String? {
        val n = ns(name)
        val iv = prefs.getString("iv_$n", null) ?: return null
        val blob = prefs.getString("v_$n", null) ?: return null
        return try {
            val cipher = Cipher.getInstance(TRANSFORM)
            cipher.init(Cipher.DECRYPT_MODE, master(), GCMParameterSpec(TAG_BITS, Base64.Default.decode(iv)))
            String(cipher.doFinal(Base64.Default.decode(blob)), Charsets.UTF_8)
        } catch (e: Exception) {
            null
        }
    }

    private fun pairKey(peerHex: String) = "pair_${peerHex.lowercase()}"

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
        private const val PREFS = "carrierpony.sealed"
        private const val KEYSTORE = "AndroidKeyStore"
        private const val ALIAS = "carrierpony.sealed.master"
        private const val TRANSFORM = "AES/GCM/NoPadding"
        private const val TAG_BITS = 128
        private const val DEVICE_KEY = "device_key"
        private const val DEVICE_ID = "device_id"
    }
}
