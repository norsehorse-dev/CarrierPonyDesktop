// Invite.kt
// CarrierPony Android
//
// An invite is what the offerer sends over a channel they already trust
// (Signal, SMS, email, in person). It carries the one-time relay token plus
// the offerer's fingerprint. The fingerprint is the anti-MITM anchor: when
// the invitee accepts, their app fetches the offerer's key from the relay and
// requires it to hash to this fingerprint before adding the contact. Because
// the fingerprint travelled out-of-band, the relay cannot substitute a key
// without the invitee noticing.
//
// Wire form: "CPPAIR1:" + base64url(JSON). Compact enough for a text message
// or a QR, and prefixed so a pasted invite is easy to recognize. Ported from
// iOS Core/Pairing/Invite.swift; both platforms must parse each other's
// invites.

package com.carrierpony.app.pairing

import com.carrierpony.app.crypto.Fingerprint
import org.json.JSONObject
import kotlin.io.encoding.Base64

data class Invite(
    val v: Int,
    val t: String,      // one-time relay token (32 hex)
    val f: String,      // offerer fingerprint (40 hex)
    val n: String?      // offerer display name, optional
) {

    val fingerprint: Fingerprint? get() = Fingerprint.from(f)

    fun encoded(): String {
        val json = JSONObject()
        json.put("v", v)
        json.put("t", t)
        json.put("f", f)
        if (n != null) json.put("n", n)
        return PREFIX + base64url.encode(json.toString().toByteArray(Charsets.UTF_8))
    }

    companion object {
        const val PREFIX = "CPPAIR1:"
        const val CURRENT_VERSION = 1

        private val base64url = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT_OPTIONAL)
        private val tokenPattern = Regex("^[0-9a-f]{32}$")

        fun create(token: String, fingerprint: Fingerprint, name: String?): Invite = Invite(
            v = CURRENT_VERSION,
            t = token,
            f = fingerprint.hex,
            n = if (name.isNullOrEmpty()) null else name
        )

        /** Parse a pasted or scanned invite. Null for anything malformed. */
        fun decode(text: String): Invite? {
            val trimmed = text.trim()
            if (!trimmed.startsWith(PREFIX)) return null
            val body = trimmed.removePrefix(PREFIX)
            return try {
                val json = JSONObject(String(base64url.decode(body), Charsets.UTF_8))
                val invite = Invite(
                    v = json.getInt("v"),
                    t = json.getString("t"),
                    f = json.getString("f"),
                    n = if (json.has("n")) json.getString("n") else null
                )
                if (invite.fingerprint == null) return null
                if (!tokenPattern.matches(invite.t)) return null
                invite
            } catch (e: Exception) {
                null
            }
        }
    }
}
