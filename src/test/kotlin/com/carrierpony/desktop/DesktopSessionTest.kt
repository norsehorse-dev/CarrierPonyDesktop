// DesktopSessionTest.kt
// The D3 claim, tested without a network: two complete desktop stacks (vault, key stores,
// vendored ChatStore and RelayClient) hold a conversation through an in-process relay. This is
// the vendored Android messaging code running end to end on a plain JVM. What it cannot show is
// interop with a real phone or the real relay; that is the manual half of D3.

package com.carrierpony.desktop

import com.carrierpony.app.crypto.Fingerprint
import com.carrierpony.app.crypto.PublicKey
import com.carrierpony.app.identity.Identity
import com.carrierpony.app.messaging.ChatMessage
import com.carrierpony.app.messaging.Contact
import com.carrierpony.app.messaging.TrustLevel
import com.carrierpony.core.CPIdentityGenerator
import com.carrierpony.app.net.LanDiscovery
import com.carrierpony.app.net.LanMdns
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DesktopSessionTest {

    private val cheap = Vault.Cost(memoryKiB = 256, iterations = 1, parallelism = 1)

    private fun newIdentity(name: String): Identity {
        val g = CPIdentityGenerator.generateV4Identity(name, "$name@carrierpony.com")
        return Identity(Fingerprint.from(g.fingerprint)!!, g.secretKey, g.armoredPublicKey)
    }

    private fun contactFor(identity: Identity, name: String) = Contact(
        fingerprint = identity.fingerprint,
        publicKey = PublicKey(identity.fingerprint, identity.armoredPublicKey),
        name = name,
        trust = TrustLevel.VERIFIED
    )

    private fun session(identity: Identity, relay: FakeRelay, scope: CoroutineScope, deviceID: String, dir: Path = Files.createTempDirectory("cp-session")): DesktopSession {
        val vault = Vault(DesktopStorage.vaultFile(dir))
        if (vault.exists) assertTrue(vault.unlock("pw".toCharArray())) else vault.create("pw".toCharArray(), cheap)
        return DesktopSession(identity, DesktopStorage(dir, vault), scope, DesktopPrefs(dir.resolve("prefs.json")), relayBaseURL = relay.baseURL, deviceID = deviceID, lanDiscovery = LanDiscovery(LanMdns.None) { false })
    }

    private fun texts(session: DesktopSession): List<String> =
        session.store.conversations.value.values.flatMap { c -> c.messages }.mapNotNull { m: ChatMessage -> m.text }

    @Test
    fun twoDesktopsHoldAConversationThroughTheRelay(): Unit = runBlocking {
        FakeRelay().use { relay ->
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            try {
                val alice = newIdentity("alice")
                val bob = newIdentity("bob")
                val a = session(alice, relay, scope, "a".repeat(32))
                val b = session(bob, relay, scope, "b".repeat(32))
                a.contacts.add(contactFor(bob, "Bob"))
                b.contacts.add(contactFor(alice, "Alice"))
                a.start(pollIntervalSeconds = 3600); b.start(pollIntervalSeconds = 3600)
                assertEquals("desktop", relay.labelOf("a".repeat(32)))

                a.store.send(text = "hello from the desktop", to = a.contacts.contact(bob.fingerprint)!!)
                b.store.refresh()
                assertTrue("hello from the desktop" in texts(b), "Bob should have received: ${texts(b)}")

                b.store.send(text = "and back again", to = b.contacts.contact(alice.fingerprint)!!)
                a.store.refresh()
                assertTrue("and back again" in texts(a), "Alice should have received: ${texts(a)}")

                // A few more rounds let the sealed-sender key exchange settle. Once both sides
                // hold the other's inbound key, traffic should move to sealed mailboxes.
                repeat(3) { i ->
                    a.store.refresh(); b.store.refresh()
                    a.store.send(text = "round $i a", to = a.contacts.contact(bob.fingerprint)!!)
                    b.store.refresh()
                    b.store.send(text = "round $i b", to = b.contacts.contact(alice.fingerprint)!!)
                    a.store.refresh()
                }
                assertTrue("round 2 a" in texts(b)); assertTrue("round 2 b" in texts(a))
                assertTrue(a.store.isSealed(bob.fingerprint), "Alice should be able to address Bob by sealed mailbox")
                assertTrue(relay.sealedSendCount() > 0, "later messages should travel as sealed sends")

                a.stop(); b.stop()
            } finally {
                scope.cancel()
            }
        }
    }

    @Test
    fun conversationSurvivesRestart(): Unit = runBlocking {
        FakeRelay().use { relay ->
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            try {
                val alice = newIdentity("alice")
                val bob = newIdentity("bob")
                val bobDir = Files.createTempDirectory("cp-session-bob")
                val a = session(alice, relay, scope, "a".repeat(32))
                val b = session(bob, relay, scope, "b".repeat(32), bobDir)
                a.contacts.add(contactFor(bob, "Bob")); b.contacts.add(contactFor(alice, "Alice"))
                a.start(3600); b.start(3600)
                a.store.send(text = "remember me", to = a.contacts.contact(bob.fingerprint)!!)
                b.store.refresh()
                b.stop()

                // Nothing readable on disk: conversations and contacts are sealed files.
                val stateFiles = Files.list(bobDir).use { it.toList() }.filter { it.fileName.toString().startsWith("carrierpony-") && Files.isRegularFile(it) }
                assertTrue(stateFiles.size >= 2, "expected conversation and contact files, found $stateFiles")
                for (file in stateFiles) {
                    val raw = String(Files.readAllBytes(file), Charsets.ISO_8859_1)
                    assertTrue(raw.startsWith("CPAR1\n"), "$file should be sealed")
                    assertFalse(raw.contains("remember me")); assertFalse(raw.contains("Alice"))
                }

                val again = session(bob, relay, scope, "b".repeat(32), bobDir)
                assertTrue("remember me" in texts(again), "history should load from disk: ${texts(again)}")
                assertEquals(1, again.contacts.contacts.size)
                a.stop()
            } finally {
                scope.cancel()
            }
        }
    }

    @Test
    fun phoneAndDesktopOnOneIdentityBothReceive(): Unit = runBlocking {
        // The multi-device decision in the plan: the relay queues per device, so a desktop
        // restored from a phone backup does not take messages away from the phone.
        FakeRelay().use { relay ->
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            try {
                val alice = newIdentity("alice")
                val bob = newIdentity("bob")
                val sender = session(alice, relay, scope, "a".repeat(32))
                val bobPhone = session(bob, relay, scope, "1".repeat(32))
                val bobDesktop = session(bob, relay, scope, "2".repeat(32))
                sender.contacts.add(contactFor(bob, "Bob"))
                bobPhone.contacts.add(contactFor(alice, "Alice")); bobDesktop.contacts.add(contactFor(alice, "Alice"))
                sender.start(3600); bobPhone.start(3600); bobDesktop.start(3600)

                sender.store.send(text = "to both of you", to = sender.contacts.contact(bob.fingerprint)!!)
                bobPhone.store.refresh()
                bobDesktop.store.refresh()
                assertTrue("to both of you" in texts(bobPhone))
                assertTrue("to both of you" in texts(bobDesktop), "the phone's ack must not consume the desktop's copy")

                // And what one of Bob's devices sends shows up on the other as a self-copy.
                bobDesktop.store.send(text = "sent from my desktop", to = bobDesktop.contacts.contact(alice.fingerprint)!!)
                bobPhone.store.refresh()
                assertTrue("sent from my desktop" in texts(bobPhone), "self-copy should sync to the other device: ${texts(bobPhone)}")
                assertFalse(texts(sender).contains("sent from my desktop"))
                sender.store.refresh()
                assertTrue("sent from my desktop" in texts(sender))

                sender.stop(); bobPhone.stop(); bobDesktop.stop()
            } finally {
                scope.cancel()
            }
        }
    }

    @org.junit.Ignore("Known protocol gap, not a desktop bug. See PHASE_D2B_NOTES.md: sealed mailboxes have one owner on the relay.")
    @Test
    fun sealedSenderReachesBothDevicesOfOneIdentity(): Unit = runBlocking {
        // Once a peer has moved to sealed sends, it addresses ONE inbound key per contact, and
        // the relay gives each mailbox to the first device that claims it. A second device on
        // the same identity announces its own inbound key and takes the stream over; the first
        // device stops receiving. Fingerprint-routed sends (above) do fan out; sealed ones do
        // not. This test states the behaviour same-identity desktop use needs. Un-ignore it
        // when the protocol has an answer.
        FakeRelay().use { relay ->
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            try {
                val alice = newIdentity("alice"); val bob = newIdentity("bob")
                val a = session(alice, relay, scope, "a".repeat(32))
                val phone = session(bob, relay, scope, "1".repeat(32))
                a.contacts.add(contactFor(bob, "Bob")); phone.contacts.add(contactFor(alice, "Alice"))
                a.start(3600); phone.start(3600)
                repeat(3) { i ->
                    a.store.send(text = "warm $i", to = a.contacts.contact(bob.fingerprint)!!); phone.store.refresh()
                    phone.store.send(text = "back $i", to = phone.contacts.contact(alice.fingerprint)!!); a.store.refresh()
                }
                assertTrue(a.store.isSealed(bob.fingerprint))

                val desktop = session(bob, relay, scope, "2".repeat(32))
                desktop.contacts.add(contactFor(alice, "Alice")); desktop.start(3600)
                a.store.send(text = "to both, sealed", to = a.contacts.contact(bob.fingerprint)!!)
                phone.store.refresh(); desktop.store.refresh()
                assertTrue("to both, sealed" in texts(phone))
                assertTrue("to both, sealed" in texts(desktop))
            } finally {
                scope.cancel()
            }
        }
    }
}
