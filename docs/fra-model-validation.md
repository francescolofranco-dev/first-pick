# Reality Fracture Premier Draft model

Bundled model: `src/main/resources/picknet/FRA_PremierDraft.fpnet`, introduced in
FirstPick 1.5.1. This is a dedicated FRA PickNet model trained on actual FRA
Premier Draft picks using FirstPick's existing
[statistical-drafting](https://github.com/danieljbrooks/statistical-drafting)
recipe.

The model derives from [17Lands contributors' public FRA Premier Draft data](https://17lands-public.s3.amazonaws.com/analysis_data/draft_data/draft_data_public.FRA.PremierDraft.csv.gz),
under the public datasets' [CC BY 4.0 terms](https://www.17lands.com/public_datasets).
FirstPick distributes trained weights, not the source decision rows. This is an
independent derived model and does not imply endorsement by 17Lands.

## Provenance — October 17, 2026

- Source HTTP 200; `Last-Modified: Fri, 16 Oct 2026 12:23:42 GMT`;
  47,394,333 downloaded bytes.
- Source dataset SHA-256:
  `7e63d703e33ec055305e5ee957d7e59bccd554ed648c7beeae53cd4ecdb21e3d`.
- Trainer: unchanged `training/train_picker.py` at
  `93722d6d2772433f444fab5a20f1d6a9c154f7d5`.
  Trainer SHA-256:
  `07ddde5d6b53ab5f664b55da62cec3d07c8664cbec78158ea3d061eeba5dc649`.
- Local run: October 17, 21:31:11–21:36:42 UTC; macOS 26.6.2 arm64,
  Python 3.11.15, PyTorch 2.13.0, NumPy 2.4.6, Apple MPS backend.
  Torch initialization seed 42, existing NumPy split/shuffle seed 42,
  six CPU threads.
- Arguments: `--set FRA --fmt PremierDraft --data training/data/FRA.csv.gz
  --out training/out`; no row limit. Defaults: 400-unit hidden layers,
  dropout 0.6, batch 1,000, Adam learning rate 0.003, epoch decay 0.94,
  maximum 80 epochs, early-stopping patience 15.
- 1,308,012 source picks; 949,553 skill-filtered non-holdout examples,
  split into 759,643 optimization examples and 189,910 validation examples.
  92,772 non-holdout picks were filtered by the skill rule; zero unknown picks.
- Best internal validation top-1 agreement: **65.92%**, zero-based epoch 47.
  Early stopping after epoch 62. This random row split among skilled
  non-holdout picks selects the checkpoint; it is separate from the independent
  draft-level evaluation below.
- Export: 295 cards; 1,595,033 bytes. Model SHA-256:
  `0f95680febd9595c7c370be8bb677149adbae0355d7f3213213448c0db0d53a4`.
  Saved checkpoint SHA-256:
  `082559b7acb8a73ca6e73d7352b35748045abb4693b859da35ac07b9ef1fdf42`.

Downloaded data, checkpoint, rarity snapshot, and complete training/evaluation
logs remain local under the ignored `training/data/` and `training/out/`
directories.

## Coverage and export checks

The vocabulary covers all 290 FRA cards in the app's Scryfall catalog plus the
five basic lands, all 150 distinct named cards in the researched synergy
profile, and all preparation creature front names in the FRA fixture. Prepared
spell faces are not separate draft choices. Every vocabulary card appears in
both packs and recorded picks in the captured holdout.

The trainer's BatchNorm-folding check passed with maximum score error
`2.10e-05` (required tolerance `1e-3`). A separate CPU check compared the saved
checkpoint and exported dense chain on all 4,200 evaluation picks: all weights,
checkpoint tensors, and scores were finite; vocabulary, layer shapes, and file
length matched exactly. Across 31,481 available-card scores, maximum error was
`2.86102e-05`, also below `1e-3`.

Checkpoint and export top choices matched on 4,199 of 4,200 picks. One pair with
a checkpoint gap of `3.81e-06` became an exact exported-score tie; top-three
membership was unchanged. The exported chain's raw top-one/top-three agreement
was 64.69% / 92.21%. The Kotlin runtime is evaluated separately below.

## Independent Kotlin runtime evaluation

All drafts satisfying `(java_hash(draft_id) & 0x7fffffff) % 5 == 0` were excluded
from model training and checkpoint selection, before skill filtering. The
trainer persisted the first 150,000 held-out rows. These contain 3,568 complete
42-pick drafts, three drafts missing their final pick, and a final capped
21-pick draft. All four incomplete drafts were excluded from evaluation.

The runtime sample contains **100 complete held-out drafts / 4,200 picks**.
In source order, the 3,568 eligible drafts were divided into 100 contiguous
strata with integer-floor boundaries; Python `random.Random(42)` selected one
draft uniformly per stratum. Source row order was preserved. Every selected
row passed the exact trainer holdout predicate, and every chosen card was
present in its pack. Sample SHA-256:
`4a5d2dcfed000f54d772a15ea8dfdd2e33f043cc743d91f6b2127748a73b289a`.

Sample draft timestamps span September 29–October 2, reflecting the trainer's
holdout-file cap. This is an independent runtime comparison on that captured
launch-period sample, not an evaluation of every date in the full dataset.
The current researched synergy profile and FRA Premier Draft ratings were used.
The ratings were refreshed October 17 at 21:37 UTC: 290 card rows, 279 with
GIH and at least 200 games, median 15,492 GIH games among positive-game rows.
Ratings snapshot SHA-256:
`52fa688c9c3765a68c34823563776620a49e2256451f07519768ee976732a8a2`.

```bash
./gradlew evalHarness -Pset=FRA \
  -Pdata=training/out/FRA_PremierDraft_eval.csv \
  -Pnet=src/main/resources/picknet/FRA_PremierDraft.fpnet
```

| Metric | Heuristic advisor | Raw PickNet |
| --- | ---: | ---: |
| Top-1 pick agreement | 47.3% (1,987/4,200) | 64.7% (2,718/4,200) |
| Top-3 pick agreement | 79.9% (3,355/4,200) | 92.2% (3,873/4,200) |
| Mean GIH-oracle regret, percentage points | 1.14 | 1.82 |

PickNet better imitates recorded choices on this sample, while the heuristic
has lower aggregate GIH regret. Regret uses only picks with observed ratings
for the selected and recorded cards, so the rated subsets can differ between
the two policies. The harness compares raw PickNet with the heuristic advisor;
it does not measure the app's guarded reranking. Recorded-pick agreement and
aggregate GIH regret are different diagnostics, and neither establishes improved
game win rate.
The outcome-estimator stage is correctly skipped for this holdout-only input,
which contains no outcome-training drafts.

## App integration

The existing repository discovers the bundled model automatically for FRA
Premier Draft. Guide and mana guardrails still constrain model promotions.
Traditional Draft and Quick Draft keep their existing format-specific
ratings and heuristic behavior; no FRA model is claimed for those formats.
FRA remains eligible for the weekly public-data retraining selection.

Regression coverage verifies bundled discovery, the 295-card vocabulary, real
FRA card and preparation-front coverage, finite scores throughout representative
drafts, and isolation between formats and sets. All 458 Kotlin/JUnit tests and
four Python training-selection tests passed. The signed production app was
rebuilt, its packaged model checksum matched the trained artifact, and the
installed app was restarted. Visible verification confirmed header version
1.5.1 and FRA in the **AI picks, 18 sets** coverage list.
