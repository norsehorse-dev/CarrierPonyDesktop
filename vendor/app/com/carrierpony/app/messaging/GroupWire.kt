// GroupWire.kt
// CarrierPony Android
//
// Wire payload for group-key control envelopes, ported from iOS
// Core/Messaging/GroupWire.swift. Carries the current epoch key (base64) plus the
// full roster, PGP-encrypted to one member on join or rekey, and sent silent. The
// JSON keys match iOS exactly (Codable): group_id / epoch / key_b64 / name /
// members, and each member's fingerprint is a nested {"hex": ...} object as Swift's
// synthesized Codable emits it, so both platforms parse each other's payloads.

package com.carrierpony.app.messaging

import com.carrierpony.app.crypto.Fingerprint
import org.json.JSONArray
import org.json.JSONObject

data class GroupKeyPayload(
    val groupID: String,
    val epoch: Int,
    val keyB64: String,
    val name: String,
    val members: List<GroupMember>,
    val isChannel: Boolean = false
) {
    fun encoded(): ByteArray {
        val o = JSONObject()
        o.put("group_id", groupID)
        o.put("epoch", epoch)
        o.put("key_b64", keyB64)
        o.put("name", name)
        o.put("members", membersToJson(members))
        if (isChannel) o.put("is_channel", true)
        return o.toString().toByteArray(Charsets.UTF_8)
    }

    companion object {
        fun decode(data: ByteArray): GroupKeyPayload? = try {
            val o = JSONObject(String(data, Charsets.UTF_8))
            GroupKeyPayload(
                groupID = o.getString("group_id"),
                epoch = o.getInt("epoch"),
                keyB64 = o.getString("key_b64"),
                name = o.getString("name"),
                members = membersFromJson(o.getJSONArray("members")),
                isChannel = o.optBoolean("is_channel", false)
            )
        } catch (e: Exception) {
            null
        }
    }
}

// Member <-> JSON, matching iOS's synthesized Codable (fingerprint nested {"hex"}).
internal fun memberToJson(m: GroupMember): JSONObject {
    val o = JSONObject()
    o.put("fingerprint", JSONObject().put("hex", m.fingerprint.hex))
    o.put("armoredPublicKey", m.armoredPublicKey)
    if (m.name != null) o.put("name", m.name)
    o.put("isAdmin", m.isAdmin)
    return o
}

internal fun membersToJson(list: List<GroupMember>): JSONArray {
    val a = JSONArray()
    for (m in list) a.put(memberToJson(m))
    return a
}

internal fun memberFromJson(o: JSONObject): GroupMember? {
    val fprHex = o.getJSONObject("fingerprint").getString("hex")
    val fpr = Fingerprint.from(fprHex) ?: return null
    return GroupMember(
        fingerprint = fpr,
        armoredPublicKey = o.getString("armoredPublicKey"),
        name = if (o.has("name") && !o.isNull("name")) o.getString("name") else null,
        isAdmin = o.optBoolean("isAdmin", false)
    )
}

internal fun membersFromJson(a: JSONArray): List<GroupMember> {
    val list = mutableListOf<GroupMember>()
    for (i in 0 until a.length()) memberFromJson(a.getJSONObject(i))?.let { list.add(it) }
    return list
}
