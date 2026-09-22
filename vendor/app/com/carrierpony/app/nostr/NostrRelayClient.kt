package com.carrierpony.app.nostr

import org.json.JSONArray
import org.json.JSONObject

/** The Nostr kind CarrierPony posts sealed envelopes under (regular/stored range). */
object NostrKind { const val MAILBOX = 1314 }

/**
 * Reconnect backoff shared with the read loops: a capped exponential delay with jitter.
 * A drop after a healthy stretch (>= STABLE_MS up) resets the backoff so a normal
 * disconnect reconnects promptly; rapid flapping backs off. Mirrors iOS NostrReconnect.
 */
object NostrReconnect {
    const val BASE_MS = 1000L
    const val CAP_MS = 30000L
    const val STABLE_MS = 20000L
    const val MAX_ATTEMPT = 5

    fun delayMs(attempt: Int): Long {
        val capped = minOf(BASE_MS shl attempt, CAP_MS)
        return (capped * (0.8 + Math.random() * 0.4)).toLong()
    }
}

/**
 * A relay connection speaking the NIP-01 client messages over [NostrWebSocket]:
 * EVENT to publish, REQ/CLOSE to subscribe, and a blocking read loop that dispatches
 * EVENT / OK / EOSE / NOTICE / CLOSED to callbacks. One instance per relay. The socket
 * is blocking, so connect() and readLoop() run on Dispatchers.IO.
 */
class NostrRelayClient(val url: String) {
    private val ws = NostrWebSocket(url)
    @Volatile private var running = false

    var onEvent: ((subId: String, event: JSONObject) -> Unit)? = null
    var onOk: ((eventId: String, accepted: Boolean, message: String) -> Unit)? = null
    var onEose: ((subId: String) -> Unit)? = null
    var onNotice: ((message: String) -> Unit)? = null
    var onClosed: ((subId: String, message: String) -> Unit)? = null

    fun connect() = ws.connect()
    fun publish(eventJson: String) = ws.sendText("[\"EVENT\",$eventJson]")
    fun subscribe(subId: String, filterJson: String) = ws.sendText("[\"REQ\",${quote(subId)},$filterJson]")
    fun closeSub(subId: String) = ws.sendText("[\"CLOSE\",${quote(subId)}]")
    fun close() { running = false; ws.close() }

    /** Blocking dispatch loop; returns when the socket closes or close() is called. */
    fun readLoop() {
        running = true
        while (running) {
            val msg = ws.readText() ?: break
            dispatch(msg)
        }
    }

    private fun dispatch(msg: String) {
        val arr = try { JSONArray(msg) } catch (_: Exception) { return }
        when (arr.optString(0)) {
            "EVENT" -> if (arr.length() >= 3) onEvent?.invoke(arr.getString(1), arr.getJSONObject(2))
            "OK" -> if (arr.length() >= 3) onOk?.invoke(arr.getString(1), arr.optBoolean(2), arr.optString(3, ""))
            "EOSE" -> if (arr.length() >= 2) onEose?.invoke(arr.getString(1))
            "NOTICE" -> onNotice?.invoke(arr.optString(1, ""))
            "CLOSED" -> if (arr.length() >= 2) onClosed?.invoke(arr.getString(1), arr.optString(2, ""))
        }
    }

    companion object {
        private fun quote(s: String) = JSONObject.quote(s)

        /** Filter for our own inbound window: {"kinds":[1314],"#t":[addr,...]}. */
        fun mailboxFilter(addresses: List<String>): String {
            val f = JSONObject()
            f.put("kinds", JSONArray(listOf(NostrKind.MAILBOX)))
            f.put("#t", JSONArray(addresses))
            return f.toString()
        }

        /** Filter matching one event by id: {"ids":[id]}. */
        fun idFilter(eventId: String): String =
            JSONObject().put("ids", JSONArray(listOf(eventId))).toString()
    }
}
