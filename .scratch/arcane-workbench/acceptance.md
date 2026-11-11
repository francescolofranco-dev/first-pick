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

## Waiting-screen redesign — 3.12.2

- Replaced the legacy hero panel with a quiet, centered entry state: native vector brand mark, official mana symbols, clear draft-start guidance, and compact plain-text Arena setup instructions.
- Removed Deep synergy, Data synergy, and AI picks coverage lists and their unused private UI helpers. Idle navigation now explains that no draft is active instead of presenting empty pack/pool counters.
- Retained developer-only demo entry and the distinct between-packs message. Returning to idle clears the pool view so the waiting screen appears consistently.
- Rendered 720 × 760, 1280 × 900, and 1440 × 960. Verified the installed 3.12.2 production app visually and through its accessibility tree; no coverage lists or zero-card controls are present.
- `./gradlew buildProduction workbenchVisuals`: passed; 548 JVM tests and 30 native tests. Production installer verified its signature and restarted `~/Applications/FirstPick.app`.
- Screenshots: `fidelity/release-3.12.2/` in the design artifact directory, including `native-waiting.png`.

### Integrated release — 3.12.3

Merged main's concurrent navigation cleanup (`e7830a2`) without restoring the redundant Draft history footer shortcut. The combined waiting-screen release uses 3.12.3 to advance both parents' 3.12.2 versions. Final comparison artifacts are in `fidelity/release-3.12.3/`.

`./gradlew buildProduction workbenchVisuals` passed for the merged source: 548 JVM tests, 30 native tests. The production installer verified the bundle signature and restarted 3.12.3. Its visible/accessibility state confirms the new waiting page, no coverage lists, no idle counters, and no redundant Draft history footer entry. Final native proof is `release-3.12.3/native-waiting.png`.

### Integrated release — 3.12.4

Preserved main's subsequent compact-toolbar search-icon fix (`cf1395d`) as well. The final waiting-screen build includes both navigation improvements and uses 3.12.4. Its comparison artifacts are in `fidelity/release-3.12.4/`.

Final integrated gate passed: 548 JVM tests and 30 native tests, zero failures or skips. Installed and visibly verified 3.12.4, including the accessible vector Search button, simplified idle rail, and redesigned waiting content. Native proof: `release-3.12.4/native-waiting.png`.

## FirstPick logo — 3.12.5

Replaced the provisional star drawings in the shared toolbar and waiting screen with the bundled `icons/app_icon.png` used by the app/Dock. The toolbar uses a larger 26 / 30 dp icon at compact / desktop sizes; the waiting screen uses an 84 dp icon. Both expose “FirstPick logo” to accessibility. Rendered the actual composables at all three comparison sizes.

`./gradlew buildProduction workbenchVisuals` passed: 548 JVM tests, 30 native tests. Installed and visually verified the signed production app at version 3.12.5. Screenshot: `fidelity/release-3.12.5/native-logo.png`.
