# Pick Two and contextual draft advice

FirstPick detects Pick Two from `PickTwoDraft` event names, `NumCardsToPick: 2` pack notifications and snapshots, or a two-card pick from a pack with more than two cards remaining. A final single card does not switch it back. A new event/course resets detection. The header Draft picks dropdown offers Auto, One and Two; the manual choice lasts until changed or New draft. All supported drafts use Premier ratings and matching learned models. Choosing Two before a development demo starts also runs the four-seat Pick Two simulator.

The [official format description](https://magic.wizards.com/en/news/announcements/introducing-pick-two-draft) specifies four players, two cards per pass, three boosters, and left/right/left passing. Live tracking follows actual pack contents and completion messages rather than assuming a pod size or a fixed number of selections. The seeded simulator uses four seats and 15-card test packs: eight choices per pack, with one card at the end, for 45 cards total. This is a test fixture, not a claim that every Arena booster has 15 draftable cards.

## Pair scoring

The normal card ranking, learned model and mana guardrails remain the foundation. All pairs of nonbasic pack entries are compared. For each pair, the heuristic evaluates each card after adding its partner to the pool; changes in supported synergy, unmet roles and duplicate saturation provide the pair adjustment. The adjustment averages both orders and is capped at three score points in either direction. The cap is an engineering heuristic, not a measured win-rate improvement. Two feasible cards exclude constrained alternatives. Copies are separate physical entries.

The suggested pair is highlighted in the list and Arena overlay; individual grades keep their original meaning. With no recorded synergy the engine still uses card quality and pool needs. Missing metadata does not become invented synergy. Single-pick confidence margins, ALSA wheel penalties and ATA lateness signals are not used in Pick Two: ordinary draft timing data does not establish their validity here. Progress uses drafted card count. Ratings and learned models consistently use Premier data; FirstPick does not claim to have a separately calibrated Pick Two dataset.

## Jim Davis research

The series is **Bronze to Mythic**, by **Jim Davis**, as described on [his site](https://jimdavismtg.com/youtube.html). The following conclusions use indexed text of his authored articles, not video transcripts. Direct article opens redirected to the publisher's article listing during this research, while search returned the article text.

- In [Dominaria United](https://www.coolstuffinc.com/a/jimdavis-10142022-going-from-bronze-to-mythic-in-dominaria-united-draft), Davis explains that card values change with the resources and synergies in the deck. General application: reevaluate payoffs when actual enablers arrive. FirstPick already does this against the pool; Pick Two now also checks the prospective partner. His specific common-card rankings are not general rules.
- In [Lost Caverns of Ixalan](https://www.coolstuffinc.com/a/jimdavis-12012023-going-from-bronze-to-mythic-in-the-lost-caverns-of-ixalan-draft), Davis links early defense and splash caution to format speed and available fixing. General application: slower decks still need early plays, and splash ambitions require mana support. Existing adaptive curves and source-aware splash checks cover those concerns. His preferred color pairs and one-drop assessments stay specific to that set.
- His [Arena Cube article](https://www.coolstuffinc.com/a/jimdavis-07222024-how-to-draft-the-mtg-arena-cube) prioritizes mana, cheap plays and coherent game plans over collecting interchangeable spells. General application: prefer cards that improve the eventual deck, with diminishing value for surplus roles. Existing deck projection and needs scoring provide this; pair scoring applies their marginal role/copy effects. We do not apply Cube land priorities or a universal cheapest-card rule to every set.

The two Bronze to Mythic articles are now attributed in the in-app guide. These are interpretations translated into bounded heuristics, not a reproduction of Davis's drafting process or a claim of endorsement.

## Validation

Regression tests cover event-name and explicit-count detection, mode reset, final odd cards, duplicate entries, automatic/manual UI state, complementary pairs, quality gaps and feasibility constraints. The seeded audit now runs ordinary demo drafts, four-seat Pick Two drafts and random pools across FRA, BLB and DSK, checking the same completed-deck legality, copies, mana and splash invariants. These tests do not establish optimal play or predict match results.

Release validation for 1.9.0: 494 regression tests passed. Seeds 0–99 covered 900 pools, 1,986 suggested builds and 37 splash builds, with zero audit findings.

## Opening picks policy (1.9.1)

The first five card selections emphasize quality, with a maximum three-point bonus for synergies actually supported by the pool. A same-color card alone earns no synergy bonus. A close supported partner can win; a large quality gap stays decisive. During this opening, the learned ranker can lead with cards only within three contextual heuristic score points of the best option. Its raw ranking remains visible in explanations. These score limits are tuning choices, not win-rate percentages.

After five selections, color pressure and deck-fit scoring begin when repeated pool evidence supports a base color or pair. Lane detection still requires at least two supporting cards per color and a margin over competing pairs; five scattered picks do not establish a pair. Mono-color pools keep a second color open. Both the first-five checkpoint and the close-choice preference implement the user's requested heuristic; no specific video timestamp or verified five-pick quotation is claimed. Pick Two measures this opening in cards drafted, matching its existing progress calculation, and retains its separate bounded pair adjustment.

Release validation for 1.9.1: all 501 tests passed. The production replay visibly retained the strongest heuristic opening option ahead of a much weaker model-preferred card. Normal Arena-log tracking was restored after verification.

## Simplified draft support (2.0.0)

The data-source selector and separate pick-mode chips are replaced by one header dropdown: Draft picks — Auto, One, Two. Premier ratings and learned models are used for every supported draft, including Quick and Pick Two. Legacy ratings overrides in saved settings are ignored. Alternative ratings datasets are rejected before cache/network access. Traditional Draft recognition and data loading are removed; a confirmed unsupported event join suppresses subsequent unnamed draft packets until a supported named draft appears. Traditional can be designed again in the future alongside actual best-of-three and sideboard advice.

Release validation for 2.0.0: all 498 regression tests passed, including non-Premier dataset rejection and Traditional log transitions. The production app was rebuilt, restarted, and visibly checked; the header menu contains only Auto, One, and Two.
