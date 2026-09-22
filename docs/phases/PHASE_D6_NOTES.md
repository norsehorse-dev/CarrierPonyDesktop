# Phase D6: attachments

2026-09-17. The window had paired, sent and received against the production relay before this
went in.

## What exists

- `Attachments.kt` (no Compose imports): files to outgoing attachments, pasted image to PNG, the
  50 MB per-message ceiling checked from file sizes BEFORE anything is read, a mime table that
  wins over `Files.probeContentType` (the phones decide "is this an image" from the mime string,
  so common types must not depend on which OS sent them), never-overwrite saving
  ("name (1).ext"), thumbnails that never upscale, and a temporary plaintext copy for "Open".
- `Gui.kt`: an Attach button (native file picker, multiple files), drag and drop anywhere on the
  window, Cmd/Ctrl+V of files or an image, a queue above the composer with Remove, a message
  can now be files with no text, and received attachments show a thumbnail plus Save and Open.

## Decisions not obvious from the code

- Drag and drop is an AWT `DropTarget` on the window's content pane, not a Compose modifier.
  The Compose desktop drag-and-drop API has changed across releases and could not be compiled
  where this was written; the AWT one is stable. If drops do not arrive on some platform,
  try `window.dropTarget` instead of `window.contentPane.dropTarget`.
- Received files are sealed on disk, so the window never hands out the stored file. Save writes
  the opened bytes to Downloads. Open writes a plaintext copy to the temp directory and removes
  it when the app exits. That is the one place a received file leaves the sealed store without
  the user choosing where; it is a separate button for that reason.
- HEIC and WebP have no decoder in the JDK, so photos sent by an iPhone as HEIC show no
  thumbnail, only the name with Save and Open. Worth checking what the iOS app actually sends.
- Attachments are whole byte arrays in memory, as upstream `OutgoingMessage.Attachment` is. At
  the 50 MB ceiling that is fine; it is also why the ceiling is checked before reading.

## Verified

7 new tests pass outside Gradle, including a full trip: a PNG and a text file sent from one
desktop stack arrive on another, are sealed on disk there ("CPAR1" magic, no plaintext),
save back out byte for byte without overwriting, and thumbnail correctly.

NOT verified: every line of the `Gui.kt` changes (Compose cannot be compiled here), drag and
drop on a real window, clipboard image paste, the native file picker, `Desktop.open`.

## To check by hand

1. Attach a photo and a PDF with the button; send; they arrive on the phone.
2. Drop a file on the window with a conversation open; it queues.
3. Copy an image (a screenshot to clipboard), Cmd+V in the composer; it queues as pasted-*.png.
4. Send a photo from the phone; a thumbnail shows; Save puts it in Downloads; Save again makes
   "name (1)"; Open launches Preview.
5. Try a file over 50 MB; it is refused with a message and nothing is queued.
