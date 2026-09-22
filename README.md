# CarrierPony Desktop

CarrierPony for **macOS, Linux, and Windows**. The desktop member of the
[CarrierPony](https://carrierpony.com) family: a private messenger and secure file transfer app
built on real OpenPGP encryption. No phone number, no email, no account.

## Not a rewrite

This app compiles the same crypto core and messaging code the CarrierPony Android app ships, vendored verbatim under
[`vendor/`](vendor/) from the CarrierPonyAndroid repository. One tested implementation means the
desktop opens what the phones send, and there is far less code that could drift. What is desktop-specific lives under [`src/`](src/): the app itself in `com.carrierpony.desktop`,
plus small replacements for the handful of Android-bound files, inventoried in
[`vendor/README.md`](vendor/README.md).

## Build & run

Requires a JDK 17 or newer.

```sh
./gradlew run                      # launch the GUI
./gradlew run --args="selftest"    # verify the crypto core runs on this JVM
./gradlew run --args="version"
./gradlew test                     # unit suite, including the vendored core's own tests
```

The same binary is both the GUI and the `carrierpony` command line: a bare launch opens the app,
a verb runs the command. `./gradlew run --args="help"` lists the verbs: `init`, `create`,
`restore`, `backup`, `invite`, `accept`, `list-contacts`, `send`, `inbox` and a few more. The
launch passphrase is read from `CARRIERPONY_PASSPHRASE` or a prompt, never from an argument.

## Install

Installers for every release are on the
[releases page](https://github.com/norsehorse-dev/CarrierPonyDesktop/releases): a notarized
`.dmg` for macOS (Apple silicon), an `.msi` for Windows, and a `.deb`, a portable `.tar.gz` and
an `.AppImage` for Linux on x86_64 and ARM64. Every file has a detached PGP signature and is
listed in a signed `SHA256SUMS`. Nothing needs a JDK; the runtime is bundled.

On Windows the command line is `carrierpony-cli`; on macOS and Linux the app binary is also the
command.

## Status

**1.0.0 in preparation.** The window is the phone app's look on a desktop frame: Messages,
Files and Settings on a rail; pairing by invite or QR; safety numbers; groups and broadcast
channels; file attachments with drag, drop and paste; several accounts; direct delivery on the
local network; a tray with an unread badge, notifications and an idle lock; nine languages,
switchable without a restart. Packaging and CI are in place; the first tagged release is next.
The plan of record is [`PLANNING_DESKTOP_1_0_0.md`](PLANNING_DESKTOP_1_0_0.md), and phase
records are in [`docs/phases/`](docs/phases/). Releases follow [`RELEASING.md`](RELEASING.md).

## License

Licensed under the Apache License, Version 2.0. See [LICENSE](LICENSE) and [NOTICE](NOTICE).

Copyright 2026 Kevin Stewart.
