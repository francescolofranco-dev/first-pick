
cask "firstpick" do
  version "1.0.0"
  sha256 "0000000000000000000000000000000000000000000000000000000000000000"

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
