// CPIdentityGeneratorTest.kt
// CarrierPonyCore

package com.carrierpony.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CPIdentityGeneratorTest {

    @Test
    fun generatesV4IdentityWith40HexFingerprint() {
        val identity = CPIdentityGenerator.generateV4Identity("Test Pony", "test@carrierpony.com")
        assertEquals(40, identity.fingerprint.length)
        assertTrue(identity.fingerprint.all { it.isDigit() || it in 'A'..'F' })
        assertEquals(4, CPKeyInfo.keyVersion(identity.secretKey))
    }

    @Test
    fun armoredPublicKeyMatchesSecretFingerprint() {
        val identity = CPIdentityGenerator.generateV4Identity("Test Pony", "test@carrierpony.com")
        assertTrue(identity.armoredPublicKey.contains("-----BEGIN PGP PUBLIC KEY BLOCK-----"))
        assertEquals(identity.fingerprint, CPKeyInfo.primaryFingerprint(identity.armoredPublicKey))
        assertEquals(identity.fingerprint, CPKeyInfo.primaryFingerprint(identity.secretKey))
    }

    @Test
    fun armoredPublicKeyCarriesNoVersionHeader() {
        val identity = CPIdentityGenerator.generateV4Identity("Test Pony", "test@carrierpony.com")
        assertTrue(!identity.armoredPublicKey.contains("Version:"))
        assertTrue(!identity.armoredPublicKey.contains("Comment:"))
    }

    @Test
    fun userIDNameComesBackFromSecretKey() {
        val identity = CPIdentityGenerator.generateV4Identity("Sea Biscuit", "sea@carrierpony.com")
        assertEquals("Sea Biscuit", CPKeyInfo.userIDName(identity.secretKey))
    }

    @Test
    fun secretKeyRingContainsEncryptionSubkey() {
        val identity = CPIdentityGenerator.generateV4Identity("Test Pony", "test@carrierpony.com")
        val ring = CPMessenger.loadSecretRing(identity.secretKey)
        val subkeys = ring.secretKeys.asSequence().filter { !it.publicKey.isMasterKey }.toList()
        assertEquals(1, subkeys.size)
        assertTrue(subkeys[0].publicKey.isEncryptionKey)
    }

    @Test
    fun exportArmoredPublicKeyFromSecretBytes() {
        val identity = CPIdentityGenerator.generateV4Identity("Test Pony", "test@carrierpony.com")
        val exported = CPIdentityGenerator.exportArmoredPublicKey(identity.secretKey)
        assertEquals(identity.fingerprint, CPKeyInfo.primaryFingerprint(exported))
    }

    @Test
    fun dearmorRoundTripsThePublicKey() {
        val identity = CPIdentityGenerator.generateV4Identity("Test Pony", "test@carrierpony.com")
        val binary = CPArmor.dearmor(identity.armoredPublicKey)
        assertNotNull(binary)
        assertEquals(identity.fingerprint, CPKeyInfo.primaryFingerprint(binary!!))
    }

    @Test
    fun dearmorRejectsGarbage() {
        assertEquals(null, CPArmor.dearmor("not a key at all"))
    }
}
