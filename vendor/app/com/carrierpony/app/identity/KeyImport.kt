// KeyImport.kt
// CarrierPony Android
//
// Import an existing OpenPGP private key as this device's identity, ported
// from iOS Core/App/KeyImport.swift with the same user-facing error copy.
// CarrierPony's engine is v4 Ed25519 (sign) + Cv25519 (encrypt); other
// algorithms are rejected clearly. A passphrase-protected key is kept
// protected: its bytes are stored as-is and its passphrase is held behind the
// app lock (PassphraseVault).

package com.carrierpony.app.identity

import com.carrierpony.app.crypto.Fingerprint
import com.carrierpony.core.CPArmor
import com.carrierpony.core.CPKeyImport
import com.carrierpony.core.CPKeyImportError
import com.carrierpony.core.CPKeyInfo

class KeyImportException(message: String) : Exception(message) {
    companion object {
        fun malformed() = KeyImportException("That doesn't look like a valid private key.")
        fun notSupported() = KeyImportException("CarrierPony only supports Ed25519 keys with a Curve25519 encryption subkey. RSA and other key types can't be imported.")
        fun noEncryptionSubkey() = KeyImportException("This key has no encryption subkey, so it can't receive messages. Import a key that includes a Curve25519 encryption subkey.")
        fun wrongPassphrase() = KeyImportException("Wrong passphrase for this key.")
    }
}

object KeyImport {

    class Inspection(
        val secretKey: ByteArray,
        val armoredPublic: String,
        val needsPassphrase: Boolean
    )

    /** Validate the armored key without a passphrase: confirm it's an Ed25519
     *  key, derive its public key, and report whether it's protected. */
    fun inspect(armored: String): Inspection {
        val secret = CPArmor.dearmor(armored)
            ?.takeIf { it.isNotEmpty() }
            ?: throw KeyImportException.malformed()
        val armoredPublic = try {
            CPKeyImport.transferablePublicFromSecret(secret)
        } catch (e: CPKeyImportError.Malformed) {
            throw KeyImportException.malformed()
        } catch (e: CPKeyImportError) {
            throw KeyImportException.notSupported()
        }
        val needsPassphrase = try {
            CPKeyImport.needsPassphrase(secret)
        } catch (e: Exception) {
            throw KeyImportException.malformed()
        }
        return Inspection(secret, armoredPublic, needsPassphrase)
    }

    /** Produce an Identity, validating the passphrase (if any) and the
     *  presence of an encryption subkey. */
    fun makeIdentity(inspection: Inspection, passphrase: String?): Identity {
        try {
            CPKeyImport.validate(inspection.secretKey, passphrase)
        } catch (e: CPKeyImportError.WrongPassphrase) {
            throw KeyImportException.wrongPassphrase()
        } catch (e: CPKeyImportError.NoEncryptionSubkey) {
            throw KeyImportException.noEncryptionSubkey()
        } catch (e: CPKeyImportError) {
            throw KeyImportException.notSupported()
        }
        val fingerprint = CPKeyInfo.primaryFingerprint(inspection.armoredPublic)
            ?.let { Fingerprint.from(it) }
            ?: throw KeyImportException.malformed()
        return Identity(
            fingerprint = fingerprint,
            secretKey = inspection.secretKey,
            armoredPublicKey = inspection.armoredPublic,
            protected = !passphrase.isNullOrEmpty()
        )
    }

    /** The display name from the key's User ID, or null. */
    fun userIDName(secret: ByteArray): String? = CPKeyImport.userIDName(secret)
}
