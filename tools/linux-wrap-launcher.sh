#!/bin/sh
# linux-wrap-launcher.sh <app-image-root>
#
# Wraps the jpackage launcher so it preloads the system libfreetype before the JVM starts, which
# fixes the blank GTK file chooser on Linux (PGPonyDesktop issue #1). Compose/Skiko loads its own
# FreeType into the process ahead of the system copy; when AWT opens a GTK file chooser, GTK
# binds its text drawing to that copy and every widget fails to draw. Preloading the system
# libfreetype.so.6 puts one FreeType ahead of everything. Replacing the runtime's bundled
# libfreetype does not fix it, so the fix has to be an LD_PRELOAD set before the JVM starts.
#
# The jpackage launcher locates its config by its own basename, so the real ELF is renamed to
# CarrierPony.bin (config renamed to match) and a shell CarrierPony takes its place. Idempotent.
set -eu

APP="${1:?usage: linux-wrap-launcher.sh <app-image-root>}"
BIN="$APP/bin/CarrierPony"
CFG="$APP/lib/app/CarrierPony.cfg"

if [ -e "$APP/bin/CarrierPony.bin" ]; then
  echo "linux-wrap-launcher: $APP already wrapped, nothing to do"
  exit 0
fi
if [ ! -f "$BIN" ] || [ ! -f "$CFG" ]; then
  echo "linux-wrap-launcher: expected launcher $BIN and config $CFG" >&2
  exit 1
fi

mv "$BIN" "$APP/bin/CarrierPony.bin"
mv "$CFG" "$APP/lib/app/CarrierPony.bin.cfg"

cat > "$BIN" <<'WRAP'
#!/bin/sh
HERE="$(dirname "$(readlink -f "$0")")"
SYS_FT=""
for LDC in ldconfig /sbin/ldconfig /usr/sbin/ldconfig; do
  SYS_FT="$("$LDC" -p 2>/dev/null | awk '/libfreetype\.so\.6/ {print $NF; exit}')"
  [ -n "$SYS_FT" ] && break
done
if [ -z "$SYS_FT" ]; then
  for d in /usr/lib/*/ /usr/lib/ /lib/*/ /lib/; do
    if [ -e "${d}libfreetype.so.6" ]; then SYS_FT="${d}libfreetype.so.6"; break; fi
  done
fi
if [ -n "$SYS_FT" ] && [ -e "$SYS_FT" ]; then
  LD_PRELOAD="${SYS_FT}${LD_PRELOAD:+:$LD_PRELOAD}"
  export LD_PRELOAD
fi
exec "$HERE/CarrierPony.bin" "$@"
WRAP
chmod +x "$BIN"
echo "linux-wrap-launcher: wrapped $BIN (real launcher -> CarrierPony.bin)"
