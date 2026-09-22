import org.gradle.api.provider.ListProperty
import org.jetbrains.compose.desktop.application.dsl.TargetFormat

// Plugin set + versions: the PGPonyDesktop / RelayPonyDesktop-proven combination (Kotlin 2.2.10,
// Compose Multiplatform 1.11.1, Gradle wrapper 9.4.1). No KSP and no Room: CarrierPony's stores
// are JSON files, not a database.
plugins {
    kotlin("jvm") version "2.2.10"
    kotlin("plugin.compose") version "2.2.10"          // Compose compiler (matches Kotlin)
    id("org.jetbrains.compose") version "1.11.1"       // Compose Multiplatform + native packaging
}

kotlin {
    jvmToolchain(17)
}

// --- macOS signing + notarization, opt-in via environment (nothing secret is committed) ---
// Same contract as PGPonyDesktop: set MACOS_SIGN_IDENTITY plus the NOTARIZATION_* vars, then
// `./gradlew notarizeDmg -Pcompose.desktop.mac.notarization.teamID=$NOTARIZATION_TEAM_ID`
// builds a signed, stapled dmg. With them unset, `packageDmg` builds an unsigned dmg.
val macSignIdentity: String? = System.getenv("MACOS_SIGN_IDENTITY")
val notaryAppleId = providers.environmentVariable("NOTARIZATION_APPLE_ID")
val notaryPassword = providers.environmentVariable("NOTARIZATION_PASSWORD")

// CarrierPony Desktop is not a rewrite: it compiles the exact crypto core, envelope format and
// relay client the Android app ships. Sources are vendored VERBATIM under vendor/ (sync:
// tools/sync-vendor.sh). Only com.carrierpony.desktop under src/ is desktop-specific, plus the
// twins inventoried in vendor/README.md. Excludes apply SET-WIDE (all srcDirs), so a desktop twin
// must never share an excluded file's name.
//
sourceSets {
    main {
        kotlin {
            srcDir("vendor/core")                           // D1: carrierponycore, verbatim
            srcDir("vendor/app")                            // D2: the portable app packages, verbatim
            // Android-coupled files inside the synced packages. Each has a desktop twin under
            // src/main/kotlin/com/carrierpony/app/ with a DIFFERENT file name (excludes are
            // set-wide), inventoried in vendor/README.md.
            exclude("**/identity/IdentityStore.kt")         // AndroidKeyStore + SharedPreferences
            exclude("**/identity/PassphraseVault.kt")       // AndroidKeyStore + SharedPreferences
            exclude("**/messaging/SealedKeyStore.kt")       // AndroidKeyStore + SharedPreferences
            exclude("**/messaging/GroupKeyStore.kt")        // AndroidKeyStore + SharedPreferences
            exclude("**/net/LanDiscovery.kt")               // Android NSD; desktop mDNS lands in D9
            exclude("**/net/WanDirectBridge.kt")            // PonyDirect WebRTC; post-1.0
        }
    }
    test {
        kotlin {
            srcDir("vendor/core-tests")                     // D1: the core's own suite, verbatim
        }
        resources.srcDir("vendor/core-test-resources")
    }
}

dependencies {
    implementation(compose.desktop.currentOs)                          // Compose runtime + Skiko for this OS
    implementation(compose.material3)
    implementation(compose.materialIconsExtended)                      // rail, bubbles and settings glyphs
    // Bouncy Castle 1.85: the version every Pony JVM app is converging on. carrierponycore
    // upstream moves from 1.84 to 1.85 in D0; the vendored source is version-agnostic between
    // the two, so desktop pins the target now.
    implementation("org.bouncycastle:bcprov-jdk18on:1.85")
    implementation("org.bouncycastle:bcpg-jdk18on:1.85")
    // The vendored app layer: coroutines (ChatStore, RelayClient, transports) and org.json, which
    // is bundled on Android and a dependency here. HTTP is java.net.HttpURLConnection, so there is
    // no client library to match. -swing provides Dispatchers.Main for Compose Desktop.
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.10.2")
    implementation("org.json:json:20240303")
    // QR for pairing invites. `core` alone: the matrix is drawn on a Compose Canvas, so the
    // javase bridge is not needed. Same ZXing version as the Android app.
    implementation("com.google.zxing:core:3.5.3")
    // LAN-direct discovery: mDNS / DNS-SD in pure Java, the desktop stand-in for Android NSD.
    implementation("org.jmdns:jmdns:3.5.9")
    // JmDNS logs through SLF4J. With no binding on the classpath SLF4J prints a three-line
    // "no providers" warning on the first log call, in the terminal and in the packaged app's
    // stderr. The NOP binding is the explicit answer: nothing is logged, and nothing warns.
    implementation("org.slf4j:slf4j-nop:1.7.36")

    testImplementation(kotlin("test-junit"))
    testImplementation("junit:junit:4.13.2")
}

// One binary, two faces (PGPony / RelayPony pattern): no args opens the GUI, a verb runs the CLI.
// Native installers: `./gradlew packageDmg` / `notarizeDmg` (macOS), `packageDeb` (Linux),
// `packageMsi` (Windows, WiX 3.x required). jpackage builds only for the OS it runs on.
// D12: the three installer icons come from tools/make-icons.py (one 1024px master, three
// containers because jpackage wants a different one per platform), and packageMsi adds the
// console launcher carrierpony-cli below.
compose.desktop {
    application {
        mainClass = "com.carrierpony.desktop.MainKt"
        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Deb, TargetFormat.Msi)
            packageName = "CarrierPony"
            packageVersion = "1.0.0"
            description = "Private OpenPGP messenger and file transfer for the desktop"
            vendor = "NorseHorse"
            copyright = "Copyright 2026 Kevin Stewart"
            macOS {
                bundleID = "com.carrierpony.desktop"
                appCategory = "public.app-category.social-networking"
                minimumSystemVersion = "11.0"
                iconFile.set(project.file("packaging/carrierpony.icns"))
                if (!macSignIdentity.isNullOrBlank()) {
                    signing {
                        sign.set(true)
                        identity.set(macSignIdentity)
                    }
                    notarization {
                        appleID.set(notaryAppleId)
                        password.set(notaryPassword)
                    }
                }
            }
            linux {
                packageName = "carrierpony"                            // lowercase for the .deb package id
                iconFile.set(project.file("packaging/carrierpony.png"))
            }
            windows {
                iconFile.set(project.file("packaging/carrierpony.ico"))
                menu = true
                menuGroup = "CarrierPony"
                shortcut = true
                dirChooser = true
                // Fixed identity so each new .msi upgrades the previous install in place.
                upgradeUuid = "0dde687a-fb69-485f-87db-7e44661a1581"
            }
        }
    }
}

// D11: the two string layers. They cannot both be plain resource srcDirs (the second would
// overwrite the first on the classpath), so each is copied under its own prefix, matching
// I18n.ANDROID_LAYER and I18n.DESKTOP_LAYER in Strings.kt:
//   vendor/app-strings/values-de/strings.xml  ->  /i18n/android/values-de/strings.xml
//   i18n/values-de/strings.xml                ->  /i18n/desktop/values-de/strings.xml
// vendor/app-strings/ is verbatim Android (tools/sync-vendor.sh refreshes it). i18n/ holds only
// keys the desktop app has and Android does not.
tasks.named<Copy>("processResources") {
    from("vendor/app-strings") { into("i18n/android") }
    from("i18n") { into("i18n/desktop") }
}

// D12: the Windows CLI needs a console. jpackage builds a GUI-subsystem executable, and a
// GUI-subsystem process on Windows has no stdout, so every `carrierpony <verb>` would run and
// silently discard its output (PGPony 1.0.0 shipped that). Compose's windows { console = true }
// is the wrong fix: it makes the GUI app console-subsystem too, so a black window flashes up
// behind it. jpackage's --add-launcher builds a SECOND executable from its own properties file,
// so carrierpony-cli.exe gets win-console=true while CarrierPony.exe stays windowless. Scoped
// to packageMsi; macOS and Linux already have a console.
//
// The launcher is carrierpony-cli, never carrierpony: Windows filesystems are case-insensitive,
// so a launcher called carrierpony resolves to the same file as CarrierPony.exe and jpackage
// dies with FileAlreadyExistsException. Reached through the task's dynamic freeArgs property
// rather than the plugin's internal task type; if freeArgs ever disappears this degrades to a
// no-op, which is why release.yml asserts that carrierpony-cli.exe exists and prints.
tasks.matching { it.name == "packageMsi" }.configureEach {
    val free = runCatching { property("freeArgs") }.getOrNull()
    if (free is ListProperty<*>) {
        @Suppress("UNCHECKED_CAST")
        (free as ListProperty<String>).addAll(
            "--add-launcher",
            "carrierpony-cli=" + project.file("packaging/carrierpony-cli.properties").absolutePath.replace('\\', '/')
        )
    }
}

// Keep the CLI's interactive stdin on `./gradlew run` (defensive: no-op if run isn't a JavaExec).
tasks.matching { it.name == "run" }.configureEach {
    (this as? JavaExec)?.standardInput = System.`in`
}

// Forward selected -D properties to the forked unit-test JVM (Gradle does not propagate
// command-line system properties to test JVMs). Gated harnesses:
//   -Dcp.emitVectors=true   CPConformanceTest writes Android-side vectors to build/conformance-out
tasks.withType<Test>().configureEach {
    useJUnit()
    testLogging { events("passed", "skipped", "failed") }
    listOf("cp.emitVectors").forEach { k ->
        System.getProperty(k)?.let { systemProperty(k, it) }
    }
}
