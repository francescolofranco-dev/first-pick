#!/usr/bin/env python3
"""Train a per-set draft pick model on 17Lands public draft data.

Architecture and recipe follow danieljbrooks/statistical-drafting (MIT): a small
MLP that scores every card in the set given the current pool, output masked to
the pack. Trained only on picks by drafters whose bucketed win rate clears a
confidence threshold, so the model imitates winning players, not the field.

Drafts whose Java-hashCode(draft_id) % 5 == 0 are excluded from training and
written to a holdout CSV — the same split the Kotlin eval harness uses — so the
reported comparison is on drafts the model never saw.

Usage:
  .venv/bin/python train_picker.py --set MKM --fmt PremierDraft \
      --data data/draft_data_public.MKM.PremierDraft.csv.gz --max-rows 1200000

Outputs (under --out, default out/):
  <SET>_<FMT>.fpnet         weights for the Kotlin runtime (BatchNorm folded)
  <SET>_<FMT>_holdout.csv   held-out drafts for `gradlew evalHarness`
  <SET>_<FMT>.pt            torch checkpoint (resume/inspection)
"""

import argparse
import csv
import gzip
import io
import json
import math
import struct
import sys
import urllib.request

import numpy as np
import torch
from torch import nn

POOL_PREFIX = "pool_"
PACK_PREFIX = "pack_card_"


def java_hash(s: str) -> int:
    h = 0
    for c in s:
        h = (31 * h + ord(c)) & 0xFFFFFFFF
    return h - 0x100000000 if h >= 0x80000000 else h


def is_holdout(draft_id: str) -> bool:
    return (java_hash(draft_id) & 0x7FFFFFFF) % 5 == 0


def min_winrate(n_games: float, p: float = 0.55, stdev: float = 1.5) -> float:
    """Lower confidence bound used by statistical-drafting: keep drafters whose
    bucketed win rate is within `stdev` standard errors of p from above."""
    if n_games <= 0:
        return 1.0
    return p - stdev * math.sqrt(p * (1 - p) / n_games)


def fetch_rarities(set_code: str, fmt: str) -> dict:
    url = (
        "https://www.17lands.com/card_ratings/data"
        f"?expansion={set_code}&format={fmt}"
    )
    req = urllib.request.Request(url, headers={"User-Agent": "firstpick-training"})
    with urllib.request.urlopen(req, timeout=60) as resp:
        return {c["name"]: (c.get("rarity") or "") for c in json.load(resp)}


def open_maybe_gzip(path: str):
    raw = open(path, "rb")
    if raw.read(2) == b"\x1f\x8b":
        raw.seek(0)
        return io.TextIOWrapper(gzip.GzipFile(fileobj=raw), newline="")
    raw.seek(0)
    return io.TextIOWrapper(raw, newline="")


def load_examples(path, max_rows, holdout_path, holdout_cap=150_000):
    """Stream the 17Lands draft CSV once. Returns (cards, pools, packs, picks)
    for skill-filtered training rows; writes raw holdout-draft rows untouched."""
    pools, packs, picks = [], [], []
    n_rows = n_hold = n_unskilled = n_badpick = 0
    with open_maybe_gzip(path) as f, open(holdout_path, "w", newline="") as hold_f:
        reader = csv.reader(f)
        header = next(reader)
        col = {name: i for i, name in enumerate(header)}
        cards = sorted(n[len(POOL_PREFIX):] for n in header if n.startswith(POOL_PREFIX))
        pack_cards = sorted(n[len(PACK_PREFIX):] for n in header if n.startswith(PACK_PREFIX))
        if cards != pack_cards:
            sys.exit("pool_ and pack_card_ column sets differ — unexpected dataset shape")
        card_idx = {c: i for i, c in enumerate(cards)}
        pool_cols = [(col[POOL_PREFIX + c]) for c in cards]
        pack_cols = [(col[PACK_PREFIX + c]) for c in cards]
        i_draft, i_pick = col["draft_id"], col["pick"]
        i_wr = col.get("user_game_win_rate_bucket")
        i_ng = col.get("user_n_games_bucket")
        if i_wr is None or i_ng is None:
            sys.exit("missing skill-bucket columns in dataset")

        hold_w = csv.writer(hold_f)
        hold_w.writerow(header)
        for row in reader:
            n_rows += 1
            if n_rows % 200_000 == 0:
                print(f"  …{n_rows:,} rows ({len(picks):,} train examples, {n_hold:,} holdout rows)")
            if max_rows and n_rows > max_rows:
                break
            if is_holdout(row[i_draft]):
                if n_hold < holdout_cap:
                    hold_w.writerow(row)
                    n_hold += 1
                continue
            try:
                wr = float(row[i_wr]) if row[i_wr] else -1.0
                ng = float(row[i_ng]) if row[i_ng] else 0.0
            except ValueError:
                continue
            if wr < min_winrate(ng):
                n_unskilled += 1
                continue
            pick = card_idx.get(row[i_pick])
            if pick is None:
                n_badpick += 1
                continue
            pools.append(np.array([int(row[c] or 0) for c in pool_cols], dtype=np.uint8))
            packs.append(np.array([1 if (row[c] or "0") != "0" else 0 for c in pack_cols], dtype=np.uint8))
            picks.append(pick)

    print(
        f"rows {n_rows:,} -> train {len(picks):,} | holdout {n_hold:,} "
        f"| filtered: unskilled {n_unskilled:,}, unknown pick {n_badpick:,}"
    )
    return cards, np.stack(pools), np.stack(packs), np.array(picks, dtype=np.int64)


class DraftNet(nn.Module):
    def __init__(self, n_cards: int, hidden: int = 400, dropout: float = 0.6):
        super().__init__()
        self.l1 = nn.Linear(n_cards, hidden)
        self.bn1 = nn.BatchNorm1d(hidden)
        self.l2 = nn.Linear(hidden, hidden)
        self.bn2 = nn.BatchNorm1d(hidden)
        self.out = nn.Linear(hidden, n_cards)
        self.act = nn.GELU()
        self.drop = nn.Dropout(dropout)

    def forward(self, pool, pack):
        x = self.bn1(self.drop(self.act(self.l1(pool))))
        x = self.bn2(self.drop(self.act(self.l2(x))))
        return self.out(x) * pack


def masked_top1(logits, pack, target) -> torch.Tensor:
    masked = logits.masked_fill(pack == 0, float("-inf"))
    return (masked.argmax(dim=1) == target).float().mean()


def train(cards, pools, packs, picks, rarities, device, epochs, patience, lr, batch):
    n = len(cards)
    is_premium = torch.tensor(
        [rarities.get(c, "").lower() in ("rare", "mythic") for c in cards], device=device
    )
    rng = np.random.default_rng(42)
    order = rng.permutation(len(picks))
    n_val = len(picks) // 5
    val_idx, train_idx = order[:n_val], order[n_val:]

    t_pool = torch.from_numpy(pools)
    t_pack = torch.from_numpy(packs)
    t_pick = torch.from_numpy(picks)
    v_pool = t_pool[val_idx].float().to(device)
    v_pack = t_pack[val_idx].float().to(device)
    v_pick = t_pick[val_idx].to(device)

    model = DraftNet(n).to(device)
    opt = torch.optim.Adam(model.parameters(), lr=lr)
    sched = torch.optim.lr_scheduler.StepLR(opt, step_size=1, gamma=0.94)
    ce = nn.CrossEntropyLoss(reduction="none")

    best_acc, best_state, best_epoch = 0.0, None, -1
    for epoch in range(epochs):
        model.train()
        perm = torch.from_numpy(rng.permutation(len(train_idx)))
        total = 0.0
        for start in range(0, len(train_idx), batch):
            idx = torch.from_numpy(train_idx[perm[start:start + batch].numpy()])
            pool = t_pool[idx].float().to(device)
            pack = t_pack[idx].float().to(device)
            pick = t_pick[idx].to(device)
            logits = model(pool, pack)
            loss = ce(logits, pick)
            raredraft = is_premium[logits.argmax(dim=1)] & ~is_premium[pick]
            loss = (loss * torch.where(raredraft, 3.0, 1.0)).mean()
            opt.zero_grad()
            loss.backward()
            opt.step()
            total += loss.item() * len(idx)
        sched.step()

        model.eval()
        with torch.no_grad():
            acc = masked_top1(model(v_pool, v_pack), v_pack, v_pick).item()
        marker = ""
        if acc > best_acc:
            best_acc, best_epoch = acc, epoch
            best_state = {k: v.detach().cpu().clone() for k, v in model.state_dict().items()}
            marker = " *"
        print(f"epoch {epoch:3d}  loss {total / len(train_idx):.4f}  val top-1 {acc:.4f}{marker}")
        if epoch - best_epoch >= patience:
            print(f"early stop (no improvement for {patience} epochs)")
            break

    model.load_state_dict(best_state)
    print(f"best val top-1: {best_acc:.4f} (epoch {best_epoch})")
    return model.cpu().eval(), best_acc


def fold_batchnorm(model: DraftNet):
    """Fold each BatchNorm (an affine map at inference) into the FOLLOWING
    linear layer, leaving plain W·gelu(...)+b chains for the Kotlin runtime."""
    def affine(bn):
        a = bn.weight / torch.sqrt(bn.running_var + bn.eps)
        return a, bn.bias - bn.running_mean * a

    with torch.no_grad():
        a1, c1 = affine(model.bn1)
        a2, c2 = affine(model.bn2)
        w1, b1 = model.l1.weight.clone(), model.l1.bias.clone()
        w2 = model.l2.weight * a1
        b2 = model.l2.weight @ c1 + model.l2.bias
        w3 = model.out.weight * a2
        b3 = model.out.weight @ c2 + model.out.bias
    return [(w1, b1), (w2, b2), (w3, b3)]


def export_fpnet(path, set_code, fmt, cards, layers, val_acc):
    header = json.dumps(
        {
            "v": 1,
            "set": set_code,
            "format": fmt,
            "hidden": layers[0][0].shape[0],
            "valTop1": round(val_acc, 4),
            "cards": cards,
        },
        ensure_ascii=False,
    )
    with open(path, "wb") as f:
        f.write(header.encode("utf-8"))
        f.write(b"\n")
        for w, b in layers:
            for t in (w, b):
                f.write(struct.pack(f"<{t.numel()}f", *t.reshape(-1).tolist()))
    print(f"wrote {path}")


def verify_export(model: DraftNet, layers, pools, packs):
    """The folded chain must reproduce eval-mode model outputs on pack cards."""
    pool = torch.from_numpy(pools[:64]).float()
    pack = torch.from_numpy(packs[:64]).float()
    with torch.no_grad():
        want = model(pool, pack)
        x = pool
        for i, (w, b) in enumerate(layers):
            x = x @ w.T + b
            if i < len(layers) - 1:
                x = nn.functional.gelu(x)
        got = x * pack
    err = (want - got).abs().max().item()
    if err > 1e-3:
        sys.exit(f"BatchNorm folding check failed: max |diff| = {err}")
    print(f"folding check OK (max |diff| = {err:.2e})")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--set", required=True)
    ap.add_argument("--fmt", default="PremierDraft")
    ap.add_argument("--data", required=True, help="17Lands draft CSV (.csv or .csv.gz)")
    ap.add_argument("--out", default="out")
    ap.add_argument("--max-rows", type=int, default=0, help="0 = all rows")
    ap.add_argument("--epochs", type=int, default=80)
    ap.add_argument("--patience", type=int, default=15)
    ap.add_argument("--lr", type=float, default=0.003)
    ap.add_argument("--batch", type=int, default=1000)
    args = ap.parse_args()

    import os

    os.makedirs(args.out, exist_ok=True)
    base = f"{args.out}/{args.set}_{args.fmt}"

    print(f"Fetching rarities for {args.set} {args.fmt}…")
    rarities = fetch_rarities(args.set, args.fmt)
    print(f"Streaming {args.data}…")
    cards, pools, packs, picks = load_examples(args.data, args.max_rows, f"{base}_holdout.csv")
    device = "mps" if torch.backends.mps.is_available() else "cpu"
    print(f"{len(cards)} cards, training on {device}")

    model, val_acc = train(
        cards, pools, packs, picks, rarities, device,
        args.epochs, args.patience, args.lr, args.batch,
    )
    torch.save({"state": model.state_dict(), "cards": cards}, f"{base}.pt")

    layers = fold_batchnorm(model)
    verify_export(model, layers, pools, packs)
    export_fpnet(f"{base}.fpnet", args.set, args.fmt, cards, layers, val_acc)


if __name__ == "__main__":
    main()
