// RelayClient.kt
// CarrierPony Android
//
// The client for api.carrierpony.com, ported endpoint-for-endpoint from iOS
// Core/Networking/RelayClient.swift. Every authenticated call runs the same
// two-step loop the relay expects: POST /v1/challenge for a nonce, sign the
// nonce with the identity's key (armored detached signature the relay
// verifies with GnuPG), and include fpr + nonce + sig in the request body.
// The relay never learns anything beyond fingerprints, sealed envelopes, and
// expiry times.
//
// Plain HttpURLConnection inside Dispatchers.IO — no HTTP dependency. Bodies
// are JSON; a non-200 reply raises RelayException with the server's error
// code when the body carries {"error": "..."}.

package com.carrierpony.app.relay

import com.carrierpony.app.crypto.CryptoEngine
import com.carrierpony.app.crypto.Fingerprint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import kotlin.io.encoding.Base64

class RelayClient(
    baseURL: String,
    private val crypto: CryptoEngine,
    private val deviceID: String
) {

    private val baseURL: String = baseURL.trimEnd('/')


    // ── Device registration ────────────────────────────────────────────

    suspend fun registerDevice(label: String? = null) {
        val nonce = challenge(crypto.fingerprint.hex)
        val sig = crypto.detachedSignature(nonce.toByteArray(Charsets.UTF_8))
        val body = JSONObject()
        body.put("fpr", crypto.fingerprint.hex)
        body.put("pubkey", crypto.armoredPublicKey())
        body.put("device_id", deviceID)
        body.put("nonce", nonce)
        body.put("sig", sig)
        if (label != null) body.put("label", label)
        post("/v1/register-device", body)
    }

    // ── Messaging ──────────────────────────────────────────────────────

    suspend fun send(envelope: ByteArray, to: Fingerprint, expiresAt: Long, silent: Boolean = false): SendResponse {
        val body = authBody()
        body.put("to_fpr", to.hex)
        body.put("envelope", Base64.Default.encode(envelope))
        body.put("expires_at", expiresAt)
        // Control messages and self-copies ask the relay for a background push
        // (no alert), so read receipts, profile syncs and multi-device echoes
        // don't buzz the recipient. Real messages leave this false.
        if (silent) body.put("silent", true)
        return SendResponse.from(post("/v1/send", body))
    }

    suspend fun inbox(): List<InboxMessage> {
        val body = authBody()
        body.put("device_id", deviceID)
        val response = post("/v1/inbox", body)
        val array = response.getJSONArray("messages")
        return (0 until array.length()).map { InboxMessage.from(array.getJSONObject(it)) }
    }

    suspend fun ack(messageIDs: List<String>): Int {
        val body = authBody()
        body.put("device_id", deviceID)
        body.put("message_ids", JSONArray(messageIDs))
        return post("/v1/ack", body).getInt("acked")
    }

    /** Register a push token for this device. The platform field tells the
     *  relay which pipe to use ("fcm" here, "apns" on iOS); relays predating
     *  the field treat an absent value as APNs, so iOS needs no change. */
    suspend fun registerPush(token: String, platform: String = "fcm") {
        val body = authBody()
        body.put("device_id", deviceID)
        body.put("push_token", token)
        body.put("platform", platform)
        post("/v1/register-push", body)
    }

    /** Store (or clear) this device's gateway wake token on the relay. An empty
     *  string clears it. The relay never sees the push token, only this opaque
     *  token it uses to nudge the gateway. */
    suspend fun registerWake(wakeToken: String) {
        val body = authBody()
        body.put("device_id", deviceID)
        body.put("wake_token", wakeToken)
        post("/v1/register-wake", body)
    }

    // ── Pairing ────────────────────────────────────────────────────────

    /** Publish a pairing offer carrying this identity's public key. Returns a
     *  one-time token the peer uses to accept. */
    suspend fun pairOffer(pubkey: String): PairOfferResponse {
        val body = authBody()
        body.put("pubkey", pubkey)
        return PairOfferResponse.from(post("/v1/pair/offer", body))
    }

    /** Accept an offer by token, submitting this identity's public key and
     *  receiving the offerer's key in return. */
    suspend fun pairAccept(token: String, pubkey: String): PairAcceptResponse {
        val body = authBody()
        body.put("token", token)
        body.put("pubkey", pubkey)
        return PairAcceptResponse.from(post("/v1/pair/accept", body))
    }

    /** Poll an offer this identity created. Once accepted, carries the
     *  responder's fingerprint and public key. */
    suspend fun pairStatus(token: String): PairStatusResponse {
        val body = authBody()
        body.put("token", token)
        return PairStatusResponse.from(post("/v1/pair/status", body))
    }

    // ── Reporting ──────────────────────────────────────────────────────

    suspend fun submitReport(
        reportedFingerprint: Fingerprint,
        category: String,
        description: String?,
        content: String?
    ) {
        val body = authBody()
        body.put("reported_fpr", reportedFingerprint.hex)
        body.put("category", category)
        if (!description.isNullOrEmpty()) body.put("description", description)
        if (!content.isNullOrEmpty()) body.put("content", content)
        post("/v1/report", body)
    }

    // ── Challenge auth ─────────────────────────────────────────────────

    // ── Sealed sender ──────────────────────────────────────────────────

    // Sealed calls carry their OWN device id (from SealedKeyStore), independent
    // of the push/legacy deviceID, so the relay cannot link the two and the
    // (device_id, device_key) pair shares one lifecycle.
    suspend fun sealedRegisterDevice(sealedDeviceId: String, deviceKey: String, wakeToken: String?) {
        val ts = System.currentTimeMillis() / 1000
        val body = JSONObject()
        body.put("device_id", sealedDeviceId)
        body.put("device_key", deviceKey)
        if (!wakeToken.isNullOrEmpty()) body.put("wake_token", wakeToken)
        body.put("ts", ts)
        body.put("auth", MailboxCrypto.deviceAuth(deviceKey, "register", sealedDeviceId, ts))
        post("/v1/sealed/register-device", body)
    }

    suspend fun sealedRegisterMailboxes(sealedDeviceId: String, deviceKey: String, mailboxes: JSONArray): Int {
        val ts = System.currentTimeMillis() / 1000
        val body = JSONObject()
        body.put("device_id", sealedDeviceId)
        body.put("ts", ts)
        body.put("auth", MailboxCrypto.deviceAuth(deviceKey, "mbx", sealedDeviceId, ts))
        body.put("mailboxes", mailboxes)
        return post("/v1/sealed/register-mailboxes", body).optInt("registered", 0)
    }

    suspend fun sealedSend(mailbox: String, envelope: ByteArray, expiresAt: Long, silent: Boolean = false) {
        val body = JSONObject()
        body.put("mailbox", mailbox)
        body.put("envelope", Base64.Default.encode(envelope))
        body.put("expires_at", expiresAt)
        if (silent) body.put("silent", true)
        post("/v1/sealed/send", body)
    }

    suspend fun sealedInbox(sealedDeviceId: String, deviceKey: String): List<SealedInboxMessage> {
        val ts = System.currentTimeMillis() / 1000
        val body = JSONObject()
        body.put("device_id", sealedDeviceId)
        body.put("ts", ts)
        body.put("auth", MailboxCrypto.deviceAuth(deviceKey, "inbox", sealedDeviceId, ts))
        val response = post("/v1/sealed/inbox", body)
        val array = response.getJSONArray("messages")
        return (0 until array.length()).map { SealedInboxMessage.from(array.getJSONObject(it)) }
    }

    suspend fun sealedAck(sealedDeviceId: String, deviceKey: String, messageIDs: List<String>): Int {
        val ts = System.currentTimeMillis() / 1000
        val body = JSONObject()
        body.put("device_id", sealedDeviceId)
        body.put("ts", ts)
        body.put("auth", MailboxCrypto.deviceAuth(deviceKey, "ack", sealedDeviceId, ts))
        body.put("message_ids", JSONArray(messageIDs))
        return post("/v1/sealed/ack", body).optInt("deleted", 0)
    }

    private suspend fun challenge(fpr: String): String {
        val body = JSONObject().put("fpr", fpr)
        return post("/v1/challenge", body).getString("nonce")
    }

    private suspend fun authBody(): JSONObject {
        val nonce = challenge(crypto.fingerprint.hex)
        val sig = crypto.detachedSignature(nonce.toByteArray(Charsets.UTF_8))
        val body = JSONObject()
        body.put("fpr", crypto.fingerprint.hex)
        body.put("nonce", nonce)
        body.put("sig", sig)
        return body
    }

    // ── Transport ──────────────────────────────────────────────────────

    private suspend fun post(path: String, body: JSONObject): JSONObject = withContext(Dispatchers.IO) {
        val connection = URL(baseURL + path).openConnection() as? HttpURLConnection
            ?: throw RelayException(-1, "bad_url")
        try {
            connection.requestMethod = "POST"
            connection.setRequestProperty("Content-Type", "application/json")
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.doOutput = true
            connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }

            val status = connection.responseCode
            val data = (if (status == 200) connection.inputStream else connection.errorStream)
                ?.use { it.readBytes() } ?: ByteArray(0)
            if (status != 200) {
                val code = try {
                    JSONObject(String(data, Charsets.UTF_8)).optString("error").takeIf { it.isNotEmpty() }
                } catch (e: Exception) {
                    null
                }
                throw RelayException(status, code)
            }
            JSONObject(String(data, Charsets.UTF_8))
        } finally {
            connection.disconnect()
        }
    }

    private companion object {
        const val CONNECT_TIMEOUT_MS = 15_000
        const val READ_TIMEOUT_MS = 30_000
    }
}
