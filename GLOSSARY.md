# FirstPick

FirstPick helps players choose cards during an Arena draft and assemble a deck
from their drafted cards.

## Language

**Draft pack**:
The cards currently offered for a draft pick.

**Draft pool**:
The cards the player has acquired during the current draft, including copies
currently in the deck.

**Current deck**:
The player's present card selection in Arena's deck builder, including unsaved
changes.
_Avoid_: Saved deck, suggested build

**Suggested build**:
The deck composition FirstPick recommends from the draft pool, including its
basic-land allocation.
_Avoid_: Current deck

**Deck guidance**:
The additions and cuts needed to make the current deck match the selected
suggested build.

**Horizontal deck layout**:
Arena's deck-builder arrangement with the card pool above the deck's stacked
card columns.

**Vertical deck layout**:
Arena's deck-builder arrangement with the card pool on the left and the deck
list on the right.

**Front card**:
The card at the front of a stack in the horizontal deck layout, uncovered by
other deck cards.
_Avoid_: First row

**Score badge**:
The grade and numerical advisor score displayed beside an individual draft card.

**Advisor score**:
FirstPick's contextual assessment of a card as a draft choice, taking the
player's drafted cards into account.

**Add/Cut highlight**:
A translucent marking over a whole pool card or deck entry. Green indicates
copies to add to the current deck, and red indicates deck copies to cut.

**Copy change**:
The number of copies of a card that must be added or cut to make the current
deck match the selected suggested build.

**Close cuts**:
Non-basic card copies in the draft pool that are absent from the selected
suggested build. This read-only list presents near-miss alternatives; it is not
a sideboard. Copies already used by the build are subtracted individually.

**Fit**:
The compact workspace label for the advisor score. It is a contextual preference,
not a win percentage. GIH WR is a separate observed reference metric.

**Draft review**:
Read-only inspection of a locally recorded draft and its linked event/matches.
A recorded saved deck is distinguished from the drafted pool. Missing historical
pick decisions or advisor scores must not be inferred from the final pool.
