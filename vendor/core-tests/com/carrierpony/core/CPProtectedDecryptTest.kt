// CPProtectedDecryptTest.kt
// CarrierPonyCore
//
// Reproduction harness for the imported-protected-key field report: a
// passphrase-protected identity receiving envelopes. Exercises both failing
// directions from the device log — a peer's message TO the protected key, and
// the protected identity's own self-copy (encrypt-to-self, then decrypt).
// If CPMessenger mishandles protected recipients, these fail here on the JVM
// with the typed error, no phones required.

package com.carrierpony.core

import org.bouncycastle.bcpg.HashAlgorithmTags
import org.bouncycastle.bcpg.SymmetricKeyAlgorithmTags
import org.bouncycastle.openpgp.PGPSecretKeyRing
import org.bouncycastle.openpgp.PGPUtil
import org.bouncycastle.openpgp.operator.bc.BcKeyFingerprintCalculator
import org.bouncycastle.openpgp.operator.bc.BcPBESecretKeyEncryptorBuilder
import org.bouncycastle.openpgp.operator.bc.BcPGPDigestCalculatorProvider
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.BeforeClass
import org.junit.Test
import java.io.ByteArrayInputStream

class CPProtectedDecryptTest {

    companion object {
        private lateinit var sender: CPGeneratedIdentity
        private lateinit var receiverPlain: CPGeneratedIdentity
        private lateinit var receiverProtected: ByteArray
        private lateinit var receiverPublic: ByteArray
        private const val PASS = "receiver passphrase 99"

        @BeforeClass
        @JvmStatic
        fun setUp() {
            sender = CPIdentityGenerator.generateV4Identity("Sender", "s@carrierpony.com")
            receiverPlain = CPIdentityGenerator.generateV4Identity("Receiver", "r@carrierpony.com")

            val ring = PGPSecretKeyRing(
                PGPUtil.getDecoderStream(ByteArrayInputStream(receiverPlain.secretKey)),
                BcKeyFingerprintCalculator()
            )
            val encryptor = BcPBESecretKeyEncryptorBuilder(
                SymmetricKeyAlgorithmTags.AES_128,
                BcPGPDigestCalculatorProvider().get(HashAlgorithmTags.SHA256)
            ).build(PASS.toCharArray())
            receiverProtected = PGPSecretKeyRing.copyWithNewPassword(ring, null, encryptor).encoded
            receiverPublic = CPArmor.dearmor(receiverPlain.armoredPublicKey)!!
        }
    }

    @Test
    fun peerMessageDecryptsWithProtectedRecipientKey() {
        val plaintext = "iphone to android".toByteArray()
        val envelope = CPMessenger.signAndEncrypt(plaintext, sender.secretKey, null, receiverPublic)
        val verified = CPMessenger.decryptAndVerify(envelope, receiverProtected, PASS) { fpr ->
            if (fpr == sender.fingerprint) CPArmor.dearmor(sender.armoredPublicKey) else null
        }
        assertArrayEquals(plaintext, verified.plaintext)
        assertEquals(sender.fingerprint, verified.senderFingerprint)
    }

    @Test
    fun selfCopyRoundTripsWithProtectedKey() {
        // The 613-byte failure from the field log: sign with the protected key,
        // encrypt to itself, then decrypt and verify with the same passphrase.
        val plaintext = "self copy".toByteArray()
        val envelope = CPMessenger.signAndEncrypt(plaintext, receiverProtected, PASS, receiverPublic)
        val verified = CPMessenger.decryptAndVerify(envelope, receiverProtected, PASS) { fpr ->
            if (fpr == receiverPlain.fingerprint) receiverPublic else null
        }
        assertArrayEquals(plaintext, verified.plaintext)
        assertEquals(receiverPlain.fingerprint, verified.senderFingerprint)
    }
}
