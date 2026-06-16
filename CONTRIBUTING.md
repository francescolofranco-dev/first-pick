# Contributing to FirstPick

Thanks for your interest! FirstPick is a Kotlin + Compose Multiplatform desktop
app. This guide covers how it's built and where things live.

## Build & test

Requires JDK 21. The Gradle wrapper pins the Gradle version.

```bash
./gradlew test    # run all unit tests
./gradlew run     # launch the app
```

Code style is Kotlin official (`kotlin.code.style=official`). Keep new code in the
idiom of the surrounding code; prefer small, pure, testable functions.

## Architecture

A one-way coroutine `Flow` pipeline turns log lines into UI state:

```
Player.log ──tail──► EventParser ──► DraftState (reducer)
                                          │
   17Lands ratings ─┐                     ▼
   archetype data  ─┼─► repositories ► AdvisorEngine ► DraftUiState ► Compose UI
   Scryfall facts  ─┘                     ▲
                              LaneDetector / DeckNeeds / SignalsEngine / DeckBuilder
```

Source layout (`src/main/kotlin/com/firstpick/`):

| Package      | Responsibility |
|--------------|----------------|
| `log/`       | Tail `Player.log` (`LogWatcher`), decode events (`EventParser`), fuzzy matching (`LogMatch`) |
| `model/`     | Domain model + the pure draft-state reducer (`Draft.kt`) |
| `draft/`     | `DraftTracker` — folds log lines into a `StateFlow<DraftState>` |
| `cards/`     | Data layer: 17Lands ratings + archetype data, Scryfall metadata/roles, MTGA card DB, set metrics |
| `advisor/`   | Scoring: `AdvisorEngine`, `LaneDetector`, `DeckNeeds`, `DeckBuilder` |
| `signals/`   | Open-lane signal detection |
| `ui/`        | `DraftViewModel` (orchestration) + Compose UI (`App.kt`, `Theme.kt`, `UiState.kt`) |
| `core/`      | `AppPaths` (config/cache locations) |
| `tools/`     | Dev CLIs: `Replay` (reconstruct a draft from a log), `RankDemo` (advisor on a pack) |

The advisor algorithms are a Kotlin port of the reference Python tool
[`MTGA_Draft_17Lands`](https://github.com/unrealities/MTGA_Draft_17Lands); its
`docs/` describe the scoring model.

## Testing notes

- Pure logic (parser, reducer, advisor, lane, deck-needs, deck-builder) is unit
  tested without the network — see `src/test/`.
- Network clients are verified manually via the `replay` / `rankDemo` dev tasks
  against a real `Player.log`.
- Gotcha: Compose `LazyColumn` keys must be unique — packs and decks can contain
  duplicate cards, so never key a card list by name alone.

## Data sources

Be a good citizen of [17Lands](https://www.17lands.com/) (CC-BY-4.0) and
[Scryfall](https://scryfall.com/): keep requests cached and throttled, and send a
descriptive `User-Agent`. The app must remain read-only with respect to Arena.

## Pull requests

- Add or update tests for logic changes; keep `./gradlew test` green.
- Keep the advisor's reasoning explainable — every score should map to a reason a
  drafter would recognize.
