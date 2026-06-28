#!/usr/bin/env bash
# Regenerate FirstPick.icns from AppIcon.iconset (macOS only).
#   packaging/icon/build-icon.sh
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
iconutil -c icns "$here/AppIcon.iconset" -o "$here/FirstPick.icns"
echo "wrote packaging/icon/FirstPick.icns"
