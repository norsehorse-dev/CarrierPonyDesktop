// Config.kt
// CarrierPony Desktop: per-OS app paths + version constants (PGPony / RelayPony Config pattern).

package com.carrierpony.desktop

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.attribute.PosixFilePermissions

object AppVersion {
    /**
     * Desktop version line: independent of the Android and iOS bands.
     *
     * Must stay in step with `packageVersion` in build.gradle.kts: that one becomes the
     * installer's version and, on macOS, CFBundleShortVersionString. VersionDriftTest enforces
     * the agreement.
     */
    const val VERSION = "1.0.0"
}

object Config {

    /**
     * Per-OS application data directory, created on first use and restricted to the user
     * (0700) where the filesystem supports POSIX permissions:
     *   macOS   ~/Library/Application Support/CarrierPony
     *   Linux   $XDG_DATA_HOME/carrierpony  (fallback ~/.local/share/carrierpony)
     *   Windows %APPDATA%\CarrierPony
     */
    val dataDir: Path by lazy {
        val home = System.getProperty("user.home")
        val os = System.getProperty("os.name").lowercase()
        val dir: Path = when {
            os.contains("mac") -> Paths.get(home, "Library", "Application Support", "CarrierPony")
            os.contains("win") -> {
                val appData = System.getenv("APPDATA")
                if (appData.isNullOrBlank()) Paths.get(home, "AppData", "Roaming", "CarrierPony")
                else Paths.get(appData, "CarrierPony")
            }
            else -> {
                val xdg = System.getenv("XDG_DATA_HOME")
                if (xdg.isNullOrBlank()) Paths.get(home, ".local", "share", "carrierpony")
                else Paths.get(xdg, "carrierpony")
            }
        }
        Files.createDirectories(dir)
        // Conversations and key material live under here. Windows has no POSIX view; its
        // per-user %APPDATA% ACL already does this job.
        runCatching {
            Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString("rwx------"))
        }
        dir
    }
}
