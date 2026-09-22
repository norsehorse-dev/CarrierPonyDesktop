// ChatModels.kt
// CarrierPony Android
//
// The chat domain models, ported from iOS Core/Messaging/ChatModels.swift
// (Contact and TrustLevel arrived earlier in ContactModels.kt). Kotlin
// versions are immutable data classes; ChatStore replaces instances instead
// of mutating fields, with the same observable results.

package com.carrierpony.app.messaging

import com.carrierpony.app.crypto.Fingerprint

enum class MessageDirection {
    INCOMING,
    OUTGOING
}

data class ChatMessage(
    val id: String,
    val threadID: String,
    val peer: Fingerprint,
    val direction: MessageDirection,
    val text: String?,
    val attachments: List<Attachment>,
    val sentAt: Long,
    val expiresAt: Long,
    val isRead: Boolean,
    // True when this message was delivered/received over the local network
    // (LAN-direct) rather than fetched from the relay. Drives a subtle indicator.
    val viaLan: Boolean = false
) {

    // Attachment bytes live on disk (see AttachmentStore); the message only
    // carries metadata + a local path, so large files don't bloat the
    // conversation store or sit in memory.
    data class Attachment(
        val id: String,
        val filename: String,
        val mime: String,
        val size: Long,
        val localPath: String
    ) {
        val isImage: Boolean get() = mime.startsWith("image/")
    }
}

data class Conversation(
    val threadID: String,
    val peer: Fingerprint,
    val peerName: String?,
    val messages: List<ChatMessage>
) {
    val id: String get() = threadID

    val sortedMessages: List<ChatMessage>
        get() = messages.sortedBy { it.sentAt }

    val lastMessage: ChatMessage?
        get() = messages.maxByOrNull { it.sentAt }

    val unreadCount: Int
        get() = messages.count { it.direction == MessageDirection.INCOMING && !it.isRead }
}
