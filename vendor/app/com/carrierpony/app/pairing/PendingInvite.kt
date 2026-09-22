// PendingInvite.kt
// CarrierPony Android
//
// A pairing offer this identity created that hasn't been accepted yet. Persisted
// per identity so acceptance is still detected after the invite screen — or the
// whole app — has been closed: the invitee may paste an emailed invite hours
// later, and the pending list is swept on every inbox refresh and at the next
// launch until the offer is accepted or expires. Ported from iOS
// Core/Pairing/Invite.swift (PendingInvite). Timestamps are epoch milliseconds
// here (iOS uses Date); only relative comparisons matter, so the units are
// private to this platform.

package com.carrierpony.app.pairing

import com.carrierpony.app.crypto.Fingerprint
import org.json.JSONArray
import org.json.JSONObject

data class PendingInvite(
    val token: String,       // one-time relay token (32 hex)
    val name: String?,       // display name the invite carried when created
    val createdAt: Long,     // epoch millis
    val expiresAt: Long?     // epoch millis; null if the relay gave no expiry
) {
    /** Rebuild the shareable invite. The fingerprint is supplied by the caller
     *  (always the current identity's — the pending list is keyed per identity). */
    fun invite(fingerprint: Fingerprint): Invite =
        Invite.create(token = token, fingerprint = fingerprint, name = name)

    fun toJson(): JSONObject = JSONObject().apply {
        put("token", token)
        if (name != null) put("name", name)
        put("createdAt", createdAt)
        if (expiresAt != null) put("expiresAt", expiresAt)
    }

    companion object {
        fun fromJson(o: JSONObject): PendingInvite = PendingInvite(
            token = o.getString("token"),
            name = if (o.has("name") && !o.isNull("name")) o.getString("name") else null,
            createdAt = o.optLong("createdAt", 0L),
            expiresAt = if (o.has("expiresAt") && !o.isNull("expiresAt")) o.getLong("expiresAt") else null
        )

        fun listToJson(list: List<PendingInvite>): String {
            val arr = JSONArray()
            list.forEach { arr.put(it.toJson()) }
            return arr.toString()
        }

        fun listFromJson(s: String): List<PendingInvite> = try {
            val arr = JSONArray(s)
            (0 until arr.length()).map { fromJson(arr.getJSONObject(it)) }
        } catch (e: Exception) {
            emptyList()
        }
    }
}
