// AppControllerTest.kt
// The window's stages, without a window: first run, unlock, wrong passphrase, identity creation,
// lock, and coming back.

package com.carrierpony.desktop

import com.carrierpony.app.AppConfig
import com.carrierpony.app.net.LanMdns
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class AppControllerTest {
    // The controller's messages go through tr(); the assertions below are the English strings.
    init { I18n.pinEnglish() }


    private val cheap = Vault.Cost(memoryKiB = 256, iterations = 1, parallelism = 1)

    @AfterTest fun resetGlobals() { AppConfig.prefs = null }

    @Test
    fun firstRunThroughLockAndBack(): Unit = runBlocking {
        FakeRelay().use { relay ->
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            try {
                val dir = Files.createTempDirectory("cp-controller")
                val app = AppController(dir, scope, relay.baseURL, cheap, pollIntervalSeconds = 3600, lanMdns = { LanMdns.None })
                assertIs<AppController.Stage.NeedsVault>(app.stage.value)

                assertFalse(app.createVault("short", "short"))
                assertNotNull(app.error.value)
                assertFalse(app.createVault("long enough passphrase", "different passphrase"))
                assertTrue(app.createVault("long enough passphrase", "long enough passphrase"))
                assertIs<AppController.Stage.NeedsIdentity>(app.stage.value)

                assertFalse(app.createIdentity("   "))
                assertTrue(app.createIdentity("Desk"))
                val ready = assertIs<AppController.Stage.Ready>(app.stage.value)
                ready.starting.join()
                assertEquals("desktop", relay.labelOf(AppConfig.deviceID()), "the session should have registered")
                val fpr = ready.session.identity.fingerprint

                app.lock()
                assertIs<AppController.Stage.Locked>(app.stage.value)
                assertFalse(app.unlock("not it"))
                assertEquals("Wrong passphrase.", app.error.value)
                assertTrue(app.unlock("long enough passphrase"))
                assertEquals(fpr, assertIs<AppController.Stage.Ready>(app.stage.value).session.identity.fingerprint)

                // A fresh process over the same directory starts locked, not at first run.
                app.lock()
                val second = AppController(dir, scope, relay.baseURL, cheap, pollIntervalSeconds = 3600, lanMdns = { LanMdns.None })
                assertIs<AppController.Stage.Locked>(second.stage.value)
            } finally {
                scope.cancel()
            }
        }
    }

    @Test
    fun anUnreachableRelayDoesNotBlockLaunch(): Unit = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val app = AppController(Files.createTempDirectory("cp-controller"), scope, "http://127.0.0.1:9", cheap, 3600, lanMdns = { LanMdns.None })
            assertTrue(app.createVault("long enough passphrase", "long enough passphrase"))
            assertTrue(app.createIdentity("Offline"))
            val ready = assertIs<AppController.Stage.Ready>(app.stage.value)
            withTimeout(20_000) { ready.session.store.lastError.first { it != null } }
            assertTrue(ready.starting.isActive, "it should still be retrying, not given up")
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun theSessionOutlivesTheScreenThatCreatedIt(): Unit = runBlocking {
        // The bug from the first real run of the window: createIdentity was called from a
        // screen-scoped coroutine that is cancelled as soon as the stage changes.
        FakeRelay().use { relay ->
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            try {
                val app = AppController(Files.createTempDirectory("cp-controller"), scope, relay.baseURL, cheap, 3600, lanMdns = { LanMdns.None })
                val screen = CoroutineScope(SupervisorJob() + Dispatchers.Default)
                screen.launch {
                    app.createVault("long enough passphrase", "long enough passphrase")
                    app.createIdentity("Desk")
                }
                val ready = withTimeout(20_000) { app.stage.first { it is AppController.Stage.Ready } } as AppController.Stage.Ready
                screen.cancel()                                  // the screen leaves the composition
                ready.starting.join()
                assertEquals(null, ready.session.store.lastError.value)
                assertEquals("desktop", relay.labelOf(AppConfig.deviceID()))
                ready.session.pairing.createInvite()             // a signed call works: the relay knows us
            } finally {
                scope.cancel()
            }
        }
    }
}
