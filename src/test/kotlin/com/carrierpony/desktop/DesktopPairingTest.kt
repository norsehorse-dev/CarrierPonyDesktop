// DesktopPairingTest.kt
// Pairing over the (fake) relay, both directions, and the two refusals that make it safe.

package com.carrierpony.desktop

import com.carrierpony.app.messaging.TrustLevel
import com.carrierpony.app.pairing.Invite
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
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DesktopPairingTest {

    private val cheap = Vault.Cost(memoryKiB = 256, iterations = 1, parallelism = 1)

    private class Peer(val accounts: DesktopAccounts, val session: DesktopSession)

    private fun peer(name: String, relay: FakeRelay, scope: CoroutineScope, deviceID: String, dir: Path = Files.createTempDirectory("cp-pair")): Peer {
        val vault = Vault(DesktopStorage.vaultFile(dir))
        if (vault.exists) assertTrue(vault.unlock("pw".toCharArray())) else vault.create("pw".toCharArray(), cheap)
        val accounts = DesktopAccounts(DesktopStorage(dir, vault), DesktopPrefs(dir.resolve("prefs.json")))
        val identity = accounts.selected() ?: accounts.create(name)
        return Peer(accounts, accounts.session(identity, scope, relayBaseURL = relay.baseURL, deviceID = deviceID, lanDiscovery = LanDiscovery(LanMdns.None) { false }))
    }

    private fun texts(s: DesktopSession) = s.store.conversations.value.values.flatMap { it.messages }.mapNotNull { it.text }

    @Test
    fun remoteInvitePairsBothSidesUnverifiedAndTheyCanTalk(): Unit = runBlocking {
        FakeRelay().use { relay ->
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            try {
                val alice = peer("Alice", relay, scope, "a".repeat(32))
                val bob = peer("Bob", relay, scope, "b".repeat(32))
                alice.session.start(3600); bob.session.start(3600)

                val (invite, expiresAt) = alice.session.pairing.createInvite()
                assertNotNull(expiresAt)
                assertEquals(1, alice.session.pairing.pending().size)
                assertNull(alice.session.pairing.pollInvite(invite.t), "nobody has accepted yet")

                // The invite travels as text, the way it would through another messenger.
                val added = bob.session.pairing.acceptInvite(invite.encoded())
                assertEquals(alice.session.identity.fingerprint, added.fingerprint)
                assertEquals("Alice", added.name)
                assertEquals(TrustLevel.UNVERIFIED, added.trust)

                // Alice's side completes through the sweep that runs inside refresh().
                alice.session.pairing.sweep(force = true)
                val bobAtAlice = alice.session.contacts.contact(bob.session.identity.fingerprint)
                assertNotNull(bobAtAlice)
                assertEquals(TrustLevel.UNVERIFIED, bobAtAlice.trust)
                assertTrue(alice.session.pairing.pending().isEmpty())

                // Profiles were exchanged, so Alice learns Bob's name from his profile message.
                alice.session.store.refresh()
                assertEquals("Bob", alice.session.contacts.contact(bob.session.identity.fingerprint)?.name)

                bob.session.store.send(text = "paired over the relay", to = added)
                alice.session.store.refresh()
                assertTrue("paired over the relay" in texts(alice.session))
            } finally {
                scope.cancel()
            }
        }
    }

    @Test
    fun inPersonInviteCompletesVerifiedOnTheOfferer(): Unit = runBlocking {
        FakeRelay().use { relay ->
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            try {
                val desktop = peer("Desk", relay, scope, "a".repeat(32))
                val phone = peer("Phone", relay, scope, "b".repeat(32))
                desktop.session.start(3600); phone.session.start(3600)
                val (invite, _) = desktop.session.pairing.createInvite(inPerson = true)
                phone.session.pairing.acceptInvite(invite, TrustLevel.VERIFIED)
                val contact = desktop.session.pairing.pollInvite(invite.t)
                assertNotNull(contact)
                assertEquals(TrustLevel.VERIFIED, desktop.session.contacts.contact(phone.session.identity.fingerprint)?.trust)
                assertEquals(contact, desktop.session.pairing.pollInvite(invite.t), "a second poll must not add twice")
                assertEquals(1, desktop.session.contacts.contacts.size)
            } finally {
                scope.cancel()
            }
        }
    }

    @Test
    fun pendingOffersSurviveARestart(): Unit = runBlocking {
        FakeRelay().use { relay ->
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            try {
                val dir = Files.createTempDirectory("cp-pair-restart")
                val alice = peer("Alice", relay, scope, "a".repeat(32), dir)
                val bob = peer("Bob", relay, scope, "b".repeat(32))
                alice.session.start(3600); bob.session.start(3600)
                val (invite, _) = alice.session.pairing.createInvite()
                alice.session.stop()

                bob.session.pairing.acceptInvite(invite)
                val again = peer("Alice", relay, scope, "a".repeat(32), dir)
                assertEquals(1, again.session.pairing.pending().size)
                again.session.start(3600)                    // refresh() runs the sweep
                assertNotNull(again.session.contacts.contact(bob.session.identity.fingerprint))
            } finally {
                scope.cancel()
            }
        }
    }

    @Test
    fun aRelayThatSwapsTheKeyIsRefused(): Unit = runBlocking {
        FakeRelay().use { relay ->
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            try {
                val alice = peer("Alice", relay, scope, "a".repeat(32))
                val bob = peer("Bob", relay, scope, "b".repeat(32))
                val mallory = peer("Mallory", relay, scope, "c".repeat(32))
                alice.session.start(3600); bob.session.start(3600)
                val (invite, _) = alice.session.pairing.createInvite()
                relay.swapKey = mallory.session.identity.armoredPublicKey
                assertFailsWith<PairingException.KeyMismatch> { bob.session.pairing.acceptInvite(invite) }
                assertTrue(bob.session.contacts.contacts.isEmpty(), "a refused pairing must add nothing")
            } finally {
                scope.cancel()
            }
        }
    }

    @Test
    fun garbageAndOwnInvitesAreRefused(): Unit = runBlocking {
        FakeRelay().use { relay ->
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            try {
                val alice = peer("Alice", relay, scope, "a".repeat(32))
                alice.session.start(3600)
                assertFailsWith<PairingException.MalformedInvite> { alice.session.pairing.acceptInvite("hello there") }
                val (own, _) = alice.session.pairing.createInvite()
                assertFailsWith<PairingException.OwnInvite> { alice.session.pairing.acceptInvite(Invite.decode(own.encoded())!!) }
            } finally {
                scope.cancel()
            }
        }
    }
}
