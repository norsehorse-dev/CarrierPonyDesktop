// AppController.kt
// CarrierPony Desktop. What the window is showing and why: no vault yet, locked, unlocked with
// no identity, or ready with a running session. All the decisions live here, in plain Kotlin
// with no Compose imports, so they are unit-tested; Gui.kt only draws the current stage and
// forwards clicks.

package com.carrierpony.desktop

import com.carrierpony.app.AppConfig
import com.carrierpony.app.identity.BackupException
import com.carrierpony.app.messaging.ChatMessage
import com.carrierpony.app.messaging.MessageDirection
import com.carrierpony.app.net.JmDnsLanMdns
import com.carrierpony.app.net.LanDiscovery
import com.carrierpony.app.net.LanMdns
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.nio.file.Files
import java.nio.file.Path

class AppController(
    dataDir: Path,
    private val scope: CoroutineScope,
    private val relayOverride: String? = null,
    private val vaultCost: Vault.Cost = Vault.Cost(),
    private val pollIntervalSeconds: Long = 5,
    private val retrySeconds: Long = 15,
    /** Where new-message notifications go. The window wires the tray; tests record. */
    private val notifier: (title: String, body: String) -> Unit = { _, _ -> },
    private val clock: () -> Long = System::currentTimeMillis,
    /** How LAN discovery reaches the network. Tests pass LanMdns.None. */
    private val lanMdns: () -> LanMdns = { JmDnsLanMdns() },
) {
    sealed interface Stage {
        /** First run on this computer: choose the launch passphrase. */
        data object NeedsVault : Stage
        data object Locked : Stage
        /** Unlocked, but there is no identity yet: create one or restore a backup. */
        data object NeedsIdentity : Stage
        /** [starting] completes once the session has registered with the relay and drained the
         *  inbox once. The window does not wait for it; tests do. */
        class Ready(val session: DesktopSession, val accounts: DesktopAccounts, val starting: Job) : Stage
    }

    companion object {
        const val MIN_PASSPHRASE = 8
    }

    private val dir: Path = Files.createDirectories(dataDir)
    private val vault = Vault(DesktopStorage.vaultFile(dir))
    /** Plain, non-secret settings. Public so the window can attach the language, theme and
     *  update-check switches before the first frame; secrets never live here. */
    val prefs = DesktopPrefs(dir.resolve("prefs.json")).also { AppConfig.prefs = it }
    private var accounts: DesktopAccounts? = null
    val settings = Settings(prefs)

    /** Unread incoming messages across every conversation and group of the active account. */
    private val _unread = MutableStateFlow(0)
    val unread: StateFlow<Int> = _unread

    private var watcher: Job? = null
    @Volatile private var lastActivity: Long = clock()

    /** Accounts on this computer, for the switcher. Empty until unlocked. */
    fun accountList(): List<DesktopAccounts.Summary> = accounts?.list().orEmpty()

    private val _stage = MutableStateFlow<Stage>(if (vault.exists) Stage.Locked else Stage.NeedsVault)
    val stage: StateFlow<Stage> = _stage

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error
    fun clearError() { _error.value = null }

    /** First run. False (with [error] set) when the passphrase is too short or the repeat differs. */
    suspend fun createVault(passphrase: String, repeat: String): Boolean {
        _error.value = when {
            passphrase.length < MIN_PASSPHRASE -> tr("d_err_passphrase_short", MIN_PASSPHRASE)
            passphrase != repeat -> tr("d_err_passphrase_mismatch")
            else -> null
        }
        if (_error.value != null) return false
        withContext(Dispatchers.Default) { vault.create(passphrase.toCharArray(), vaultCost) }
        afterUnlock()
        return true
    }

    suspend fun unlock(passphrase: String): Boolean {
        val ok = withContext(Dispatchers.Default) { vault.unlock(passphrase.toCharArray()) }
        if (!ok) { _error.value = tr("d_err_wrong_passphrase"); return false }
        _error.value = null
        afterUnlock()
        return true
    }

    suspend fun createIdentity(name: String): Boolean {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) { _error.value = tr("d_err_name_required"); return false }
        val a = accounts ?: return false
        val identity = withContext(Dispatchers.Default) { a.create(trimmed) }
        _error.value = null
        ready(a, identity.fingerprint.hex)
        return true
    }

    suspend fun restoreBackup(blob: String, backupPassphrase: String): Boolean {
        val a = accounts ?: return false
        val identity = try {
            withContext(Dispatchers.Default) { a.restoreBackup(blob, backupPassphrase) }
        } catch (e: BackupException) {
            _error.value = e.message; return false
        }
        _error.value = null
        ready(a, identity.fingerprint.hex)
        return true
    }

    /** Stop the session, drop the key from memory, and show the unlock screen. */
    fun lock() {
        stopSession()
        vault.lock()
        accounts = null
        _stage.value = Stage.Locked
    }

    private fun stopSession() {
        watcher?.cancel(); watcher = null
        (_stage.value as? Stage.Ready)?.let { it.starting.cancel(); it.session.stop() }
        _unread.value = 0
    }

    /** Any keyboard or mouse activity in the window. The idle timer counts from the last one. */
    fun noteActivity() { lastActivity = clock() }

    // ── Several accounts ───────────────────────────────────────────────

    suspend fun switchAccount(fprHex: String): Boolean {
        val a = accounts ?: return false
        val current = (_stage.value as? Stage.Ready)?.session?.identity?.fingerprint?.hex
        if (fprHex == current) return true
        if (a.load(fprHex) == null) return false
        stopSession()
        a.select(fprHex)
        ready(a, fprHex)
        return true
    }

    /** Add an account while unlocked and make it active. Same rules as createIdentity. */
    suspend fun addAccount(name: String): Boolean {
        val a = accounts ?: return false
        val trimmed = name.trim()
        if (trimmed.isEmpty()) { _error.value = tr("d_err_name_required"); return false }
        val identity = withContext(Dispatchers.Default) { a.create(trimmed) }
        stopSession()
        ready(a, identity.fingerprint.hex)
        return true
    }

    suspend fun addAccountFromBackup(blob: String, backupPassphrase: String): Boolean {
        val a = accounts ?: return false
        val identity = try {
            withContext(Dispatchers.Default) { a.restoreBackup(blob, backupPassphrase) }
        } catch (e: BackupException) {
            _error.value = e.message; return false
        }
        stopSession()
        ready(a, identity.fingerprint.hex)
        return true
    }

    /** Remove an account and everything stored for it here. The next account becomes active;
     *  with none left the identity screen shows. */
    suspend fun removeAccount(fprHex: String) {
        val a = accounts ?: return
        val active = (_stage.value as? Stage.Ready)?.session?.identity?.fingerprint?.hex
        if (fprHex == active) stopSession()
        val next = withContext(Dispatchers.Default) { a.remove(fprHex) }
        if (fprHex != active) return
        if (next == null) _stage.value = Stage.NeedsIdentity else ready(a, next)
    }

    private suspend fun afterUnlock() {
        val a = DesktopAccounts(DesktopStorage(dir, vault), prefs)
        accounts = a
        val selected = a.selected()
        if (selected == null) _stage.value = Stage.NeedsIdentity else ready(a, selected.fingerprint.hex)
    }

    private suspend fun ready(a: DesktopAccounts, fprHex: String) {
        val identity = a.load(fprHex) ?: return
        val session = a.session(
            identity, scope,
            relayBaseURL = relayOverride ?: AppConfig.relayBaseURL(),
            deviceID = AppConfig.deviceID(),
            lanDiscovery = LanDiscovery(lanMdns())
        )
        // The session must start on the app's own scope, never on the caller's. The caller is a
        // screen's rememberCoroutineScope, and that screen leaves the composition the moment the
        // stage flips to Ready, which cancels its scope. Started there, register-device was
        // cancelled mid-request: the relay never learned this key, so every later signed call
        // came back 401 bad_signature, and the cancellation text landed in the error banner.
        //
        // It also keeps trying. ChatStore.start registers exactly once, so a launch with no
        // network would otherwise stay unregistered until the app was restarted.
        val starting = scope.launch {
            while (true) {
                session.start(pollIntervalSeconds)
                if (session.store.lastError.value == null) break
                delay(retrySeconds * 1000)
                session.store.clearError()
            }
        }
        _stage.value = Stage.Ready(session, a, starting)
        watcher = scope.launch { watch(session) }
    }

    // Unread count for the tray, a notification per batch of new incoming messages, and the
    // idle lock. One coroutine per session; cancelled by stopSession().
    private suspend fun watch(session: DesktopSession) {
        val known = HashSet<String>()
        var primed = false
        combine(session.store.conversations, session.store.groupMessages) { convs, groups ->
            convs.values.flatMap { it.messages } + groups.values.flatten()
        }.collect { all ->
            _unread.value = all.count { it.direction == MessageDirection.INCOMING && !it.isRead }
            val fresh = all.filter { it.direction == MessageDirection.INCOMING && known.add(it.id) }
            // The first emission is history loaded from disk, not news.
            if (primed && fresh.isNotEmpty()) notify(session, fresh)
            primed = true
        }
    }

    private fun notify(session: DesktopSession, fresh: List<ChatMessage>) {
        if (!settings.notifications) return
        val body = if (settings.notificationsShowSender) {
            val names = fresh.map { m -> session.contacts.contact(m.peer)?.displayName ?: session.store.groups.value[m.threadID]?.name ?: tr("d_someone") }.distinct()
            if (fresh.size == 1) tr("d_notify_from_one", names.single()) else tr("d_notify_from_many", fresh.size, names.joinToString(", "))
        } else {
            if (fresh.size == 1) tr("d_notify_one") else tr("d_notify_many", fresh.size)
        }
        notifier("CarrierPony", body)
    }

    /** Runs for the life of the app: locks when idle longer than the setting. Call once. */
    suspend fun runIdleLock(checkEverySeconds: Long = 30) {
        while (scope.isActive) {
            delay(checkEverySeconds * 1000)
            val minutes = settings.lockAfterMinutes
            if (minutes > 0 && _stage.value !is Stage.Locked && _stage.value !is Stage.NeedsVault &&
                clock() - lastActivity >= minutes * 60_000L) {
                lock()
            }
        }
    }
}
