#!/usr/bin/env bash
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
iconutil -c icns "$here/AppIcon.iconset" -o "$here/FirstPick.icns"
echo "wrote packaging/icon/FirstPick.icns"
