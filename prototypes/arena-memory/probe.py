#!/usr/bin/env python3
"""Bounded, opt-in macOS Arena memory transport proof. Standard library only.

No writes, code execution, injection, debugger attachment, process suspension,
heap scan, raw dump, privilege escalation, or production-app integration.
"""
from __future__ import annotations

import argparse
import ctypes as C
import hashlib
import json
import os
from pathlib import Path
import plistlib
import signal
import struct
import subprocess
import sys
import time

DEFAULT_APP = Path('/Users/Shared/Epic Games/MagicTheGathering/MTGA.app')
BUNDLE_ID = 'com.wizards.mtga'
MAX_READ_BYTES = 2 * 1024 * 1024
MAX_IMAGES = 4096
MAX_SECONDS = 8
MH_MAGIC_64 = 0xFEEDFACF
REQUIRED_IMAGES = {'MTGA', 'GameAssembly.dylib'}


class ProbeError(Exception):
    status = 'verification_failed'
    exit_code = 4


class AccessDenied(ProbeError):
    status = 'access_denied'
    exit_code = 3


class InvalidTarget(ProbeError):
    status = 'invalid_target'
    exit_code = 2


class ReadBudget:
    def __init__(self, read, limit=MAX_READ_BYTES, seconds=MAX_SECONDS):
        self._read = read
        self.limit = limit
        self.bytes_requested = 0
        self.started = time.monotonic()
        self.seconds = seconds

    def read(self, address: int, size: int) -> bytes:
        if address <= 0 or address >= (1 << 64) or size <= 0 or address + size > (1 << 64):
            raise ProbeError('Invalid address or size; no read performed.')
        if self.bytes_requested + size > self.limit:
            raise ProbeError('Read budget exhausted; no further read performed.')
        if time.monotonic() - self.started > self.seconds:
            raise ProbeError('Time budget exhausted; no further read performed.')
        self.bytes_requested += size
        data = self._read(address, size)
        if len(data) != size:
            raise ProbeError('Short memory read; the observation is not verified.')
        return data

    def path(self, address: int) -> str:
        # Read up to each page boundary; do not cross an unmapped page needlessly.
        data = bytearray()
        while len(data) < 4096:
            size = min(256, 4096 - len(data), 4096 - ((address + len(data)) % 4096))
            part = self.read(address + len(data), size)
            end = part.find(b'\0')
            if end >= 0:
                try:
                    return (data + part[:end]).decode('utf-8')
                except UnicodeDecodeError as error:
                    raise ProbeError('Image path is not valid UTF-8.') from error
            data.extend(part)
        raise ProbeError('Image path exceeded the bounded path length.')


class DarwinMemory:
    def __init__(self, pid: int, *, self_test=False, allow_control_port=False):
        if sys.platform != 'darwin' or C.sizeof(C.c_void_p) != 8:
            raise InvalidTarget('The prototype requires 64-bit macOS.')
        self.lib = C.CDLL('/usr/lib/libSystem.B.dylib', use_errno=True)
        self.lib.mach_task_self.argtypes = []
        self.lib.mach_task_self.restype = C.c_uint
        self.lib.mach_port_deallocate.argtypes = [C.c_uint, C.c_uint]
        self.lib.mach_port_deallocate.restype = C.c_int
        self.lib.mach_vm_read_overwrite.argtypes = [C.c_uint, C.c_uint64, C.c_uint64, C.c_uint64, C.POINTER(C.c_uint64)]
        self.lib.mach_vm_read_overwrite.restype = C.c_int
        self.lib.task_info.argtypes = [C.c_uint, C.c_uint, C.POINTER(C.c_int), C.POINTER(C.c_uint)]
        self.lib.task_info.restype = C.c_int
        self.self_port = self.lib.mach_task_self()
        self.port = 0
        self.owns_port = False
        if self_test:
            if pid != os.getpid():
                raise InvalidTarget('Self-test cannot target another process.')
            self.port = self.self_port
            self.api = 'mach_task_self (self-test only)'
            return
        attempts = []
        names = ['task_read_for_pid'] + (['task_for_pid'] if allow_control_port else [])
        for name in names:
            fn = getattr(self.lib, name, None)
            if fn is None:
                attempts.append(name + ': unavailable')
                continue
            fn.argtypes = [C.c_uint, C.c_int, C.POINTER(C.c_uint)]
            fn.restype = C.c_int
            port = C.c_uint()
            C.set_errno(0)
            result = fn(self.self_port, pid, C.byref(port))
            err = C.get_errno()
            if result == 0 and port.value:
                self.port = port.value
                self.owns_port = True
                self.api = name
                return
            attempts.append(f'{name}: result={result}, errno={err}')
        raise AccessDenied('; '.join(attempts) + '. No target memory was read. No system settings were changed.')

    def close(self):
        if self.owns_port and self.port:
            self.lib.mach_port_deallocate(self.self_port, self.port)
            self.port = 0

    def __enter__(self):
        return self

    def __exit__(self, *args):
        self.close()

    def read(self, address, size):
        output = C.create_string_buffer(size)
        actual = C.c_uint64()
        result = self.lib.mach_vm_read_overwrite(self.port, address, size, C.addressof(output), C.byref(actual))
        if result != 0 or actual.value != size:
            raise ProbeError(f'mach_vm_read_overwrite failed: result={result}; requested={size}, returned={actual.value}.')
        return output.raw

    def images_address(self):
        # Darwin task_info.h packs task_dyld_info to 4-byte alignment: QQi = 20 bytes.
        info = (C.c_int * 5)()
        count = C.c_uint(5)
        result = self.lib.task_info(self.port, 17, info, C.byref(count))
        if result != 0 or count.value != 5:
            raise ProbeError(f'TASK_DYLD_INFO failed: result={result}, words={count.value}.')
        address, size, image_format = struct.unpack('<QQi', bytes(info))
        if image_format != 1 or address == 0 or size < 16:
            raise ProbeError('Only a valid 64-bit dyld image list is supported.')
        return address


def process_path(pid: int) -> Path:
    lib = C.CDLL('/usr/lib/libproc.dylib', use_errno=True)
    lib.proc_pidpath.argtypes = [C.c_int, C.c_void_p, C.c_uint]
    lib.proc_pidpath.restype = C.c_int
    buffer = C.create_string_buffer(4096)
    length = lib.proc_pidpath(pid, buffer, len(buffer))
    if length <= 0:
        raise InvalidTarget('Cannot verify the process executable path; refusing to read memory.')
    return Path(os.fsdecode(buffer.value)).resolve()


def inspect_build(app: Path):
    app = app.resolve(strict=True)
    with (app / 'Contents/Info.plist').open('rb') as source:
        info = plistlib.load(source)
    if info.get('CFBundleIdentifier') != BUNDLE_ID or info.get('CFBundleExecutable') != 'MTGA':
        raise InvalidTarget('This is not the expected MTGA app bundle.')
    executable = (app / 'Contents/MacOS/MTGA').resolve(strict=True)
    assembly = (app / 'Contents/Frameworks/GameAssembly.dylib').resolve(strict=True)
    metadata = app / 'Contents/Resources/Data/il2cpp_data/Metadata/global-metadata.dat'
    if not executable.is_relative_to(app) or not assembly.is_relative_to(app):
        raise InvalidTarget('Arena executable or assembly resolves outside its bundle.')
    data = metadata.read_bytes()
    if len(data) < 8:
        raise ProbeError('IL2CPP metadata header is truncated.')
    magic, version = struct.unpack_from('<II', data)
    if magic != 0xFAB11BAF:
        raise ProbeError('The installed build does not have the expected IL2CPP metadata signature.')
    return app, executable, {
        'bundle_id': BUNDLE_ID,
        'app_version': info.get('CFBundleShortVersionString'),
        'runtime': 'IL2CPP',
        'metadata_version': version,
        'metadata_sha256': hashlib.sha256(data).hexdigest(),
        'deck_type_markers_present': [name for name in ['DeckBuilderModel', 'DraftDeckView', 'WrapperDeckBuilder'] if name.encode() in data],
        'deck_snapshot': 'not_implemented',
        'arena_access_proven': False,
    }


def resolve_pid(executable: Path, requested=None):
    if requested is not None:
        candidates = [requested]
    else:
        process = subprocess.run(['/usr/bin/pgrep', '-x', 'MTGA'], capture_output=True, text=True, timeout=3)
        if process.returncode not in (0, 1):
            raise InvalidTarget('Process discovery failed.')
        candidates = [int(value) for value in process.stdout.split()]
    if any(not 1 <= pid < (1 << 31) for pid in candidates):
        raise InvalidTarget("PID is outside the supported range.")
    matches = [pid for pid in candidates if process_path(pid) == executable]
    if len(matches) != 1:
        raise InvalidTarget('Expected exactly one running process matching this Arena executable; use --pid if necessary.')
    return matches[0]


def image_snapshot(reader: ReadBudget, address: int):
    for _ in range(3):
        before = reader.read(address, 16)
        version, count, array = struct.unpack('<IIQ', before)
        if version < 1 or not 1 <= count <= MAX_IMAGES or not array:
            raise ProbeError('dyld image list is unavailable or outside supported bounds.')
        records = reader.read(array, count * 24)
        if before == reader.read(address, 16):
            return [struct.unpack_from('<QQQ', records, i * 24) for i in range(count)]
    raise ProbeError('dyld image list changed during every attempt; no snapshot verified.')


def disk_header(path: Path, cpu: int, subtype: int):
    with path.open('rb') as source:
        head = source.read(32)
        if len(head) != 32:
            raise ProbeError('Mach-O file header is truncated.')
        magic = struct.unpack_from('>I', head)[0]
        if magic in (0xCAFEBABE, 0xCAFEBABF, 0xBEBAFECA, 0xBFBAFECA):
            endian = '>' if magic in (0xCAFEBABE, 0xCAFEBABF) else '<'
            wide = magic in (0xCAFEBABF, 0xBFBAFECA)
            count = struct.unpack_from(endian + 'I', head, 4)[0]
            if not 1 <= count <= 32:
                raise ProbeError('Universal binary architecture count exceeds bounds.')
            source.seek(8)
            layout = endian + ('IIQQII' if wide else 'IIIII')
            size = struct.calcsize(layout)
            table = source.read(count * size)
            if len(table) != count * size:
                raise ProbeError('Universal binary architecture table is truncated.')
            matches = []
            file_size = os.fstat(source.fileno()).st_size
            for i in range(count):
                arch_cpu, arch_subtype, offset, length, *_ = struct.unpack_from(layout, table, i * size)
                if arch_cpu == cpu and (arch_subtype & 0xFFFFFF) == (subtype & 0xFFFFFF):
                    if offset < 8 + count * size or length < 32 or offset + length > file_size:
                        raise ProbeError('Universal binary slice exceeds file bounds.')
                    matches.append(offset)
            if len(matches) != 1:
                raise ProbeError('No unique on-disk architecture matches the running image.')
            source.seek(matches[0])
            head = source.read(32)
        if len(head) != 32 or struct.unpack_from('<I', head)[0] != MH_MAGIC_64:
            raise ProbeError('Only little-endian 64-bit Mach-O images are supported.')
        _, actual_cpu, actual_subtype = struct.unpack_from('<III', head)
        if actual_cpu != cpu or (actual_subtype & 0xFFFFFF) != (subtype & 0xFFFFFF):
            raise ProbeError('Running and installed image architectures disagree.')
        return head


def verify_images(reader: ReadBudget, image_records, app: Path):
    result = []
    expected = {'MTGA': app / 'Contents/MacOS/MTGA', 'GameAssembly.dylib': app / 'Contents/Frameworks/GameAssembly.dylib'}
    for address, path_address, _ in image_records:
        path = Path(reader.path(path_address))
        if path.name not in expected:
            continue
        if path.resolve() != expected[path.name].resolve():
            raise ProbeError('A required image resolves to a different installed binary.')
        header = reader.read(address, 32)
        magic, cpu, subtype = struct.unpack_from('<III', header)
        if magic != MH_MAGIC_64 or header != disk_header(path, cpu, subtype):
            raise ProbeError('Live image header does not match the installed Arena binary.')
        result.append({'image': path.name, 'architecture': {0x100000C: 'arm64', 0x1000007: 'x86_64'}.get(cpu, hex(cpu)),
                       'header_sha256': hashlib.sha256(header).hexdigest(), 'matches_installed_binary': True})
    if {item['image'] for item in result} != REQUIRED_IMAGES or len(result) != 2:
        raise ProbeError('Both Arena and GameAssembly must be verified before declaring success.')
    return result


def self_test():
    sample = b'FirstPick read-only transport self-test\0'
    buffer = C.create_string_buffer(sample)
    with DarwinMemory(os.getpid(), self_test=True) as memory:
        reader = ReadBudget(memory.read)
        if reader.read(C.addressof(buffer), len(sample)) != sample:
            raise ProbeError('Self-test bytes did not match.')
        return {'status': 'self_read_verified', 'bytes_read': reader.bytes_requested, 'arena_access_proven': False}


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('action', choices=['inspect-build', 'probe', 'self-test'])
    parser.add_argument('--app', type=Path, default=DEFAULT_APP)
    parser.add_argument('--pid', type=int)
    parser.add_argument('--allow-control-port', action='store_true', help='Explicitly allow task_for_pid fallback; all operations remain reads.')
    args = parser.parse_args(argv)
    report = {}
    timer_started = False
    try:
        if args.action == 'self-test':
            report = self_test()
        else:
            app, executable, report = inspect_build(args.app)
            if args.action == 'inspect-build':
                report['status'] = 'installed_build_inspected'
            else:
                if sys.platform != 'darwin':
                    raise InvalidTarget('Live process reads require macOS.')
                pid = resolve_pid(executable, args.pid)
                # This terminates the probe itself, never the target, if an OS call stalls.
                signal.signal(signal.SIGALRM, signal.SIG_DFL)
                signal.setitimer(signal.ITIMER_REAL, MAX_SECONDS + 2)
                timer_started = True
                with DarwinMemory(pid, allow_control_port=args.allow_control_port) as memory:
                    if process_path(pid) != executable:
                        raise InvalidTarget('Target process changed before reading; aborting.')
                    reader = ReadBudget(memory.read)
                    records = image_snapshot(reader, memory.images_address())
                    images = verify_images(reader, records, app)
                    if process_path(pid) != executable:
                        raise InvalidTarget('Target process changed during reading; aborting.')
                    report.update(status='arena_memory_read_verified', arena_access_proven=True, read_api=memory.api,
                                  verified_images=images, bytes_read=reader.bytes_requested,
                                  elapsed_ms=round((time.monotonic() - reader.started) * 1000, 1))
        print(json.dumps(report, indent=2))
        return 0
    except ProbeError as error:
        report.update(status=error.status, reason=str(error))
        print(json.dumps(report, indent=2))
        return error.exit_code
    except (OSError, ValueError, subprocess.SubprocessError, struct.error) as error:
        print(json.dumps({'status': 'probe_failed', 'reason': str(error)}, indent=2))
        return 4
    finally:
        if timer_started:
            signal.setitimer(signal.ITIMER_REAL, 0)


if __name__ == '__main__':
    sys.exit(main())
