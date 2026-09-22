// CPKeyInfo.kt
// CarrierPonyCore
//
// Read-only key inspection: version detection, primary fingerprints, user ID
// names. These back three iOS behaviors:
//   - openPGPKeyVersion(from:) — the engine routes v4 vs v6 by the key bytes
//   - OpenPGPKeyID.primaryFingerprint(armoredPublicKey:) — pairing recomputes
//     the fingerprint from the key and refuses a contact whose claimed
//     fingerprint does not match (the anti-MITM anchor)
//   - KeyImport.userIDName(fromSecretKey:) — a fresh profile name defaults to
//     the identity's user ID name
//
// BouncyCastle computes fingerprints itself (v4 SHA-1 over 0x99 || len ||
// body, v6 SHA-256 over 0x9B || len || body), so unlike the Swift side there
// is no hand-rolled packet walk here — the fingerprint comes off the parsed
// primary key.

package com.carrierpony.core

import org.bouncycastle.openpgp.PGPPublicKey
import org.bouncycastle.openpgp.PGPPublicKeyRing
import org.bouncycastle.openpgp.PGPSecretKeyRing
import org.bouncycastle.openpgp.PGPUtil
import org.bouncycastle.openpgp.operator.bc.BcKeyFingerprintCalculator
import java.io.ByteArrayInputStream

object CPKeyInfo {

    /**
     * The OpenPGP key version (4 or 6) of the primary key in the given key
     * material, or null if the bytes do not parse as a secret or public key
     * ring. Accepts armored or binary input.
     */
    fun keyVersion(keyData: ByteArray): Int? = primaryKey(keyData)?.version

    /**
     * The primary-key fingerprint of the given key material as uppercase hex
     * without separators (40 hex for v4, 64 hex for v6), or null if the bytes
     * do not parse. Accepts armored or binary input.
     */
    fun primaryFingerprint(keyData: ByteArray): String? =
        primaryKey(keyData)?.fingerprint?.let { bytesToHex(it) }

    /**
     * The primary-key fingerprint of an armored public key, or null if the
     * text is not a valid key. The pairing path calls this to verify that a
     * peer's claimed fingerprint matches the key they actually sent.
     */
    fun primaryFingerprint(armoredPublicKey: String): String? =
        primaryFingerprint(armoredPublicKey.toByteArray(Charsets.UTF_8))

    /**
     * The name portion of the first user ID on the key ("Name <email>" gives
     * "Name"), or null if the key has no user ID. Accepts secret or public
     * key material, armored or binary.
     */
    fun userIDName(keyData: ByteArray): String? {
        val primary = primaryKey(keyData) ?: return null
        val userID = primary.userIDs.asSequence().firstOrNull() ?: return null
        val angle = userID.indexOf('<')
        val name = if (angle >= 0) userID.substring(0, angle) else userID
        return name.trim().takeIf { it.isNotEmpty() }
    }

    // ── Internals ──────────────────────────────────────────────────────

    /**
     * Parse key material (secret or public ring, armored or binary) and
     * return its primary public key, or null on any parse failure.
     */
    internal fun primaryKey(keyData: ByteArray): PGPPublicKey? {
        val calculator = BcKeyFingerprintCalculator()
        try {
            val stream = PGPUtil.getDecoderStream(ByteArrayInputStream(keyData))
            return PGPSecretKeyRing(stream, calculator).publicKey
        } catch (e: Exception) {
            // Not a secret key ring; fall through to public.
        }
        try {
            val stream = PGPUtil.getDecoderStream(ByteArrayInputStream(keyData))
            return PGPPublicKeyRing(stream, calculator).publicKey
        } catch (e: Exception) {
            return null
        }
    }

    internal fun bytesToHex(bytes: ByteArray): String =
        bytes.joinToString("") { "%02X".format(it.toInt() and 0xFF) }
}
