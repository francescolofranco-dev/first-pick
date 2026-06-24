package com.firstpick.log

import com.firstpick.model.DraftEvent
import com.firstpick.model.DraftFormat
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Decodes a single MTGA `Player.log` line into a [DraftEvent], or null if the
 * line carries nothing we care about.
 *
 * Grounded in the real SOS-era log format (see docs and captured fixtures):
 *  - Quick/Bot draft pack: a bare-JSON line
 *      {"CurrentModule":"BotDraft","Payload":"<escaped JSON with DraftPack/PickedCards>"}
 *  - Quick/Bot draft pick: `BotDraftDraftPick ... "request":"{...PickInfo.CardIds...}"`
 *  - Premier/Traditional (human) draft is best-effort per spec until validated
 *    against a real human-draft log (Draft.Notify / Event_PlayerDraftMakePick).
 */
class EventParser(
    private val json: Json = Json { ignoreUnknownKeys = true; isLenient = true },
) {
    fun parse(line: String): DraftEvent? {
        if ('{' !in line) return null

        // Quick/Bot draft snapshot — the most reliable signal.
        if (LogMatch.contains(line, "DraftPack")) {
            parseBotDraftSnapshot(line)?.let { return it }
        }
        // Quick/Bot draft pick action.
        if (LogMatch.contains(line, "BotDraftDraftPick") && LogMatch.contains(line, "CardIds")) {
            parseBotDraftPick(line)?.let { return it }
        }
        // Human draft (Premier/Traditional) — best effort.
        if (LogMatch.contains(line, "DraftNotify") || LogMatch.contains(line, "PackCards")) {
            parsePremierPack(line)?.let { return it }
        }
        if (LogMatch.contains(line, "PlayerDraftMakePick")) {
            parsePremierPick(line)?.let { return it }
        }

        // Discover event joins to determine set and format (e.g. PremiumDraft / TradDraft).
        if (LogMatch.contains(line, "EventName") || LogMatch.contains(line, "InternalEventName")) {
            parseEventJoined(line)?.let { return it }
        }
        return null
    }

    private val eventNamePattern = Regex("\"(?:Internal)?EventName\"\\s*:\\s*\"([^\"]+)\"")

    private fun parseEventJoined(line: String): DraftEvent.EventJoined? {
        val match = eventNamePattern.find(line) ?: return null
        val name = match.groupValues[1]
        val format = DraftFormat.fromEventName(name)
        if (format == DraftFormat.UNKNOWN) return null
        return DraftEvent.EventJoined(name)
    }

    // ---- Quick / Bot draft ------------------------------------------------

    @Serializable
    private data class BotDraftOuter(
        @SerialName("CurrentModule") val currentModule: String? = null,
        @SerialName("Payload") val payload: String? = null,
    )

    @Serializable
    private data class BotDraftPayload(
        @SerialName("EventName") val eventName: String = "",
        @SerialName("DraftStatus") val draftStatus: String = "",
        @SerialName("PackNumber") val packNumber: Int = 0,
        @SerialName("PickNumber") val pickNumber: Int = 0,
        @SerialName("DraftPack") val draftPack: List<String> = emptyList(),
        @SerialName("PickedCards") val pickedCards: List<String> = emptyList(),
    )

    private fun parseBotDraftSnapshot(line: String): DraftEvent.Snapshot? {
        val obj = decodeObject(line) ?: return null
        val outer = runCatching { json.decodeFromJsonElement(BotDraftOuter.serializer(), obj) }.getOrNull()
            ?: return null
        if (outer.currentModule != "BotDraft" || outer.payload.isNullOrEmpty()) return null
        val payload = runCatching { json.decodeFromString(BotDraftPayload.serializer(), outer.payload) }
            .getOrNull() ?: return null
        if (payload.eventName.isEmpty()) return null

        return DraftEvent.Snapshot(
            eventName = payload.eventName,
            pack = payload.packNumber + 1, // Arena is 0-indexed
            pick = payload.pickNumber + 1,
            packCards = payload.draftPack.toInts(),
            pool = payload.pickedCards.toInts(),
            complete = !payload.draftStatus.equals("PickNext", ignoreCase = true),
        )
    }

    @Serializable
    private data class BotDraftPickRequest(
        @SerialName("id") val id: String? = null,
        @SerialName("request") val request: String? = null,
    )

    @Serializable
    private data class BotDraftPickInfo(
        @SerialName("PickInfo") val pickInfo: PickInfo? = null,
    ) {
        @Serializable
        data class PickInfo(
            @SerialName("CardIds") val cardIds: List<String> = emptyList(),
            @SerialName("PackNumber") val packNumber: Int = 0,
            @SerialName("PickNumber") val pickNumber: Int = 0,
        )
    }

    private fun parseBotDraftPick(line: String): DraftEvent.PickMade? {
        val obj = decodeObject(line) ?: return null
        val outer = runCatching { json.decodeFromJsonElement(BotDraftPickRequest.serializer(), obj) }.getOrNull()
            ?: return null
        val request = outer.request ?: return null
        val info = runCatching { json.decodeFromString(BotDraftPickInfo.serializer(), request) }
            .getOrNull()?.pickInfo ?: return null
        if (info.cardIds.isEmpty()) return null
        return DraftEvent.PickMade(
            pack = info.packNumber + 1,
            pick = info.pickNumber + 1,
            cardIds = info.cardIds.toInts(),
        )
    }

    // ---- Premier / Traditional (human) draft — best effort ----------------

    private fun parsePremierPack(line: String): DraftEvent.PackSeen? {
        val obj = decodeObject(line) ?: return null
        // PackCards may be a comma-separated string or an array, depending on Arena version.
        val packCards = obj.findStringList("PackCards") ?: return null
        if (packCards.isEmpty()) return null
        val pack = obj.findInt("SelfPack") ?: obj.findInt("PackNumber")?.plus(1) ?: 1
        val pick = obj.findInt("SelfPick") ?: obj.findInt("PickNumber")?.plus(1) ?: 1
        val eventName = obj.findString("EventName") ?: obj.findString("InternalEventName") ?: ""
        return DraftEvent.PackSeen(eventName, pack, pick, packCards.toInts())
    }

    private fun parsePremierPick(line: String): DraftEvent.PickMade? {
        val obj = decodeObject(line) ?: return null
        // Picks live inside a nested escaped "request" string here too.
        val request = obj.findString("request")
        val payload = request?.let { runCatching { json.parseToJsonElement(it) }.getOrNull() as? JsonObject }
            ?: obj
        val ids = payload.findStringList("GrpIds")
            ?: payload.findInt("GrpId")?.let { listOf(it.toString()) }
            ?: return null
        return DraftEvent.PickMade(
            pack = payload.findInt("PackNumber")?.plus(1) ?: 0,
            pick = payload.findInt("PickNumber")?.plus(1) ?: 0,
            cardIds = ids.toInts(),
        )
    }

    // ---- JSON helpers -----------------------------------------------------

    /** Extract the outermost JSON object from a line that may carry a log prefix. */
    private fun decodeObject(line: String): JsonObject? {
        val start = line.indexOf('{')
        if (start < 0) return null
        val slice = line.substring(start)
        return runCatching { json.parseToJsonElement(slice) as? JsonObject }.getOrNull()
    }

    private fun List<String>.toInts(): List<Int> = mapNotNull { it.trim().toIntOrNull() }

    private fun JsonObject.findString(key: String): String? =
        (this[key] as? JsonPrimitive)?.contentOrNull

    private fun JsonObject.findInt(key: String): Int? =
        (this[key] as? JsonPrimitive)?.contentOrNull?.toIntOrNull()

    /** Read a key that is either a JSON array of ids or a comma-separated string. */
    private fun JsonObject.findStringList(key: String): List<String>? {
        return when (val el: JsonElement? = this[key]) {
            is JsonArray -> el.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
            is JsonPrimitive -> el.contentOrNull
                ?.split(',')
                ?.map { it.trim() }
                ?.filter { it.isNotEmpty() }
            else -> null
        }
    }
}
