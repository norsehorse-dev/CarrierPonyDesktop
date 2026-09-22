// Vault.kt
// CarrierPony Desktop. The launch passphrase and the key it unlocks.
//
// Android wraps every secret under a non-exportable AndroidKeyStore key. A desktop has no single
// equivalent, so the 1.0 decision is a passphrase typed at launch: Argon2id turns it into a
// 256-bit key that lives in memory only while the app is unlocked. Everything secret on disk
// (SecureKV, and from D2b the conversation file) is AES-256-GCM under that key. lock() drops it.
//
// vault.json holds only what is needed to re-derive and check the key: the Argon2id parameters,
// the salt, and a sealed constant. A wrong passphrase fails the GCM tag on that constant, so
// unlock() can say "wrong passphrase" without any secret having been touched.

package com.carrierpony.desktop

import org.bouncycastle.crypto.generators.Argon2BytesGenerator
import org.bouncycastle.crypto.params.Argon2Parameters
import org.json.JSONObject
import java.nio.file.Files
import java.nio.file.Path
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

class VaultLockedException : IllegalStateException("the vault is locked")

class Vault(private val file: Path) {

    /** Argon2id cost. Defaults follow the RFC 9106 second recommendation (64 MiB, 3 passes),
     *  sized for a one-time unlock at launch. Tests pass something cheap. */
    data class Cost(val memoryKiB: Int = 64 * 1024, val iterations: Int = 3, val parallelism: Int = 1)

    @Volatile private var key: SecretKey? = null

    val exists: Boolean get() = Files.isRegularFile(file)
    val isUnlocked: Boolean get() = key != null

    /** First run: choose the passphrase. Refuses to overwrite an existing vault, because doing
     *  so would orphan every secret sealed under the old key. */
    @Synchronized
    fun create(passphrase: CharArray, cost: Cost = Cost()) {
        check(!exists) { "a vault already exists at $file" }
        require(passphrase.isNotEmpty()) { "the passphrase must not be empty" }
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val derived = derive(passphrase, salt, cost)
        val json = JSONObject()
            .put("v", 1)
            .put("kdf", "argon2id")
            .put("salt", b64(salt))
            .put("m", cost.memoryKiB)
            .put("t", cost.iterations)
            .put("p", cost.parallelism)
            .put("check", seal(derived, CHECK_AAD, CHECK_PLAINTEXT))
        AtomicFiles.write(file, json.toString(2).toByteArray(Charsets.UTF_8))
        key = derived
    }

    /** True and unlocked on the right passphrase, false on a wrong one. Throws only when the
     *  vault file is missing or malformed. */
    @Synchronized
    fun unlock(passphrase: CharArray): Boolean {
        val json = JSONObject(String(Files.readAllBytes(file), Charsets.UTF_8))
        require(json.optInt("v") == 1 && json.optString("kdf") == "argon2id") { "unsupported vault format" }
        val cost = Cost(json.getInt("m"), json.getInt("t"), json.getInt("p"))
        val derived = derive(passphrase, unb64(json.getString("salt")), cost)
        val opened = try {
            open(derived, CHECK_AAD, json.getString("check"))
        } catch (e: Exception) {
            return false
        }
        if (!opened.contentEquals(CHECK_PLAINTEXT)) return false
        key = derived
        return true
    }

    fun lock() { key = null }

    /** Seal bytes under the vault key. [aad] binds the ciphertext to its slot (a key name, a file
     *  name), so a blob copied into a different slot fails to open. Returns "iv:ciphertext". */
    fun seal(aad: String, plaintext: ByteArray): String = seal(requireKey(), aad, plaintext)

    /** Open a blob made by [seal]. Throws on a wrong slot, tampering, or a malformed blob. */
    fun open(aad: String, sealed: String): ByteArray = open(requireKey(), aad, sealed)

    /** Binary form of [seal] for whole files: a 12-byte IV followed by the ciphertext and tag.
     *  No base64, so a 50 MB attachment costs 50 MB on disk, not 67. */
    fun sealBytes(aad: String, plaintext: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, requireKey())
        cipher.updateAAD(aad.toByteArray(Charsets.UTF_8))
        val blob = cipher.doFinal(plaintext)
        return cipher.iv + blob
    }

    fun openBytes(aad: String, stored: ByteArray, offset: Int = 0): ByteArray {
        require(stored.size - offset > IV_BYTES) { "sealed data is too short" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, requireKey(), GCMParameterSpec(TAG_BITS, stored, offset, IV_BYTES))
        cipher.updateAAD(aad.toByteArray(Charsets.UTF_8))
        return cipher.doFinal(stored, offset + IV_BYTES, stored.size - offset - IV_BYTES)
    }

    private fun requireKey(): SecretKey = key ?: throw VaultLockedException()

    private fun derive(passphrase: CharArray, salt: ByteArray, cost: Cost): SecretKey {
        val params = Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
            .withVersion(Argon2Parameters.ARGON2_VERSION_13)
            .withSalt(salt)
            .withMemoryAsKB(cost.memoryKiB)
            .withIterations(cost.iterations)
            .withParallelism(cost.parallelism)
            .build()
        val out = ByteArray(32)
        Argon2BytesGenerator().apply { init(params) }.generateBytes(passphrase, out)
        return SecretKeySpec(out, "AES")
    }

    private companion object {
        const val TAG_BITS = 128
        const val IV_BYTES = 12
        const val CHECK_AAD = "carrierpony.vault.check"
        val CHECK_PLAINTEXT = "carrierpony-vault-v1".toByteArray(Charsets.UTF_8)

        fun b64(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)
        fun unb64(text: String): ByteArray = Base64.getDecoder().decode(text)

        fun seal(key: SecretKey, aad: String, plaintext: ByteArray): String {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key)               // the provider picks a fresh 96-bit IV
            cipher.updateAAD(aad.toByteArray(Charsets.UTF_8))
            val blob = cipher.doFinal(plaintext)
            return b64(cipher.iv) + ":" + b64(blob)
        }

        fun open(key: SecretKey, aad: String, sealed: String): ByteArray {
            val parts = sealed.split(":")
            require(parts.size == 2) { "malformed sealed blob" }
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, unb64(parts[0])))
            cipher.updateAAD(aad.toByteArray(Charsets.UTF_8))
            return cipher.doFinal(unb64(parts[1]))
        }
    }
}
