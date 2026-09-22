# Phase D8: accounts, lock, tray, notifications, launch at login, settings

2026-09-18. The last "daily app" phase before LAN-direct and packaging.

## What exists

- `Settings.kt`: lock after N idle minutes (15, 0 never), close to tray (on), notifications (on),
  show sender in notifications (OFF by default: a notification reads from across a room), the
  downloads folder. Plain prefs, nothing secret.
- `AppController`: switch, add (new or from backup) and remove accounts; an `unread` flow across
  conversations and groups of the active account; one notification per batch of new incoming
  messages (contentless unless the sender option is on; the first emission after a session
  starts is history and never notifies); `noteActivity()` plus `runIdleLock()`; `Stage.Ready`
  gains nothing new.
- `LaunchAtLogin.kt`: a LaunchAgent plist (macOS), an XDG autostart entry (Linux), a HKCU Run
  value through `reg` (Windows). Only offered when `jpackage.app-path` is set, so never under
  `gradlew run`. The entry starts the app with `--hidden`.
- `Main.kt`: `--hidden` starts in the tray with no window.
- `Gui.kt`: Compose `Tray` with a code-drawn icon carrying the unread badge, a tooltip and an
  Open, Lock, Quit menu; the window title carries the count too; close hides to the tray when
  the setting is on and the platform has a tray, else quits; Cmd/Ctrl+L locks; every AWT key
  and mouse event resets the idle timer (one listener in `cmdGui`, so no composable has to
  care); an Accounts dialog (switch, remove with a confirm step, new, from backup) and a
  Settings dialog (everything above plus the relay URL, which takes effect at next launch).
  The Save button on attachments now uses the downloads setting.

## Decisions not obvious from the code

- Notifications go through the Compose tray (`TrayState.sendNotification`), which on macOS
  shows a Notification Center banner and on Windows a toast. That is the least native-code
  route; if banners do not appear on some desktop, the fallback is `java.awt.TrayIcon
  .displayMessage`, which the Compose one wraps.
- Sleep and screen-lock detection are not in. The JVM has no portable signal for them; the
  idle timer covers most of the same ground, and `pmset`-style hooks are a per-OS follow-up.
- The idle timer keeps running while locked; it only acts when there is something to lock.
- The tray icon is drawn in code (a circle with a chevron and a red badge). D12 replaces it
  with the real icon.

## Verified

5 new tests pass outside Gradle: switch, add and remove accounts including the active one;
unread count and notifications with the sender option on and off and notifications disabled;
the idle lock against a fake clock; settings defaults and persistence; launch-at-login files on
all three OS shapes (Windows through a recorded `reg` call). Plus the 3 controller tests.

NOT verified: `Gui.kt` again (Compose), and everything OS-facing: the tray on macOS, close to
tray, banners, `--hidden`, the plist on a real install.

## To check by hand

1. Close the window: the app stays in the menu bar, the icon shows a badge when the phone
   sends, clicking it reopens the window.
2. Settings: set lock to 1 minute, leave the Mac alone; it locks.
3. Accounts: add a second account, switch back and forth, remove the second.
4. A notification arrives with "You have a new message."; turn on the sender option and it
   names the phone.
