// DesktopAccountsTest.kt
// Identity lifecycle on a desktop: create, back up, restore into a second data directory (the
// "bring my phone identity over" path uses exactly this blob format), import a protected
// OpenPGP key, remove an account.

package com.carrierpony.desktop

import com.carrierpony.app.identity.BackupException
import com.carrierpony.app.identity.KeyImportException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DesktopAccountsTest {

    private val cheap = Vault.Cost(memoryKiB = 256, iterations = 1, parallelism = 1)

    private fun open(dir: Path = Files.createTempDirectory("cp-accounts")): DesktopAccounts {
        val vault = Vault(DesktopStorage.vaultFile(dir))
        if (vault.exists) assertTrue(vault.unlock("pw".toCharArray())) else vault.create("pw".toCharArray(), cheap)
        return DesktopAccounts(DesktopStorage(dir, vault), DesktopPrefs(dir.resolve("prefs.json")))
    }

    @Test
    fun createListsSelectsAndSurvivesRestart() {
        val dir = Files.createTempDirectory("cp-accounts")
        val accounts = open(dir)
        assertTrue(accounts.list().isEmpty())
        val first = accounts.create("First")
        val second = accounts.create("Second")
        assertEquals(listOf("First", "Second"), accounts.list().map { it.name })
        assertEquals(second, accounts.selected())
        accounts.select(first.fingerprint.hex)

        val reopened = open(dir)
        assertEquals(first, reopened.selected())
        assertEquals("Second", reopened.profileName(second.fingerprint.hex))
    }

    @Test
    fun backupMadeHereRestoresElsewhereWithTheSameFingerprint() {
        val source = open()
        val identity = source.create("Traveller")
        val blob = source.exportBackup(identity.fingerprint.hex, "backup passphrase")
        assertTrue(blob.contains("BEGIN PGP MESSAGE"))

        val target = open()
        assertFailsWith<BackupException> { target.restoreBackup(blob, "wrong") }
        assertTrue(target.list().isEmpty(), "a failed restore must not leave an account behind")
        val restored = target.restoreBackup("  $blob\n", "backup passphrase")
        assertEquals(identity, restored)
        assertEquals(identity.fingerprint, target.selected()!!.fingerprint)
    }

    @Test
    fun removeDeletesOnlyThatAccount() {
        val dir = Files.createTempDirectory("cp-accounts")
        val accounts = open(dir)
        val keep = accounts.create("Keep")
        val drop = accounts.create("Drop")
        Files.write(dir.resolve("carrierpony-conversations-${drop.fingerprint.hex}.json"), byteArrayOf(1))
        Files.write(dir.resolve("carrierpony-conversations-${keep.fingerprint.hex}.json"), byteArrayOf(1))

        assertEquals(keep.fingerprint.hex, accounts.remove(drop.fingerprint.hex))
        assertNull(accounts.load(drop.fingerprint.hex))
        assertNotNull(accounts.load(keep.fingerprint.hex))
        assertFalse(Files.exists(dir.resolve("carrierpony-conversations-${drop.fingerprint.hex}.json")))
        assertTrue(Files.exists(dir.resolve("carrierpony-conversations-${keep.fingerprint.hex}.json")))
        assertNull(accounts.profileName(drop.fingerprint.hex))
    }

    @Test
    fun importRefusesGarbage() {
        val accounts = open()
        assertFailsWith<KeyImportException> { accounts.importKey("not a key", null) }
        assertTrue(accounts.list().isEmpty())
    }

    @Test
    fun atRestCodecRoundTripsAndRefusesPlaintextAndWrongNames() {
        val dir = Files.createTempDirectory("cp-atrest")
        val vault = Vault(DesktopStorage.vaultFile(dir)).apply { create("pw".toCharArray(), cheap) }
        val codec = VaultAtRestCodec(vault)
        val sealed = codec.seal("a.json", "hello".toByteArray())
        assertEquals("hello", String(codec.open("a.json", sealed)))
        assertFailsWith<Exception> { codec.open("b.json", sealed) }
        assertFailsWith<Exception> { codec.open("a.json", "{\"plain\":true}".toByteArray()) }
        // Binary sealing adds a fixed 6 + 12 + 16 bytes, not a third.
        assertEquals(1000 + 34, codec.seal("big.bin", ByteArray(1000)).size)
    }
}
