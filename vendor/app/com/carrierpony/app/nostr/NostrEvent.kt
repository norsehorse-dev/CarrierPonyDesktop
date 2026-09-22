package com.carrierpony.app.nostr

import java.security.MessageDigest

/** One Nostr event (NIP-01). */
class NostrEvent(
    val id: String,
    val pubkey: String,
    val createdAt: Long,
    val kind: Int,
    val tags: List<List<String>>,
    val content: String,
    val sig: String,
) {
    /** Wire JSON object relays accept in ["EVENT", <this>]. */
    fun json(): String {
        val sb = StringBuilder("{")
        sb.append("\"id\":").append(NostrSerialize.str(id)).append(",")
        sb.append("\"pubkey\":").append(NostrSerialize.str(pubkey)).append(",")
        sb.append("\"created_at\":").append(createdAt).append(",")
        sb.append("\"kind\":").append(kind).append(",")
        sb.append("\"tags\":").append(NostrSerialize.tags(tags)).append(",")
        sb.append("\"content\":").append(NostrSerialize.str(content)).append(",")
        sb.append("\"sig\":").append(NostrSerialize.str(sig))
        sb.append("}")
        return sb.toString()
    }
}

/** NIP-01 compact serialization (no whitespace, minimal escaping). */
object NostrSerialize {
    fun str(s: String): String {
        val sb = StringBuilder("\"")
        for (c in s) when (c) {
            '"' -> sb.append("\\\"")
            '\\' -> sb.append("\\\\")
            '\n' -> sb.append("\\n")
            '\r' -> sb.append("\\r")
            '\t' -> sb.append("\\t")
            '\b' -> sb.append("\\b")
            '\u000C' -> sb.append("\\f")
            else -> sb.append(c)
        }
        return sb.append("\"").toString()
    }

    fun tags(tags: List<List<String>>): String {
        val sb = StringBuilder("[")
        for ((i, tag) in tags.withIndex()) {
            if (i > 0) sb.append(",")
            sb.append("[")
            for ((j, v) in tag.withIndex()) { if (j > 0) sb.append(","); sb.append(str(v)) }
            sb.append("]")
        }
        return sb.append("]").toString()
    }

    /** The id preimage: [0,pubkey,created_at,kind,tags,content]. */
    fun idPreimage(pubkey: String, createdAt: Long, kind: Int, tags: List<List<String>>, content: String): String {
        val sb = StringBuilder("[0,")
        sb.append(str(pubkey)).append(",")
        sb.append(createdAt).append(",")
        sb.append(kind).append(",")
        sb.append(tags(tags)).append(",")
        sb.append(str(content))
        return sb.append("]").toString()
    }
}

object NostrEventBuilder {
    /** Build + sign an event with a fresh (or given) ephemeral secp256k1 key. */
    fun build(seckey: ByteArray, createdAt: Long, kind: Int, tags: List<List<String>>, content: String): NostrEvent {
        val pubkey = Secp256k1.xOnlyPubkey(seckey).toHex()
        val preimage = NostrSerialize.idPreimage(pubkey, createdAt, kind, tags, content)
        val id = MessageDigest.getInstance("SHA-256").digest(preimage.toByteArray(Charsets.UTF_8))
        val sig = Secp256k1.schnorrSign(seckey, id, Secp256k1.randomAux())
        return NostrEvent(id.toHex(), pubkey, createdAt, kind, tags, content, sig.toHex())
    }
}

internal fun ByteArray.toHex(): String {
    val hex = "0123456789abcdef"
    val sb = StringBuilder(size * 2)
    for (b in this) { val v = b.toInt() and 0xFF; sb.append(hex[v ushr 4]); sb.append(hex[v and 0x0F]) }
    return sb.toString()
}
