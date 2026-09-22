# Phase D1: skeleton, vendored core, selftest

2026-09-17. D1 ran ahead of D0 on purpose: the crypto core has no Android imports, so it vendors
today with no upstream change. D0 (the seams in CarrierPonyAndroid) is only needed before
`vendor/app/` arrives in D2/D3.

## What exists

- Gradle build copied from PGPonyDesktop's proven set: Kotlin 2.2.10, Compose Multiplatform
  1.11.1, wrapper 9.4.1, JDK 17 toolchain. No KSP and no Room, since CarrierPony's stores are
  JSON files. The wrapper jar, `gradlew` and `gradlew.bat` are byte copies from PGPonyDesktop. LICENSE is a
  byte copy of CarrierPonyAndroid's (Copyright 2026 The CarrierPony Authors).
- `tools/sync-vendor.sh`: delete-and-recopy of `carrierponycore` main, tests and test resources.
  Default source is `../CarrierPonyAndroid`.
- `vendor/core`, `vendor/core-tests`, `vendor/core-test-resources`: first sync from
  CarrierPonyAndroid `main` at 4be796c. `diff -r` against upstream is clean.
- `Main.kt` verb dispatch (`selftest`, `version`, `help`, `gui`), `ExitCode` (unknown verb exits
  64), `SelfTest.kt`, `Config.kt` (per-OS data dir, 0700), `Gui.kt` placeholder window.
- Tests: `VersionDriftTest`, `SelfTestTest`, plus the six vendored core test classes.
- `build.gradle.kts` sets bundle id `com.carrierpony.desktop` and a fresh Windows `upgradeUuid`
  (0dde687a-fb69-485f-87db-7e44661a1581). That UUID is permanent once a 1.0 msi ships.

## Decisions not obvious from the code

- Desktop pins Bouncy Castle 1.85 while upstream `carrierponycore` still declares 1.84. D0 bumps
  upstream. If 1.85 breaks anything in the core, fix it upstream, not here.
- `useJUnit()` is set because the vendored suite is JUnit 4 (`org.junit.Test`); the desktop
  tests use `kotlin.test` over the same runner.
- `-Dcp.emitVectors` is forwarded to the test JVM so `CPConformanceTest` can still emit
  Android-side vectors from this repo.
- SelfTest catches Throwable. A broken jlink image fails with NoClassDefFoundError, which is
  exactly the case a user runs `selftest` to diagnose.
- No icons, URL scheme, single-instance guard or Windows console launcher yet. Those belong to
  D5 and D12, and the case-collision warning for the console launcher name is in CLAUDE.md.

## What was verified, and how

No Gradle build has run. The session that wrote this cannot reach Maven Central. Instead:

- Main.kt, SelfTest.kt, Config.kt, both desktop tests and all vendored main and test sources
  were type-checked together with kotlinc 2.2.10. The only errors were two in vendored
  `CPMessenger.kt` caused by the stand-in Bouncy Castle (1.77/1.78, which lacks the two-argument
  `PGPSignatureGenerator` constructor). Those do not occur on 1.84 or 1.85.
- With a scratch copy patched for that old constructor, `selftest` passed all six steps and
  JUnit ran 33 tests, 0 failures. `version` prints, and an unknown verb exits 64.

NOT verified, so the first run on the Mac is the real test:

- `build.gradle.kts` itself (plugin resolution, source sets, the Test task block).
- `Gui.kt`. It was never compiled, because Compose was not available. It is 30 lines of
  standard Compose Desktop.
- The core on Bouncy Castle 1.85.

## Open questions for NorseHorse

1. NOTICE and README say "Copyright 2026 The CarrierPony Authors" to match the Android repo.
   build.gradle.kts `vendor` stays "NorseHorse", as on PGPony.
2. D0 edits a shipped Android app. It is drafted next, but should be reviewed and built before
   it goes anywhere near a release.
