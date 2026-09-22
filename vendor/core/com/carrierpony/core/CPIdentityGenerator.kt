// CPIdentityGenerator.kt
// CarrierPonyCore
//
// Generates the app's messaging identity: a v4 Ed25519 signing primary
// (EdDSA-legacy, algorithm 22) with a Cv25519 encryption subkey (ECDH,
// algorithm 18). This is the Android twin of iOS
// OpenPGPKeyGenerator.generateV4Identity(name:email:) and follows the key
// layout the CarrierPony relay and iOS peers expect: 40-hex fingerprint,
// signing primary for relay-challenge signatures, Cv25519 subkey for message
// encryption.
//
// Generated identities are passphrase-less by design — the whole record lives
// inside platform-encrypted storage on the device (Keychain on iOS, the
// Keystore-encrypted store on Android), matching Identity.protected = false.
// Passphrase-protected keys enter only via import, which this core unlocks at
// use time.
//
// The generator settings (POSITIVE_CERTIFICATION, SHA-256 certification hash,
// AES-256/192/128 and SHA-256/384/512 preferences, sign+certify primary
// flags, comms+storage encryption subkey flags) mirror the Ed25519 path
// proven in PGPony Android.

package com.carrierpony.core

import org.bouncycastle.bcpg.HashAlgorithmTags
import org.bouncycastle.bcpg.PublicKeyAlgorithmTags
import org.bouncycastle.bcpg.SymmetricKeyAlgorithmTags
import org.bouncycastle.bcpg.ArmoredOutputStream
import org.bouncycastle.crypto.generators.Ed25519KeyPairGenerator
import org.bouncycastle.crypto.generators.X25519KeyPairGenerator
import org.bouncycastle.crypto.params.Ed25519KeyGenerationParameters
import org.bouncycastle.crypto.params.X25519KeyGenerationParameters
import org.bouncycastle.openpgp.PGPKeyFlags
import org.bouncycastle.openpgp.PGPKeyRingGenerator
import org.bouncycastle.openpgp.PGPPublicKeyRing
import org.bouncycastle.openpgp.PGPSignature
import org.bouncycastle.openpgp.PGPSignatureSubpacketGenerator
import org.bouncycastle.openpgp.operator.bc.BcPGPContentSignerBuilder
import org.bouncycastle.openpgp.operator.bc.BcPGPDigestCalculatorProvider
import org.bouncycastle.openpgp.operator.bc.BcPGPKeyPair
import java.io.ByteArrayOutputStream
import java.security.SecureRandom
import java.util.Date

/**
 * The material a fresh identity consists of, in the exact shape the app's
 * Identity record persists: serialized secret key bytes, the armored public
 * key, and the primary fingerprint (40 hex, uppercase).
 */
data class CPGeneratedIdentity(
    val fingerprint: String,
    val secretKey: ByteArray,
    val armoredPublicKey: String
)

object CPIdentityGenerator {

    /**
     * Generate a fresh v4 Ed25519+Cv25519 identity with user ID
     * "name <email>". Throws CPCryptoError.KeyGenerationFailed on any
     * BouncyCastle failure.
     */
    fun generateV4Identity(name: String, email: String): CPGeneratedIdentity {
        try {
            val userID = "$name <$email>"
            val creationDate = Date()
            val random = SecureRandom()

            // Ed25519 signing primary — EdDSA-legacy (algorithm 22)
            val edGen = Ed25519KeyPairGenerator()
            edGen.init(Ed25519KeyGenerationParameters(random))
            val masterKeyPair = BcPGPKeyPair(
                PublicKeyAlgorithmTags.EDDSA_LEGACY,
                edGen.generateKeyPair(),
                creationDate
            )

            // Cv25519 encryption subkey — ECDH (algorithm 18)
            val xGen = X25519KeyPairGenerator()
            xGen.init(X25519KeyGenerationParameters(random))
            val encKeyPair = BcPGPKeyPair(
                PublicKeyAlgorithmTags.ECDH,
                xGen.generateKeyPair(),
                creationDate
            )

            val sigSubpackets = PGPSignatureSubpacketGenerator()
            sigSubpackets.setKeyFlags(false, PGPKeyFlags.CAN_SIGN or PGPKeyFlags.CAN_CERTIFY)
            sigSubpackets.setPreferredSymmetricAlgorithms(false, intArrayOf(
                SymmetricKeyAlgorithmTags.AES_256,
                SymmetricKeyAlgorithmTags.AES_192,
                SymmetricKeyAlgorithmTags.AES_128
            ))
            sigSubpackets.setPreferredHashAlgorithms(false, intArrayOf(
                HashAlgorithmTags.SHA256,
                HashAlgorithmTags.SHA384,
                HashAlgorithmTags.SHA512
            ))
            sigSubpackets.setIssuerFingerprint(false, masterKeyPair.publicKey)

            val encSubpackets = PGPSignatureSubpacketGenerator()
            encSubpackets.setKeyFlags(false, PGPKeyFlags.CAN_ENCRYPT_COMMS or PGPKeyFlags.CAN_ENCRYPT_STORAGE)

            val certSigner = BcPGPContentSignerBuilder(
                masterKeyPair.publicKey.algorithm,
                HashAlgorithmTags.SHA256
            )

            val generator = PGPKeyRingGenerator(
                PGPSignature.POSITIVE_CERTIFICATION,
                masterKeyPair,
                userID,
                BcPGPDigestCalculatorProvider().get(HashAlgorithmTags.SHA1),
                sigSubpackets.generate(),
                null,
                certSigner,
                null
            )
            // Three-argument addSubKey: an encryption subkey gets a binding
            // signature from the PRIMARY only. The four-argument overload
            // additionally embeds a primary-key-binding back-signature made
            // BY the subkey, which only signing-capable subkeys can produce —
            // an X25519 ECDH key cannot sign, so that path throws.
            generator.addSubKey(encKeyPair, encSubpackets.generate(), null)

            val secretKeyRing = generator.generateSecretKeyRing()
            val publicKeyRing = generator.generatePublicKeyRing()

            return CPGeneratedIdentity(
                fingerprint = CPKeyInfo.bytesToHex(publicKeyRing.publicKey.fingerprint),
                secretKey = secretKeyRing.encoded,
                armoredPublicKey = armorPublicKeyRing(publicKeyRing)
            )
        } catch (e: CPCryptoError) {
            throw e
        } catch (e: Exception) {
            throw CPCryptoError.KeyGenerationFailed(e.message ?: "Unknown error")
        }
    }

    /**
     * Export any key material's public half as clean armor (no Version, no
     * Comment header). Used at generation time above and by the import flow
     * to derive the armored public key from an imported secret key.
     */
    fun exportArmoredPublicKey(keyData: ByteArray): String {
        val primary = CPKeyInfo.primaryKey(keyData)
            ?: throw CPCryptoError.MalformedKey("Key data did not parse as an OpenPGP key ring")
        val ring = publicRing(keyData)
            ?: throw CPCryptoError.MalformedKey("Could not derive a public key ring for ${CPKeyInfo.bytesToHex(primary.fingerprint)}")
        return armorPublicKeyRing(ring)
    }

    // ── Internals ──────────────────────────────────────────────────────

    private fun publicRing(keyData: ByteArray): PGPPublicKeyRing? {
        val calculator = org.bouncycastle.openpgp.operator.bc.BcKeyFingerprintCalculator()
        try {
            val stream = org.bouncycastle.openpgp.PGPUtil.getDecoderStream(java.io.ByteArrayInputStream(keyData))
            val secret = org.bouncycastle.openpgp.PGPSecretKeyRing(stream, calculator)
            val keys = secret.secretKeys.asSequence().map { it.publicKey }.toList()
            return PGPPublicKeyRing(keys)
        } catch (e: Exception) {
            // Not a secret ring; fall through to public.
        }
        return try {
            val stream = org.bouncycastle.openpgp.PGPUtil.getDecoderStream(java.io.ByteArrayInputStream(keyData))
            PGPPublicKeyRing(stream, calculator)
        } catch (e: Exception) {
            null
        }
    }

    private fun armorPublicKeyRing(ring: PGPPublicKeyRing): String {
        val out = ByteArrayOutputStream()
        val armored = ArmoredOutputStream(out).clean()
        ring.encode(armored)
        armored.close()
        return out.toString(Charsets.UTF_8)
    }
}
