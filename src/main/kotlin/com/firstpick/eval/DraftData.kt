package com.firstpick.eval

data class PickRow(
    val draftId: String,
    val pack: Int,
    val pick: Int,
    val pickedName: String,
    val wins: Int,
    val losses: Int,
    val rank: String,
    val packCards: List<String>,
)

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

class DraftReader(header: List<String>) {
    private val idxDraftId = header.indexOf("draft_id")
    private val idxPack = header.indexOf("pack_number")
    private val idxPick = header.indexOf("pick_number")
    private val idxPicked = header.indexOf("pick")
    private val idxWins = header.indexOf("event_match_wins")
    private val idxLosses = header.indexOf("event_match_losses")
    private val idxRank = header.indexOf("rank")
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
        val losses = if (idxLosses >= 0) fields.getOrNull(idxLosses)?.toIntOrNull() ?: 0 else 0
        val rank = if (idxRank >= 0) fields.getOrNull(idxRank).orEmpty() else ""
        val packCards = ArrayList<String>(16)
        for ((col, name) in packCardCols) {
            val count = fields.getOrNull(col)?.toIntOrNull() ?: 0
            repeat(count) { packCards.add(name) }
        }
        if (packCards.isEmpty()) return null
        return PickRow(fields[idxDraftId], pack, pick, picked, wins, losses, rank, packCards)
    }

    companion object {
        private const val PACK_CARD_PREFIX = "pack_card_"
    }
}
