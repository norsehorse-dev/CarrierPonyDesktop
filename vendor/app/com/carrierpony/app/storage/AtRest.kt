// AtRest.kt
// CarrierPony Android
//
// The one place the app's state files (conversations, contacts, attachments)
// touch the disk. On the phones those files sit in app-private storage the OS
// already walls off, so no codec is installed and reads and writes are the
// plain File calls they always were. The desktop client compiles this file
// verbatim and installs a codec that seals every file under its launch
// passphrase, because a desktop home directory is readable by any process
// running as the user.
//
// `name` is the file's own name, passed so a codec can bind ciphertext to the
// file it belongs to.

package com.carrierpony.app.storage

import java.io.File

interface AtRestCodec {
    fun seal(name: String, plaintext: ByteArray): ByteArray
    fun open(name: String, stored: ByteArray): ByteArray
}

object AtRest {

    /** Null (the default, and always on Android) stores bytes as they are. */
    @Volatile var codec: AtRestCodec? = null

    fun writeBytes(file: File, bytes: ByteArray) {
        val c = codec
        file.writeBytes(if (c == null) bytes else c.seal(file.name, bytes))
    }

    fun readBytes(file: File): ByteArray {
        val stored = file.readBytes()
        val c = codec
        return if (c == null) stored else c.open(file.name, stored)
    }

    fun writeText(file: File, text: String) = writeBytes(file, text.toByteArray(Charsets.UTF_8))

    fun readText(file: File): String = String(readBytes(file), Charsets.UTF_8)
}
