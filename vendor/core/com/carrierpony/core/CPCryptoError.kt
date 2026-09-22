// CPCryptoError.kt
// CarrierPonyCore
//
// Typed error surface for the core. The app layer maps these onto the same
// user-facing outcomes as CarrierPony iOS: NoValidSignature and
// DecryptionFailed are the two the ChatStore cares about; the passphrase pair
// exists for imported, passphrase-protected identities.

package com.carrierpony.core

sealed class CPCryptoError(message: String) : Exception(message) {

    /** The message decrypted but carried no verifiable inline signature from a
     *  resolvable sender. Mirrors iOS CryptoError.noValidSignature. */
    class NoValidSignature(msg: String = "No valid signature") : CPCryptoError(msg)

    /** Session-key recovery or plaintext parsing failed. Mirrors iOS
     *  CryptoError.decryptionFailed. */
    class DecryptionFailed(msg: String) : CPCryptoError("Decryption failed: $msg")

    /** SEIPDv1 MDC mismatch, SEIPDv2 AEAD tag mismatch, or a legacy
     *  unprotected packet. The plaintext is never returned when this fires. */
    class IntegrityCheckFailed(msg: String) : CPCryptoError(msg)

    class EncryptionFailed(msg: String) : CPCryptoError("Encryption failed: $msg")

    class SigningFailed(msg: String) : CPCryptoError("Signing failed: $msg")

    class KeyGenerationFailed(msg: String) : CPCryptoError("Key generation failed: $msg")

    /** The supplied key bytes did not parse as an OpenPGP key ring, or the
     *  ring lacks the required capability (signing primary, encryption subkey). */
    class MalformedKey(msg: String) : CPCryptoError(msg)

    /** The identity's secret key is passphrase-protected and none was given. */
    class PassphraseRequired : CPCryptoError("Passphrase is required for this key")

    /** The supplied passphrase failed to unlock the secret key. */
    class InvalidPassphrase : CPCryptoError("Incorrect passphrase")
}
