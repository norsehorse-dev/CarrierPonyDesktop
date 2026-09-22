// SelfTest.kt
// CarrierPony Desktop. `carrierpony selftest` proves the vendored crypto core runs on THIS JVM,
// outside the unit-test harness. It walks the operations a real conversation needs, in order:
// two identities, a relay-challenge signature, a sealed envelope, the refusal of an unknown
// sender, and a passphrase box. Exit code 0 means every step passed.

package com.carrierpony.desktop

import com.carrierpony.core.CPArmor
import com.carrierpony.core.CPGeneratedIdentity
import com.carrierpony.core.CPIdentityGenerator
import com.carrierpony.core.CPKeyInfo
import com.carrierpony.core.CPMessenger
import com.carrierpony.core.CPPassphraseBox

object SelfTest {

    private var failures = 0

    private fun step(name: String, block: () -> Boolean) {
        val ok = try {
            block()
        } catch (e: Throwable) {
            // Throwable, not Exception: a broken packaged runtime shows up as NoClassDefFoundError
            // or UnsatisfiedLinkError, and a selftest that dies with a stack trace has failed at
            // the one thing it is for.
            println("        ${e::class.simpleName}: ${e.message}")
            false
        }
        println((if (ok) "  ok    " else "  FAIL  ") + name)
        if (!ok) failures++
    }

    /** Runs every step and returns the process exit code: 0 all passed, 1 otherwise. */
    fun run(): Int {
        failures = 0
        println("CarrierPony Desktop ${AppVersion.VERSION} selftest")
        println("  JVM   ${System.getProperty("java.vm.name")} ${System.getProperty("java.version")}")

        val (a: CPGeneratedIdentity, b: CPGeneratedIdentity) = try {
            Pair(
                CPIdentityGenerator.generateV4Identity("Alice", "alice@carrierpony.com"),
                CPIdentityGenerator.generateV4Identity("Bob", "bob@carrierpony.com")
            )
        } catch (e: Throwable) {
            println("  FAIL  generate two v4 Ed25519+Cv25519 identities")
            println("        ${e::class.simpleName}: ${e.message}")
            println("selftest: FAILED (key generation did not complete)")
            return 1
        }
        step("generate two v4 Ed25519+Cv25519 identities") { a.fingerprint != b.fingerprint }

        val aPublic: ByteArray? = CPArmor.dearmor(a.armoredPublicKey)
        val bPublic: ByteArray? = CPArmor.dearmor(b.armoredPublicKey)

        step("armored public keys dearmor and parse back to the same fingerprints") {
            aPublic != null && bPublic != null &&
                CPKeyInfo.primaryFingerprint(a.armoredPublicKey) == a.fingerprint &&
                CPKeyInfo.primaryFingerprint(b.armoredPublicKey) == b.fingerprint
        }
        if (aPublic == null || bPublic == null) {
            println("selftest: FAILED (public key did not dearmor)")
            return 1
        }

        step("relay-challenge detached signature verifies, and fails over other bytes") {
            val challenge = "selftest-challenge".toByteArray()
            val signature = CPMessenger.detachedSignature(challenge, a.secretKey, null)
            CPMessenger.verifyDetached(signature, challenge, a.armoredPublicKey) &&
                !CPMessenger.verifyDetached(signature, "other bytes".toByteArray(), a.armoredPublicKey)
        }

        step("sign-and-encrypt, then decrypt-and-verify, Alice to Bob") {
            val plaintext = byteArrayOf(0x43, 0x50, 0x4E, 0x31, 0, 1, 2) + "selftest".toByteArray()
            val envelope = CPMessenger.signAndEncrypt(plaintext, a.secretKey, null, bPublic)
            val opened = CPMessenger.decryptAndVerify(envelope, b.secretKey, null) { fpr ->
                if (fpr == a.fingerprint) aPublic else null
            }
            opened.senderFingerprint == a.fingerprint && opened.plaintext.contentEquals(plaintext)
        }

        step("an envelope from an unknown sender is refused") {
            val envelope = CPMessenger.signAndEncrypt("hello".toByteArray(), a.secretKey, null, bPublic)
            try {
                CPMessenger.decryptAndVerify(envelope, b.secretKey, null) { null }
                false
            } catch (e: Exception) {
                true
            }
        }

        step("passphrase box seals, opens, and rejects a wrong passphrase") {
            val sealed = CPPassphraseBox.seal(a.secretKey, "correct horse")
            val opened = CPPassphraseBox.open(sealed, "correct horse")
            val wrongRejected = try {
                CPPassphraseBox.open(sealed, "wrong horse")
                false
            } catch (e: Exception) {
                true
            }
            opened.contentEquals(a.secretKey) && wrongRejected
        }

        println(if (failures == 0) "selftest: PASSED" else "selftest: FAILED ($failures step(s))")
        return if (failures == 0) 0 else 1
    }
}
