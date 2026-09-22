// SafetyNumber.kt
// CarrierPony Android
//
// A safety number is a short, human-comparable digest of two identities' key
// fingerprints. Both sides compute the same value (the inputs are sorted
// first, so order doesn't matter), so if the two people read it to each other
// over any out-of-band channel and it matches, they've confirmed there's no
// man in the middle — and the contact can be marked verified.
//
// Format: 60 decimal digits, shown as twelve groups of five. Numeric so it's
// language-neutral and easy to read aloud. Ported from iOS
// Core/Pairing/SafetyNumber.swift; the derivation must match digit-for-digit
// across platforms (pinned by test vector).

package com.carrierpony.app.pairing

import com.carrierpony.app.crypto.Fingerprint
import java.security.MessageDigest

object SafetyNumber {

    /** The 60-digit safety number for a pair of fingerprints (order-independent). */
    fun compute(a: Fingerprint, b: Fingerprint): String {
        val pair = listOf(a.hex, b.hex).sorted()
        val seed = (pair[0] + pair[1]).toByteArray(Charsets.UTF_8)

        // 32 + 32 = 64 bytes of deterministic material; we use the first 60.
        val sha256 = MessageDigest.getInstance("SHA-256")
        val h1 = sha256.digest(seed)
        val h2 = sha256.digest(h1)
        val bytes = h1 + h2

        val digits = StringBuilder(60)
        for (group in 0 until 12) {
            val start = group * 5
            var value = 0L
            for (j in 0 until 5) {
                value = (value shl 8) or (bytes[start + j].toLong() and 0xFF)
            }
            digits.append("%05d".format(value % 100000))
        }
        return digits.toString()
    }

    /** The safety number split into twelve space-separated groups of five. */
    fun grouped(a: Fingerprint, b: Fingerprint): String =
        compute(a, b).chunked(5).joinToString(" ")
}
