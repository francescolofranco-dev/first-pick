#!/usr/bin/env python3
"""Check the pinned production layout against installed Arena files; no live reads."""
import hashlib
import importlib.util
import json
from pathlib import Path
import sys
import struct

ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location(
    'arena_layout', ROOT / 'prototypes/arena-memory/deck_reader.py')
layout = importlib.util.module_from_spec(spec)
spec.loader.exec_module(layout)


def verify(app):
    profile = json.loads((ROOT / 'native/macos/arena-memory-layout.json').read_text())
    app, _, report = layout.transport.inspect_build(app)
    assembly = layout.inspect_profile(app, report, profile)
    # Decode the reviewed class lookup's ADRP/LDR pair independently of the
    # profile pointer. A stale class-table location must fail even on disk.
    lookup = profile['code_anchors'][0]['vm']
    adrp, ldr = struct.unpack('<2I', assembly.read(lookup + 36, 8))
    if adrp & 0x9f00001f != 0x90000008 or ldr & 0xffc003ff != 0xf9400116:
        raise layout.Unsupported('Class table lookup instructions changed.')
    immediate = ((adrp >> 5 & 0x7ffff) << 2) | (adrp >> 29 & 3)
    if immediate & (1 << 20):
        immediate -= 1 << 21
    table = ((lookup + 36) & ~0xfff) + (immediate << 12) + ((ldr >> 10 & 0xfff) * 8)
    if table != profile['class_table_vm']:
        raise layout.Unsupported('Class table location differs from its native lookup.')
    unity = layout.MachImage(app / 'Contents/Frameworks/UnityPlayer.dylib')
    if hashlib.sha256(unity.data).hexdigest() != profile['unity_sha256']:
        raise layout.Unsupported('Unity fingerprint differs from the reviewed profile.')
    for anchor in profile['unity_code_anchors']:
        expected = bytes.fromhex(anchor['hex'])
        if unity.read(anchor['vm'], len(expected)) != expected:
            raise layout.Unsupported('Unity geometry anchor differs from the reviewed profile.')
    return {
        'status': 'verified_installed_layout',
        'app_version': profile['app_version'],
        'reviewed_types': len(profile['types']),
        'assembly_code_anchors': len(profile['code_anchors']),
        'unity_code_anchors': len(profile['unity_code_anchors']),
        'live_memory_verified': False,
    }


if __name__ == '__main__':
    try:
        print(json.dumps(verify(Path(sys.argv[1]) if len(sys.argv) > 1 else layout.transport.DEFAULT_APP)))
    except (OSError, layout.ProbeError) as error:
        sys.exit(str(error))
