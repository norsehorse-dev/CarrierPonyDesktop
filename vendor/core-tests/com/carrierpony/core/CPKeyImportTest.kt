// CPKeyImportTest.kt
// CarrierPonyCore
//
// Import gates against real keys: a generated identity (unprotected), the
// same key re-encrypted under a passphrase (protected path, exercised via
// BC's copyWithNewPassword so no external vector is needed), and refusals.

package com.carrierpony.core

import org.bouncycastle.openpgp.PGPSecretKeyRing
import org.bouncycastle.openpgp.PGPUtil
import org.bouncycastle.openpgp.bc.BcPGPSecretKeyRingCollection
import org.bouncycastle.openpgp.operator.bc.BcKeyFingerprintCalculator
import org.bouncycastle.openpgp.operator.bc.BcPBESecretKeyDecryptorBuilder
import org.bouncycastle.openpgp.operator.bc.BcPBESecretKeyEncryptorBuilder
import org.bouncycastle.openpgp.operator.bc.BcPGPDigestCalculatorProvider
import org.bouncycastle.bcpg.SymmetricKeyAlgorithmTags
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.BeforeClass
import org.junit.Test
import java.io.ByteArrayInputStream

class CPKeyImportTest {

    companion object {
        private lateinit var unprotected: ByteArray
        private lateinit var protectedKey: ByteArray
        private lateinit var fingerprint: String
        private const val PASS = "key passphrase 123"

        @BeforeClass
        @JvmStatic
        fun makeKeys() {
            val generated = CPIdentityGenerator.generateV4Identity("Importable Pony", "import@carrierpony.com")
            unprotected = generated.secretKey
            fingerprint = generated.fingerprint

            // Re-encrypt the same ring under a passphrase for the protected path.
            val ring = PGPSecretKeyRing(
                PGPUtil.getDecoderStream(ByteArrayInputStream(unprotected)),
                BcKeyFingerprintCalculator()
            )
            val encryptor = BcPBESecretKeyEncryptorBuilder(
                SymmetricKeyAlgorithmTags.AES_256,
                BcPGPDigestCalculatorProvider().get(org.bouncycastle.bcpg.HashAlgorithmTags.SHA256)
            ).build(PASS.toCharArray())
            val reencrypted = PGPSecretKeyRing.copyWithNewPassword(ring, null, encryptor)
            protectedKey = reencrypted.encoded
        }
    }

    @Test
    fun unprotectedKeyInspects() {
        assertFalse(CPKeyImport.needsPassphrase(unprotected))
        val pub = CPKeyImport.transferablePublicFromSecret(unprotected)
        assertTrue(pub.contains("-----BEGIN PGP PUBLIC KEY BLOCK-----"))
        assertEquals(fingerprint, CPKeyInfo.primaryFingerprint(pub))
        CPKeyImport.validate(unprotected, null)   // must not throw
    }

    @Test
    fun protectedKeyNeedsAndProvesPassphrase() {
        assertTrue(CPKeyImport.needsPassphrase(protectedKey))
        CPKeyImport.validate(protectedKey, PASS)  // must not throw
        try {
            CPKeyImport.validate(protectedKey, "wrong")
            fail("Expected WrongPassphrase")
        } catch (e: CPKeyImportError.WrongPassphrase) {
            // Correct.
        }
    }

    @Test
    fun protectedKeyStillSignsThroughTheEngine() {
        // The whole point of protected import: CPMessenger operations accept the
        // passphrase per call, so the key bytes never need rewriting.
        val sig = CPMessenger.detachedSignature("prove it".toByteArray(), protectedKey, PASS)
        assertTrue(sig.contains("-----BEGIN PGP SIGNATURE-----"))
    }

    @Test
    fun userIDNameParses() {
        assertEquals("Importable Pony", CPKeyImport.userIDName(unprotected))
    }

    @Test
    fun garbageIsMalformed() {
        try {
            CPKeyImport.transferablePublicFromSecret("junk".toByteArray())
            fail("Expected Malformed")
        } catch (e: CPKeyImportError.Malformed) {
            // Correct.
        }
    }
}
