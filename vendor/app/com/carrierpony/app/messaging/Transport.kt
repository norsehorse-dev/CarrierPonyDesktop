// Transport.kt
// CarrierPony Android
//
// What carries a sealed envelope from us to a peer. The relay is the default
// store-and-forward path; LAN-direct and future transports (SMS, Meshtastic,
// Nostr) are alternatives. The sealed envelope, crypto, and pair state stay
// ABOVE this seam in ChatStore; a Transport only decides how the opaque bytes
// reach the peer. See CarrierPony-2.1-Transport-LANDirect-Design.md.

package com.carrierpony.app.messaging

import com.carrierpony.app.crypto.Fingerprint
import com.carrierpony.app.relay.RelayClient
import com.carrierpony.app.relay.RelayException

enum class TransportID { RELAY, LAN_DIRECT, WAN_DIRECT, NOSTR, SMS }

data class TransportCapabilities(
    val storeAndForward: Boolean,
    val revealsIP: Boolean,
    val worksOffline: Boolean,
)

interface Transport {
    val id: TransportID
    val capabilities: TransportCapabilities

    /** True if this transport can deliver to that peer right now (relay: always;
     *  LAN: only a peer currently discovered on the network). */
    fun canReach(peer: Fingerprint): Boolean

    /** Deliver one sealed envelope. Returns true if delivered, false to let the
     *  caller fall through to the next transport; throws on a hard error, which
     *  the relay (the last transport) surfaces to the user exactly as before.
     *  mailbox is the sealed address ChatStore computed once for this message; the
     *  store-and-forward transports post to it and the direct transports ignore it. */
    suspend fun send(envelope: ByteArray, to: Fingerprint, mailbox: String?, expiresAt: Long, silent: Boolean): Boolean
}

/** The default transport: the relay's store-and-forward path. It takes the sealed
 *  mailbox address ChatStore computed for the message and posts the envelope to it. */
class RelayTransport(
    private val relay: RelayClient,
) : Transport {

    override val id = TransportID.RELAY
    override val capabilities = TransportCapabilities(storeAndForward = true, revealsIP = false, worksOffline = false)

    override fun canReach(peer: Fingerprint) = true

    override suspend fun send(envelope: ByteArray, to: Fingerprint, mailbox: String?, expiresAt: Long, silent: Boolean): Boolean {
        if (mailbox != null) {
            try {
                relay.sealedSend(mailbox, envelope, expiresAt, silent)
                return true
            } catch (e: RelayException) {
                // The peer has not registered that address (it has not polled since it learned
                // our key, or we ran past its window). A relay that reports this lets the message
                // fall back to the fingerprint path instead of vanishing; a relay that still
                // answers ok for unknown mailboxes never reaches here.
                if (e.status != 404) throw e
            }
        }
        relay.send(envelope, to, expiresAt, silent)
        return true
    }
}

/** LAN-direct as a Transport (M3b). Wraps LanDiscovery so ChatStore can treat
 *  direct LAN delivery like any other transport. Non-store-and-forward, reveals
 *  this device's IP to the peer, works with no internet. The relay stays the
 *  authority; this only accelerates. */
class LanDirectTransport(
    private val discovery: com.carrierpony.app.net.LanDiscovery,
) : Transport {
    override val id = TransportID.LAN_DIRECT
    override val capabilities = TransportCapabilities(storeAndForward = false, revealsIP = true, worksOffline = true)

    override fun canReach(peer: Fingerprint): Boolean = discovery.canReachLan(peer.hex)

    override suspend fun send(envelope: ByteArray, to: Fingerprint, mailbox: String?, expiresAt: Long, silent: Boolean): Boolean =
        discovery.deliver(to.hex, envelope)
}

/** WAN-direct as a Transport (2.2 M3). Wraps the PonyDirect hole-punch bridge so
 *  ChatStore treats direct internet delivery like any other transport. The relay
 *  stays authoritative; this accelerates, and can carry the whole message when the
 *  relay is unreachable, since send() resolves only on confirmed delivery. */
class WanDirectTransport(
    private val bridge: com.carrierpony.app.net.WanDirectBridge,
) : Transport {
    override val id = TransportID.WAN_DIRECT
    override val capabilities = TransportCapabilities(storeAndForward = false, revealsIP = true, worksOffline = false)

    override fun canReach(peer: Fingerprint): Boolean = bridge.canReach(peer.hex)

    override suspend fun send(envelope: ByteArray, to: Fingerprint, mailbox: String?, expiresAt: Long, silent: Boolean): Boolean =
        bridge.sendDirect(envelope, to.hex)
}
