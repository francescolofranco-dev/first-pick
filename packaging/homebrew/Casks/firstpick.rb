# Homebrew cask for FirstPick.
#
# This is a TEMPLATE. It lives here for review/versioning, but Homebrew only reads
# casks from a tap repo. To publish:
#   1. Create a public repo: github.com/francescolofranco-dev/homebrew-firstpick
#   2. Copy this file to its Casks/firstpick.rb
#   3. Fill in `sha256` from the released dmg (or use `brew bump-cask-pr`)
# Users then install with:
#   brew install --cask francescolofranco-dev/firstpick/firstpick
#
# See docs/distribution.md for the full flow.

cask "firstpick" do
  version "1.0.0"
  sha256 "0000000000000000000000000000000000000000000000000000000000000000" # replace per release

  url "https://github.com/francescolofranco-dev/first-pick/releases/download/v#{version}/FirstPick-#{version}.dmg",
      verified: "github.com/francescolofranco-dev/first-pick/"
  name "FirstPick"
  desc "Native macOS MTG Arena draft assistant (17Lands-driven)"
  homepage "https://github.com/francescolofranco-dev/first-pick"

  livecheck do
    url :url
    strategy :github_latest
  end

  depends_on macos: ">= :ventura"

  app "FirstPick.app"

  zap trash: [
    "~/Library/Application Support/FirstPick",
    "~/Library/Logs/FirstPick",
  ]
end
