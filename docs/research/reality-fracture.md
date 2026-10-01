# Reality Fracture profile research

Reviewed October 1, 2026. The bundled profile covers all ten official two-color archetypes with 150 distinct cards, 18–20 role assignments per archetype, six mechanics, and twenty interactions. Every role and interaction card was matched by its creature/front name against the 285 unique FRA cards returned by Scryfall on September 30.

## Sources

- [Reality Fracture Mechanics](https://magic.wizards.com/en/news/feature/reality-fracture-mechanics), Matt Tabak, Wizards of the Coast, September 8, 2026: empower Jace, Heartwood, and preparation.
- [Reality Fracture Prerelease Guide](https://magic.wizards.com/en/news/feature/reality-fracture-prerelease-guide), Jubilee Finnegan, Wizards of the Coast, September 18, 2026: the ten archetypes and their featured cards.
- [Reality Fracture Release Notes](https://magic.wizards.com/en/news/feature/reality-fracture-release-notes), Jess Dunks and Eric Levine, Wizards of the Coast, September 18, 2026: rules, flashback, loyalty limitations, and card-specific clarifications.
- [Scryfall FRA card reference](https://api.scryfall.com/cards/search?q=set%3Afra&order=set&unique=cards), retrieved September 30, 2026, including its second page: exact names, colors, preparation faces, and card text.

Role assignments, speed labels, and interaction advice are editorial interpretations of the official archetypes and verified card rules. They are not win-rate rankings. The profile makes no claim to have observed launch-week 17Lands statistics.

## Rules checked during review

- Empower adds counters to a **Jace planeswalker token**, including a token obtained by another effect. It does not add counters to a nontoken Jace.
- Gaining more loyalty or a new Way ability does not reset a planeswalker's activation limit. Kiora's attack trigger needs an activation before attackers are declared.
- Heartwood is a red and green artifact and provides repeatable mana. Draconic Visitor replaces its creation with a Dragon that has no Heartwood mana ability.
- A prepared spell copy is cast from exile, so it triggers prowess and Saheeli. A copy created directly on the stack by Way of the Cryomancer is not cast. Preparation cards remain creature cards in the graveyard, and their spell copies cannot provide lasting cards toward threshold.
- Flashback and abilities that exile graveyard cards can remove threshold. Proft, Sinister Mastermind's casting restriction does not stop Rewrite Regrets from returning it directly.
- Prudent Fateseer and Bloombrute have once-per-turn limits. The Fateseer interaction explicitly assumes its trigger remains available.
- Essence Burn and Fulminous Forte cannot damage the opponent directly. The Stingerquill enabler list instead includes effects that can deal noncombat damage to a player.

## Scope of the profile

All ten archetypes retain the two cards highlighted in Wizards' guide. Roles and combinations use front names so preparation cards match both plain Arena names and combined Scryfall names through the existing normalization.

The profile stores positive relationships rather than a full rules engine. It cannot express all opposing interactions: for example, Karn, Argent Defender suppresses many creature entry effects and is deliberately absent from the creature-value role lists. Card text and the interaction notes remain necessary for timing, target, and resource decisions.

Preparation cards use the creature face's casting cost for curve and mana checks.
Their spell effects still contribute card roles. Heartwood from an additional
prepared spell is not counted as a guaranteed splash source: the current mana
planner does not model the additional spell's cost or timing.
