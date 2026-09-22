// CPMessengerTest.kt
// CarrierPonyCore
//
// The behaviors that make the messenger safe: a full sign-encrypt-decrypt-
// verify round trip between two identities, refusal paths (unknown sender,
// tampered ciphertext, wrong recipient), relay-challenge detached signatures,
// and a wire-layout guard proving envelopes go out uncompressed with a
// one-pass signature — the base-case packet sequence every OpenPGP parser
// accepts, including the Swift core on the other side of a conversation.

package com.carrierpony.core

import org.bouncycastle.openpgp.PGPOnePassSignatureList
import org.bouncycastle.openpgp.PGPSignature
import org.bouncycastle.openpgp.PGPUtil
import org.bouncycastle.openpgp.bc.BcPGPObjectFactory
import org.bouncycastle.openpgp.PGPEncryptedDataList
import org.bouncycastle.openpgp.PGPPublicKeyEncryptedData
import org.bouncycastle.openpgp.operator.bc.BcPBESecretKeyDecryptorBuilder
import org.bouncycastle.openpgp.operator.bc.BcPGPContentVerifierBuilderProvider
import org.bouncycastle.openpgp.operator.bc.BcPGPDigestCalculatorProvider
import org.bouncycastle.openpgp.operator.bc.BcPublicKeyDataDecryptorFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.BeforeClass
import org.junit.Test
import java.io.ByteArrayInputStream

class CPMessengerTest {

    companion object {
        private lateinit var alice: CPGeneratedIdentity
        private lateinit var bob: CPGeneratedIdentity

        @BeforeClass
        @JvmStatic
        fun generateIdentities() {
            alice = CPIdentityGenerator.generateV4Identity("Alice", "alice@carrierpony.com")
            bob = CPIdentityGenerator.generateV4Identity("Bob", "bob@carrierpony.com")
        }

        private fun resolver(vararg identities: CPGeneratedIdentity): (String) -> ByteArray? = { fpr ->
            identities.firstOrNull { it.fingerprint == fpr }
                ?.let { CPArmor.dearmor(it.armoredPublicKey) }
        }
    }

    // ── Round trip ─────────────────────────────────────────────────────

    @Test
    fun roundTripRecoversPlaintextAndSender() {
        val plaintext = "CPN1 payload bytes \u0000\u0001\u0002 with binary".toByteArray()
        val envelope = CPMessenger.signAndEncrypt(plaintext, alice.secretKey, null, CPArmor.dearmor(bob.armoredPublicKey)!!)
        val verified = CPMessenger.decryptAndVerify(envelope, bob.secretKey, null, resolver(alice))
        assertEquals(alice.fingerprint, verified.senderFingerprint)
        assertArrayEquals(plaintext, verified.plaintext)
    }

    @Test
    fun roundTripHandlesLargePayload() {
        val plaintext = ByteArray(2 * 1024 * 1024) { (it % 251).toByte() }
        val envelope = CPMessenger.signAndEncrypt(plaintext, alice.secretKey, null, CPArmor.dearmor(bob.armoredPublicKey)!!)
        val verified = CPMessenger.decryptAndVerify(envelope, bob.secretKey, null, resolver(alice))
        assertArrayEquals(plaintext, verified.plaintext)
    }

    // ── Refusal paths ──────────────────────────────────────────────────

    @Test
    fun unknownSenderIsRefused() {
        val envelope = CPMessenger.signAndEncrypt("hi".toByteArray(), alice.secretKey, null, CPArmor.dearmor(bob.armoredPublicKey)!!)
        try {
            CPMessenger.decryptAndVerify(envelope, bob.secretKey, null) { null }
            fail("Expected NoValidSignature for an unresolvable sender")
        } catch (e: CPCryptoError.NoValidSignature) {
            // Correct: plaintext must not come back when the sender is unknown.
        }
    }

    @Test
    fun wrongSenderKeyIsRefused() {
        val envelope = CPMessenger.signAndEncrypt("hi".toByteArray(), alice.secretKey, null, CPArmor.dearmor(bob.armoredPublicKey)!!)
        try {
            // Resolver hands back Bob's key for Alice's claimed fingerprint.
            CPMessenger.decryptAndVerify(envelope, bob.secretKey, null) { CPArmor.dearmor(bob.armoredPublicKey) }
            fail("Expected NoValidSignature when the resolved key does not match")
        } catch (e: CPCryptoError.NoValidSignature) {
            // Correct: a substituted key must not verify.
        }
    }

    @Test
    fun wrongRecipientCannotDecrypt() {
        val eve = CPIdentityGenerator.generateV4Identity("Eve", "eve@carrierpony.com")
        val envelope = CPMessenger.signAndEncrypt("hi".toByteArray(), alice.secretKey, null, CPArmor.dearmor(bob.armoredPublicKey)!!)
        try {
            CPMessenger.decryptAndVerify(envelope, eve.secretKey, null, resolver(alice))
            fail("Expected DecryptionFailed for a non-recipient")
        } catch (e: CPCryptoError.DecryptionFailed) {
            // Correct: Eve holds no matching decryption key.
        }
    }

    @Test
    fun tamperedCiphertextIsRefused() {
        val envelope = CPMessenger.signAndEncrypt("hi".toByteArray(), alice.secretKey, null, CPArmor.dearmor(bob.armoredPublicKey)!!)
        val tampered = envelope.copyOf()
        val index = tampered.size - 30
        tampered[index] = (tampered[index].toInt() xor 0x41).toByte()
        try {
            CPMessenger.decryptAndVerify(tampered, bob.secretKey, null, resolver(alice))
            fail("Expected a failure for tampered ciphertext")
        } catch (e: CPCryptoError) {
            // Correct: integrity, parse, or signature failure — never plaintext.
        }
    }

    // ── Detached signatures (relay challenge auth) ─────────────────────

    @Test
    fun detachedSignatureIsArmoredAndVerifies() {
        val nonce = "9f8e7d6c5b4a39281706f5e4d3c2b1a0".toByteArray()
        val armored = CPMessenger.detachedSignature(nonce, alice.secretKey)
        assertTrue(armored.contains("-----BEGIN PGP SIGNATURE-----"))
        assertTrue(!armored.contains("Version:"))

        val sigBytes = CPArmor.dearmor(armored)!!
        val factory = BcPGPObjectFactory(PGPUtil.getDecoderStream(ByteArrayInputStream(sigBytes)))
        val sigList = factory.nextObject() as org.bouncycastle.openpgp.PGPSignatureList
        val signature: PGPSignature = sigList[0]

        val senderRing = CPMessenger.loadPublicRing(CPArmor.dearmor(alice.armoredPublicKey)!!)
        signature.init(BcPGPContentVerifierBuilderProvider(), senderRing.getPublicKey(signature.keyID))
        signature.update(nonce)
        assertTrue(signature.verify())

        val claimed = signature.hashedSubPackets.issuerFingerprint
        assertEquals(alice.fingerprint, CPKeyInfo.bytesToHex(claimed.fingerprint))
    }

    @Test
    fun detachedSignatureFailsOverDifferentData() {
        val armored = CPMessenger.detachedSignature("nonce-a".toByteArray(), alice.secretKey)
        val sigBytes = CPArmor.dearmor(armored)!!
        val factory = BcPGPObjectFactory(PGPUtil.getDecoderStream(ByteArrayInputStream(sigBytes)))
        val sigList = factory.nextObject() as org.bouncycastle.openpgp.PGPSignatureList
        val signature: PGPSignature = sigList[0]

        val senderRing = CPMessenger.loadPublicRing(CPArmor.dearmor(alice.armoredPublicKey)!!)
        signature.init(BcPGPContentVerifierBuilderProvider(), senderRing.getPublicKey(signature.keyID))
        signature.update("nonce-b".toByteArray())
        assertTrue(!signature.verify())
    }

    // ── Wire layout guard ──────────────────────────────────────────────

    @Test
    fun envelopeInnerLayoutIsUncompressedOnePassSignature() {
        val envelope = CPMessenger.signAndEncrypt("layout".toByteArray(), alice.secretKey, null, CPArmor.dearmor(bob.armoredPublicKey)!!)

        // Manually open the container and inspect the first inner packet.
        val ring = CPMessenger.loadSecretRing(bob.secretKey)
        val factory = BcPGPObjectFactory(PGPUtil.getDecoderStream(ByteArrayInputStream(envelope)))
        var obj = factory.nextObject()
        var encList: PGPEncryptedDataList? = null
        while (obj != null) {
            if (obj is PGPEncryptedDataList) { encList = obj; break }
            obj = factory.nextObject()
        }
        val encData = encList!!.encryptedDataObjects.asSequence()
            .filterIsInstance<PGPPublicKeyEncryptedData>().first()
        assertTrue("Envelope must be integrity protected", encData.isIntegrityProtected)

        val secretKey = ring.getSecretKey(encData.keyID)!!
        val privateKey = secretKey.extractPrivateKey(
            BcPBESecretKeyDecryptorBuilder(BcPGPDigestCalculatorProvider()).build(charArrayOf())
        )
        val inner = BcPGPObjectFactory(encData.getDataStream(BcPublicKeyDataDecryptorFactory(privateKey)))
        val first = inner.nextObject()
        assertTrue(
            "First inner packet must be a one-pass signature, not compression; got ${first?.javaClass?.simpleName}",
            first is PGPOnePassSignatureList
        )
    }
}
