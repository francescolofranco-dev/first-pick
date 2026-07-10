# Evaluation: how we know the advisor is good (and keep improving it)

There's no single ground truth for "best pick" in Limited, but **17Lands' aggregated
outcome data is the closest thing to it** — millions of real games. Our strategy is
to measure our recommendations against that data, turn it into a number, and use the
number as both a regression gate and a tuning target.

Two complementary layers:

## Layer 1 — Scenario regression suite (built ✅)

`src/test/kotlin/com/firstpick/advisor/ScenarioSuiteTest.kt` encodes ~20 situations a
good drafter has a clear opinion on (P1P1 bomb on top; off-color bomb overrides the
P3 lock; needed removal jumps a slightly-better creature late but *not* early; toss-up
detection; …) and asserts the engine agrees. It tests **direction**, not exact scores,
so it stays valid as constants are tuned. This is the safety net for every change.

## Layer 2 — Dataset backtest harness (built ✅)

The engine of actual improvement. Offline, reproducible, per-set. Code in
`src/main/kotlin/com/firstpick/eval/`.

```bash
# grab a sample (streams, stops early — only a few MB downloaded)
SET=MKM
curl -s "https://17lands-public.s3.amazonaws.com/analysis_data/draft_data/draft_data_public.$SET.PremierDraft.csv.gz" \
  | gunzip | head -n 90000 > /tmp/${SET}_sample.csv
./gradlew evalHarness -Pdata=/tmp/${SET}_sample.csv -Pset=$SET
```

**MKM baseline (90k picks, 2321 drafts):** Top-1 agreement 40.6%, Top-3 73.4%;
winner-minus-loser agreement **+4.7 pts** (drafters who pick what we recommend win
more); regret vs the greedy oracle **0.86 pts (ours) vs 2.23 pts (the field)**; VALUE
calibration monotonic (52% → 61%). i.e. we sacrifice less win rate than the average
drafter, align with winning play, and our 0–100 scale tracks real win rate.

### Data

[17Lands public datasets](https://www.17lands.com/public_datasets), per set:
- **`*.draft.csv.gz`** — one row per pick: `draft_id`, `pack_number`, `pick_number`,
  the `pick` (card taken), `pack_card_<Name>` columns (what was in the pack),
  `pool_<Name>` columns (the pool so far), and the drafter's `event_match_wins`
  (their eventual record). This is everything we need to replay a real pick.
- **`*.game.csv.gz`** — deck lists + win/loss (for deck-level and calibration checks).

### Runner

A new module / `./gradlew evalHarness -Pset=MKM -Pdata=<dir>`:
1. Stream the draft CSV; for each pick row, reconstruct the pack + pool (card names).
2. Resolve names against our cached 17Lands ratings + archetype data (reuse `cards/`).
3. Run the existing `AdvisorEngine.score(...)` to get our ranking.
4. Compare our pick to the row's actual `pick` and to outcome-weighted references.

### Metrics (the report)

- **Pick regret** — `GIH_WR(best reasonable on-color card) − GIH_WR(our #1)`, averaged.
  Lower is better; the headline number.
- **Winning-drafter agreement** — restricted to high-win drafters (`event_match_wins ≥ 6`),
  how often our #1 (and top-3) matches their actual pick. This grounds "are we
  recommending what *wins*?" in real outcomes, not just raw card rates.
- **Value calibration** — bucket recommended cards by VALUE; plot each bucket's actual
  in-context GIH/GD win rate. The curve must be monotonic, or we recalibrate the value
  mapping (makes the new tier colors honest).
- **Confidence calibration** — when we say TOSS-UP, is the real GIH-WR delta between the
  top cards actually negligible (< ~0.3%)? Tune `Confidence` gaps to a real delta.

Output: a markdown + CSV report per set, checked into `eval/reports/`.

### Tuning loop (how it improves over time)

`AdvisorEngine.Config` already holds every constant (archetype weight ramp, needs
weights, penalties, value mapping) as a data class. The harness sweeps `Config`
(coordinate descent / small Bayesian opt) to minimize regret + maximize winning-drafter
agreement on a training set, validated on a held-out set. Re-tune when a new set drops
— each set's archetypes behave differently. "Is this version better than the last?"
becomes a measured answer.

### Build order

1. CSV reader + pick reconstruction; sanity-check counts vs. a known set.
2. Wire the existing engine; emit pick-regret + agreement.
3. Add value + confidence calibration plots.
4. Add the `Config` sweep + held-out validation.

## Layer 3 — Learned pick model (PickNet)

`training/train_picker.py` trains a per-set MLP on the same 17Lands draft CSVs
(picks by high-win-rate drafters only), exported as `.fpnet` and scored in-app by
`com.firstpick.model.PickNet`. Pass `-Pnet=<path>.fpnet` to `evalHarness` to
report its top-1/top-3 agreement next to the heuristic engine on identical picks;
train/eval never share drafts (`hashCode(draft_id) % 5` holdout). See
`training/README.md`.

## Ongoing, cheap sanity (no harness needed)

- **Differential test** our top pick vs. 17Lands' own card ordering / Arena Tutor /
  Untapped for the same pack — systematic disagreements are either bugs or genuine
  context wins; investigate each.
- **Opt-in local log** (later, privacy = on-device only): record (our rec, your pick,
  draft result). Over many drafts, surface where you deviate and whether it won more —
  a personal feedback loop.
