// ContactStore.kt
// CarrierPony Android
//
// Persistent store of known contacts (peers you have paired with), ported
// from iOS Core/App/ContactStore.swift. Contacts are app state, not crypto:
// the crypto engine's resolver and the ChatStore both read from here.
// Persisted as a JSON array of small records in the app's private files dir.

package com.carrierpony.app.messaging

import com.carrierpony.app.crypto.Fingerprint
import com.carrierpony.app.crypto.PublicKey
import com.carrierpony.app.storage.AtRest
import com.carrierpony.core.CPArmor
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class ContactStore(private val storageDir: File) {

    // Contacts are per-account. The store is switchable: activate(fpr) points it
    // at that identity's file and reloads, so the same instance (and the flow the
    // UI observes) survives an account switch. Starts empty until activated.
    private val legacyURL = File(storageDir, "carrierpony-contacts.json")
    private var fileURL: File = legacyURL

    private val _contacts = MutableStateFlow<List<Contact>>(emptyList())
    val contactsFlow: StateFlow<List<Contact>> = _contacts.asStateFlow()

    val contacts: List<Contact> get() = _contacts.value

    /** Point the store at an identity's contacts and load them. Called on launch
     *  and on every account switch. The first account to activate inherits the
     *  pre-account shared file so a later second account starts empty. */
    fun activate(identityHex: String) {
        val perAccount = File(storageDir, "carrierpony-contacts-$identityHex.json")
        if (!perAccount.exists() && legacyURL.exists()) {
            try {
                legacyURL.copyTo(perAccount, overwrite = false)
                legacyURL.delete()
            } catch (e: Exception) {
                // best-effort; a failed move just means an empty contact list
            }
        }
        fileURL = perAccount
        load()
    }

    /** Delete this account's contacts file (used when an account is removed). */
    fun deleteFile(identityHex: String) {
        try { File(storageDir, "carrierpony-contacts-$identityHex.json").delete() } catch (e: Exception) {}
    }

    // ── Mutations ──────────────────────────────────────────────────────

    fun add(contact: Contact) {
        _contacts.value = _contacts.value.filter { it.fingerprint != contact.fingerprint } + contact
        persist()
    }

    fun remove(fingerprint: Fingerprint) {
        _contacts.value = _contacts.value.filter { it.fingerprint != fingerprint }
        persist()
    }

    fun removeAll() {
        _contacts.value = emptyList()
        persist()
    }

    /** Promote a contact to verified (the user compared the safety number
     *  out-of-band, or paired in person). */
    fun markVerified(fingerprint: Fingerprint) {
        update(fingerprint) { it.copy(trust = TrustLevel.VERIFIED) }
    }

    /** Set the name a peer told us about themselves (arrives via pairing or an
     *  encrypted profile message). Does not touch a local nickname. */
    fun setName(fingerprint: Fingerprint, name: String?) {
        val trimmed = name?.trim()?.takeIf { it.isNotEmpty() }
        update(fingerprint) { it.copy(name = trimmed) }
    }

    /** Set your local nickname for a contact. Overrides whatever name they sent. */
    fun setNickname(fingerprint: Fingerprint, nickname: String?) {
        val trimmed = nickname?.trim()?.takeIf { it.isNotEmpty() }
        update(fingerprint) { it.copy(nickname = trimmed) }
    }

    /** Set the phone number used for the SMS transport (foss). Local only. */
    fun setSmsNumber(fingerprint: Fingerprint, number: String?) {
        val trimmed = number?.trim()?.takeIf { it.isNotEmpty() }
        update(fingerprint) { it.copy(smsNumber = trimmed) }
    }

    private fun update(fingerprint: Fingerprint, transform: (Contact) -> Contact) {
        val current = _contacts.value
        val index = current.indexOfFirst { it.fingerprint == fingerprint }
        if (index < 0) return
        _contacts.value = current.toMutableList().also { it[index] = transform(it[index]) }
        persist()
    }

    // ── Lookups ────────────────────────────────────────────────────────

    fun contact(fingerprint: Fingerprint): Contact? =
        _contacts.value.firstOrNull { it.fingerprint == fingerprint }

    /** Raw (binary) public key bytes for a fingerprint, dearmored from the
     *  stored armored key. This is what the crypto engine's resolver wants. */
    fun publicKeyData(fingerprint: Fingerprint): ByteArray? =
        contact(fingerprint)?.let { CPArmor.dearmor(it.publicKey.armored) }

    // ── Persistence ────────────────────────────────────────────────────

    private fun load() {
        val text = try {
            fileURL.takeIf { it.exists() }?.let { AtRest.readText(it) }
        } catch (e: Exception) {
            null
        } ?: run { _contacts.value = emptyList(); return }   // no file: this account has no contacts yet
        val loaded = try {
            val array = JSONArray(text)
            (0 until array.length()).mapNotNull { index ->
                val record = array.getJSONObject(index)
                val fingerprint = Fingerprint.from(record.getString("fingerprint"))
                    ?: return@mapNotNull null
                Contact(
                    fingerprint = fingerprint,
                    publicKey = PublicKey(fingerprint, record.getString("armored")),
                    name = record.optString("name").takeIf { it.isNotEmpty() },
                    trust = TrustLevel.from(record.optString("trust", "unverified")),
                    nickname = record.optString("nickname").takeIf { it.isNotEmpty() },
                    smsNumber = record.optString("smsNumber").takeIf { it.isNotEmpty() }
                )
            }
        } catch (e: Exception) {
            _contacts.value = emptyList()
            return
        }
        _contacts.value = loaded
    }

    private fun persist() {
        val array = JSONArray()
        for (contact in _contacts.value) {
            val record = JSONObject()
            record.put("fingerprint", contact.fingerprint.hex)
            record.put("armored", contact.publicKey.armored)
            if (contact.name != null) record.put("name", contact.name)
            record.put("trust", contact.trust.wire)
            if (contact.nickname != null) record.put("nickname", contact.nickname)
            if (contact.smsNumber != null) record.put("smsNumber", contact.smsNumber)
            array.put(record)
        }
        try {
            fileURL.parentFile?.mkdirs()
            AtRest.writeText(fileURL, array.toString())
        } catch (e: Exception) {
            // Mirrors iOS's try? — persistence failure never crashes the app.
        }
    }
}
