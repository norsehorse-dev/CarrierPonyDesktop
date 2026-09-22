// CPMessenger.kt
// CarrierPonyCore
//
// The three operations CarrierPony's CryptoEngine contract needs, in the
// exact flow the iOS PGPonyCryptoEngine implements:
//
//   detachedSignature   — armored detached signature over relay challenge
//                         nonces; the relay verifies it with GnuPG.
//   signAndEncrypt      — one binary OpenPGP message per recipient: PKESK for
//                         the recipient's Cv25519 subkey, SEIPD container
//                         holding one-pass-signature + literal data +
//                         signature. The signature carries the issuer
//                         fingerprint as a hashed subpacket — that is how the
//                         receiving side learns who claims to have sent it.
//   decryptAndVerify    — decrypt, read the claimed signer fingerprint from
//                         the inline signature, resolve that sender's public
//                         key through the caller's resolver, verify, and only
//                         then hand back plaintext + sender.
//
// Wire-format decisions for cross-implementation safety:
//   - No compression on send. Compression is optional in OpenPGP; an
//     uncompressed OPS + literal + signature sequence is the base case every
//     parser handles, including a from-scratch packet parser like the Swift
//     core's. BouncyCastle transparently decompresses on receive, so
//     iOS-built envelopes work regardless of what iOS chooses.
//   - SEIPDv1 (AES-256-CFB + MDC) whenever the recipient is a v4 key — the
//     live CarrierPony fleet. A v6 recipient gets SEIPDv2 (AEAD/OCB),
//     mirroring PGPony Android's all-v6 gate.
//   - Signature version follows the signing key automatically: the two-arg
//     PGPSignatureGenerator(builder, publicKey) constructor makes
//     BouncyCastle emit a v6 signature for a v6 key and a v4 signature for a
//     v4 key, so there are no separate v4/v6 entry points here — the version
//     routing the Swift core does by hand happens inside BC.
//   - Decryption requires integrity protection. A legacy unprotected packet,
//     a failed MDC, or a failed AEAD tag rejects the message and the
//     plaintext is never returned.

package com.carrierpony.core

import org.bouncycastle.bcpg.AEADAlgorithmTags
import org.bouncycastle.bcpg.ArmoredOutputStream
import org.bouncycastle.bcpg.BCPGOutputStream
import org.bouncycastle.bcpg.HashAlgorithmTags
import org.bouncycastle.bcpg.PublicKeyAlgorithmTags
import org.bouncycastle.bcpg.SymmetricKeyAlgorithmTags
import org.bouncycastle.openpgp.PGPCompressedData
import org.bouncycastle.openpgp.PGPEncryptedDataGenerator
import org.bouncycastle.openpgp.PGPEncryptedDataList
import org.bouncycastle.openpgp.PGPException
import org.bouncycastle.openpgp.PGPKeyFlags
import org.bouncycastle.openpgp.PGPLiteralData
import org.bouncycastle.openpgp.PGPLiteralDataGenerator
import org.bouncycastle.openpgp.PGPOnePassSignatureList
import org.bouncycastle.openpgp.PGPPrivateKey
import org.bouncycastle.openpgp.PGPPublicKey
import org.bouncycastle.openpgp.PGPPublicKeyEncryptedData
import org.bouncycastle.openpgp.PGPPublicKeyRing
import org.bouncycastle.openpgp.PGPSecretKey
import org.bouncycastle.openpgp.PGPSecretKeyRing
import org.bouncycastle.openpgp.PGPSignature
import org.bouncycastle.openpgp.PGPSignatureGenerator
import org.bouncycastle.openpgp.PGPSignatureList
import org.bouncycastle.openpgp.PGPSignatureSubpacketGenerator
import org.bouncycastle.openpgp.PGPUtil
import org.bouncycastle.openpgp.bc.BcPGPObjectFactory
import org.bouncycastle.openpgp.operator.bc.BcKeyFingerprintCalculator
import org.bouncycastle.openpgp.operator.bc.BcPBESecretKeyDecryptorBuilder
import org.bouncycastle.openpgp.operator.bc.BcPGPContentSignerBuilder
import org.bouncycastle.openpgp.operator.bc.BcPGPContentVerifierBuilderProvider
import org.bouncycastle.openpgp.operator.bc.BcPGPDataEncryptorBuilder
import org.bouncycastle.openpgp.operator.bc.BcPGPDigestCalculatorProvider
import org.bouncycastle.openpgp.operator.bc.BcPublicKeyDataDecryptorFactory
import org.bouncycastle.openpgp.operator.bc.BcPublicKeyKeyEncryptionMethodGenerator
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.security.SecureRandom
import java.util.Date

/**
 * A successfully decrypted and signature-verified message: who signed it
 * (primary fingerprint, uppercase hex, from the signature's issuer
 * fingerprint subpacket) and the recovered plaintext.
 */
data class CPVerifiedMessage(
    val senderFingerprint: String,
    val plaintext: ByteArray
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is CPVerifiedMessage) return false
        return senderFingerprint == other.senderFingerprint && plaintext.contentEquals(other.plaintext)
    }

    override fun hashCode(): Int = 31 * senderFingerprint.hashCode() + plaintext.contentHashCode()
}

object CPMessenger {

    // ── Detached signature (relay challenge auth) ──────────────────────

    /**
     * Produce an armored detached signature over the given bytes with the
     * identity's signing key. v4 identities yield a v4 EdDSA-legacy
     * signature (verifiable by the relay's GnuPG); a v6 identity yields a v6
     * (RFC 9580) signature via BouncyCastle's automatic version routing.
     */
    fun detachedSignature(
        data: ByteArray,
        secretKey: ByteArray,
        passphrase: String? = null
    ): String {
        try {
            val ring = loadSecretRing(secretKey)
            val signingKey = pickSigningSecretKey(ring)
                ?: throw CPCryptoError.MalformedKey("No signing key in identity")
            val privateKey = unlockPrivateKey(signingKey, passphrase)

            val generator = PGPSignatureGenerator(
                BcPGPContentSignerBuilder(signingKey.publicKey.algorithm, HashAlgorithmTags.SHA256),
                signingKey.publicKey
            )
            generator.init(PGPSignature.BINARY_DOCUMENT, privateKey)

            val subpackets = PGPSignatureSubpacketGenerator()
            subpackets.setIssuerFingerprint(false, signingKey.publicKey)
            generator.setHashedSubpackets(subpackets.generate())

            generator.update(data)
            val signature = generator.generate()

            val out = ByteArrayOutputStream()
            val armored = ArmoredOutputStream(out).clean()
            val bcpgOut = BCPGOutputStream(armored)
            signature.encode(bcpgOut)
            bcpgOut.close()
            armored.close()
            return out.toString(Charsets.UTF_8)
        } catch (e: CPCryptoError) {
            throw e
        } catch (e: Exception) {
            throw CPCryptoError.SigningFailed(e.message ?: "Unknown error")
        }
    }

    /**
     * Verify an armored detached signature over [data] against the signer's
     * armored public key. Returns true only when the signature parses, was made
     * by a key in that ring, and is valid over exactly these bytes; any parse or
     * verification failure returns false. Added for group-message sender
     * authentication (the Android twin of the iOS core's verifyDetachedEd25519).
     */
    fun verifyDetached(
        armoredSignature: String,
        data: ByteArray,
        signerArmoredPublicKey: String
    ): Boolean {
        return try {
            val sigBytes = CPArmor.dearmor(armoredSignature) ?: return false
            val factory = BcPGPObjectFactory(PGPUtil.getDecoderStream(ByteArrayInputStream(sigBytes)))
            val sigList = factory.nextObject() as? PGPSignatureList ?: return false
            if (sigList.isEmpty) return false
            val signature = sigList[0]
            val ringBytes = CPArmor.dearmor(signerArmoredPublicKey) ?: return false
            val ring = loadPublicRing(ringBytes)
            // Resolve by the signature's issuer key ID, then fall back to the ring's
            // primary key. A v4 CarrierPony identity signs with its primary key, and
            // a producer whose issuer-keyID subpacket the strict lookup won't match
            // would otherwise fail to verify even though the signature is valid.
            val pub = ring.getPublicKey(signature.keyID) ?: ring.publicKey ?: return false
            signature.init(BcPGPContentVerifierBuilderProvider(), pub)
            signature.update(data)
            signature.verify()
        } catch (e: Exception) {
            false
        }
    }

    // ── Sign and encrypt ───────────────────────────────────────────────

    /**
     * Sign the plaintext with the identity's signing key and encrypt it to
     * the recipient's encryption subkey. Returns a binary (unarmored)
     * OpenPGP message, matching the iOS engine's armor:false behavior — the
     * relay carries it base64-encoded.
     */
    fun signAndEncrypt(
        plaintext: ByteArray,
        secretKey: ByteArray,
        passphrase: String? = null,
        recipientKey: ByteArray
    ): ByteArray {
        try {
            val ring = loadSecretRing(secretKey)
            val signingKey = pickSigningSecretKey(ring)
                ?: throw CPCryptoError.MalformedKey("No signing key in identity")
            val privateKey = unlockPrivateKey(signingKey, passphrase)

            val recipientRing = loadPublicRing(recipientKey)
            val encryptionKey = findEncryptionKey(recipientRing)
                ?: throw CPCryptoError.MalformedKey(
                    "No encryption subkey for ${CPKeyInfo.bytesToHex(recipientRing.publicKey.fingerprint)}"
                )

            // Container version by recipient key version: SEIPDv2 (AEAD/OCB)
            // only when the recipient is v6; SEIPDv1 + MDC for the v4 fleet.
            val encBuilder = BcPGPDataEncryptorBuilder(SymmetricKeyAlgorithmTags.AES_256)
            val recipientIsV6 = recipientRing.publicKey.version == 6
            val configured = if (recipientIsV6) {
                encBuilder
                    .setWithAEAD(AEADAlgorithmTags.OCB, 6)
                    .setUseV6AEAD()
                    .setSecureRandom(SecureRandom())
            } else {
                encBuilder
                    .setWithIntegrityPacket(true)
                    .setSecureRandom(SecureRandom())
            }

            val encryptedGen = PGPEncryptedDataGenerator(configured)
            encryptedGen.addMethod(BcPublicKeyKeyEncryptionMethodGenerator(encryptionKey))

            val out = ByteArrayOutputStream()
            val encryptedOut = encryptedGen.open(out, ByteArray(1 shl 16))

            // One-pass signature header. No compression layer — see the wire
            // format notes at the top of this file.
            val sigGen = PGPSignatureGenerator(
                BcPGPContentSignerBuilder(signingKey.publicKey.algorithm, HashAlgorithmTags.SHA256),
                signingKey.publicKey
            )
            sigGen.init(PGPSignature.BINARY_DOCUMENT, privateKey)
            val subpackets = PGPSignatureSubpacketGenerator()
            subpackets.setIssuerFingerprint(false, signingKey.publicKey)
            sigGen.setHashedSubpackets(subpackets.generate())
            sigGen.generateOnePassVersion(false).encode(encryptedOut)

            // Literal data — the CPN1 container is opaque binary.
            val literalGen = PGPLiteralDataGenerator()
            val literalOut = literalGen.open(
                encryptedOut,
                PGPLiteralData.BINARY,
                "",
                plaintext.size.toLong(),
                Date()
            )
            literalOut.write(plaintext)
            literalGen.close()

            // Trailing signature over the same bytes.
            sigGen.update(plaintext)
            sigGen.generate().encode(encryptedOut)

            encryptedOut.close()
            encryptedGen.close()
            return out.toByteArray()
        } catch (e: CPCryptoError) {
            throw e
        } catch (e: Exception) {
            throw CPCryptoError.EncryptionFailed(e.message ?: "Unknown error")
        }
    }

    // ── Decrypt and verify ─────────────────────────────────────────────

    /**
     * Decrypt an envelope with the identity's encryption key, extract the
     * claimed signer fingerprint from the inline signature's issuer
     * fingerprint subpacket, resolve that fingerprint to key material via
     * [senderPublicKey], and verify the signature. Fails with
     * NoValidSignature if the message is unsigned, the fingerprint is
     * missing, the sender is unknown to the resolver, or the signature does
     * not verify — plaintext is never returned in any of those cases.
     */
    fun decryptAndVerify(
        message: ByteArray,
        secretKey: ByteArray,
        passphrase: String? = null,
        senderPublicKey: (String) -> ByteArray?
    ): CPVerifiedMessage {
        try {
            val ring = loadSecretRing(secretKey)
            val factory = BcPGPObjectFactory(PGPUtil.getDecoderStream(ByteArrayInputStream(message)))
            val encryptedList = findEncryptedDataList(factory)
                ?: throw CPCryptoError.DecryptionFailed("No encrypted data found in message")

            // First PKESK we hold the key for.
            var encryptedData: PGPPublicKeyEncryptedData? = null
            var privateKey: PGPPrivateKey? = null
            for (obj in encryptedList.encryptedDataObjects) {
                if (obj !is PGPPublicKeyEncryptedData) continue
                val candidate = ring.getSecretKey(obj.keyID) ?: continue
                privateKey = unlockPrivateKey(candidate, passphrase)
                encryptedData = obj
                break
            }
            if (encryptedData == null || privateKey == null) {
                throw CPCryptoError.DecryptionFailed("No matching decryption key found")
            }

            val clearStream = encryptedData.getDataStream(BcPublicKeyDataDecryptorFactory(privateKey))
            val contents = readSignedContents(BcPGPObjectFactory(clearStream))

            // INTEGRITY GATE — after the plaintext stream is fully consumed.
            // Legacy unprotected packets are rejected outright; SEIPDv1's MDC
            // (and SEIPDv2's AEAD tag) must verify.
            val isProtected = encryptedData.isIntegrityProtected
            val intact = isProtected && try {
                encryptedData.verify()
            } catch (e: PGPException) {
                false
            }
            if (!intact) {
                throw CPCryptoError.IntegrityCheckFailed(
                    if (!isProtected) "Message has no integrity protection and was rejected"
                    else "Integrity check failed - the message may have been tampered with"
                )
            }

            val signature = contents.signature
                ?: throw CPCryptoError.NoValidSignature("Message carries no signature")
            val claimedFingerprint = signature.hashedSubPackets?.issuerFingerprint
                ?.let { CPKeyInfo.bytesToHex(it.fingerprint) }
                ?: throw CPCryptoError.NoValidSignature("Signature has no issuer fingerprint")

            val senderKeyData = senderPublicKey(claimedFingerprint)
                ?: throw CPCryptoError.NoValidSignature("Unknown sender $claimedFingerprint")
            val senderRing = loadPublicRing(senderKeyData)
            val verificationKey = senderRing.getPublicKey(signature.keyID)
                ?: throw CPCryptoError.NoValidSignature("Sender key does not contain the signing key")

            signature.init(BcPGPContentVerifierBuilderProvider(), verificationKey)
            signature.update(contents.literal)
            if (!signature.verify()) {
                throw CPCryptoError.NoValidSignature("Signature verification failed")
            }

            return CPVerifiedMessage(claimedFingerprint, contents.literal)
        } catch (e: CPCryptoError) {
            throw e
        } catch (e: Exception) {
            throw CPCryptoError.DecryptionFailed(e.message ?: "Unknown error")
        }
    }

    /**
     * Decrypt a message to our key and enforce the SEIPD integrity gate (MDC /
     * AEAD), but do NOT resolve or verify the signer. Returns the literal
     * plaintext only.
     *
     * This exists solely for self-authenticating control ops — specifically a
     * channel-subscribe from a stranger, whose payload carries the sender's own
     * public key and whose identity is the fingerprint of that key. The caller
     * MUST check that the enclosed public key hashes to the claimed fingerprint
     * before trusting anything in the payload. Never use this for message
     * content or any op that grants a privilege: those require a verified
     * signer via decryptAndVerify, which never returns unverified plaintext.
     */
    fun decryptOnly(
        message: ByteArray,
        secretKey: ByteArray,
        passphrase: String? = null
    ): ByteArray {
        try {
            val ring = loadSecretRing(secretKey)
            val factory = BcPGPObjectFactory(PGPUtil.getDecoderStream(ByteArrayInputStream(message)))
            val encryptedList = findEncryptedDataList(factory)
                ?: throw CPCryptoError.DecryptionFailed("No encrypted data found in message")

            var encryptedData: PGPPublicKeyEncryptedData? = null
            var privateKey: PGPPrivateKey? = null
            for (obj in encryptedList.encryptedDataObjects) {
                if (obj !is PGPPublicKeyEncryptedData) continue
                val candidate = ring.getSecretKey(obj.keyID) ?: continue
                privateKey = unlockPrivateKey(candidate, passphrase)
                encryptedData = obj
                break
            }
            if (encryptedData == null || privateKey == null) {
                throw CPCryptoError.DecryptionFailed("No matching decryption key found")
            }

            val clearStream = encryptedData.getDataStream(BcPublicKeyDataDecryptorFactory(privateKey))
            val contents = readSignedContents(BcPGPObjectFactory(clearStream))

            val isProtected = encryptedData.isIntegrityProtected
            val intact = isProtected && try {
                encryptedData.verify()
            } catch (e: PGPException) {
                false
            }
            if (!intact) {
                throw CPCryptoError.IntegrityCheckFailed(
                    if (!isProtected) "Message has no integrity protection and was rejected"
                    else "Integrity check failed - the message may have been tampered with"
                )
            }

            return contents.literal
        } catch (e: CPCryptoError) {
            throw e
        } catch (e: Exception) {
            throw CPCryptoError.DecryptionFailed(e.message ?: "Unknown error")
        }
    }

    // ── Inner-packet walk ──────────────────────────────────────────────

    private class SignedContents(val literal: ByteArray, val signature: PGPSignature?)

    /**
     * Walk the decrypted packet sequence: an optional compression layer
     * (recursed into — iOS or other producers may compress even though this
     * core never does), an optional one-pass-signature list, the literal
     * data, and the trailing signature list. Verification always uses the
     * trailing signature, so both OPS and sig-sandwich layouts verify.
     */
    private fun readSignedContents(factory: BcPGPObjectFactory): SignedContents {
        var currentFactory = factory
        var literal: ByteArray? = null
        var signature: PGPSignature? = null

        var obj = currentFactory.nextObject()
        while (obj != null) {
            when (obj) {
                is PGPCompressedData -> {
                    currentFactory = BcPGPObjectFactory(obj.dataStream)
                }
                is PGPOnePassSignatureList -> {
                    // Noted and skipped: the trailing signature carries
                    // everything verification needs.
                }
                is PGPLiteralData -> {
                    literal = obj.inputStream.readBytes()
                }
                is PGPSignatureList -> {
                    if (!obj.isEmpty) signature = obj[0]
                }
            }
            obj = currentFactory.nextObject()
        }

        val bytes = literal ?: throw CPCryptoError.DecryptionFailed("No literal data found")
        return SignedContents(bytes, signature)
    }

    // ── Key loading and selection ──────────────────────────────────────

    internal fun loadSecretRing(secretKey: ByteArray): PGPSecretKeyRing {
        return try {
            PGPSecretKeyRing(
                PGPUtil.getDecoderStream(ByteArrayInputStream(secretKey)),
                BcKeyFingerprintCalculator()
            )
        } catch (e: Exception) {
            throw CPCryptoError.MalformedKey("Secret key did not parse: ${e.message}")
        }
    }

    internal fun loadPublicRing(publicKey: ByteArray): PGPPublicKeyRing {
        return try {
            PGPPublicKeyRing(
                PGPUtil.getDecoderStream(ByteArrayInputStream(publicKey)),
                BcKeyFingerprintCalculator()
            )
        } catch (e: Exception) {
            throw CPCryptoError.MalformedKey("Public key did not parse: ${e.message}")
        }
    }

    /**
     * The signing key of an identity: prefer a signing-capable subkey (the
     * v6 layout), else the primary when it is signing-capable (the v4
     * layout). Mirrors the version routing the Swift core does explicitly.
     */
    internal fun pickSigningSecretKey(ring: PGPSecretKeyRing): PGPSecretKey? {
        var master: PGPSecretKey? = null
        for (candidate in ring.secretKeys) {
            val pub = candidate.publicKey
            if (!isSigningAlgorithm(pub.algorithm)) continue
            if (pub.isMasterKey) {
                if (master == null) master = candidate
            } else if (hasKeyFlag(pub, PGPKeyFlags.CAN_SIGN)) {
                return candidate
            }
        }
        return master
    }

    /** The recipient's encryption key: prefer an encryption subkey, else an
     *  encryption-capable primary. Mirrors PGPony Android's selection. */
    internal fun findEncryptionKey(ring: PGPPublicKeyRing): PGPPublicKey? {
        var primaryCandidate: PGPPublicKey? = null
        for (key in ring.publicKeys) {
            if (!key.isEncryptionKey) continue
            if (!key.isMasterKey) return key
            if (primaryCandidate == null) primaryCandidate = key
        }
        return primaryCandidate
    }

    private fun isSigningAlgorithm(algorithm: Int): Boolean = when (algorithm) {
        PublicKeyAlgorithmTags.EDDSA_LEGACY,
        PublicKeyAlgorithmTags.Ed25519,
        PublicKeyAlgorithmTags.Ed448,
        PublicKeyAlgorithmTags.ECDSA,
        PublicKeyAlgorithmTags.DSA,
        PublicKeyAlgorithmTags.RSA_GENERAL,
        PublicKeyAlgorithmTags.RSA_SIGN -> true
        else -> false
    }

    private fun hasKeyFlag(key: PGPPublicKey, flag: Int): Boolean {
        val signatures = key.signatures
        while (signatures.hasNext()) {
            val sig = signatures.next() as? PGPSignature ?: continue
            val flags = sig.hashedSubPackets?.keyFlags ?: continue
            if (flags and flag != 0) return true
        }
        return false
    }

    /**
     * Unlock a secret key's private half, translating BouncyCastle's generic
     * checksum failure into the typed passphrase errors: a key whose
     * s2KUsage is 0 stores its material unencrypted, so a failure there
     * cannot be passphrase-related.
     */
    private fun unlockPrivateKey(secretKey: PGPSecretKey, passphrase: String?): PGPPrivateKey {
        return try {
            secretKey.extractPrivateKey(
                BcPBESecretKeyDecryptorBuilder(BcPGPDigestCalculatorProvider())
                    .build((passphrase ?: "").toCharArray())
            )
        } catch (e: PGPException) {
            if (secretKey.s2KUsage.toInt() != 0) {
                if (passphrase.isNullOrEmpty()) throw CPCryptoError.PassphraseRequired()
                throw CPCryptoError.InvalidPassphrase()
            }
            throw CPCryptoError.MalformedKey(e.message ?: "Failed to unlock secret key")
        }
    }

    private fun findEncryptedDataList(factory: BcPGPObjectFactory): PGPEncryptedDataList? {
        var obj = factory.nextObject()
        while (obj != null) {
            if (obj is PGPEncryptedDataList) return obj
            obj = factory.nextObject()
        }
        return null
    }
}
