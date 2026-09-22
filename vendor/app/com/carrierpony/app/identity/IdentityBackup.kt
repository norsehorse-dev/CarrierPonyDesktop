// IdentityBackup.kt
// CarrierPony Android
//
// Backup packaging, ported from iOS Core/App/IdentityBackup.swift. A
// CarrierPony identity backup is a passphrase-sealed blob. Inside is a small
// JSON payload carrying everything needed to reconstruct the Identity: the
// fingerprint, the armored public key, and the raw secret-key bytes (base64).
// The whole payload is encrypted under the user's passphrase via
// CPPassphraseBox (OpenPGP symmetric, S2K-derived key), so the secret is
// never written in the clear. Restore is the exact inverse.
//
// The wire format is identical on both platforms: an iOS backup restores
// here, and one made here restores on an iPhone. There is no passphrase
// recovery. A lost passphrase means the backup — and the identity it holds —
// cannot be recovered.

package com.carrierpony.app.identity

import com.carrierpony.app.crypto.Fingerprint
import com.carrierpony.core.CPPassphraseBox
import org.json.JSONObject
import kotlin.io.encoding.Base64

class BackupException(message: String) : Exception(message) {
    companion object {
        fun malformed() = BackupException("This backup is not valid, or the passphrase is wrong.")
    }
}

object IdentityBackup {

    /** Seal an identity into an armored, passphrase-protected backup blob. */
    fun export(identity: Identity, passphrase: String): String {
        val payload = JSONObject()
            .put("v", 1)
            .put("fpr", identity.fingerprint.hex)
            .put("pub", identity.armoredPublicKey)
            .put("sec", Base64.Default.encode(identity.secretKey))
        return CPPassphraseBox.seal(payload.toString().toByteArray(Charsets.UTF_8), passphrase)
    }

    /** Open a backup blob with its passphrase and reconstruct the Identity. */
    fun restore(blob: String, passphrase: String): Identity {
        val json = try {
            CPPassphraseBox.open(blob, passphrase)
        } catch (e: Exception) {
            throw BackupException.malformed()
        }
        return try {
            val payload = JSONObject(String(json, Charsets.UTF_8))
            val fingerprint = Fingerprint.from(payload.getString("fpr"))
                ?: throw BackupException.malformed()
            Identity(
                fingerprint = fingerprint,
                secretKey = Base64.Default.decode(payload.getString("sec")),
                armoredPublicKey = payload.getString("pub")
            )
        } catch (e: BackupException) {
            throw e
        } catch (e: Exception) {
            throw BackupException.malformed()
        }
    }
}
