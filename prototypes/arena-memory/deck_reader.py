#!/usr/bin/env python3
"""Opt-in, build-specific read-only snapshot of Arena's active Limited editor.

Standard library only. No heap scans, game calls, writes, injection, debugger,
permission changes, helper installation, or production integration.
"""
from __future__ import annotations

import argparse
import hashlib
import importlib.util
import json
from pathlib import Path
import signal
import struct
import sys
import time

# Explicit sibling import also works with Python's isolated (-I) mode.
_spec = importlib.util.spec_from_file_location('arena_transport', Path(__file__).with_name('probe.py'))
transport = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(transport)
ProbeError = transport.ProbeError
PROFILE_PATH = Path(__file__).with_name('layout-2026.64.50-arm64.json')
MAX_SLOTS = 2048
MAX_COPIES = 300


class Unsupported(ProbeError):
    status = 'unsupported_layout'


class NotEditing(ProbeError):
    status = 'limited_editor_not_active'


class Changing(ProbeError):
    status = 'deck_changing'


def unpack(fmt, data):
    return struct.unpack('<' + fmt, data)


class MachImage:
    """Bounded arm64 file view. VM offsets are never process addresses."""
    def __init__(self, path):
        self.data = Path(path).read_bytes()
        if len(self.data) < 32:
            raise Unsupported('Truncated assembly.')
        self.start = 0
        magic = struct.unpack_from('>I', self.data)[0]
        if magic == 0xCAFEBABE:
            count = struct.unpack_from('>I', self.data, 4)[0]
            if not 1 <= count <= 32 or 8 + count * 20 > len(self.data):
                raise Unsupported('Invalid architecture table.')
            matches = []
            for i in range(count):
                cpu, _, offset, size, _ = struct.unpack_from('>5I', self.data, 8 + i * 20)
                if cpu == 0x100000C:
                    if offset < 8 + count * 20 or size < 32 or offset + size > len(self.data):
                        raise Unsupported('Invalid arm64 slice.')
                    matches.append((offset, size))
            if len(matches) != 1:
                raise Unsupported('Expected one arm64 slice.')
            self.start, length = matches[0]
        else:
            length = len(self.data)
        self.slice = memoryview(self.data)[self.start:self.start + length]
        magic, cpu, _, _, count, size, _, _ = unpack('8I', self.slice[:32])
        if magic != transport.MH_MAGIC_64 or cpu != 0x100000C or size > 65536 or 32 + size > length:
            raise Unsupported('Only a valid arm64 Mach-O is supported.')
        self.commands = bytes(self.slice[32:32 + size])
        self.segments = []
        offset = 0
        for _ in range(count):
            if offset + 8 > size:
                raise Unsupported('Truncated load command.')
            cmd, n = unpack('2I', self.commands[offset:offset + 8])
            if n < 8 or offset + n > size:
                raise Unsupported('Invalid load command size.')
            if cmd == 0x19:
                if n < 72:
                    raise Unsupported('Truncated segment command.')
                vm, vsize, file_offset, file_size = unpack('4Q', self.commands[offset + 24:offset + 56])
                if file_offset + file_size > length or file_size > vsize:
                    raise Unsupported('Segment exceeds assembly bounds.')
                self.segments.append((vm, vsize, file_offset, file_size))
            offset += n
        if offset != size:
            raise Unsupported('Load command count does not match size.')

    def read(self, address, size):
        if address < 0 or size <= 0:
            raise Unsupported('Invalid file read.')
        matches = [s for s in self.segments if s[0] <= address and address + size <= s[0] + s[3]]
        if len(matches) != 1:
            raise Unsupported('File read is outside one backed segment.')
        vm, _, offset, _ = matches[0]
        return bytes(self.slice[offset + address - vm:offset + address - vm + size])


def inspect_profile(app, report, profile):
    if (report['app_version'] != profile['app_version'] or
            report['metadata_version'] != 39 or report['metadata_sha256'] != profile['metadata_sha256']):
        raise Unsupported('Arena build/metadata differs from the reviewed profile; no memory read attempted.')
    image = MachImage(app / 'Contents/Frameworks/GameAssembly.dylib')
    if hashlib.sha256(image.data).hexdigest() != profile['assembly_sha256']:
        raise Unsupported('Assembly fingerprint differs from the reviewed profile; no memory read attempted.')
    for anchor in profile['code_anchors']:
        if image.read(anchor['vm'], len(bytes.fromhex(anchor['hex']))) != bytes.fromhex(anchor['hex']):
            raise Unsupported('Native layout anchor differs from the reviewed profile.')
    metadata = (app / 'Contents/Resources/Data/il2cpp_data/Metadata/global-metadata.dat').read_bytes()
    verify_metadata_fields(metadata, image, profile)
    return image


def verify_metadata_fields(data, image, profile):
    """Validate the few selected type/field rows, not all gameplay metadata."""
    if len(data) < 380 or unpack('2I', data[:8]) != (0xFAB11BAF, 39):
        raise Unsupported('Expected metadata v39.')
    tables = [unpack('3I', data[8 + i * 12:20 + i * 12]) for i in range(31)]
    if any(offset + size > len(data) for offset, size, _ in tables):
        raise Unsupported('Metadata table exceeds file bounds.')
    strings, fields, definitions = tables[2], tables[11], tables[19]
    if definitions[1] != definitions[2] * 82 or fields[1] != fields[2] * 12:
        raise Unsupported('Unexpected metadata table strides.')

    def name(index):
        if index >= strings[1]:
            raise Unsupported('Metadata string index exceeds bounds.')
        offset = strings[0] + index
        end = data.find(b'\0', offset, min(offset + 256, strings[0] + strings[1]))
        if end < 0:
            raise Unsupported('Metadata name is unterminated.')
        return data[offset:end].decode('ascii')

    for spec in profile['types'].values():
        index = spec['index']
        if not 0 <= index < definitions[2]:
            raise Unsupported('Profile type index exceeds metadata bounds.')
        offset = definitions[0] + index * 82
        definition = unpack('5IH9I8H2I', data[offset:offset + 82])
        if (name(definition[0]), name(definition[1])) != (spec['name'], spec['namespace']):
            raise Unsupported('Profile type name differs from metadata.')
        first, count = definition[7], definition[17]
        if first + count > fields[2]:
            raise Unsupported('Type field range exceeds bounds.')
        offsets = image.read(unpack('Q', image.read(profile['field_offsets_vm'] + index * 8, 8))[0], count * 4)
        actual = {}
        for i in range(count):
            start = fields[0] + (first + i) * 12
            field_name = name(unpack('3I', data[start:start + 12])[0])
            actual[field_name] = unpack('i', offsets[i * 4:i * 4 + 4])[0]
        if any(actual.get(field) != value for field, value in spec['fields'].items()):
            raise Unsupported('Profile field offset differs from the installed binary.')


class Runtime:
    def __init__(self, reader, slide, profile):
        self.reader, self.slide, self.profile = reader, slide, profile
        self.classes = {}
        self.field_cache = {}
        self.checkpoint = 'runtime_class_table'

    def read(self, address, size):
        if address < 4096 or address + size > 1 << 48 or not 0 < size <= 65536:
            raise Unsupported('Runtime read is outside the supported address/size bounds.')
        return self.reader.read(address, size)

    def u64(self, address):
        return unpack('Q', self.read(address, 8))[0]

    def pointer(self, address, *, nullable=False, alignment=8, label='runtime'):
        value = self.u64(address)
        if nullable and value == 0:
            return 0
        if value < 4096 or value >= 1 << 48 or value % alignment:
            raise Unsupported(f'The required {label} pointer is null or invalid.')
        return value

    def string(self, address):
        # Type/field names only; never managed card/account/deck-name strings.
        result = bytearray()
        for _ in range(4):
            size = min(32, 4096 - ((address + len(result)) % 4096))
            part = self.read(address + len(result), size)
            end = part.find(b'\0')
            if end >= 0:
                try:
                    return (result + part[:end]).decode('ascii')
                except UnicodeDecodeError as error:
                    raise Unsupported('Runtime metadata name is not ASCII.') from error
            result.extend(part)
        raise Unsupported('Runtime metadata name exceeds bounds.')

    def identity(self, klass):
        # Names point into a packed metadata string table, at byte alignment.
        # Class/object/array pointers keep their separate eight-byte check.
        return (self.string(self.pointer(klass + 16, alignment=1, label='class-name metadata')),
                self.string(self.pointer(klass + 24, alignment=1, label='namespace metadata')))

    def klass(self, key):
        if key not in self.classes:
            spec = self.profile['types'][key]
            table = self.pointer(self.slide + self.profile['class_table_vm'], label='class table')
            klass = self.pointer(table + spec['index'] * 8, label=f'{key} class')
            if self.identity(klass) != (spec['name'], spec['namespace']):
                raise Unsupported(f'Runtime class table does not match the reviewed {key} type.')
            self.classes[key] = klass
        return self.classes[key]

    def check_object(self, obj, key):
        if not obj or self.pointer(obj) != self.klass(key):
            raise Unsupported(f'Object type differs from the expected {key} type.')
        return obj

    def object_field(self, obj, key, field, target, *, nullable=False):
        self.check_object(obj, key)
        pointer = self.pointer(obj + self.offset(key, field), nullable=nullable, label=f'{key}.{field}')
        return self.check_object(pointer, target) if pointer else 0

    def offset(self, key, field):
        return self.profile['types'][key]['fields'][field]

    def boolean(self, obj, key, field):
        value = self.read(obj + self.offset(key, field), 1)[0]
        if value not in (0, 1):
            raise Unsupported('Invalid Boolean field.')
        return bool(value)

    def singleton(self, key, field):
        storage = self.pointer(self.klass(key) + 0xB8, label=f'{key} static fields')
        obj = self.pointer(storage + self.offset(key, field), label=f'{key}.{field}')
        return self.check_object(obj, key)

    def fields(self, klass):
        if klass not in self.field_cache:
            count = unpack('H', self.read(klass + 0x124, 2))[0]
            if not 1 <= count <= 64:
                raise Unsupported('Runtime field count exceeds the supported class shape.')
            array = self.pointer(klass + 0x80)
            data = self.read(array, count * 32)
            fields = {}
            for i in range(count):
                name, _, parent, offset, _ = unpack('QQQiI', data[i * 32:(i + 1) * 32])
                if parent != klass or not 0 <= offset <= 4096:
                    raise Unsupported('Invalid runtime field metadata.')
                name = self.string(name)
                if name in fields:
                    raise Unsupported('Duplicate runtime field name.')
                fields[name] = offset
            self.field_cache[klass] = fields
        return self.field_cache[klass]

    def dictionary(self, obj, kind):
        klass = self.pointer(obj)
        if self.identity(klass) != ('Dictionary`2', 'System.Collections.Generic'):
            raise Unsupported('Expected a managed dictionary.')
        # Confirm the primitive/reference widths for the three reviewed instances.
        data, bits, _ = unpack('QII', self.read(klass + 0x20, 16))
        if (bits >> 16) & 255 != 21:
            raise Unsupported('Dictionary is not the expected generic instance.')
        inst = self.pointer(data + 8)
        if self.u64(inst) != 2:
            raise Unsupported('Dictionary generic arity differs.')
        args = self.read(self.pointer(inst + 8), 16)
        arg_kinds = tuple((unpack('QII', self.read(p, 16))[1] >> 16) & 255 for p in unpack('2Q', args))
        expected = {'quantities': (9, 9), 'scopes': (17, 18), 'services': (18, 28)}[kind]
        if arg_kinds != expected:
            raise Unsupported('Dictionary key/value types differ from the reviewed layout.')
        fields = self.fields(klass)
        needed = ('_entries', '_count', '_freeCount', '_version')
        if any(k not in fields for k in needed):
            raise Unsupported('Dictionary bookkeeping fields are missing.')

        def header():
            return (self.pointer(obj + fields['_entries'], nullable=True),
                    unpack('i', self.read(obj + fields['_count'], 4))[0],
                    unpack('i', self.read(obj + fields['_freeCount'], 4))[0],
                    unpack('i', self.read(obj + fields['_version'], 4))[0])

        before = header()
        entries, count, free, _ = before
        if not 0 <= free <= count <= MAX_SLOTS:
            raise Unsupported('Dictionary size exceeds the supported bounds.')
        if not entries:
            if count or free:
                raise Unsupported('Non-empty dictionary has no entries.')
            if header() != before:
                raise Changing('Dictionary changed during the read.')
            return []
        bounds, capacity = unpack('2Q', self.read(entries + 16, 16))
        if bounds or not count <= capacity <= MAX_SLOTS:
            raise Unsupported('Dictionary array shape exceeds supported bounds.')
        stride = 16 if kind == 'quantities' else 24
        raw = self.read(entries + 32, count * stride) if count else b''
        # Re-read bytes as well as version: rejects a write observed before its
        # version increment. No process suspension or atomicity is claimed.
        again = self.read(entries + 32, count * stride) if count else b''
        if header() != before or raw != again:
            raise Changing('Dictionary changed during the read.')
        result = []
        for i in range(count):
            chunk = raw[i * stride:(i + 1) * stride]
            hash_code, next_slot = unpack('2i', chunk[:8])
            if hash_code < 0:
                continue
            if not -1 <= next_slot < capacity:
                raise Unsupported('Invalid dictionary collision link.')
            if kind == 'quantities':
                key, value = unpack('2I', chunk[8:])
            elif kind == 'scopes':
                key = unpack('i', chunk[8:12])[0]
                value = unpack('Q', chunk[16:])[0]
            else:
                key, value = unpack('2Q', chunk[8:])
            result.append((key, value))
        if len(result) != count - free or len({k for k, _ in result}) != len(result):
            raise Unsupported('Dictionary active-entry accounting is inconsistent.')
        return result


class DeckReader:
    def __init__(self, runtime):
        self.rt = runtime

    def roots(self):
        r = self.rt
        r.checkpoint = 'active_editor'
        wrapper = r.singleton('wrapper', '<Instance>k__BackingField')
        scene = r.object_field(wrapper, 'wrapper', '<SceneLoader>k__BackingField', 'scene')
        nav = r.pointer(scene + r.offset('scene', '<CurrentNavContent>k__BackingField'), nullable=True)
        if not nav or r.pointer(nav) != r.klass('editor'):
            raise NotEditing('The active navigation content is not the supported deck editor.')
        if not r.boolean(nav, 'editor', '_isActive'):
            raise NotEditing('The deck editor is inactive.')
        widget = r.object_field(nav, 'editor', '_deckbuilder', 'widget')
        if not r.boolean(widget, 'widget', '_isActive'):
            raise NotEditing('The deck widget is inactive.')
        r.checkpoint = 'service_registry'
        pantry = r.singleton('pantry', 'instance')
        parent = r.pointer(r.klass('pantry') + 0x58)
        if r.identity(parent) != ('Pantry`1', 'SharedClientCore.SharedClientCore.Code'):
            raise Unsupported('Unexpected service-registry parent.')
        fields = r.fields(parent)
        if '_containers' not in fields:
            raise Unsupported('Service scope table is missing.')
        scopes_obj = r.pointer(pantry + fields['_containers'])
        scopes = r.dictionary(scopes_obj, 'scopes')
        if len(scopes) > 5 or any(not 0 <= k <= 4 for k, _ in scopes):
            raise Unsupported('Unexpected service scopes.')
        found = {'model_provider': set(), 'context_provider': set()}
        service_tables = []
        for _, container in scopes:
            r.check_object(container, 'container')
            refs = r.pointer(container + r.offset('container', '_references'))
            service_tables.append(refs)
            for _, value in r.dictionary(refs, 'services'):
                if value:
                    klass = r.pointer(value)
                    for key in found:
                        if klass == r.klass(key):
                            found[key].add(value)
        if any(len(values) != 1 for values in found.values()):
            raise Unsupported('A unique initialized deck model/context provider was not found.')
        r.checkpoint = 'limited_model'
        provider = next(iter(found['model_provider']))
        context_provider = next(iter(found['context_provider']))
        model = r.object_field(provider, 'model_provider', '_model', 'model')
        context = r.object_field(context_provider, 'context_provider', '_context', 'context')
        if r.boolean(model, 'model', '_isConstructed'):
            raise NotEditing('Constructed editors are outside this Limited prototype.')
        event = r.pointer(context + r.offset('context', 'Event'), nullable=True)
        if not event:
            raise NotEditing('No Limited event context is active.')
        main = r.object_field(model, 'model', '_mainDeck', 'card_list')
        side = r.object_field(model, 'model', '_sideboard', 'card_list')
        return (wrapper, scene, nav, widget, pantry, scopes_obj, tuple(service_tables),
                provider, context_provider, model, context, event, main, side)

    def quantities(self, card_list):
        r = self.rt
        r.checkpoint = 'unfiltered_card_quantities'
        obj = r.pointer(card_list + r.offset('card_list', '_grpQuantityTable'))
        rows = r.dictionary(obj, 'quantities')
        if any(not 0 < card <= 0xFFFFFFFF or not 0 <= qty <= MAX_COPIES for card, qty in rows):
            raise Unsupported('Card ID/quantity is outside supported bounds.')
        rows = sorted((card, qty) for card, qty in rows if qty)
        if sum(q for _, q in rows) > MAX_COPIES:
            raise Unsupported('Pile exceeds the Limited prototype copy limit.')
        return rows

    def snapshot(self):
        for _ in range(3):
            try:
                roots = self.roots()
                main, side = self.quantities(roots[-2]), self.quantities(roots[-1])
                if (roots != self.roots() or main != self.quantities(roots[-2]) or
                        side != self.quantities(roots[-1])):
                    raise Changing('Active editor or pile counts changed between observations.')
                return {'source': 'arena_memory', 'phase': 'limited_deck_builder',
                        'consistency': 'two_matching_observations',
                        'main_deck': [{'grp_id': c, 'quantity': q} for c, q in main],
                        'sideboard': [{'grp_id': c, 'quantity': q} for c, q in side],
                        'main_copies': sum(q for _, q in main), 'sideboard_copies': sum(q for _, q in side)}
            except Changing:
                continue
        raise Changing('No consistent active deck snapshot within three attempts.')


def verify_live_assembly(reader, records, app, image, profile):
    expected = (app / 'Contents/Frameworks/GameAssembly.dylib').resolve()
    matches = [address for address, path, _ in records if Path(reader.path(path)).resolve() == expected]
    if len(matches) != 1:
        raise Unsupported('Expected exactly one matching live GameAssembly.')
    slide = matches[0]  # __TEXT VM address is zero in the pinned build.
    if reader.read(slide, 32 + len(image.commands)) != bytes(image.slice[:32]) + image.commands:
        raise Unsupported('Running assembly load commands/UUID differ from disk.')
    for anchor in profile['code_anchors']:
        data = bytes.fromhex(anchor['hex'])
        if reader.read(slide + anchor['vm'], len(data)) != data:
            raise Unsupported('Running native layout anchor differs from the pinned build.')
    return slide


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('action', choices=['inspect-layout', 'snapshot'])
    parser.add_argument('--app', type=Path, default=transport.DEFAULT_APP)
    parser.add_argument('--pid', type=int)
    parser.add_argument('--session', type=Path, help='Use a separately authorized, expiring local read session.')
    args = parser.parse_args(argv)
    report = {'arena_access_proven': False, 'deck_snapshot': 'unverified'}
    timer = False
    reader = runtime = None
    try:
        app, executable, build = transport.inspect_build(args.app)
        report.update({k: build[k] for k in ('bundle_id', 'app_version', 'runtime', 'metadata_version', 'metadata_sha256')})
        profile = json.loads(PROFILE_PATH.read_text())
        image = inspect_profile(app, report, profile)
        if args.action == 'inspect-layout':
            report.update(status='installed_layout_verified', architecture='arm64',
                          assembly_sha256=profile['assembly_sha256'], supported_phase='limited_deck_builder')
        else:
            if sys.platform != 'darwin':
                raise transport.InvalidTarget('Live process reads require macOS.')
            pid = transport.resolve_pid(executable, args.pid)
            signal.signal(signal.SIGALRM, signal.SIG_DFL)
            signal.setitimer(signal.ITIMER_REAL, transport.MAX_SECONDS + 2)
            timer = True
            if args.session:
                session_spec = importlib.util.spec_from_file_location('arena_read_session', Path(__file__).with_name('read_session.py'))
                session_module = importlib.util.module_from_spec(session_spec)
                session_spec.loader.exec_module(session_module)
                session_module.transport = transport  # Share error types across isolated sibling imports.
                memory_source = session_module.RemoteMemory(args.session, pid)
            else:
                memory_source = transport.DarwinMemory(pid)
            with memory_source as memory:
                if transport.process_path(pid) != executable:
                    raise transport.InvalidTarget('Target changed before reading.')
                reader = transport.ReadBudget(memory.read)
                records = transport.image_snapshot(reader, memory.images_address())
                images = transport.verify_images(reader, records, app)
                if any(i['architecture'] != 'arm64' for i in images):
                    raise Unsupported('Only the reviewed arm64 runtime is supported.')
                slide = verify_live_assembly(reader, records, app, image, profile)
                report.update(arena_access_proven=True, read_api=memory.api)
                runtime = Runtime(reader, slide, profile)
                deck = DeckReader(runtime).snapshot()
                if transport.process_path(pid) != executable:
                    raise transport.InvalidTarget('Target changed during reading.')
                report.update(status='active_limited_deck_read', arena_access_proven=True,
                              deck_snapshot=deck, read_api=memory.api,
                              bytes_read=reader.bytes_requested,
                              elapsed_ms=round((time.monotonic() - reader.started) * 1000, 1))
        print(json.dumps(report, indent=2))
        return 0
    except ProbeError as error:
        report.update(status=error.status, reason=str(error))
        if runtime:
            report['checkpoint'] = runtime.checkpoint
        if reader:
            report['bytes_read'] = reader.bytes_requested
        print(json.dumps(report, indent=2))
        return error.exit_code
    except (OSError, ValueError, KeyError, struct.error) as error:
        # Do not echo backend exceptions that could contain process addresses.
        report.update(status='reader_failed', reason=type(error).__name__)
        print(json.dumps(report, indent=2))
        return 4
    finally:
        if timer:
            signal.setitimer(signal.ITIMER_REAL, 0)


if __name__ == '__main__':
    sys.exit(main())
