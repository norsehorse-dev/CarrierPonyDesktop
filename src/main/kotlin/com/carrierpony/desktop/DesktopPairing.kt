// DesktopPairing.kt
// CarrierPony Desktop. Pairing over the relay: publish an offer, accept someone else's, and
// finish offers of ours that were accepted while nobody was looking. Upstream this lives inside
// Android's AppModel, which is not vendored, so it is written again here against the same
// vendored pieces (Invite, PairingSupport, RelayClient, ContactStore) and the same rules:
//
//  - The accepter refuses a key that does not hash to the fingerprint the invite carried.
//  - The offerer refuses a responder key that does not hash to the fingerprint the relay names.
//  - An invite shown face to face (a QR on this screen, scanned by a phone) completes VERIFIED.
//    One that travelled over some other channel completes UNVERIFIED until the safety numbers
//    are compared.
//  - An offer is remembered until it completes or expires, and swept before every inbox
//    refresh, so the first messages from a new contact open on the pass that discovers them.
//
// DRIFT WATCH: if AppModel's pairing section changes upstream, this file changes with it.

package com.carrierpony.desktop

import com.carrierpony.app.crypto.Fingerprint
import com.carrierpony.app.messaging.ChatStore
import com.carrierpony.app.messaging.Contact
import com.carrierpony.app.messaging.ContactStore
import com.carrierpony.app.messaging.TrustLevel
import com.carrierpony.app.pairing.Invite
import com.carrierpony.app.pairing.PairingSupport
import com.carrierpony.app.relay.RelayClient
import com.carrierpony.app.relay.RelayException
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/** Pairing failures, with the same user-facing copy as the phones. */
sealed class PairingException(message: String) : Exception(message) {
    class MalformedInvite : PairingException("That doesn't look like a CarrierPony invite.")
    class KeyMismatch : PairingException("The key the relay returned doesn't match the invite. Pairing was refused to keep you safe.")
    class OwnInvite : PairingException("That is your own invite.")
}

class DesktopPairing(
    private val identity: Fingerprint,
    private val armoredPublicKey: String,
    private val relay: RelayClient,
    private val contacts: ContactStore,
    private val store: () -> ChatStore,
    private val prefs: DesktopPrefs,
    private val profileName: () -> String?,
    private val sweepThrottleMillis: Long = 30_000,
) {
    /** An offer this identity published and has not yet collected the acceptance for. */
    data class Pending(val token: String, val name: String?, val expiresAt: Long?, val inPerson: Boolean)

    private val lock = Any()
    private val completed = ConcurrentHashMap<String, Contact>()
    @Volatile private var lastSweep = 0L

    private val prefsKey = "cp.pendinginvites.${identity.hex}"

    fun pending(): List<Pending> = synchronized(lock) { loadPending() }

    /** Publish an offer and remember it. Returns the invite to show (as text, a link or a QR)
     *  and when the relay will forget it, in epoch seconds. */
    suspend fun createInvite(inPerson: Boolean = false): Pair<Invite, Long?> {
        val response = relay.pairOffer(pubkey = armoredPublicKey)
        val expiresAt = if (response.expiresIn > 0) System.currentTimeMillis() / 1000 + response.expiresIn else null
        val name = profileName()
        synchronized(lock) { savePending(loadPending() + Pending(response.token, name, expiresAt, inPerson)) }
        return Invite.create(token = response.token, fingerprint = identity, name = name) to expiresAt
    }

    /** Accept an invite someone sent. [trust] is VERIFIED only when it was read off their screen
     *  in person; a pasted invite stays UNVERIFIED. */
    suspend fun acceptInvite(text: String, trust: TrustLevel = TrustLevel.UNVERIFIED): Contact =
        acceptInvite(Invite.decode(text) ?: throw PairingException.MalformedInvite(), trust)

    suspend fun acceptInvite(invite: Invite, trust: TrustLevel = TrustLevel.UNVERIFIED): Contact {
        val invitedFpr = invite.fingerprint ?: throw PairingException.MalformedInvite()
        if (invitedFpr == identity) throw PairingException.OwnInvite()
        val response = relay.pairAccept(token = invite.t, pubkey = armoredPublicKey)
        if (response.offererFpr.uppercase() != invitedFpr.hex) throw PairingException.KeyMismatch()
        val contact = PairingSupport.consistentContact(invitedFpr.hex, response.offererPubkey, trust)
            ?: throw PairingException.KeyMismatch()
        val named = contact.copy(name = invite.n)
        contacts.add(named)
        store().sendProfile(name = profileName(), to = named)
        return named
    }

    /** Poll one offer of ours. The new contact once the peer has accepted, null while waiting. */
    suspend fun pollInvite(token: String): Contact? {
        completed[token]?.let { return it }
        val status = relay.pairStatus(token = token)
        if (status.state != "accepted") {
            if (status.state == "expired") forget(token)
            return null
        }
        val responderFpr = status.responderFpr ?: return null
        val responderPubkey = status.responderPubkey ?: return null
        return finish(token, responderFpr, responderPubkey)
    }

    /** Check every remembered offer. Wired into ChatStore's refresh, so it is throttled. */
    suspend fun sweep(force: Boolean = false) {
        val snapshot = synchronized(lock) {
            val list = loadPending()
            if (list.isEmpty()) return
            val now = System.currentTimeMillis()
            if (!force && now - lastSweep < sweepThrottleMillis) return
            lastSweep = now
            list
        }
        val nowSec = System.currentTimeMillis() / 1000
        for (p in snapshot) {
            if (p.expiresAt != null && p.expiresAt <= nowSec) { forget(p.token); continue }
            try {
                pollInvite(p.token)
            } catch (e: PairingException.KeyMismatch) {
                forget(p.token)                                  // can never become valid
            } catch (e: RelayException) {
                if (e.status in 400..499) forget(p.token)        // the relay forgot the token
            } catch (e: Exception) {
                // offline or a 5xx: keep it for the next sweep
            }
        }
    }

    // Exactly once per token, so the on-screen poll and the background sweep cannot double-add.
    private suspend fun finish(token: String, responderFpr: String, responderPubkey: String): Contact {
        completed[token]?.let { return it }
        val inPerson = synchronized(lock) { loadPending().firstOrNull { it.token == token }?.inPerson ?: false }
        val trust = if (inPerson) TrustLevel.VERIFIED else TrustLevel.UNVERIFIED
        val contact = PairingSupport.consistentContact(responderFpr, responderPubkey, trust)
            ?: run { forget(token); throw PairingException.KeyMismatch() }
        completed[token] = contact
        contacts.add(contact)
        if (inPerson) contacts.markVerified(contact.fingerprint)
        forget(token)
        store().sendProfile(name = profileName(), to = contact)
        return contact
    }

    private fun forget(token: String) = synchronized(lock) {
        savePending(loadPending().filterNot { it.token == token })
    }

    private fun loadPending(): List<Pending> {
        val raw = prefs.getString(prefsKey) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                Pending(
                    token = o.getString("token"),
                    name = if (o.has("name") && !o.isNull("name")) o.getString("name") else null,
                    expiresAt = if (o.has("expiresAt") && !o.isNull("expiresAt")) o.getLong("expiresAt") else null,
                    inPerson = o.optBoolean("inPerson", false)
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun savePending(list: List<Pending>) {
        val arr = JSONArray()
        for (p in list) {
            val o = JSONObject().put("token", p.token).put("inPerson", p.inPerson)
            if (p.name != null) o.put("name", p.name)
            if (p.expiresAt != null) o.put("expiresAt", p.expiresAt)
            arr.put(o)
        }
        prefs.putString(prefsKey, if (list.isEmpty()) null else arr.toString())
    }
}
