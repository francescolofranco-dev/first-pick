# Native acceptance — 3.12.1

The approved reference is `firstpick-interactive-prototype.html` in the design artifact directory. It was left unchanged. Comparisons use equal logical client sizes; macOS window chrome and text rasterization remain native.

## Visual checks

- Rendered the actual production composables using isolated example fixtures at 720 × 760, 1280 × 900, and 1440 × 960. Captured Draft, both Deckbuilder layouts, Analytics Overview, Match history, and archived Deck / Pick log / Matches.
- Matched the reference's 54 px toolbar, 27 px status bar, responsive navigation widths (110 / 145 / 166 px), and persistent right inspector widths (220 / 250 / 274 px). Draft starts with signals hidden. Automated geometry tests exercise compact and desktop client layouts and reach the fourteenth draft choice by scrolling.
- Corrected toolbar alignment, inline draft metadata, inline ranks, gold recommendation edge, score bars, table proportions, lighter hairlines, build rank/power placement, Close cuts, overview curve, filter labels, summary layout, and finish-chart count positioning.
- Archived reviews use underlined tabs, a persistent draft context panel, and dense tables. Missing historical picks/advisor scores remain explicitly unavailable. Recorded match dates are shown instead of reconstructing uncertain intermediate run scores.
- Native typography resolves `.AppleSystemUIFont` on macOS; other platforms retain their default interface font. No system font files are bundled. The first focus request now runs after workspace subcomposition attaches its targets; the offscreen render no longer emits initialization warnings.
- Example data and render tooling live only in test sources and never enter player storage or production analytics.

## Release verification

- `./gradlew buildProduction workbenchVisuals`: production build and full test gates; passed. 548 JVM tests, 30 native tests; zero failures or skipped tests.
- Native fixture suite: 30 tests. JVM suite includes two new layout regressions and a repeated Command-K / Escape focus regression.
- Production installation: `~/Applications/FirstPick.app`, using `scripts/run-production.py` with development/preview flags disabled.
- No new performance benchmark results are claimed. Acceptance uses the shipping-equivalent bundled-JVM app and optimized native helpers.

## Data behavior retained

- Filters intersect expansion, format, and calendar month / recent period, and remain applied when navigating within Analytics.
- Exact Arena season assignments are absent from older records; month filters are labelled honestly.
- Archived cards use the recorded saved deck when available; otherwise the UI explicitly shows the recorded pool. No historical recommendations, card-taken attribution, or ratings are invented.
- Live Arena geometry was not measured while Arena was idle. Existing overlay and native interaction regressions remain part of release verification.

## Installed app acceptance

- Verified version 3.12.1 in the installed app and strict/deep signature validation through the production installer.
- Opened real Analytics records, filtered to Reality Fracture, and confirmed 16 completed drafts / 72 linked matches stayed selected across Overview, Match history, archived review, and back.
- Opened an actual saved 40-card deck and recorded packs; checked explicit missing-match state. Saved native screenshots alongside the deterministic comparison renders.
- Search opens from its toolbar control. Command-K / Escape / reopen passes in the production-composable input regression. Desktop automation did not deliver keyboard input reliably, so OS-level shortcut acceptance is not claimed from that tool.
- Comparison artifacts: `fidelity/release-3.12.1/` in the design artifact directory.
