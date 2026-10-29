# Overlay improvement design interview

Status: ready-for-agent

This is the design record for the user's grill-with-docs session. The user has
confirmed the shared design and Q8's verification targets, and requested visual
examples as the next step. Hover interactions remain deferred.

## Settled requirements from the conversation

- Read live card identities, quantities and positions from Arena memory.
- Support drafting and deck building.
- Support both horizontal and vertical deck layouts automatically.
- Put draft grades and scores at the bottom of individual cards using the score
  badge presentation.
- In the pool, highlight the whole card green when copies should be added.
  In the deck, highlight the whole row green for Add or red for Cut; use the
  exposed entry band in the horizontal stacked layout. Preserve readability.
  This visual-review decision supersedes name-only tints and detached labels.
- Identify the specific basic lands to add or cut.
- Make the overlay accurate, responsive and steady during ordinary interaction.
- This pass improves overlay quality without changing the card-ranking or
  deck-recommendation algorithms (Q1 confirmed by the user).
- Keep the in-game overlay click-through for this iteration. Interactive
  elements, especially hovering a score badge to explain its rating, are a
  future enhancement (Q2 confirmed by the user).
- Ordinary hovering, clicking, scrolling and other interaction must not cause
  the overlay to blink. Keep verified advice steady and replace it only when a
  confirmed card or position change requires an update. Routine clearing and
  reappearing is not acceptable (Q3 clarified by the user).
- Use a compact deck summary with named basic-land changes. When the current
  deck matches the suggested build, briefly confirm success and fade that
  summary; detailed lists and troubleshooting stay in FirstPick (Q5 confirmed).
- Do not show individual +1/+2/−1 copy-change badges in Arena. The user removed
  those badges during visual review. Keep aggregate Add/Cut totals in the top
  bar and exact named changes in FirstPick; native Arena quantity counters stay.
- Withdraw affected advice for actual card/editor changes or a sustained loss
  of trustworthy data, never merely for hovering or one missed reading. Use
  small, plain-language recovery notices with details in FirstPick (Q6 confirmed).
- Count changes for off-screen cards in the compact summary. Show their full
  names and exact copy changes in FirstPick, so scrolling does not hide the
  remaining work or imply the deck matches (Q7 confirmed after clarification).

## Design tree

- Scope and success criteria
  - Q1: Improve overlay quality while preserving recommendation algorithms?
    - Confirmed: Yes. Focus on overlay quality.
    - Next: define measurable accuracy, stability and response-time gates.
- Interaction boundary
  - Q2: Keep all in-game marks click-through and put controls in FirstPick?
    - Confirmed for this iteration. In-game hover explanations are deferred.
    - Controls, explanations and troubleshooting belong in FirstPick for now.
- Presentation, anchored by the settled card-attached design
  - Q4 presentation, revised during visual review:
    - Confirmed: whole pool cards green; whole deck rows green/red. In the
      horizontal layout, tint the full exposed deck-entry band.
    - Remove individual signed copy-change badges. This supersedes the initial
      Q4 approval of +2/−1 indicators. Draft score badges remain unchanged.
    - Verify full card/entry bounds and clipping, rather than requiring exact
      title-text rectangles for the revised design.
  - Q5: Use compact summaries and fade successful completion?
    - Confirmed: Yes. Show named land changes; briefly confirm and fade when
      the deck matches. Keep detailed lists and troubleshooting in FirstPick.
    - Q7: show accurate totals and a pointer to FirstPick when needed changes
      are off-screen, filtered out or cannot be shown readably?
      - Confirmed after the concrete off-screen example: keep Add 2 cards in
        the compact overlay and list which cards/how many copies in FirstPick.
- Transitions and recovery
  - Q3: Maintain steady advice throughout ordinary interaction?
    - Confirmed with a correction: do not use brief disappearance and recovery
      as the default behavior. Only confirmed changes should update the overlay.
    - Q6: withdraw affected advice only for actual card/editor changes or a
      sustained loss of trustworthy data, using compact recovery notices?
      - Confirmed: Yes. Ordinary motion and a single missed read are not
        reasons to withdraw advice.
- Acceptance
  - Q8: verify normal interaction across drafting and both deck layouts, with
    zero incorrect card/copy markings or routine blinking and a measured target
    of 95% of updates within 500 ms after Arena settles?
    - Confirmed: Yes. These are acceptance targets, not measured results.
    - Live positioned draft-badge acceptance remains pending until a pack is
      available, as the user previously requested.

## Deferred enhancements

- Hovering a score badge to explain the advisor score and its contributing
  factors. The user explicitly wants to keep the current iteration simple;
  this is not authorization to introduce in-game interaction now.
- Other interactive overlay elements, to be designed separately when needed.

## Current evidence

- Production 3.2.1 has live memory-geometry evidence for both deck layouts.
  Its automated checks pass, but revised label appearance still needs visual
  acceptance. See ../../docs/overlay-diagnostics.md.
- Draft pack comparison includes geometry, whereas deck comparison excludes
  it. Position changes can therefore trigger draft revalidation even when card
  identities are unchanged. This is a code finding, not a measured explanation
  of every reported flicker. See
  ../../src/main/kotlin/com/firstpick/overlay/MemoryPackGuidance.kt and
  ../../native/macos/arena-memory.swift.
- Draft and deck overlays both clear displayed advice on interrupted readings;
  a watchdog also clears it after a 900 ms gap.
- Deck health can report Synced without usable card positions; draft health
  requires positions. A shared user-facing meaning of readiness needs to be
  decided. See ../../src/main/kotlin/com/firstpick/overlay/ArenaMemoryOverlayWindow.kt.
- Vertical labels use a fixed width and offset. Horizontal markers disappear
  when their measured gap is too narrow, and the native horizontal reader emits
  no deck marks if every column has only one card. There is no alternative
  presentation for these cases. See
  ../../src/main/kotlin/com/firstpick/overlay/DeckGuidanceOverlay.kt and
  ../../native/macos/arena-geometry.swift.
- For the earlier name-only design, precise title-area rectangles were not
  exposed by the production snapshot.
  Vertical whole-row and frame-image rectangles have live evidence; installed
  static metadata also identifies a separate name-text field. For pool and
  stacked cards, static metadata identifies renderer parts and Title/AltTitle
  text fillers. These are candidates for a memory-only title-geometry prototype,
  not proven live title positions. The revised whole-card/whole-row design no
  longer requires this title-filler mapping; validate complete entry bounds
  and clipping instead.
- The external overlay draws above Arena's rendered content; it cannot insert
  a background layer beneath Arena's own text. Use a translucent tint over the
  full card or row and verify legibility on different card frames, selection and
  hover states. Arena's own names, quantity counters and mana symbols remain
  visible through the tint; there are no added per-card quantity labels.
- Deck guidance knows exact copy differences but its current card markers omit
  those numbers. Filters can hide cards that need adding while the aggregate
  summary remains correct. Q7 addresses a discovery aid without assuming a
  return to the previously removed permanent in-game list.
- Score badges use the same contextual advisor score as FirstPick's pack view.
  That score can incorporate the drafted pool and optional PickNet reranking;
  it is not the rating another companion prints inside Arena.
- Native error messages can reach the user directly. Draft geometry failure
  reasons and native elapsed times are not consumed by Kotlin; the deck reader
  does not expose an equivalent geometry failure reason.
- The Q8 response-time target is agreed; live interaction benchmarks have not
  yet been measured for this improvement session.

## Round 1

Q1: Should this pass improve stability, readability and responsiveness while
keeping ranking and deck-recommendation algorithms unchanged?
Recommendation: Yes. Answer: Yes—focus on overlay quality.

Q2: Should the in-game overlay remain entirely click-through, with controls,
explanations and troubleshooting in FirstPick?
Recommendation: Yes. Answer: Keep it simple and click-through for now; consider
interactive elements and hover explanations for card ratings later.

## Round 2

Q3 initially proposed brief disappearance of labels with uncertain positions.
The user rejected interaction-triggered blinking: the overlay should stay
steady and only change when a change is confirmed. This supersedes treating
brief hide-and-restore cycles as normal operation. Ordinary movement alone must
not be treated as a new pack or trigger a reset of verified advice. Genuine
pack/editor transitions and longer unavailable readings remain to be designed;
this decision does not establish that stale ratings may be shown on new cards.

Acceptance requirement: no visible clearing/reappearing cycles caused solely by
ordinary interaction with unchanged card identities and still-valid positions.
When positions genuinely change, update the corresponding marks coherently
without resetting unchanged advice. Exact timing and measurement gates remain
open.

Q4: Should deck markers show exact copy changes, using Add 2/Cut 1 when space
allows and +2/−1 in tight spaces, without covering names, counters or controls?
The user instead specified a stronger visual presentation: a translucent green
or red background wrapping the whole card name area for Add or Cut. Adopt that
presentation instead of detached labels. The separate question of displaying
exact copy changes remains open.

Q4 follow-up: Should a highlighted card also have a small +2/−1 copy-change
indicator, leaving its name and Arena's existing quantity counter readable?
Recommendation: Yes. Answer: Yes, confirmed after question five was settled.
Superseded during visual review: remove those individual badges; use full-card
and full-row highlights plus the aggregate top bar.

Q5: Should normal drafting show score badges only and deck building show
markers plus a compact summary with named basic-land changes? Briefly confirm a
matching deck and then fade that message; keep detailed lists and troubleshooting
in FirstPick.
Recommendation: Yes. Answer: Yes, confirmed explicitly for question five.

## Round 3

Q6: Should affected advice be withdrawn only when cards/editor actually change
or trustworthy data is lost for a sustained period, never for ordinary hovering
or one missed reading? Use small, plain-language recovery notices with details
in FirstPick. This does not relax the no-blinking requirement for normal use.
Recommendation: Yes. Answer: Yes—use this behavior.

Q7: If needed changes are off-screen, filtered out or have no readable highlight
placement, should the compact summary still count them and point to the full
named list in FirstPick, while preserving card-name and counter legibility?
The user requested clarification. Concrete example: two needed cards are below
the current pool view; the overlay still says Add 2 cards and FirstPick shows
which cards/how many copies. Answer: Yes to that clarified behavior.

Q8: Should acceptance cover drafting and both deck layouts during hovering,
scrolling, resizing, filtering, duplicate-copy edits and layout changes, requiring
zero incorrect card/copy markings and no interaction-triggered blinking? Proposed
response-time target: at least 95% of confirmed updates visible within 500 ms
after Arena settles. Measure this target; do not present it as achieved in
advance. Live draft acceptance remains pending until a pack is available.
Recommendation: Yes. Answer: Yes. The user also requested visual examples of the
agreed design before the next implementation work.

## Implementation proposal

The confirmed work sequence and acceptance matrix are in
[implementation-plan.md](implementation-plan.md).

## Visual examples

The conversation examples show the vertical deck builder, horizontal deck
builder and draft score badges. Following user feedback, the updated deck
examples use a full-card green wash in the pool and full-row green/red washes
in the deck, with no individual copy-change badges. Local design options cover
tint strength, fine edging and deck completion. The aggregate Add/Cut top bar
stays compact and does not list individual non-basic card names. The previously
requested named basic-land instruction remains. Cards and scores are
illustrative, not live recommendations. The previews do not prove live geometry
or application performance.

Preview checks cover carousel navigation, no horizontal overflow at 320-pixel
content width, coherent example deck totals and remaining changes, completion
highlight removal, whole-card/row tint coverage, removal of individual change
badges, and tint/draft-badge-size controls. Production acceptance remains the
work described in the implementation plan.
