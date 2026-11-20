#!/usr/bin/env python3
import importlib.util
import unittest
from datetime import date
from pathlib import Path

spec = importlib.util.spec_from_file_location("sync", Path(__file__).with_name("sync-limited-sets.py"))
sync = importlib.util.module_from_spec(spec)
spec.loader.exec_module(sync)


def product(code, release, kind="expansion"):
    return {"code": code, "released_at": release, "set_type": kind, "name": code.upper()}


def card(name, rarity, colors, keywords=()):
    return {"name": name, "rarity": rarity, "colors": colors, "keywords": keywords}


class LimitedSetSyncTest(unittest.TestCase):
    today = date(2026, 11, 10)

    def test_arena_catalog_then_ratings_then_no_change(self):
        manifest = {"sets": [{"code": "FRA", "tier": "researched"}]}
        products = [
            product("a01", "2026-11-13"), product("far", "2027-02-05"),
            product("trc", "2026-11-13", "commander"), product("pap", "2026-11-15", "masters"),
        ]
        cards = [card(f"Card {i}", "common", "W") for i in range(40)]
        checked = []

        def get_cards(code):
            checked.append(code)
            return cards if code == "A01" else []

        self.assertEqual(["A01 catalog"], sync.sync_sets(manifest, products, self.today, get_cards, lambda _: False))
        self.assertEqual(["A01", "PAP"], checked)
        self.assertFalse(manifest["sets"][0]["ratingsReady"])
        self.assertEqual(["A01 17Lands"], sync.sync_sets(manifest, products, self.today, get_cards, lambda _: True))
        self.assertTrue(manifest["sets"][0]["ratingsReady"])
        self.assertEqual([], sync.sync_sets(manifest, products, self.today, get_cards, lambda _: True))
        self.assertEqual({"code": "FRA", "tier": "researched"}, manifest["sets"][1])

    def test_statistics_outage_does_not_block_the_card_catalog(self):
        manifest = {"sets": []}

        def offline(_):
            raise OSError("test outage")

        changes = sync.sync_sets(
            manifest, [product("new", "2026-11-13")], self.today,
            lambda _: [card(str(i), "common", "") for i in range(40)], offline,
        )
        self.assertEqual(["NEW catalog"], changes)

    def test_suggests_only_shared_mechanics_with_castable_support(self):
        cards = [
            card("A", "uncommon", "WU", ("Flying", "Station")),
            card("B", "uncommon", "UW", ("Flying", "Station")),
            card("C", "common", "U", ("Station",)),
            card("D", "common", "G", ("Station",)),
            card("E", "common", "U", ("Flying",)),
        ] + [card(str(i), "common", "") for i in range(20)]
        self.assertEqual(
            {"WU": {"name": "Station", "signposts": ["A", "B"], "supportingCards": ["C"]}},
            sync.arena_themes(cards),
        )

    def test_extracts_only_announced_future_dates(self):
        page = """<span>Upcoming Sets</span></h2><swiper-container>
        <span>October 2, 2026</span><h3>Reality Fracture</h3>
        <span>February 5, 2027</span><h3>Nauctis &amp; friends</h3></swiper-container>
        <span>May 16, 2027</span><h3>Other product</h3>"""
        self.assertEqual(
            [{"date": "2027-02-05", "name": "Nauctis & friends"}],
            sync.upcoming_wizards(page, self.today),
        )


if __name__ == "__main__":
    unittest.main()
