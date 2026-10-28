# Issue tracker: Local Markdown

Issues and specs live in `.scratch/`.

- Feature directory: `.scratch/<feature-slug>/`
- Spec: `spec.md`
- Tickets: `issues/<NN>-<slug>.md`, numbered from 01;
  one file per ticket.
- Triage state: a `Status:` line near the top, using
  the roles in `triage-labels.md`.
- Conversation: append under `## Comments`.

Publishing means creating the appropriate local file.
Fetching a ticket means reading its referenced file.

## Wayfinding operations

- Map: `.scratch/<effort>/map.md`, containing Notes,
  Decisions-so-far, and Fog.
- Child tickets: `issues/NN-<slug>.md`, with the question
  in the body and a Type of research/prototype/grilling/task.
- Dependencies: `Blocked by: NN, NN`.
- Frontier: first numbered open, unblocked, unclaimed ticket.
- Claim: save `Status: claimed` before working.
- Resolve: append `## Answer`, set `Status: resolved`,
  and add a summary and link to the map's Decisions-so-far.
