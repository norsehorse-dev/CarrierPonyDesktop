// CPPassphraseBox.kt
// CarrierPonyCore
//
// Passphrase-sealed blobs: OpenPGP symmetric encryption (SKESK with an
// S2K-derived key, AES-256, integrity protected) around arbitrary bytes,
// armored as a PGP MESSAGE. The Android twin of the Swift core's
// PassphraseBox, and byte-level standard OpenPGP — a blob sealed on either
// platform (or by gpg -c) opens on the other. CarrierPony uses it for
// identity backups; there is no passphrase recovery by design.

package com.carrierpony.core

import org.bouncycastle.bcpg.ArmoredOutputStream
import org.bouncycastle.bcpg.HashAlgorithmTags
import org.bouncycastle.bcpg.SymmetricKeyAlgorithmTags
import org.bouncycastle.openpgp.PGPCompressedData
import org.bouncycastle.openpgp.PGPEncryptedDataGenerator
import org.bouncycastle.openpgp.PGPEncryptedDataList
import org.bouncycastle.openpgp.PGPLiteralData
import org.bouncycastle.openpgp.PGPLiteralDataGenerator
import org.bouncycastle.openpgp.PGPPBEEncryptedData
import org.bouncycastle.openpgp.PGPUtil
import org.bouncycastle.openpgp.bc.BcPGPObjectFactory
import org.bouncycastle.openpgp.operator.bc.BcPBEDataDecryptorFactory
import org.bouncycastle.openpgp.operator.bc.BcPBEKeyEncryptionMethodGenerator
import org.bouncycastle.openpgp.operator.bc.BcPGPDataEncryptorBuilder
import org.bouncycastle.openpgp.operator.bc.BcPGPDigestCalculatorProvider
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.security.SecureRandom
import java.util.Date

object CPPassphraseBox {

    /** Seal bytes under a passphrase. Returns an armored PGP MESSAGE. */
    fun seal(data: ByteArray, passphrase: String): String {
        try {
            val encGen = PGPEncryptedDataGenerator(
                BcPGPDataEncryptorBuilder(SymmetricKeyAlgorithmTags.AES_256)
                    .setWithIntegrityPacket(true)
                    .setSecureRandom(SecureRandom())
            )
            encGen.addMethod(
                BcPBEKeyEncryptionMethodGenerator(
                    passphrase.toCharArray(),
                    BcPGPDigestCalculatorProvider().get(HashAlgorithmTags.SHA256)
                )
            )

            val out = ByteArrayOutputStream()
            val armored = ArmoredOutputStream(out).clean()
            val encryptedOut = encGen.open(armored, ByteArray(1 shl 16))
            val literalGen = PGPLiteralDataGenerator()
            val literalOut = literalGen.open(encryptedOut, PGPLiteralData.BINARY, "", data.size.toLong(), Date())
            literalOut.write(data)
            literalGen.close()
            encryptedOut.close()
            encGen.close()
            armored.close()
            return out.toString(Charsets.UTF_8)
        } catch (e: Exception) {
            throw CPCryptoError.EncryptionFailed(e.message ?: "Unknown error")
        }
    }

    /** Open a sealed blob. Throws DecryptionFailed on a wrong passphrase or
     *  malformed input, IntegrityCheckFailed on tampering. */
    fun open(armored: String, passphrase: String): ByteArray {
        try {
            val bytes = armored.toByteArray(Charsets.UTF_8)
            val factory = BcPGPObjectFactory(PGPUtil.getDecoderStream(ByteArrayInputStream(bytes)))
            var obj = factory.nextObject()
            var encList: PGPEncryptedDataList? = null
            while (obj != null) {
                if (obj is PGPEncryptedDataList) { encList = obj; break }
                obj = factory.nextObject()
            }
            val pbe = encList?.encryptedDataObjects?.asSequence()
                ?.filterIsInstance<PGPPBEEncryptedData>()?.firstOrNull()
                ?: throw CPCryptoError.DecryptionFailed("No passphrase-encrypted data found")

            val clear = pbe.getDataStream(
                BcPBEDataDecryptorFactory(passphrase.toCharArray(), BcPGPDigestCalculatorProvider())
            )

            var inner = BcPGPObjectFactory(clear)
            var literal: ByteArray? = null
            var next = inner.nextObject()
            while (next != null) {
                when (next) {
                    is PGPCompressedData -> inner = BcPGPObjectFactory(next.dataStream)
                    is PGPLiteralData -> literal = next.inputStream.readBytes()
                }
                next = inner.nextObject()
            }
            val payload = literal ?: throw CPCryptoError.DecryptionFailed("No literal data found")

            val intact = pbe.isIntegrityProtected && try { pbe.verify() } catch (e: Exception) { false }
            if (!intact) {
                throw CPCryptoError.IntegrityCheckFailed("Integrity check failed")
            }
            return payload
        } catch (e: CPCryptoError) {
            throw e
        } catch (e: Exception) {
            throw CPCryptoError.DecryptionFailed(e.message ?: "Wrong passphrase or malformed blob")
        }
    }
}
