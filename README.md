<p align="center">
  <picture>
    <img src="docs/logo-banner.png" alt="FirstPick" width="480" />
  </picture>
</p>

<p align="center">
  A native macOS draft assistant for <b>Magic: The Gathering Arena</b>, built with Kotlin + Compose Multiplatform.
</p>

FirstPick watches Arena's log during a Draft, ranks every card in the pack using
[17Lands](https://www.17lands.com/) data adjusted to your colors, archetype, curve,
and needs, then proposes 2–3 finished decks when the draft ends.

> Unofficial fan project. Not affiliated with or endorsed by Wizards of the Coast.

<p align="center">
  <picture>
    <img src="docs/screenshots/draft-dashboard.png" width="820" alt="FirstPick live draft dashboard" />
  </picture>
</p>

## Features

- **Live pack ranking** — every card graded **A+ → F** with a 0–100 value, best pick on top.
- **Deck-aware scoring** — blends global and in-color win rates, archetype synergy, and missing roles.
- **Dashboard** — likely colors, archetypes, open-color signals, curve, and pool.
- **Memory-backed overlay** — complete live deck identities and quantities, with named add/cut guidance independent of scrolling and filters. Draft pick advice is a preview pending live pack validation; per-card placement is pending verified memory geometry.
- **Set guides** — color-pair themes and key commons/uncommons per set, with cited sources.
- **Personal stats** — local SQLite storage for drafts, draft event results, draft match history, play/draw performance, deck colors, and Limited rank progression. Recover available records from older Arena logs automatically.
- **Draft history** — completed drafts saved locally; reopen deck builds and the full drafted pool, including drafts recovered from the current Arena log.
- **Deck builder** — 2–3 ranked builds with manabase, then red/green guidance in Arena's deck builder.

Hover any card to preview it:

<picture>
  <img src="docs/screenshots/card-preview.png" width="320" alt="Card-image preview on hover" />
</picture>

After the draft, compare builds and review the full list:

<picture>
  <img src="docs/screenshots/deck-builder.png" width="820" alt="Post-draft deck builder options" />
</picture>

<picture>
  <img src="docs/screenshots/deck-list.png" width="820" alt="Deck list with roles and manabase" />
</picture>

## Install

1. Download the `.dmg` for your Mac (`arm64` for Apple Silicon, `x86_64` for Intel) from
   [**Releases**](../../releases/latest) and drag **FirstPick** to Applications.
2. In Arena, enable **Options → Account → Detailed Logs (Plugin Support)**.
3. Enable the overlay and click **Grant Arena access** in FirstPick. Complete the macOS administrator prompt for temporary read-only access. The current supported memory layout is Arena **2026.64.50 on Apple Silicon**; unsupported builds pause the overlay. Screen Recording is not used. The dashboard still works without memory access.

Older or `*-unsigned.dmg` builds may need **Open Anyway** in Privacy & Security, or
`xattr -dr com.apple.quarantine "/Applications/FirstPick.app"`.

## Run from source

```bash
git clone https://github.com/francescolofranco-dev/first-pick firstpick && cd firstpick
native/macos/build.sh --host # native window-metadata and read-only memory helpers
./gradlew run           # needs a full JDK 21
./gradlew runProduction # build, install, and open the production app
```

See [technical notes](docs/technical-notes.md), [CONTRIBUTING.md](CONTRIBUTING.md),
and [distribution](docs/distribution.md) for architecture, builds, and advisor details.

## Attribution

Win-rate data from [17Lands](https://www.17lands.com/) (CC-BY-4.0); card data and mana
symbols from [Scryfall](https://scryfall.com/). Magic: The Gathering is © Wizards of the Coast.

## License

[MIT](LICENSE) © 2026 Francesco Lo Franco
