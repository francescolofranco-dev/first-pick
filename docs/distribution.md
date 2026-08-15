# Distribution: signed & notarized `.dmg` + Homebrew

FirstPick's release pipeline ships tagged releases as signed, notarized macOS `.dmg`
files (no Gatekeeper warning and no `xattr` workaround) and can also ship via a
Homebrew cask. The build config and CI read credentials from the environment, so
nothing secret lives in the repository.

The release workflow deliberately fails closed: a `v*` tag cannot publish unless
all signing and notarization secrets are configured. A manually dispatched workflow
may omit the secrets, but its output is named `*-unsigned.dmg`, retained only as a
short-lived Actions artifact, and never published to GitHub Releases.

This document is the runbook for turning it on.

## Status

- [x] `build.gradle.kts` — `macOS { signing { } notarization { } }`, env-driven, `bundleID = com.firstpick.app`
- [x] `packageVersion` overridable via `-PpackageVersion` (release sets it from the tag)
- [x] `.github/workflows/release.yml` — signed tag releases plus unsigned manual packaging tests
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

`release.yml` then runs two architecture jobs on Apple Silicon macOS runners. Each
job imports the certificate and runs the `notarizeDmg` Gradle task with the version
from the tag (build → sign → notarize → staple), validates the stapled ticket, and
computes a SHA-256. The publish job verifies both checksums and refuses any
`*-unsigned.dmg` before creating the GitHub Release. Tags must use the exact
`vMAJOR.MINOR.PATCH` form.

To test packaging before the secrets are ready, use **Actions → Release → Run
workflow**. That produces `FirstPick-1.0.0-<arch>-unsigned.dmg` artifacts for seven
days. These manual artifacts still trigger Gatekeeper and are not suitable for a
public release. A partially configured secret set is always treated as an error.

Locally you can produce the same artifact after importing the Developer ID
certificate into your keychain and setting the environment variables:
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

## Update checks

The app's update checker requests metadata from GitHub's public
`/repos/francescolofranco-dev/first-pick/releases/latest` API. It compares semantic
versions and selects the matching `arm64` or `x86_64` `.dmg`. It only returns the
release page and asset link for the UI to present: it never downloads, mounts, or
installs an update automatically.

## Notes

- Both jobs run on `macos-14`: the arm64 app uses an AArch64 JDK and the x86_64 app
  uses an Intel JDK under Rosetta. `jpackage` therefore bundles a runtime matching
  the artifact name.
- The release uses `notarizeDmg` (not the ProGuard-minified `notarizeReleaseDmg`) to
  avoid needing Compose keep-rules. Switch later if you want a smaller bundle.
