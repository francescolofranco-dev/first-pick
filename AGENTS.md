# Working agreements

- Complete and verify implementation changes, then commit and integrate them into `main`. Never commit partial or failing work.
- Every commit changing the app (`src/main`, native helpers, packaging, or build configuration) must include a semantic version increase in `VERSION`. Use PATCH for fixes and internal changes, MINOR for backward-compatible features, and MAJOR for breaking changes. Reset lower components when increasing MINOR or MAJOR. Documentation-only and test-only commits need no bump.
- Choose and stage the version before running checks so the tested version is the committed version. The header, About window, update checker, and package all use this file. Release tags must match it.
- Enable the version guard in each clone with `git config core.hooksPath .githooks`. Do not bypass it for app changes.
- After a requested app fix or feature, rebuild the production app and restart it when a running FirstPick instance needs the change; verify the visible result.
