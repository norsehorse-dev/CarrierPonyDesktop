// VaultTest.kt
// The launch-passphrase vault: right and wrong passphrases, locking, slot binding, tamper
// detection, and refusal to overwrite. Argon2id runs at a toy cost here; the production cost is
// the Vault.Cost default.

package com.carrierpony.desktop

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class VaultTest {

    private val cheap = Vault.Cost(memoryKiB = 256, iterations = 1, parallelism = 1)
    private fun tempVaultFile() = Files.createTempDirectory("cp-vault").resolve("vault.json")

    @Test
    fun createThenUnlockWithTheRightPassphrase() {
        val file = tempVaultFile()
        Vault(file).create("correct horse".toCharArray(), cheap)
        val reopened = Vault(file)
        assertFalse(reopened.isUnlocked)
        assertTrue(reopened.unlock("correct horse".toCharArray()))
        assertTrue(reopened.isUnlocked)
    }

    @Test
    fun wrongPassphraseIsRefusedAndStaysLocked() {
        val file = tempVaultFile()
        Vault(file).create("correct horse".toCharArray(), cheap)
        val reopened = Vault(file)
        assertFalse(reopened.unlock("wrong horse".toCharArray()))
        assertFalse(reopened.isUnlocked)
    }

    @Test
    fun sealedBytesOpenAcrossSessionsButNotWhenLocked() {
        val file = tempVaultFile()
        val first = Vault(file).apply { create("pw".toCharArray(), cheap) }
        val sealed = first.seal("slot", byteArrayOf(1, 2, 3))

        val second = Vault(file)
        assertFailsWith<VaultLockedException> { second.open("slot", sealed) }
        assertTrue(second.unlock("pw".toCharArray()))
        assertContentEquals(byteArrayOf(1, 2, 3), second.open("slot", sealed))

        second.lock()
        assertFailsWith<VaultLockedException> { second.seal("slot", byteArrayOf(9)) }
    }

    @Test
    fun aBlobDoesNotOpenInAnotherSlotOrAfterTampering() {
        val vault = Vault(tempVaultFile()).apply { create("pw".toCharArray(), cheap) }
        val sealed = vault.seal("slot-a", "secret".toByteArray())
        assertFailsWith<Exception> { vault.open("slot-b", sealed) }

        val parts = sealed.split(":")
        val flipped = parts[0] + ":" + (if (parts[1][0] == 'A') "B" else "A") + parts[1].substring(1)
        assertFailsWith<Exception> { vault.open("slot-a", flipped) }
    }

    @Test
    fun twoSealsOfTheSameBytesDiffer() {
        val vault = Vault(tempVaultFile()).apply { create("pw".toCharArray(), cheap) }
        val a = vault.seal("slot", "same".toByteArray())
        val b = vault.seal("slot", "same".toByteArray())
        assertTrue(a != b, "a repeated IV under one GCM key would be fatal")
    }

    @Test
    fun createRefusesToOverwriteAnExistingVault() {
        val file = tempVaultFile()
        Vault(file).create("pw".toCharArray(), cheap)
        assertFailsWith<IllegalStateException> { Vault(file).create("other".toCharArray(), cheap) }
    }

    @Test
    fun theVaultFileNeverContainsThePassphrase() {
        val file = tempVaultFile()
        Vault(file).create("hunter2-hunter2".toCharArray(), cheap)
        assertFalse(String(Files.readAllBytes(file)).contains("hunter2"))
    }
}
