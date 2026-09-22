// EnvelopeFactory.kt
// CarrierPony Android
//
// Turns an outgoing message into an encrypted envelope and an incoming
// envelope back into a message, ported from iOS
// Core/Messaging/EnvelopeFactory.swift. The factory owns the CPN1 + Manifest
// assembly; the CryptoEngine owns signing and encryption.

package com.carrierpony.app.messaging

import com.carrierpony.app.crypto.CryptoEngine
import com.carrierpony.app.crypto.Fingerprint
import com.carrierpony.app.crypto.PublicKey
import com.carrierpony.app.envelope.CPN1
import com.carrierpony.app.envelope.Manifest
import java.util.UUID

class OutgoingMessage(
    val threadID: String,
    val text: String? = null,
    val attachments: List<Attachment> = emptyList(),
    val expiresAt: Long
) {
    class Attachment(
        val filename: String,
        val mime: String,
        val data: ByteArray
    )
}

class IncomingMessage(
    val sender: Fingerprint,
    val manifest: Manifest,
    val text: String?,
    val files: List<Pair<Manifest.Part, ByteArray>>
)

class EnvelopeFactory(private val crypto: CryptoEngine) {

    fun build(
        message: OutgoingMessage,
        to: PublicKey,
        messageID: String = UUID.randomUUID().toString().uppercase()
    ): ByteArray {
        val parts = mutableListOf<ByteArray>()
        val meta = mutableListOf<Manifest.Part>()

        if (message.text != null) {
            parts.add(message.text.toByteArray(Charsets.UTF_8))
            meta.add(Manifest.Part(kind = "text", mime = "text/plain; charset=utf-8"))
        }
        for (attachment in message.attachments) {
            parts.add(attachment.data)
            meta.add(Manifest.Part(
                kind = "file",
                mime = attachment.mime,
                filename = attachment.filename,
                size = attachment.data.size.toLong()
            ))
        }

        val manifest = Manifest(
            v = 1,
            type = "message",
            messageID = messageID,
            threadID = message.threadID,
            to = to.fingerprint.hex,
            sentAt = System.currentTimeMillis() / 1000,
            expiresAt = message.expiresAt,
            parts = meta
        )

        val container = CPN1.encode(manifest.encoded(), parts)
        return crypto.signAndEncrypt(container, to)
    }

    fun open(envelope: ByteArray): IncomingMessage {
        val verified = crypto.decryptAndVerify(envelope)
        val decoded = CPN1.decode(verified.plaintext)
        val manifest = Manifest.decode(decoded.manifest)

        var text: String? = null
        val files = mutableListOf<Pair<Manifest.Part, ByteArray>>()
        for ((index, part) in manifest.parts.withIndex()) {
            if (index >= decoded.parts.size) break
            when (part.kind) {
                "text" -> text = String(decoded.parts[index], Charsets.UTF_8)
                // "control" parts carry the ControlOp JSON; expose them the same
                // way as files so ChatStore's control handlers can read the
                // payload. (iOS note: OpenValue's switch drops "control" parts,
                // which silently disables read-receipt sync and profile
                // exchange — patch EnvelopeFactory.open there to match.)
                "file", "control" -> files.add(part to decoded.parts[index])
            }
        }

        return IncomingMessage(
            sender = verified.sender,
            manifest = manifest,
            text = text,
            files = files
        )
    }
}
