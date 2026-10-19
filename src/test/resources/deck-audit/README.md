# Frozen deck audit inputs

Compact observations exported from the existing FirstPick local cache on
2026-10-20. Only fields needed for simulation and deck construction are retained.
Tests never consult the user's cache or fetch current data.

| Set | Cards | Ratings input | Metadata input |
| --- | ---: | --- | --- |
| FRA | 290 | ratings3_FRA_PremierDraft.json | scryfall8_FRA.json |
| BLB | 271 | ratings3_BLB_PremierDraft.json | scryfall6_BLB.json |
| DSK | 281 | ratings3_DSK_PremierDraft.json | scryfall6_DSK.json |

Rows require an Arena ID and matching metadata. Rating fields include identity,
rarity, types, GIH win rate, game count, and improvement when drawn. Metadata
includes curve, roles, mana production, and available mana symbols.

FRA has exact printed costs. The older BLB and DSK metadata intentionally
exercises the supported legacy color/pip fallback. These snapshots do not
provide equal coverage of current impact classification. Exact pip/timing
behavior also has separate ManaSources regression tests.

Ratings: [17Lands](https://www.17lands.com/), CC-BY-4.0.
Card facts: [Scryfall](https://scryfall.com/).
No fixture contains rules that privilege a named card or set.
