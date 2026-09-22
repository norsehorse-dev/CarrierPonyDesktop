// CryptoEngine.kt
// CarrierPony Android
//
// The crypto contract the messaging stack builds on, ported 1:1 from iOS
// Core/Crypto/CryptoEngine.swift. Fingerprint is the 40-hex v4 primary-key
// fingerprint — it IS a contact's identity throughout the app and the relay
// protocol. (A 64-hex v6 fingerprint deliberately does not fit this type and
// is rejected upstream, which is correct for the v4 relay path today.)

package com.carrierpony.app.crypto

/** A v4 OpenPGP primary-key fingerprint: exactly 40 uppercase hex characters. */
@ConsistentCopyVisibility
data class Fingerprint private constructor(val hex: String) {
    companion object {
        /** Returns null unless the string is exactly 40 hex characters. */
        fun from(string: String): Fingerprint? {
            val upper = string.uppercase()
            val valid = upper.length == 40 && upper.all { it.isDigit() || it in 'A'..'F' }
            return if (valid) Fingerprint(upper) else null
        }
    }
}

/** A contact's public key: its fingerprint and the armored key material. */
data class PublicKey(
    val fingerprint: Fingerprint,
    val armored: String
)

/** A decrypted message whose inline signature verified: who sent it and what they sent. */
class VerifiedMessage(
    val sender: Fingerprint,
    val plaintext: ByteArray
)

/** The engine failures the messaging layer distinguishes, mirroring iOS CryptoError. */
sealed class CryptoException(message: String? = null, cause: Throwable? = null) : Exception(message, cause) {
    class NoValidSignature(detail: String? = null, cause: Throwable? = null) : CryptoException(detail, cause)
    class DecryptionFailed(detail: String? = null, cause: Throwable? = null) : CryptoException(detail, cause)
    class NotImplemented : CryptoException()
}

/**
 * Everything the app needs from an identity's cryptography. One production
 * implementation exists (PonyCryptoEngine over CarrierPonyCore); tests may
 * substitute fakes.
 */
interface CryptoEngine {

    /** The identity's own fingerprint. */
    val fingerprint: Fingerprint

    /** The identity's armored public key (shared during pairing). */
    fun armoredPublicKey(): String

    /** Armored detached signature over the data (relay challenge auth). */
    fun detachedSignature(over: ByteArray): String

    /** Verify an armored detached signature over [over] against a signer's
     *  public key. True only if it parses, was made by that key, and is valid
     *  over exactly these bytes. Used for group-message sender authentication. */
    fun verifyDetached(signature: String, over: ByteArray, signerPublicKey: PublicKey): Boolean

    /** Sign with our key, encrypt to the recipient. Returns a binary OpenPGP message. */
    fun signAndEncrypt(plaintext: ByteArray, to: PublicKey): ByteArray

    /**
     * Decrypt and verify an envelope. Throws CryptoException.NoValidSignature
     * when the sender cannot be verified, CryptoException.DecryptionFailed when
     * the message cannot be opened at all. Plaintext is never returned unverified.
     */
    fun decryptAndVerify(message: ByteArray): VerifiedMessage

    /**
     * Decrypt to our key and enforce the integrity gate, WITHOUT verifying the
     * signer. Returns the raw plaintext. Only for self-authenticating control
     * ops (channel-subscribe) whose payload carries the sender's own public
     * key; the caller must check fingerprint == hash(pubkey). Never use for
     * message content. Throws CryptoException.DecryptionFailed if it cannot be
     * opened to our key.
     */
    fun decryptOnly(message: ByteArray): ByteArray
}
