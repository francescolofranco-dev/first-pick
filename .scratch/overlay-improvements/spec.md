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
- Attach small Add/Cut markers to relevant deck-builder cards.
- Identify the specific basic lands to add or cut.
- Make the overlay accurate, responsive and steady during ordinary interaction.

## Design tree

- Scope and success criteria
  - Q1: Improve overlay quality while preserving recommendation algorithms?
    - Pending user decision.
    - Next: define measurable accuracy, stability and response-time gates.
- Interaction boundary
  - Q2: Keep all in-game marks click-through and put controls in FirstPick?
    - Pending user decision.
    - Next: decide where explanations, settings and recovery actions belong.
- Presentation, anchored by the settled card-attached design
  - Next: define information density, visual hierarchy and crowded-card behavior.
- Transitions and recovery
  - Next: decide presentation during scrolling, animations, layout changes,
    stale reads, focus changes and lost access.
- Acceptance
  - Next: agree on coverage for drafting and both deck layouts, including
    resizing and actual interaction sequences.

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
- Deck guidance knows exact copy differences but its card-attached markers omit
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
Recommendation: Yes. Answer: pending.

Q2: Should the in-game overlay remain entirely click-through, with controls,
explanations and troubleshooting in FirstPick?
Recommendation: Yes. Answer: pending.
