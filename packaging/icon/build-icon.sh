#!/usr/bin/env bash
# Regenerate the FirstPick app icon (.icns) + repo logo PNG from IconGen.java.
# macOS only (uses sips + iconutil). Run from anywhere:
#   packaging/icon/build-icon.sh
set -euo pipefail

here="$(cd "$(dirname "$0")" && pwd)"
root="$(cd "$here/../.." && pwd)"
jh="$(/usr/libexec/java_home)"
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT

echo "Rendering master 1024px…"
"$jh/bin/javac" -d "$work" "$here/IconGen.java"
( cd "$work" && "$jh/bin/java" IconGen "$work/icon-1024.png" )

echo "Building .icns iconset…"
iconset="$work/FirstPick.iconset"
mkdir -p "$iconset"
gen() { sips -z "$1" "$1" "$work/icon-1024.png" --out "$iconset/$2" >/dev/null; }
gen 16   icon_16x16.png
gen 32   icon_16x16@2x.png
gen 32   icon_32x32.png
gen 64   icon_32x32@2x.png
gen 128  icon_128x128.png
gen 256  icon_128x128@2x.png
gen 256  icon_256x256.png
gen 512  icon_256x256@2x.png
gen 512  icon_512x512.png
cp "$work/icon-1024.png" "$iconset/icon_512x512@2x.png"

mkdir -p "$root/packaging/macos" "$root/docs"
iconutil -c icns "$iconset" -o "$root/packaging/macos/FirstPick.icns"

echo "Writing repo logo PNGs…"
cp "$work/icon-1024.png" "$root/docs/icon.png"           # full-res logo
sips -z 256 256 "$work/icon-1024.png" --out "$root/docs/icon-256.png" >/dev/null

echo "Done:"
echo "  packaging/macos/FirstPick.icns"
echo "  docs/icon.png (1024), docs/icon-256.png"
