// LanDirectTest.kt
// LAN-direct between two desktop stacks with mDNS replaced by an in-memory bus: real TCP on
// 127.0.0.1, the real CPL1 handshake over the real per-pair keys, real delivery into ChatStore.
// The JmDNS adapter is the only part not covered here.

package com.carrierpony.desktop

import com.carrierpony.app.AppConfig
import com.carrierpony.app.crypto.PublicKey
import com.carrierpony.app.messaging.Contact
import com.carrierpony.app.messaging.TrustLevel
import com.carrierpony.app.net.LanDiscovery
import com.carrierpony.app.net.LanMdns
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.net.InetAddress
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** One "network": every node advertised on it is found by every browser on it. */
class FakeMdnsBus {
    private val nodes = LinkedHashMap<String, Int>()
    private val listeners = mutableListOf<LanMdns.Listener>()

    fun endpoint(): LanMdns = object : LanMdns {
        private var mine: String? = null
        override fun advertise(name: String, port: Int) {
            val snapshot = synchronized(this@FakeMdnsBus) { mine = name; nodes[name] = port; listeners.toList() }
            for (l in snapshot) l.found(name, InetAddress.getLoopbackAddress(), port)
        }
        override fun browse(listener: LanMdns.Listener) {
            val existing = synchronized(this@FakeMdnsBus) { listeners.add(listener); nodes.toMap() }
            for ((name, port) in existing) listener.found(name, InetAddress.getLoopbackAddress(), port)
        }
        override fun close() {
            val (name, snapshot) = synchronized(this@FakeMdnsBus) { val n = mine; if (n != null) nodes.remove(n); n to listeners.toList() }
            if (name != null) for (l in snapshot) l.lost(name)
        }
    }
}

class LanDirectTest {

    private val cheap = Vault.Cost(memoryKiB = 256, iterations = 1, parallelism = 1)

    @AfterTest fun resetGlobals() { AppConfig.prefs = null }

    private fun open(name: String, relay: FakeRelay, scope: CoroutineScope, device: String, bus: FakeMdnsBus, enabled: () -> Boolean): DesktopSession {
        val dir = Files.createTempDirectory("cp-lan-$name")
        val vault = Vault(DesktopStorage.vaultFile(dir)).apply { create("pw".toCharArray(), cheap) }
        val accounts = DesktopAccounts(DesktopStorage(dir, vault), DesktopPrefs(dir.resolve("prefs.json")))
        val identity = accounts.create(name)
        return DesktopSession(identity, DesktopStorage(dir, vault), scope, DesktopPrefs(dir.resolve("prefs.json")),
            relayBaseURL = relay.baseURL, deviceID = device, lanDiscovery = LanDiscovery(bus.endpoint(), enabled))
    }

    private fun contact(s: DesktopSession, n: String) = Contact(s.identity.fingerprint, PublicKey(s.identity.fingerprint, s.identity.armoredPublicKey), n, TrustLevel.VERIFIED)
    private fun texts(s: DesktopSession) = s.store.conversations.value.values.flatMap { it.messages }.mapNotNull { it.text }

    @Test
    fun twoDesktopsOnOneLanIdentifyEachOtherAndDeliverWithoutTheRelay(): Unit = runBlocking {
        FakeRelay().use { relay ->
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val prefsDir = Files.createTempDirectory("cp-lan-prefs")
            AppConfig.prefs = DesktopPrefs(prefsDir.resolve("prefs.json"))
            try {
                val bus = FakeMdnsBus()
                var lanOn = false
                val alice = open("Alice", relay, scope, "a".repeat(32), bus) { lanOn }
                val bob = open("Bob", relay, scope, "b".repeat(32), bus) { lanOn }
                alice.contacts.add(contact(bob, "Bob")); bob.contacts.add(contact(alice, "Alice"))
                alice.start(3600); bob.start(3600)

                // Discovery runs regardless of the setting: both see one nearby node.
                withTimeout(5_000) { alice.lanDiscovery.nearby.first { it.size == 1 } }
                assertEquals(bob.lanDiscovery.nodeID, alice.lanDiscovery.nearby.value.single().nodeID)
                assertFalse(alice.lanDiscovery.canReachLan(bob.identity.fingerprint.hex), "identify is off until the setting is on")

                // The per-pair LAN key needs the sealed key exchange, which runs over the relay.
                repeat(3) { i ->
                    alice.store.send(text = "warm $i", to = alice.contacts.contact(bob.identity.fingerprint)!!); bob.store.refresh()
                    bob.store.send(text = "back $i", to = bob.contacts.contact(alice.identity.fingerprint)!!); alice.store.refresh()
                }
                // Alice announced her inbound key at start(), before Bob had registered with the
                // relay, so that announcement was lost; a phone heals this on its next 30-second
                // window refresh. resync re-announces now.
                alice.store.resyncSealedKeys(); bob.store.refresh(); alice.store.refresh()
                assertTrue(alice.store.lanContactKeys().isNotEmpty() && bob.store.lanContactKeys().isNotEmpty(), "both sides need the pair key")

                lanOn = true
                alice.lanDidToggle(); bob.lanDidToggle()
                withTimeout(10_000) { alice.lanDiscovery.reachable.first { bob.identity.fingerprint.hex.lowercase() in it } }
                assertTrue(alice.lanDiscovery.canReachLan(bob.identity.fingerprint.hex))

                // Direct only: the relay must see nothing new for this message.
                AppConfig.setLanDirectSkipRelay(true)
                val sealedBefore = relay.sealedSendCount(); val legacyBefore = relay.legacySendCount()
                alice.store.send(text = "over the wire", to = alice.contacts.contact(bob.identity.fingerprint)!!)
                withTimeout(10_000) { bob.store.conversations.first { c -> c.values.any { conv -> conv.messages.any { it.text == "over the wire" } } } }
                assertEquals(sealedBefore, relay.sealedSendCount(), "no sealed relay send")
                assertEquals(legacyBefore + 1, relay.legacySendCount(), "only the silent self-copy uses the relay")

                // Relay-authoritative mode: LAN accelerates, the relay still carries the message.
                AppConfig.setLanDirectSkipRelay(false)
                alice.store.send(text = "both paths", to = alice.contacts.contact(bob.identity.fingerprint)!!)
                withTimeout(10_000) { bob.store.conversations.first { c -> c.values.any { conv -> conv.messages.any { it.text == "both paths" } } } }
                assertEquals(sealedBefore + 1, relay.sealedSendCount())
                bob.store.refresh()
                assertEquals(1, texts(bob).count { it == "both paths" }, "the relay copy is deduplicated by message id")

                // Switching LAN off drops reachability at once; a node leaving is noticed.
                lanOn = false
                alice.lanDidToggle()
                assertFalse(alice.lanDiscovery.canReachLan(bob.identity.fingerprint.hex))
                bob.stop()
                withTimeout(5_000) { alice.lanDiscovery.nearby.first { it.isEmpty() } }
                alice.stop()
            } finally {
                scope.cancel()
            }
        }
    }

    @Test
    fun aStrangerOnTheLanIsNotIdentified(): Unit = runBlocking {
        FakeRelay().use { relay ->
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            AppConfig.prefs = DesktopPrefs(Files.createTempDirectory("cp-lan-prefs").resolve("prefs.json"))
            try {
                val bus = FakeMdnsBus()
                val alice = open("Alice", relay, scope, "a".repeat(32), bus) { true }
                val mallory = open("Mallory", relay, scope, "c".repeat(32), bus) { true }
                // Alice knows Bob (a fake key), not Mallory. Mallory knows nobody.
                alice.lanDiscovery.keyProvider = { mapOf("b".repeat(40) to ByteArray(32) { 7 }) }
                alice.start(3600); mallory.start(3600)
                withTimeout(5_000) { alice.lanDiscovery.nearby.first { it.size == 1 } }
                alice.lanDidToggle()
                kotlinx.coroutines.delay(1_500)
                assertTrue(alice.lanDiscovery.reachable.value.isEmpty(), "a node without a matching pair key never becomes reachable")
                assertTrue(mallory.lanDiscovery.reachable.value.isEmpty())
                alice.stop(); mallory.stop()
            } finally {
                scope.cancel()
            }
        }
    }

    @Test
    fun ourOwnAnnouncementIsNeverTreatedAsAPeer(): Unit = runBlocking {
        FakeRelay().use { relay ->
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            AppConfig.prefs = DesktopPrefs(Files.createTempDirectory("cp-lan-prefs").resolve("prefs.json"))
            try {
                val bus = FakeMdnsBus()
                val alice = open("Alice", relay, scope, "a".repeat(32), bus) { true }
                alice.lanDiscovery.keyProvider = { mapOf("b".repeat(40) to ByteArray(32) { 7 }) }
                alice.start(3600)
                val me = alice.lanDiscovery.nodeID
                // What JmDNS reports back on a Mac with several interfaces: our id, renamed per
                // interface, at our own addresses and port.
                // Feed the renamed announcements through the real bus by advertising them there.
                val e3 = bus.endpoint(); e3.advertise("$me (2)", alice.lanDiscovery.port)
                val e4 = bus.endpoint(); e4.advertise("$me (3)", alice.lanDiscovery.port)
                kotlinx.coroutines.delay(1_500)
                assertTrue(alice.lanDiscovery.nearby.value.isEmpty(), "own renamed announcements must not count as nearby: ${alice.lanDiscovery.nearby.value}")
                assertTrue(alice.lanDiscovery.reachable.value.isEmpty(), "and must never identify as a contact")
                alice.stop()
            } finally {
                scope.cancel()
            }
        }
    }
}
