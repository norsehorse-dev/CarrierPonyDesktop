// Cli.kt
// CarrierPony Desktop. The `carrierpony` command line: the same vault, accounts and messaging
// stack the window uses, driven from a terminal. It exists for scripts, and it is also how the
// stack gets exercised against the real relay and real phones before there is a window.
//
// Output is plain and stable, one record per line, so it can be piped. Errors go to stderr.
// Exit codes are in ExitCode. The launch passphrase comes from CARRIERPONY_PASSPHRASE, else an
// interactive prompt; it is never accepted as an argument, because arguments show up in `ps`
// and in shell history.

package com.carrierpony.desktop

import com.carrierpony.app.AppConfig
import com.carrierpony.app.identity.BackupException
import com.carrierpony.app.identity.Identity
import com.carrierpony.app.identity.KeyImportException
import com.carrierpony.app.messaging.Contact
import com.carrierpony.app.messaging.MessageDirection
import com.carrierpony.app.net.LanDiscovery
import com.carrierpony.app.net.LanMdns
import com.carrierpony.app.relay.RelayException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import java.io.BufferedReader
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.text.SimpleDateFormat
import java.util.Date

class CliError(val code: Int, message: String) : Exception(message)

class Cli(
    private val out: PrintStream = System.out,
    private val err: PrintStream = System.err,
    private val input: BufferedReader = System.`in`.bufferedReader(),
    private val env: (String) -> String? = System::getenv,
    /** Tests point this at an in-process relay. Not reachable from arguments or the
     *  environment: a user's relay is set with `relay`, which insists on https. */
    private val relayOverride: String? = null,
    private val vaultCost: Vault.Cost = Vault.Cost(),
) {
    companion object {
        val VERBS = setOf(
            "init", "create", "restore", "backup", "import-key", "accounts", "use", "relay",
            "invite", "accept", "list-contacts", "send", "inbox"
        )

        const val USAGE = """Messaging commands (they share the app's data, and need the launch passphrase):

  init                          Choose the launch passphrase for this computer (first run)
  create <name>                 Create a new identity
  restore <backup-file>         Bring an identity over from a phone or desktop backup
  backup <out-file>             Write a passphrase-protected backup of the selected identity
  import-key <armored-key-file> Use an existing Ed25519 OpenPGP private key as an identity
  accounts                      List identities (* marks the selected one)
  use <fingerprint>             Select an identity
  relay [<https-url> | default] Show or set the relay
  invite [--in-person] [--wait <seconds>]
                                Publish a pairing invite, print it, wait for it to be accepted
  accept <invite>               Accept an invite (CPPAIR1:...)
  list-contacts                 List contacts
  send <contact> <text...>      Send a message. <contact> is a fingerprint, a unique fingerprint
                                suffix, or a unique name
  inbox [--watch]               Fetch and print new messages (--watch keeps polling)

Environment: CARRIERPONY_PASSPHRASE (launch passphrase), CARRIERPONY_BACKUP_PASSPHRASE,
CARRIERPONY_KEY_PASSPHRASE, CARRIERPONY_DATA_DIR."""
    }

    fun run(args: List<String>): Int = try {
        dispatch(args)
        ExitCode.OK
    } catch (e: CliError) {
        err.println("carrierpony: ${e.message}"); e.code
    } catch (e: PairingException) {
        err.println("carrierpony: ${e.message}"); ExitCode.FAILURE
    } catch (e: BackupException) {
        err.println("carrierpony: ${e.message}"); ExitCode.FAILURE
    } catch (e: KeyImportException) {
        err.println("carrierpony: ${e.message}"); ExitCode.FAILURE
    } catch (e: RelayException) {
        err.println("carrierpony: the relay refused the request (${e.message})"); ExitCode.RELAY
    } catch (e: java.io.IOException) {
        err.println("carrierpony: could not reach the relay or read a file (${e.message})"); ExitCode.RELAY
    }

    // ── Plumbing ───────────────────────────────────────────────────────

    private fun dataDir(): Path = env("CARRIERPONY_DATA_DIR")?.takeIf { it.isNotBlank() }?.let { Paths.get(it) } ?: Config.dataDir

    private fun secret(envName: String, prompt: String): String {
        env(envName)?.let { return it }
        val console = System.console()
        if (console != null) return String(console.readPassword("%s: ", prompt) ?: CharArray(0))
        err.print("$prompt: "); err.flush()
        return input.readLine() ?: ""
    }

    private fun usage(text: String): Nothing = throw CliError(ExitCode.USAGE, "usage: carrierpony $text")

    private class Opened(val storage: DesktopStorage, val accounts: DesktopAccounts, val prefs: DesktopPrefs)

    private fun open(): Opened {
        val dir = Files.createDirectories(dataDir())
        val vault = Vault(DesktopStorage.vaultFile(dir))
        if (!vault.exists) throw CliError(ExitCode.FAILURE, "no launch passphrase has been set on this computer yet. Run: carrierpony init")
        if (!vault.unlock(secret("CARRIERPONY_PASSPHRASE", "Launch passphrase").toCharArray())) {
            throw CliError(ExitCode.AUTH, "wrong launch passphrase")
        }
        val prefs = DesktopPrefs(dir.resolve("prefs.json"))
        AppConfig.prefs = prefs
        val storage = DesktopStorage(dir, vault)
        return Opened(storage, DesktopAccounts(storage, prefs), prefs)
    }

    private fun selected(o: Opened): Identity =
        o.accounts.selected() ?: throw CliError(ExitCode.FAILURE, "there is no identity yet. Run: carrierpony create <name>, or restore <backup-file>")

    /** Build the stack for the selected identity, register with the relay, run [block], stop. */
    private fun <T> withSession(o: Opened, block: suspend (DesktopSession) -> T): T = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            // A one-shot command never joins the LAN: no multicast, nothing to shut down after.
            val session = o.accounts.session(
                selected(o), scope,
                relayBaseURL = relayOverride ?: AppConfig.relayBaseURL(),
                deviceID = AppConfig.deviceID(),
                lanDiscovery = LanDiscovery(LanMdns.None) { false }
            )
            session.start(pollIntervalSeconds = 3600)     // one registration and one inbox pass
            try { block(session) } finally { session.stop() }
        } finally {
            scope.cancel()
        }
    }

    private fun resolveContact(session: DesktopSession, who: String): Contact {
        val all = session.contacts.contacts
        val needle = who.replace(" ", "").uppercase()
        val byFpr = all.filter { it.fingerprint.hex == needle }.ifEmpty {
            if (needle.length >= 8 && needle.all { it in "0123456789ABCDEF" }) all.filter { it.fingerprint.hex.endsWith(needle) } else emptyList()
        }
        val matches = byFpr.ifEmpty { all.filter { it.displayName.equals(who, ignoreCase = true) } }
        return when (matches.size) {
            1 -> matches[0]
            0 -> throw CliError(ExitCode.FAILURE, "no contact matches '$who'. See: carrierpony list-contacts")
            else -> throw CliError(ExitCode.FAILURE, "'$who' matches ${matches.size} contacts. Use a fingerprint")
        }
    }

    private fun shortFpr(hex: String) = hex.takeLast(16).chunked(4).joinToString(" ")

    // ── Verbs ──────────────────────────────────────────────────────────

    private fun dispatch(args: List<String>) {
        val rest = args.drop(1)
        when (args.firstOrNull()) {
            "init" -> init()
            "create" -> create(rest)
            "restore" -> restore(rest)
            "backup" -> backup(rest)
            "import-key" -> importKey(rest)
            "accounts" -> accounts()
            "use" -> use(rest)
            "relay" -> relay(rest)
            "invite" -> invite(rest)
            "accept" -> accept(rest)
            "list-contacts" -> listContacts()
            "send" -> send(rest)
            "inbox" -> inbox(rest)
            else -> throw CliError(ExitCode.USAGE, "unknown command. Run: carrierpony help")
        }
    }

    private fun init() {
        val dir = Files.createDirectories(dataDir())
        val vault = Vault(DesktopStorage.vaultFile(dir))
        if (vault.exists) throw CliError(ExitCode.FAILURE, "a launch passphrase is already set for $dir")
        val first = secret("CARRIERPONY_PASSPHRASE", "Choose a launch passphrase")
        if (first.length < 8) throw CliError(ExitCode.FAILURE, "the launch passphrase must be at least 8 characters")
        if (env("CARRIERPONY_PASSPHRASE") == null && secret("CARRIERPONY_PASSPHRASE", "Repeat it") != first) {
            throw CliError(ExitCode.FAILURE, "the two passphrases did not match")
        }
        vault.create(first.toCharArray(), vaultCost)
        out.println("Launch passphrase set. There is no recovery: if it is lost, the data in $dir cannot be opened.")
    }

    private fun create(rest: List<String>) {
        val name = rest.joinToString(" ").trim().ifEmpty { usage("create <name>") }
        val identity = open().accounts.create(name)
        out.println("created ${identity.fingerprint.hex} $name")
    }

    private fun restore(rest: List<String>) {
        val file = rest.singleOrNull() ?: usage("restore <backup-file>")
        val o = open()
        val blob = String(Files.readAllBytes(Paths.get(file)), Charsets.UTF_8)
        val identity = o.accounts.restoreBackup(blob, secret("CARRIERPONY_BACKUP_PASSPHRASE", "Backup passphrase"))
        out.println("restored ${identity.fingerprint.hex}")
    }

    private fun backup(rest: List<String>) {
        val file = rest.singleOrNull() ?: usage("backup <out-file>")
        val o = open()
        val target = Paths.get(file)
        if (Files.exists(target)) throw CliError(ExitCode.FAILURE, "$file already exists; backups never overwrite")
        val pass = secret("CARRIERPONY_BACKUP_PASSPHRASE", "Backup passphrase")
        if (pass.length < 8) throw CliError(ExitCode.FAILURE, "the backup passphrase must be at least 8 characters")
        val blob = o.accounts.exportBackup(selected(o).fingerprint.hex, pass)
        AtomicFiles.write(target, blob.toByteArray(Charsets.UTF_8))
        out.println("wrote $file")
    }

    private fun importKey(rest: List<String>) {
        val file = rest.singleOrNull() ?: usage("import-key <armored-key-file>")
        val o = open()
        val armored = String(Files.readAllBytes(Paths.get(file)), Charsets.UTF_8)
        val needs = com.carrierpony.app.identity.KeyImport.inspect(armored).needsPassphrase
        val pass = if (needs) secret("CARRIERPONY_KEY_PASSPHRASE", "Passphrase for this key") else null
        val identity = o.accounts.importKey(armored, pass)
        out.println("imported ${identity.fingerprint.hex}")
    }

    private fun accounts() {
        val o = open()
        val current = o.accounts.selected()?.fingerprint
        for (a in o.accounts.list()) {
            out.println("${if (a.fingerprint == current) "*" else " "} ${a.fingerprint.hex} ${a.name ?: ""}".trimEnd())
        }
    }

    private fun use(rest: List<String>) {
        val fpr = rest.singleOrNull()?.replace(" ", "")?.uppercase() ?: usage("use <fingerprint>")
        val o = open()
        if (o.accounts.list().none { it.fingerprint.hex == fpr }) throw CliError(ExitCode.FAILURE, "no identity $fpr")
        o.accounts.select(fpr)
        out.println("selected $fpr")
    }

    private fun relay(rest: List<String>) {
        AppConfig.prefs = DesktopPrefs(Files.createDirectories(dataDir()).resolve("prefs.json"))
        when (val arg = rest.singleOrNull()) {
            null -> if (rest.isEmpty()) out.println(AppConfig.relayBaseURL()) else usage("relay [<https-url> | default]")
            "default" -> { AppConfig.setRelayBaseURL(null); out.println(AppConfig.relayBaseURL()) }
            else -> {
                if (!AppConfig.setRelayBaseURL(arg)) throw CliError(ExitCode.FAILURE, "a relay must be an https URL with a host")
                out.println(AppConfig.relayBaseURL())
            }
        }
    }

    private fun invite(rest: List<String>) {
        val inPerson = "--in-person" in rest
        val waitIdx = rest.indexOf("--wait")
        val wait = if (waitIdx >= 0) rest.getOrNull(waitIdx + 1)?.toLongOrNull() ?: usage("invite [--in-person] [--wait <seconds>]") else 300L
        withSession(open()) { session ->
            val (invite, _) = session.pairing.createInvite(inPerson)
            out.println(invite.encoded())
            out.flush()
            val deadline = System.currentTimeMillis() + wait * 1000
            var contact: Contact? = null
            while (contact == null && System.currentTimeMillis() < deadline) {
                delay(2000)
                contact = session.pairing.pollInvite(invite.t)
            }
            if (contact == null) {
                err.println("carrierpony: not accepted yet. The invite stays valid; it completes on the next inbox or invite run.")
            } else {
                session.store.refresh()                      // pick up their profile name
                val named = session.contacts.contact(contact.fingerprint) ?: contact
                out.println("paired ${named.fingerprint.hex} ${named.trust.wire} ${named.displayName ?: ""}".trimEnd())
            }
        }
    }

    private fun accept(rest: List<String>) {
        val text = rest.singleOrNull() ?: usage("accept <invite>")
        withSession(open()) { session ->
            val contact = session.pairing.acceptInvite(text)
            out.println("paired ${contact.fingerprint.hex} ${contact.trust.wire} ${contact.displayName ?: ""}".trimEnd())
        }
    }

    private fun listContacts() {
        val o = open()
        val contacts = com.carrierpony.app.messaging.ContactStore(o.storage.dir.toFile()).let { store ->
            o.storage.installAtRestCodec()
            store.activate(selected(o).fingerprint.hex)
            store.contacts
        }
        for (c in contacts) out.println("${c.fingerprint.hex} ${c.trust.wire} ${c.displayName ?: ""}".trimEnd())
    }

    private fun send(rest: List<String>) {
        if (rest.size < 2) usage("send <contact> <text...>")
        val text = rest.drop(1).joinToString(" ")
        withSession(open()) { session ->
            val to = resolveContact(session, rest[0])
            session.store.send(text = text, to = to)
            session.store.lastError.value?.let { throw CliError(ExitCode.RELAY, it) }
            out.println("sent ${to.fingerprint.hex}")
        }
    }

    private fun inbox(rest: List<String>) {
        val watch = "--watch" in rest
        val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss")
        withSession(open()) { session ->
            val printed = HashSet<String>()
            // Everything already read in an earlier run is history, not new mail.
            session.store.conversations.value.values.forEach { c -> c.messages.filter { it.isRead }.forEach { printed.add(it.id) } }
            do {
                val fresh = session.store.conversations.value.values
                    .flatMap { c -> c.messages.map { c to it } }
                    .filter { (_, m) -> m.direction == MessageDirection.INCOMING && printed.add(m.id) }
                    .sortedBy { (_, m) -> m.sentAt }
                for ((conversation, m) in fresh) {
                    val who = session.contacts.contact(m.peer)?.displayName ?: conversation.peerName ?: shortFpr(m.peer.hex)
                    val files = if (m.attachments.isEmpty()) "" else " [${m.attachments.size} file(s)]"
                    out.println("${stamp.format(Date(m.sentAt * 1000))} $who: ${m.text ?: ""}$files")
                }
                for (threadID in fresh.map { it.first.threadID }.toSet()) session.store.markRead(threadID)
                out.flush()
                if (watch) { delay(5000); session.store.refresh() }
            } while (watch)
        }
    }
}
