// AttachmentStore.kt
// CarrierPony Android
//
// Attachment bytes are stored as individual files on disk rather than inline
// in the conversation JSON. That keeps the conversation store small and cheap
// to rewrite, and keeps large blobs out of memory until they're actually
// viewed. Files are content-addressed by a random id prefix so names never
// collide. Ported from iOS Core/App/AttachmentStore.swift.
//
// The directory defaults to a JVM temp location so unit tests work without
// Android; AppModel points it at the app's private files dir on startup.

package com.carrierpony.app.attachments

import com.carrierpony.app.storage.AtRest
import java.io.File
import java.util.UUID

object AttachmentStore {

    var directory: File = File(System.getProperty("java.io.tmpdir"), "carrierpony-attachments")
        set(value) {
            field = value
            value.mkdirs()
        }

    /** Persist bytes and return the on-disk relative name to store on the message. */
    fun save(data: ByteArray, suggestedName: String): String {
        directory.mkdirs()
        val name = UUID.randomUUID().toString() + "_" + sanitize(suggestedName)
        try {
            AtRest.writeBytes(File(directory, name), data)
        } catch (e: Exception) {
            // Mirrors iOS's try? — a failed write yields a dangling path, never a crash.
        }
        return name
    }

    fun file(localPath: String): File = File(directory, localPath)

    fun data(localPath: String): ByteArray? =
        try { file(localPath).takeIf { it.exists() }?.let { AtRest.readBytes(it) } } catch (e: Exception) { null }

    fun delete(localPath: String) {
        file(localPath).delete()
    }

    /** Write a temporary copy under the original filename, for share/save/open. */
    fun temporaryCopy(localPath: String, filename: String): File? {
        val bytes = data(localPath) ?: return null
        return try {
            val tmp = File.createTempFile("cp-share-", "-" + sanitize(filename))
            tmp.writeBytes(bytes)
            tmp
        } catch (e: Exception) {
            null
        }
    }

    fun sanitize(name: String): String {
        val allowed = ("ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789._- ").toSet()
        val cleaned = name.filter { it in allowed }.trim()
        return if (cleaned.isEmpty()) "file" else cleaned.take(120)
    }
}
