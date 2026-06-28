#!/usr/bin/env bash
# Compile the window-locator helper as a universal (arm64 + x86_64) binary into
# the app resources so it ships in the .dmg and is on the classpath for `./gradlew run`.
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
out="$here/../../src/main/resources/native/macos"
mkdir -p "$out"
tmp="$(mktemp -d)"; trap 'rm -rf "$tmp"' EXIT
swiftc -O -target arm64-apple-macos11   "$here/window-locator.swift" -o "$tmp/wl-arm64"
swiftc -O -target x86_64-apple-macos11  "$here/window-locator.swift" -o "$tmp/wl-x64"
lipo -create "$tmp/wl-arm64" "$tmp/wl-x64" -o "$out/window-locator"
chmod +x "$out/window-locator"
echo "built $out/window-locator"; lipo -archs "$out/window-locator"
