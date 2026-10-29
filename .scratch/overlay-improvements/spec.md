# Overlay improvement design interview

Status: needs-info

This is a working design record for the user's grill-with-docs session. It is
not an approved implementation plan. No application changes are authorized by
unanswered recommendations in this document.

## Settled requirements from the conversation

- Read live card identities, quantities and positions from Arena memory.
- Support drafting and deck building.
- Support both horizontal and vertical deck layouts automatically.
- Put draft grades and scores at the bottom of individual cards using the score
  badge presentation.
- Highlight the full name area of relevant deck-builder cards with a
  translucent green background for Add and red for Cut, keeping Arena's name
  readable. This supersedes the earlier request for small detached labels.
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
  - Q4 presentation: use translucent green/red Add/Cut highlights over names?
    - Confirmed by the user. Replace detached labels in the deck builder;
      draft score badges remain unchanged.
  - Q4 copy precision: include exact copy differences with these highlights?
    - Pending user decision; do not assume the visual-style decision settles it.
    - Next: settle placement, clipping and crowded-name behavior using verified
      title-area geometry for both layouts and pool cards.
  - Q5: Use compact summaries and fade successful completion?
    - Confirmed: Yes. Show named land changes; briefly confirm and fade when
      the deck matches. Keep detailed lists and troubleshooting in FirstPick.
    - Next: define discovery of needed cards hidden by scrolling or filtering.
- Transitions and recovery
  - Q3: Maintain steady advice throughout ordinary interaction?
    - Confirmed with a correction: do not use brief disappearance and recovery
      as the default behavior. Only confirmed changes should update the overlay.
    - Next: distinguish real pack/editor changes and lost access from ordinary
      motion; decide exceptional stale-advice handling and recovery thresholds.
- Acceptance
  - Depends on Q3–Q5: agree on measurable coverage and release gates for
    drafting and both deck layouts, including resizing and actual interaction
    sequences.

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
- Precise title-area rectangles are not exposed by the production snapshot.
  Vertical whole-row and frame-image rectangles have live evidence; installed
  static metadata also identifies a separate name-text field. For pool and
  stacked cards, static metadata identifies renderer parts and Title/AltTitle
  text fillers. These are candidates for a memory-only title-geometry prototype,
  not proven live title positions. Validate the active title/face and clipping
  before claiming support for precise name-area highlights.
- The external overlay draws above Arena's rendered content; it cannot insert
  a background layer beneath Arena's own text. Use a translucent tint over the
  name area and verify legibility on different card frames, selection and hover
  states. The intended visual effect is a highlighted nameplate, without
  changing Arena's text or covering its quantity counter and mana symbols.
- Deck guidance knows exact copy differences but its current card markers omit
  those numbers. Filters can hide cards that need adding while the aggregate
  summary remains correct. The need for a discovery aid is an open design
  question, not a decision to restore the previously removed permanent list.
- Score badges use the same contextual advisor score as FirstPick's pack view.
  That score can incorporate the drafted pool and optional PickNet reranking;
  it is not the rating another companion prints inside Arena.
- Native error messages can reach the user directly. Draft geometry failure
  reasons and native elapsed times are not consumed by Kotlin; the deck reader
  does not expose an equivalent geometry failure reason.
- Response-time targets and live interaction benchmarks have not been agreed
  or measured for this improvement session.

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
Recommendation: Yes. Answer: pending.

Q5: Should normal drafting show score badges only and deck building show
markers plus a compact summary with named basic-land changes? Briefly confirm a
matching deck and then fade that message; keep detailed lists and troubleshooting
in FirstPick.
Recommendation: Yes. Answer: Yes, confirmed explicitly for question five.
