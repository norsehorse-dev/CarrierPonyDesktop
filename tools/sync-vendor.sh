#!/usr/bin/env bash
# sync-vendor.sh: refresh vendor/ from the CarrierPonyAndroid working tree.
# Delete-and-recopy so removals upstream propagate. Run the test suite afterward.
#
# Synced trees:
#   carrierponycore/src/main/kotlin      → vendor/core/                  (D1)
#   carrierponycore/src/test/kotlin      → vendor/core-tests/            (D1)
#   carrierponycore/src/test/resources   → vendor/core-test-resources/   (D1)
#   app/src/main/java/com/carrierpony/app/{crypto,envelope,relay,messaging,pairing,identity,
#     attachments,net,nostr,storage}             → vendor/app/com/carrierpony/app/...  (D2)
#   app/src/main/res/values*/strings.xml   → vendor/app-strings/values*/strings.xml (D11)
#
# The app's top-level files (AppModel, AppSupport, MainActivity) and its ui/, lock/ and push/
# packages are Android-only and are NOT synced. Android-coupled files inside the synced packages
# are copied but excluded in build.gradle.kts; see vendor/README.md.
#
# ALL trees sync together, every run: never refresh one in isolation (a main-tree/test-tree
# snapshot skew produces phantom unresolved references; PGPonyDesktop D5 Fix1).
#
# Usage: tools/sync-vendor.sh [path-to-CarrierPonyAndroid]   (default: ../CarrierPonyAndroid)

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
ANDROID_ROOT="${1:-$REPO_ROOT/../CarrierPonyAndroid}"
CORE="$ANDROID_ROOT/carrierponycore/src"

[ -d "$CORE/main/kotlin" ] || { echo "error: $CORE/main/kotlin not found (pass the CarrierPonyAndroid path)"; exit 1; }

sync_tree() { # $1 = source dir, $2 = vendor root name, $3 = file glob to count
    local src="$1"
    local dst="$REPO_ROOT/vendor/$2"
    rm -rf "$dst"
    mkdir -p "$(dirname "$dst")"
    cp -R "$src" "$dst"
    find "$dst" \( -name ".DS_Store" -o -name "*.bak" -o -name ".fuse_hidden*" \) -delete
    echo "Synced $(find "$dst" -type f -name "$3" | wc -l | tr -d ' ') $3 files → vendor/$2"
}

sync_tree "$CORE/main/kotlin" core '*.kt'
sync_tree "$CORE/test/kotlin" core-tests '*.kt'
sync_tree "$CORE/test/resources" core-test-resources '*'

APP="$ANDROID_ROOT/app/src/main/java/com/carrierpony/app"
[ -d "$APP/messaging" ] || { echo "error: $APP/messaging not found"; exit 1; }
for pkg in crypto envelope relay messaging pairing identity attachments net nostr storage; do
    sync_tree "$APP/$pkg" "app/com/carrierpony/app/$pkg" '*.kt'
done

# D11: the string layers. Android's strings.xml files are vendored verbatim, one per locale, and
# read by Strings.kt as the Android layer. Desktop-only keys live in i18n/ and are never touched here.
RES="$ANDROID_ROOT/app/src/main/res"
[ -f "$RES/values/strings.xml" ] || { echo "error: $RES/values/strings.xml not found"; exit 1; }
STRINGS="$REPO_ROOT/vendor/app-strings"
rm -rf "$STRINGS"
for d in values values-de values-es values-fr values-it values-ja values-pt values-ru values-zh; do
    [ -f "$RES/$d/strings.xml" ] || { echo "error: $RES/$d/strings.xml is missing. Update this list and I18n.SUPPORTED together."; exit 1; }
    mkdir -p "$STRINGS/$d"
    cp "$RES/$d/strings.xml" "$STRINGS/$d/strings.xml"
    printf '  %-12s %5d keys\n' "$d" "$(grep -c '<string name=' "$STRINGS/$d/strings.xml" || true)"
done
echo "Synced 9 strings.xml files → vendor/app-strings/"

echo "Done. Now run: ./gradlew test"
