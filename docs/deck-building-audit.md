# Randomized deck-building validation

The regular `./gradlew test` runs a fast multi-set sample. Run the larger
offline audit (100 seeds, three sets, two pool types = 600 pools) with:

```sh
./gradlew deckAudit
```

Reproduce a seed or use a new range:

```sh
./gradlew deckAudit -PauditSeedStart=17 -PauditSeeds=1
./gradlew deckAudit -PauditSeedStart=100 -PauditSeeds=100
```

Each seed/set runs the existing DraftSimulator with zero delay through the real
EventParser, DraftTracker, repository resolver, and DeckBuilder. It checks all
46 snapshots and the final 45-card pool. A second, uniformly random 45-card pool
with replacement stresses unfocused pools and duplicate copies. Neither sample
uses the advisor to pick cards, so deck selection is tested independently of
the recommendation engine.

Every proposed build is checked for:

- Card and copy conservation against the pool.
- Forty total cards and valid spell/land placement.
- Color legality, including hybrid costs and incomplete legacy metadata.
- Supported splash mana and a qualifying haymaker.
- Deterministic output from the same pool.
- Avoidable missing spells when at least 23 base-color cards exist.
- Conservative local dominance: an omitted card must not beat a selected card
  by more than three percentage points of evidence-adjusted quality while
  preserving color, cost, all recorded roles, and archetype-role tags.

The report is `build/reports/deck-audit.md`; reports are also retained as
`deck-audit-<first-seed>-<seed-count>.md`. Failures identify the set, seed, pool
type, base pair, and splash.

## Findings and changes

The initial audit found an illegal-color build in DSK seed 0: incomplete
metadata recorded a card's heavy black pips but omitted its other required
color. The builder had treated that partial information as complete. It now
preserves printed colors unless exact pip metadata establishes the cost.

The next audit found incomplete builds caused by name-based copy caps. For
example, FRA seed 3 produced a 22-spell UR build from 25 base-color cards, and
BLB seed 7 produced a 22-spell UB build from 24 base-color cards. The builder
also left stronger copies out while seating weaker cards. Copies now compete
individually through the existing quality, curve, and role selection.

After those changes, seeds 0–99 produced 1,319 suggested builds from 600 pools,
with zero findings under the audit checks. A separate validation range, seeds
100–199, produced another 1,319 builds from 600 pools, again with zero findings.
Across both ranges: 1,200 pools, 2,638 builds, and 152 splash builds checked.

## Limits

This audit detects concrete construction and selection regressions. It does
not prove global deck optimality or predict match wins. The demo uses weighted
random picks with a color preference, six seats, and randomly assembled packs;
it does not reproduce booster rarity collation, eight-player draft signals,
or opponent gameplay.

Frozen observations cover FRA, BLB, and DSK. Older metadata intentionally
exercises fallback behavior; see the fixture README for provenance and
coverage. Synergy strength, conditional card interactions, land-count choices,
and complicated fixing effects still require broader validation.
