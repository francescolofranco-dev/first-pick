"""Cross-language synthetic IL2CPP graphs; no Arena access or authorization."""
import base64
import importlib.util
import json
from pathlib import Path
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location('graph_tests', ROOT / 'prototypes/arena-memory/test_deck_reader.py')
fixtures = importlib.util.module_from_spec(spec)
spec.loader.exec_module(fixtures)
fixtures.PROFILE = json.loads((ROOT / 'native/macos/arena-memory-layout.json').read_text())
BINARY = ROOT / 'src/main/resources/native/macos/arena-memory'


def graph():
    g = fixtures.Graph()
    wrapper = g.object('connection_wrapper')
    connection = g.object('connection')
    tcp = g.object('tcp')
    state = g.object('tcp_state')
    for obj, key, field, value in [
        (g.wrapper, 'wrapper', '<FrontDoorConnectionServiceWrapper>k__BackingField', wrapper),
        (wrapper, 'connection_wrapper', '_frontDoorConnectionAWS', connection),
        (connection, 'connection', '_tcpConn', tcp), (tcp, 'tcp', '_state', state)]:
        g.field(obj, key, field, value)
    g.field(state, 'tcp_state', '<ConnectState>k__BackingField', 4, 'i')
    g.tcp_state = state
    host = g.object('settings_host')
    g.menu = g.object('settings_menu')
    g.field(g.wrapper, 'wrapper', '<SettingsMenuHost>k__BackingField', host)
    g.field(host, 'settings_host', '_menuInstance', g.menu)
    g.field(g.menu, 'settings_menu', '<IsOpen>k__BackingField', 0, 'B')
    messages = g.singleton('messages', '_instance')
    list_class = g.make_class('List`1', 'System.Collections.Generic', {'_size': 16})
    g.messages_list = g.m.alloc()
    g.m.put(g.messages_list, 'Q', list_class)
    g.m.put(g.messages_list + 16, 'i', 0)
    g.field(messages, 'messages', '_messages', g.messages_list)
    popup = g.object('popup_manager')
    g.field(popup, 'popup_manager', '_activePopup', 0)
    refs = g.dictionary([(g.m.alloc(), obj) for obj in [g.model_provider, g.context_provider, popup]], 'services')
    container = g.object('container')
    g.field(container, 'container', '_references', refs)
    g.m.put(g.pantry + 16, 'Q', g.dictionary([(4, container)], 'scopes'))
    pool = g.object('card_list')
    g.field(pool, 'card_list', '_grpQuantityTable', g.dictionary([(1001, 2), (1002, 1), (1003, 1), (1004, 2), (2001, 17)], 'quantities'))
    g.field(g.model, 'model', '_cardPool', pool)
    return g


def read(g):
    with tempfile.TemporaryDirectory() as tmp:
        path = Path(tmp) / 'fixture.json'
        path.write_text(json.dumps({'profile': g.profile, 'memory': base64.b64encode(g.m.data[:g.m.cursor]).decode()}))
        result = subprocess.run([str(BINARY), '--fixture', str(path)], capture_output=True, text=True, timeout=5)
        return json.loads(result.stdout)


class NativeDeckReaderTests(unittest.TestCase):
    def test_exact_quantities_and_pool_are_read_by_the_native_implementation(self):
        value = read(graph())
        self.assertEqual('ready', value['status'])
        self.assertTrue(value['fixture'])
        self.assertEqual([[1001, 2], [1002, 1], [2001, 17]], value['main'])
        self.assertEqual([[1003, 1], [1004, 2]], value['sideboard'])
        self.assertEqual(5, len(value['pool']))
        self.assertEqual(20, value['total'])
        self.assertEqual(64, len(value['editor_session']))

    def test_disconnection_dialog_and_settings_prevent_guidance(self):
        for condition in ['disconnected', 'dialog', 'settings']:
            g = graph()
            if condition == 'disconnected': g.field(g.tcp_state, 'tcp_state', '<ConnectState>k__BackingField', 1, 'i')
            elif condition == 'dialog': g.m.put(g.messages_list + 16, 'i', 1)
            else: g.field(g.menu, 'settings_menu', '<IsOpen>k__BackingField', 1, 'B')
            with self.subTest(condition=condition):
                result = read(g)
                self.assertEqual('blocked', result['status'])
                self.assertNotIn('main', result)

    def test_invalid_quantities_and_wrong_editor_fail_without_partial_cards(self):
        g = graph()
        g.field(g.scene, 'scene', '<CurrentNavContent>k__BackingField', g.widget)
        self.assertEqual('outside_builder', read(g)['status'])
        g = graph()
        g.field(g.main, 'card_list', '_grpQuantityTable', g.dictionary([(1001, 301)], 'quantities'))
        result = read(g)
        self.assertEqual('unsupported_build', result['status'])
        self.assertNotIn('main', result)


if __name__ == '__main__':
    unittest.main()
