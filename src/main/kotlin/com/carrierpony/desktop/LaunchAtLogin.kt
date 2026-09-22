// LaunchAtLogin.kt
// CarrierPony Desktop. Start the app when the user logs in, the way each OS expects:
// a LaunchAgent plist on macOS, an XDG autostart entry on Linux, a HKCU Run value on Windows.
// The entry launches the installed app, so it is only offered when running from a packaged
// install (jpackage sets `jpackage.app-path`); under `gradlew run` there is nothing to point at.

package com.carrierpony.desktop

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

class LaunchAtLogin(
    private val home: Path = Paths.get(System.getProperty("user.home")),
    private val os: String = System.getProperty("os.name").lowercase(),
    /** The executable to launch. Null when not running from an install. */
    private val appPath: String? = System.getProperty("jpackage.app-path"),
    private val runRegistry: (List<String>) -> Int = { args ->
        runCatching { ProcessBuilder(listOf("reg") + args).redirectErrorStream(true).start().waitFor() }.getOrDefault(1)
    },
) {
    val isSupported: Boolean get() = appPath != null && (os.contains("mac") || os.contains("win") || os.contains("nux"))

    private val plist: Path get() = home.resolve("Library/LaunchAgents/com.carrierpony.desktop.plist")
    private val xdg: Path get() = (System.getenv("XDG_CONFIG_HOME")?.takeIf { it.isNotBlank() }?.let { Paths.get(it) } ?: home.resolve(".config")).resolve("autostart/carrierpony.desktop")

    fun isEnabled(): Boolean = when {
        os.contains("mac") -> Files.isRegularFile(plist)
        os.contains("win") -> runRegistry(listOf("query", RUN_KEY, "/v", "CarrierPony")) == 0
        else -> Files.isRegularFile(xdg)
    }

    /** Returns false when it could not be done (unsupported, or the OS refused). */
    fun setEnabled(on: Boolean): Boolean {
        val exe = appPath ?: return false
        return try {
            when {
                os.contains("mac") -> {
                    if (on) {
                        Files.createDirectories(plist.parent)
                        AtomicFiles.write(plist, macPlist(exe).toByteArray(Charsets.UTF_8))
                    } else Files.deleteIfExists(plist)
                    true
                }
                os.contains("win") -> {
                    if (on) runRegistry(listOf("add", RUN_KEY, "/v", "CarrierPony", "/t", "REG_SZ", "/d", "\"$exe\" --hidden", "/f")) == 0
                    else runRegistry(listOf("delete", RUN_KEY, "/v", "CarrierPony", "/f")) == 0
                }
                else -> {
                    if (on) {
                        Files.createDirectories(xdg.parent)
                        AtomicFiles.write(xdg, linuxDesktop(exe).toByteArray(Charsets.UTF_8))
                    } else Files.deleteIfExists(xdg)
                    true
                }
            }
        } catch (e: Exception) {
            false
        }
    }

    companion object {
        const val RUN_KEY = "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Run"

        fun macPlist(exe: String): String = """
            |<?xml version="1.0" encoding="UTF-8"?>
            |<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
            |<plist version="1.0">
            |<dict>
            |    <key>Label</key><string>com.carrierpony.desktop</string>
            |    <key>ProgramArguments</key>
            |    <array><string>${escapeXml(exe)}</string><string>--hidden</string></array>
            |    <key>RunAtLoad</key><true/>
            |</dict>
            |</plist>
            |""".trimMargin()

        fun linuxDesktop(exe: String): String = """
            |[Desktop Entry]
            |Type=Application
            |Name=CarrierPony
            |Exec="$exe" --hidden
            |X-GNOME-Autostart-enabled=true
            |""".trimMargin()

        private fun escapeXml(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
    }
}
