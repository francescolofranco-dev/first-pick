# Pick-model training

Trains the learned pick model (`PickNet`) for one set: a small MLP that scores
every card given the current pool, imitating high-win-rate 17Lands drafters.
Architecture and recipe follow [statistical-drafting](https://github.com/danieljbrooks/statistical-drafting) (MIT).

## Setup (once)

```bash
python3.11 -m venv .venv
.venv/bin/pip install torch numpy
```

## Train a set

```bash
SET=MKM
curl -o data/$SET.csv.gz \
  "https://17lands-public.s3.amazonaws.com/analysis_data/draft_data/draft_data_public.$SET.PremierDraft.csv.gz"
.venv/bin/python train_picker.py --set $SET --fmt PremierDraft --data data/$SET.csv.gz
```

Outputs under `out/`:

- **`<SET>_<FMT>.fpnet`** — weights for the Kotlin runtime
  (`com.firstpick.model.PickNet`). BatchNorm is folded at export, so the file is
  just three dense layers: a JSON header line (card list, dims) + float32 LE
  weights. ~1.5MB per set.
- **`<SET>_<FMT>_holdout.csv`** — every draft with `hashCode(draft_id) % 5 == 0`,
  excluded from training. Evaluate on it:

```bash
./gradlew evalHarness -Pdata=training/out/${SET}_PremierDraft_holdout.csv \
  -Pset=$SET -Pnet=training/out/${SET}_PremierDraft.fpnet
```

The report prints the heuristic engine and PickNet top-1/top-3 agreement on the
same picks — drafts neither has seen.

## Notes

- Training filters to drafters whose bucketed win rate clears a confidence bound
  (≈ .54+); evaluation is against all drafters in the holdout, matching the
  engine's historical baseline protocol.
- Training is ~20 min CPU / less on Apple Silicon (MPS) for a full set.
- The export self-checks: the folded chain must reproduce the torch model's
  outputs to 1e-3 before the .fpnet is written.
