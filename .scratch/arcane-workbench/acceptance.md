# Native acceptance — 3.12.0

- `./gradlew buildProduction`: passed; 545 JVM tests, 30 native fixture tests.
- Local production app signed and verified, installed at `~/Applications/FirstPick.app`, and restarted with preview/debug environment flags disabled.
- Native 720 × 760 window: checked navigation, readable button contrast, data-driven finish chart and real saved records.
- Command-K opens card/workspace search; Escape dismisses it.
- Expansion filtering persists from Analytics Overview to Match history and back from an archived draft.
- Opened an actual saved FRA draft: recorded 40-card deck, available pack snapshots, and explicit absence of linked individual matches.
- No synthetic ratings or archived recommendations enter production. Exact Arena season assignment is absent from old records; date filters label calendar months honestly. Archived picks are shown as recorded packs, without invented card-taken attribution or historical advisor scores.
- Live Arena draft geometry/interaction was not measured: Arena was idle during acceptance. Existing overlay/native regressions passed. The production build is performance-ready; no new performance benchmark results are claimed.

The legacy hover fixture had a startup race. Its synchronization now waits for both newly created windows and the actual Compose input listener instead of accepting leftover windows or sleeping for a fixed duration.
