// Attachments.kt
// CarrierPony Desktop. Everything about files that is not drawing: turning dropped, picked or
// pasted things into outgoing attachments, saving received ones without ever overwriting, and
// making thumbnails. No Compose imports, so it is unit-tested; Gui.kt only calls it.
//
// Received attachments are sealed on disk (AtRest), so nothing here hands out the stored file
// itself. Bytes always come from AttachmentStore.data(), which opens them.

package com.carrierpony.desktop

import com.carrierpony.app.AppConfig
import com.carrierpony.app.attachments.AttachmentStore
import com.carrierpony.app.messaging.ChatMessage
import com.carrierpony.app.messaging.OutgoingMessage
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.text.SimpleDateFormat
import java.util.Date
import javax.imageio.ImageIO

object Attachments {

    class Rejected(message: String) : Exception(message)

    /** Read files into outgoing attachments. Sizes are checked BEFORE anything is read, so a
     *  dropped 4 GB video is refused without being pulled into memory. [alreadyPending] is the
     *  byte count of attachments already queued on the composer. */
    fun fromFiles(paths: List<Path>, alreadyPending: Long = 0): List<OutgoingMessage.Attachment> {
        if (paths.isEmpty()) return emptyList()
        var total = alreadyPending
        for (p in paths) {
            if (Files.isDirectory(p)) throw Rejected("${p.fileName} is a folder. Zip it first to send it.")
            if (!Files.isRegularFile(p) || !Files.isReadable(p)) throw Rejected("${p.fileName} cannot be read.")
            total += Files.size(p)
        }
        if (total > AppConfig.maxAttachmentBytes) throw Rejected(tooLarge())
        return paths.map { p ->
            OutgoingMessage.Attachment(
                filename = AttachmentStore.sanitize(p.fileName.toString()),
                mime = mimeOf(p),
                data = Files.readAllBytes(p)
            )
        }
    }

    /** An image pasted from the clipboard, encoded as PNG. */
    fun fromImage(image: java.awt.Image, alreadyPending: Long = 0, now: Date = Date()): OutgoingMessage.Attachment {
        val buffered = toBuffered(image)
        val out = ByteArrayOutputStream()
        ImageIO.write(buffered, "png", out)
        val bytes = out.toByteArray()
        if (alreadyPending + bytes.size > AppConfig.maxAttachmentBytes) throw Rejected(tooLarge())
        val name = "pasted-" + SimpleDateFormat("yyyyMMdd-HHmmss").format(now) + ".png"
        return OutgoingMessage.Attachment(name, "image/png", bytes)
    }

    private fun tooLarge(): String =
        "That is too large to send (limit ${AppConfig.maxAttachmentBytes / (1024 * 1024)} MB per message)."

    // ── Mime ───────────────────────────────────────────────────────────

    private val byExtension = mapOf(
        "png" to "image/png", "jpg" to "image/jpeg", "jpeg" to "image/jpeg", "gif" to "image/gif",
        "webp" to "image/webp", "heic" to "image/heic", "bmp" to "image/bmp", "svg" to "image/svg+xml",
        "pdf" to "application/pdf", "txt" to "text/plain", "md" to "text/markdown", "csv" to "text/csv",
        "json" to "application/json", "zip" to "application/zip", "gz" to "application/gzip",
        "mp4" to "video/mp4", "mov" to "video/quicktime", "mp3" to "audio/mpeg", "m4a" to "audio/mp4",
        "wav" to "audio/wav", "asc" to "application/pgp-encrypted", "gpg" to "application/pgp-encrypted"
    )

    /** The extension table first, then the OS. Files.probeContentType differs by platform (and
     *  returns null on some), and the phones decide "is this an image" from the mime string, so
     *  the common types must not depend on which desktop sent them. */
    fun mimeOf(path: Path): String {
        val ext = path.fileName.toString().substringAfterLast('.', "").lowercase()
        byExtension[ext]?.let { return it }
        return runCatching { Files.probeContentType(path) }.getOrNull() ?: "application/octet-stream"
    }

    // ── Saving ─────────────────────────────────────────────────────────

    fun defaultDownloadsDir(): Path = Paths.get(System.getProperty("user.home"), "Downloads")

    /** Write a received attachment into [dir] under its own name, or "name (1).ext" and so on
     *  when that exists. Never overwrites. Returns the file written, or null when the stored
     *  bytes are gone or will not open. */
    fun saveTo(attachment: ChatMessage.Attachment, dir: Path): Path? {
        val bytes = AttachmentStore.data(attachment.localPath) ?: return null
        Files.createDirectories(dir)
        val target = uniqueName(dir, AttachmentStore.sanitize(attachment.filename))
        Files.write(target, bytes, java.nio.file.StandardOpenOption.CREATE_NEW)
        return target
    }

    fun uniqueName(dir: Path, filename: String): Path {
        var candidate = dir.resolve(filename)
        if (!Files.exists(candidate)) return candidate
        val dot = filename.lastIndexOf('.')
        val stem = if (dot > 0) filename.substring(0, dot) else filename
        val ext = if (dot > 0) filename.substring(dot) else ""
        var n = 1
        while (Files.exists(candidate)) { candidate = dir.resolve("$stem ($n)$ext"); n++ }
        return candidate
    }

    /** A plaintext temporary copy for "Open with the default app", removed when the app exits.
     *  This is the one place a received file leaves the sealed store without the user choosing
     *  where it goes, which is why it is a separate, explicit action in the window. */
    fun temporaryCopyForOpening(attachment: ChatMessage.Attachment): Path? {
        val file = AttachmentStore.temporaryCopy(attachment.localPath, attachment.filename) ?: return null
        file.deleteOnExit()
        return file.toPath()
    }

    // ── Thumbnails ─────────────────────────────────────────────────────

    /** A thumbnail no larger than [maxPx] on its long side, or null when the attachment is not an
     *  image ImageIO can read (HEIC and WebP, for instance, are not in the JDK). Never upscales. */
    fun thumbnail(attachment: ChatMessage.Attachment, maxPx: Int = 320): BufferedImage? {
        if (!attachment.isImage) return null
        val bytes = AttachmentStore.data(attachment.localPath) ?: return null
        return thumbnail(bytes, maxPx)
    }

    fun thumbnail(bytes: ByteArray, maxPx: Int = 320): BufferedImage? {
        val source = runCatching { ImageIO.read(ByteArrayInputStream(bytes)) }.getOrNull() ?: return null
        val long = maxOf(source.width, source.height)
        if (long <= maxPx) return source
        val scale = maxPx.toDouble() / long
        val w = maxOf(1, (source.width * scale).toInt())
        val h = maxOf(1, (source.height * scale).toInt())
        val out = BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB)
        val g = out.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
        g.drawImage(source, 0, 0, w, h, null)
        g.dispose()
        return out
    }

    private fun toBuffered(image: java.awt.Image): BufferedImage {
        if (image is BufferedImage) return image
        val w = image.getWidth(null); val h = image.getHeight(null)
        require(w > 0 && h > 0) { "the pasted image has no size" }
        val out = BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB)
        val g = out.createGraphics()
        g.drawImage(image, 0, 0, null)
        g.dispose()
        return out
    }

    fun humanSize(bytes: Long): String = when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "${bytes / 1024} KB"
        else -> String.format("%.1f MB", bytes / (1024.0 * 1024.0))
    }
}
