// DesktopSealedKeyStore.kt
// CarrierPony Desktop. Twin of Android's SealedKeyStore: this device's sealed-sender identity
// (device_id, device_key) and the per-pair and per-group delivery state, namespaced by account
// so two accounts never present the same sealed device to the relay. The state classes
// themselves (SealedPairState, SealedGroupState, SealedRoute) are the vendored ones from
// SealedModels.kt, so their JSON cannot drift from the phones.
//
// One deliberate difference from Android: deviceId() is always a fresh random id. Android seeds
// the FIRST account's sealed id from its legacy push id, so installs that registered mailboxes
// before sealed ids existed keep owning them. A desktop install has no such history, and an
// unlinked id is the better privacy default.

package com.carrierpony.app.messaging

import com.carrierpony.app.relay.MailboxCrypto
import com.carrierpony.desktop.SecureKV

class SealedKeyStore(private val kv: SecureKV, private val identityHex: String) {

    private fun ns(name: String) = "$identityHex.$name"

    /** This device's sealed auth key (64 hex), minted once and persisted. */
    fun deviceKey(): String {
        kv.getString(ns(DEVICE_KEY))?.let { return it }
        val hex = MailboxCrypto.hex(MailboxCrypto.randomBytes(32))
        kv.putString(ns(DEVICE_KEY), hex)
        return hex
    }

    /** This device's sealed device_id (32 hex), sharing device_key's lifecycle. */
    fun deviceId(): String {
        kv.getString(ns(DEVICE_ID))?.let { return it }
        val id = MailboxCrypto.hex(MailboxCrypto.randomBytes(16))
        kv.putString(ns(DEVICE_ID), id)
        return id
    }

    /** Mint a FRESH random sealed device_id (keeping device_key), so the next register-device
     *  lands on a device row the relay has never claimed. Recovers from a relay 401. */
    fun rotateDeviceIdentity() {
        kv.putString(ns(DEVICE_ID), MailboxCrypto.hex(MailboxCrypto.randomBytes(16)))
    }

    fun newPairKey(): ByteArray = MailboxCrypto.randomBytes(32)

    fun pair(peerHex: String): SealedPairState? =
        readOrNull(pairKey(peerHex))?.let { SealedPairState.fromJson(it) }

    fun setPair(peerHex: String, state: SealedPairState) =
        kv.putString(ns(pairKey(peerHex)), state.toJson())

    fun group(groupID: String): SealedGroupState? =
        readOrNull(groupKey(groupID))?.let { SealedGroupState.fromJson(it) }

    fun setGroup(groupID: String, state: SealedGroupState) =
        kv.putString(ns(groupKey(groupID)), state.toJson())

    fun deleteGroupState(groupID: String) {
        kv.remove(ns(groupKey(groupID)))
    }

    /** Desktop has no push channel, so there is never a wake token to hand the relay. */
    fun wakeToken(): String? = null

    /** Wipe only THIS identity's sealed state, leaving any other account's namespace intact. */
    fun purge() {
        val mine = "$identityHex."
        kv.removeAll(kv.names().filter { it.startsWith(mine) })
    }

    // Android's readString returns null when a blob will not open; match that, so one damaged
    // entry degrades to "no state for this peer" and heals through the normal resync path.
    private fun readOrNull(name: String): String? = try {
        kv.getString(ns(name))
    } catch (e: com.carrierpony.desktop.VaultLockedException) {
        throw e
    } catch (e: Exception) {
        null
    }

    private fun pairKey(peerHex: String) = "pair_${peerHex.lowercase()}"
    private fun groupKey(g: String) = "group_$g"

    private companion object {
        const val DEVICE_KEY = "device_key"
        const val DEVICE_ID = "device_id"
    }
}
