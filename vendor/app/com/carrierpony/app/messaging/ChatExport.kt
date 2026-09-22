// ChatExport.kt
// CarrierPony Android
//
// Local, user-initiated export of one conversation to a file, ported from iOS
// Core/Messaging/ChatExport.swift. Nothing here touches the relay or the
// network. The rendered transcript can optionally be sealed with a passphrase
// via CPPassphraseBox (the same OpenPGP symmetric box used for identity
// backups), so the default path leaves no plaintext at rest. Attachment bytes
// are never embedded; attachments are listed by name and size only.

package com.carrierpony.app.messaging

import com.carrierpony.app.crypto.Fingerprint
import com.carrierpony.core.CPPassphraseBox
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class ExportFormat(val ext: String) {
    TXT("txt"),
    JSON("json"),
    HTML("html")
}

object ChatExport {

    /** Render a conversation into a file inside [dir] and return it. When
     *  [passphrase] is non-empty the bytes are sealed with CPPassphraseBox and
     *  the file gets a .<fmt>.asc extension; otherwise plaintext is written.
     *  Attachment bytes are never embedded — listed by name and size only. */
    fun writeFile(
        conversation: Conversation,
        peerName: String,
        format: ExportFormat,
        passphrase: String?,
        dir: File
    ): File {
        val content = render(conversation.sortedMessages, peerName, conversation.peer, format)
        val base = "CarrierPony-${safeName(peerName)}-${stamp()}"
        val bytes: ByteArray
        val ext: String
        if (!passphrase.isNullOrEmpty()) {
            bytes = CPPassphraseBox.seal(content, passphrase).toByteArray(Charsets.UTF_8)
            ext = "${format.ext}.asc"
        } else {
            bytes = content
            ext = format.ext
        }
        if (!dir.exists()) dir.mkdirs()
        val file = File(dir, "$base.$ext")
        file.writeBytes(bytes)
        return file
    }

    // ── Rendering ──────────────────────────────────────────────────────

    private fun render(messages: List<ChatMessage>, peerName: String, fingerprint: Fingerprint, format: ExportFormat): ByteArray =
        when (format) {
            ExportFormat.TXT -> renderText(messages, peerName, fingerprint).toByteArray(Charsets.UTF_8)
            ExportFormat.JSON -> renderJson(messages, peerName, fingerprint).toByteArray(Charsets.UTF_8)
            ExportFormat.HTML -> renderHtml(messages, peerName, fingerprint).toByteArray(Charsets.UTF_8)
        }

    private fun who(m: ChatMessage, peerName: String) =
        if (m.direction == MessageDirection.OUTGOING) "Me" else peerName

    private fun renderText(messages: List<ChatMessage>, peerName: String, fingerprint: Fingerprint): String {
        val sb = StringBuilder()
        sb.append("CarrierPony chat export\n")
        sb.append("With: $peerName (${fingerprint.hex})\n")
        sb.append("Exported: ${medium(Date())}\n")
        sb.append("Messages auto-expire in CarrierPony; this exported copy does not.\n\n")
        for (m in messages) {
            val whenStr = medium(Date(m.sentAt * 1000))
            val line = m.text?.takeIf { it.isNotEmpty() } ?: ""
            sb.append("[$whenStr] ${who(m, peerName)}: $line\n")
            for (a in m.attachments) {
                sb.append("    [attachment: ${a.filename} (${byteLabel(a.size)})]\n")
            }
        }
        return sb.toString()
    }

    private fun renderJson(messages: List<ChatMessage>, peerName: String, fingerprint: Fingerprint): String {
        val doc = JSONObject()
        doc.put("app", "CarrierPony")
        doc.put("exportedAt", iso(Date()))
        doc.put("peer", peerName)
        doc.put("fingerprint", fingerprint.hex)
        doc.put("note", "Messages auto-expire in CarrierPony; this exported copy does not. Attachment bytes are not included.")
        val arr = JSONArray()
        for (m in messages) {
            val mo = JSONObject()
            mo.put("from", if (m.direction == MessageDirection.OUTGOING) "me" else "peer")
            mo.put("sentAt", iso(Date(m.sentAt * 1000)))
            mo.put("text", m.text ?: JSONObject.NULL)
            val atts = JSONArray()
            for (a in m.attachments) {
                atts.put(JSONObject().put("filename", a.filename).put("size", a.size))
            }
            mo.put("attachments", atts)
            arr.put(mo)
        }
        doc.put("messages", arr)
        return doc.toString(2)
    }

    private fun renderHtml(messages: List<ChatMessage>, peerName: String, fingerprint: Fingerprint): String {
        val rows = StringBuilder()
        for (m in messages) {
            val side = if (m.direction == MessageDirection.OUTGOING) "out" else "in"
            val whenStr = esc(medium(Date(m.sentAt * 1000)))
            val inner = StringBuilder()
            m.text?.takeIf { it.isNotEmpty() }?.let { inner.append("<div class=\"text\">${esc(it)}</div>") }
            for (a in m.attachments) {
                inner.append("<div class=\"att\">${esc(a.filename)} (${esc(byteLabel(a.size))})</div>")
            }
            rows.append("<div class=\"row $side\"><div class=\"bubble\">$inner<div class=\"meta\">${esc(who(m, peerName))} &middot; $whenStr</div></div></div>\n")
        }
        return """
        <!doctype html>
        <html lang="en"><head><meta charset="utf-8">
        <meta name="viewport" content="width=device-width, initial-scale=1">
        <title>CarrierPony chat with ${esc(peerName)}</title>
        <style>
        :root { color-scheme: light dark; }
        body { margin: 0; background: #f2f2f7; color: #111; font: 15px/1.4 -apple-system, system-ui, sans-serif; }
        header { padding: 16px; background: #fff; border-bottom: 1px solid #ddd; }
        header h1 { margin: 0 0 4px; font-size: 17px; }
        header .sub { color: #666; font-size: 12px; word-break: break-all; }
        header .warn { color: #8a6d00; font-size: 12px; margin-top: 6px; }
        main { max-width: 720px; margin: 0 auto; padding: 12px; }
        .row { display: flex; margin: 3px 0; }
        .row.out { justify-content: flex-end; }
        .bubble { max-width: 78%; padding: 8px 12px; border-radius: 18px; background: #fff; }
        .row.out .bubble { background: #2f6bff; color: #fff; }
        .att { font-size: 13px; opacity: 0.85; margin-top: 4px; }
        .meta { font-size: 11px; opacity: 0.6; margin-top: 4px; }
        @media (prefers-color-scheme: dark) {
          body { background: #000; color: #eee; }
          header { background: #1c1c1e; border-color: #333; }
          .bubble { background: #26262a; }
        }
        </style></head>
        <body>
        <header>
          <h1>Chat with ${esc(peerName)}</h1>
          <div class="sub">${esc(fingerprint.hex)}</div>
          <div class="sub">Exported ${esc(medium(Date()))}</div>
          <div class="warn">Messages auto-expire in CarrierPony. This exported copy does not, so it outlives the disappear time.</div>
        </header>
        <main>
        $rows
        </main>
        </body></html>
        """.trimIndent()
    }

    // ── Helpers ────────────────────────────────────────────────────────

    private fun esc(s: String): String =
        s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    private fun medium(d: Date): String =
        DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(d)

    private fun iso(d: Date): String =
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US).format(d)

    private fun byteLabel(n: Long): String = when {
        n < 1024 -> "$n B"
        n < 1024 * 1024 -> String.format(Locale.US, "%.1f KB", n / 1024.0)
        n < 1024 * 1024 * 1024 -> String.format(Locale.US, "%.1f MB", n / (1024.0 * 1024))
        else -> String.format(Locale.US, "%.1f GB", n / (1024.0 * 1024 * 1024))
    }

    private fun safeName(s: String): String {
        val mapped = s.map { if (it.isLetterOrDigit()) it else '-' }.joinToString("")
        val trimmed = mapped.trim('-')
        return if (trimmed.isEmpty()) "chat" else trimmed.take(40)
    }

    private fun stamp(): String = SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date())
}
