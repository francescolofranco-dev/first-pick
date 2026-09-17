#!/usr/bin/env bash
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
out="$here/../../src/main/resources/native/macos"
sdk="${SDKROOT:-$(xcrun --sdk macosx --show-sdk-path)}"
case "${1:-}" in
  "") native_archs=(arm64 x86_64) ;;
  --host)
    host_arch="$(uname -m)"
    case "$host_arch" in
      arm64|x86_64) native_archs=("$host_arch") ;;
      *) echo "Unsupported Mac architecture: $host_arch" >&2; exit 1 ;;
    esac
    ;;
  *) echo "Usage: $0 [--host]" >&2; exit 1 ;;
esac
if (( $# > 1 )); then echo "Usage: $0 [--host]" >&2; exit 1; fi
mkdir -p "$out"

build() {
  local name="$1"; local minos="$2"; shift 2
  local tmp; tmp="$(mktemp -d)"
  for arch in "${native_archs[@]}"; do
    swiftc -sdk "$sdk" -module-cache-path "$tmp/modules-$arch" -O -target "$arch-apple-macos$minos" "$@" "$here/$name.swift" -o "$tmp/$arch"
  done
  if (( ${#native_archs[@]} > 1 )); then
    lipo -create "$tmp/arm64" "$tmp/x86_64" -o "$tmp/helper"
  else
    mv "$tmp/${native_archs[0]}" "$tmp/helper"
  fi
  chmod +x "$tmp/helper"
  mv "$tmp/helper" "$out/$name"
  rm -rf "$tmp"
  echo "built $out/$name ($(lipo -archs "$out/$name"))"
}

build window-locator 11
build window-capture 13 -framework ScreenCaptureKit
build vision-ocr 11 -framework Vision -framework ImageIO
