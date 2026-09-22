package com.carrierpony.app.net

import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * LAN-direct wire crypto (2.1). Must match iOS LanCrypto byte-for-byte - see the
 * "Wire protocol v1" section of CarrierPony-2.1-Transport-LANDirect-Design.md.
 * HMAC-SHA256 is the only primitive; all labels are ASCII with no NUL.
 */
object LanCrypto {
    val MAGIC = "CPL1".toByteArray(Charsets.US_ASCII)

    const val T_HELLO: Byte = 0x01
    const val T_HELLO_ACK: Byte = 0x02
    const val T_NO_MATCH: Byte = 0x03
    const val T_ENVELOPE: Byte = 0x04   // M3b

    private val LABEL_PAIR = "cp-lan-v1".toByteArray(Charsets.US_ASCII)
    private val LABEL_ID = "cp-lan-id-v1".toByteArray(Charsets.US_ASCII)
    private val LABEL_ID_ACK = "cp-lan-id-v1-ack".toByteArray(Charsets.US_ASCII)

    private val rng = SecureRandom()

    fun randomBytes(n: Int): ByteArray = ByteArray(n).also { rng.nextBytes(it) }

    private fun hmac(key: ByteArray, message: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(message)
    }

    /** Direction-independent per-pair LAN key from the two directional inbound keys. */
    fun pairKey(myInbound: ByteArray, peerInbound: ByteArray): ByteArray {
        val (lo, hi) = lexSort(myInbound, peerInbound)
        return hmac(LABEL_PAIR, lo + hi)
    }

    fun helloTag(pairKey: ByteArray, dialerNonce: ByteArray): ByteArray =
        hmac(pairKey, LABEL_ID + dialerNonce)

    fun ackTag(pairKey: ByteArray, dialerNonce: ByteArray, listenerNonce: ByteArray): ByteArray =
        hmac(pairKey, LABEL_ID_ACK + dialerNonce + listenerNonce)

    fun constantTimeEquals(a: ByteArray, b: ByteArray): Boolean {
        if (a.size != b.size) return false
        var diff = 0
        for (i in a.indices) diff = diff or (a[i].toInt() xor b[i].toInt())
        return diff == 0
    }

    /** type(1) | length(4, big-endian) | payload */
    fun frame(type: Byte, payload: ByteArray): ByteArray {
        val n = payload.size
        val out = ByteArray(5 + n)
        out[0] = type
        out[1] = ((n ushr 24) and 0xFF).toByte()
        out[2] = ((n ushr 16) and 0xFF).toByte()
        out[3] = ((n ushr 8) and 0xFF).toByte()
        out[4] = (n and 0xFF).toByte()
        System.arraycopy(payload, 0, out, 5, n)
        return out
    }

    /** Lexicographic unsigned-byte sort of two keys, matching iOS. */
    private fun lexSort(a: ByteArray, b: ByteArray): Pair<ByteArray, ByteArray> {
        val min = minOf(a.size, b.size)
        for (i in 0 until min) {
            val ai = a[i].toInt() and 0xFF
            val bi = b[i].toInt() and 0xFF
            if (ai != bi) return if (ai < bi) Pair(a, b) else Pair(b, a)
        }
        return if (a.size <= b.size) Pair(a, b) else Pair(b, a)
    }
}
