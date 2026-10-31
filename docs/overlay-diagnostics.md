# Memory overlay verification

The production overlay is memory-only from 3.0.0. Screenshot capture, OCR,
artwork matching, calibration, screen replay and recording commands were removed.
Historical image fixtures are retained as research evidence; they do not run in
or ship with the app. The standalone transport prototype remains under
`prototypes/arena-memory`.

## Automated checks

```sh
./gradlew test
./gradlew buildNativeHelpers
python3 -m unittest discover -s native/tests -v
./gradlew buildProduction
```

Kotlin tests reject incomplete snapshots, unsupported builds, incorrect pack
multisets, stale pools, duplicate-count mistakes, different editors and read gaps.
The compiled Swift helper reads synthetic IL2CPP graphs to verify exact deck and
pack IDs, quantities, card view order, and blocking during animations, dragging,
settings, dialogs and disconnection. Position-only draft animation preserves
complete card identity while geometry can be withheld independently. These
tests do not prove live draft access.
Draft view fixtures must model `DraftPackCardView.CurrentCard` as an
`ICardCollectionItem`, whose `Card` field contains `CardData`. Treating the view's
item as a printing caused the `Unexpected Arena object: card_printing` error;
regression tests cover this wrapper and reject incorrectly unwrapped objects.
Collections may also retain zero-copy entries after card moves/removals. Ignore
these entries before dereferencing their card; they do not contribute to the
pack or picked pool. Negative quantities, excessive counts and copy-limit
violations must still suppress the entire snapshot.

The shipping app must contain only `window-locator` and `arena-memory` in its
Helpers directory, with no capture/OCR executables or recognition classes in the
app JAR. The locator reads OS window metadata; it does not capture screen pixels.

## Live acceptance

1. Start the production app, select the relevant build and grant Arena access.
2. Confirm complete deck guidance without scanning the deck list.
3. Scroll, filter to no matching cards, and verify exact counts remain unchanged.
4. Add one basic land, observe only that quantity and the total change, remove it,
   and confirm the original selection is restored. Avoid changing deck artwork.
5. Open and cancel a dialog; confirm instructions disappear and then recover.
6. Quit either process and verify the read session ends.

Version 3.1.1 was verified against a live Premier Draft on the pinned macOS arm64
build. The app reached `Overlay · Synced`; native observations read complete
packs and picked pools, followed a pack transition, and paused during card
movement and waiting for the next pack. Sanitized evidence is saved in
`prototypes/arena-memory/verified-live-draft-macos-arm64.json`.

Version 3.1.2 additionally verified recovery from `Invalid draft card quantity`
during pack three of the same live Premier Draft. After zero-copy entries were
excluded, complete pack/pool readings resumed and the app reached
`Overlay · Synced` with 37 drafted copies at pack 3, pick 10. Evidence is saved
in `prototypes/arena-memory/verified-draft-empty-items-macos-arm64.json`.

Further live acceptance must cover Quick Draft, pick-two, identical copies,
more pack transitions, layouts, selected cards, animations and changed window
sizes. Pack identities and drafted pools must match the advisor before grades
can appear.

## Positioned badges (3.2.0)

This section records the 3.2.0–3.2.1 placement milestones. See the 3.3.0 update
below for the current presentation and retention behavior.

The native reader verifies UnityPlayer's complete fingerprint, loaded header and
reviewed code anchors before reading native UI geometry. It follows actual card
colliders/row rectangles through their parent TRS hierarchy into the root
screen-space Canvas. The result is normalized against the live client viewport;
the renderer accounts for macOS window chrome without screenshot calibration.

Each rectangle travels with its exact card ID and quantity. Hidden objects,
dirty rectangles, dragging, invalid/cyclic parents and incoherent observations
produce no spatial marks. Pool pages and scrolling decks are clipped to their
actual viewport. Stacked deck cards use the exposed header band derived from
their neighboring card positions. Exact deck counts remain usable when geometry
is temporarily unavailable.

Draft cards use the restored colored score crests at their lower edge. In the
horizontal split (pool above, stacked deck below), deck guidance uses +/− markers
in the actual gaps between columns, with an Add/Cut key. The marker's full bounds
must fit in that gap, keeping both its own card and neighboring titles clear.
In the vertical split (deck list on the right), full Add/Cut labels sit beside
the complete row, including its quantity counter. Pool cards use larger Add
labels above the card. The full card-name change
list has been removed from the overlay; named basic-land instructions remain.

The installed production reader was checked against 27 live deck positions in
the column layout and 7 pool-card positions, all matching the authoritative
40-card deck/pool IDs and quantities. The app reached `Overlay · Synced` after
the original pool-and-deck view was restored. Evidence is saved in
`prototypes/arena-memory/verified-card-geometry-macos-arm64.json`.
The 3.2.1 follow-up checked the vertical layout with 10 visible pool cards and
16 deck rows. Main deck, sideboard and pool counts were identical across layouts
and the app restart. Its whole-row geometry leaves a 39.2-point gap beside the
pool; the 34-point labels and one-point margin fit within that gap. Render tests
check that list labels and horizontal gutter markers leave card names clear.
The installed optimized 3.2.1 artifact matches the tested build; 467 app tests
and 13 native tests pass. These geometry and renderer checks do not replace the
user's final visual acceptance of the revised labels.
Draft projection also has native synthetic graph tests for duplicate IDs,
visibility, invalid transforms and dirty layout state. A real draft-pack visual
acceptance test remains deferred at the user's request.

## Steady full-card highlights (3.3.0)

Pool additions use a translucent green wash across the complete visible card.
Vertical deck entries use a complete green Add or red Cut row. In horizontal
stacks, covered cards use their exposed region; the front card uses its full
collider, including single-card columns. Every mark is clipped to the actual
viewport and bound to its exact card ID and quantity. The renderer adds no
individual +1/+2 labels. The compact bar contains aggregate Add/Cut counts and
named basic-land instructions once; a verified match confirms briefly and fades
after 2.4 seconds. Full named copy changes are available in FirstPick's deck tab.

Pack stabilization compares semantic identity without geometry and uses the
actual pack collection in its session identity. Native double observations
compare complete pack/pool state without demanding identical animated positions.
Card data/quantity is checked around each native projection. Deck quantities
remain checked before and after geometry extraction. Scrolling itself is no
longer treated as loss of the deck's complete identity.

Presentation retains verified advice through a single transient read gap.
Semantic freshness and position freshness have separate 1.2-second bounds;
continued count readings do not keep missing positions alive indefinitely.
Confirmed pack/editor changes, scene exits, blocking dialogs and access loss
invalidate the previous presentation. The initial notice is delayed 500 ms.
After stabilization, faster polls do not repeatedly restart validation. Window
metadata includes the display backing scale so a same-aspect resize cannot
reuse old pixel geometry. Counts remain usable during position reacquisition.

The optimized installed 3.3.0 artifact passes 475 Kotlin tests and 16 native
tests, with renderer coverage for full cards, rows, exposed bands and front
cards. Deterministic interaction tests cover retained advice through isolated
hover/read interruptions, real identity changes and independently stale positions.
These checks are not a measured end-to-end interaction benchmark.

A subsequent live horizontal-layout check verified the complete 40-card deck
with seven visible pool cards and 27 deck entries across seven columns. Every
geometry record matched the authoritative card IDs and quantities. Covered bands
do not overlap; each front entry extends below its covered bands and is clipped
to the viewport. FirstPick reported `Overlay · Synced` and displayed the full
named changes, including specific basic lands. No cards were changed. The typed
geometry is saved in `prototypes/arena-memory/verified-steady-overlay-macos-arm64.json`.
Arena's per-window capture omits the separate overlay window, so this observation
does not establish final composite appearance. The 3.3.0 vertical follow-up and
live motion/appearance checks remain pending.

The subsequent real Quick Draft pack check recorded 500 typed observations of
Pack 1, Pick 1. Every reading returned the same complete 14-card pack and 14
ordered projected rectangles, with an empty picked pool. IDs resolved through
Arena's local catalog matched all visible names and their pack order. There
were no missing-position samples. Helper elapsed time was 14 ms median, 17 ms
at the 95th percentile and 24 ms maximum. These are native observation timings,
excluding app polling, validation and drawing. All 350 foreground-probed samples
reported Arena in the background, so this is a static reader check rather than
foreground visual or hover acceptance. No pick was made. The sanitized results
and exact geometry are in the same evidence file under `live_draft_followup`.

The user subsequently confirmed that all 14 score badges were readable and
steady during the requested foreground check. A follow-up typed snapshot still
returned the same complete pack and empty picked pool, with matching ordered
positions. This supplies visual acceptance for that pack, reported by the user;
it does not establish next-pack behavior, alternate layouts or instrumented
motion/frame latency.

## Preferred basic printing during a pack transition (3.3.1)

The live second pack exposed a missing case: its log contained Island ID 106528,
but Arena displayed the preferred Island printing 75022. Both resolve to Island
in Arena's local catalog. Exact pack-ID equality rejected all recommendations,
including the twelve other cards. Validation now consumes every recommendation
once, using exact IDs first and allowing only catalog-verified basic-land
printing equivalence. Published badge IDs use the actual displayed printing.
Different non-basic IDs, different basic names and mismatched picked pools still
fail validation.

The freshness watchdog also replaced an explicit recommendation-waiting reason
after the presentation had been cleared, alternating the two visible messages.
With no previous verified advice to expire, it now preserves the explicit reason.
Regression tests reproduced both failures before the fix and passed afterwards;
a replay of the actual thirteen-card second-pack IDs preserves all twelve
numerical scores and the unrated Island across repeated polls.

The optimized 3.3.1 build passes 478 app tests and 16 native tests. Its installed
JAR and helpers match the tested build and its signature verifies. Rebuilding
ended the previous process-bound connection. After the user reconnected, the
same formerly failing thirteen-card pack returned complete ordered geometry in
40 consecutive observations, including the substituted Island ID 75022. The
installed app reported `Overlay · Synced` before and after the sample. No picks
were made by the agent. This verifies production synchronization; the separate
overlay is still omitted from Arena's per-window capture. Evidence is recorded in
`prototypes/arena-memory/verified-basic-printing-transition-macos-arm64.json`.

Arena process restarts end the process-bound read connection. The native reader
now reports an exited process as `arena_closed`, rather than leaving a cached
unsupported result. FirstPick discards its stale worker and restores the access
grant when a supported process returns. A scripted reader regression test checks
that an earlier unsupported response cannot permanently hide the grant button.

For bounded local diagnostics, launch with `FIRSTPICK_OVERLAY_TRACE=1`. Up to
512 observation records per overlay instance include helper time, read time and
position failure reasons; foreground composition records report
`read_to_compose_ms` and `validation_to_compose_ms`. The trace contains no card
names, account IDs, session tokens, raw memory or screenshots. Composition timing
is not OS frame presentation or input-to-visible-update latency. The agreed
95%-within-500-ms target still requires a controlled live interaction measurement;
a real draft-pack visual check remains deferred until a pack is available.

## Interactive overlay (3.4.0)

The Arena-sized window remains click-through. Small non-focusable windows over
rating badges and the access notice receive mouse events without activating
FirstPick. Hovering a rated badge shows the main app's score breakdown, including
model details when available. Unrated badges remain click-through. The whole
access notice invokes the same grant callback as the main app and is disabled
while connecting. Both draft and deck guidance expose this action.

Run `./gradlew overlayInteractionProbe` for a synthetic native-window fixture.
Click the access notice and check its Connecting state; click the game target
and check its counter; hover the grade to inspect the score breakdown. This
fixture never requests real Arena permissions. The fixture's Inspect interaction
windows button hides its background window for inspecting the floating windows.
For live acceptance, also move/resize Arena, switch apps, toggle the overlay,
and change packs: the input windows and tooltips must follow or disappear with
the verified advice. Automated geometry/data checks and production packaging
do not replace live hover/click acceptance or measured performance benchmarks.

Version 3.4.1 draws hovered score details on the full Arena-sized click-through
layer. A popup inside a small badge window inherited that window's constraints,
which wrapped the header and hid the calculation. Rendering regressions hover
a 100-pixel badge, verify complete single-line labels on the separate overlay,
check dismissal on exit, and keep the panel visible at all viewport corners.
The rendered acceptance preview is `build/reports/overlay-score-hover.png`.
