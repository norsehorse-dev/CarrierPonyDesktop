# Phases D2b, D5 and D10 (all headless): sealed files, accounts, pairing, the CLI

2026-09-17, after the first green `./gradlew test` on both repos. Everything below was compiled
and tested with kotlinc outside Gradle again, so the next `./gradlew test` is the real check.

## Answers folded in

1. Relay label: `register_device.php` stores any label up to 64 characters. "desktop" is fine.
2. At-rest hooks upstream: done, see below.
3. Pairing: rewritten for desktop (`DesktopPairing`), not moved upstream.
4. Copyright: LICENSE, NOTICE, README and the installer metadata say Kevin Stewart. NOTICE still
   credits the vendored code to The CarrierPony Authors, because that is what upstream says.

## IMPORTANT: same identity on phone and desktop breaks under sealed sender

The relay code confirms what you said about the fingerprint inbox: `send.php` writes one
`deliveries` row per registered device and `ack.php` acks per device. Fingerprint-routed
messages reach every device.

Sealed sender does not work that way. `sealed_register_mailboxes.php` gives each mailbox to the
FIRST sealed device that claims it (`INSERT IGNORE`, then an owner check), and `sealed_send.php`
delivers to that one owner. On the client, each device generates its own inbound key per
contact, and a peer stores ONE inbound key per contact. So when a second device on the same
identity comes up, it announces its key, the peer switches to it, and the first device stops
receiving sealed messages from that peer. I reproduced it with two stacks on one identity
against the fake relay: after sealed was established with the "phone", bringing up the
"desktop" moved the stream. First message after: phone got it, desktop did not. After the
desktop spoke once: desktop got the next one, phone did not.

This is not a desktop bug. It should already affect an iPhone and an Android phone restored from
the same backup. But it means the 1.0 decision "use your phone identity on the desktop" is not
safe to ship as things stand. `DesktopSessionTest.sealedSenderReachesBothDevicesOfOneIdentity`
states the wanted behaviour and is @Ignore'd with a pointer here.

Ways out, roughly in order of effort:

- A. Ship desktop 1.0 as its own identity only. Restore-from-backup stays for moving an identity,
  with a warning that it must then live on one device. No protocol work.
- B. Peers keep a SET of inbound keys per contact (one per device of that contact) and send a
  sealed copy to each. Client-only change on all three platforms, more sends, no relay change.
- C. Devices of one identity share the inbound key (synced over the existing self-copy channel)
  and the relay lets several sealed devices own one mailbox, fanning out like `deliveries`.
  Smallest traffic, but a relay schema change plus key sync.

I have not changed anything for this. It is your call.

## D2b

Upstream (CarrierPonyAndroid working tree, uncommitted):

- NEW `storage/AtRest.kt`: `AtRestCodec` interface and the `AtRest` object. With no codec set
  (always, on Android) it is `File.writeBytes` / `readBytes`, byte for byte what was there.
- `ChatStore`, `ContactStore`, `AttachmentStore`: their six read and write calls go through
  `AtRest`. Nothing else changed. Pre-edit copies are in `_to_delete/*.before-D2b`.

Desktop:

- `Vault.sealBytes/openBytes`: binary AES-256-GCM (IV, ciphertext, tag), so a 50 MB attachment
  costs 50 MB plus 34 bytes on disk.
- `VaultAtRestCodec`: "CPAR1\n" magic plus the sealed bytes, bound to the file name. A file
  without the magic is refused, never read as plaintext.
- `DesktopStorage`: one data directory. Hands out ONE `SecureKV` per file (two cached instances
  over one file would overwrite each other) and installs the codec.
- `DesktopAccounts`: create, restore backup, export backup, import key, list, select, remove.
  Same record and backup formats as the phones.

Caveat inherited from upstream: if a state file fails to open, `ChatStore.load()` treats it as
absent and the next save replaces it. On desktop a file only fails to open if it was tampered
with or belongs to another vault, but it is worth knowing.

Not covered by a test: importing a passphrase-protected key (needs a protected-key fixture).

## D5, the headless half

`DesktopPairing`: create invite (remote or in person), accept (text or Invite), poll, and a
throttled sweep wired into `ChatStore.refresh()` through `pairingSweep`. Pending offers persist
in prefs under the key the phones use. Same two refusals as AppModel, plus one extra: accepting
your own invite is refused. `FakeRelay` grew the three pair endpoints and a `swapKey` switch that
plays a relay in the middle.

Still to do in D5: QR rendering, the `carrierpony://` scheme, single instance, safety numbers
on screen.

## D10, pulled forward

The CLI came early because it is the only way to drive this stack against the real relay and
real phones before there is a window. Verbs: init, create, restore, backup, import-key, accounts,
use, relay, invite, accept, list-contacts, send, inbox. The launch passphrase comes from
`CARRIERPONY_PASSPHRASE` or a prompt, never from an argument. Exit codes: 0, 1, 64 usage,
69 relay, 77 wrong passphrase. `Cli.VERBS` is the one list Main's gate and Cli's dispatch are
both tested against.

## Verified

36 desktop tests pass outside Gradle (1 ignored, above), on top of the 33 from D1: 5 CLI,
5 pairing, 3 session, 5 accounts and codec, 11 stores, 7 vault.

## The manual half of D3 and D5, on your Mac

Against the real relay, with a phone. In one terminal:

    export CARRIERPONY_DATA_DIR=~/cp-desktop-test
    ./gradlew run --args="init"
    ./gradlew run --args="create Desk"
    ./gradlew run --args="invite --wait 300"

Paste the CPPAIR1 line into the phone's "Enter an invite". When it prints `paired`, send from
the phone, then:

    ./gradlew run --args="inbox"
    ./gradlew run --args="send <name-on-phone> hello from the desktop"

A throwaway data dir keeps this away from real data. `gradlew run` has no console, so the
passphrase prompt reads a plain line from stdin; set CARRIERPONY_PASSPHRASE to avoid that.

## D4: the window (added after the first real pairing and message on 2026-09-17)

The CLI paired with a phone through the real relay and received a message, so the stack is
proven against production. The window went in next.

- `AppController` (no Compose imports, 2 tests): the four stages NeedsVault, Locked,
  NeedsIdentity, Ready; passphrase rules; unlock; create or restore an identity; lock. An
  unreachable relay does not block launch, it shows up as the store's error banner.
- `Gui.kt`: first run, unlock, identity (create or paste a backup), two panes with unread
  counts and previews, conversation with Enter to send and Shift+Enter for a line, add contact
  (create an invite shown as text and QR, with an "in person" box that pairs as verified, or
  paste one), safety number with "mark verified", lock. Closing the window locks and quits.
- New dependency: `com.google.zxing:core:3.5.3`. The QR is drawn on a Canvas, always black on
  white.

`Gui.kt` is the one file in this repo that has NEVER been compiled: Compose is not reachable
from the session that wrote it. Expect a first-build error or two. Paste them and they get
fixed; everything it calls is tested.

Not in the window yet: attachments, groups, channels, multiple accounts, settings, tray,
notifications, the URL scheme.

## Same identity on several devices: decision (2026-09-17)

Chosen: peers keep an inbound key per device and send a sealed copy to each. The design is in
the iOS repo as `CarrierPony-MultiDevice-Sealed-Design.md`. Working it through showed the
problem is wider than the receive side: two devices of one SENDER also collide, because both
walk the same address stream with their own counters, and group streams have both faults. The
fix is a v2 address that names the sending device, with no relay change.

It is a protocol change across Android, iOS and desktop, so it is not in desktop 1.0's critical
path. Until it lands, the restore screen and the `restore` verb say that restoring MOVES an
identity. The ignored test `sealedSenderReachesBothDevicesOfOneIdentity` is its acceptance test.

Also fixed the same day: the window started the session on a screen's coroutine scope, which is
cancelled when the stage flips to Ready. Registration was cut off mid-request, so the relay
never learned the key and every signed call returned 401 bad_signature. `AppController` now
starts (and retries) the session on the app scope; `theSessionOutlivesTheScreenThatCreatedIt`
pins it.
