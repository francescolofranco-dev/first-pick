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

## App versions

`VERSION` is the single source of truth for the visible app version and package
version. Set up the commit guard once per clone:

```bash
git config core.hooksPath .githooks
```

Before committing an app change, bump and stage `VERSION`: patch for fixes or
internal changes, minor for backward-compatible features, major for breaking
changes. Bump before testing. Documentation-only and test-only commits do not
need a new version. Release tags and any `-PpackageVersion` override must match
`VERSION`. The guard checks the staged version, so unstaged bumps do not count.

## Arena Limited releases

[Sync Arena Limited sets](.github/workflows/sync-limited-sets.yml) checks Scryfall
and 17Lands every six hours. It considers Arena card pools starting seven days
before Scryfall’s tabletop date, since Arena can open early. It saves the
[Wizards release calendar](docs/limited-release-calendar.json) daily when dates
change, and records a successful calendar check at least every 28 days. The
calendar keeps a history of what was announced and gives the public repository
activity during gaps between sets. GitHub can disable schedules after 60 days
of inactivity. If that happens, enable the workflow in Actions and run it on
`main`.

The first app update adds the set as soon as an Arena card pool is available.
FirstPick uses card roles, curve, and provisional color-pair themes inferred
from multicolor uncommons. A bundled, researched profile in
`src/main/resources/synergy/<CODE>.json` takes precedence where available.
Once at least 30 cards have 200 or more games in hand on 17Lands Premier Draft,
the workflow publishes the ratings-ready update. FirstPick queries live card
and color-pair ratings directly, so newer observations can influence advice
before and after that update. The weekly pick-model training workflow is
separate and depends on the public raw draft dataset.

Set changes stage a patch bump, pass tests, and fast-forward `main` before
calling the existing macOS release workflow with that exact commit. A later
sync retries the current version if packaging or publishing failed. Calendar
changes alone need no version bump. The workflow uses the repository Actions
token and requires permission to fast-forward `main`. Release signing uses
the same settings as ordinary FirstPick releases.
