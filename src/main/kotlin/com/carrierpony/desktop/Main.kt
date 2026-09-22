// Main.kt
// CarrierPony Desktop. One binary, two faces (PGPony / RelayPony pattern): a bare launch (or
// `gui`) opens the graphical app; a CLI verb runs in-process and exits. The meta verbs are
// handled here; the messaging verbs belong to Cli, and Cli.VERBS is the single list both the
// gate below and Cli's own dispatch are checked against (CliTest), so a verb cannot be wired
// into one and forgotten in the other (the PGPony 1.0.1 card-info lesson).

package com.carrierpony.desktop

import kotlin.system.exitProcess

private val MESSAGING_VERBS = Cli.VERBS
private val CLI_VERBS =
    setOf("selftest", "version", "--version", "gui", "--hidden", "help", "--help", "-h") + MESSAGING_VERBS

/** Stable exit codes for scripts. 64, 69 and 77 are EX_USAGE, EX_UNAVAILABLE and EX_NOPERM from
 *  sysexits.h. */
object ExitCode {
    const val OK = 0
    const val FAILURE = 1
    const val USAGE = 64
    const val RELAY = 69      // the relay could not be reached, or refused the request
    const val AUTH = 77       // wrong launch passphrase
}

fun main(args: Array<String>) {
    val first = args.firstOrNull()

    when (first) {
        "selftest" -> exitProcess(SelfTest.run())
        "version", "--version" -> { println("CarrierPony Desktop ${AppVersion.VERSION}"); return }
        "help", "--help", "-h" -> { usage(); return }
        in MESSAGING_VERBS -> {
            // CLI output is scriptable and stays English whatever language the window is set to.
            I18n.pinEnglish()
            exitProcess(Cli().run(args.toList()))
        }
    }

    // Anything that is not a known verb is a usage error.
    if (first != null && first !in CLI_VERBS) {
        System.err.println("carrierpony: unknown command '$first'")
        usage()
        exitProcess(ExitCode.USAGE)
    }

    // D12: one window per data directory. A second launch raises the running window and exits.
    val instance = SingleInstance(Config.dataDir)
    if (!instance.acquire()) return

    // --hidden (from a login item) starts in the tray with no window.
    cmdGui(startHidden = "--hidden" in args, instance = instance)
}

private fun usage() {
    println(
        """
        carrierpony: private OpenPGP messenger for the desktop

        Usage: carrierpony [command]

          gui         Open the graphical app (also opens when run with no command)
          --hidden    Start in the tray without showing the window (login items use this)
          selftest    Verify the vendored crypto core runs on this JVM (keygen + round-trips)
          version     Print the version
        """.trimIndent()
    )
    println()
    println(Cli.USAGE)
}
