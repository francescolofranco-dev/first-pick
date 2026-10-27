# Overlay diagnostics and baseline replay

Milestone A adds standalone developer tools. It does not change draft matching,
deck reconciliation, rendering, polling, or normal application startup. Existing
`overlayDebug` remains available, but its current-log comparison is not an accuracy
baseline for an older screenshot.

## Run the initial offline baseline

```sh
./gradlew overlayReplay
```

The default input is `src/test/resources/overlay/baseline.json`. The output is a
new `build/reports/overlay-baseline/` directory containing `report.md` and
`trace.json`. Choose a different output directory for subsequent runs; reports
and recordings are never overwritten:

```sh
./gradlew overlayReplay -Pmanifest=/absolute/path/manifest.json \
  -PreportDir=/absolute/path/new-report-directory
```

Replay does not read Player.log, fetch reference images, invoke native capture or
OCR, or access a running Arena instance. It uses the current detector, recognizer,
three-read settler, title correction, screen interpreter, deck guidance session,
and presentation reducer. Captured OCR is input evidence, not newly recognized
text. A new OCR implementation will need a separate evaluation from source pixels.

The draft replay stops recognition once its assignment locks, matching the current
tracker. It retains the current behavior of pairing a selected assignment with the
latest calibration. Context changes reset sessions. A deck `focus_lost` resets its
session because it supplies no capturable window evidence. A deck frame with
`window.frontmost=false` continues the reading but suppresses visible instructions,
matching background deck capture. `reader_failure`, `outside`, and explicitly annotated `tick` events test
presentation gaps; tick events clear the model as the current watchdog does.
Replay is a component/pipeline baseline, not an instrumented production UI. It
does not reproduce the production capture debounce, retry limit, coroutine
cancellation, actual window bounds/focus changes, or Compose frame scheduling.

## What the initial corpus establishes

- Four existing real-image recognition cases: Repulsor/Avengers, Arcane/Killian,
  missing Swamp reference, and sideways-room references.
- Two full Arena grid captures, with no card identity annotations or references.
- Six distinct images and fourteen observations. Repeated stills exercise the
  current settling code; they are not independent samples or recorded animation.
- Ten annotated identity observations, on the third read of the component cases.
  Initial acquisition frames are deliberately unannotated.
- Recognition crops and labels come from existing `CardRecognizerTest` fixtures.
  Cropped cases bypass detection and cannot establish full-screen layout quality.
  Room A/B are fixture identities, not resolved canonical names.

These fixtures do not include the user's FRA swapped-rating screenshot with
matching reference assets, any real deck-edit timeline, or a recording of badge
movement. Those remain explicit gaps. The synthetic deck timeline in the new unit
tests verifies that replay respects failure/focus events and separates unknown
counts from incorrect counts; it is not evidence of real deck-reading accuracy.

## Record a deliberate Arena session

Recording is never enabled by running FirstPick normally, running tests, or running
replay. Start the separate command only when you intend to save the game's window:

```sh
./gradlew overlayRecord -Pcontext=/absolute/path/context.json \
  -PrecordingDir=/absolute/path/new-recording-directory \
  -PrecordFrames=60 -PrecordIntervalMs=300
```

The command stores at most 300 observations, 120 seconds, and 250 MiB of reference
assets plus frame images. Defaults are 60 observations and a minimum 300 ms between
request starts. Slow capture/OCR increases that interval. It does not edit Arena,
choose cards, read account stores, or copy Player.log. Arena images can contain
player names and other visible game UI; inspect them before sharing a recording.
Draft mode saves only foreground Arena frames; background attempts become
`focus_lost` events. Deck mode also saves capturable Arena frames while the game
is inactive, recording `window.frontmost=false`. If the Arena window disappears,
it records `focus_lost`; an unavailable capture becomes `reader_failure`.

For draft mode it captures through `WindowCapture` and records `VisionOcr` output.
For deck mode it uses the current `DeckFrameReader`, including its current pixel/
text freshness behavior. It stores no reference image URLs; all references are
copied into the recording. Files stay local. No automatic upload or corpus commit
is performed. Delete a recording directory when it is no longer needed.

The manifest is written before capture and after each observation. If interrupted,
it remains marked incomplete with the successfully stored observations. Existing
directories are rejected. Failed/skipped reads count toward the observation limit.
The timestamp `atMs` is the capture **request start**, not a hardware frame timestamp;
`completedAtMs` is the capture/OCR completion before PNG serialization. The recorder
adds diagnostic work and is not a measurement of normal overlay performance.

## Prepare the recording context

Use the following shape for `context.json`, with actual names, local reference
images, and counts for the session being recorded. It must have exactly one context
and no frames. References resolve relative to the context file. The initial
baseline manifest shows crop examples for reference strips.

```json
{
  "schema": 1,
  "description": "Controlled deck edits: duplicates, scrolling, focus gap",
  "provenance": "manual context; annotate truth after recording",
  "appVersion": "filled by recorder",
  "contexts": [{
    "id": "deck-session-1",
    "mode": "deck",
    "cards": [
      {"name": "Negate", "count": 2},
      {"name": "Abrade", "count": 1}
    ],
    "target": [
      {"name": "Negate", "count": 1},
      {"name": "Abrade", "count": 1}
    ]
  }],
  "frames": []
}
```

For `draft`, cards must describe the exact pack in log/input order. Give each card
its `reference` image path (and `isRoom` when relevant). Optional `value` records
the main app's rating so output ratings can be inspected. A missing reference is
represented by null; it is not fetched from the network. Do not invent identities
from screen slot order. To test another pack or draft session, use a new context ID.
For `deck`, cards are the full drafted pool and target is the chosen build. A partial
pool cannot establish deck accuracy. Preparing contexts automatically from the
application is a follow-up; this developer command currently needs a supplied one.

## Annotate and inspect

Each frame has its context, image, request/completion times, and optional OCR.
Optional `action` records a human description. Add independently checked truth:

- `expectedCards`: a map from screen rectangle index to card identity. This scores
  published assignments, not raw guesses. Annotate only the slots being evaluated.
- `expectedCounts`: nonbasic card names to exact current main-deck counts.
- `expectedStatus`: the expected guidance state when known.
- `recognitionRects`: component-only pixel crops. Omit this for grid-detection tests.
- `textAvailable`: distinguishes a failed/unrecorded OCR pass from an empty result.

Inspect `trace.json` for raw assignments, the three nearest artwork candidates per
rectangle, title anchors, published assignments and ratings, geometry source,
recognition-to-render rectangle offset, known counts, status, and guidance marks.
Duplicate image hashes expose repeated inputs. Wrong, missing, and unknown results
are counted separately so suppressing all guidance cannot look accurate.

The report includes annotated identity precision and correct coverage together.
Unannotated cards are not scored. Unannotated count/status results do not establish
accuracy. Counts are observation-weighted, not independent card/sample counts.
Recognition-to-render offset is diagnostic disagreement, not annotated placement
error. Live latency, display flicker, badge jitter, click-through, late coroutine
results, and task-completion time remain unmeasured.

## Next evidence collection

1. Capture normal and Pick Two packs with their exact references and verified
   identities. Include the swapped-rating case if it can be reproduced.
2. Capture actual deck edits with a full pool and fixed target: add/remove copies,
   remove a last copy, change lands, scroll, filter, swap at equal totals, and refocus.
3. Annotate action boundaries, readable/occluded regions, correct counts, and which
   frames are settled. Keep separate held-out recordings.
4. Record the visible screen alongside the inputs to evaluate badge placement and
   flicker. This recorder does not capture the overlay output itself.
5. Add production display-event instrumentation before evaluating asynchronous
   cancellation or claiming latency parity. No recognition changes are justified
   solely by a clean score on the initial component corpus.
