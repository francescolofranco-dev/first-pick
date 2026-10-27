import importlib.util
import os
import sys
from pathlib import Path
import struct
import tempfile
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location('arena_memory_probe', Path(__file__).with_name('probe.py'))
probe = importlib.util.module_from_spec(spec)
spec.loader.exec_module(probe)


def header(cpu=0x100000C, subtype=0):
    return struct.pack('<8I', probe.MH_MAGIC_64, cpu, subtype, 2, 0, 0, 0, 0)


class ProbeTest(unittest.TestCase):
    def test_read_budget_and_addresses_stop_before_backend_is_called(self):
        calls = []
        def read(address, size):
            calls.append((address, size))
            return b'x' * size
        reader = probe.ReadBudget(read, limit=4)
        self.assertEqual(b'xxxx', reader.read(1, 4))
        for address, size in [(1, 1), (0, 1), (-1, 1), (1, 0), ((1 << 64) - 1, 2)]:
            with self.assertRaises(probe.ProbeError):
                reader.read(address, size)
        self.assertEqual([(1, 4)], calls)

    def test_short_and_expired_reads_are_not_trusted(self):
        with self.assertRaises(probe.ProbeError):
            probe.ReadBudget(lambda a, s: b'').read(1, 2)
        reader = probe.ReadBudget(lambda a, s: self.fail('expired read reached backend'), seconds=0)
        with self.assertRaises(probe.ProbeError):
            reader.read(1, 1)

    def test_path_read_does_not_cross_page_before_finding_terminator(self):
        reader = probe.ReadBudget(lambda a, s: b'A' * s if a == 4094 else b'\0' + b'X' * (s - 1))
        self.assertEqual('AA', reader.path(4094))
        self.assertEqual(258, reader.bytes_requested)

    def test_unterminated_image_path_is_bounded(self):
        with self.assertRaises(probe.ProbeError):
            probe.ReadBudget(lambda a, s: b'A' * s).path(4096)

    def test_dyld_snapshot_retries_when_array_changes(self):
        a = struct.pack('<IIQ', 17, 1, 100)
        b = struct.pack('<IIQ', 17, 1, 200)
        headers = iter([a, b, b, b])
        record = struct.pack('<QQQ', 400, 500, 0)
        class Reader:
            def read(self, address, size):
                return next(headers) if address == 1 else record
        self.assertEqual([(400, 500, 0)], probe.image_snapshot(Reader(), 1))

    def test_invalid_dyld_count_rejects_before_reading_array(self):
        for count in [0, probe.MAX_IMAGES + 1]:
            calls = []
            def read(address, size):
                calls.append(address)
                return struct.pack('<IIQ', 17, count, 100)
            with self.assertRaises(probe.ProbeError):
                probe.image_snapshot(probe.ReadBudget(read), 1)
            self.assertEqual([1], calls)

    def test_thin_and_universal_macho_select_the_live_architecture(self):
        with tempfile.TemporaryDirectory() as tmp:
            p = Path(tmp) / 'image'
            p.write_bytes(header())
            self.assertEqual(header(), probe.disk_header(p, 0x100000C, 0))
            other = header(0x1000007, 3)
            fat = struct.pack('>II', 0xCAFEBABE, 2)
            fat += struct.pack('>IIIII', 0x1000007, 3, 48, 32, 0)
            fat += struct.pack('>IIIII', 0x100000C, 0, 80, 32, 0)
            p.write_bytes(fat + other + header())
            self.assertEqual(header(), probe.disk_header(p, 0x100000C, 0))
            self.assertEqual(other, probe.disk_header(p, 0x1000007, 3))

    def test_malformed_slice_and_architecture_mismatch_fail_closed(self):
        with tempfile.TemporaryDirectory() as tmp:
            p = Path(tmp) / 'image'
            p.write_bytes(struct.pack('>II', 0xCAFEBABE, 1) + struct.pack('>IIIII', 0x100000C, 0, 9999, 32, 0))
            with self.assertRaises(probe.ProbeError):
                probe.disk_header(p, 0x100000C, 0)
            p.write_bytes(header())
            with self.assertRaises(probe.ProbeError):
                probe.disk_header(p, 0x1000007, 3)

    def test_both_live_images_must_match_expected_bundle_paths_and_bytes(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            exe = root / 'Contents/MacOS/MTGA'
            asm = root / 'Contents/Frameworks/GameAssembly.dylib'
            for p in [exe, asm]:
                p.parent.mkdir(parents=True, exist_ok=True)
                p.write_bytes(header())
            paths = {101: str(exe), 102: str(asm)}
            class Reader:
                def path(self, address): return paths[address]
                def read(self, address, size): return header()
            records = [(1, 101, 0), (2, 102, 0)]
            self.assertEqual(2, len(probe.verify_images(Reader(), records, root)))
            with self.assertRaises(probe.ProbeError):
                probe.verify_images(Reader(), records[:1], root)
            asm.write_bytes(header(0x1000007, 3))
            with self.assertRaises(probe.ProbeError):
                probe.verify_images(Reader(), records, root)
            paths[102] = str(root / 'different/GameAssembly.dylib')
            with self.assertRaises(probe.ProbeError):
                probe.verify_images(Reader(), records, root)

    @unittest.skipUnless(sys.platform == 'darwin', 'real Mach API check requires macOS')
    def test_real_self_process_image_header_matches_installed_binary(self):
        executable = probe.process_path(os.getpid())
        with probe.DarwinMemory(os.getpid(), self_test=True) as memory:
            reader = probe.ReadBudget(memory.read)
            records = probe.image_snapshot(reader, memory.images_address())
            addresses = [address for address, path, _ in records if Path(reader.path(path)).resolve() == executable]
            self.assertEqual(1, len(addresses))
            live = reader.read(addresses[0], 32)
            magic, cpu, subtype = struct.unpack_from('<III', live)
            self.assertEqual(probe.MH_MAGIC_64, magic)
            self.assertEqual(probe.disk_header(executable, cpu, subtype), live)

    def test_process_discovery_rejects_a_different_executable_and_bad_pid(self):
        with patch.object(probe, 'process_path', return_value=Path('/other/MTGA')):
            with self.assertRaises(probe.InvalidTarget):
                probe.resolve_pid(Path('/expected/MTGA'), 123)
        for pid in [-1, 0, (1 << 31)]:
            with self.assertRaises(probe.InvalidTarget):
                probe.resolve_pid(Path('/expected/MTGA'), pid)


if __name__ == '__main__':
    unittest.main()
