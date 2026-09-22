// ChatGroup.kt
// CarrierPony Android
//
// A group's identity and membership, ported from iOS Core/Messaging/ChatGroup.swift.
// The epoch key itself is never stored here; it lives in GroupKeyStore, keyed by
// (groupID, epoch). This value is safe to persist alongside pairwise contacts: it
// holds no secret key material, only public keys and roster metadata.

package com.carrierpony.app.messaging

import com.carrierpony.app.crypto.Fingerprint
import com.carrierpony.app.crypto.PublicKey
import java.security.SecureRandom

data class GroupMember(
    val fingerprint: Fingerprint,
    val armoredPublicKey: String,
    val name: String?,
    val isAdmin: Boolean
) {
    val publicKey: PublicKey get() = PublicKey(fingerprint, armoredPublicKey)
}

data class ChatGroup(
    val groupID: String,   // 16 random bytes, hex; also the group thread's ID
    val name: String,
    val members: List<GroupMember>,
    val epoch: Int,        // current key epoch; bumped on every membership change
    val isChannel: Boolean = false,  // broadcast channel: admins post, others subscribe
    val createdAt: Long = 0L         // local epoch-seconds this device created/joined; sorts empty groups by recency
) {
    fun member(fpr: Fingerprint): GroupMember? = members.firstOrNull { it.fingerprint == fpr }
    fun isAdmin(fpr: Fingerprint): Boolean = member(fpr)?.isAdmin == true
    val admins: List<GroupMember> get() = members.filter { it.isAdmin }

    companion object {
        /** A fresh random group identifier (16 bytes, hex). */
        fun newID(): String {
            val bytes = ByteArray(16)
            SecureRandom().nextBytes(bytes)
            return bytes.joinToString("") { "%02x".format(it.toInt() and 0xFF) }
        }
    }
}
