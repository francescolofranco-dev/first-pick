# Overlay improvement implementation proposal

Status: ready-for-agent

This plan implements the confirmed decisions in [spec.md](spec.md), including
Q8's acceptance targets. The user requested visual examples next. Those examples
illustrate the proposed appearance; no application behavior has changed during
the design interview or preview work.

## Intended result

Drafting keeps a grade and score badge at the bottom of each card. Deck building
uses a translucent green highlight over whole pool cards that need adding and
over whole deck rows needing additional copies; deck rows needing cuts are red.
The horizontal deck layout uses the full exposed entry band. There are no
individual +1/+2/−1 badges. Both layouts remain click-through and preserve
readable native names, quantities and mana symbols.

Ordinary interaction must not make the overlay blink. Confirmed advice stays
steady; changes to card state and positions update coherently. A compact deck
summary includes named basic-land changes and counts work on off-screen cards.
FirstPick supplies the full named change list. A verified matching deck briefly
shows success and then fades that summary.

Ranking and deck-recommendation algorithms stay unchanged. Hover explanations
and other in-game controls are deferred. Live card state and geometry remain
memory-only.

## 1. Establish a reproducible baseline

- Record bounded local traces of typed memory observations, validation results,
  geometry, presentation decisions and monotonic timings. Reuse sanitized
  fixture conventions; avoid raw process dumps and account/session credentials.
- Reproduce the known pack-stability issue with unchanged card identities and
  changed positions. Cover one missed reading, hover, scrolling, window moves,
  resizing, layout changes and actual pack changes separately.
- Record baseline visible clearing/reappearing cycles and update timings before
  changing the implementation. Helper read time alone is not a measure of
  action-to-visible-update latency.

Relevant code: MemoryPackGuidance.kt, MemoryDeckGuidance.kt,
ArenaOverlayTracker.kt, ArenaDeckBuilderOverlay.kt and
ArenaMemoryOverlayWindow.kt under src/main/kotlin/com/firstpick/overlay/;
native/macos/arena-memory.swift and native/macos/arena-geometry.swift.

## 2. Separate card advice from its current positions

- Identify a pack by the actual editor/pack identity, ordered card IDs and copy
  counts, and drafted pool. Geometry changes alone must not create a new pack or
  invalidate its scores.
- Apply the same separation in native coherence checks and Kotlin validation.
  Preserve the supported-build, complete-pack/pool and exact-copy checks.
- Keep semantic validity, position validity and connection health distinct. A
  temporary position update must not erase verified deck counts or scores.
- Replace the displayed frame coherently. Do not clear the whole overlay before
  publishing another valid observation of the same cards.
- Distinguish routine motion and isolated read failures from confirmed pack or
  editor changes, leaving the scene and sustained loss of trustworthy data.
  Never carry a previous pack's scores onto a different pack.
- Define bounded retention and invalidation from these typed conditions. Tune
  grace periods against recorded interaction traces; avoid arbitrary timers
  that mask a genuine scene change or repeatedly blank normal interaction.

Verify that unchanged observations do not cause resets, position-only changes
preserve advice, and actual identity changes invalidate the affected advice.
Check both focus recovery and window/viewport alignment, rather than validating
counts while accidentally rendering them in an old window rectangle.

## 3. Validate complete card and deck-entry geometry through memory

The latest visual review replaces name-only tints with whole-card and whole-row
highlights. Production already has live evidence for pool-card colliders and
complete vertical deck-row bounds, so exact title-text mapping is no longer a
prerequisite for this presentation.

1. Validate complete vertical row bounds, including the native quantity and mana
   regions. Apply a translucent tint over the row rather than adding labels in
   the gap beside it.
2. Validate complete pool-card bounds and clip the green wash to the card and
   actual pool viewport. A pool-only card must never receive a red deck-cut cue.
3. Validate the full exposed deck-entry bands in horizontal stacks. Handle
   sparse columns and columns containing one card explicitly; the current
   spacing-derived geometry must not silently omit these entries.
4. Keep exact card ID, quantity, viewport and geometry in a coherent observation.
   Include typed reasons for unavailable geometry for both draft and deck views.
5. Verify full bounds and clipping during scrolling, selection/hover, resizing,
   alternate faces and layout switches. Reject another card's geometry or a
   stale viewport. Do not tint neighboring rows or obscured stacked areas.

Use the existing authorized read-only connection when available. If a process
restart ends it, the user completes the existing macOS grant flow; this proposal
does not introduce broader permissions or an alternative screen reader.

If complete, correctly clipped entry geometry cannot be demonstrated on a
surface, keep that support explicitly incomplete. Do not approximate positions
from screenshots or fixed fractions and claim the highlight is finished.

## 4. Render full-card and full-row highlights

- Replace detached deck labels with the agreed translucent whole-card/row
  treatment. Arena's text is already rendered beneath the external overlay;
  choose tint opacity and a restrained edge treatment that preserve legibility.
- Remove individual signed copy-change indicators, keeping Arena's native
  quantity counters and mana symbols readable through the tint.
- Continue computing exact remaining copy differences for the aggregate bar
  and FirstPick's detailed comparison. Adding one of two required copies leaves
  the green highlight in place; adding the second removes it. The aggregate
  count updates after each confirmed edit.
- Apply green Add guidance to needed pool cards and red Cut guidance to excess
  deck copies. Existing deck entries needing more copies may also show their
  positive remaining change. Do not mark a sideboard/pool-only card as a deck cut.
- Map by exact card ID and canonical catalog identity. Two cards with the same
  visible ellipsized prefix must remain independent.
- Keep draft crests visually as agreed, changing their stability and positioning
  behavior without changing score computation.

Use renderer checks with actual observed geometry as well as constructed edge
cases. Inspect light, dark and multicolor frames and selected/hovered cards;
pixel assertions alone do not establish readability.

## 5. Make summary and health reflect what the user can see

- Calculate remaining work from the complete current deck versus the selected
  suggested build. The number of visible highlights must not determine whether
  the deck matches.
- Keep off-screen and filtered-out changes in the compact total. FirstPick
  exposes the full card names, add/cut direction and exact copy changes from the
  same comparison, with a compact in-game pointer when needed.
- Name specific basic lands, including the number of copies to add or cut.
- Briefly confirm a verified matching deck and then fade the summary. A later
  confirmed edit restores it with the new remaining changes.
- Define health consistently for drafting and deck building: distinguish valid
  advice with usable positions from valid counts without renderable positions.
  Do not report fully positioned advice as synced when it cannot be drawn.
- Map technical reader failures to small, plain-language product messages.
  Detailed reasons, affected surface and recovery actions belong in FirstPick.
  Routine interaction and a single missed read must not trigger large warnings.

## 6. Acceptance and timing

The following coverage and numerical target were confirmed in Q8; they are not
performance results already achieved.

| Surface | Required scenarios |
| --- | --- |
| Drafting | Hover, selection, card movement, actual next-pack transition, identical copies, pick-two fixtures, score/ID association and unrated cards |
| Vertical deck layout | Scrolling, filtering, duplicates, single-copy and last-copy changes, basic-land changes, truncated names and readable full-card/row tint |
| Horizontal deck layout | The same edits, sparse/single-card columns, stacked overlap, exposed-entry clipping and pool scrolling |
| Window and recovery | Window movement, supported size/scale changes, fullscreen, layout switch, focus changes, genuine scene exit, isolated read failures and sustained loss/recovery |

Acceptance requirements:

- No incorrect card association, add/cut direction, aggregate copy total or
  detailed FirstPick copy delta in the test corpus; no stale draft score
  attached to a new card. No individual change badges appear in Arena.
- No visible clearing/reappearing cycles caused solely by normal interaction
  with unchanged card state and still-valid positions.
- Card names, Arena quantity counters and mana symbols remain readable; all
  highlights are clipped to the correct card and viewport.
- Off-screen changes remain counted; success occurs only on a verified match.
- Agreed response target: at least 95% of confirmed updates appear within
  500 ms after Arena settles. Record sampling and measurement boundaries, and
  report read-to-render timing separately from action-to-visible-update timing.
- Test results identify the build, layouts, window sizes and real scenarios
  actually exercised. A live positioned-draft check remains pending until a
  pack is available; fixtures do not count as live acceptance.

## 7. Deliver a verified production build

- Choose and stage the semantic version before application checks. Run the
  relevant Kotlin, native coherence/geometry and renderer tests, then the
  repository's production-build checks.
- Build and install the optimized production app, restart the running instance
  and verify the resulting visible overlay and memory connection. Verify the
  installed artifact matches the tested build.
- Commit the completed, passing implementation and integrate it into main.
  Update diagnostics and acceptance evidence with actual results and any
  remaining live-test limits.
- Report measured timings as measurements; describe an optimized artifact as
  performance-ready without implying the response-time target has passed before
  the interaction benchmark is run.

## Design confirmation

The user confirmed the verification coverage, response target and shared
design, and requested visual examples of the appearance. All interview decisions
are settled. The examples do not replace the memory-geometry proof, real
interaction measurements or production acceptance described above.
