# Phases D0, D2a and D3 (headless): upstream edits, key stores, the messaging stack

2026-09-17, same session as D1. None of this has been through Gradle yet. See "Verified" below
for what was actually run.

## D0 shrank, on evidence

The plan said: add `SecretStore`, `Notifier` and `PeerDiscovery` interfaces to CarrierPonyAndroid.
Before writing them, the nine portable app packages were compiled on a plain JVM to see what
really fails. 208 errors, and nearly all of them cascade from four things:

1. `ChatStore` and `NostrTransport` call `android.util.Log.d` fully qualified.
2. `ChatStore` and `GatewayClient` read `AppConfig` and `DemoMode`, which live in the
   Context-bound `AppSupport.kt`.
3. `SealedPairState`, `SealedGroupState` and `SealedRoute` were declared inside
   `SealedKeyStore.kt`, next to the AndroidKeyStore code.
4. `Transport.kt` names `LanDiscovery` and `WanDirectBridge` directly.

Only the third needs an upstream change. The first two are a stand-in object each, the fourth a
placeholder class each. A shared `SecretStore` interface would have meant rerouting four shipped
stores, each with its own prefs file, Keystore alias and layout, with no way to build or test
the result from here. The PGPony pattern (exclude the Android file, write a desktop twin with
the same class name) gets the same outcome and touches nothing a phone depends on.

### What changed in CarrierPonyAndroid (uncommitted, in the working tree)

- NEW `app/src/main/java/com/carrierpony/app/messaging/SealedModels.kt`: the three classes,
  moved byte for byte. `SealedKeyStore.kt` lost them plus two imports it no longer uses
  (`Fingerprint`, `JSONObject`). Same package, so no caller changes.
- `messaging/ChatStore.kt`: new last constructor parameter `deviceLabel: String = "android"`,
  used in `relay.registerDevice(label = deviceLabel)`. AppModel passes nothing and keeps
  "android".
- `carrierponycore/build.gradle.kts`: Bouncy Castle 1.84 to 1.85 (both artifacts).

Pre-edit copies of the two Kotlin files are in this repo's `_to_delete/` as
`SealedKeyStore.kt.before-D0` and `ChatStore.kt.before-D0`.

## D2a: vault and key stores

- `Vault`: the launch passphrase. Argon2id (64 MiB, 3 passes, from Bouncy Castle) derives a
  256-bit key held in memory while unlocked. `vault.json` stores the parameters, the salt and
  a sealed constant, so a wrong passphrase is detected without touching any secret. `create`
  refuses to overwrite an existing vault.
- `SecureKV`: one JSON file of name to AES-256-GCM blob. The name is the associated data, so a
  blob copied under another name will not open. This is the desktop shape of "SharedPreferences
  sealed under a Keystore key", which is how all four Android stores persist.
- Twins: `IdentityStore`, `PassphraseVault`, `GroupKeyStore`, `SealedKeyStore`, each taking a
  `SecureKV`. Inventory and the two deliberate differences are in `vendor/README.md`.
- `AtomicFiles`: temp file plus atomic move, 0600.
- `DesktopPrefs` and `DesktopAppSupport.kt`: non-secret settings, and the `AppConfig` /
  `DemoMode` objects the vendored code refers to by name. The constants are copies. A test pins
  them; check the file on every vendor sync.

## D3, the headless half

`DesktopSession` is the part of `AppModel.buildStack()` a desktop needs: crypto engine,
RelayClient, ContactStore, ChatStore, wired to the twins, registering as label "desktop". It
also points the process-wide `AttachmentStore` at the data directory, because upstream defaults
it to java.io.tmpdir.

`FakeRelay` (test only, JDK `com.sun.net.httpserver`) models relay ROUTING: fingerprint sends
fan out to every registered device, each device acks its own copy, sealed sends go to the device
that registered the mailbox. It checks no signatures.

## Verified

Type-checked and run with kotlinc 2.2.10 outside Gradle, against stand-in Bouncy Castle jars
(bcprov 1.77, bcpg 1.78.1) and a scratch copy of `CPMessenger.kt` patched for that old bcpg.
54 tests, 0 failures:

- 33 from D1 (core suite, selftest, version drift).
- 7 `VaultTest`, 11 `DesktopStoresTest`.
- 3 `DesktopSessionTest`: two desktops converse and move to sealed sends; history and contacts
  survive a restart; a phone and a desktop on ONE identity both receive, and a message sent
  from one shows up on the other as a self-copy.

That last test is the plan's multi-device assumption holding in the client code. It assumes the
real relay queues per device, as you said it does.

NOT verified: anything through Gradle, the Android build after the D0 edits, Compose, Bouncy
Castle 1.85, and the real relay.

## Open questions for NorseHorse

1. Does the relay accept any `label` on register-device? Desktop sends "desktop".
2. Conversations and attachments are still plaintext on disk (D2b). The clean fix is an optional
   codec on `ChatStore`'s load and save upstream, identity on Android. `AttachmentStore` needs
   the same. OK to add both hooks upstream, or would you rather desktop wrap the files itself?
3. Pairing orchestration (create invite, accept, poll, the pending-invite sweep) lives in
   `AppModel` upstream, so desktop cannot vendor it. Either it is rewritten for desktop in D5 or
   it moves upstream into a portable `PairingCoordinator` that both apps use. The second is
   less drift but is a real refactor of a shipped flow.
4. `AttachmentStore` is a process-wide singleton upstream, so every account shares one
   attachments directory. Same as the phones today. Fine for desktop 1.0?
