package com.carrierpony.app.net

import android.util.Base64
import com.ponydirect.PonyDirectKeyProvider
import com.ponydirect.PonyDirectSignal
import com.ponydirect.PonyDirectSignaling
import com.ponydirect.PonyDirectWan
import org.json.JSONArray
import org.json.JSONObject

/**
 * Encodes/decodes a PonyDirect signal onto CarrierPony's existing webrtc-* control
 * ops. Offer/answer carry the candidate list + session nonce as JSON in the `sdp`
 * field; ice carries one "ip:port" in the `candidate` field. Byte-compatible with
 * iOS WanSignalCodec.
 */
object WanSignalCodec {
    fun encode(s: PonyDirectSignal): Triple<String, String?, String?> = when (s.kind) {
        PonyDirectSignal.Kind.OFFER, PonyDirectSignal.Kind.ANSWER -> {
            val obj = JSONObject()
            obj.put("c", JSONArray(s.candidates))
            obj.put("s", Base64.encodeToString(s.sessionNonce ?: ByteArray(0), Base64.NO_WRAP))
            val op = if (s.kind == PonyDirectSignal.Kind.OFFER) "webrtc-offer" else "webrtc-answer"
            Triple(op, obj.toString(), null)
        }
        PonyDirectSignal.Kind.ICE -> Triple("webrtc-ice", null, s.candidate)
    }

    fun decode(op: String, sdp: String?, candidate: String?): PonyDirectSignal? = when (op) {
        "webrtc-offer", "webrtc-answer" -> {
            if (sdp == null) null else try {
                val obj = JSONObject(sdp)
                val arr = obj.optJSONArray("c") ?: JSONArray()
                val cands = ArrayList<String>(arr.length())
                for (i in 0 until arr.length()) cands.add(arr.getString(i))
                val nonce = obj.optString("s").takeIf { it.isNotEmpty() }
                    ?.let { Base64.decode(it, Base64.NO_WRAP) }
                val kind = if (op == "webrtc-offer") PonyDirectSignal.Kind.OFFER
                           else PonyDirectSignal.Kind.ANSWER
                PonyDirectSignal(kind, cands, nonce)
            } catch (e: Exception) {
                null
            }
        }
        "webrtc-ice" -> if (candidate == null) null
                        else PonyDirectSignal(PonyDirectSignal.Kind.ICE, candidate = candidate)
        else -> null
    }
}

/**
 * Thread-safe bridge between the app and the PonyDirect WAN transport (STUN +
 * authenticated UDP hole punching). PonyDirect carries opaque bytes and never sees
 * the app's raw keys; it calls back on its own threads, so key material is snapshotted
 * under a lock and outbound signals hop through a closure the app sets.
 */
class WanDirectBridge : PonyDirectKeyProvider, PonyDirectSignaling, PonyDirectWan.Delegate {

    private val lock = Any()
    private var keySnapshot: Map<String, ByteArray> = emptyMap()
    private val connected = HashSet<String>()

    /** Set by the app: relays one outbound signaling op (op, peerHex, sdp, candidate). */
    var sendOp: ((String, String, String?, String?) -> Unit)? = null
    /** Set by the app: current connected-peer count. */
    var onConnectedChange: ((Int) -> Unit)? = null
    /** Set by the app: a payload arrived on a direct path (M3 delivery). No-op in M2. */
    var payloadSink: ((String, ByteArray) -> Unit)? = null

    var wan: PonyDirectWan? = null
        private set

    /** Refresh the per-pair key snapshot (peerHex lower -> 32B pair key). */
    fun updateKeys(keys: Map<String, ByteArray>) {
        synchronized(lock) { keySnapshot = keys }
    }

    /** Bring the WAN transport up. Returns false if the UDP socket could not bind. */
    fun enable(stunHost: String, stunPort: Int): Boolean {
        if (wan != null) return true
        return try {
            val w = PonyDirectWan(PonyDirectWan.StunServer(stunHost, stunPort), this, this)
            w.delegate = this
            wan = w
            true
        } catch (e: Exception) {
            false
        }
    }

    fun disable() {
        wan = null
        synchronized(lock) { connected.clear() }
        onConnectedChange?.invoke(0)
    }

    /** Open a path as the initiator. The app calls this only for the peer whose
     *  fingerprint sorts after ours, so exactly one side offers. */
    fun openPath(peerHex: String) {
        wan?.open(peerHex.lowercase(), PonyDirectWan.Role.INITIATOR)
    }

    /** Feed an incoming signaling op parsed from a sealed control op. */
    fun handleIncoming(peerHex: String, op: String, sdp: String?, candidate: String?) {
        val w = wan ?: return
        val signal = WanSignalCodec.decode(op, sdp, candidate) ?: return
        w.handleSignal(signal, peerHex.lowercase())
    }

    val connectedCount: Int get() = synchronized(lock) { connected.size }

    /** The initiator role check needs our own fingerprint; the app sets it on enable. */
    var selfHex: String? = null

    /** True when a direct WAN path to this peer is live. Also lazily starts a path the
     *  first time we are asked to reach an initiator peer, so paths exist only for the
     *  contacts actually messaged rather than every contact at once. Idempotent. */
    fun canReach(peerHex: String): Boolean {
        val w = wan ?: return false
        val peer = peerHex.lowercase()
        if (w.stateOf(peer) == PonyDirectWan.PathState.CONNECTED) return true
        val mine = selfHex
        if (mine != null && mine < peer) w.open(peer, PonyDirectWan.Role.INITIATOR)
        return false
    }

    /** Deliver over the direct path, resolving true only once every chunk is acked
     *  (or false if there is no path, it is oversized, or the ARQ deadline passes). */
    suspend fun sendDirect(payload: ByteArray, peerHex: String): Boolean {
        val w = wan ?: return false
        return kotlinx.coroutines.suspendCancellableCoroutine { cont ->
            val queued = w.sendPayload(payload, peerHex.lowercase()) { delivered ->
                if (cont.isActive) cont.resumeWith(Result.success(delivered))
            }
            if (!queued && cont.isActive) cont.resumeWith(Result.success(false))
        }
    }

    // PonyDirectKeyProvider
    override fun pairKey(peerID: String): ByteArray? =
        synchronized(lock) { keySnapshot[peerID.lowercase()] }

    // PonyDirectSignaling
    override fun sendSignal(signal: PonyDirectSignal, peerID: String) {
        val (op, sdp, candidate) = WanSignalCodec.encode(signal)
        sendOp?.invoke(op, peerID, sdp, candidate)
    }

    // PonyDirectWan.Delegate
    override fun onPathState(peerID: String, state: PonyDirectWan.PathState) {
        val count = synchronized(lock) {
            if (state == PonyDirectWan.PathState.CONNECTED) connected.add(peerID)
            else connected.remove(peerID)
            connected.size
        }
        onConnectedChange?.invoke(count)
    }

    override fun onPayload(peerID: String, payload: ByteArray) {
        payloadSink?.invoke(peerID, payload)
    }
}
