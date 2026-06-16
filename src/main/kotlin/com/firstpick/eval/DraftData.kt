package com.firstpick.eval

/** A single pick from a 17Lands draft dataset row. */
data class PickRow(
    val draftId: String,
    val pack: Int,        // 0-indexed as in the dataset
    val pick: Int,        // 0-indexed
    val pickedName: String,
    val wins: Int,        // event_match_wins for this drafter
    val packCards: List<String>,
)

/** Minimal RFC-4180 CSV line parser — handles quoted fields with embedded commas. */
object Csv {
    fun parse(line: String): List<String> {
        val out = ArrayList<String>(8)
        val sb = StringBuilder()
        var i = 0
        var inQuotes = false
        while (i < line.length) {
            val c = line[i]
            when {
                inQuotes -> when {
                    c == '"' && i + 1 < line.length && line[i + 1] == '"' -> { sb.append('"'); i++ }
                    c == '"' -> inQuotes = false
                    else -> sb.append(c)
                }
                c == '"' -> inQuotes = true
                c == ',' -> { out.add(sb.toString()); sb.setLength(0) }
                else -> sb.append(c)
            }
            i++
        }
        out.add(sb.toString())
        return out
    }
}

/**
 * Reads 17Lands draft-dataset rows. The header maps which columns are `pack_card_<Name>`;
 * the ordered pool is reconstructed from the sequence of picks (see [EvalHarness]), not the
 * `pool_*` columns, so it matches what the live advisor sees.
 */
class DraftReader(header: List<String>) {
    private val idxDraftId = header.indexOf("draft_id")
    private val idxPack = header.indexOf("pack_number")
    private val idxPick = header.indexOf("pick_number")
    private val idxPicked = header.indexOf("pick")
    private val idxWins = header.indexOf("event_match_wins")
    private val packCardCols: List<Pair<Int, String>> =
        header.withIndex()
            .filter { it.value.startsWith(PACK_CARD_PREFIX) }
            .map { it.index to it.value.removePrefix(PACK_CARD_PREFIX) }

    val valid: Boolean = idxDraftId >= 0 && idxPack >= 0 && idxPick >= 0 && idxPicked >= 0 && idxWins >= 0 && packCardCols.isNotEmpty()

    fun parse(fields: List<String>): PickRow? {
        if (fields.size <= packCardCols.lastOrNull()?.first.let { it ?: 0 }) return null
        val pack = fields.getOrNull(idxPack)?.toIntOrNull() ?: return null
        val pick = fields.getOrNull(idxPick)?.toIntOrNull() ?: return null
        val picked = fields.getOrNull(idxPicked)?.takeIf { it.isNotBlank() } ?: return null
        val wins = fields.getOrNull(idxWins)?.toIntOrNull() ?: 0
        val packCards = ArrayList<String>(16)
        for ((col, name) in packCardCols) {
            val count = fields.getOrNull(col)?.toIntOrNull() ?: 0
            repeat(count) { packCards.add(name) }
        }
        if (packCards.isEmpty()) return null
        return PickRow(fields[idxDraftId], pack, pick, picked, wins, packCards)
    }

    companion object {
        private const val PACK_CARD_PREFIX = "pack_card_"
    }
}
