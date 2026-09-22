// Identity.kt
// CarrierPony Android
//
// The app's own messaging identity, ported from iOS Core/App/Identity.swift:
// a v4 Ed25519 signing + Cv25519 encryption key (or a v6 key; the crypto
// engine detects the version from the bytes). The secret key never leaves the
// device; IdentityStore keeps it sealed under an Android Keystore key, the
// platform's counterpart to the iOS Keychain.
//
// IdentityRecord is the JSON shape the sealed blob contains — the same record
// the iOS Keychain item stores (fingerprint, base64 secret key, armored
// public key, protected flag), kept identical so identity backups translate
// cleanly between platforms later.

package com.carrierpony.app.identity

import com.carrierpony.app.crypto.Fingerprint
import org.json.JSONObject
import kotlin.io.encoding.Base64

data class Identity(
    val fingerprint: Fingerprint,
    val secretKey: ByteArray,        // serialized OpenPGP secret key bytes
    val armoredPublicKey: String,    // ASCII-armored public key
    val protected: Boolean = false   // true if secretKey is passphrase-protected (imported)
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Identity) return false
        return fingerprint == other.fingerprint &&
            secretKey.contentEquals(other.secretKey) &&
            armoredPublicKey == other.armoredPublicKey &&
            protected == other.protected
    }

    override fun hashCode(): Int = fingerprint.hashCode()
}

class IdentityStoreException(message: String) : Exception(message)

object IdentityRecord {

    fun encode(identity: Identity): String {
        val json = JSONObject()
        json.put("fingerprint", identity.fingerprint.hex)
        json.put("secretKeyB64", Base64.Default.encode(identity.secretKey))
        json.put("armoredPublicKey", identity.armoredPublicKey)
        json.put("protected", identity.protected)
        return json.toString()
    }

    /** Parse a record. Null for malformed JSON, bad fingerprint, or bad base64.
     *  A record without the protected flag (older writers) reads as false. */
    fun decode(text: String): Identity? {
        return try {
            val json = JSONObject(text)
            val fingerprint = Fingerprint.from(json.getString("fingerprint")) ?: return null
            val secretKey = Base64.Default.decode(json.getString("secretKeyB64"))
            Identity(
                fingerprint = fingerprint,
                secretKey = secretKey,
                armoredPublicKey = json.getString("armoredPublicKey"),
                protected = json.optBoolean("protected", false)
            )
        } catch (e: Exception) {
            null
        }
    }
}
