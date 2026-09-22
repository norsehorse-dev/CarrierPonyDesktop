// GroupsTest.kt
// Groups and broadcast channels between desktop stacks: the vendored group code (epoch keys,
// roster distribution, sealed fan-out, channel subscribe by invite) running on the JVM through
// the in-process relay. The window in D7 only draws what these calls produce.

package com.carrierpony.desktop

import com.carrierpony.app.crypto.PublicKey
import com.carrierpony.app.messaging.Contact
import com.carrierpony.app.messaging.MessageDirection
import com.carrierpony.app.messaging.TrustLevel
import com.carrierpony.app.pairing.ChannelInvite
import com.carrierpony.app.net.LanDiscovery
import com.carrierpony.app.net.LanMdns
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GroupsTest {

    private val cheap = Vault.Cost(memoryKiB = 256, iterations = 1, parallelism = 1)

    private fun open(name: String, relay: FakeRelay, scope: CoroutineScope, device: String): DesktopSession {
        val dir = Files.createTempDirectory("cp-group-$name")
        val vault = Vault(DesktopStorage.vaultFile(dir)).apply { create("pw".toCharArray(), cheap) }
        val accounts = DesktopAccounts(DesktopStorage(dir, vault), DesktopPrefs(dir.resolve("prefs.json")))
        return accounts.session(accounts.create(name), scope, relayBaseURL = relay.baseURL, deviceID = device, lanDiscovery = LanDiscovery(LanMdns.None) { false })
    }

    private fun contact(s: DesktopSession, name: String) =
        Contact(s.identity.fingerprint, PublicKey(s.identity.fingerprint, s.identity.armoredPublicKey), name, TrustLevel.VERIFIED)

    private fun pairAll(vararg sessions: Pair<DesktopSession, String>) {
        for ((a, _) in sessions) for ((b, bName) in sessions) if (a !== b) a.contacts.add(contact(b, bName))
    }

    private fun groupTexts(s: DesktopSession, groupID: String) =
        s.store.groupMessages.value[groupID].orEmpty().mapNotNull { it.text }

    private suspend fun settle(vararg sessions: DesktopSession, rounds: Int = 3) {
        repeat(rounds) { for (s in sessions) s.store.refresh() }
    }

    @Test
    fun aGroupOfThreeTalksAndARemovedMemberStopsReceiving(): Unit = runBlocking {
        FakeRelay().use { relay ->
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            try {
                val alice = open("Alice", relay, scope, "a".repeat(32))
                val bob = open("Bob", relay, scope, "b".repeat(32))
                val carol = open("Carol", relay, scope, "c".repeat(32))
                pairAll(alice to "Alice", bob to "Bob", carol to "Carol")
                alice.start(3600); bob.start(3600); carol.start(3600)

                alice.store.createGroup("Weekend", listOf(alice.contacts.contact(bob.identity.fingerprint)!!, alice.contacts.contact(carol.identity.fingerprint)!!))
                val group = alice.store.groups.value.values.single()
                assertTrue(alice.store.amGroupAdmin(group.groupID))
                settle(bob, carol, alice)
                assertNotNull(bob.store.groups.value[group.groupID], "Bob should have received the roster and key")
                assertEquals("Weekend", carol.store.groups.value[group.groupID]?.name)
                assertFalse(bob.store.amGroupAdmin(group.groupID))

                alice.store.sendGroupMessage(group.groupID, "hello group")
                settle(bob, carol, alice)
                assertTrue("hello group" in groupTexts(bob, group.groupID), groupTexts(bob, group.groupID).toString())
                assertTrue("hello group" in groupTexts(carol, group.groupID))

                bob.store.sendGroupMessage(group.groupID, "hi from bob")
                settle(alice, carol, bob)
                assertTrue("hi from bob" in groupTexts(alice, group.groupID))
                assertTrue("hi from bob" in groupTexts(carol, group.groupID))

                // Unread bookkeeping the window relies on.
                val unreadAtCarol = carol.store.groupMessages.value[group.groupID].orEmpty().count { it.direction == MessageDirection.INCOMING && !it.isRead }
                assertEquals(2, unreadAtCarol)
                carol.store.markGroupRead(group.groupID)
                assertEquals(0, carol.store.groupMessages.value[group.groupID].orEmpty().count { !it.isRead })

                // Remove Carol: a rekey she never receives.
                alice.store.removeMember(carol.identity.fingerprint, group.groupID)
                settle(bob, carol, alice)
                assertEquals(1, alice.store.groups.value[group.groupID]!!.epoch)
                alice.store.sendGroupMessage(group.groupID, "after carol left")
                settle(bob, carol, alice)
                assertTrue("after carol left" in groupTexts(bob, group.groupID))
                assertFalse("after carol left" in groupTexts(carol, group.groupID), "a removed member must not receive")

                alice.store.renameGroup(group.groupID, "Weekend plans")
                settle(bob, alice)
                assertEquals("Weekend plans", bob.store.groups.value[group.groupID]?.name)
            } finally {
                scope.cancel()
            }
        }
    }

    @Test
    fun aChannelIsJoinedByInviteAndOnlyTheAdminPosts(): Unit = runBlocking {
        FakeRelay().use { relay ->
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            try {
                val admin = open("Admin", relay, scope, "a".repeat(32))
                val reader = open("Reader", relay, scope, "b".repeat(32))
                admin.start(3600); reader.start(3600)

                admin.store.createChannel("Announcements", emptyList())
                val channel = admin.store.groups.value.values.single()
                assertTrue(channel.isChannel)
                val invite = assertNotNull(admin.store.channelInvite(channel.groupID))
                assertTrue(invite.startsWith("CPCHAN1:"))

                // The reader has never paired with the admin: the invite carries the admin's key.
                assertTrue(reader.store.subscribeToChannel(ChannelInvite.decode(invite)!!))
                settle(admin, reader, admin, reader)
                assertNotNull(reader.store.groups.value[channel.groupID], "the admin should have returned the channel key")
                assertFalse(reader.store.amGroupAdmin(channel.groupID))
                assertEquals(2, admin.store.groups.value[channel.groupID]!!.members.size)

                admin.store.sendGroupMessage(channel.groupID, "first post")
                settle(reader, admin)
                assertTrue("first post" in groupTexts(reader, channel.groupID))

                reader.store.unsubscribeFromChannel(channel.groupID)
                assertNull(reader.store.groups.value[channel.groupID])
                settle(admin, reader)
                assertEquals(1, admin.store.groups.value[channel.groupID]!!.members.size, "the admin drops an unsubscribed reader")
            } finally {
                scope.cancel()
            }
        }
    }

    @Test
    fun leavingAGroupDeletesItLocallyAndTheOthersCarryOn(): Unit = runBlocking {
        FakeRelay().use { relay ->
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            try {
                val alice = open("Alice", relay, scope, "a".repeat(32))
                val bob = open("Bob", relay, scope, "b".repeat(32))
                val carol = open("Carol", relay, scope, "c".repeat(32))
                pairAll(alice to "Alice", bob to "Bob", carol to "Carol")
                alice.start(3600); bob.start(3600); carol.start(3600)
                alice.store.createGroup("Trio", listOf(alice.contacts.contact(bob.identity.fingerprint)!!, alice.contacts.contact(carol.identity.fingerprint)!!))
                val id = alice.store.groups.value.keys.single()
                settle(bob, carol, alice)

                bob.store.leaveGroup(id)
                assertNull(bob.store.groups.value[id])
                settle(alice, carol, bob)
                alice.store.sendGroupMessage(id, "still here")
                settle(carol, bob, alice)
                assertTrue("still here" in groupTexts(carol, id))
                assertTrue(groupTexts(bob, id).isEmpty())
            } finally {
                scope.cancel()
            }
        }
    }

    @Test
    fun aPostSentBeforeAMemberRegisteredItsWindowStillArrives(): Unit = runBlocking {
        // The relay answers 404 for an unregistered mailbox (the real one must be changed to do
        // so; see PHASE_D7_NOTES). The sender then falls back to the per-peer path, which the
        // receiver opens as a group payload too.
        FakeRelay().use { relay ->
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            try {
                val alice = open("Alice", relay, scope, "a".repeat(32))
                val bob = open("Bob", relay, scope, "b".repeat(32))
                pairAll(alice to "Alice", bob to "Bob")
                alice.start(3600); bob.start(3600)
                alice.store.createChannel("News", listOf(alice.contacts.contact(bob.identity.fingerprint)!!))
                val id = alice.store.groups.value.keys.single()
                // Alice posts at once. Bob has not polled since the key went out: no windows yet.
                alice.store.sendGroupMessage(id, "posted immediately")
                bob.store.refresh()                       // key arrives, windows get registered
                bob.store.refresh()
                assertTrue("posted immediately" in groupTexts(bob, id), groupTexts(bob, id).toString())
                assertTrue(relay.requestLog.any { it == "/v1/sealed/send:UNKNOWN_MAILBOX" }, "the sealed attempt should have been refused first")
            } finally {
                scope.cancel()
            }
        }
    }
}
