# Personal stats and durable storage

FirstPick stores drafts and structured performance records in
`~/Library/Application Support/FirstPick/firstpick.db` (SQLite, schema version 1).
The existing `draft-history.json` is migrated transactionally into the database;
the original JSON stays in place as a recovery copy. Migration is idempotent and
preserves draft IDs, timestamps, saved decks, seen packs, and every pool copy.

At startup FirstPick scans Arena's current/previous Player logs, archived UTC
session logs, and local MTGA Draft Tool logs. It then records new events from the
live log watcher. Stable Arena course IDs and match IDs prevent duplicate totals
when a session is replayed. There is no expiry or automatic deletion.

The **Analytics** workspace provides **Overview** and **Draft history**. Expansion,
format, and period filters stay applied when switching between them. Overview
shows match win rate, completed drafts, trophy runs, finish distribution, and match
results. Open a draft from history to review **Deck**, **Pick log**, and **Results**.
See the [current screenshots](../README.md#a-look-inside). Only Draft
events and their matches contribute to statistics or filter choices. Constructed,
normal Play, Midweek Magic, and Sealed events are excluded, including records
already saved by earlier app versions. New unrelated records are not persisted.

- Event totals use the largest observed cumulative win/loss counters per course.
  Counts can be incomplete when later results were never logged. Date filters
  select events by first observation and show their whole recorded totals.
- Drafts with pools but no result counters remain in History and the draft count;
  they are excluded from result calculations. Missing results are not losses.
- Match results require a confirmed match-level result and a known local seat/team.
  Individual games of a best-of-three do not count as separate matches. Win rate
  excludes draws, which are shown separately.
- Play/draw results require an observed starting player in game one. Unknown
  starting players are excluded from those two groups.
- Color breakdowns use an observed saved deck and locally cached card colors,
  rather than treating all colors in a drafted pool as the played deck.
- Stored Limited rank observations are separate from draft results. Arena’s Limited
  ladder is shared with Sealed; its season totals are not counted as draft results.
  The current Analytics workspace does not expose a separate Rank section.
- Trophies require an observed completed event with seven wins (three for
  Traditional Draft, four for Pick Two Draft). An absent trophy record does not mean every old event failed.

Only structured draft/performance fields are persisted. Authentication packets,
chat, and opponent names are not stored. No records are uploaded.

SQLite writes are transactional, use WAL journaling, and have a busy timeout.
Future schema versions are rejected instead of being opened as a fresh database.

Historical drafts open using saved card ratings, catalogs, and metadata even when
those caches are older than live-advice refresh limits. Optional archetype data
is loaded only from disk during review, so its refresh cannot block the saved
pool or deck builds. The history filters include saved expansions, and records
without known results remain available for review.

Verified historical Untapped draft results can be recovered into the local file
`~/Library/Application Support/FirstPick/recovered-draft-results.json`. Startup
validates its structured course and match rows before merging them into SQLite.
Stable source IDs make repeated imports idempotent. Source URLs and exact pool
links (when available) remain on the records; personal recovery data is not part
of the repository. Event totals and individual replay coverage can differ.
Imported matches with no exact timestamp display their known draft date explicitly.
This is a local recovery import, not automatic synchronization with Untapped.
