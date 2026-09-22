// ContactModels.kt
// CarrierPony Android
//
// Contact and its trust level, ported from the corresponding pieces of iOS
// Core/Messaging/ChatModels.swift. The remaining chat models (Message,
// Conversation, delivery state) arrive with the ChatStore phase; Contact is
// needed now because pairing produces one.

package com.carrierpony.app.messaging

import com.carrierpony.app.crypto.Fingerprint
import com.carrierpony.app.crypto.PublicKey

enum class TrustLevel(val wire: String) {
    UNVERIFIED("unverified"),
    VERIFIED("verified");

    companion object {
        fun from(wire: String): TrustLevel =
            entries.firstOrNull { it.wire == wire } ?: UNVERIFIED
    }
}

data class Contact(
    val fingerprint: Fingerprint,
    val publicKey: PublicKey,
    val name: String? = null,       // display name the peer told us (travels via pairing)
    val trust: TrustLevel = TrustLevel.UNVERIFIED,
    val nickname: String? = null,   // local override you set; wins over `name`
    val smsNumber: String? = null   // phone number for the SMS transport (foss); set locally
) {
    val id: String get() = fingerprint.hex

    /** What to show for this contact: your nickname, else their sent name. */
    val displayName: String? get() = nickname ?: name
}
