// CPArmor.kt
// CarrierPonyCore
//
// ASCII armor helpers. Dearmoring is needed by the pairing path (armored
// public keys travel in PairingPayload / over the relay) and by key import.
// The Version-header strip mirrors PGPony Android's Phase A7 fix: bcpg emits
// a build-placeholder Version header in every armored block, and GnuPG warns
// about it, so every ArmoredOutputStream in this core removes it. CarrierPony
// output carries no Comment header either — messenger output stays free of
// provenance metadata.

package com.carrierpony.core

import org.bouncycastle.bcpg.ArmoredInputStream
import org.bouncycastle.bcpg.ArmoredOutputStream
import java.io.ByteArrayInputStream

object CPArmor {

    /** True if the bytes look like an ASCII-armored OpenPGP block. */
    fun isArmored(data: ByteArray): Boolean {
        val head = String(data, 0, minOf(data.size, 256), Charsets.ISO_8859_1)
        return head.contains("-----BEGIN PGP")
    }

    /** True if the text looks like an ASCII-armored OpenPGP block. */
    fun isArmored(text: String): Boolean = text.contains("-----BEGIN PGP")

    /**
     * Decode an armored OpenPGP block to its binary packet bytes.
     * Returns null for text that is not valid armor. Mirrors the Swift
     * PGPArmor.dearmor used by CarrierPony iOS's pairing verification.
     */
    fun dearmor(text: String): ByteArray? {
        return try {
            val input = ArmoredInputStream(ByteArrayInputStream(text.toByteArray(Charsets.UTF_8)))
            input.readBytes().also { input.close() }.takeIf { it.isNotEmpty() }
        } catch (e: Exception) {
            null
        }
    }
}

/**
 * Strip the bcpg Version header and ensure no Comment header, producing
 * clean armor. Applied at every ArmoredOutputStream construction site in
 * this core (detached signatures, exported public keys).
 */
internal fun ArmoredOutputStream.clean(): ArmoredOutputStream = apply {
    setHeader("Version", null)
    setHeader("Comment", null)
}
