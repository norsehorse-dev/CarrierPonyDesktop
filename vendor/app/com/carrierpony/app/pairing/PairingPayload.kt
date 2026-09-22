// PairingPayload.kt
// CarrierPony Android
//
// The data exchanged when pairing: a peer's fingerprint, optional display
// name, and armored public key. Serialized to a compact base64 string for the
// QR. Ported from iOS Core/Pairing/PairingPayload.swift.
//
// Security: on scan we recompute the primary-key fingerprint from the public
// key and require it to equal the claimed fingerprint before storing a
// contact. The fingerprint *is* the identity, so a QR whose key does not hash
// to its claimed fingerprint is rejected — you never store a contact you
// would then encrypt to under a fingerprint that does not match the key.
//
// The fingerprint recomputation itself (iOS OpenPGPKeyID.primaryFingerprint's
// hand-rolled packet walk) is CPKeyInfo.primaryFingerprint here — BouncyCastle
// computes the v4 SHA-1 / v6 SHA-256 fingerprint from the parsed key, same
// result, no bespoke parser to keep in sync.

package com.carrierpony.app.pairing

import com.carrierpony.app.crypto.Fingerprint
import com.carrierpony.app.crypto.PublicKey
import com.carrierpony.app.messaging.Contact
import com.carrierpony.app.messaging.TrustLevel
import com.carrierpony.core.CPKeyInfo
import org.json.JSONObject
import kotlin.io.encoding.Base64

data class PairingPayload(
    val v: Int,
    val fpr: String,        // claimed fingerprint (hex)
    val name: String?,
    val pub: String         // armored public key
) {

    fun encoded(): String {
        val json = JSONObject()
        json.put("v", v)
        json.put("fpr", fpr)
        if (name != null) json.put("name", name)
        json.put("pub", pub)
        return base64.encode(json.toString().toByteArray(Charsets.UTF_8))
    }

    /**
     * Build a Contact only if the claimed fingerprint matches the public key.
     * Returns null for a malformed key or a fingerprint mismatch.
     */
    fun verifiedContact(): Contact? {
        val fingerprint = Fingerprint.from(fpr) ?: return null
        val computed = CPKeyInfo.primaryFingerprint(pub) ?: return null
        if (computed != fingerprint.hex) return null
        return Contact(
            fingerprint = fingerprint,
            publicKey = PublicKey(fingerprint = fingerprint, armored = pub),
            name = name,
            trust = TrustLevel.VERIFIED
        )
    }

    companion object {
        const val CURRENT_VERSION = 1

        private val base64 = Base64.Default.withPadding(Base64.PaddingOption.PRESENT_OPTIONAL)

        fun create(fingerprint: Fingerprint, name: String?, armoredPublicKey: String): PairingPayload =
            PairingPayload(
                v = CURRENT_VERSION,
                fpr = fingerprint.hex,
                name = name,
                pub = armoredPublicKey
            )

        /** Parse a scanned payload. Null for anything malformed. */
        fun decode(text: String): PairingPayload? {
            return try {
                val json = JSONObject(String(base64.decode(text.trim()), Charsets.UTF_8))
                PairingPayload(
                    v = json.getInt("v"),
                    fpr = json.getString("fpr"),
                    name = if (json.has("name")) json.getString("name") else null,
                    pub = json.getString("pub")
                )
            } catch (e: Exception) {
                null
            }
        }
    }
}
