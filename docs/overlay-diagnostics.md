# Memory overlay verification

## Selective, atomic hover occlusion (3.8.2)

The first hover implementation could withhold the entire geometry frame when
Arena's preview used a separate transform hierarchy. The shared canvas fix
from 3.8.1 is included here. Brief animation-read gaps again retain verified
positions rather than clearing every badge immediately.

Visible scores now render together in the main click-through overlay. Separate
input panels contain only transparent hit areas, so creating or disposing them
cannot make badges fade away one at a time. Occlusion checks use each score
badge's actual viewport rectangle. A tooltip covering a card title can suppress
that card's highlight while leaving its uncovered score visible.

The reader projects individual active ability/flavor tooltip items instead of
their potentially screen-height layout parent. It also reads static enlarged
card hangers, secondary-face previews, and the general TooltipSystem's active
rectangle. Unsupported scenes and actual modal dialogs keep their existing
blocking behavior.

Validation: 507 app tests and 30 compiled native-reader fixtures pass. Live
checks on Arena 2026.65.0 exercised ordinary Geist hover, enlarged details,
Adventure-card hover, and a deck-list Unsummon hover without losing the complete
eight-card pack. The captured Geist geometry has one preview and two separate
tooltip rectangles. Its replay retains the three uncovered original badges,
adds the enlarged Geist score, and hides only the five covered originals.
The sanitized fixture is `src/test/resources/overlay/arena-hover-preview.json`.
General tooltips and secondary faces additionally have native synthetic tests.

The combined signed production app was installed and restarted as 3.8.2, and
its reader matched the built artifact. Per-window Arena screenshots verify the
game's preview layout; the shared-layer render regression verifies atomic score
rendering. Per-window capture does not independently prove the OS's composite
of all application windows. These are functional checks, not measured benchmarks.


## Hover-preview layering (3.8.0)

The memory reader now projects Arena's rollover and enlarged-detail card
colliders separately from the pack. Draft badges covered by a preview or its
ability/flavor tooltip disappear, and the enlarged card repeats the matching
pack card's score, breakdown and recommendation at the same bottom-center
anchor. Hover-source indices preserve the selected copy's recommendation when
the pack contains duplicates. Deck highlights also disappear under previews.

An inactive retained preview card is ignored even if Arena leaves its zoom
controller marked active. An unreadable active preview withholds geometry;
the presentation clears old rectangles immediately while keeping verified
advice. This avoids retaining ordinary badges over a newly opened preview.

Validation: 504 app tests and 25 compiled native-reader fixture tests pass. The
profile's added types and offsets match the installed Arena 2026.65.0 binary.
The signed production app was rebuilt, installed and restarted. A live reader
returned the complete eight-card Quick Draft pack with eight positions after
the inactive-preview fix. Synthetic tests cover enlarged geometry, tooltip
occlusion, preview closure, duplicate source selection and interrupted reads.
The final live hover/composite visual check remains pending: automated input
did not trigger Arena's hover preview. These checks are not measured benchmarks.


## Hidden deck-details recovery (3.7.1)

Arena can retain a hidden `DeckDetailsPopup` with `IsShowing` still set. Checking
only that flag blocked the entire pack before any screen could be verified,
leaving saved access connected and the overlay reading indefinitely. The reader
now checks the popup's native GameObject activity as well. Active dialogs still
suppress advice; inactive or destroyed popups do not.

The native regression reproduced `blocked` for a hidden popup before the fix.
Draft and deck fixtures now cover hidden popups alongside the existing visible
dialog checks. The optimized, signed 3.7.1 build passed 499 app tests and 20
native tests. On 2026-11-04, the installed reader changed from repeated false
deck-details blocks to complete eight-card pack readings with eight matching
positions on the same Quick Draft pack 3, pick 7. Saved access reconnected and
the foreground app reported `Overlay · Synced`. No draft pick was made. This
verifies live synchronization; Arena's per-window screenshot does not establish
the final composite appearance of the separate overlay windows.

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

Version 3.4.2 disposes the complete overlay window tree when Arena is not
frontmost. Merely setting the large window invisible suspended its composition
and left independent badge/access windows on top of other apps. A missing Arena
observation also clears foreground visibility immediately, while retaining only
geometry during the short lookup grace period. The macOS native lifecycle test
creates the actual layer, badge, and access windows, verifies all disappear on
focus loss, return on focus regain, and disappear when the locator returns no
window. It requests no real Arena access and is skipped on headless/non-macOS
hosts.

Version 3.4.4 creates interactive controls as AWT popup windows before their native
peers exist. On macOS this selects an NSPanel with the non-activating style mask;
merely disabling keyboard focus on a normal window still allowed a click to
activate FirstPick and raise its main window. Controls also disable automatic
focus requests. The native lifecycle regression verifies both the Java settings
and the actual Cocoa panel class/style mask without opening an authorization
prompt. The system-owned password prompt remains part of the existing grant flow.

Version 3.4.6 gives expanded score details their own full-size, non-activating,
click-through panel one native window level above popup controls. Rendering the
panel on the lower base layer allowed neighboring badges to cover its text.
The transient panel is configured before becoming visible so it cannot intercept
the pointer and cancel its own hover. Existing layout/edge checks still apply;
the native regression additionally compares actual Cocoa levels against both
badges and the access notice, verifies mouse pass-through, and checks that all
layers disappear when Arena loses focus.

## Arena 2026.65.0 compatibility (3.5.2)

Arena's update changed all three pinned file fingerprints, moved the IL2CPP
class table and field-offset table, and shifted several reviewed type indices
and native functions. Version 3.5.2 updates the pinned arm64 profile after
reviewing those locations against the installed metadata and native code.
All selected managed field offsets remain unchanged. The thirteen Unity
geometry code anchors remain byte-identical at their existing locations.
Unknown builds continue to fail closed.

Run `python3 scripts/verify-arena-layout.py` to check the installed Arena against
the production profile without process access. It verifies complete fingerprints,
all 47 selected type identities and their fields, eight assembly code anchors,
and thirteen Unity code anchors. It independently decodes the class-table
location from the reviewed native lookup. This is a static compatibility check,
not proof of a live pack/deck read or measured overlay performance.

The signed optimized 3.5.2 production build passes 490 app tests and 16 native
reader tests. The installed native `--inspect` check reports `supported` for
Arena 2026.65.0; the previous installed reader reported `unsupported_build`.

After installing and restarting 3.5.2, the app reported `Live Arena access is
connected`. Forty consecutive authorized snapshots of the live Quick Draft
Pack 1, Pick 1 returned `draft_ready`, the complete 14-card pack, an empty picked
pool, and 14 in-bounds card rectangles whose ID multiset exactly matched the
pack on every read. Median native snapshot time was 16 ms. No picks were made.
This verifies live identity and geometry on the new build, not interaction or
end-to-end rendering latency. The foreground metadata probe reported Arena in
the background during agent verification, so foreground badge appearance still
requires user acceptance.

## Saved Arena authorization (3.7.0)

The Grant access control is independent of verified card advice. Before access
has been granted, FirstPick cannot determine the current Arena screen, so the
small access control can appear anywhere in foreground Arena when the overlay
is enabled. Card advice and reading notices still require a verified draft or
deck screen. All windows disappear outside foreground Arena. A native regression
exercises both production trackers from an initial `access_required` reading.

One administrator approval now installs a certificate-signed, root-owned broker
at `/Library/PrivilegedHelperTools/com.firstpick.arena-reader`, registered by
`/Library/LaunchDaemons/com.firstpick.arena-reader.plist`. This uses a conventional
launch daemon, which supports locally signed builds; the SMAppService registration
probe on this Mac was rejected with error 1, and Developer ID/notarization was
not configured. No password is stored and no system privacy or trust settings
are relaxed.

The broker accepts XPC connections only from the reader's designated signing
requirement and the UID explicitly approved at installation. Its interface can
start a bounded, app-owned reader session, install a signed reader update, or
revoke the grant. It cannot execute arbitrary commands, accept arbitrary reader
arguments, or return raw process memory. It derives the caller's UID/GID from
the XPC connection and verifies that both the app and Arena belong to that user.
Each child acquires only an Arena read right, then permanently drops root before
runtime discovery, socket handling, and typed snapshots, as before.

App and Arena restarts end their read session, but retain the installed grant.
FirstPick automatically reconnects with `--connect`, which cannot install a
helper or open an administrator prompt. Signed app updates replace the broker
through a root-owned staging file verified against the approved signing
requirement. This relies on retaining the local signing certificate (or the
release signing identity); changing identity requires explicit reauthorization.
The root broker never launches a binary from the user's app directory.

Revoke access in the main app stops active sessions and removes the broker and
launch-daemon registration. Subsequent connections require explicit approval.
Only helper installation requests a macOS administrator prompt. Routine
reconnection, signed updates, and revocation use the authenticated broker.

Verification includes app/Arena restart and revocation tests using real private
sockets with a scripted reader, foreground native-window lifecycle tests, native
memory fixtures, and signature checks on the optimized production helper. These
checks do not replace live acceptance of the initial administrator approval,
automatic reconnection, or helper replacement across installed app updates.

Live acceptance on 2026-11-04: the user completed the initial administrator
approval and FirstPick reported that approval was saved. The installed broker
was root-owned with mode 0755 and its registration was scoped to the current
user. Restarting into the final optimized 3.7.0 build, then enabling the overlay,
automatically updated the earlier broker binary and reconnected without another
administrator prompt. The installed broker's SHA-256 matched the final packaged
helper, and the UI again showed saved access with Revoke access available.
Arena itself was not restarted, and revocation was exercised in the scripted
regression rather than removing the user's newly approved live grant.
