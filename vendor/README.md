# vendor/: verbatim CarrierPonyAndroid sources

Verbatim copies from the CarrierPonyAndroid repository:

- `core/` = `carrierponycore/src/main/kotlin` (the crypto core: v4 Ed25519+Cv25519 identity
  generation, detached signatures for relay challenge auth, sign-and-encrypt and
  decrypt-and-verify for envelopes, the passphrase box, key import). Pure Kotlin/JVM over Bouncy
  Castle, no Android imports. Synced D1.
- `core-tests/` = `carrierponycore/src/test/kotlin` (the core's own suite, including
  `CPConformanceTest`, the iOS interop vector harness) and `core-test-resources/` =
  `carrierponycore/src/test/resources`. Synced D1; compiled into the desktop `test` source set.
  The `-Dcp.emitVectors` gate is forwarded to the test JVM by build.gradle.kts.

- `app/` = ten packages of `app/src/main/java/com/carrierpony/app/`: `crypto`, `envelope`,
  `relay`, `messaging`, `pairing`, `identity`, `attachments`, `net`, `nostr`, `storage`. This is the whole
  messaging stack: CPN1 envelopes, RelayClient, ChatStore, groups and channels, sealed sender,
  pairing payloads, identity backup. Synced D2. The app's top-level files (`AppModel.kt`,
  `AppSupport.kt`, `MainActivity.kt`) and its `ui/`, `lock/` and `push/` packages are Android-only
  and are not synced.

- `app-strings/` = `app/src/main/res/values*/strings.xml`, the nine locales, verbatim. Read at
  runtime as the Android string layer (`I18n.ANDROID_LAYER`); desktop-only keys live in `i18n/`.
  Synced D11 by the same script.

Last sync: 2026-09-17 (D1 core, D2 app), from the CarrierPonyAndroid working tree on top of
`main` at 4be796c (3.0.0), INCLUDING the edits made there the same day and not yet committed upstream: the D2b
`storage/AtRest.kt` hook and its six call sites, and the three D0 edits: `messaging/SealedModels.kt` split out of `SealedKeyStore.kt`, the
`deviceLabel` constructor parameter on `ChatStore`, and Bouncy Castle 1.85 in `carrierponycore`.
Re-sync: `tools/sync-vendor.sh` (delete-and-recopy, all trees together), then `./gradlew test`.
NEVER hand-edit files here. Fix upstream in CarrierPonyAndroid and re-sync.

## Excluded in build.gradle.kts

Excludes are SET-WIDE (every srcDir, including `src/`), so a desktop twin must never share an
excluded file's name. Twins keep the Android class name and public API and live in the same
package under `src/main/kotlin/com/carrierpony/app/`.

| Path | Why | Desktop twin |
|---|---|---|
| `identity/IdentityStore.kt` | AndroidKeyStore + SharedPreferences | `identity/DesktopIdentityStore.kt` (`class IdentityStore(kv: SecureKV)`; same vendored `IdentityRecord` format) |
| `identity/PassphraseVault.kt` | AndroidKeyStore + SharedPreferences | `identity/DesktopPassphraseVault.kt` |
| `messaging/SealedKeyStore.kt` | AndroidKeyStore + SharedPreferences | `messaging/DesktopSealedKeyStore.kt`. State classes come from vendored `SealedModels.kt`. Differs on purpose: `deviceId()` is always fresh random (no legacy push id to seed from), `wakeToken()` is always null |
| `messaging/GroupKeyStore.kt` | AndroidKeyStore + SharedPreferences | `messaging/DesktopGroupKeyStore.kt` |
| `net/LanDiscovery.kt` | Android NSD | `net/DesktopLanDiscovery.kt`: the same handshake, sweep and delivery logic (DRIFT WATCH: a copy), with discovery behind the `LanMdns` seam and `JmDnsLanMdns` over JmDNS |
| `net/WanDirectBridge.kt` | PonyDirect over WebRTC | `net/DesktopWanDirectBridge.kt`, a never-reachable placeholder (post-1.0) |

## Not vendored, replaced by name

| Upstream | Why | Desktop |
|---|---|---|
| `AppSupport.kt` (`AppConfig`, `DemoMode`) | every accessor takes a `Context` | `app/DesktopAppSupport.kt` declares both objects. Its constants are COPIES; `DesktopStoresTest.appConfigConstantsMatchAndroid` pins them. Check this file on every sync |
| `android.util.Log` | vendored files call `android.util.Log.d` fully qualified | `src/main/kotlin/android/util/Log.kt`, silent unless `CARRIERPONY_DEBUG=1` |
| `ui/Components.kt` `UiFormat`, `ui/FilesScreen.kt`, `ui/LegalScreens.kt` `LegalContent` | Android Compose | `desktop/UiFormat.kt` (copy), `desktop/FilesScreen.kt` (port), `desktop/LegalContent.kt` (adapted). DRIFT WATCH on all three |
| `AppModel.kt` | Context, push, SMS, lifecycle | `desktop/DesktopSession.kt` builds the stack for one account, `desktop/DesktopAccounts.kt` is the identity half, `desktop/DesktopPairing.kt` re-implements AppModel's pairing section. DRIFT WATCH on all three when AppModel changes |

Bouncy Castle: desktop and upstream both declare 1.85 as of the D0 edits.
