// DesktopStorage.kt
// CarrierPony Desktop. One unlocked data directory: the vault, the sealed key-value files, and
// the at-rest codec for everything the vendored stores write (conversations, contacts,
// attachments).
//
// SecureKV caches its file in memory, so two instances over one file would overwrite each
// other's changes. Everything that needs a SecureKV gets it from here, one instance per file.

package com.carrierpony.desktop

import com.carrierpony.app.storage.AtRest
import com.carrierpony.app.storage.AtRestCodec
import java.nio.file.Files
import java.nio.file.Path

class DesktopStorage(dataDir: Path, val vault: Vault) {

    val dir: Path = Files.createDirectories(dataDir)

    private val kvs = HashMap<String, SecureKV>()

    fun kv(fileName: String): SecureKV = synchronized(kvs) {
        kvs.getOrPut(fileName) { SecureKV(dir.resolve(fileName), vault) }
    }

    /** Route the vendored stores' file I/O through the vault. Process-wide, like the AtRest
     *  object it configures; every account in this process shares the one vault anyway. */
    fun installAtRestCodec() {
        AtRest.codec = VaultAtRestCodec(vault)
    }

    companion object {
        const val IDENTITIES = "identities.json"
        const val PASSPHRASES = "passphrases.json"
        const val GROUP_KEYS = "groupkeys.json"
        const val SEALED = "sealed.json"

        fun vaultFile(dataDir: Path): Path = dataDir.resolve("vault.json")
    }
}

/** Seals each state file under the vault key, bound to the file's own name. A file that does
 *  not start with the magic is refused rather than read as plaintext: desktop never wrote
 *  plaintext state, so an unsealed file here means something else put it there. */
class VaultAtRestCodec(private val vault: Vault) : AtRestCodec {

    override fun seal(name: String, plaintext: ByteArray): ByteArray =
        MAGIC + vault.sealBytes("file:$name", plaintext)

    override fun open(name: String, stored: ByteArray): ByteArray {
        require(stored.size > MAGIC.size && stored.copyOfRange(0, MAGIC.size).contentEquals(MAGIC)) {
            "$name is not a sealed CarrierPony file"
        }
        return vault.openBytes("file:$name", stored, MAGIC.size)
    }

    private companion object {
        val MAGIC = "CPAR1\n".toByteArray(Charsets.US_ASCII)
    }
}
