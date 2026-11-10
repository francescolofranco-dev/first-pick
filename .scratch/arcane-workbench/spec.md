# Arcane workbench implementation

Approved direction: the interactive prototype reviewed in this chat. Replace the old bento shell with Draft / Deckbuilder / Analytics, a context-sensitive left rail, bottom-left history/guides/about/settings, midnight/violet/gold surfaces, compact ranked draft choices, and read-only build lists. Keep native data and overlay authorization. Card hover shows only the image. Never invent ratings, historical deck snapshots, pick decisions, or season assignments.

Implementation scope: native workspace and live data bindings, vertical/horizontal deck layouts, rank/Power, close cuts calculated from pool copies, composable analytics filters and completed-result distribution, archived draft review using stored records. Preserve existing guide and access workflows. Missing historical information receives an explicit empty state.

Validation: pure model tests for copy subtraction, filters and completed-result histograms; existing JVM/native regressions; production build and visible native-app acceptance. Version 3.12.0. Integrate the verified release into main.
