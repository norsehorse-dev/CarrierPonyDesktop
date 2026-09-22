// CPConformanceTest.kt
// CarrierPonyCore
//
// Cross-implementation conformance against CarrierPony iOS, mirroring the
// role cp_envelope_harness.py plays for the Swift core. Two directions:
//
//   iOS -> Android: drop these four files into src/test/resources/vectors/
//     recipient-secret.asc   the test recipient's secret key (armored or binary,
//                            also accepted as recipient-secret.bin)
//     sender-public.asc      the iOS sender's armored public key
//     envelope.bin           an envelope built by CarrierPony iOS from the
//                            sender identity to the recipient identity
//     plaintext.bin          the exact bytes iOS encrypted
//   and iosEnvelopeOpensHere proves this core opens it with the right sender.
//
//   Android -> iOS: run
//     ./gradlew :carrierponycore:test --tests "*CPConformanceTest*" -Dcp.emitVectors=1
//   and emitAndroidVectorsForIOS writes a fresh sender/recipient pair plus an
//   Android-built envelope to carrierponycore/build/conformance-out/ for the
//   iOS harness to open.
//
// Both tests skip (JUnit assumption) when their precondition is absent, so
// the ordinary test run stays green before vectors exist.

package com.carrierpony.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

class CPConformanceTest {

    private fun vector(name: String): ByteArray? =
        javaClass.getResourceAsStream("/vectors/$name")?.use { it.readBytes() }

    @Test
    fun iosEnvelopeOpensHere() {
        val recipientSecret = vector("recipient-secret.asc") ?: vector("recipient-secret.bin")
        val senderPublic = vector("sender-public.asc")
        val envelope = vector("envelope.bin")
        val plaintext = vector("plaintext.bin")
        assumeTrue(
            "No iOS vectors present; drop recipient-secret / sender-public / envelope / plaintext into src/test/resources/vectors/ to activate",
            recipientSecret != null && senderPublic != null && envelope != null && plaintext != null
        )

        val senderKeyBytes = if (CPArmor.isArmored(senderPublic!!)) {
            CPArmor.dearmor(String(senderPublic, Charsets.UTF_8))!!
        } else senderPublic
        val senderFingerprint = CPKeyInfo.primaryFingerprint(senderKeyBytes)!!

        val verified = CPMessenger.decryptAndVerify(envelope!!, recipientSecret!!, null) { fpr ->
            if (fpr == senderFingerprint) senderKeyBytes else null
        }
        assertEquals(senderFingerprint, verified.senderFingerprint)
        assertArrayEquals(plaintext!!, verified.plaintext)
    }

    @Test
    fun emitAndroidVectorsForIOS() {
        assumeTrue(
            "Pass -Dcp.emitVectors=1 to write Android-built vectors for the iOS harness",
            System.getProperty("cp.emitVectors") != null
        )

        val sender = CPIdentityGenerator.generateV4Identity("Android Vector Sender", "vector-sender@carrierpony.com")
        val recipient = CPIdentityGenerator.generateV4Identity("Android Vector Recipient", "vector-recipient@carrierpony.com")
        val plaintext = "CarrierPony Android conformance vector \u0000\u0001\u00ff payload".toByteArray(Charsets.ISO_8859_1)
        val envelope = CPMessenger.signAndEncrypt(plaintext, sender.secretKey, null, CPArmor.dearmor(recipient.armoredPublicKey)!!)

        val outDir = File("build/conformance-out").apply { mkdirs() }
        File(outDir, "sender-public.asc").writeText(sender.armoredPublicKey)
        File(outDir, "sender-fingerprint.txt").writeText(sender.fingerprint)
        File(outDir, "recipient-secret.bin").writeBytes(recipient.secretKey)
        File(outDir, "recipient-public.asc").writeText(recipient.armoredPublicKey)
        File(outDir, "recipient-fingerprint.txt").writeText(recipient.fingerprint)
        File(outDir, "envelope.bin").writeBytes(envelope)
        File(outDir, "plaintext.bin").writeBytes(plaintext)

        assertEquals(sender.fingerprint,
            CPMessenger.decryptAndVerify(envelope, recipient.secretKey, null) { fpr ->
                if (fpr == sender.fingerprint) CPArmor.dearmor(sender.armoredPublicKey) else null
            }.senderFingerprint
        )
    }
}
