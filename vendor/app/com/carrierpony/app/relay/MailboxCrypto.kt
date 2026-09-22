// MailboxCrypto.kt
// CarrierPony Android
//
// Opaque rotating mailbox addresses and the device auth for the /v1/sealed/*
// endpoints. In lockstep with the relay src/sealed.php and the iOS MailboxCrypto.
// See CarrierPony-2.0-SealedSender-Design.md.

package com.carrierpony.app.relay

import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

object MailboxCrypto {

    /** The address for the n-th message on a per-pair key, matching the relay's
     *  64-hex mailbox id: HMAC-SHA256(key, context || uint64_be(counter)). The
     *  pair key is the raw 32 secret bytes, used directly as the HMAC key. */
    fun address(key: ByteArray, counter: Long, context: String = "cpmbx1"): String {
        val message = context.toByteArray(Charsets.UTF_8) + beUInt64(counter)
        return hmacHex(key, message)
    }

    /** Device proof for register-mailboxes / inbox / ack: HMAC over
     *  "action:device_id:ts" with the device_key hex STRING used directly as the
     *  key bytes (no hex decode), matching cp_sealed_verify on the relay. */
    fun deviceAuth(deviceKeyHex: String, action: String, deviceID: String, ts: Long): String {
        val message = "$action:$deviceID:$ts".toByteArray(Charsets.UTF_8)
        return hmacHex(deviceKeyHex.toByteArray(Charsets.UTF_8), message)
    }

    /** A group message address for one ordered sender->recipient pair, from the
     *  group's epoch key so any member can compute it and it rotates on rekey:
     *  HMAC-SHA256(epochKey, context || sender_fpr || recipient_fpr || uint64_be(counter)). */
    fun groupAddress(epochKey: ByteArray, senderFprHex: String, recipientFprHex: String, counter: Long, context: String = "cpgmbx1"): String {
        val message = context.toByteArray(Charsets.UTF_8) +
            senderFprHex.toByteArray(Charsets.UTF_8) +
            recipientFprHex.toByteArray(Charsets.UTF_8) +
            beUInt64(counter)
        return hmacHex(epochKey, message)
    }

    fun randomBytes(count: Int): ByteArray {
        val bytes = ByteArray(count)
        SecureRandom().nextBytes(bytes)
        return bytes
    }

    fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }

    private fun hmacHex(key: ByteArray, message: ByteArray): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return hex(mac.doFinal(message))
    }

    private fun beUInt64(value: Long): ByteArray {
        val out = ByteArray(8)
        for (i in 0 until 8) out[i] = (value ushr (8 * (7 - i))).toByte()
        return out
    }
}
