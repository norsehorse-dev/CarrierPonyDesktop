# Phase D11: UI overhaul, two string layers, settings, legal, update check

Status: written in the sandbox, type-checked where the sandbox can (everything that is not
Compose), 16 new tests green there. The Compose files (Gui, Brand, Theme, MessagesScreen,
FilesScreen, SettingsScreen, Dialogs, AppLinks) compile for the first time on the Mac. Expect a
first round of compiler nits; the logic underneath is unchanged from D10.

## What changed

The window was rebuilt on the phone app's look, with the PGPonyDesktop frame. Old `Gui.kt`
(one file, 1200 lines, hard-coded English, Material defaults) became:

| File | Holds |
|---|---|
| `Theme.kt` | The CarrierPony palette from Android's `ui/theme/Color.kt` (gold, coral, red gradient; coral accent), a dark scheme tuned to the iPhone app (black canvas, `2C2C2E` bubbles) and a light one, `AppTheme` System / Light / Dark persisted in prefs, `CarrierPonyTheme` |
| `Brand.kt` | Gradients, `Spacing`, `Radius`, `Avatar` (gradient circle with initial), `BrandMark`, `VerifiedMark`, `UnreadBadge`, `RoundIconButton` (the phone's dark disc with a coral glyph), `BrandButton` (gradient fill), `BrandCard`, `SectionCard`, `SectionHeader`, `EmptyState`, `StatusStrip`, `BrandDialog`, `LabeledValue`, `HairlineRule`, `TabTitle`, `HelpText`, `FingerprintTail` |
| `Gui.kt` | `cmdGui`, tray (icon from `icons/carrierpony_tray.png` with the unread badge drawn on), window, the three gated screens on the mark, the main frame with a 92dp rail: Messages (unread badge), Files, Settings |
| `MessagesScreen.kt` | Inbox pane: account name with a switcher chevron, compose pencil menu (new message, pair, group, channel, join), big title, rows with avatar, verified tick, preview, time and coral unread pill. Conversation pane: avatar, name, tick, green lock and fingerprint tail, nearby marker, a menu (safety number, nickname, clear history, delete conversation, unpair). Bubbles: coral outgoing, grey incoming, time and tick under each, day separators, sender name on group bubbles, right-click delete for me / for everyone. Composer: coral plus, pill field, gradient send button. Groups and channels on the same pane |
| `FilesScreen.kt` | Port of Android `ui/FilesScreen.kt`: every attachment across conversations and groups, newest first, with thumbnails, direction arrow, from/to, size, time; actions Save to downloads, Save as, Open (menu and right-click); image preview dialog; Send a file dialog (pick contact, pick files). Files dropped on the window while this tab is open start the send flow |
| `SettingsScreen.kt` | Cards in the phone's order: Accounts (switch, remove, edit name, back up, add), Appearance (theme radios, language picker, both live), Notifications and lock, Files (downloads folder, choose, open), Local network, Relay (current, custom with Use and Reset, self-host links), Updates, About (mark, version, runtime line with Copy, website, repo, issues, feedback mailto, privacy and terms, copyright), More from NorseHorse. No Save button: every control applies as it changes |
| `Dialogs.kt` | `ConfirmDialog`, `NewMessageDialog`, `SafetyNumberDialog`, `NicknameDialog`, `MembersDialog`, `NewGroupDialog`, `JoinChannelDialog`, `AddContactDialog` (invite with QR and paste), `AddAccountDialog`, `BackupDialog` (passphrase twice, copy or save to file), `LegalDialog`, `QrCode` |
| `AppLinks.kt` | `Links`, `PonyApps` (eight apps, same order as Android's settings), `openUri` with the browser, shell opener and clipboard fallbacks, `openFolder`, `mailto`, `copyToClipboard`, `appIcon` cache, `LinkRow`, `AppLinkRow` |
| `Strings.kt` | `I18n` and `tr()` / `trQuantity()` |
| `UiFormat.kt` | Copy of Android's `UiFormat` (list time, bubble time, day label, size, short fingerprint, disappearing label) plus `tr`-backed wrappers |
| `UpdateCheck.kt` | Opt-in, once a day, `HttpURLConnection` GET of `carrierpony.com/downloads/desktop.json`, `compareVersions`, statuses as resource keys |
| `LegalContent.kt` | Privacy policy and terms, adapted from Android's `ui/LegalScreens.kt` for the desktop (see below) |

`resources/icons/`: the eight family icons at 128px (six copied from PGPonyDesktop, ScrubPony
and PassPony and VaultPony downscaled from their masters), `carrierpony_512.png` and
`carrierpony_tray.png` from the iOS 1024 master.

## The two string layers

Android's nine `strings.xml` files (en, de, es, fr, it, ja, pt, ru, zh) are vendored verbatim
under `vendor/app-strings/` by `tools/sync-vendor.sh` and never edited here. Desktop-only wording
lives in `i18n/values/strings.xml`, every key prefixed `d_`. `build.gradle.kts` copies the two
trees under `/i18n/android` and `/i18n/desktop` on the classpath (they cannot both be plain
resource dirs; the second would overwrite the first). Ownership is decided by the English file:
a key in `i18n/values/` belongs to the desktop layer in every language.

`I18n.language` is snapshot state and `tr()` reads it, so the language picker re-renders the
whole window on the next frame. The CLI pins English (`Main.kt`) so scripts see stable output.

Coverage: both layers are complete in all nine languages. The desktop layer's 82 `d_` keys were
translated in the sandbox (de, es, fr, it, ja, pt, ru, zh) using the vocabulary the vendored
Android files already settled per language (koppeln / emparejar / jumeler / associare /
ペアリング / parear / связаться / 配对 for pairing, Relay / relé / relais / relay / リレー /
retransmissor / ретранслятор / 中继 for the relay). A missing key in a locale file still falls
back to English, never to the key. Kevin's own testers (Mike for RU, fhyq for ZH) are the
review step; the wording is a first pass.

`I18nTest` scans `src/main/kotlin` for every key the UI asks for and fails if any layer lacks it;
checks each locale file parses; checks placeholder parity against English in every language and
both layers; forbids a desktop key that also exists in Android; pins the CLDR plural table; and
checks live switching. Placeholder parity passed for all nine Android files as synced.

## Settings and prefs

Theme, language and the update-check switch persist in `prefs.json` through `DesktopPrefs`, like
every other non-secret setting; `AppController.prefs` became public so `cmdGui` can attach them
before the first frame. No `java.util.prefs`.

## Legal texts

`LegalContent` is the Android text with the desktop differences stated: no push service (the
app polls the relay while it runs, and notifications are local), a local-network section, an
update-check section, "encrypted under your launch passphrase", accounts removed from Settings
instead of Reset App, and no third-party services at all. The abuse-report paragraph was left
out because the desktop has no report UI. The em dashes in the Android text became colons and
commas. Kevin should read both texts once before 1.0; they are his words to the user.

## Update check

`https://carrierpony.com/downloads/desktop.json`, shape `{ "current": { "version": "1.0.1",
"date": "2026-10-01" } }`. D12 produces it in the release process. Off by default; the About and
Updates cards say so. The privacy policy names it.

## Not done, and why

- Report a contact (Android has it; ChatStore has `submitReport`). No UI for it on desktop yet.
- Chat export (Android `ChatExport`). Not in the 1.0 scope.
- Group avatars are the gradient initial like everything else; the phone does the same.

## Build and test

```
cd ~/Apps/CarrierPonyDesktop
./gradlew test
./gradlew run
```

Then on the window: switch the language in Settings and watch the rail relabel without a
restart (the phase's exit criterion), flip the theme, open a conversation from the phone, send
a file from the Files tab, check the update card says the check has not run and the toggle is
off.
