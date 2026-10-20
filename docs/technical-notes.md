# Technical notes

Details moved from the README.

## Requirements and deck-guidance limits

- macOS (Apple Silicon or Intel)
- MTG Arena, with **Options → Account → Detailed Logs (Plugin Support)** enabled
- For the draft and deck-builder overlays: **Screen Recording** permission (System Settings →
  Privacy & Security → Screen Recording). macOS prompts on first use; FirstPick uses
  it solely to find where the cards are drawn. Frames are processed locally through
  short-lived temporary files that are deleted after each pass; they are never retained
  or sent anywhere. The rest of the app works without it.

Deck guidance currently supports Arena's vertical compact deck list with the sideboard
collapsed. It marks only cards and rows visible on the current pool page or deck-list
scroll position. A persistent native worker reads the deck panel and pool title bands,
reusing OCR for unchanged regions. Returning from another app or an unreadable builder
requires fresh verification. Scroll through the deck or pool to expose unknown cards.

The build page also compares your chosen build with Arena's **last saved deck**, using
server-confirmed course data from the log. This comparison is explicitly separate from
unsaved on-screen edits. The chosen build stays fixed until you choose another build.

## How it works

A coroutine pipeline: tail `~/Library/Logs/Wizards of the Coast/MTGA/Player.log`
→ reconstruct the live draft → fetch + cache 17Lands ratings, archetype data, and
Scryfall card facts → combine them with bundled, reviewed set guidance → run the
contextual advisor → render a Compose dashboard.
It only ever **reads** the log and your local MTGA card database — plus, when the
overlays are on, screen frames of the Arena window analyzed locally to locate cards
and read the visible deck-builder counts. Capture and OCR use short-lived temporary
files that are deleted after each pass. FirstPick never writes to Arena, blocks Arena's
Done button, or retains or uploads frames.

See [CONTRIBUTING.md](CONTRIBUTING.md) for the architecture.

## Build an app for everyday use

From your up-to-date repository checkout on macOS:

```bash
./gradlew buildProduction      # compile native helpers, run tests, and build FirstPick.app
./gradlew runProduction        # do the same, then replace and open the installed app
```

The app is written to `build/compose/binaries/main/app/FirstPick.app`.
`runProduction` quits existing packaged FirstPick instances, installs the verified
build at `~/Applications/FirstPick.app`, and opens that copy. Use this command for
updates so every checkout launches the same installation.
It bundles its own JVM and the optimized capture/OCR helpers for your Mac's
architecture, and does not enable the development demo launcher. `runProduction`
also disables the demo, overlay tracking, deck-preview, and smoke-test environment
flags. The first build needs
the Xcode command-line tools and can take several minutes; unchanged native
helpers and tests are reused on later builds. Changes to the Swift toolchain,
SDK, or native sources trigger a rebuild. To force all build steps to run again,
add `--rerun-tasks`. `createDistributable` also includes these native-build and
test checks; ordinary `run` and `test` do not require a native rebuild.
Without `MACOS_SIGNING_IDENTITY`, `buildProduction` signs the app and its bundled
helpers with a persistent local certificate. The keychain and its unlock file live
in `~/Library/Application Support/FirstPick/signing` with private permissions;
keep this directory across builds and checkouts. No system trust or screen-recording
settings are changed. This is local signing, not Apple notarization. Switching from
an older ad-hoc build may require one new Screen Recording grant and a full quit
and reopen. Subsequent builds retain the same certificate-backed identity.
`createDistributable` alone remains an unsigned packaging test when no Developer ID
is configured; use `buildProduction` or `runProduction` for everyday local builds.

Local builds target this Mac. For cross-architecture packaging, use a matching
JDK and add `-PnativeArchitecture=universal`; the release workflow uses that
option to bundle both native architectures (which requires an SDK supporting
both).

This is the same build type used by the shipped DMG, not the experimental
ProGuard `*Release` tasks. Both development and packaged apps use JVM JIT
optimization; packaging is not a different OCR algorithm and does not itself fix
card-recognition errors. Local builds are not notarized unless signing and
notarization are configured (see [the distribution runbook](docs/distribution.md)).
If packaging cannot find a full JDK, pass `-PcomposeJdk=/path/to/full/jdk21` to
either command, or set `COMPOSE_JDK`.

## Development

```bash
native/macos/build.sh          # once per native-helper change (macOS only)
./gradlew run                  # launch the app (fastest; uses your dev JDK)
./gradlew test                 # unit tests
./gradlew createDistributable  # build a runnable FirstPick.app for local testing
./gradlew packageDmg           # build the macOS .dmg

# dev helpers (replay / inspect real logs)
./gradlew replay   -PlogPath="$HOME/Library/Logs/Wizards of the Coast/MTGA/Player.log"
./gradlew rankDemo -PlogPath="<log>" -Pstop="2:3"   # advisor output for a mid-draft pack
```

`createDistributable` writes `build/compose/binaries/main/app/FirstPick.app`
(`open` it or copy to /Applications). Packaging needs a **full** JDK that ships
`jpackage`; if your default JDK is an IDE's bundled JBR (which omits it), point
packaging at one:

```bash
./gradlew createDistributable -PcomposeJdk="$(/usr/libexec/java_home)"   # or set COMPOSE_JDK
```

Env vars: `FIRSTPICK_LOG=<path>` watches a custom log; `FIRSTPICK_FORCE_DECKS=1`
previews the deck builder mid-draft; `FIRSTPICK_SMOKE=1` opens then self-exits;
`FIRSTPICK_DEMO=1` shows the built-in demo-draft launcher (a dev aid that `./gradlew
run` sets automatically, and the packaged app omits).

Brand assets live in `packaging/icon/` (SVG source + `AppIcon.iconset`) and `docs/`
(banner, mark, social preview). Regenerate the `.icns` from the iconset with
`packaging/icon/build-icon.sh` (macOS only).

## Reality Fracture

`FRA` has a researched guide covering all ten color pairs, card roles, twenty
interactions, and the set's mechanics, including empower Jace, Heartwood, and
prepare. The advisor and deck builder use the same profile as the guide.

FRA includes a dedicated Premier Draft PickNet model trained on public 17Lands
FRA picks. It refines suggestions within the existing guide and mana guardrails;
other formats continue to use their own live ratings and contextual advisor.
See the [model validation report](docs/fra-model-validation.md) for provenance,
card coverage, and the independent held-out comparison.

When 17Lands has no ratings for a format yet, FirstPick loads card details from
Scryfall and resolves missing Arena IDs from the installed Arena database.
The app labels this as card-catalog mode and leaves unobserved card power unrated.
Retry ratings to use 17Lands data once published. FRA remains eligible for the
existing weekly model-training selection as its public draft data grows.

## Starting another draft

FirstPick clears the old pool and signals when Arena reports a new draft or course ID,
including repeated runs of Pick Two Draft. If detection misses a transition, click
**New draft** in the header before entering the draft in Arena. This clears the tracked
pool, pack, and recommendations and follows only new log activity. If the new pack is
already open, re-enter the draft in Arena to obtain a fresh snapshot. Cards from before
the reset can only be recovered if Arena sends a full pool snapshot.


## Deck construction and splashes

Draft creature-curve targets combine the set profile's archetype speeds with the
selected color pair's plan. Missing speed guidance uses the existing balanced
curve.

The builder compares clean two-color builds and each possible single-color
splash, aiming for three credible options. It does not pad the third slot with
incomplete builds or a build more than 12 power-score points behind the leader.

A splash package needs a haymaker assessment separate from mana feasibility.
The same rules apply to every set, with no named-card exceptions or reviewed
haymaker lists. Reliable results must be exceptional relative to that set:
at least 200 games, 1.5 standard deviations above the mean and 4.5 percentage
points of improvement when drawn, or 2 standard deviations when improvement
data is unavailable.

Effect metadata identifies broad unconditional creature sweepers, planeswalkers
with interaction or card draw, repeatable card-advantage triggers, finishers,
and card-draw roles. High mana value, an ordinary body, single-target removal,
or the planeswalker type alone does not qualify a card. Cards costing three or
less also need repeatable advantage, at least 2.5 standard deviations above the
mean, and 6 percentage points of improvement when drawn. These are general
heuristics, not a complete rules-text interpreter. Conditional sweepers are not
assumed to have their setup satisfied. Unknown performance does not establish a
haymaker.

Every completed splash still needs the pip-, timing-, and fixing-aware mana
checks without starving its base colors. The explanation names the qualifying
card, describes its general impact, and shows available versus required splash
sources. These rules also apply when filling short pools.

## Randomized deck-building audit

`./gradlew deckAudit` validates complete seeded demo drafts and random pools
against frozen data from three sets. It checks card conservation, color and
splash legality, completeness, and conservative quality substitutions.
Use `-PauditSeedStart=100 -PauditSeeds=100` to check another 600 pools.
See [the audit report and reproduction instructions](docs/deck-building-audit.md).


## Mana access and fixing

Draft uses full base-color source targets without a two-source discount. Basic and typed landcycling use their printed activation
cost and require an allocated basic of a matching type. Unknown costs, late
activation, and activation requiring the missing splash color receive no
credit. The cycling credit is capped by matching basic targets; it is not also
counted as a normal fixing spell.

Timely base-colored draw, draw/discard, scry, and surveil effects receive 0.25
source-equivalent credit per extra card seen, capped at one for the deck.
This is a bounded heuristic, not a calibrated draw-probability calculation.
Credit applies to splash access only; it cannot lower the base-color targets
or replace the minimum three source accesses to the splash color. Splash-colored
or late draw cannot justify its own mana. Known conditional fixing (such as
Treasure on death) is not promised as an unconditional source.

Ordinary eligible fixing spells still count as one source. Tapped-land tempo,
full one-use/repeatable mana simulation, and complex conditional effects remain
approximations. Metadata refreshes once to recognize the new effects, retaining
older metadata if offline. The splash explanation shows landcycling and partial
selection credit separately.

Landcycling rules follow the [official mechanics explanation](https://magic.wizards.com/en/news/feature/final-fantasy-mechanics).
