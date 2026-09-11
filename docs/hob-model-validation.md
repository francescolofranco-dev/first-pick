# HOB Premier Draft model

Bundled model: `src/main/resources/picknet/HOB_PremierDraft.fpnet`.

The model derives from [17Lands contributors' public HOB Premier Draft data](https://17lands-public.s3.amazonaws.com/analysis_data/draft_data/draft_data_public.HOB.PremierDraft.csv.gz),
under the public datasets' [CC BY 4.0 terms](https://www.17lands.com/public_datasets).
FirstPick distributes trained weights, not the source decision rows. This is an
independent derived model and does not imply endorsement by 17Lands.

## Provenance

- [Training run, September 7, 2026](https://github.com/francescolofranco-dev/first-pick/actions/runs/34109432185).
- Source model commit: `ceedc927378f39de0cbc7a3059eaa58404971092`.
- SHA-256: `b5f28cf748b861f5d4f227bb594e4d59c723da0a27adba24de7201b5b67b0da9`.
- 193 cards; 1,833,289 source rows; 1,321,227 training examples.
- 150,000 holdout rows; best validation top-1 agreement: 68.67%.
- Folded export matched training-runtime scores within `1.14e-05`.

## Kotlin runtime check — September 11, 2026

The evaluation harness replayed the first 25 held-out drafts (1,050 picks),
selected using the trainer's exact split: `(java_hash(draft_id) & 0x7fffffff) % 5 == 0`.
All selected drafts were excluded from training. Live card ratings and the current
researched synergy profile were used. This small sample is a runtime spot-check,
not a new independent validation set or an estimate of game win rate.

| Metric | Heuristic advisor | Raw PickNet |
| --- | ---: | ---: |
| Top-1 pick agreement | 47.9% | 63.6% |
| Top-3 pick agreement | 82.1% | 93.3% |
| Mean GIH-oracle regret, percentage points | 1.10 | 1.75 |

PickNet better imitates the recorded choices on this sample, while the heuristic
has lower aggregate GIH regret. Neither metric proves improved game outcomes.
The app retains its existing guide and mana guardrails around model reranking.
The outcome-estimator stage was correctly skipped because this input contains
only held-out drafts; there are no outcome-training drafts in this sample.

The full test suite passed, including bundled HOB discovery, model parsing,
finite scores, a bomb-versus-basic check, and format isolation. Only Premier Draft
has a bundled HOB model; other formats retain their existing fallback behavior.
