# CarrierPony Desktop 1.0.0: plan of record

Decided 2026-09-17. The long-form version with the template audit, portability map, storage
options and UI plan is the "CarrierPony Desktop Plan" doc; this file is the part a working
session needs.

## What it is

A Compose Desktop client for macOS, Linux and Windows that compiles CarrierPony Android's own
messaging stack, the way PGPony Desktop compiles PGPony Android's engine. A full client: it holds
an identity, talks to the relay directly, and interoperates byte for byte with iOS and Android 3.0.

## Decisions

| Question | Decision |
| --- | --- |
| Identity model | Both: a fresh desktop identity, or the phone identity restored from backup |
| Relay inbox | Per device already, so a same-identity desktop needs no relay change (D3 verifies) |
| Upstream changes | Kept minimal: a move-only model split, a `deviceLabel` parameter, BC 1.85. Key stores are desktop twins, not a shared interface (see D0 below) |
| Key protection | Passphrase at launch, conversation file encrypted at rest; OS keychain later |
| 1.0 scope | One-to-one, files, groups, broadcast channels, multiple accounts, LAN-direct, basic CLI |
| After 1.0 | WAN-direct (PonyDirect), Nostr transport, Tor/SOCKS for the relay connection |
| CLI | `send`, `inbox`, `list-contacts` in 1.0 on the PGPony verb dispatch |
| Platforms | All three at 1.0, same artifact set as PGPony: dmg, msi, and deb + tar.gz + AppImage on x86_64 and aarch64, plus signed SHA256SUMS |
| Bouncy Castle | 1.85; `carrierponycore` upstream bumps from 1.84 during D0 |
| Naming | Repo `CarrierPonyDesktop`, bundle id `com.carrierpony.desktop`, carrierpony.com/desktop, Apache-2.0 |

## Layout

- `vendor/core/`, `vendor/core-tests/`, `vendor/core-test-resources/`: `carrierponycore`, verbatim (D1).
- `vendor/app/`: the portable `app` packages (crypto, envelope, relay, messaging, pairing,
  identity, attachments, net, nostr), verbatim, from D2/D3 once D0 has landed.
- `vendor/app-strings/`: Android `strings.xml` set mounted as resources (D11).
- `src/main/kotlin/com/carrierpony/desktop/`: UI, Main, tray, notifications, config.
- `src/main/kotlin/com/carrierpony/app/...`: desktop twins of excluded files, inventoried in
  `vendor/README.md`.

## D0 (in CarrierPonyAndroid), revised 2026-09-17

The first plan called for `SecretStore`, `Notifier` and `PeerDiscovery` interfaces upstream.
Compiling the app packages on a plain JVM showed that was more change than the job needs, and
the wrong kind: the four key stores each have their own prefs file, Keystore alias and on-disk
layout, so rerouting them through a shared interface risks stranding identities on installed
phones. The Android stores are already the seam. Desktop excludes them and supplies twins with
the same class names. `AppModel` is not vendored at all, so `Notifier` has no caller, and
`Transport.kt` names `LanDiscovery` directly, so a desktop class of that name is enough.

What D0 became, all three default-preserving:

1. `messaging/SealedModels.kt`: `SealedPairState`, `SealedGroupState` and `SealedRoute` moved out
   of `SealedKeyStore.kt` unchanged, so desktop compiles the real state classes.
2. `ChatStore(deviceLabel: String = "android")` replaces the hard-coded relay label.
3. Bouncy Castle 1.84 to 1.85 in `carrierponycore`.

Still open upstream, to decide when their phase arrives: an at-rest codec hook on `ChatStore`'s
load and save (D2b; attachments need the same answer, since `AttachmentStore` writes plaintext
files), and pulling pairing orchestration out of `AppModel` into a portable class (D5).

## Phases

| Phase | Delivers | Verify |
| --- | --- | --- |
| D0 | The three upstream edits above | Android builds, its tests pass, a phone still sends and receives |
| D1 | This repo: skeleton, `sync-vendor.sh`, vendored core plus tests, verb dispatch with `selftest` and `version` | `./gradlew test` green, `run --args=selftest` passes |
| D2a | Launch-passphrase `Vault` (Argon2id), `SecureKV`, the four key store twins, `AppConfig` and `Log` stand-ins, vendored app packages | `./gradlew test` green |
| D2b | Encrypted conversation file and attachments, identity create, backup restore, key import, first-run and unlock flow | Restore a phone backup, fingerprint matches |
| D3 | `DesktopSession`: the headless stack for one account. In-process relay tests cover send, receive, sealed sender, restart and two devices on one identity | Same round-trips against the real relay with Android and iOS |
| D4 | Window shell: two panes, conversation list, conversation view, composer | Hold a full conversation from the UI |
| D5 | Pairing: QR display, invite links, `carrierpony://` scheme, single instance, safety numbers | Pair with both phones, both directions |
| D6 | Attachments and files: drag-and-drop, paste, save, thumbnails | Send and receive a photo and a large file |
| D7 | Groups and broadcast channels | Join an existing group and a channel, post and receive |
| D8 | Multi-account, lock, tray, notifications, launch at login | Two identities, lock on idle, unread badge |
| D9 | LAN-direct with the desktop `LanDiscovery` over mDNS | Message a phone with the relay unreachable |
| D10 | CLI verbs `send`, `inbox`, `list-contacts`; passphrase via env, fd or prompt; stable exit codes | Send from a shell script, receive on a phone |
| D11 | UI overhaul on the phone look (rail: Messages, Files, Settings), two string layers, Settings incl. More from NorseHorse, legal, update check | Language switch without restart; the window reads like the iPhone app |
| D12 | Icons, single instance, Windows console launcher, CI for all eight installers, SHA256SUMS, desktop.json, RELEASING.md, 1.0.0 | Clean-machine installs on all three OSes |

Phase notes go in `docs/phases/PHASE_D<n>_NOTES.md`.
