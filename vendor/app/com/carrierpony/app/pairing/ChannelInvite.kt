// ChannelInvite.kt
// CarrierPony Android
//
// A broadcast-channel subscribe invite: a reusable link/QR an admin shares
// publicly so anyone can subscribe. Unlike a pairing Invite (a one-time relay
// token), this carries the admin's public key directly, so a stranger can seal
// a channel-subscribe to the admin with no prior pairing. It reveals only the
// channel id, the admin's identity, and the admin's key - nothing the relay can
// use to map the channel's membership.
//
// Wire form: "CPCHAN1:" + base64url(JSON), matching the CPPAIR1 shape so the
// same scanner and paste box recognize it. Both platforms must parse each
// other's invites.

package com.carrierpony.app.pairing

import com.carrierpony.app.crypto.Fingerprint
import org.json.JSONObject
import kotlin.io.encoding.Base64

data class ChannelInvite(
    val v: Int,
    val c: String,       // channel/group id (32 hex)
    val f: String,       // admin fingerprint (40 hex)
    val k: String,       // admin armored public key
    val t: String?,      // optional invite token (gated channels); null = open
    val n: String?       // channel name, optional (display only)
) {
    val adminFingerprint: Fingerprint? get() = Fingerprint.from(f)

    fun encoded(): String {
        val json = JSONObject()
        json.put("v", v)
        json.put("c", c)
        json.put("f", f)
        json.put("k", k)
        if (t != null) json.put("t", t)
        if (n != null) json.put("n", n)
        return PREFIX + base64url.encode(json.toString().toByteArray(Charsets.UTF_8))
    }

    companion object {
        const val PREFIX = "CPCHAN1:"
        const val CURRENT_VERSION = 1

        private val base64url = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT_OPTIONAL)
        private val channelIdPattern = Regex("^[0-9a-f]{32}$")

        fun create(channelId: String, adminFingerprint: Fingerprint, adminArmoredKey: String,
                   token: String?, name: String?): ChannelInvite = ChannelInvite(
            v = CURRENT_VERSION,
            c = channelId,
            f = adminFingerprint.hex,
            k = adminArmoredKey,
            t = if (token.isNullOrEmpty()) null else token,
            n = if (name.isNullOrEmpty()) null else name
        )

        /** Parse a pasted or scanned channel invite. Null for anything malformed. */
        fun decode(text: String): ChannelInvite? {
            val trimmed = text.trim()
            if (!trimmed.startsWith(PREFIX)) return null
            val body = trimmed.removePrefix(PREFIX)
            return try {
                val json = JSONObject(String(base64url.decode(body), Charsets.UTF_8))
                val invite = ChannelInvite(
                    v = json.getInt("v"),
                    c = json.getString("c").lowercase(),
                    f = json.getString("f"),
                    k = json.getString("k"),
                    t = if (json.has("t")) json.getString("t") else null,
                    n = if (json.has("n")) json.getString("n") else null
                )
                if (invite.adminFingerprint == null) return null
                if (!channelIdPattern.matches(invite.c)) return null
                if (invite.k.isBlank()) return null
                invite
            } catch (e: Exception) {
                null
            }
        }
    }
}
