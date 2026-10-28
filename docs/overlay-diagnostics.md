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

Draft cards use the restored colored score crests at their lower edge. Deck
guidance uses Add/Cut labels, with smaller labels for stacked headers. The full
card-name change list has been removed from the overlay; named basic-land
instructions remain available.

The installed production reader was checked against 27 live deck positions in
the column layout and 7 pool-card positions, all matching the authoritative
40-card deck/pool IDs and quantities. The app reached `Overlay · Synced` after
the original pool-and-deck view was restored. Evidence is saved in
`prototypes/arena-memory/verified-card-geometry-macos-arm64.json`.
Draft projection also has native synthetic graph tests for duplicate IDs,
visibility, invalid transforms and dirty layout state. A real draft-pack visual
acceptance test remains deferred at the user's request.
