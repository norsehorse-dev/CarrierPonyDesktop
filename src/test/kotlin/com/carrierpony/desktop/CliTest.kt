// CliTest.kt
// The command line end to end: two data directories, one in-process relay, real verbs. This is
// the script a person would run against the real relay, minus the network.

package com.carrierpony.desktop

import com.carrierpony.app.AppConfig
import java.io.BufferedReader
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.io.StringReader
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CliTest {

    private val cheap = Vault.Cost(memoryKiB = 256, iterations = 1, parallelism = 1)

    private class Result(val code: Int, val out: String, val err: String)

    private class User(val dir: Path, val relay: String?, val cost: Vault.Cost, val passphrase: String = "launch-passphrase") {
        val extraEnv = HashMap<String, String>()
        fun run(vararg args: String): Result {
            val out = ByteArrayOutputStream(); val err = ByteArrayOutputStream()
            val env = mapOf("CARRIERPONY_DATA_DIR" to dir.toString(), "CARRIERPONY_PASSPHRASE" to passphrase) + extraEnv
            val cli = Cli(PrintStream(out, true), PrintStream(err, true), BufferedReader(StringReader("")), { env[it] }, relay, cost)
            val code = cli.run(args.toList())
            return Result(code, out.toString().trim(), err.toString().trim())
        }
    }

    private fun user(relay: FakeRelay?) = User(Files.createTempDirectory("cp-cli"), relay?.baseURL, cheap)

    @AfterTest fun resetGlobals() { AppConfig.prefs = null }

    @Test
    fun everyVerbInTheGateIsDispatched() {
        // A verb in Cli.VERBS that dispatch() does not know would print "unknown command".
        val u = user(null)
        for (verb in Cli.VERBS) {
            val r = u.run(verb)
            assertFalse(r.err.contains("unknown command"), "verb '$verb' is listed but not dispatched")
        }
        assertEquals(ExitCode.USAGE, u.run("no-such-verb").code)
    }

    @Test
    fun nothingWorksBeforeInitAndAWrongPassphraseIsItsOwnExitCode() {
        val u = user(null)
        assertEquals(ExitCode.FAILURE, u.run("accounts").code)
        assertEquals(ExitCode.OK, u.run("init").code)
        assertEquals(ExitCode.FAILURE, u.run("init").code, "init must not replace an existing vault")
        assertEquals(ExitCode.AUTH, User(u.dir, null, cheap, "not the passphrase").run("accounts").code)
        assertEquals(ExitCode.FAILURE, User(Files.createTempDirectory("cp-cli"), null, cheap, "short").run("init").code)
    }

    @Test
    fun twoPeoplePairAndTalkFromTheCommandLine() {
        FakeRelay().use { relay ->
            val alice = user(relay); val bob = user(relay)
            for ((u, name) in listOf(alice to "Alice", bob to "Bob")) {
                assertEquals(ExitCode.OK, u.run("init").code)
                val created = u.run("create", name)
                assertEquals(ExitCode.OK, created.code, created.err)
                assertTrue(created.out.startsWith("created "))
            }

            // Alice publishes an invite without waiting; Bob accepts; Alice's next run completes it.
            val invite = alice.run("invite", "--wait", "0")
            val code = invite.out.lines().first()
            assertTrue(code.startsWith("CPPAIR1:"), invite.out + invite.err)
            val accepted = bob.run("accept", code)
            assertEquals(ExitCode.OK, accepted.code, accepted.err)
            assertTrue(accepted.out.contains("unverified Alice"), accepted.out)

            assertEquals(ExitCode.OK, alice.run("inbox").code)      // refresh sweeps the pending offer
            val contacts = alice.run("list-contacts")
            assertTrue(contacts.out.contains("Bob"), "Alice should now know Bob: ${contacts.out}")

            val sent = bob.run("send", "alice", "hello", "from", "the", "terminal")
            assertEquals(ExitCode.OK, sent.code, sent.err)
            val inbox = alice.run("inbox")
            assertTrue(inbox.out.contains("Bob: hello from the terminal"), inbox.out)
            assertEquals("", alice.run("inbox").out, "a message is only new once")

            assertEquals(ExitCode.FAILURE, bob.run("send", "nobody", "hi").code)
            assertEquals(ExitCode.USAGE, bob.run("send", "alice").code)
        }
    }

    @Test
    fun backupAndRestoreAcrossDataDirectories() {
        val source = user(null); val target = user(null)
        source.run("init"); target.run("init")
        val fpr = source.run("create", "Traveller").out.split(" ")[1]
        val file = source.dir.resolve("identity.cpbackup")
        source.extraEnv["CARRIERPONY_BACKUP_PASSPHRASE"] = "backup-passphrase"
        assertEquals(ExitCode.OK, source.run("backup", file.toString()).code)
        assertEquals(ExitCode.FAILURE, source.run("backup", file.toString()).code, "backups never overwrite")

        target.extraEnv["CARRIERPONY_BACKUP_PASSPHRASE"] = "wrong"
        assertEquals(ExitCode.FAILURE, target.run("restore", file.toString()).code)
        target.extraEnv["CARRIERPONY_BACKUP_PASSPHRASE"] = "backup-passphrase"
        assertEquals("restored $fpr", target.run("restore", file.toString()).out)
        assertTrue(target.run("accounts").out.startsWith("* $fpr"))
    }

    @Test
    fun relayMustBeHttps() {
        val u = user(null)
        assertEquals("https://api.carrierpony.com", u.run("relay").out)
        assertEquals(ExitCode.FAILURE, u.run("relay", "http://relay.example.com").code)
        assertEquals("https://relay.example.com", u.run("relay", "https://relay.example.com/").out)
        assertEquals("https://api.carrierpony.com", u.run("relay", "default").out)
    }
}
