// DesktopIdentityStore.kt
// CarrierPony Desktop. Twin of Android's IdentityStore (excluded from the vendored compile: it
// is AndroidKeyStore + SharedPreferences). Same class name and public API, so code written
// against the Android store reads the same here. The record format is the vendored
// IdentityRecord, identical on every platform; only where the sealed blob lives differs.
// Here each record is one SecureKV entry under the launch-passphrase vault.

package com.carrierpony.app.identity

import com.carrierpony.desktop.SecureKV
import org.json.JSONArray

class IdentityStore(private val kv: SecureKV) {

    /** Fingerprint hexes of all stored accounts, in insertion order. */
    fun list(): List<String> {
        val raw = kv.getString(KEY_IDS) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { arr.getString(it) }
        } catch (e: Exception) { emptyList() }
    }

    fun selected(): String? = kv.getString(KEY_SELECTED)?.takeIf { it in list() } ?: list().firstOrNull()

    fun setSelected(fprHex: String) {
        if (fprHex in list()) kv.putString(KEY_SELECTED, fprHex)
    }

    /** The selected account's identity, null when none exists. */
    fun load(): Identity? = selected()?.let { load(it) }

    /** One account's identity by fingerprint. Throws when its record is present but cannot be
     *  opened or parsed. */
    fun load(fprHex: String): Identity? {
        if (!kv.contains(recordKey(fprHex))) return null
        val json = try {
            kv.getString(recordKey(fprHex))
        } catch (e: Exception) {
            throw IdentityStoreException("identity unseal failed: ${e.message}")
        } ?: return null
        return IdentityRecord.decode(json) ?: throw IdentityStoreException("identity record malformed")
    }

    /** Add or replace an account and make it the selected one. */
    fun save(identity: Identity) {
        val fpr = identity.fingerprint.hex
        try {
            kv.putString(recordKey(fpr), IdentityRecord.encode(identity))
        } catch (e: Exception) {
            throw IdentityStoreException("identity seal failed: ${e.message}")
        }
        val ids = list().toMutableList()
        if (fpr !in ids) ids.add(fpr)
        kv.putString(KEY_IDS, JSONArray(ids).toString())
        kv.putString(KEY_SELECTED, fpr)
    }

    /** Remove one account. If it was selected, the first remaining account becomes selected.
     *  Returns the new selected fingerprint, or null if none are left. */
    fun delete(fprHex: String): String? {
        val wasSelected = kv.getString(KEY_SELECTED) == fprHex
        val ids = list().toMutableList().also { it.remove(fprHex) }
        kv.remove(recordKey(fprHex))
        kv.putString(KEY_IDS, JSONArray(ids).toString())
        val newSelected = if (wasSelected) ids.firstOrNull() else kv.getString(KEY_SELECTED)
        if (newSelected != null) kv.putString(KEY_SELECTED, newSelected) else kv.remove(KEY_SELECTED)
        return newSelected
    }

    /** Wipe every account. */
    fun deleteAll() {
        kv.removeAll(list().map { recordKey(it) } + listOf(KEY_IDS, KEY_SELECTED))
    }

    private fun recordKey(fpr: String) = "record.$fpr"

    private companion object {
        const val KEY_IDS = "ids"
        const val KEY_SELECTED = "selected"
    }
}
