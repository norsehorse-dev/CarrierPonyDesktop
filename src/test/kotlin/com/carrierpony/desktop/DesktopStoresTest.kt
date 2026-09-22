// DesktopStoresTest.kt
// The desktop twins of the four Android key stores, over a real SecureKV on a temp directory.
// Each test reopens the files with a fresh Vault and SecureKV where persistence is the point,
// because a store that only works within one process would pass everything else.

package com.carrierpony.desktop

import com.carrierpony.app.AppConfig
import com.carrierpony.app.DemoMode
import com.carrierpony.app.crypto.Fingerprint
import com.carrierpony.app.identity.Identity
import com.carrierpony.app.identity.IdentityStore
import com.carrierpony.app.identity.PassphraseVault
import com.carrierpony.app.messaging.GroupKeyStore
import com.carrierpony.app.messaging.SealedGroupState
import com.carrierpony.app.messaging.SealedKeyStore
import com.carrierpony.app.messaging.SealedPairState
import com.carrierpony.core.CPIdentityGenerator
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DesktopStoresTest {

    private val cheap = Vault.Cost(memoryKiB = 256, iterations = 1, parallelism = 1)

    private class Env(val dir: Path, val vault: Vault) {
        fun kv(name: String) = SecureKV(dir.resolve(name), vault)
    }

    private fun newEnv(): Env {
        val dir = Files.createTempDirectory("cp-stores")
        return Env(dir, Vault(DesktopStorage.vaultFile(dir)).apply { create("pw".toCharArray(), cheap) })
    }

    private fun reopen(env: Env): Env =
        Env(env.dir, Vault(DesktopStorage.vaultFile(env.dir)).apply { assertTrue(unlock("pw".toCharArray())) })

    private fun newIdentity(name: String): Identity {
        val g = CPIdentityGenerator.generateV4Identity(name, "$name@carrierpony.com")
        return Identity(Fingerprint.from(g.fingerprint)!!, g.secretKey, g.armoredPublicKey)
    }

    // ── SecureKV ───────────────────────────────────────────────────────

    @Test
    fun secureKvPersistsAndKeepsValuesOutOfTheFile() {
        val env = newEnv()
        env.kv("kv.json").putString("name", "a very secret value")
        val onDisk = String(Files.readAllBytes(env.dir.resolve("kv.json")))
        assertFalse(onDisk.contains("secret"))
        assertEquals("a very secret value", reopen(env).kv("kv.json").getString("name"))
    }

    @Test
    fun secureKvValueCannotBeMovedToAnotherName() {
        val env = newEnv()
        env.kv("kv.json").putString("a", "value")
        val file = env.dir.resolve("kv.json")
        val json = org.json.JSONObject(String(Files.readAllBytes(file)))
        json.put("b", json.getString("a"))
        Files.write(file, json.toString().toByteArray())
        val reopened = reopen(env).kv("kv.json")
        assertEquals("value", reopened.getString("a"))
        assertTrue(runCatching { reopened.getString("b") }.isFailure)
    }

    // ── IdentityStore ──────────────────────────────────────────────────

    @Test
    fun identityStoreSavesSelectsAndSurvivesRestart() {
        val env = newEnv()
        val store = IdentityStore(env.kv("identity.json"))
        assertNull(store.load())
        val alice = newIdentity("alice")
        val bob = newIdentity("bob")
        store.save(alice)
        store.save(bob)
        assertEquals(listOf(alice.fingerprint.hex, bob.fingerprint.hex), store.list())
        assertEquals(bob.fingerprint.hex, store.selected(), "saving an identity selects it")
        store.setSelected(alice.fingerprint.hex)

        val after = IdentityStore(reopen(env).kv("identity.json"))
        assertEquals(alice, after.load())
        assertEquals(bob, after.load(bob.fingerprint.hex))
    }

    @Test
    fun identityStoreDeleteMovesSelection() {
        val env = newEnv()
        val store = IdentityStore(env.kv("identity.json"))
        val alice = newIdentity("alice")
        val bob = newIdentity("bob")
        store.save(alice); store.save(bob)
        assertEquals(alice.fingerprint.hex, store.delete(bob.fingerprint.hex))
        assertNull(store.load(bob.fingerprint.hex))
        assertNull(store.delete(alice.fingerprint.hex))
        assertTrue(store.list().isEmpty())
        store.save(alice); store.deleteAll()
        assertNull(store.load())
    }

    @Test
    fun identitySecretKeyIsNotReadableOnDisk() {
        val env = newEnv()
        val alice = newIdentity("alice")
        IdentityStore(env.kv("identity.json")).save(alice)
        val onDisk = String(Files.readAllBytes(env.dir.resolve("identity.json")))
        assertFalse(onDisk.contains("secretKeyB64"))
        assertFalse(onDisk.contains("BEGIN PGP"))
    }

    // ── PassphraseVault ────────────────────────────────────────────────

    @Test
    fun passphraseVaultRoundTrip() {
        val env = newEnv()
        val vault = PassphraseVault(env.kv("passphrases.json"))
        assertNull(vault.read("ABCD"))
        assertTrue(vault.store("imported key passphrase", "ABCD"))
        assertEquals("imported key passphrase", PassphraseVault(reopen(env).kv("passphrases.json")).read("ABCD"))
        vault.delete("ABCD")
        assertNull(vault.read("ABCD"))
    }

    // ── GroupKeyStore ──────────────────────────────────────────────────

    @Test
    fun groupKeysByEpoch() {
        val env = newEnv()
        val store = GroupKeyStore(env.kv("groupkeys.json"))
        val k0 = store.newKey()
        val k1 = store.newKey()
        store.store(k0, "g1", 0); store.store(k1, "g1", 1)
        val after = GroupKeyStore(reopen(env).kv("groupkeys.json"))
        assertContentEquals(k0.encoded, after.key("g1", 0)!!.encoded)
        assertContentEquals(k1.encoded, after.key("g1", 1)!!.encoded)
        assertNull(after.key("g1", 2))
        after.deleteGroup("g1", 0)
        assertNull(after.key("g1", 0))
        assertNotNull(after.key("g1", 1))
        after.purge()
        assertNull(after.key("g1", 1))
    }

    // ── SealedKeyStore ─────────────────────────────────────────────────

    @Test
    fun sealedDeviceIdentityIsStablePerAccountAndDistinctAcrossAccounts() {
        val env = newEnv()
        val kv = env.kv("sealed.json")
        val a = SealedKeyStore(kv, "AAAA")
        val b = SealedKeyStore(kv, "BBBB")
        val idA = a.deviceId(); val keyA = a.deviceKey()
        assertTrue(Regex("^[0-9a-f]{32}$").matches(idA))
        assertTrue(Regex("^[0-9a-f]{64}$").matches(keyA))
        assertNotEquals(idA, b.deviceId(), "two accounts must not present one sealed device")
        assertNotEquals(keyA, b.deviceKey())

        val again = SealedKeyStore(reopen(env).kv("sealed.json"), "AAAA")
        assertEquals(idA, again.deviceId())
        assertEquals(keyA, again.deviceKey())
        again.rotateDeviceIdentity()
        assertNotEquals(idA, again.deviceId())
        assertEquals(keyA, again.deviceKey(), "rotation keeps the device key")
        assertNull(again.wakeToken())
    }

    @Test
    fun sealedPairAndGroupStateRoundTripAndPurgeIsPerAccount() {
        val env = newEnv()
        val kv = env.kv("sealed.json")
        val a = SealedKeyStore(kv, "AAAA")
        val b = SealedKeyStore(kv, "BBBB")
        val mine = a.newPairKey()
        a.setPair("PEER", SealedPairState(mine, null, 3, 7, true))
        a.setGroup("g1", SealedGroupState(2, mutableMapOf("x" to 5L), mutableMapOf("y" to 6L)))
        b.setPair("PEER", SealedPairState(b.newPairKey(), null, 0, 0, false))

        val pair = a.pair("peer")!!                     // peer hex is case-insensitive, as on Android
        assertContentEquals(mine, pair.myInboundKey)
        assertEquals(3, pair.sendCounter); assertEquals(7, pair.receiveHigh)
        assertFalse(pair.canSend)
        val group = a.group("g1")!!
        assertEquals(2, group.epoch); assertEquals(5L, group.sendCounters["x"]); assertEquals(6L, group.recvHighs["y"])

        a.deleteGroupState("g1")
        assertNull(a.group("g1"))
        a.purge()
        assertNull(a.pair("PEER"))
        assertNotNull(b.pair("PEER"), "purging one account leaves the other intact")
    }

    // ── AppConfig / DemoMode twins ─────────────────────────────────────

    @Test
    fun appConfigConstantsMatchAndroid() {
        // Copies of upstream AppSupport.kt constants. If upstream changes, change both.
        assertEquals("https://api.carrierpony.com", AppConfig.defaultRelayBaseURL)
        assertEquals(50 * 1024 * 1024, AppConfig.maxAttachmentBytes)
        assertEquals("https://push.carrierpony.com", AppConfig.gatewayBaseURL)
        assertEquals("DE300DE300DE300DE300DE300DE300DE300DE300", DemoMode.fingerprintHex)
        assertEquals("CarrierPony Demo", DemoMode.peerName)
        assertEquals(DemoMode.fingerprintHex, DemoMode.fingerprint.hex)
    }

    @Test
    fun relayUrlRulesAndDeviceId() {
        val dir = Files.createTempDirectory("cp-prefs")
        AppConfig.prefs = DesktopPrefs(dir.resolve("prefs.json"))
        try {
            assertEquals(AppConfig.defaultRelayBaseURL, AppConfig.relayBaseURL())
            assertFalse(AppConfig.usingCustomRelay())
            assertFalse(AppConfig.setRelayBaseURL("http://relay.example.com"), "http is refused")
            assertFalse(AppConfig.setRelayBaseURL("not a url"))
            assertTrue(AppConfig.setRelayBaseURL(" https://relay.example.com/ "))
            assertEquals("https://relay.example.com", AppConfig.relayBaseURL())
            assertTrue(AppConfig.usingCustomRelay())
            assertTrue(AppConfig.setRelayBaseURL(null))
            assertEquals(AppConfig.defaultRelayBaseURL, AppConfig.relayBaseURL())

            val id = AppConfig.deviceID()
            assertTrue(Regex("^[0-9a-f]{32}$").matches(id))
            AppConfig.prefs = DesktopPrefs(dir.resolve("prefs.json"))
            assertEquals(id, AppConfig.deviceID(), "the device id survives a restart")
        } finally {
            AppConfig.prefs = null
        }
    }
}
