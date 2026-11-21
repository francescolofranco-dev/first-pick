# Screenshots

The [README](../../README.md) uses these images of the current workspace:

- `draft-dashboard.png` — ranked picks, mana costs, pool summary, and color direction.
- `deck-builder.png` — suggested builds, vertical card list, close cuts, curve, and manabase.
- `analytics.png` — performance overview and finish distribution.
- `draft-history.png` — saved drafts with shared filters and the Open action.

These are offscreen renders of the production Compose UI with fixed, illustrative
sample data from `WorkbenchVisualFixtures`. They do not read a player's database
or Arena log, and the displayed scores and results are not performance claims.

## Refresh

From the repository root, with JDK 21:

```bash
./gradlew workbenchVisuals
cp build/workbench-visuals/draft-1280.png docs/screenshots/draft-dashboard.png
cp build/workbench-visuals/deck-1280.png docs/screenshots/deck-builder.png
cp build/workbench-visuals/analytics-1280.png docs/screenshots/analytics.png
cp build/workbench-visuals/history-1280.png docs/screenshots/draft-history.png
```

The runner also produces smaller and wider layouts for visual checks. Inspect each
chosen PNG at full size before committing. Keep the filenames stable so links
continue to work, and update the README captions when a view changes. The older
card-preview, data-source selector, and deck-list screenshots were retired with
the previous dashboard design.
