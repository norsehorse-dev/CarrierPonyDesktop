# Phase D7: groups and broadcast channels

2026-09-18. All group logic is the vendored `ChatStore`; desktop adds tests and the window.

## Upstream fix found by the tests (CarrierPonyAndroid, uncommitted)

A group's creator did not register inbound sealed windows for the new group until the next
30-second window refresh. A member who replied inside that window sent to an unregistered
mailbox, which the relay accepts and drops (`sealed_send.php` answers ok for an unknown
mailbox). The same applied to any local roster change: add, remove, and an admin accepting a
channel subscriber. `ChatStore` now sets `lastSealedWindowRefresh = 0` after each of those, so
the next refresh registers the windows first. Members already got this on receiving a key
(line "if (existing == null || payload.epoch > existing.epoch)"); the local side did not. The
phones have the same gap until this is built and shipped. Pre-edit copy:
`_to_delete/ChatStore.kt.before-D7`.

Noticed, not fixed: a group message's silent self-copy goes to the sender's OWN group address,
but a device never registers a window for itself as sender, so the self-copy is always dropped.
Multi-device group sync is already covered by the multi-device design doc.

## Desktop

- `GroupsTest` (3 tests): three stacks form a group, all directions deliver, unread counts,
  a removed member stops receiving after the rekey, rename propagates; a channel is created,
  joined by CPCHAN1 invite by someone who never paired with the admin, only the admin posts,
  unsubscribe drops the reader from the admin's roster; leaving deletes locally and the others
  carry on.
- `Gui.kt`: contacts and groups in one list sorted by latest activity, with unread counts and a
  "Group, N members" or "Channel, N subscribed" line. New group and New channel dialogs (pick
  contacts, name), Join by pasted channel invite. The group pane shows sender names, posts with
  text and attachments, and a Members dialog: copy channel invite (admin), the roster with
  Remove (admin), add members or subscribers (admin), rename (admin), and Leave or
  Unsubscribe with a confirm step. A subscriber sees "only admins post" instead of a composer.

## Verified

The 3 group tests pass outside Gradle against the patched `ChatStore`. `Gui.kt` is uncompiled
again; everything it calls is exercised by the tests.

## To check by hand

1. Create a group with the phone as a member; the phone shows it; both post.
2. Create a channel, copy the invite, paste it into the phone; a post from the desktop arrives.
3. Remove the phone from the group; a later post does not reach it.
4. On the phone, create a group with the desktop in it; the desktop lists it and can post.

## Follow-up (2026-09-18): a channel made on the desktop shows on the iPhone, its posts do not

Same root cause as the creator-side bug above, seen from the other end. The subscriber learns
the key over the fingerprint path, but registers its inbound windows for the channel only on
its next maintain pass (iOS `applyGroupKey` resets `lastSealedWindowRefresh`, then the pass
runs on the next poll). A post sent before that pass goes to an unregistered mailbox and
`sealed_send.php` answers 200 ok and drops it. iOS derives the same addresses as Android
(`RelayClient.swift groupAddress`, checked), so it is timing, not a format mismatch. The same
loss happens whenever a sender runs past a recipient's window while the recipient is offline.

Fix in two halves, both default-safe:

1. Relay, one line in `sealed_send.php`: answer `404 unknown_mailbox` for an unregistered
   address instead of `200 ok`. The design doc already says unregistered addresses are
   rejected; addresses are HMAC outputs, so the reply reveals nothing a sender could use.
2. Clients (Android done, uncommitted; iOS to mirror): `RelayTransport.send` falls back to the
   fingerprint send on a 404, and `sendGroupMessage` falls back to `deliverToPeer` for that
   member. The receiver already opens group payloads on the pair path. Until the relay change
   is deployed the fallback never triggers and nothing changes.

`GroupsTest.aPostSentBeforeAMemberRegisteredItsWindowStillArrives` covers it against the fake
relay, which already answers 404.

Until the relay change is live: wait a minute after creating a channel or group before the
first post, or post again.
