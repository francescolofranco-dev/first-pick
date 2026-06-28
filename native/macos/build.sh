#!/usr/bin/env bash
# Compile the macOS native helpers as universal (arm64 + x86_64) binaries into the app
# resources, so they ship in the .dmg and are on the classpath for `./gradlew run`.
#   window-locator  — Arena window bounds (no permission needed)
#   window-capture  — Arena window screenshot via ScreenCaptureKit (Screen Recording perm)
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
out="$here/../../src/main/resources/native/macos"
mkdir -p "$out"

build() { # name  min-macos  [extra swiftc flags...]
  local name="$1"; local minos="$2"; shift 2
  local tmp; tmp="$(mktemp -d)"
  swiftc -O -target "arm64-apple-macos$minos"  "$@" "$here/$name.swift" -o "$tmp/a"
  swiftc -O -target "x86_64-apple-macos$minos" "$@" "$here/$name.swift" -o "$tmp/x"
  lipo -create "$tmp/a" "$tmp/x" -o "$out/$name"
  chmod +x "$out/$name"; rm -rf "$tmp"
  echo "built $out/$name ($(lipo -archs "$out/$name"))"
}

build window-locator 11
build window-capture 13 -framework ScreenCaptureKit
