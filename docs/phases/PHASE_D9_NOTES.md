# Phase D9: LAN-direct

2026-09-18. A desktop and a phone on the same network deliver straight to each other.

## What exists

- `net/LanMdns.kt`: the seam. Advertise this node, browse for others. Nothing else in the LAN
  code knows what mDNS is.
- `net/JmDnsLanMdns.kt`: the seam over JmDNS 3.5.9, service type `_carrierpony._tcp.local.`,
  bound to the site-local IPv4 of the first up, non-loopback interface. NOT compiled here (no
  JmDNS jar reachable); it is 60 lines against a stable API.
- `net/DesktopLanDiscovery.kt`: replaces the placeholder. Same public surface as Android's
  `LanDiscovery` (`nearby`, `reachable`, `keyProvider`, `onEnvelope`, `start`, `stop`,
  `lanDidToggle`, `canReachLan`, `deliver`). The handshake, identify sweep and delivery are a
  copy of Android's; the vendored `LanCrypto` gives the byte-exact wire protocol, so a desktop
  identifies to a phone with the same per-pair key math.
- `DesktopAppSupport`: `lanDirectEnabled` and `lanDirectSkipRelay` settings, both off.
- `DesktopSession`: wires `keyProvider` and `onEnvelope` as AppModel does, starts and stops
  discovery with the session, passes `lanSkipRelay` to ChatStore, and exposes `lanDidToggle()`.
- `Gui.kt`: two settings toggles with a firewall note and the live nearby count, and "On your
  local network" in a conversation header while the contact is identified.

## Decisions not obvious from the code

- Discovery (advertise and browse) runs whenever a session runs, so the nearby count is honest
  before the user turns delivery on. Identify and delivery need the setting, as on Android.
  A listening socket is opened either way; if the firewall prompt at first launch is a problem,
  move `startListening()` behind the setting.
- JmDNS binds one interface. A Mac with Wi-Fi and Ethernet on different networks gets the first
  site-local address. A picker, or JmmDNS for all interfaces, is a follow-up.
- Noticed, not fixed: a device announces its sealed inbound key once at `start()`. If the peer
  is not registered with the relay yet at that moment (a brand-new pairing where both sides
  come up together), the announcement is lost and the pair only completes on the next 30-second
  window refresh. Harmless for humans, visible in tests (`resyncSealedKeys()` works around it).

## Verified

`LanDirectTest`, 2 tests, real TCP on 127.0.0.1 with mDNS replaced by an in-memory bus: both
nodes see each other; nothing is identified until the setting is on; after the sealed key
exchange the identify handshake succeeds; a message goes over the wire with no relay send in
skip mode and with both paths (deduplicated) otherwise; turning the setting off drops
reachability; a node leaving is noticed. A stranger with no matching pair key is never
identified.

NOT verified: JmDNS itself, the firewall prompt, and a real phone on the same Wi-Fi.

## To check by hand

1. `./gradlew run`, Settings, turn on local delivery. macOS asks about incoming connections;
   allow. The nearby count should show the phone (Android or iOS with the app open).
2. Open a paired contact's conversation on the phone and on the desktop: "On your local network"
   appears within a few seconds of the sweep (it needs the sealed keys, so exchange one message
   over the relay first if the pair is new).
3. Turn off Wi-Fi's uplink (or block the relay) and send both ways with "skip the relay" on.

## Follow-up (2026-09-18): the desktop was talking to itself

The first real run showed why LAN delivery only sometimes reached the phone. JmmDNS hears
our own announcement back on every interface, and renames a service it sees more than once:
`<id>`, `<id> (2)`, `<id> (3)`. The name check `name == nodeID` missed the renamed ones, so
the desktop dialled its own port, matched its own pair keys (it holds both halves), and
mapped its own node to a contact. `deliver` then picked whichever node was mapped first,
sometimes itself. The phone (`a67d...` at 172.16.1.10) was found and matched correctly; the
race decided who got the envelope.

Fix: `isSelf()` ignores our id and any "<id> (n)" variant, and any address that belongs to
this machine on our own listening port. Test: `ourOwnAnnouncementIsNeverTreatedAsAPeer`.
Also: one endpoint per node, preferring IPv4 over a link-local IPv6 (which needs a scope id
to dial). And `JmDnsLanMdns` moved from JmDNS to JmmDNS (all interfaces).

Worth doing upstream too: Android NSD and iOS NWBrowser can rename on conflict the same way.
Android's `onServiceFound` already skips `registeredName` (the name after any rename); iOS
should be checked.

## Follow-up: test run stalled at CliTest

`./gradlew test` hung after 48 tests because every session built by the CLI
or by a test constructed a real `LanDiscovery`, which opened JmmDNS on all
interfaces and blocked in `start()`/`stop()`. Fixes:

- `LanMdns.None`: a no-op implementation of the seam. `Cli` builds sessions
  with `LanDiscovery(LanMdns.None) { false }`, and every test that builds a
  session passes `LanMdns.None`. Real mDNS now runs only in the window.
- `AppController` takes `lanMdns: () -> LanMdns` (default `JmDnsLanMdns()`),
  so `AppControllerTest` injects the no-op.
- `DesktopAccounts.session(...)` takes a `lanDiscovery` parameter.
- `LanDiscovery.start()`/`stop()` no longer block the caller: browse and
  advertise run on the discovery scope, and `mdns.close()` runs on a daemon
  thread.
