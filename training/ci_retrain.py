#!/usr/bin/env python3
"""CI helper: decide which Standard sets need a pick-model retrain.

A set needs retraining when it has 17Lands PremierDraft data and either no
bundled model exists, or the S3 dataset is newer than the commit that last
touched the model (17Lands keeps updating a set's file while people draft it;
files go static once the format moves on — so mature sets naturally drop out).

Stdlib only. Prints a JSON array of set codes for the workflow matrix.

  ci_retrain.py select --auto
  ci_retrain.py select --sets "MSH, SOS"
"""

import argparse
import json
import subprocess
import sys
import urllib.request
from datetime import datetime, timezone
from email.utils import parsedate_to_datetime
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
MANIFEST = ROOT / "src/main/resources/synergy/standard.json"
MODELS = ROOT / "src/main/resources/picknet"
FMT = "PremierDraft"
DATA_URL = (
    "https://17lands-public.s3.amazonaws.com/analysis_data/draft_data/"
    "draft_data_public.{s}.{f}.csv.gz"
)


def standard_sets() -> list:
    with open(MANIFEST) as f:
        return [s["code"].upper() for s in json.load(f)["sets"]]


def data_last_modified(set_code: str):
    req = urllib.request.Request(DATA_URL.format(s=set_code, f=FMT), method="HEAD")
    try:
        with urllib.request.urlopen(req, timeout=30) as r:
            lm = r.headers.get("Last-Modified")
            return parsedate_to_datetime(lm) if lm else None
    except Exception:
        return None


def model_committed(set_code: str):
    path = MODELS / f"{set_code}_{FMT}.fpnet"
    if not path.exists():
        return None
    out = subprocess.run(
        ["git", "log", "-1", "--format=%cI", "--", str(path)],
        capture_output=True, text=True, cwd=ROOT,
    ).stdout.strip()
    return datetime.fromisoformat(out) if out else datetime.now(timezone.utc)


def select_auto() -> list:
    picked = []
    for s in standard_sets():
        data = data_last_modified(s)
        if data is None:
            print(f"  {s}: no 17Lands data — skip", file=sys.stderr)
            continue
        model = model_committed(s)
        if model is None:
            print(f"  {s}: no bundled model — retrain", file=sys.stderr)
            picked.append(s)
        elif data > model:
            print(f"  {s}: data {data:%Y-%m-%d} > model {model:%Y-%m-%d} — retrain", file=sys.stderr)
            picked.append(s)
        else:
            print(f"  {s}: model up to date ({model:%Y-%m-%d})", file=sys.stderr)
    return picked


def select_named(raw: str) -> list:
    known = set(standard_sets())
    picked = []
    for tok in raw.replace(",", " ").split():
        s = tok.upper()
        if s not in known:
            print(f"  {s}: not in standard.json — skip", file=sys.stderr)
        elif data_last_modified(s) is None:
            print(f"  {s}: no 17Lands data — skip", file=sys.stderr)
        else:
            picked.append(s)
    return picked


def main():
    ap = argparse.ArgumentParser()
    sub = ap.add_subparsers(dest="cmd", required=True)
    sel = sub.add_parser("select")
    group = sel.add_mutually_exclusive_group(required=True)
    group.add_argument("--auto", action="store_true")
    group.add_argument("--sets")
    args = ap.parse_args()

    picked = select_auto() if args.auto else select_named(args.sets)
    print(json.dumps(picked))


if __name__ == "__main__":
    main()
