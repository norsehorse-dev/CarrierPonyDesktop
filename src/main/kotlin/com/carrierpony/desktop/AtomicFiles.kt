// AtomicFiles.kt
// CarrierPony Desktop. Every state file is written whole to a sibling temp file and moved into
// place, so a crash or power cut leaves either the old file or the new one, never half of one.
// Files are created 0600 where the filesystem has POSIX permissions (Windows relies on the
// per-user %APPDATA% ACL instead).

package com.carrierpony.desktop

import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermissions

object AtomicFiles {

    fun write(target: Path, bytes: ByteArray) {
        val dir = target.toAbsolutePath().parent
        Files.createDirectories(dir)
        val tmp = Files.createTempFile(dir, target.fileName.toString() + ".", ".tmp")
        try {
            runCatching { Files.setPosixFilePermissions(tmp, PosixFilePermissions.fromString("rw-------")) }
            Files.write(tmp, bytes)
            try {
                Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (e: AtomicMoveNotSupportedException) {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            Files.deleteIfExists(tmp)
        }
    }
}
