// CPPassphraseBoxTest.kt
// CarrierPonyCore

package com.carrierpony.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class CPPassphraseBoxTest {

    private val secret = "identity payload \u0000\u0001\u00ff bytes".toByteArray(Charsets.ISO_8859_1)

    @Test
    fun roundTrips() {
        val blob = CPPassphraseBox.seal(secret, "correct horse battery staple")
        assertTrue(blob.contains("-----BEGIN PGP MESSAGE-----"))
        assertTrue(!blob.contains("Version:"))
        assertArrayEquals(secret, CPPassphraseBox.open(blob, "correct horse battery staple"))
    }

    @Test
    fun wrongPassphraseIsRefused() {
        val blob = CPPassphraseBox.seal(secret, "right")
        try {
            CPPassphraseBox.open(blob, "wrong")
            fail("Expected a failure for the wrong passphrase")
        } catch (e: CPCryptoError) {
            // Correct: wrong key never yields plaintext.
        }
    }

    @Test
    fun tamperedBlobIsRefused() {
        val blob = CPPassphraseBox.seal(ByteArray(600) { (it % 251).toByte() }, "pass")
        val lines = blob.lines().toMutableList()
        val bodyIndex = lines.indexOfFirst { it.length > 40 && !it.startsWith("-") } + 2
        val line = lines[bodyIndex].toCharArray()
        line[5] = if (line[5] == 'A') 'B' else 'A'
        lines[bodyIndex] = String(line)
        try {
            CPPassphraseBox.open(lines.joinToString("\n"), "pass")
            fail("Expected a failure for tampered ciphertext")
        } catch (e: CPCryptoError) {
            // Correct: CRC, parse, or integrity failure — never plaintext.
        }
    }

    @Test
    fun garbageIsRefused() {
        try {
            CPPassphraseBox.open("not a pgp message", "pass")
            fail("Expected DecryptionFailed")
        } catch (e: CPCryptoError.DecryptionFailed) {
            // Correct.
        }
    }
}
