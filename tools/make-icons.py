#!/usr/bin/env python3
"""Generate the desktop icon set from the CarrierPony master artwork.

The canonical master is the iOS app icon, a 1024x1024 full-bleed square carrying the
gold-to-coral-to-red gradient with the horse mark. The Android adaptive icon and everything
here derive from that one file, so it stays the single place the artwork is edited.

    CarrierPony/CarrierPony/Assets.xcassets/AppIcon.appiconset/AppIcon-1024.png

Run it from the CarrierPonyDesktop repo root:

    python3 tools/make-icons.py ../CarrierPony/CarrierPony/Assets.xcassets/AppIcon.appiconset/AppIcon-1024.png

Outputs (all regenerated from scratch, all safe to delete and re-run):

    src/main/resources/icons/carrierpony_512.png   window, Dock and taskbar icon, rail mark
    src/main/resources/icons/carrierpony_tray.png  system tray / menu bar icon
    packaging/carrierpony.png                      jpackage, Linux
    packaging/carrierpony.ico                      jpackage, Windows (multi-size)
    packaging/carrierpony.icns                     jpackage, macOS (multi-size, Retina pairs)

src/main/resources/icons/carrierpony.png is NOT touched: that is the 128px family avatar in
the More from NorseHorse list, sized and cropped to match its neighbours.

Requires Pillow. numpy is used for the corner mask when present; a supersampled ImageDraw
path stands in when it is not.
"""

import io
import os
import struct
import sys

from PIL import Image, ImageDraw

try:
    import numpy as np
except ImportError:
    np = None

# Apple's continuous corner curve is a superellipse rather than a circular arc. Exponent 5 is
# the usual fit; 0.2237 is the corner-radius ratio Apple's own icon template uses.
SQUIRCLE_EXPONENT = 5.0
CORNER_RATIO = 0.2237

# macOS draws app icons inside a fixed grid: on a 1024 canvas the rounded square occupies
# 824px and the rest is transparent margin for the system's drop shadow. Skip it and the icon
# sits noticeably larger than every other icon in the Dock.
MACOS_CONTENT_RATIO = 824.0 / 1024.0

ICO_SIZES = (16, 24, 32, 48, 64, 128, 256)

ICNS_CHUNKS = (
    (b"icp4", 16),
    (b"icp5", 32),
    (b"ic11", 32),
    (b"ic12", 64),
    (b"ic07", 128),
    (b"ic13", 256),
    (b"ic08", 256),
    (b"ic14", 512),
    (b"ic09", 512),
    (b"ic10", 1024),
)


def squircle_mask(size):
    radius = size * CORNER_RATIO
    if np is not None:
        axis = (np.arange(size, dtype=np.float64) + 0.5)
        dx = np.maximum(radius - axis, axis - (size - radius))
        dy = dx.reshape(-1, 1)
        dx = np.maximum(dx, 0.0) / radius
        dy = np.maximum(dy, 0.0) / radius
        field = dx ** SQUIRCLE_EXPONENT + dy ** SQUIRCLE_EXPONENT
        edge = np.clip((1.0 - field) * size * 0.5 + 0.5, 0.0, 1.0)
        return Image.fromarray((edge * 255.0).round().astype("uint8"), mode="L")
    scale = 4
    big = size * scale
    mask = Image.new("L", (big, big), 0)
    ImageDraw.Draw(mask).rounded_rectangle((0, 0, big - 1, big - 1), radius=radius * scale, fill=255)
    return mask.resize((size, size), Image.LANCZOS)


def rounded(master, size):
    art = master.resize((size, size), Image.LANCZOS).convert("RGBA")
    art.putalpha(squircle_mask(size))
    return art


def macos_tile(master, size):
    content = max(1, int(round(size * MACOS_CONTENT_RATIO)))
    canvas = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    offset = (size - content) // 2
    canvas.paste(rounded(master, content), (offset, offset))
    return canvas


def write_png(image, path):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    image.save(path, format="PNG", optimize=True)
    print("  {:<44} {}x{}".format(os.path.relpath(path), image.width, image.height))


def png_bytes(image):
    buffer = io.BytesIO()
    image.save(buffer, format="PNG", optimize=True)
    return buffer.getvalue()


def write_icns(master, path):
    """Build the ICNS container by hand: Pillow only writes it on macOS via iconutil, and the
    format is a magic word, a big-endian length, then typed chunks (type, length incl. the 8
    byte header, payload)."""
    body = b""
    for chunk_type, size in ICNS_CHUNKS:
        png = png_bytes(macos_tile(master, size))
        body += chunk_type + struct.pack(">I", len(png) + 8) + png
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "wb") as handle:
        handle.write(b"icns" + struct.pack(">I", len(body) + 8) + body)
    print("  {:<44} {} chunks, {} bytes".format(os.path.relpath(path), len(ICNS_CHUNKS), len(body) + 8))


def write_ico(master, path):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    largest = rounded(master, max(ICO_SIZES))
    largest.save(path, format="ICO", sizes=[(s, s) for s in ICO_SIZES])
    print("  {:<44} {}".format(os.path.relpath(path), ", ".join("{}px".format(s) for s in ICO_SIZES)))


def main(argv):
    if len(argv) != 2:
        print(__doc__.strip(), file=sys.stderr)
        return 2
    master = Image.open(argv[1]).convert("RGBA")
    if master.width != master.height:
        print("master must be square, got {}x{}".format(master.width, master.height), file=sys.stderr)
        return 1
    if master.width < 512:
        print("master is {}px, expected the 1024px iOS source".format(master.width), file=sys.stderr)
        return 1

    root = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
    print("master  {} ({}x{})".format(argv[1], master.width, master.height))
    print("writing under {}".format(root))
    icons = os.path.join(root, "src", "main", "resources", "icons")
    packaging = os.path.join(root, "packaging")

    write_png(rounded(master, 512), os.path.join(icons, "carrierpony_512.png"))
    write_png(rounded(master, 128), os.path.join(icons, "carrierpony_tray.png"))
    write_png(rounded(master, 512), os.path.join(packaging, "carrierpony.png"))
    write_ico(master, os.path.join(packaging, "carrierpony.ico"))
    write_icns(master, os.path.join(packaging, "carrierpony.icns"))
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
