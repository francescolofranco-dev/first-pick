#!/usr/bin/env python3

import struct
import sys
from pathlib import Path


PNG_SIGNATURE = b"\x89PNG\r\n\x1a\n"
ICON_CHUNKS = (
    ("icp4", "icon_16x16.png"),
    ("ic11", "icon_16x16@2x.png"),
    ("icp5", "icon_32x32.png"),
    ("ic12", "icon_32x32@2x.png"),
    ("ic07", "icon_128x128.png"),
    ("ic13", "icon_128x128@2x.png"),
    ("ic08", "icon_256x256.png"),
    ("ic14", "icon_256x256@2x.png"),
    ("ic09", "icon_512x512.png"),
    ("ic10", "icon_512x512@2x.png"),
)


def pack_iconset(iconset: Path, output: Path) -> None:
    chunks = []
    for chunk_type, filename in ICON_CHUNKS:
        data = (iconset / filename).read_bytes()
        if not data.startswith(PNG_SIGNATURE):
            raise ValueError(f"{filename} is not a PNG")
        chunks.append(chunk_type.encode("ascii") + struct.pack(">I", len(data) + 8) + data)

    body = b"".join(chunks)
    output.write_bytes(b"icns" + struct.pack(">I", len(body) + 8) + body)


if __name__ == "__main__":
    if len(sys.argv) != 3:
        raise SystemExit("usage: pack_icns.py <AppIcon.iconset> <output.icns>")
    pack_iconset(Path(sys.argv[1]), Path(sys.argv[2]))
