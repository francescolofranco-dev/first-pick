#!/usr/bin/env bash
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
out="$here/../../src/main/resources/native/macos"
mkdir -p "$out"

build() {
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
