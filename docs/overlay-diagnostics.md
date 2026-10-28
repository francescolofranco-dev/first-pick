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
settings, dialogs and disconnection. These tests do not prove live draft access.

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

A real draft pack is required before accepting live picking support. Check
Quick Draft, human drafts, pick-two, identical copies, pack transitions, layouts,
selected cards, animations and changed window sizes. The current preview shows
named choices only after the complete pack and drafted pool match the advisor.
It emits no guessed per-card coordinates.

## Next: memory geometry

Installed metadata identifies `DraftContentController`, `DraftPackHolder`,
`DraftPackCardView`, `DraftPackLayoutData` and `MetaCardView`. Layout records include
columns, rows, scale, offsets and local positions. Before exposing screen bounds,
verify the parent transforms, camera/canvas projection, clipping, active state,
animation completion and viewport dimensions against real interaction. Associate
each rectangle with its exact card ID in the same coherent observation. Until
then, memory-based counts remain authoritative and there are no spatial badges.
