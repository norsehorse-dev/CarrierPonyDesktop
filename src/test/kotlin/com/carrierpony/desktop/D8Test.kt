// D8Test.kt
// Several accounts, unread counting, notifications, the idle lock, settings and launch-at-login
// files, all without a window.

package com.carrierpony.desktop

import com.carrierpony.app.AppConfig
import com.carrierpony.app.crypto.PublicKey
import com.carrierpony.app.messaging.Contact
import com.carrierpony.app.messaging.TrustLevel
import com.carrierpony.app.net.LanMdns
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class D8Test {
    // The controller's messages go through tr(); the assertions below are the English strings.
    init { I18n.pinEnglish() }


    private val cheap = Vault.Cost(memoryKiB = 256, iterations = 1, parallelism = 1)

    @AfterTest fun resetGlobals() { AppConfig.prefs = null }

    private fun contactFor(s: DesktopSession, name: String) =
        Contact(s.identity.fingerprint, PublicKey(s.identity.fingerprint, s.identity.armoredPublicKey), name, TrustLevel.VERIFIED)

    @Test
    fun switchAddAndRemoveAccounts(): Unit = runBlocking {
        FakeRelay().use { relay ->
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            try {
                val app = AppController(Files.createTempDirectory("cp-d8"), scope, relay.baseURL, cheap, 3600, lanMdns = { LanMdns.None })
                app.createVault("long enough passphrase", "long enough passphrase")
                app.createIdentity("First")
                val first = assertIs<AppController.Stage.Ready>(app.stage.value).session.identity.fingerprint
                assertTrue(app.addAccount("Second"))
                val second = assertIs<AppController.Stage.Ready>(app.stage.value).session.identity.fingerprint
                assertEquals(listOf("First", "Second"), app.accountList().map { it.name })

                assertTrue(app.switchAccount(first.hex))
                assertEquals(first, assertIs<AppController.Stage.Ready>(app.stage.value).session.identity.fingerprint)
                assertFalse(app.switchAccount("0".repeat(40)))

                app.removeAccount(first.hex)
                assertEquals(second, assertIs<AppController.Stage.Ready>(app.stage.value).session.identity.fingerprint, "removing the active account moves to the next")
                app.removeAccount(second.hex)
                assertIs<AppController.Stage.NeedsIdentity>(app.stage.value)
            } finally {
                scope.cancel()
            }
        }
    }

    @Test
    fun unreadCountAndNotificationsFollowIncomingMessages(): Unit = runBlocking {
        FakeRelay().use { relay ->
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            try {
                val notes = mutableListOf<String>()
                val app = AppController(Files.createTempDirectory("cp-d8"), scope, relay.baseURL, cheap, 3600, notifier = { _, body -> synchronized(notes) { notes.add(body) } }, lanMdns = { LanMdns.None })
                app.createVault("long enough passphrase", "long enough passphrase")
                app.createIdentity("Desk")
                val desk = assertIs<AppController.Stage.Ready>(app.stage.value)
                desk.starting.join()

                // A plain second stack plays the phone.
                val dir = Files.createTempDirectory("cp-d8-phone")
                val vault = Vault(DesktopStorage.vaultFile(dir)).apply { create("pw".toCharArray(), cheap) }
                val accounts = DesktopAccounts(DesktopStorage(dir, vault), DesktopPrefs(dir.resolve("prefs.json")))
                val phone = accounts.session(accounts.create("Phone"), scope, relayBaseURL = relay.baseURL, deviceID = "b".repeat(32))
                phone.contacts.add(contactFor(desk.session, "Desk")); desk.session.contacts.add(contactFor(phone, "Phone"))
                phone.start(3600)

                assertEquals(0, app.unread.value)
                phone.store.send(text = "one", to = phone.contacts.contact(desk.session.identity.fingerprint)!!)
                desk.session.store.refresh()
                withTimeout(5_000) { app.unread.first { it == 1 } }
                assertEquals(listOf("You have a new message."), synchronized(notes) { notes.toList() })

                app.settings.notificationsShowSender = true
                phone.store.send(text = "two", to = phone.contacts.contact(desk.session.identity.fingerprint)!!)
                desk.session.store.refresh()
                withTimeout(5_000) { app.unread.first { it == 2 } }
                assertEquals("From Phone", synchronized(notes) { notes.last() })

                app.settings.notifications = false
                phone.store.send(text = "three", to = phone.contacts.contact(desk.session.identity.fingerprint)!!)
                desk.session.store.refresh()
                withTimeout(5_000) { app.unread.first { it == 3 } }
                assertEquals(2, synchronized(notes) { notes.size }, "notifications off means silence")

                val thread = desk.session.store.conversations.value.keys.single()
                desk.session.store.markRead(thread)
                withTimeout(5_000) { app.unread.first { it == 0 } }
            } finally {
                scope.cancel()
            }
        }
    }

    @Test
    fun idleLockFiresFromTheLastActivity(): Unit = runBlocking {
        FakeRelay().use { relay ->
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            try {
                var now = 1_000_000L
                val app = AppController(Files.createTempDirectory("cp-d8"), scope, relay.baseURL, cheap, 3600, clock = { now }, lanMdns = { LanMdns.None })
                app.createVault("long enough passphrase", "long enough passphrase")
                app.createIdentity("Desk")
                app.settings.lockAfterMinutes = 2
                val idle = scope.launch { app.runIdleLock(checkEverySeconds = 1) }

                now += 90_000; app.noteActivity()          // active at t+90s
                now += 90_000                              // t+180s: 90s since activity, under 2 min
                kotlinx.coroutines.delay(1_500)
                assertIs<AppController.Stage.Ready>(app.stage.value)
                now += 40_000                              // 130s since activity
                withTimeout(5_000) { app.stage.first { it is AppController.Stage.Locked } }
                idle.cancel()

                app.settings.lockAfterMinutes = 0
                assertEquals(0, app.settings.lockAfterMinutes)
            } finally {
                scope.cancel()
            }
        }
    }

    @Test
    fun settingsPersistAndDefaultPrivately() {
        val dir = Files.createTempDirectory("cp-d8-settings")
        val s = Settings(DesktopPrefs(dir.resolve("prefs.json")))
        assertEquals(15, s.lockAfterMinutes); assertTrue(s.closeToTray); assertTrue(s.notifications); assertFalse(s.notificationsShowSender)
        s.lockAfterMinutes = 5; s.closeToTray = false; s.notificationsShowSender = true; s.downloadsDir = dir
        val again = Settings(DesktopPrefs(dir.resolve("prefs.json")))
        assertEquals(5, again.lockAfterMinutes); assertFalse(again.closeToTray); assertTrue(again.notificationsShowSender); assertEquals(dir, again.downloadsDir)
    }

    @Test
    fun launchAtLoginWritesAndRemovesTheRightFile() {
        val home = Files.createTempDirectory("cp-d8-home")
        val mac = LaunchAtLogin(home, "mac os x", "/Applications/CarrierPony.app/Contents/MacOS/CarrierPony")
        assertTrue(mac.isSupported); assertFalse(mac.isEnabled())
        assertTrue(mac.setEnabled(true)); assertTrue(mac.isEnabled())
        val plist = String(Files.readAllBytes(home.resolve("Library/LaunchAgents/com.carrierpony.desktop.plist")))
        assertTrue(plist.contains("<string>/Applications/CarrierPony.app/Contents/MacOS/CarrierPony</string>") && plist.contains("--hidden"))
        assertTrue(mac.setEnabled(false)); assertFalse(mac.isEnabled())

        val linux = LaunchAtLogin(home, "linux", "/opt/carrierpony/bin/CarrierPony")
        assertTrue(linux.setEnabled(true))
        assertTrue(String(Files.readAllBytes(home.resolve(".config/autostart/carrierpony.desktop"))).contains("Exec=\"/opt/carrierpony/bin/CarrierPony\" --hidden"))

        val calls = mutableListOf<List<String>>()
        val win = LaunchAtLogin(home, "windows 11", "C:\\Program Files\\CarrierPony\\CarrierPony.exe", runRegistry = { calls.add(it); 0 })
        assertTrue(win.setEnabled(true))
        assertEquals("add", calls.last()[0]); assertTrue(calls.last().contains("\"C:\\Program Files\\CarrierPony\\CarrierPony.exe\" --hidden"))

        val dev = LaunchAtLogin(home, "mac os x", appPath = null)
        assertFalse(dev.isSupported); assertFalse(dev.setEnabled(true))
    }
}
