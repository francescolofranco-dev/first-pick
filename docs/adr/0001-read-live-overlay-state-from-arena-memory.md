# Read live overlay state and positions from Arena memory

FirstPick uses authorized, read-only Arena memory access for live card
identities, quantities and positions during drafting and deck building. The user
chose this approach after screen recognition repeatedly lost cards during
scrolling, confused truncated names and misplaced badges; screenshot recognition
was removed from the production overlay. The trade-off is explicit access
authorization and support for specific Arena builds: unavailable or unsupported
memory readings must be reported honestly rather than replaced with guessed
screen-derived instructions.
