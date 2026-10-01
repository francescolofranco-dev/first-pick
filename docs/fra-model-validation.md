# Reality Fracture model readiness

Checked October 1, 2026, at 09:42 UTC. FRA does **not** have a bundled
PickNet model yet. Its researched set profile and the advisor's existing
ratings, guide, synergy, and mana rules work without one.

## Public training data

The [FRA Premier Draft source dataset](https://17lands-public.s3.amazonaws.com/analysis_data/draft_data/draft_data_public.FRA.PremierDraft.csv.gz)
returned HTTP 403 to a public HEAD request on both September 30 and October 1.
The equivalent
[HOB dataset](https://17lands-public.s3.amazonaws.com/analysis_data/draft_data/draft_data_public.HOB.PremierDraft.csv.gz)
returned HTTP 200 on September 30 with a September 3, 2026 `Last-Modified` date
and 56,648,372 bytes. FRA's response confirms that the file was not publicly readable at the
time of the check; it does not distinguish an unpublished object from a private
one.

[17Lands' usage guidelines](https://www.17lands.com/usage_guidelines) describe an
intended first draft-data release two weeks into a set. FRA's
[Arena release was September 29](https://magic.wizards.com/en/news/mtg-arena/reality-fracture-mastery-details),
so October 13 is an expected publication window, not a promised availability
date. Live aggregate ratings are a separate data source and cannot supply the
pack, pool, and chosen-card examples required to train PickNet.

No training or held-out model evaluation was run for FRA. No weights or model
accuracy are claimed. A different set's weights are not a substitute.

## Live card ratings

The app's separate live-rating endpoints returned empty data on September 30
at 18:51 UTC. By October 1 at 09:42 UTC, both formats had published ratings:

| Format | HTTP status | Card rows | Cards with GIH and at least 200 games | App median GIH games |
| --- | ---: | ---: | ---: | ---: |
| Premier Draft | 200 | 290 | 254 | 2,490 |
| Sealed | 200 | 290 | 188 | 738 |

Requests used `https://www.17lands.com/api/card_data?expansion=FRA&event_type=PremierDraft&time_period=ALL_TIME`
and the same URL with `event_type=Sealed`, matching FirstPick's client. All 290
rows in each response had Arena IDs. The app's median uses positive-game rows
and rounds down when averaging the middle two counts. Counts are a dated
snapshot, not a guaranteed coverage level.

FRA can now use live aggregate ratings while its learned model remains pending.
Cards without observed power remain explicitly unrated. When all live ratings
are unavailable, the catalog fallback keeps identities, researched synergies,
and pool-fit notes available without displaying invented grades or model
confidence. A successful empty response alone does not supply card ratings.
Deck builds without observed card power are labeled provisional structural
comparisons and omit numerical power and tier badges.

## Automatic eligibility

The weekly `retrain-models` workflow reads `synergy/standard.json`, which includes
FRA. It skips FRA while the public draft file is unavailable, then selects it
when the file has a `Last-Modified` header and no FRA model is bundled. Later
runs select the set when the source dataset is newer than the model commit.
No additional set-specific training code is required.

Run this from the repository root to check the same selection path manually:

```bash
python3 training/ci_retrain.py select --sets FRA
```

The command returns `[]` while the source is unavailable and `["FRA"]` when it
is available. Network errors also result in a skip, so inspect the HTTP response
before interpreting a skip as evidence about publication.

## Validate the first genuine model

Once the public file is readable, use the existing trainer and preserve its
draft-level holdout. These commands run from the repository root after setting
up `training/.venv` as described in the [training guide](../training/README.md):

```bash
mkdir -p training/data
curl --fail --location --output training/data/FRA.csv.gz \
  'https://17lands-public.s3.amazonaws.com/analysis_data/draft_data/draft_data_public.FRA.PremierDraft.csv.gz'
training/.venv/bin/python training/train_picker.py \
  --set FRA --fmt PremierDraft --data training/data/FRA.csv.gz --out training/out
./gradlew evalHarness -Pset=FRA \
  -Pdata=training/out/FRA_PremierDraft_holdout.csv \
  -Pnet=training/out/FRA_PremierDraft.fpnet
```

Before bundling, record the dataset date and checksum, training-run provenance,
card coverage, source and training counts, held-out draft and pick counts,
validation agreement, export-folding error, and Kotlin holdout comparison with
the heuristic advisor. Review both agreement and regret; agreement with recorded
picks does not establish better game results. Confirm that scores are finite,
FRA's real draft cards are covered, and other formats retain their existing
fallback behavior. The [HOB validation report](hob-model-validation.md) is the
existing reporting baseline.

The trainer's export check must pass before any model is copied into
`src/main/resources/picknet/FRA_PremierDraft.fpnet`. Adding the model is an app
change and needs a version increase, relevant tests, and a production rebuild.
