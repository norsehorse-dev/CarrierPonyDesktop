// DesktopAccounts.kt
// CarrierPony Desktop. The identities on this computer: create one, bring one over from a phone
// backup, import an existing OpenPGP key, export a backup, list, remove. It is the identity half
// of Android's AppModel, without the UI state. Record and backup formats are the vendored ones,
// so a backup made here restores on a phone and the reverse.
//
// Needs an unlocked vault. The GUI's first-run and unlock screens (D4) and the CLI (D10) both
// go through this class.

package com.carrierpony.desktop

import com.carrierpony.app.crypto.Fingerprint
import com.carrierpony.app.identity.Identity
import com.carrierpony.app.identity.IdentityBackup
import com.carrierpony.app.identity.IdentityStore
import com.carrierpony.app.identity.KeyImport
import com.carrierpony.app.identity.PassphraseVault
import com.carrierpony.app.messaging.ContactStore
import com.carrierpony.app.messaging.GroupKeyStore
import com.carrierpony.app.messaging.SealedKeyStore
import com.carrierpony.app.net.LanDiscovery
import com.carrierpony.core.CPIdentityGenerator
import kotlinx.coroutines.CoroutineScope
import java.io.File

class DesktopAccounts(private val storage: DesktopStorage, private val prefs: DesktopPrefs) {

    data class Summary(val fingerprint: Fingerprint, val name: String?)

    private val identities = IdentityStore(storage.kv(DesktopStorage.IDENTITIES))
    private val passphrases = PassphraseVault(storage.kv(DesktopStorage.PASSPHRASES))

    fun list(): List<Summary> = identities.list().mapNotNull { hex ->
        Fingerprint.from(hex)?.let { Summary(it, profileName(hex)) }
    }

    fun selected(): Identity? = identities.load()

    fun load(fprHex: String): Identity? = identities.load(fprHex)

    fun select(fprHex: String) = identities.setSelected(fprHex)

    /** The display name this account sends to new contacts. Not secret: it lives in prefs, under
     *  the same key the phones use. */
    fun profileName(fprHex: String): String? = prefs.getString("cp.profile.$fprHex")

    fun setProfileName(fprHex: String, name: String?) =
        prefs.putString("cp.profile.$fprHex", name?.trim()?.takeIf { it.isNotEmpty() })

    /** Generate a fresh v4 Ed25519 + Cv25519 identity. The user id email is the same placeholder
     *  the phones use; CarrierPony has no accounts and never shows it. */
    fun create(name: String, email: String = "user@carrierpony.app"): Identity {
        val generated = CPIdentityGenerator.generateV4Identity(name, email)
        val fingerprint = Fingerprint.from(generated.fingerprint)
            ?: throw IllegalStateException("generated key has no usable fingerprint")
        val identity = Identity(fingerprint, generated.secretKey, generated.armoredPublicKey)
        identities.save(identity)
        setProfileName(fingerprint.hex, name)
        return identity
    }

    /** Restore a phone (or desktop) identity backup. Throws BackupException on a wrong
     *  passphrase or a malformed blob. */
    fun restoreBackup(blob: String, passphrase: String): Identity {
        val identity = IdentityBackup.restore(blob.trim(), passphrase)
        identities.save(identity)
        return identity
    }

    fun exportBackup(fprHex: String, passphrase: String): String {
        val identity = identities.load(fprHex) ?: throw IllegalArgumentException("no such account")
        return IdentityBackup.export(identity, passphrase)
    }

    /** Import an existing armored OpenPGP private key. Throws KeyImportException with a
     *  user-facing message when the key is unsupported or the passphrase is wrong. */
    fun importKey(armored: String, passphrase: String?): Identity {
        val inspection = KeyImport.inspect(armored)
        val identity = KeyImport.makeIdentity(inspection, passphrase?.takeIf { it.isNotEmpty() })
        if (identity.protected && !passphrases.store(passphrase!!, identity.fingerprint.hex)) {
            throw IllegalStateException("could not store the key's passphrase")
        }
        identities.save(identity)
        KeyImport.userIDName(inspection.secretKey)?.let { setProfileName(identity.fingerprint.hex, it) }
        return identity
    }

    /** Remove one account and everything stored for it on this computer. Attachments are shared
     *  between accounts upstream and are left alone. Returns the newly selected account. */
    fun remove(fprHex: String): String? {
        SealedKeyStore(storage.kv(DesktopStorage.SEALED), fprHex).purge()
        passphrases.delete(fprHex)
        ContactStore(storage.dir.toFile()).deleteFile(fprHex)
        File(storage.dir.toFile(), "carrierpony-conversations-$fprHex.json").delete()
        prefs.putString("cp.profile.$fprHex", null)
        return identities.delete(fprHex)
    }

    /** Build (not start) the messaging stack for one account. */
    fun session(
        identity: Identity,
        scope: CoroutineScope,
        relayBaseURL: String = com.carrierpony.app.AppConfig.relayBaseURL(),
        deviceID: String = com.carrierpony.app.AppConfig.deviceID(),
        lanDiscovery: LanDiscovery = LanDiscovery(),
    ): DesktopSession = DesktopSession(identity, storage, scope, prefs, { profileName(identity.fingerprint.hex) }, relayBaseURL, deviceID, lanDiscovery)
}
