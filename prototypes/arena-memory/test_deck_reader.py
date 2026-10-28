import copy
import importlib.util
import json
from pathlib import Path
import struct
import tempfile
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location('arena_decks', Path(__file__).with_name('deck_reader.py'))
decks = importlib.util.module_from_spec(spec)
spec.loader.exec_module(decks)
PROFILE = json.loads(decks.PROFILE_PATH.read_text())


class Memory:
    """Synthetic IL2CPP graph, not recorded Arena/account memory."""
    def __init__(self):
        self.data = bytearray(4 * 1024 * 1024)
        self.cursor = 4096
        self.calls = 0
        self.hook = None

    def alloc(self, size=512):
        address = self.cursor
        self.cursor += (size + 7) // 8 * 8
        return address

    def put(self, address, fmt, *args):
        raw = struct.pack('<' + fmt, *args)
        self.data[address:address + len(raw)] = raw

    def string(self, value):
        # Metadata strings are packed bytes, unlike object/array allocations.
        address = self.alloc(len(value) + 2) + 1
        self.data[address:address + len(value)] = value.encode()
        return address

    def read(self, address, size):
        self.calls += 1
        if self.hook:
            self.hook(address, size)
        if address < 4096 or address + size > self.cursor:
            raise decks.ProbeError('Synthetic unmapped region.')
        return bytes(self.data[address:address + size])


class Graph:
    def __init__(self):
        self.m = Memory()
        self.profile = copy.deepcopy(PROFILE)
        self.profile['class_table_vm'] = self.m.alloc(8)
        table = self.m.alloc(38442 * 8)
        self.m.put(self.profile['class_table_vm'], 'Q', table)
        self.classes = {}
        for key, t in self.profile['types'].items():
            klass = self.make_class(t['name'], t['namespace'])
            self.m.put(table + t['index'] * 8, 'Q', klass)
            self.classes[key] = klass
        parent = self.make_class('Pantry`1', 'SharedClientCore.SharedClientCore.Code', {'_containers': 16, '_containersByType': 24})
        self.m.put(self.classes['pantry'] + 0x58, 'Q', parent)
        self.wrapper = self.singleton('wrapper', '<Instance>k__BackingField')
        self.pantry = self.singleton('pantry', 'instance')
        self.scene = self.object('scene')
        self.editor = self.object('editor')
        self.widget = self.object('widget')
        self.model_provider = self.object('model_provider')
        self.context_provider = self.object('context_provider')
        self.model = self.object('model')
        self.context = self.object('context')
        self.main = self.object('card_list')
        self.side = self.object('card_list')
        for obj, key, field, target in [
                (self.wrapper, 'wrapper', '<SceneLoader>k__BackingField', self.scene),
                (self.scene, 'scene', '<CurrentNavContent>k__BackingField', self.editor),
                (self.editor, 'editor', '_deckbuilder', self.widget),
                (self.model_provider, 'model_provider', '_model', self.model),
                (self.context_provider, 'context_provider', '_context', self.context),
                (self.model, 'model', '_mainDeck', self.main),
                (self.model, 'model', '_sideboard', self.side),
                (self.context, 'context', 'Event', self.m.alloc())]:
            self.field(obj, key, field, target)
        for obj, key in [(self.editor, 'editor'), (self.widget, 'widget')]:
            self.field(obj, key, '_isActive', 1, 'B')
        refs = self.dictionary([(self.m.alloc(), self.model_provider), (self.m.alloc(), self.context_provider)], 'services')
        container = self.object('container')
        self.field(container, 'container', '_references', refs)
        scopes = self.dictionary([(4, container)], 'scopes')
        self.m.put(self.pantry + 16, 'Q', scopes)
        self.main_dict = self.dictionary([(1001, 2), (1002, 1), (2001, 17)], 'quantities')
        self.side_dict = self.dictionary([(1003, 1), (1004, 2), (1005, 0)], 'quantities')
        self.field(self.main, 'card_list', '_grpQuantityTable', self.main_dict)
        self.field(self.side, 'card_list', '_grpQuantityTable', self.side_dict)
        self.runtime = decks.Runtime(decks.transport.ReadBudget(self.m.read), 0, self.profile)
        self.reader = decks.DeckReader(self.runtime)

    def make_class(self, name, namespace='', fields=None):
        klass = self.m.alloc()
        self.m.put(klass + 16, '2Q', self.m.string(name), self.m.string(namespace))
        if fields:
            array = self.m.alloc(32 * len(fields))
            self.m.put(klass + 0x80, 'Q', array)
            self.m.put(klass + 0x124, 'H', len(fields))
            for i, (name, offset) in enumerate(fields.items()):
                self.m.put(array + i * 32, 'QQQiI', self.m.string(name), 0, klass, offset, 0)
        return klass

    def object(self, key):
        obj = self.m.alloc()
        self.m.put(obj, 'Q', self.classes[key])
        return obj

    def singleton(self, key, field):
        obj = self.object(key)
        storage = self.m.alloc()
        self.m.put(self.classes[key] + 0xB8, 'Q', storage)
        self.field(storage, key, field, obj)
        return obj

    def field(self, obj, key, field, value, fmt='Q'):
        self.m.put(obj + self.profile['types'][key]['fields'][field], fmt, value)

    def dictionary(self, pairs, kind, *, free=0):
        fields = {'_buckets': 16, '_entries': 24, '_count': 32, '_freeList': 36, '_freeCount': 40, '_version': 44}
        klass = self.make_class('Dictionary`2', 'System.Collections.Generic', fields)
        generic = self.m.alloc(32)
        inst = self.m.alloc(16)
        args = self.m.alloc(16)
        self.m.put(klass + 0x20, 'QII', generic, 21 << 16, 0)
        self.m.put(generic + 8, 'Q', inst)
        self.m.put(inst, '2Q', 2, args)
        for i, arg in enumerate({'quantities': (9, 9), 'scopes': (17, 18), 'services': (18, 28)}[kind]):
            typ = self.m.alloc(16)
            self.m.put(typ, 'QII', 0, arg << 16, 0)
            self.m.put(args + i * 8, 'Q', typ)
        obj = self.m.alloc()
        self.m.put(obj, 'Q', klass)
        stride = 16 if kind == 'quantities' else 24
        count = len(pairs) + free
        arr = self.m.alloc(32 + stride * count)
        self.m.put(arr + 16, '2Q', 0, count)
        self.m.put(obj + 24, 'Q', arr)
        self.m.put(obj + 32, '4i', count, -1, free, 1)
        for i, (key, value) in enumerate(pairs):
            address = arr + 32 + i * stride
            self.m.put(address, '2i', 1, -1)
            if kind == 'quantities':
                self.m.put(address + 8, '2I', key, value)
            elif kind == 'scopes':
                self.m.put(address + 8, 'i', key)
                self.m.put(address + 16, 'Q', value)
            else:
                self.m.put(address + 8, '2Q', key, value)
        for i in range(len(pairs), count):
            self.m.put(arr + 32 + i * stride, 'i', -1)
        return obj


class DeckTests(unittest.TestCase):
    def test_byte_aligned_metadata_names_work_without_relaxing_object_alignment(self):
        g = Graph()
        for klass in g.classes.values():
            name, namespace = struct.unpack_from('<2Q', g.m.data, klass + 16)
            self.assertNotEqual(0, name % 8)
            self.assertNotEqual(0, namespace % 8)
        self.assertEqual(20, g.reader.snapshot()['main_copies'])
        g.field(g.model_provider, 'model_provider', '_model', g.model + 1)
        with self.assertRaisesRegex(decks.Unsupported, 'model_provider._model pointer'):
            g.reader.snapshot()

    def test_unfiltered_piles_preserve_exact_ids_duplicates_and_zero_entries(self):
        g = Graph()
        # Deliberately misleading filtered lists must never influence the read.
        g.m.put(g.main + 16, 'Q', 0xDEADBEEF)
        result = g.reader.snapshot()
        self.assertEqual([{'grp_id': 1001, 'quantity': 2}, {'grp_id': 1002, 'quantity': 1},
                          {'grp_id': 2001, 'quantity': 17}], result['main_deck'])
        self.assertEqual(20, result['main_copies'])
        self.assertEqual(3, result['sideboard_copies'])
        self.assertEqual([1003, 1004], [r['grp_id'] for r in result['sideboard']])

    def test_edit_is_read_fresh_and_not_accumulated_from_previous_observations(self):
        g = Graph()
        self.assertEqual(20, g.reader.snapshot()['main_copies'])
        replacement = g.dictionary([(1002, 2), (2001, 17)], 'quantities')
        g.field(g.main, 'card_list', '_grpQuantityTable', replacement)
        after = g.reader.snapshot()
        self.assertEqual(19, after['main_copies'])
        self.assertEqual([1002, 2001], [r['grp_id'] for r in after['main_deck']])

    def test_inactive_other_or_constructed_editors_do_not_emit_a_deck(self):
        for condition in ['inactive', 'other_scene', 'constructed', 'no_event']:
            g = Graph()
            if condition == 'inactive':
                g.field(g.editor, 'editor', '_isActive', 0, 'B')
            elif condition == 'other_scene':
                g.field(g.scene, 'scene', '<CurrentNavContent>k__BackingField', g.widget)
            elif condition == 'constructed':
                g.field(g.model, 'model', '_isConstructed', 1, 'B')
            else:
                g.field(g.context, 'context', 'Event', 0)
            with self.subTest(condition=condition), self.assertRaises(decks.NotEditing):
                g.reader.snapshot()

    def test_old_or_wrong_model_object_is_rejected(self):
        g = Graph()
        g.field(g.model_provider, 'model_provider', '_model', g.context)
        with self.assertRaises(decks.Unsupported):
            g.reader.snapshot()

    def test_equal_total_swap_between_observations_is_retried(self):
        g = Graph()
        calls = 0
        original = g.reader.quantities
        def quantities(pile):
            nonlocal calls
            calls += 1
            rows = original(pile)
            if calls == 1:
                replacement = g.dictionary([(1001, 1), (1002, 2), (2001, 17)], 'quantities')
                g.field(g.main, 'card_list', '_grpQuantityTable', replacement)
            return rows
        with patch.object(g.reader, 'quantities', side_effect=quantities):
            result = g.reader.snapshot()
        self.assertGreater(calls, 4)
        self.assertEqual({'grp_id': 1001, 'quantity': 1}, result['main_deck'][0])

    def test_continuous_change_never_returns_a_trusted_snapshot(self):
        g = Graph()
        with patch.object(g.reader, 'quantities', side_effect=decks.Changing('changing')):
            with self.assertRaises(decks.Changing):
                g.reader.snapshot()

    def test_freed_slots_are_excluded_and_accounting_must_match(self):
        g = Graph()
        obj = g.dictionary([(4, 2)], 'quantities', free=2)
        self.assertEqual([(4, 2)], g.runtime.dictionary(obj, 'quantities'))
        g.m.put(obj + 40, 'i', 1)
        with self.assertRaises(decks.Unsupported):
            g.runtime.dictionary(obj, 'quantities')

    def test_dictionary_shape_types_duplicate_keys_and_quantities_fail_closed(self):
        g = Graph()
        for pairs in [[(1001, 1), (1001, 2)], [(1001, 301)]]:
            obj = g.dictionary(pairs, 'quantities')
            g.field(g.main, 'card_list', '_grpQuantityTable', obj)
            with self.assertRaises(decks.Unsupported):
                g.reader.quantities(g.main)
        obj = g.dictionary([(1, g.model_provider)], 'scopes')
        with self.assertRaises(decks.Unsupported):
            g.runtime.dictionary(obj, 'quantities')
        g.m.put(obj + 32, 'i', decks.MAX_SLOTS + 1)
        with self.assertRaises(decks.Unsupported):
            g.runtime.dictionary(obj, 'scopes')

    def test_dictionary_change_before_version_increment_is_detected(self):
        g = Graph()
        arr = struct.unpack_from('<Q', g.m.data, g.main_dict + 24)[0]
        def change(address, size):
            if address == arr + 32:
                old = struct.unpack_from('<I', g.m.data, arr + 44)[0]
                g.m.put(arr + 44, 'I', 1 if old == 2 else 2)
        g.m.hook = change
        with self.assertRaises(decks.Changing):
            g.reader.snapshot()

    def test_uninitialized_class_and_invalid_pointer_are_rejected(self):
        g = Graph()
        for value in [0, 1, 1 << 48]:
            g.m.put(g.wrapper, 'Q', value)
            with self.assertRaises(decks.Unsupported):
                g.reader.snapshot()
        calls = g.m.calls
        with self.assertRaises(decks.Unsupported):
            g.runtime.read(1 << 48, 8)
        self.assertEqual(calls, g.m.calls)

    def test_changed_build_is_rejected_before_reading_assembly(self):
        report = {'app_version': 'new', 'metadata_version': 39, 'metadata_sha256': PROFILE['metadata_sha256']}
        with patch.object(decks, 'MachImage', side_effect=AssertionError('must not open assembly')):
            with self.assertRaises(decks.Unsupported):
                decks.inspect_profile(Path('/absent'), report, PROFILE)

    def test_invalid_macho_bounds_fail_closed(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / 'image'
            for data in [b'', struct.pack('>II', 0xCAFEBABE, 99) + b'\0' * 32,
                         struct.pack('<8I', 0xFEEDFACF, 0x100000C, 0, 6, 1, 8, 0, 0) + struct.pack('<2I', 1, 7)]:
                path.write_bytes(data)
                with self.assertRaises(decks.Unsupported):
                    decks.MachImage(path)

    def test_static_metadata_validation_detects_wrong_field_offsets(self):
        names = b'Foo\0\0field\0'
        field_start = 380 + len(names)
        type_start = field_start + 12
        data = bytearray(type_start + 82)
        struct.pack_into('<2I', data, 0, 0xFAB11BAF, 39)
        for index, values in [(2, (380, len(names), len(names))),
                              (11, (field_start, 12, 1)), (19, (type_start, 82, 1))]:
            struct.pack_into('<3I', data, 8 + index * 12, *values)
        data[380:380 + len(names)] = names
        struct.pack_into('<3I', data, field_start, 5, 0, 0)
        definition = [0] * 25
        definition[1], definition[17] = 4, 1
        struct.pack_into('<5IH9I8H2I', data, type_start, *definition)
        profile = {'field_offsets_vm': 1024, 'types': {'foo': {
            'index': 0, 'name': 'Foo', 'namespace': '', 'fields': {'field': 16}}}}
        class Image:
            def read(self, address, size):
                return {1024: struct.pack('<Q', 2048), 2048: struct.pack('<i', 16)}[address]
        decks.verify_metadata_fields(data, Image(), profile)
        profile['types']['foo']['fields']['field'] = 24
        with self.assertRaises(decks.Unsupported):
            decks.verify_metadata_fields(data, Image(), profile)
        struct.pack_into('<3I', data, 8 + 19 * 12, len(data), 82, 1)
        with self.assertRaises(decks.Unsupported):
            decks.verify_metadata_fields(data, Image(), profile)

    def test_live_load_command_and_native_code_mismatch_rejects_before_objects(self):
        class Image:
            slice = b'H' * 32
            commands = b'UUID1234'
        class Reader:
            def __init__(self):
                self.header = b'H' * 32 + b'UUID1234'
                self.code = b'ABCD'
            def path(self, address):
                return '/arena/Contents/Frameworks/GameAssembly.dylib'
            def read(self, address, size):
                return self.header if address == 4096 else self.code
        profile = {'code_anchors': [{'vm': 256, 'hex': b'ABCD'.hex()}]}
        reader = Reader()
        self.assertEqual(4096, decks.verify_live_assembly(reader, [(4096, 1, 0)], Path('/arena'), Image(), profile))
        reader.header = b'H' * 32 + b'UUID5678'
        with self.assertRaises(decks.Unsupported):
            decks.verify_live_assembly(reader, [(4096, 1, 0)], Path('/arena'), Image(), profile)
        reader.header, reader.code = b'H' * 32 + b'UUID1234', b'EFGH'
        with self.assertRaises(decks.Unsupported):
            decks.verify_live_assembly(reader, [(4096, 1, 0)], Path('/arena'), Image(), profile)

    def test_success_output_contains_only_deck_data_and_no_addresses_or_names(self):
        result = Graph().reader.snapshot()
        self.assertEqual({'source', 'phase', 'consistency', 'main_deck', 'sideboard', 'main_copies', 'sideboard_copies'}, set(result))
        self.assertEqual({'grp_id', 'quantity'}, set(result['main_deck'][0]))


if __name__ == '__main__':
    unittest.main()
