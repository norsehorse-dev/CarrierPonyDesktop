// IdentityStore.kt
// CarrierPony Android
//
// Persistence for the single primary identity, the Android counterpart of the
// iOS Keychain item in Core/App/Identity.swift. The identity record (which
// contains the secret key) is sealed with AES-256-GCM under a key that lives
// in the Android Keystore — generated on-device, non-exportable, and never
// present in app memory as raw bytes. The sealed blob and its IV sit in a
// private SharedPreferences file; without the Keystore key they are noise.
//
// Matching the iOS accessibility choice (afterFirstUnlockThisDeviceOnly): the
// Keystore key is hardware-bound to this device, so the identity does not
// travel with a raw prefs backup. allowBackup semantics for the rest of the
// app's data are unaffected.

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
import org.json.JSONArray

class IdentityStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    init { migrateLegacyIfNeeded() }

    // ── Multi-identity API ─────────────────────────────────────────────

    /** Fingerprint hexes of all stored accounts, in insertion order. */
    fun list(): List<String> {
        val raw = prefs.getString(KEY_IDS, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { arr.getString(it) }
        } catch (e: Exception) { emptyList() }
    }

    fun selected(): String? = prefs.getString(KEY_SELECTED, null)?.takeIf { it in list() } ?: list().firstOrNull()

    fun setSelected(fprHex: String) {
        if (fprHex in list()) prefs.edit().putString(KEY_SELECTED, fprHex).apply()
    }

    /** The selected account's identity, null when none exists. */
    fun load(): Identity? = selected()?.let { load(it) }

    /** One account's identity by fingerprint. Throws when its blob is present but
     *  cannot be opened or parsed. */
    fun load(fprHex: String): Identity? {
        val ivB64 = prefs.getString(ivKey(fprHex), null) ?: return null
        val blobB64 = prefs.getString(blobKey(fprHex), null) ?: return null
        val json = try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, obtainKey(), GCMParameterSpec(TAG_BITS, Base64.Default.decode(ivB64)))
            String(cipher.doFinal(Base64.Default.decode(blobB64)), Charsets.UTF_8)
        } catch (e: Exception) {
            throw IdentityStoreException("identity unseal failed: ${e.message}")
        }
        return IdentityRecord.decode(json) ?: throw IdentityStoreException("identity record malformed")
    }

    /** Add or replace an account and make it the selected one (installing an
     *  identity also activates it). */
    fun save(identity: Identity) {
        val fpr = identity.fingerprint.hex
        val plaintext = IdentityRecord.encode(identity).toByteArray(Charsets.UTF_8)
        val sealed = try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, obtainKey())
            cipher.iv to cipher.doFinal(plaintext)
        } catch (e: Exception) {
            throw IdentityStoreException("identity seal failed: ${e.message}")
        }
        val ids = list().toMutableList()
        if (fpr !in ids) ids.add(fpr)
        prefs.edit()
            .putString(ivKey(fpr), Base64.Default.encode(sealed.first))
            .putString(blobKey(fpr), Base64.Default.encode(sealed.second))
            .putString(KEY_IDS, JSONArray(ids).toString())
            .putString(KEY_SELECTED, fpr)
            .apply()
    }

    /** Remove one account. If it was selected, the first remaining account becomes
     *  selected. Returns the new selected fingerprint (or null if none left). */
    fun delete(fprHex: String): String? {
        val ids = list().toMutableList().also { it.remove(fprHex) }
        val editor = prefs.edit()
            .remove(ivKey(fprHex)).remove(blobKey(fprHex))
            .putString(KEY_IDS, JSONArray(ids).toString())
        val newSelected = if (prefs.getString(KEY_SELECTED, null) == fprHex) ids.firstOrNull() else prefs.getString(KEY_SELECTED, null)
        if (newSelected != null) editor.putString(KEY_SELECTED, newSelected) else editor.remove(KEY_SELECTED)
        editor.apply()
        return newSelected
    }

    /** Wipe every account (factory reset / full sign-out). */
    fun deleteAll() {
        val editor = prefs.edit()
        for (fpr in list()) editor.remove(ivKey(fpr)).remove(blobKey(fpr))
        editor.remove(KEY_IDS).remove(KEY_SELECTED)
        editor.remove(KEY_IV).remove(KEY_BLOB)   // any straggler legacy keys
        editor.apply()
    }

    private fun ivKey(fpr: String) = "iv.$fpr"
    private fun blobKey(fpr: String) = "blob.$fpr"

    // One-time move from the v1 single-identity layout (bare iv/blob) to the
    // per-fingerprint layout. Reads the old identity, re-saves it under its
    // fingerprint, selects it, and drops the legacy keys.
    private fun migrateLegacyIfNeeded() {
        if (prefs.contains(KEY_IDS)) return
        val ivB64 = prefs.getString(KEY_IV, null) ?: return
        val blobB64 = prefs.getString(KEY_BLOB, null) ?: return
        try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, obtainKey(), GCMParameterSpec(TAG_BITS, Base64.Default.decode(ivB64)))
            val json = String(cipher.doFinal(Base64.Default.decode(blobB64)), Charsets.UTF_8)
            val identity = IdentityRecord.decode(json) ?: return
            val fpr = identity.fingerprint.hex
            prefs.edit()
                .putString(ivKey(fpr), ivB64)
                .putString(blobKey(fpr), blobB64)
                .putString(KEY_IDS, JSONArray(listOf(fpr)).toString())
                .putString(KEY_SELECTED, fpr)
                .remove(KEY_IV).remove(KEY_BLOB)
                .apply()
        } catch (e: Exception) {
            // leave the legacy keys in place; load() will surface the failure
        }
    }

    // ── Keystore ───────────────────────────────────────────────────────

    private fun obtainKey(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return generator.generateKey()
    }

    private companion object {
        const val PREFS_NAME = "cp.identity"
        const val KEY_IV = "iv"
        const val KEY_BLOB = "blob"
        const val KEY_IDS = "ids"
        const val KEY_SELECTED = "selected"
        const val KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "com.carrierpony.identity.wrap"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val TAG_BITS = 128
    }
}
