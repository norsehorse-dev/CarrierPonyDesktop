// RelayModels.kt
// CarrierPony Android
//
// Response shapes from api.carrierpony.com, ported from iOS
// Core/Networking/RelayModels.swift. The wire is snake_case JSON (iOS decodes
// with convertFromSnakeCase); here each model reads its snake_case keys
// directly. receivedAt/expiresAt on inbox messages are strings on the wire
// (MariaDB timestamps), matching iOS.

package com.carrierpony.app.relay

import org.json.JSONObject

/** A non-200 (or malformed) relay reply: HTTP status plus the server's error code when present. */
class RelayException(val status: Int, val code: String?) :
    Exception("relay error $status${code?.let { " ($it)" } ?: ""}")

class SendResponse(
    val messageId: String,
    val expiresAt: Long
) {
    companion object {
        fun from(json: JSONObject) = SendResponse(
            messageId = json.getString("message_id"),
            expiresAt = json.getLong("expires_at")
        )
    }
}

class InboxMessage(
    val messageId: String,
    val envelope: String,        // base64 OpenPGP envelope
    val receivedAt: String,
    val expiresAt: String
) {
    companion object {
        fun from(json: JSONObject) = InboxMessage(
            messageId = json.getString("message_id"),
            envelope = json.getString("envelope"),
            receivedAt = json.getString("received_at"),
            expiresAt = json.getString("expires_at")
        )
    }
}

class PairOfferResponse(
    val token: String,
    val expiresIn: Long
) {
    companion object {
        fun from(json: JSONObject) = PairOfferResponse(
            token = json.getString("token"),
            expiresIn = json.getLong("expires_in")
        )
    }
}

class PairAcceptResponse(
    val offererFpr: String,
    val offererPubkey: String
) {
    companion object {
        fun from(json: JSONObject) = PairAcceptResponse(
            offererFpr = json.getString("offerer_fpr"),
            offererPubkey = json.getString("offerer_pubkey")
        )
    }
}

class PairStatusResponse(
    val state: String,
    val responderFpr: String?,
    val responderPubkey: String?
) {
    companion object {
        fun from(json: JSONObject) = PairStatusResponse(
            state = json.getString("state"),
            responderFpr = if (json.has("responder_fpr") && !json.isNull("responder_fpr"))
                json.getString("responder_fpr") else null,
            responderPubkey = if (json.has("responder_pubkey") && !json.isNull("responder_pubkey"))
                json.getString("responder_pubkey") else null
        )
    }
}

class SealedInboxMessage(
    val messageId: String,
    val mailbox: String,
    val envelope: String,
    val receivedAt: String,
    val expiresAt: String
) {
    companion object {
        fun from(json: JSONObject) = SealedInboxMessage(
            messageId = json.getString("message_id"),
            mailbox = json.getString("mailbox"),
            envelope = json.getString("envelope"),
            receivedAt = json.getString("received_at"),
            expiresAt = json.getString("expires_at")
        )
    }
}
