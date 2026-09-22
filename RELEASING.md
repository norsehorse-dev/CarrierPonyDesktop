# Releasing CarrierPony Desktop

Each release ships eight installers: a signed and notarized `.dmg` (macOS, arm64), an `.msi`
(Windows, x64), and six for Linux, a `.deb`, a portable `.tar.gz` and an `.AppImage` for each
of x86_64 and ARM64. Plus a detached `.asc` for every one of them and a signed `SHA256SUMS`
covering the lot. Nine files to sign, seventeen files on the release.

`jpackage` only builds for the OS it runs on, so the work is split:

| Where | What |
| --- | --- |
| CI, on a tag push | the six Linux artifacts and the `.msi`, into a draft release |
| Your Mac | the `.dmg`, notarization, every PGP signature, publishing the draft |

This repository holds no secrets. The Developer ID certificate never reaches a hosted runner,
and neither does the PGP release key.

## 1. Before tagging

Bump both version numbers; `VersionDriftTest` fails the build if they disagree:

- `AppVersion.VERSION` in `src/main/kotlin/com/carrierpony/desktop/Config.kt`
- `packageVersion` in `build.gradle.kts`

Regenerate the icons only if the master artwork changed:

```sh
python3 tools/make-icons.py ../CarrierPony/CarrierPony/Assets.xcassets/AppIcon.appiconset/AppIcon-1024.png
```

Run the suite, then make sure everything is committed and pushed. A tag on a tree with
uncommitted work produces a release that does not contain it.

```sh
./gradlew test
git status --short
git push
```

## 2. Tag: CI builds the Linux artifacts and the msi

```sh
git tag v1.0.0
git push origin v1.0.0
sleep 15
gh run watch $(gh run list --workflow=release.yml --limit 1 --json databaseId --jq '.[0].databaseId')
```

The `sleep` matters: `gh run list` fires before the new run registers and hands you the
previous run's id.

Three jobs: `linux (x86_64)`, `linux (aarch64)` and `windows`. The two Linux legs are one matrix
job on purpose; a lane maintained as a copy drifts. CI also runs the CLI out of every artifact
(`version` and `selftest`) and installs the msi on the runner, so a launcher that cannot print
or a runtime that cannot run the crypto core fails the build instead of shipping.

This opens a draft release with the seven CI artifacts attached.

## 3. On the Mac: the dmg

Keep the four notarization values in a file outside the repo, `chmod 600`:

```sh
source ~/.carrierpony-release-env
./gradlew clean notarizeDmg -Pcompose.desktop.mac.notarization.teamID="$NOTARIZATION_TEAM_ID"
xcrun stapler validate build/compose/binaries/main/dmg/CarrierPony-*.dmg
```

Expect several minutes of apparent inactivity at the notarization step; that is Apple's
service, not a hang. The same three gotchas as PGPony apply: `MACOS_SIGN_IDENTITY` is the
certificate name, not its hash; only one Developer ID Application certificate may be in the
keychain; the team ID goes through the `-P` property, the DSL has none.

The dmg container is deliberately unsigned: Compose signs the `.app`, notarizes it and staples
the ticket to the image. The check that means something is the app inside:

```sh
hdiutil attach build/compose/binaries/main/dmg/CarrierPony-*.dmg -nobrowse -mountpoint /tmp/cpdmg
spctl -a -t exec -vv /tmp/cpdmg/CarrierPony.app
hdiutil detach /tmp/cpdmg
```

That must print `accepted` and `source=Notarized Developer ID`.

## 4. Assemble, sign, publish

Pull CI's artifacts down beside the dmg, then sign everything together so `SHA256SUMS` covers
the exact bytes that ship. An array, not a string: this shell is zsh.

```sh
mkdir -p ~/carrierpony-release && cd ~/carrierpony-release
gh release download v1.0.0 --repo norsehorse-dev/CarrierPonyDesktop --dir .
cp ~/Apps/CarrierPonyDesktop/build/compose/binaries/main/dmg/CarrierPony-*.dmg CarrierPony-macOS.dmg

FILES=(
  CarrierPony-macOS.dmg
  CarrierPony-linux.deb
  CarrierPony-linux-x86_64.tar.gz
  CarrierPony-x86_64.AppImage
  CarrierPony-linux-arm64.deb
  CarrierPony-linux-aarch64.tar.gz
  CarrierPony-aarch64.AppImage
  CarrierPony-windows.msi
)

shasum -a 256 $FILES > SHA256SUMS
cat SHA256SUMS
for f in $FILES SHA256SUMS; do
  gpg -u A0CBC8F65AACE56F1C5B767753F9798E4919DE62 --armor --detach-sign "$f"
done
for f in $FILES SHA256SUMS; do
  gpg --verify "$f.asc" "$f"
done
shasum -a 256 -c SHA256SUMS
```

Nine `Good signature` lines before going further. Then:

```sh
gh release upload v1.0.0 CarrierPony-macOS.dmg *.asc SHA256SUMS --repo norsehorse-dev/CarrierPonyDesktop
gh release edit v1.0.0 --draft=false --latest --repo norsehorse-dev/CarrierPonyDesktop
```

`--latest` is load-bearing: `releases/latest/download/...` resolves to whichever release
carries that flag, not to the newest tag.

## 5. After the release: the site

1. Fill `downloads/desktop.json` from `packaging/desktop.json.example`: `version`, `released`,
   `tag`, `notes`, the download URLs, and the eight `sha256` values copied out of `SHA256SUMS`.
   The app's update check reads `current.version`; the rest is for the `/desktop` page.
2. Deploy it to `https://carrierpony.com/downloads/desktop.json` with mode 644 (files written
   through the desktop bridge land as 0600).
3. Check `/desktop` shows the new version and eight working download buttons.

## 6. Verification checklist

Artifacts:

- [ ] `gpg --verify` each `.asc`, and `SHA256SUMS.asc` against `SHA256SUMS`
- [ ] `shasum -a 256 -c SHA256SUMS` passes
- [ ] the published checksums match what `downloads/desktop.json` claims
- [ ] the portable tarball extracts and `CarrierPony/bin/CarrierPony version` prints
- [ ] the AppImage is executable and runs: `chmod +x` then `./CarrierPony-x86_64.AppImage version`
- [ ] a CLI verb prints on every OS: `carrierpony version`, and `carrierpony-cli version` on Windows

macOS:

- [ ] `xcrun stapler validate` on the dmg
- [ ] `spctl -a -t exec -vv` on the app inside the mounted dmg says `Notarized Developer ID`
- [ ] a quarantined dmg opens with no dialog and the app launches from `/Applications`
- [ ] the Dock shows the pony, not the Java cup, from a cold launch

Every OS:

- [ ] clean-machine install with no JDK present
- [ ] first run: passphrase, identity, pair with a phone, send and receive, close to tray,
      second launch raises the window instead of opening another
- [ ] Settings shows the update card off by default; turning it on and pressing Check now
      reports the version in `desktop.json`
- [ ] Gatekeeper / SmartScreen behaviour recorded for the release notes
