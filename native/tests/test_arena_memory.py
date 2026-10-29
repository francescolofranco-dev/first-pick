"""Cross-language synthetic IL2CPP graphs; no Arena access or authorization."""
import base64
import importlib.util
import json
import struct
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
    g.popup_manager = popup
    g.field(popup, 'popup_manager', '_activePopup', 0)
    refs = g.dictionary([(g.m.alloc(), obj) for obj in [g.model_provider, g.context_provider, popup]], 'services')
    container = g.object('container')
    g.field(container, 'container', '_references', refs)
    g.m.put(g.pantry + 16, 'Q', g.dictionary([(4, container)], 'scopes'))
    pool = g.object('card_list')
    g.field(pool, 'card_list', '_grpQuantityTable', g.dictionary([(1001, 2), (1002, 1), (1003, 1), (1004, 2), (2001, 17)], 'quantities'))
    g.field(g.model, 'model', '_cardPool', pool)
    return g


def draft_graph():
    g = graph()
    def objects(values):
        klass = g.make_class('List`1', 'System.Collections.Generic', {'_items': 16, '_size': 24, '_version': 28})
        obj = g.m.alloc(); array = g.m.alloc(32 + 8 * len(values))
        g.m.put(obj, 'Q', klass);g.m.put(obj + 16, 'Qii', array, len(values), 1)
        g.m.put(array + 16, 'QQ', 0, len(values))
        for i, value in enumerate(values):g.m.put(array + 32 + i * 8, 'Q', value)
        return obj
    g.objects = objects
    def card(id):
        printing = g.object('card_printing')
        g.m.put(printing + 192, 'I', id)
        wrapper = g.object('card_data');g.field(wrapper, 'card_data', '_printing', printing)
        return wrapper
    def item(id, count):
        obj = g.object('card_collection_item')
        g.field(obj, 'card_collection_item', '<Card>k__BackingField', card(id))
        g.field(obj, 'card_collection_item', '<Quantity>k__BackingField', count, 'i')
        return obj
    def collection(pairs):
        obj = g.object('card_collection');items = [item(id, count) for id, count in pairs]
        g.field(obj, 'card_collection', '_itemList', objects(items));return obj
    g.collection = collection
    g.draft_controller = g.object('draft_controller');g.draft_holder = g.object('draft_holder')
    g.field(g.scene, 'scene', '<CurrentNavContent>k__BackingField', g.draft_controller)
    g.field(g.draft_controller, 'draft_controller', '_active', 1, 'B')
    g.field(g.draft_controller, 'draft_controller', '_okToPickCard', 1, 'B')
    g.field(g.draft_controller, 'draft_controller', '_draftPackHolder', g.draft_holder)
    g.field(g.draft_holder, 'draft_holder', '_isInitialized', 1, 'B')
    pack = collection([(1001, 1), (1002, 2)])
    g.field(g.draft_controller, 'draft_controller', '_packCollection', pack)
    g.field(g.draft_holder, 'draft_holder', '_packCollection', pack)
    g.views=[];g.view_items=[]
    for id in [1002,1001,1002]:
        # DraftPackCardView.CurrentCard is ICardCollectionItem, not CardData.
        current=item(id,1);g.view_items.append(current)
        view=g.object('draft_card_view');g.field(view,'draft_card_view','<CurrentCard>k__BackingField',current);g.views.append(view)
    g.field(g.draft_holder,'draft_holder','_allCardViews',objects(g.views))
    manager=g.object('draft_deck_manager');deck=g.object('draft_deck')
    g.draft_deck=deck
    g.field(g.draft_controller,'draft_controller','_draftDeckManager',manager)
    g.field(manager,'draft_deck_manager','_deck',deck)
    g.field(deck,'draft_deck','_main',collection([(1003,2)]))
    g.field(deck,'draft_deck','_sideboard',collection([(1004,1)]))
    return g


def read(g):
    with tempfile.TemporaryDirectory() as tmp:
        path = Path(tmp) / 'fixture.json'
        path.write_text(json.dumps({'profile': g.profile, 'memory': base64.b64encode(g.m.data[:g.m.cursor]).decode()}))
        result = subprocess.run([str(BINARY), '--fixture', str(path)], capture_output=True, text=True, timeout=5)
        return json.loads(result.stdout)


def draft_geometry_graph():
    g = draft_graph()
    layout = g.profile['unity_geometry']
    for name in ['canvas_vtable_vm', 'rect_transform_vtable_vm', 'box_collider_vtable_vm']:
        layout[name] = g.m.alloc()
    screen = g.m.alloc()
    layout['screen_manager_vm'] = g.m.alloc(8)
    g.m.put(layout['screen_manager_vm'], 'Q', screen)
    g.m.put(screen + 0xb8, 'II', 2560, 1440)
    hierarchy = g.m.alloc(); data = g.m.alloc(4 * 48); parents = g.m.alloc(16)
    g.m.put(hierarchy + 0x18, 'QQ', data, parents)
    for i in range(4):
        g.m.put(data + i * 48, '12f', 0 if i == 0 else -800 + i * 350, 180, 0, 0, 0, 0, 0, 1, 1, 1, 1, 0)
        g.m.put(parents + i * 4, 'i', -1 if i == 0 else 0)
    root = g.m.alloc(); canvas = g.m.alloc(1024)
    g.m.put(root, 'Q', layout['rect_transform_vtable_vm'])
    g.m.put(root + 0xb8, '4f', -960, -540, 1920, 1080)
    g.m.put(canvas, 'Q', layout['canvas_vtable_vm'])
    g.m.put(canvas + 0x2c, 'I', 1)
    def owner(transform, index, components):
        obj = g.m.alloc(); array = g.m.alloc(len(components) * 16)
        g.m.put(obj + 0x20, 'Q', array);g.m.put(obj + 0x30, 'I', len(components));g.m.put(obj + 0x47, 'B', 1)
        for i, component in enumerate(components):
            g.m.put(array + i * 16 + 8, 'Q', component);g.m.put(component + 0x20, 'Q', obj)
        g.m.put(transform + 0x28, 'Q', hierarchy);g.m.put(transform + 0x30, 'I', index)
        g.m.put(transform + 0x68, 'Q', 0 if index == 0 else root)
        return obj
    owner(root, 0, [root, canvas])
    g.geometry_owners=[];g.geometry_transforms=[]
    for i, view in enumerate(g.views, 1):
        transform = g.m.alloc(); behaviour = g.m.alloc(); collider = g.m.alloc()
        g.m.put(transform, 'Q', g.m.alloc())
        g.m.put(behaviour, 'Q', g.m.alloc())
        g.m.put(collider, 'Q', layout['box_collider_vtable_vm'])
        g.m.put(collider + 0x60, '6f', 0, 0, 0, 200, 280, .1)
        obj = owner(transform, i, [transform, behaviour, collider])
        g.m.put(view + 16, 'Q', behaviour)
        managed = g.m.alloc();g.m.put(managed + 16, 'Q', collider)
        g.field(view, 'cdc_meta', '_cardCollider', managed)
        g.geometry_owners.append(obj);g.geometry_transforms.append(transform)
    g.geometry_root=root;g.geometry_data=data
    return g


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


class NativeDraftReaderTests(unittest.TestCase):
    def test_duplicate_pack_ids_follow_actual_card_views_and_preserve_picked_pool(self):
        value=read(draft_graph())
        self.assertEqual('draft_ready',value['status'])
        self.assertEqual([1002,1001,1002],value['cards'])
        self.assertEqual([[1003,2],[1004,1]],value['picked'])
        self.assertNotIn('bounds',value)

    def test_zero_quantity_entries_after_moves_do_not_block_or_count_as_picked(self):
        g=draft_graph()
        g.field(g.draft_deck,'draft_deck','_main',g.collection([(1003,0),(1004,0),(1005,2)]))
        g.field(g.draft_deck,'draft_deck','_sideboard',g.collection([(1003,2),(1004,1),(1005,0)]))
        value=read(g)
        self.assertEqual('draft_ready',value['status'])
        self.assertEqual([[1003,2],[1004,1],[1005,2]],value['picked'])
        self.assertEqual([1002,1001,1002],value['cards'])

    def test_zero_quantity_pack_entries_do_not_require_a_card_or_visible_view(self):
        g=draft_graph()
        pack=g.collection([(1001,1),(1002,2),(1006,0)])
        g.field(g.draft_controller,'draft_controller','_packCollection',pack)
        g.field(g.draft_holder,'draft_holder','_packCollection',pack)
        items=struct.unpack_from('<Q',g.m.data,pack+24)[0]
        array=struct.unpack_from('<Q',g.m.data,items+16)[0]
        empty=struct.unpack_from('<Q',g.m.data,array+32+2*8)[0]
        g.field(empty,'card_collection_item','<Card>k__BackingField',0)
        value=read(g)
        self.assertEqual('draft_ready',value['status'])
        self.assertEqual([1002,1001,1002],value['cards'])

    def test_negative_excessive_and_total_overflow_quantities_emit_no_pack(self):
        for pairs in [[(1003,-1)],[(1003,301)],[(1003,200),(1004,101)]]:
            g=draft_graph()
            g.field(g.draft_deck,'draft_deck','_main',g.collection(pairs))
            with self.subTest(pairs=pairs):
                value=read(g)
                self.assertEqual('unsupported_build',value['status'])
                self.assertNotIn('cards',value)
                self.assertNotIn('picked',value)

    def test_card_view_requires_a_collection_item_containing_a_real_card(self):
        for condition in ['unwrapped_card', 'wrong_item_card']:
            g=draft_graph()
            if condition=='unwrapped_card':
                card_pointer=struct.unpack_from('<Q',g.m.data,g.view_items[0]+16)[0]
                g.field(g.views[0],'draft_card_view','<CurrentCard>k__BackingField',card_pointer)
            else:
                g.field(g.view_items[0],'card_collection_item','<Card>k__BackingField',g.widget)
            with self.subTest(condition=condition):
                value=read(g);self.assertEqual('unsupported_build',value['status']);self.assertNotIn('cards',value)

    def test_animations_dragging_wrong_cards_and_inactive_screen_emit_no_partial_pack(self):
        for condition in ['drag','wrong_card','inactive']:
            g=draft_graph()
            if condition=='animation':g.field(g.draft_holder,'draft_holder','<IsAnimating>k__BackingField',1,'B')
            elif condition=='drag':g.field(g.views[0],'meta_card_view','<IsDragging>k__BackingField',1,'B')
            elif condition=='wrong_card':g.field(g.views[0],'draft_card_view','<CurrentCard>k__BackingField',g.widget)
            elif condition=='inactive':g.field(g.draft_controller,'draft_controller','_active',0,'B')
            else:g.field(g.draft_controller,'draft_controller','_layoutChangeCoroutine',g.m.alloc())
            with self.subTest(condition=condition):
                value=read(g);self.assertNotEqual('draft_ready',value['status']);self.assertNotIn('cards',value)

    def test_position_only_animations_preserve_complete_pack_identity(self):
        for condition in ['animation','layout_change']:
            g=draft_graph()
            if condition=='animation':g.field(g.draft_holder,'draft_holder','<IsAnimating>k__BackingField',1,'B')
            else:g.field(g.draft_controller,'draft_controller','_layoutChangeCoroutine',g.m.alloc())
            with self.subTest(condition=condition):
                value=read(g)
                self.assertEqual('draft_ready',value['status'])
                self.assertEqual([1002,1001,1002],value['cards'])
                self.assertNotIn('geometry',value)
                self.assertIn('geometry_reason',value)

    def test_a_pending_new_pack_explicitly_invalidates_previous_presentation(self):
        g=draft_graph()
        g.field(g.draft_holder,'draft_holder','_pendingPackCollection',g.m.alloc())
        value=read(g)
        self.assertEqual('changing',value['status'])
        self.assertTrue(value['presentation_reset'])

    def test_real_modal_and_disconnect_guards_also_apply_to_picking(self):
        for condition in ['disconnected','dialog','settings','popup']:
            g=draft_graph()
            if condition=='disconnected':g.field(g.tcp_state,'tcp_state','<ConnectState>k__BackingField',1,'i')
            elif condition=='dialog':g.m.put(g.messages_list+16,'i',1)
            elif condition=='settings':g.field(g.menu,'settings_menu','<IsOpen>k__BackingField',1,'B')
            else:
                popup=g.object('popup_base');g.field(popup,'popup_base','<IsShowing>k__BackingField',1,'B')
                g.field(g.popup_manager,'popup_manager','_activePopup',popup)
            with self.subTest(condition=condition):
                value=read(g);self.assertEqual('blocked',value['status']);self.assertNotIn('cards',value)


class NativeGeometryTests(unittest.TestCase):
    def test_horizontal_front_and_single_cards_keep_full_bounds(self):
        for amount in [1,2]:
            g=draft_geometry_graph()
            g.field(g.scene,'scene','<CurrentNavContent>k__BackingField',g.editor)
            pool=g.object('pool_holder');g.field(g.widget,'widget','_poolCardHolder',pool)
            g.field(pool,'pool_holder','_pages',g.objects([]))
            deck_list=g.object('list_view');holder=g.object('list_holder')
            g.field(g.widget,'widget','_deckListView',deck_list)
            g.field(deck_list,'list_view','MainDeckCardHolder',holder)
            inactive=struct.unpack_from('<Q',g.m.data,g.views[2]+16)[0]
            g.m.put(deck_list+16,'Q',inactive);g.m.put(g.geometry_owners[2]+0x47,'B',0)
            columns=g.object('column_view');manager=g.object('draft_column_holder');column=g.object('column_holder')
            g.field(g.widget,'widget','_deckColumnView',columns)
            g.field(columns,'column_view','MainHolder',manager)
            active=struct.unpack_from('<Q',g.m.data,g.views[0]+16)[0]
            g.m.put(columns+16,'Q',active)
            views=[]
            for i in range(amount):
                view=g.object('column_card')
                behaviour=struct.unpack_from('<Q',g.m.data,g.views[i]+16)[0]
                collider=struct.unpack_from('<Q',g.m.data,g.views[i]+g.profile['types']['cdc_meta']['fields']['_cardCollider'])[0]
                card=struct.unpack_from('<Q',g.m.data,g.view_items[i]+16)[0]
                g.m.put(view+16,'Q',behaviour)
                g.field(view,'cdc_meta','_cardCollider',collider)
                g.field(view,'meta_card_view','_cardData',card)
                g.field(view,'column_card','_quantity',1,'i')
                g.m.put(g.geometry_data+(i+1)*48,'2f',-300,180-i*40)
                views.append(view)
            g.field(column,'column_holder','_cardViews',g.objects(views))
            g.field(manager,'column_manager','_allColumns',g.objects([column]))
            scroll=g.object('scroll_rect');viewport=g.m.alloc()
            g.m.put(viewport+16,'Q',g.geometry_root)
            g.field(scroll,'scroll_rect','m_Viewport',viewport)
            g.field(manager,'column_manager','scrollRect',scroll)
            with self.subTest(cards=amount):
                value=read(g)
                self.assertEqual('ready',value['status'])
                rows=value['geometry']['cards']
                self.assertEqual(amount,len(rows))
                self.assertAlmostEqual(280/1080,rows[-1][6])
                if amount==2:self.assertAlmostEqual(40/1080,rows[0][6])

    def test_exact_duplicate_ids_have_separate_projected_card_rectangles(self):
        value = read(draft_geometry_graph())
        self.assertEqual('draft_ready', value['status'])
        geometry = value['geometry']
        self.assertEqual([2560, 1440], geometry['screen'])
        self.assertEqual([1002, 1001, 1002], [row[0] for row in geometry['cards']])
        self.assertEqual(3, len({row[3] for row in geometry['cards']}))
        self.assertAlmostEqual(200 / 1920, geometry['cards'][0][5])
        self.assertAlmostEqual((540 - 180 - 140) / 1080, geometry['cards'][0][4])

    def test_hidden_dirty_or_invalid_transforms_do_not_emit_positions(self):
        for condition in ['hidden', 'dirty_rect', 'parent_cycle', 'quaternion']:
            g = draft_geometry_graph()
            if condition == 'hidden':g.m.put(g.geometry_owners[0] + 0x47, 'B', 0)
            elif condition == 'dirty_rect':g.m.put(g.geometry_root + 0xf1, 'B', 1)
            elif condition == 'parent_cycle':g.m.put(g.geometry_transforms[0] + 0x68, 'Q', g.geometry_transforms[0])
            else:g.m.put(g.geometry_data + 48 + 7 * 4, 'f', 0)
            with self.subTest(condition=condition):
                value = read(g)
                self.assertEqual('draft_ready', value['status'])
                self.assertNotIn('geometry', value)

    def test_vertical_layout_uses_the_whole_row_including_its_quantity_counter(self):
        g = draft_geometry_graph()
        g.field(g.scene,'scene','<CurrentNavContent>k__BackingField',g.editor)
        pool=g.object('pool_holder');g.field(g.widget,'widget','_poolCardHolder',pool)
        g.field(pool,'pool_holder','_pages',g.objects([]))
        deck_list=g.object('list_view');holder=g.object('list_holder');row=g.object('list_row')
        g.field(g.widget,'widget','_deckListView',deck_list)
        g.field(deck_list,'list_view','MainDeckCardHolder',holder)
        behaviour=struct.unpack_from('<Q',g.m.data,g.views[0]+16)[0]
        g.m.put(deck_list+16,'Q',behaviour);g.m.put(row+16,'Q',behaviour)
        transform=g.geometry_transforms[0]
        g.m.put(transform,'Q',g.profile['unity_geometry']['rect_transform_vtable_vm'])
        g.m.put(transform+0xb8,'4f',-130,-16,260,32)
        item=struct.unpack_from('<Q',g.m.data,g.views[0]+456)[0]
        card=struct.unpack_from('<Q',g.m.data,item+16)[0]
        g.field(row,'meta_card_view','_cardData',card)
        g.field(row,'list_row','<Quantity>k__BackingField',1,'i')
        g.field(holder,'list_holder','_tiles',g.objects([row]))
        scroll=g.object('scroll_rect');viewport=g.m.alloc()
        g.m.put(viewport+16,'Q',g.geometry_root)
        g.field(scroll,'scroll_rect','m_Viewport',viewport)
        g.field(holder,'meta_holder','ScrollRect',scroll)
        columns=g.object('column_view')
        g.field(g.widget,'widget','_deckColumnView',columns)
        inactive=struct.unpack_from('<Q',g.m.data,g.views[2]+16)[0]
        g.m.put(columns+16,'Q',inactive);g.m.put(g.geometry_owners[2]+0x47,'B',0)
        value=read(g)
        self.assertEqual('ready',value['status'])
        position=value['geometry']['cards'][0]
        self.assertEqual([1002,1,'deck'],position[:3])
        self.assertAlmostEqual(260/1920,position[5])
        self.assertAlmostEqual(32/1080,position[6])


if __name__ == '__main__':
    unittest.main()
