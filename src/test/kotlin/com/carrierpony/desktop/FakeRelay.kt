// FakeRelay.kt
// An in-process stand-in for the CarrierPony relay, enough of it to carry a conversation: the
// challenge, device registration, fingerprint-routed send/inbox/ack, and the sealed-sender
// endpoints. It checks none of the signatures (the real relay's job, and RelayClient's own
// concern); what it does model faithfully is ROUTING, because that is what these tests are
// about: a fingerprint-addressed message fans out to every registered device of that
// fingerprint, each device acks its own copy, and a sealed message goes to whichever device
// registered the mailbox.

package com.carrierpony.desktop

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import org.json.JSONArray
import org.json.JSONObject
import java.net.InetSocketAddress
import java.util.UUID
import java.util.concurrent.Executors

class FakeRelay : AutoCloseable {

    private class Stored(val id: String, val envelope: String, val mailbox: String?)

    private val lock = Any()
    private val devicesByFpr = HashMap<String, MutableSet<String>>()       // fpr -> device ids
    private val labels = HashMap<String, String>()                         // device id -> label
    private val queues = HashMap<String, MutableList<Stored>>()            // "fpr|device" or "sealed|device"
    private val mailboxOwner = HashMap<String, String>()                   // mailbox -> sealed device id
    val requestLog = mutableListOf<String>()

    private class Offer(val offererFpr: String, val offererPubkey: String, var responderFpr: String? = null, var responderPubkey: String? = null)
    private val offers = HashMap<String, Offer>()

    /** When set, pair/accept hands back THIS key instead of the offerer's: a relay in the middle. */
    @Volatile var swapKey: String? = null

    private val server: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        executor = Executors.newCachedThreadPool()
        createContext("/") { exchange -> handle(exchange) }
        start()
    }

    val baseURL: String get() = "http://127.0.0.1:${server.address.port}"

    fun labelOf(deviceId: String): String? = synchronized(lock) { labels[deviceId] }
    fun sealedSendCount(): Int = synchronized(lock) { requestLog.count { it == "/v1/sealed/send" } }
    fun legacySendCount(): Int = synchronized(lock) { requestLog.count { it == "/v1/send" } }

    override fun close() { server.stop(0) }

    private fun handle(exchange: HttpExchange) {
        val path = exchange.requestURI.path
        val body = JSONObject(String(exchange.requestBody.readBytes(), Charsets.UTF_8).ifBlank { "{}" })
        val (status, reply) = try {
            synchronized(lock) { requestLog.add(path); route(path, body) }
        } catch (e: Exception) {
            500 to JSONObject().put("error", "fake_relay_${e::class.simpleName}")
        }
        val bytes = reply.toString().toByteArray(Charsets.UTF_8)
        exchange.responseHeaders.add("Content-Type", "application/json")
        exchange.sendResponseHeaders(status, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }

    private fun ok(json: JSONObject = JSONObject()) = 200 to json

    private fun route(path: String, b: JSONObject): Pair<Int, JSONObject> = when (path) {
        "/v1/challenge" -> ok(JSONObject().put("nonce", UUID.randomUUID().toString()))

        "/v1/register-device" -> {
            devicesByFpr.getOrPut(b.getString("fpr")) { LinkedHashSet() }.add(b.getString("device_id"))
            b.optString("label").takeIf { it.isNotEmpty() }?.let { labels[b.getString("device_id")] = it }
            ok()
        }

        "/v1/send" -> {
            val to = b.getString("to_fpr")
            val id = UUID.randomUUID().toString()
            for (device in devicesByFpr[to].orEmpty()) {
                queues.getOrPut("$to|$device") { mutableListOf() }.add(Stored(id, b.getString("envelope"), null))
            }
            ok(JSONObject().put("message_id", id).put("expires_at", b.optLong("expires_at")))
        }

        "/v1/inbox" -> ok(JSONObject().put("messages", messages(queues["${b.getString("fpr")}|${b.getString("device_id")}"])))

        "/v1/ack" -> ok(JSONObject().put("acked", drop(queues["${b.getString("fpr")}|${b.getString("device_id")}"], b.getJSONArray("message_ids"))))

        "/v1/pair/offer" -> {
            val token = UUID.randomUUID().toString().replace("-", "")
            offers[token] = Offer(b.getString("fpr"), b.getString("pubkey"))
            ok(JSONObject().put("token", token).put("expires_in", 86_400))
        }

        "/v1/pair/accept" -> {
            val offer = offers[b.getString("token")]
            if (offer == null || offer.responderFpr != null) 404 to JSONObject().put("error", "unknown_token")
            else {
                offer.responderFpr = b.getString("fpr"); offer.responderPubkey = b.getString("pubkey")
                ok(JSONObject().put("offerer_fpr", offer.offererFpr).put("offerer_pubkey", swapKey ?: offer.offererPubkey))
            }
        }

        "/v1/pair/status" -> {
            val offer = offers[b.getString("token")]
            when {
                offer == null -> 404 to JSONObject().put("error", "unknown_token")
                offer.responderFpr == null -> ok(JSONObject().put("state", "pending"))
                else -> ok(JSONObject().put("state", "accepted")
                    .put("responder_fpr", offer.responderFpr).put("responder_pubkey", offer.responderPubkey))
            }
        }

        "/v1/sealed/register-device" -> ok()

        "/v1/sealed/register-mailboxes" -> {
            val boxes = b.getJSONArray("mailboxes")
            var registered = 0
            for (i in 0 until boxes.length()) {
                val address = boxes.getJSONObject(i).getString("mailbox")
                // First claim wins, as on the real relay.
                if (mailboxOwner.putIfAbsent(address, b.getString("device_id")) == null) registered++
            }
            ok(JSONObject().put("registered", registered))
        }

        "/v1/sealed/send" -> {
            val owner = mailboxOwner[b.getString("mailbox")]
            if (owner == null) { requestLog.add("/v1/sealed/send:UNKNOWN_MAILBOX"); 404 to JSONObject().put("error", "unknown_mailbox") }
            else {
                queues.getOrPut("sealed|$owner") { mutableListOf() }
                    .add(Stored(UUID.randomUUID().toString(), b.getString("envelope"), b.getString("mailbox")))
                ok()
            }
        }

        "/v1/sealed/inbox" -> ok(JSONObject().put("messages", messages(queues["sealed|${b.getString("device_id")}"])))

        "/v1/sealed/ack" -> ok(JSONObject().put("deleted", drop(queues["sealed|${b.getString("device_id")}"], b.getJSONArray("message_ids"))))

        else -> 404 to JSONObject().put("error", "not_found")
    }

    private fun messages(queue: List<Stored>?): JSONArray {
        val out = JSONArray()
        for (m in queue.orEmpty()) {
            val j = JSONObject()
                .put("message_id", m.id)
                .put("envelope", m.envelope)
                .put("received_at", "2026-01-01 00:00:00")
                .put("expires_at", "2036-01-01 00:00:00")
            if (m.mailbox != null) j.put("mailbox", m.mailbox)
            out.put(j)
        }
        return out
    }

    private fun drop(queue: MutableList<Stored>?, ids: JSONArray): Int {
        if (queue == null) return 0
        val wanted = (0 until ids.length()).map { ids.getString(it) }.toSet()
        val before = queue.size
        queue.removeAll { it.id in wanted }
        return before - queue.size
    }
}
