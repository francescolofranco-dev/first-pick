# Distribution: signed & notarized `.dmg` + Homebrew

FirstPick's release pipeline ships tagged releases as signed, notarized macOS `.dmg`
files (no Gatekeeper warning and no `xattr` workaround) and can also ship via a
Homebrew cask. The build config and CI read credentials from the environment, so
nothing secret lives in the repository.

Releases are automatic: every push to `main` that changes `VERSION` (or a pushed
`v*` tag) publishes a GitHub Release `v<VERSION>` with one `.dmg` per architecture.
If all signing and notarization secrets are configured the dmgs are signed and
notarized; if none are, they are published as `*-unsigned.dmg` with Gatekeeper
instructions in the release notes. A partial secret set fails the build. A version
whose release already has dmgs is skipped. Manual runs only produce artifacts.

This document is the runbook for turning it on.

## Status

- [x] `build.gradle.kts` — `macOS { signing { } notarization { } }`, env-driven, `bundleID = com.firstpick.app`
- [x] `packageVersion` overridable via `-PpackageVersion` (release sets it from the tag)
- [x] `.github/workflows/release.yml` — signed tag releases plus unsigned manual packaging tests
- [x] `packaging/homebrew/Casks/firstpick.rb` — cask template
- [x] **Apple Developer Program enrollment** and Developer ID Application certificate
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
6. Keep the bundle identifier `com.firstpick.app`. Register an App ID if you add capabilities that require a provisioning profile.

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

Bump `VERSION` and push to `main`. (Pushing `vX.Y.Z` matching `VERSION` also works.)

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

For local notarization, keep the app-specific password in Keychain rather than
in shell history or a repository file. Configure a profile once (the command
prompts securely for the password):

```bash
xcrun notarytool store-credentials FirstPick-notary \
  --apple-id 'YOUR_APPLE_ACCOUNT_EMAIL' --team-id 'YOUR_TEAM_ID'
```

Then build with the exact Developer ID identity shown by
`security find-identity -v -p codesigning`, submit the app, and staple its ticket:

```bash
MACOS_SIGNING_IDENTITY='Developer ID Application: Your Name (TEAMID)' \
  ./gradlew buildProduction
firstpick_version="$(cat VERSION)"
firstpick_app='build/compose/binaries/main/app/FirstPick.app'
firstpick_archive="build/FirstPick-${firstpick_version}-DeveloperID.zip"
ditto -c -k --keepParent "$firstpick_app" "$firstpick_archive"
xcrun notarytool submit "$firstpick_archive" \
  --keychain-profile FirstPick-notary --wait
xcrun stapler staple "$firstpick_app"
xcrun stapler validate "$firstpick_app"
spctl --assess --type execute --verbose=2 "$firstpick_app"
```

Wait for an **Accepted** result before stapling or installing. A submission that
is still **In Progress** has not passed notarization. The local setup was verified
on 2026-11-04: the certificate/private-key pair is valid, the official Developer ID
G2 intermediate is installed, the Keychain profile authenticates with Apple, and
an optimized signed 3.7.0 app has been submitted. No passwords or private keys are
stored in this repository.

For the existing CI `.dmg` workflow, configure all six repository secrets listed
above. `notarizeDmg` uses these environment variables directly. Manual workflow
runs produce temporary artifacts without publishing a GitHub release.

Switching from the previous local signing certificate to Developer ID changes
the identity trusted by the installed Arena access broker. Revoke access using
the old signed app before replacing it, then approve the new signed app once.
Subsequent updates signed by the same approved Developer ID identity reconnect
without another administrator prompt.

Packaging compiles the native helpers and runs unit tests before creating the
app. For everyday local use, `./gradlew buildProduction` uses only this Mac's
native architecture; distributable cross-architecture artifacts use the
`nativeArchitecture=universal` option above and an SDK that supports both targets.

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
