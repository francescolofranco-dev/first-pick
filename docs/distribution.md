# Distribution: signed & notarized `.dmg` + Homebrew

FirstPick can ship as a signed, notarized macOS `.dmg` (no Gatekeeper warning, no
`xattr` dance) and via a Homebrew cask. The build config and CI are already wired
for this — they read all credentials from the environment, so nothing secret lives
in the repo. With the credentials **unset**, builds are simply unsigned (the
current behavior); once they're present, the release pipeline signs + notarizes.

This document is the runbook for turning it on.

## Status

- [x] `build.gradle.kts` — `macOS { signing { } notarization { } }`, env-driven, `bundleID = com.firstpick.app`
- [x] `packageVersion` overridable via `-PpackageVersion` (release sets it from the tag)
- [x] `.github/workflows/release.yml` — tag-triggered build → sign → notarize → GitHub Release
- [x] `packaging/homebrew/Casks/firstpick.rb` — cask template
- [ ] **Apple Developer Program enrollment** (you — ~$99/yr)
- [ ] **Repo secrets** added (you)
- [ ] **`homebrew-firstpick` tap repo** created (you)

## One-time prerequisites (you)

1. **Enroll** in the paid Apple Developer Program as Account Holder:
   <https://developer.apple.com/programs/>. The free tier cannot create the cert below.
2. **Create a "Developer ID Application" certificate** (Xcode → Settings → Accounts →
   Manage Certificates, or developer.apple.com → Certificates). Note the exact
   identity string, e.g. `Developer ID Application: Your Name (TEAMID)`.
3. **Export it** from Keychain Access (the cert *and* its private key) as a
   password-protected `.p12`, then base64-encode it:
   ```bash
   base64 -i FirstPick.p12 | pbcopy   # → MACOS_CERTIFICATE_P12
   ```
4. **Create an app-specific password** for notarytool at <https://appleid.apple.com>
   → Sign-In and Security → App-Specific Passwords. (`altool` is retired; the build
   uses `notarytool`.)
5. **Note** your 10-character **Team ID** and the Apple ID email used for notarization.
6. **Register the App ID** `com.firstpick.app` (Identifiers in the developer portal).

## Repo secrets (Settings → Secrets and variables → Actions)

| Secret | Value |
| --- | --- |
| `MACOS_CERTIFICATE_P12` | base64 of the `.p12` |
| `MACOS_CERTIFICATE_PWD` | the `.p12` export password |
| `MACOS_SIGNING_IDENTITY` | `Developer ID Application: Your Name (TEAMID)` |
| `NOTARIZATION_APPLE_ID` | your Apple ID email |
| `NOTARIZATION_PASSWORD` | the app-specific password |
| `NOTARIZATION_TEAM_ID` | your 10-char Team ID |

## Cutting a release

```bash
git tag v1.0.0      # macOS requires the bundle major ≥ 1, so start at 1.0.0
git push origin v1.0.0
```

`release.yml` then runs on a macOS runner: imports the cert, runs
`./gradlew notarizeDmg -PpackageVersion=1.0.0` (build → sign → notarize → staple),
computes a SHA-256, and publishes a GitHub Release with the `.dmg` and `.sha256`.

Locally you can produce the same artifact (signed, if the env vars are set):
```bash
MACOS_SIGNING_IDENTITY="Developer ID Application: … (TEAMID)" \
NOTARIZATION_APPLE_ID="you@example.com" \
NOTARIZATION_PASSWORD="abcd-efgh-ijkl-mnop" \
NOTARIZATION_TEAM_ID="TEAMID" \
./gradlew notarizeDmg -PpackageVersion=1.0.0
# → build/compose/binaries/main/dmg/FirstPick-1.0.0.dmg
```

## Homebrew cask

1. Create a public repo **`github.com/francescolofranco-dev/homebrew-firstpick`**.
2. Copy `packaging/homebrew/Casks/firstpick.rb` to its `Casks/firstpick.rb`.
3. After each release, set `version` + `sha256` (from the release's `.sha256`), or run
   `brew bump-cask-pr --version <v> firstpick`.

Users then install with:
```bash
brew install --cask francescolofranco-dev/firstpick/firstpick
```

## Verifying on a clean Mac

```bash
spctl -a -vv /Applications/FirstPick.app     # → "accepted", source=Notarized Developer ID
stapler validate /Applications/FirstPick.app # → "The validate action worked!"
```

## Notes

- `macos-latest` is Apple Silicon, so the dmg is **arm64**. To also ship Intel, add a
  `macos-13` matrix leg and publish both dmgs under distinct names.
- The release uses `notarizeDmg` (not the ProGuard-minified `notarizeReleaseDmg`) to
  avoid needing Compose keep-rules. Switch later if you want a smaller bundle.
