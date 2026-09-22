// Manifest.kt
// CarrierPony Android
//
// The JSON manifest at the front of every CPN1 container, ported from iOS
// Core/Envelope/Manifest.swift with the same wire keys (snake_case where iOS
// declares CodingKeys). Optional fields are simply absent when null, matching
// JSONEncoder's behavior, and decode tolerates extra keys, so the two
// platforms' manifests are mutually parseable regardless of key order.
//
// Threading lives here as on iOS: a thread ID is the SHA-256 (lowercase hex)
// of the two participants' uppercase fingerprints concatenated in sorted
// order, so both sides derive the same ID with no coordination.

package com.carrierpony.app.envelope

import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

data class Manifest(
    val v: Int,
    val type: String,
    val messageID: String,
    val threadID: String,
    val to: String? = null,
    val sentAt: Long,
    val expiresAt: Long,
    val parts: List<Part>,
    val groupID: String? = null,
    val epoch: Int? = null
) {

    data class Part(
        val kind: String,
        val mime: String? = null,
        val filename: String? = null,
        val size: Long? = null
    )

    fun encoded(): ByteArray {
        val json = JSONObject()
        json.put("v", v)
        json.put("type", type)
        json.put("message_id", messageID)
        json.put("thread_id", threadID)
        if (to != null) json.put("to", to)
        if (groupID != null) json.put("group_id", groupID)
        if (epoch != null) json.put("epoch", epoch)
        json.put("sent_at", sentAt)
        json.put("expires_at", expiresAt)
        val partsArray = JSONArray()
        for (part in parts) {
            val obj = JSONObject()
            obj.put("kind", part.kind)
            if (part.mime != null) obj.put("mime", part.mime)
            if (part.filename != null) obj.put("filename", part.filename)
            if (part.size != null) obj.put("size", part.size)
            partsArray.put(obj)
        }
        json.put("parts", partsArray)
        return json.toString().toByteArray(Charsets.UTF_8)
    }

    companion object {
        /** Parse a manifest. Throws org.json.JSONException when required keys are missing. */
        fun decode(data: ByteArray): Manifest {
            val json = JSONObject(String(data, Charsets.UTF_8))
            val partsArray = json.getJSONArray("parts")
            val parts = mutableListOf<Part>()
            for (i in 0 until partsArray.length()) {
                val obj = partsArray.getJSONObject(i)
                parts.add(Part(
                    kind = obj.getString("kind"),
                    mime = if (obj.has("mime")) obj.getString("mime") else null,
                    filename = if (obj.has("filename")) obj.getString("filename") else null,
                    size = if (obj.has("size")) obj.getLong("size") else null
                ))
            }
            return Manifest(
                v = json.getInt("v"),
                type = json.getString("type"),
                messageID = json.getString("message_id"),
                threadID = json.getString("thread_id"),
                to = if (json.has("to")) json.getString("to") else null,
                sentAt = json.getLong("sent_at"),
                expiresAt = json.getLong("expires_at"),
                parts = parts,
                groupID = if (json.has("group_id")) json.getString("group_id") else null,
                epoch = if (json.has("epoch")) json.getInt("epoch") else null
            )
        }
    }
}

object Threading {

    /** The pairwise thread ID for two fingerprints, order-independent. */
    fun pairwise(a: String, b: String): String {
        val upperA = a.uppercase()
        val upperB = b.uppercase()
        val (lo, hi) = if (upperA <= upperB) upperA to upperB else upperB to upperA
        val digest = MessageDigest.getInstance("SHA-256").digest((lo + hi).toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it.toInt() and 0xFF) }
    }
}
