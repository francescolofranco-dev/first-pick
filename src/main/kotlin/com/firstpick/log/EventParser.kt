package com.firstpick.log

import com.firstpick.model.DraftEvent
import com.firstpick.model.DraftFormat
import com.firstpick.model.SavedDeck
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

class EventParser(
    private val json: Json = Json { ignoreUnknownKeys = true; isLenient = true },
) {
    fun parse(line: String): DraftEvent? = parseDraftEvent(line)?.takeUnless { event ->
        val name = when (event) {
            is DraftEvent.Snapshot -> event.eventName
            is DraftEvent.PackSeen -> event.eventName
            is DraftEvent.EventJoined -> event.eventName
            is DraftEvent.DeckSaved -> event.eventName
            is DraftEvent.PickMade -> ""
        }
        name.isNotBlank() && DraftFormat.fromEventName(name) == DraftFormat.UNKNOWN
    }

    private fun parseDraftEvent(line: String): DraftEvent? {
        if ('{' !in line) return null
        // A submission request may fail; only server course snapshots confirm a save.
        if (LogMatch.contains(line, "EventSetDeck") && "==>" in line) return null

        if (LogMatch.contains(line, "DraftPack")) {
            parseBotDraftSnapshot(line)?.let { return it }
        }
        if (LogMatch.contains(line, "BotDraftDraftPick") && LogMatch.contains(line, "CardIds")) {
            parseBotDraftPick(line)?.let { return it }
        }
        if (LogMatch.contains(line, "DraftNotify") || LogMatch.contains(line, "PackCards")) {
            parsePremierPack(line)?.let { return it }
        }
        if (LogMatch.contains(line, "PlayerDraftMakePick")) {
            parsePremierPick(line)?.let { return it }
        }

        if (LogMatch.contains(line, "CardPool") || LogMatch.contains(line, "DeckSelect")) {
            parseCourseSnapshot(line)?.let { return it }
        }

        if (LogMatch.contains(line, "CourseDeck")) {
            parseSavedDeckResponse(line)?.let { return it }
        }

        if (LogMatch.contains(line, "EventName") || LogMatch.contains(line, "InternalEventName")) {
            parseEventJoined(line)?.let { return it }
        }
        return null
    }

    private fun parseSavedDeckResponse(line: String): DraftEvent.DeckSaved? {
        val outer = decodeObject(line) ?: return null
        if ("Courses" in outer) return null
        val course = (outer["Course"] as? JsonObject) ?: outer
        val eventName = course.findString("InternalEventName") ?: return null
        if (DraftFormat.fromEventName(eventName) == DraftFormat.UNKNOWN) return null
        val deck = parseSavedDeck(course["CourseDeck"]) ?: return null
        return DraftEvent.DeckSaved(eventName, deck, course.findString("CourseId"))
    }

    private fun parseSavedDeck(element: JsonElement?): SavedDeck? {
        val deck = element as? JsonObject ?: return null
        fun counts(key: String): Map<Int, Int>? {
            val entries = deck[key] as? JsonArray ?: return null
            if (entries.size > 250) return null
            val result = linkedMapOf<Int, Int>()
            for (entry in entries) {
                val card = entry as? JsonObject ?: return null
                val id = (card["cardId"] as? JsonPrimitive)?.intOrNull ?: return null
                val count = (card["quantity"] as? JsonPrimitive)?.intOrNull ?: return null
                if (id <= 0 || count !in 1..250) return null
                result[id] = (result[id] ?: 0) + count
            }
            return result.takeIf { it.values.sum() <= 250 }
        }
        return SavedDeck(counts("MainDeck") ?: return null, counts("Sideboard") ?: return null)
    }

    private fun parseEventJoined(line: String): DraftEvent.EventJoined? {
        val outer = decodeObject(line) ?: return null
        val obj = (outer["Course"] as? JsonObject) ?: outer.findString("request")?.let {
            runCatching { json.parseToJsonElement(it) as? JsonObject }.getOrNull()
        } ?: outer
        val name = obj.findString("InternalEventName") ?: obj.findString("EventName") ?: return null
        if (DraftFormat.fromEventName(name) == DraftFormat.UNKNOWN) return null
        return DraftEvent.EventJoined(name, obj.findString("CourseId"))
    }

    private fun parseCourseSnapshot(line: String): DraftEvent.Snapshot? {
        val start = line.indexOf('{')
        if (start < 0) return null
        val slice = line.substring(start)
        val element = runCatching { json.parseToJsonElement(slice) }.getOrNull() ?: return null

        // Course inventories contain historical events, not an active draft selection.
        val outer = element as? JsonObject ?: return null
        if ("Courses" in outer) return null
        val course = (outer["Course"] as? JsonObject) ?: outer
        val eventName = course.findString("InternalEventName") ?: course.findString("EventName") ?: return null
        val format = DraftFormat.fromEventName(eventName)
        if (format == DraftFormat.UNKNOWN) return null

        val module = course.findString("CurrentModule")
        val cardPool = course.findStringList("CardPool")?.toInts() ?: emptyList()

        if (cardPool.isNotEmpty()) {
            val isComplete = module.equals("DeckSelect", ignoreCase = true) ||
                module.equals("Complete", ignoreCase = true) ||
                module.equals("DeckBuilder", ignoreCase = true) ||
                cardPool.size >= 40

            return DraftEvent.Snapshot(
                eventName = eventName,
                pack = 3,
                pick = 15,
                packCards = emptyList(),
                pool = cardPool,
                complete = isComplete,
                courseId = course.findString("CourseId"),
                savedDeck = parseSavedDeck(course["CourseDeck"]),
            )
        }
        return null
    }


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
            pack = payload.packNumber + 1,
            pick = payload.pickNumber + 1,
            packCards = payload.draftPack.toInts(),
            pool = payload.pickedCards.toInts(),
            complete = payload.draftStatus.equals("Complete", ignoreCase = true) ||
                payload.draftStatus.equals("Completed", ignoreCase = true),
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


    private fun parsePremierPack(line: String): DraftEvent.PackSeen? {
        val obj = decodeObject(line) ?: return null
        val packCards = obj.findStringList("PackCards") ?: return null
        if (packCards.isEmpty()) return null
        val pack = obj.findInt("SelfPack") ?: obj.findInt("PackNumber")?.plus(1) ?: 1
        val pick = obj.findInt("SelfPick") ?: obj.findInt("PickNumber")?.plus(1) ?: 1
        val eventName = obj.findString("EventName") ?: obj.findString("InternalEventName") ?: ""
        return DraftEvent.PackSeen(eventName, pack, pick, packCards.toInts(),
            obj.findString("draftId") ?: obj.findString("DraftId"))
    }

    private fun parsePremierPick(line: String): DraftEvent.PickMade? {
        val obj = decodeObject(line) ?: return null
        val request = obj.findString("request")
        val payload = request?.let { runCatching { json.parseToJsonElement(it) }.getOrNull() as? JsonObject }
            ?: obj
        val ids = payload.findStringList("GrpIds")
            ?: payload.findInt("GrpId")?.let { listOf(it.toString()) }
            ?: return null
        return DraftEvent.PickMade(
            pack = payload.findInt("Pack") ?: payload.findInt("PackNumber")?.plus(1) ?: 0,
            pick = payload.findInt("Pick") ?: payload.findInt("PickNumber")?.plus(1) ?: 0,
            cardIds = ids.toInts(),
        )
    }


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
