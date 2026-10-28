# Deck-building overlay reliability investigation

Investigated on 2026-10-21 against repository revision `997bfb2` (FirstPick 2.3.1).
The original investigation proposed changes without modifying app behavior.
The implementation follow-up is recorded below. Local logs were inspected read-only. No raw account data, identifiers,
log excerpts, or screenshots are included here.

## Conclusion — updated 2026-10-27

Treat Arena information as equally available to FirstPick and Untapped. There is
no evidence here of privileged access. Equal access does not imply that both apps
currently use the same sources: FirstPick reconstructs unsaved deck edits from
screen evidence, while Untapped documents a read-only process-memory technique
and local diagnostic component names corroborate an Arena-specific Mono reader.
The original investigation omitted this avenue and focused too narrowly on logs
and improvements to screen recognition.

A memory-based Arena state provider is now the priority feasibility investigation.
It could supply card IDs and quantities independently of shortened titles,
scrolling, and filters. Reading the current unsaved Limited deck on this macOS
installation has not yet been demonstrated. Neither a complete structure map nor
Untapped's polling/rendering implementation is established by the evidence below.

## What is known about Untapped

Draftsmith is a feature of the Untapped Companion. Its
[MTGA-specific Companion documentation](https://help.hearthsim.net/en/articles/5020719-how-does-the-untapped-gg-companion-work)
describes following `Player.log`. That establishes log use, not exclusive log use.

Two additional official articles describe the Companion accessing game memory
read-only through standard Windows and macOS APIs, without modifying game files,
injecting/hooking code, or taking game actions:

- [How does the Untapped.gg Companion work?](https://help.hearthsim.net/en/articles/8377705-how-does-the-untapped-gg-companion-work)
- [Is the Untapped.gg Companion safe to use?](https://help.hearthsim.net/en/articles/7121040-is-the-untapped-gg-companion-safe-to-use)

These articles are filed under the Yu-Gi-Oh! collection in their help center.
They establish the Companion's documented technique, but by themselves do not
prove which Draftsmith deck-editor fields are read in MTGA.

For Arena-specific corroboration, on 2026-10-27 we inspected the installed
Companion's local diagnostic logs read-only. They contain component/function
names including `MonoMtgaCurrentScene.read`,
`ScryMtgaDraftRollover.readMonoReader`, `ScryMtgaInventory.readMonoReader`, and
`MtgaDeckbuilderWatcher.nextUpdate`. These are sanitized identifiers only; no raw
log entries, account data, addresses, or tokens are retained here. Together with
the official documentation, these strongly support an Arena integration using
an internal Mono reader alongside logs. They do not establish how every unsaved
deck edit or overlay position is obtained, or rule out supplementary vision.

## Verified transport milestone — 2026-10-28

The standalone [memory probe](../prototypes/arena-memory/README.md) has now
verified actual read-only access to Arena on this Mac. Normal user access was
denied. After one explicit administrator authorization entered locally by the
user, `task_read_for_pid` granted a read port. The probe used
`mach_vm_read_overwrite` to read live ARM64 headers for `MTGA` and
`GameAssembly.dylib`, verifying both against the installed binaries.

The [sanitized result](../prototypes/arena-memory/verified-transport-macos-arm64.json)
records 296,688 bytes read and a 6 ms read phase, excluding authorization and
build inspection. These figures do not measure deck extraction or overlay
latency. Eleven prototype checks passed, including a real self-process Mach-O
read and validation of bounds, identity, and malformed inputs.

The actual installed Arena 2026.64.50 build uses IL2CPP metadata version 39.
The legacy Mono naming observed in Companion logs must not be taken as an
instruction to apply Mono layouts to this build. Current deck objects, field
offsets, card IDs, and quantities have not yet been decoded. The next milestone
is a build-specific IL2CPP deck snapshot; the production overlay is unchanged.

## Revised implementation priority — 2026-10-27

1. Establish whether the running macOS Arena process can be inspected read-only
   under the installed build's actual access constraints. Do not weaken platform
   protections or write to the game to obtain access.
2. Demonstrate a minimal reader for the current Limited deck: stable card IDs,
   per-ID quantities, main deck versus sideboard, and the active course/editor.
   Compare snapshots before and after one edit, scrolling, and filtering. Include
   the two Hapatra cards whose rendered prefixes collide. Availability of these
   specific fields remains a hypothesis until this experiment succeeds.
3. If demonstrated, put this behind a state-provider interface, using memory
   snapshots for identity/counts, logs for draft/save/history context, and screen
   recognition only where still needed for geometry or an explicit fallback.
4. Require coherent snapshots, correct course scoping, supported-build checks,
   and explicit unavailability when layouts change. Measure action-to-update
   latency against real edits before replacing the production provider.

The OCR and focus-retention fixes address real defects in the existing provider;
they do not establish parity with a memory-based integration. No memory reader
was implemented or executed during this documentation update.

## Local Arena evidence

The existing `Player.log` and `Player-prev.log` contain:

| Signal | Player.log | Player-prev.log |
| --- | ---: | ---: |
| Lines containing `Draft.DraftDeckView` | 15 | 9 |
| Lines containing `CourseDeck` | 5 | 19 |
| Lines containing `MainDeck` | 5 | 20 |
| Lines containing `EventSetDeck` | 0 | 2 |

The two matching save RPC lines in the previous log identify `EventSetDeckV3`.
Course deck objects include `MainDeck`, `Sideboard`, `Companions`, `CommandZone`,
and `CardSkins`. The screen markers are plain markers without JSON fields on the
same line; they do not themselves supply deck counts. Course inventories contain
multiple historical course objects, so object counts are not edit counts.

This sample shows available saved-deck and screen-transition signals. It does
not establish a per-click edit stream, nor prove one cannot be obtained. The
files were not captured alongside a timed action trace, so absence of an event
cannot be attributed to a particular click. No live Arena changes were made.

FirstPick's `EventParser` already reads course deck snapshots and deliberately
ignores outgoing `EventSetDeck` requests as confirmation of a successful save.
That distinction should remain. Requests may still be useful as provisional
observations if correctly associated with their responses and course.

## Confirmed weaknesses in FirstPick

### 1. Capture latency and display expiry conflict

`ArenaDeckBuilderOverlay` clears its displayed model after 900 ms without a
completed read. `DeckFrameReader` allows a worker request to run for eight
seconds. A slow but ultimately successful read therefore clears the overlay
before it finishes. The watchdog discards the status, counts, and marks together;
it does not distinguish slow recognition from leaving the deck builder.

Do not simply extend this timer to eight seconds: old highlights could then point
at different cards after a scroll. Screen-position validity and deck-state
validity need independent handling.

### 2. A single unexplained delta invalidates off-screen knowledge

`DeckObservationReducer` compares the deck-total delta with deltas for previously
known visible cards and basic lands. If they disagree, it clears remembered counts
and retains only the current frame's observations. This correctly avoids guessing
which hidden card changed, but recovery requires rediscovering the rest of the
pool. Lagging total text, lagging diamonds, and a missed count glyph can all
trigger the same broad reset as a genuine unseen edit.

The comparison has no transaction or settling phase. A row and the total changing
on adjacent frames can cause two resets during one valid action. Conversely,
unchanged totals alone cannot rule out an off-screen add/remove swap.

### 3. Recognition interruptions erase accumulated progress

`DeckGuidanceSession.unreadableFrame` clears all observations after three seconds
of unreadability. Focus loss invalidates the session immediately. Leaving the
builder invalidates it after presentation loss is confirmed. These rules protect
against stale instructions, but conflate hiding old marks with forgetting deck
knowledge. Hover occlusion and slow scrolling can require another full scan.

Retained counts must carry freshness/uncertainty; retaining them must not imply
that they are currently verified after an unobserved edit.

### 4. Recognition is gated by the whole layout

`DeckBuilderScreenInterpreter` requires the total header, an anchor, at least two
aligned rows, sufficient row spread, and valid pitch. A frame with a readable
pool card but obscured rows cannot update that card through the current full
observation path. Shell-only recognition carries no count evidence to the session.

The native helper scans fixed regions and recognizes English text. The pool
geometry supports five columns and two rows, and copy recognition supports at
most four diamonds. The session correctly refuses an ambiguous four-diamond
reading when the drafted count exceeds four. These are explicit coverage limits,
not evidence of poor card recommendations.

### 5. A recognized observation can combine different moments

The native worker recognizes text on one captured image, checks text crops
against a newer image, then returns that newer image for diamond detection.
This rejects moving text, but does not prove that header, row counts, and pool
diamonds describe one settled deck transaction. A diamond animation may change
without changing the associated title crop. Deck rows take priority when both
surfaces show the same card, but off-screen rows lack that protection.

### 6. Saved deck knowledge is separate from the live overlay

`DraftViewModel` exposes a resolved saved-deck snapshot. `Main` passes only the
target, draft pool, and session key to `ArenaDeckBuilderOverlay`. Its live session
therefore starts unknown even when a saved comparison exists elsewhere in the app.

A saved snapshot can be a candidate baseline and a recovery checkpoint. It must
not be treated as the current unsaved deck merely because the total matches.
Course identity, save timing, focus gaps, and observed edits matter.

### 7. Existing tests do not measure the reported interaction

Reducer/session tests cover scrolling, duplicates, brief unreadability, and row
priority. Copy-detector tests use generated diamonds; they do not establish
accuracy on animated real Arena frames. The repository contains real draft-pack
images, but no recorded deck-building interaction sequence. The native worker
returns capture/OCR/write timing fields that the Kotlin reader does not expose
for latency diagnostics. Passing unit tests cannot establish comparative parity.

## Original implementation order (2026-10-21)

1. Add opt-in local diagnostics: monotonic capture timestamps, worker duration,
   regions rescanned, recognition outcome, evidence source per count, and why a
   count was invalidated. Avoid storing account data or frames by default.
2. Capture a consented, controlled deck-building action trace and matching log
   interval. Include a single-copy add/remove, duplicate edits, last-copy removal,
   basic-land edits, rapid edits, scrolling, filtering, hover previews, focus loss,
   save/reopen, and both supported and unsupported layouts. Repeat the same trace
   with Untapped visible to compare observed behavior without guessing internals.
3. Replay that trace through the actual interpreter, copy detector, session, and
   presentation pipeline. Establish baseline errors and latency before fixes.
4. Reconcile changes as transactions: allow briefly asynchronous total/row/pool
   evidence to settle, prefer direct rows, corroborate diamond changes, and keep
   conflicting counts uncertain until resolved. Reacquire broadly when the trace
   shows an unaccounted edit; do not silently preserve stale instructions.
5. Separate persistent deck knowledge, current evidence, and highlight geometry.
   Hide stale positions promptly without forcing all valid counts to be relearned.
   Allow independently trustworthy pool/row observations even when the full layout
   fails recognition. Never infer absence merely from an invisible card.
6. Wire applicable confirmed saves into the reconciliation model as checkpoints.
   Validate course identity and chronology. A historical save cannot verify edits
   made after it. Add any newly demonstrated public log signals with fixtures.
7. Coordinate display expiry with measured capture and recognition latency. Retain
   a stable checking status during revalidation; do not keep stale clickable-card
   directions. Tune OCR regions/caching only after measuring real bottlenecks.

## Proposed release gates

These are targets, not measurements achieved by this investigation:

- Zero incorrect add/remove directions on the curated replay corpus, including
  duplicate-copy transitions and equal-total swaps.
- No instructions attached to a previous scroll/filter position.
- No loss of unrelated verified counts during an explained, settled edit.
- No full-model reset caused solely by an in-flight reader crossing 900 ms.
- Stable guidance on an unchanged screen over a 60-second run.
- Target p95 action-to-correct-guidance latency below 500 ms after settling on the
  supported layout; report hardware, sample size, and cold versus cached reads.
- Explicit uncertainty and reliable recovery for unsupported/obscured states,
  focus gaps, inconsistent totals, and genuine unobserved edits.

A production build, restart, and visible Arena verification are required after
implementation. This documentation-only investigation does not establish those
release gates or claim parity with Untapped.

## Source map

- [Arena overlay lifecycle](../src/main/kotlin/com/firstpick/overlay/ArenaDeckBuilderOverlay.kt)
- [Native capture, caching, and OCR](../native/macos/deck-reader.swift)
- [Worker timeout and transport](../src/main/kotlin/com/firstpick/overlay/DeckFrameReader.kt)
- [Deck count reconciliation](../src/main/kotlin/com/firstpick/overlay/DeckObservationReducer.kt)
- [Session and copy evidence](../src/main/kotlin/com/firstpick/overlay/DeckGuidanceSession.kt)
- [Layout interpretation](../src/main/kotlin/com/firstpick/overlay/DeckBuilderScreenInterpreter.kt)
- [Pool copy detection](../src/main/kotlin/com/firstpick/overlay/DeckPoolCopyDetector.kt)
- [Presentation state machine](../src/main/kotlin/com/firstpick/overlay/DeckOverlayFramePresentation.kt)
- [Arena event parser](../src/main/kotlin/com/firstpick/log/EventParser.kt)
- [Saved-deck UI state](../src/main/kotlin/com/firstpick/ui/DraftViewModel.kt)
- [Overlay inputs](../src/main/kotlin/com/firstpick/Main.kt)

## First implementation: 2.3.3

The first release addresses transaction reconciliation and capture consistency:

- Unexplained row/total deltas are retried against the last trusted state. If the
  surfaces catch up, off-screen counts survive. If identical unexplained evidence
  persists for 600 ms, hidden counts are invalidated and must be reacquired.
  Conflicting instructions are withheld while settling.
- Text and diamond pixels now come from one capture. A pool title is also checked
  against its surrounding copy-indicator region before being returned.
- A confirmed builder can supply pool evidence with no readable deck rows, and
  an empty builder can establish zero counts.
- Slow reads expire highlight positions while retaining verification progress in
  the checking label. Rate-limited slow-frame diagnostics report elapsed time and
  observation count without storing images or card/account data.
- Presentation state is scoped to the draft session, including when two sessions
  happen to contain the same pool.

Focus-loss and prolonged unreadability still require fresh verification. Saved
snapshots are not used as proof of unsaved state. Those safeguards remain until
more complete edit chronology is available. These changes do not claim comparative
parity, recorded-corpus accuracy, or a measured p95 latency improvement.

Validation for this implementation: all 512 tests passed with no skipped tests;
`buildProduction` and `runProduction` succeeded. The installed app visibly reports
v2.3.3. Arena was open in the supported builder, but the native window locator
reported `frontmost:false` during the automation check and guidance remained
inactive. Live edit latency/flicker improvement is therefore not claimed; it
still needs a foreground Arena interaction run.

The final version is 2.3.3 because the concurrently completed three-color deck
changes on main already used 2.3.2. The combined application passed the full production checks and was rebuilt,
installed, and visibly verified as v2.3.3 before integration.

## Rollback in 2.5.5

User testing found worse recognition coverage and moving rating badges during
drafting. Versions 2.3.3 and 2.5.4 did not establish live interaction parity.
The overlay implementation, native deck reader, and corresponding tests are
restored to revision 5fcfb16, before both experimental changes. Unrelated
features remain current. The earlier implementation notes above are historical,
not a description of the active overlay. Further overlay changes require real
interaction validation before claiming an improvement.

## Verified memory deck reader (2026-10-28)

The standalone [Arena memory prototype](../prototypes/arena-memory/README.md)
now extracts exact group IDs and quantities from the active Limited editor for
Arena 2026.64.50 arm64/IL2CPP v39. Live checks verified duplicate quantities,
scroll/filter independence, an Island add/remove, and a same-total swap between
Desert Frost (106460) and Desert Fang (106516), followed by restoration of the
original card selection. All 180 repeated observations succeeded; median read
time was 115.05 ms and p95 was 135 ms through the temporary read session with
runtime metadata cached. These timings exclude authorization/module verification
and do not measure overlay latency.

The [sanitized evidence](../prototypes/arena-memory/verified-deck-macos-arm64.json)
records the checks and limits. Production still needs provider integration,
permission UX, connection/modal validity, session scoping, and future-build
handling. The formal sideboard was empty; the available draft pool is a separate
model and must not be confused with it. The installed FirstPick overlay remains
unchanged by this prototype.
