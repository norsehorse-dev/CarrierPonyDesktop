// PairingSupport.kt
// CarrierPony Android
//
// The key-consistency gate shared by both relay pairing directions, ported
// from AppModel.consistentContact on iOS: a Contact is built only if the
// armored key actually hashes to the claimed fingerprint. Returns null on a
// malformed key or any mismatch — we never add a contact whose key doesn't
// match. Lives outside AppModel so it unit-tests without a Context.

package com.carrierpony.app.pairing

import com.carrierpony.app.crypto.Fingerprint
import com.carrierpony.app.crypto.PublicKey
import com.carrierpony.app.messaging.Contact
import com.carrierpony.app.messaging.TrustLevel
import com.carrierpony.core.CPKeyInfo

object PairingSupport {

    fun consistentContact(fprHex: String, armoredPub: String, trust: TrustLevel): Contact? {
        val fingerprint = Fingerprint.from(fprHex) ?: return null
        val computed = CPKeyInfo.primaryFingerprint(armoredPub) ?: return null
        if (computed.uppercase() != fingerprint.hex) return null
        return Contact(
            fingerprint = fingerprint,
            publicKey = PublicKey(fingerprint, armoredPub),
            name = null,
            trust = trust
        )
    }
}
