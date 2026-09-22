// CPKeyImport.kt
// CarrierPonyCore
//
// Primitives for importing an existing OpenPGP private key (from gpg, PGPony,
// etc.) as a CarrierPony identity. The engine is v4 Ed25519 (sign) + Cv25519
// (encrypt), so anything else is rejected. A passphrase-protected key stays
// protected: its bytes are stored as-is; callers hold the passphrase behind
// the app lock and pass it per-operation (CPMessenger already threads a
// passphrase through signing and decryption).

package com.carrierpony.core

import org.bouncycastle.bcpg.ArmoredOutputStream
import org.bouncycastle.bcpg.PublicKeyAlgorithmTags
import org.bouncycastle.openpgp.PGPPublicKey
import org.bouncycastle.openpgp.PGPSecretKeyRing
import org.bouncycastle.openpgp.PGPUtil
import org.bouncycastle.openpgp.operator.bc.BcKeyFingerprintCalculator
import org.bouncycastle.openpgp.operator.bc.BcPBESecretKeyDecryptorBuilder
import org.bouncycastle.openpgp.operator.bc.BcPGPDigestCalculatorProvider
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

sealed class CPKeyImportError(message: String) : Exception(message) {
    class Malformed : CPKeyImportError("Not a parseable OpenPGP secret key")
    class NotSupported : CPKeyImportError("Primary key is not Ed25519")
    class NoEncryptionSubkey : CPKeyImportError("No usable Curve25519 encryption subkey")
    class WrongPassphrase : CPKeyImportError("Passphrase does not unlock this key")
}

object CPKeyImport {

    /** Parse a secret key and gate the algorithm: the primary must be Ed25519
     *  (EdDSA-legacy). Throws Malformed or NotSupported. */
    private fun ring(secret: ByteArray): PGPSecretKeyRing {
        val ring = try {
            PGPSecretKeyRing(PGPUtil.getDecoderStream(ByteArrayInputStream(secret)), BcKeyFingerprintCalculator())
        } catch (e: Exception) {
            throw CPKeyImportError.Malformed()
        }
        if (ring.secretKey.publicKey.algorithm != PublicKeyAlgorithmTags.EDDSA_LEGACY) {
            throw CPKeyImportError.NotSupported()
        }
        return ring
    }

    /** The armored transferable public key derived from a secret key. */
    fun transferablePublicFromSecret(secret: ByteArray): String {
        val ring = ring(secret)
        val out = ByteArrayOutputStream()
        val armored = ArmoredOutputStream(out).clean()
        for (key in ring.publicKeys) {
            (key as PGPPublicKey).encode(armored)
        }
        armored.close()
        return out.toString(Charsets.UTF_8)
    }

    /** True when the primary secret key material is passphrase-protected. */
    fun needsPassphrase(secret: ByteArray): Boolean {
        val ring = ring(secret)
        return try {
            ring.secretKey.extractPrivateKey(null)
            false
        } catch (e: Exception) {
            true
        }
    }

    /** Validate the passphrase against the primary key and confirm a usable
     *  Cv25519 encryption subkey exists. Throws WrongPassphrase or
     *  NoEncryptionSubkey (or Malformed/NotSupported from parsing). */
    fun validate(secret: ByteArray, passphrase: String?) {
        val ring = ring(secret)
        val decryptor = passphrase?.let {
            BcPBESecretKeyDecryptorBuilder(BcPGPDigestCalculatorProvider()).build(it.toCharArray())
        }
        try {
            ring.secretKey.extractPrivateKey(decryptor)
        } catch (e: Exception) {
            throw CPKeyImportError.WrongPassphrase()
        }
        val encryption = ring.secretKeys.asSequence()
            .filterIsInstance<org.bouncycastle.openpgp.PGPSecretKey>()
            .firstOrNull { it.publicKey.algorithm == PublicKeyAlgorithmTags.ECDH }
            ?: throw CPKeyImportError.NoEncryptionSubkey()
        try {
            encryption.extractPrivateKey(decryptor)
        } catch (e: Exception) {
            throw CPKeyImportError.NoEncryptionSubkey()
        }
    }

    /** The display name from the key's first User ID ("Name <email>" -> "Name").
     *  Null when the UID is only an email or absent. */
    fun userIDName(secret: ByteArray): String? {
        val ring = try { ring(secret) } catch (e: Exception) { return null }
        val uid = ring.publicKey.userIDs.asSequence().firstOrNull() ?: return null
        val trimmed = uid.trim()
        val bracket = trimmed.indexOf('<')
        val name = if (bracket >= 0) trimmed.substring(0, bracket).trim() else trimmed
        return name.ifEmpty { null }
    }
}
