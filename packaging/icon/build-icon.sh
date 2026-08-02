#!/usr/bin/env bash
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
if ! iconutil -c icns "$here/AppIcon.iconset" -o "$here/FirstPick.icns" 2>/dev/null; then
  python3 "$here/pack_icns.py" "$here/AppIcon.iconset" "$here/FirstPick.icns"
fi
echo "wrote packaging/icon/FirstPick.icns"
