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
module_cache="$here/../../build/native-module-cache"
mkdir -p "$out" "$module_cache"
# Retire generated capture executables left by an earlier checkout/build.
rm -f "$out/window-capture" "$out/vision-ocr" "$out/deck-reader"

build() {
  local name="$1"; local minos="$2"; shift 2
  local tmp; tmp="$(mktemp -d)"
  local source="$here/$name.swift"
  if [[ "$name" == "arena-memory" ]]; then
    # Freeze the reviewed profile into the signed executable. Elevated startup
    # never reads a mutable layout file or reloads code from disk.
    python3 - "$here/arena-memory-layout.json" "$source" "$tmp/main.swift" <<'PY'
import pathlib, sys
profile, source, destination = map(pathlib.Path, sys.argv[1:])
destination.write_text('import Foundation\nprivate let pinnedMemoryProfileJSON = #"""\n' +
    profile.read_text() + '"""#\n' + source.with_name('arena-access-service.swift').read_text() + '\n' + source.read_text() + '\n' + source.with_name('arena-geometry.swift').read_text())
PY
    source="$tmp/main.swift"
  fi
  for arch in "${native_archs[@]}"; do
    swiftc -sdk "$sdk" -module-cache-path "$module_cache/$arch" -O -target "$arch-apple-macos$minos" "$@" "$source" -o "$tmp/$arch"
  done
  if (( ${#native_archs[@]} > 1 )); then
    lipo -create "$tmp/arm64" "$tmp/x86_64" -o "$tmp/helper"
  else
    mv "$tmp/${native_archs[0]}" "$tmp/helper"
  fi
  chmod +x "$tmp/helper"
  if [[ -n "${MACOS_SIGNING_IDENTITY:-}" ]]; then
    codesign --force --sign "$MACOS_SIGNING_IDENTITY" --timestamp --options runtime \
      --identifier "com.firstpick.app.$name" "$tmp/helper"
    codesign --verify --strict "$tmp/helper"
  fi
  mv "$tmp/helper" "$out/$name"
  rm -rf "$tmp"
  echo "built $out/$name ($(lipo -archs "$out/$name"))"
}

build window-locator 11
build arena-memory 13
