// CPN1.kt
// CarrierPony Android
//
// The CPN1 binary container — the plaintext that gets signed and encrypted.
// Ported byte-for-byte from iOS Core/Envelope/CPN1.swift:
//
//   "CPN1" magic (4 bytes)
//   manifest length, big-endian UInt32 (4 bytes)
//   manifest bytes (JSON)
//   for each part: length, big-endian UInt64 (8 bytes), then part bytes
//
// Both platforms must produce and parse identical bytes here; the container
// travels inside the OpenPGP envelope between them.

package com.carrierpony.app.envelope

import java.io.ByteArrayOutputStream

sealed class CPN1Exception(message: String) : Exception(message) {
    class BadMagic : CPN1Exception("Not a CPN1 container")
    class Truncated : CPN1Exception("Truncated CPN1 container")
}

object CPN1 {

    val magic: ByteArray = "CPN1".toByteArray(Charsets.UTF_8)

    class Decoded(
        val manifest: ByteArray,
        val parts: List<ByteArray>
    )

    fun encode(manifest: ByteArray, parts: List<ByteArray>): ByteArray {
        val out = ByteArrayOutputStream(8 + manifest.size + parts.sumOf { it.size + 8 })
        out.write(magic)
        writeBEUInt32(out, manifest.size.toLong())
        out.write(manifest)
        for (part in parts) {
            writeBEUInt64(out, part.size.toLong())
            out.write(part)
        }
        return out.toByteArray()
    }

    fun decode(blob: ByteArray): Decoded {
        if (blob.size < 8) throw CPN1Exception.Truncated()
        for (i in 0 until 4) {
            if (blob[i] != magic[i]) throw CPN1Exception.BadMagic()
        }

        val manifestLen = readBEUInt32(blob, 4)
        var offset = 8L
        if (offset + manifestLen > blob.size) throw CPN1Exception.Truncated()
        val manifest = blob.copyOfRange(offset.toInt(), (offset + manifestLen).toInt())
        offset += manifestLen

        val parts = mutableListOf<ByteArray>()
        while (offset < blob.size) {
            if (offset + 8 > blob.size) throw CPN1Exception.Truncated()
            val partLen = readBEUInt64(blob, offset.toInt())
            offset += 8
            if (partLen < 0 || offset + partLen > blob.size) throw CPN1Exception.Truncated()
            parts.add(blob.copyOfRange(offset.toInt(), (offset + partLen).toInt()))
            offset += partLen
        }
        return Decoded(manifest, parts)
    }

    // ── Big-endian length fields ───────────────────────────────────────

    private fun writeBEUInt32(out: ByteArrayOutputStream, value: Long) {
        out.write(((value shr 24) and 0xFF).toInt())
        out.write(((value shr 16) and 0xFF).toInt())
        out.write(((value shr 8) and 0xFF).toInt())
        out.write((value and 0xFF).toInt())
    }

    private fun writeBEUInt64(out: ByteArrayOutputStream, value: Long) {
        for (i in 0 until 8) {
            out.write(((value shr (56 - 8 * i)) and 0xFF).toInt())
        }
    }

    private fun readBEUInt32(bytes: ByteArray, offset: Int): Long {
        var value = 0L
        for (i in 0 until 4) {
            value = (value shl 8) or (bytes[offset + i].toLong() and 0xFF)
        }
        return value
    }

    private fun readBEUInt64(bytes: ByteArray, offset: Int): Long {
        var value = 0L
        for (i in 0 until 8) {
            value = (value shl 8) or (bytes[offset + i].toLong() and 0xFF)
        }
        return value
    }
}
