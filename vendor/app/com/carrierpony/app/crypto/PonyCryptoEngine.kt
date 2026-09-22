// PonyCryptoEngine.kt
// CarrierPony Android
//
// The production CryptoEngine: a thin adapter binding one identity's key
// material to CarrierPonyCore's CPMessenger, the Android twin of iOS
// PGPonyCryptoEngine. The publicKeyResolver is how decryptAndVerify turns a
// claimed signer fingerprint into key material — in the app it is backed by
// the ContactStore, plus the identity's own key so self-copies from other
// devices verify. The passphrase is a provider (not a fixed value) because a
// protected imported identity's passphrase lives behind the app lock and can
// change per session; generated identities supply null.

package com.carrierpony.app.crypto

import com.carrierpony.core.CPArmor
import com.carrierpony.core.CPCryptoError
import com.carrierpony.core.CPMessenger

class PonyCryptoEngine(
    override val fingerprint: Fingerprint,
    private val secretKey: ByteArray,
    private val armored: String,
    private val passphrase: () -> String? = { null },
    private val publicKeyResolver: (Fingerprint) -> PublicKey?
) : CryptoEngine {

    override fun armoredPublicKey(): String = armored

    override fun detachedSignature(over: ByteArray): String =
        CPMessenger.detachedSignature(over, secretKey, passphrase())

    override fun verifyDetached(signature: String, over: ByteArray, signerPublicKey: PublicKey): Boolean =
        CPMessenger.verifyDetached(signature, over, signerPublicKey.armored)

    override fun signAndEncrypt(plaintext: ByteArray, to: PublicKey): ByteArray {
        val recipientKey = CPArmor.dearmor(to.armored)
            ?: throw CPCryptoError.MalformedKey("Recipient key for ${to.fingerprint.hex} did not dearmor")
        return CPMessenger.signAndEncrypt(plaintext, secretKey, passphrase(), recipientKey)
    }

    override fun decryptAndVerify(message: ByteArray): VerifiedMessage {
        val verified = try {
            CPMessenger.decryptAndVerify(message, secretKey, passphrase()) { claimedHex ->
                Fingerprint.from(claimedHex)
                    ?.let(publicKeyResolver)
                    ?.let { CPArmor.dearmor(it.armored) }
            }
        } catch (e: CPCryptoError.NoValidSignature) {
            throw CryptoException.NoValidSignature(e.message, e)
        } catch (e: CPCryptoError) {
            // The message names the failing layer (PassphraseRequired vs
            // InvalidPassphrase vs no-matching-key vs integrity) and whether a
            // passphrase was available at call time — never its value.
            val pass = passphrase()
            val state = if (pass == null) "absent" else "present(len=" + pass.length + ")"
            throw CryptoException.DecryptionFailed(e.message + " [passphrase " + state + "]", e)
        }
        // A sender fingerprint that does not fit the 40-hex type (a v6 sender)
        // cannot be a contact on the v4 relay path — refuse, matching iOS.
        val sender = Fingerprint.from(verified.senderFingerprint)
            ?: throw CryptoException.NoValidSignature()
        return VerifiedMessage(sender, verified.plaintext)
    }

    override fun decryptOnly(message: ByteArray): ByteArray =
        try {
            CPMessenger.decryptOnly(message, secretKey, passphrase())
        } catch (e: CPCryptoError) {
            throw CryptoException.DecryptionFailed(e.message, e)
        }
}
