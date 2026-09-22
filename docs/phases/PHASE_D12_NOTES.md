# Phase D12: packaging, signing, CI, release

Status: files written; the tag push is Kevin's. Nothing here can be exercised in the sandbox
beyond the single-instance tests (2, green) and the Python icon script, which ran on the Mac's
VM against the iOS master.

## What landed

| Piece | Where |
|---|---|
| Icons | `tools/make-icons.py` (PGPony's, renamed): squircle-masked PNGs for the window and tray, `packaging/carrierpony.png` for Linux, a multi-size `.ico` for Windows, a hand-built `.icns` with the macOS 824/1024 inset. Regenerated from `CarrierPony/.../AppIcon-1024.png`; `build.gradle.kts` points each platform's `iconFile` at its container |
| Windows console launcher | `packaging/carrierpony-cli.properties` (`win-console=true`), added through `packageMsi`'s `freeArgs` as `carrierpony-cli`. Never `carrierpony`: case-insensitive filesystems make that the same file as `CarrierPony.exe` |
| Single instance | `SingleInstance.kt`: exclusive lock on `dataDir/.instance.lock`, loopback port in `.instance.port`. A second launch connects, the primary raises its window, the second exits. Wired in `Main.kt` for GUI launches only; CLI verbs never touch it. `SingleInstanceTest` covers the yield and the fail-safe (lock held, nobody listening: run anyway) |
| SLF4J | `org.slf4j:slf4j-nop:1.7.36`. JmDNS logs through SLF4J and warned on every start without a binding |
| Linux | `tools/linux-wrap-launcher.sh` and `packaging/appimage/AppRun` preload the system libfreetype (PGPony issue #1, the blank GTK file chooser); `packaging/appimage/carrierpony.desktop` |
| CI | `.github/workflows/build.yml` (every push: `./gradlew build`, which runs I18nTest and the vendored core suite) and `release.yml` (tag push: deb, tarball and AppImage on x86_64 and aarch64, msi on Windows, draft release). Every artifact's CLI is run (`version`, `selftest`); the msi is installed on the runner and both launchers checked |
| Release process | `RELEASING.md`; `packaging/desktop.json.example` is the manifest the update check reads and the site page renders |

## Decisions

The `carrierpony://` URL scheme from the plan is dropped for 1.0. Neither phone app registers
one; invites are `CPPAIR1:` and `CPCHAN1:` strings pasted or scanned, and nothing produces a
link the desktop could receive. Registering a scheme with no sender is a promise the product
does not keep. If web invite links arrive later, the single-instance socket is already the
place a second launch would hand a URL to the running window.

The SHA256SUMS list is eight installers. PGPony's "nine files" count is those eight plus the
manifest itself, which is also signed.

The macOS `.dmg` is arm64 only, as on PGPony. An Intel build would need an Intel runner or a
second local build; nobody has asked.

## Open before tagging 1.0.0

- The repo is not on GitHub yet. `git init` as norsehorse-dev, first commit, create the public
  repo, push, then the tag. `README.md` should gain the download table once the first release
  exists (the `releases/latest/download/...` URLs).
- `carrierpony.com/downloads/desktop.json` and a `/desktop` page do not exist yet; the update
  check fails quietly until they do (status "could not reach").
- The release signing key: `RELEASING.md` names the key PGPony uses. Confirm it is the one
  CarrierPony should sign with.
- `~/.carrierpony-release-env` with `MACOS_SIGN_IDENTITY`, `NOTARIZATION_APPLE_ID`,
  `NOTARIZATION_PASSWORD`, `NOTARIZATION_TEAM_ID`.

## Try it

```
cd ~/Apps/CarrierPonyDesktop
./gradlew test
./gradlew run
```

With the window open, run `./gradlew run` a second time from another terminal: the first window
should come forward and the second process exit. Then `./gradlew packageDmg` and open the dmg:
the icon in Finder, on the mounted image and in the Dock should all be the pony.
