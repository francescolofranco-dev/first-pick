#!/usr/bin/env python3
"""Discover Arena Limited sets and prepare versioned catalog changes."""
import argparse
import html
import json
import re
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
from collections import Counter, defaultdict
from datetime import date, datetime, timedelta, timezone
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
MANIFEST = ROOT / "src/main/resources/synergy/standard.json"
CALENDAR = ROOT / "docs/limited-release-calendar.json"
SCRYFALL = "https://api.scryfall.com"
WIZARDS = "https://www.magic.wizards.com/en/products"
RATINGS = "https://www.17lands.com/api/card_data"
SET_TYPES = {"core", "expansion", "masters", "draft_innovation"}
FIRST_RELEASE = date(2026, 9, 1)
COLORS = "WUBRG"
COMMON_KEYWORDS = {
    "Deathtouch", "Defender", "Double strike", "First strike", "Flash", "Flying",
    "Haste", "Hexproof", "Indestructible", "Lifelink", "Menace", "Reach", "Trample",
    "Vigilance", "Ward",
}


def fetch(url, accept="application/json"):
    request = urllib.request.Request(url, headers={
        "User-Agent": "FirstPick release sync (+https://github.com/francescolofranco-dev/first-pick)",
        "Accept": accept,
    })
    with urllib.request.urlopen(request, timeout=25) as response:
        return response.read()


def fetch_json(url):
    return json.loads(fetch(url))


def missing_is_empty(request):
    try:
        return request()
    except urllib.error.HTTPError as error:
        if error.code != 404:
            raise
        return None


def arena_cards(code):
    query = urllib.parse.urlencode({"q": f"set:{code.lower()} game:arena unique:cards"})
    url = f"{SCRYFALL}/cards/search?{query}"
    cards = []
    while url:
        time.sleep(0.12)
        page = missing_is_empty(lambda: fetch_json(url))
        if page is None and not cards:
            return []
        cards.extend(page["data"])
        url = page["next_page"] if page["has_more"] else None
        if url and not url.startswith(f"{SCRYFALL}/cards/search?"):
            raise ValueError("Unexpected Scryfall pagination URL")
    return cards


def ratings_ready(code):
    query = urllib.parse.urlencode({
        "expansion": code, "event_type": "PremierDraft", "time_period": "ALL_TIME",
    })
    payload = missing_is_empty(lambda: fetch_json(f"{RATINGS}?{query}"))
    # Match FirstPick's per-card reliability threshold. A handful of test
    # observations or a preview with no games should not trigger a release.
    reliable = {
        row["name"] for row in (payload or {}).get("data", [])
        if row.get("ever_drawn_win_rate") is not None
        and row.get("ever_drawn_game_count", 0) >= 200
    }
    return len(reliable) >= 30


def arena_themes(cards):
    """Use gold uncommons and shared non-evergreen mechanics as provisional hints."""
    signposts = defaultdict(list)
    for card in cards:
        colors = set(card.get("colors", []))
        if card["rarity"] == "uncommon" and len(colors) == 2 and colors <= set(COLORS):
            pair = "".join(color for color in COLORS if color in colors)
            signposts[pair].append(card)

    keyword_counts = Counter(keyword for card in cards for keyword in card.get("keywords", []))
    themes = {}
    for pair, gold in sorted(signposts.items()):
        shared = Counter(keyword for card in gold for keyword in card.get("keywords", []))
        mechanics = sorted(
            keyword for keyword, count in shared.items()
            if count >= 2 and 3 <= keyword_counts[keyword] <= len(cards) / 5
            and keyword not in COMMON_KEYWORDS
        )
        gold_names = sorted({card["name"] for card in gold})
        supporting = sorted({
            card["name"] for card in cards
            if card["rarity"] in {"common", "uncommon"} and card["name"] not in gold_names
            and card.get("colors") is not None and set(card["colors"]) <= set(pair)
            and set(card.get("keywords", [])).intersection(mechanics)
        })
        themes[pair] = {
            "name": " / ".join(mechanics) if supporting else "Multicolor uncommons",
            "signposts": gold_names,
            "supportingCards": supporting,
        }
    return themes


def sync_sets(manifest, calendar, today, get_cards=arena_cards, has_ratings=ratings_ready):
    changed = []
    declared = {entry["code"]: entry for entry in manifest["sets"]}
    first = max(FIRST_RELEASE, today - timedelta(days=120))
    last = today + timedelta(days=7)  # Arena often opens before the tabletop date.
    for product in sorted(calendar, key=lambda entry: entry.get("released_at") or ""):
        if product["set_type"] not in SET_TYPES or not product.get("released_at"):
            continue
        released = date.fromisoformat(product["released_at"])
        code = product["code"].upper()
        if not re.fullmatch(r"[A-Z0-9]{2,6}", code) or not first <= released <= last:
            continue
        entry = declared.get(code)
        if entry is not None and entry.get("ratingsReady", True):
            continue
        if entry is None:
            cards = get_cards(code)
            # Wait for an Arena card pool, including enough commons to draft
            # with. This excludes partial previews and standalone promos.
            if sum(card.get("rarity") == "common" for card in cards) < 40:
                continue
            entry = {
                "code": code, "tier": "catalog", "name": product["name"],
                "releasedAt": released.isoformat(), "ratingsReady": False,
                "themes": arena_themes(cards),
            }
            manifest["sets"].insert(0, entry)
            declared[code] = entry
            changed.append(f"{code} catalog")
        try:
            ready = has_ratings(code)
        except (OSError, ValueError, KeyError) as error:
            # Ship card-based coverage if the statistics provider is down.
            print(f"::warning::17Lands {code} check failed: {error}", file=sys.stderr)
            continue
        if ready:
            entry["ratingsReady"] = True
            changed.append(f"{code} 17Lands")
    if changed:
        manifest["checked"] = today.isoformat()
    return changed


def upcoming_wizards(page, today):
    # The dated cards sit in the Upcoming Sets carousel on the English page.
    section = re.search(r"Upcoming Sets\s*</span>\s*</h2>(.*?)</swiper-container>", page, re.S)
    if section is None:
        raise ValueError("Could not find the Upcoming Sets calendar")
    dates_and_names = re.findall(
        r"<span\b[^>]*>\s*([A-Za-z]+ \d+, \d{4})\s*</span>\s*<h3\b[^>]*>([^<]+)</h3>",
        section[1],
    )
    if not dates_and_names:
        raise ValueError("Could not read dates from the Upcoming Sets calendar")
    entries = [
        {"date": datetime.strptime(day, "%B %d, %Y").date().isoformat(),
         "name": " ".join(html.unescape(name).split())}
        for day, name in dates_and_names
    ]
    return [entry for entry in entries if entry["date"] >= today.isoformat()]


def write_json(path, document):
    path.write_text(json.dumps(document, ensure_ascii=False, indent=1) + "\n", encoding="utf-8")


def refresh_calendar(today):
    saved = json.loads(CALENDAR.read_text(encoding="utf-8")) if CALENDAR.exists() else {}
    try:
        upcoming = upcoming_wizards(fetch(WIZARDS, "text/html").decode("utf-8"), today)
    except (OSError, ValueError) as error:
        print(f"::warning::Wizards calendar check failed: {error}", file=sys.stderr)
        return
    last_checked = date.fromisoformat(saved.get("checked", "2000-01-01"))
    if upcoming != saved.get("upcoming") or today - last_checked >= timedelta(days=28):
        write_json(CALENDAR, {"source": WIZARDS, "checked": today.isoformat(), "upcoming": upcoming})


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--calendar", action="store_true", help="Also refresh Wizards' announced release dates")
    args = parser.parse_args()
    today = datetime.now(timezone.utc).date()
    manifest = json.loads(MANIFEST.read_text(encoding="utf-8"))
    changed = sync_sets(manifest, fetch_json(f"{SCRYFALL}/sets")["data"], today)
    if changed:
        write_json(MANIFEST, manifest)
    print("Set updates: " + (", ".join(changed) or "none"))
    if args.calendar:
        refresh_calendar(today)


if __name__ == "__main__":
    main()
