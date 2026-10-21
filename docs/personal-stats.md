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

The Stats tab provides Overview, Matches, and Rank sections. Set, play type, and
date filters apply to Overview and Matches; Rank has a date filter. Only Draft
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
- Rank shows only Arena’s Limited ladder. This ladder is shared with Sealed, so
  its aggregate season win/loss totals are not shown as draft results. Constructed
  rank is not displayed. Draft results come only from Draft events and matches.
- Trophies require an observed completed event with seven wins (three for
  Traditional Draft). An absent trophy record does not mean every old event failed.

Only structured draft/performance fields are persisted. Authentication packets,
chat, and opponent names are not stored. No records are uploaded.

SQLite writes are transactional, use WAL journaling, and have a busy timeout.
Future schema versions are rejected instead of being opened as a fresh database.
