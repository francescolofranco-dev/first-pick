package com.firstpick.tools

import com.firstpick.cards.StandardSets
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * Rotation audit: checks every bundled synergy profile against live Standard legality
 * (Scryfall) and reports which sets are still legal and which have rotated out and should
 * be dropped. Run: ./gradlew auditSets
 */
fun main() {
    val json = Json { ignoreUnknownKeys = true }
    val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build()

    val bundled = StandardSets.manifest.sets.map { it.code.uppercase() }.sorted()
    println("Bundled synergy profiles (${bundled.size}): ${bundled.joinToString(" ")}")
    println("Manifest checked: ${StandardSets.manifest.checked}\n")

    val rotated = mutableListOf<String>()
    for (code in bundled) {
        val legal = standardLegal(http, json, code)
        val mark = when (legal) {
            true -> "LEGAL"
            false -> "ROTATED — drop /synergy/$code.json + its manifest entry".also { rotated += code }
            null -> "??? (could not verify)"
        }
        println("  %-4s %s".format(code, mark))
    }

    println()
    if (rotated.isEmpty()) {
        println("All bundled profiles are Standard-legal. Nothing to drop.")
    } else {
        println("Rotated out (${rotated.size}): ${rotated.joinToString(" ")}")
        println("Delete each src/main/resources/synergy/<CODE>.json and remove it from standard.json.")
    }
}

/** null = couldn't determine; true/false = Standard legality of a representative card. */
private fun standardLegal(http: HttpClient, json: Json, setCode: String): Boolean? = runCatching {
    val url = "https://api.scryfall.com/cards/search?q=e%3A${setCode.lowercase()}&unique=cards&page=1"
    val req = HttpRequest.newBuilder(URI.create(url))
        .header("User-Agent", "FirstPick/0.1 (personal use)")
        .header("Accept", "application/json")
        .timeout(Duration.ofSeconds(30)).GET().build()
    val resp = http.send(req, HttpResponse.BodyHandlers.ofString())
    if (resp.statusCode() != 200) return@runCatching null
    val first = json.parseToJsonElement(resp.body()).jsonObject["data"]?.jsonArray?.firstOrNull()
        ?: return@runCatching null
    val standard = first.jsonObject["legalities"]?.jsonObject?.get("standard")?.jsonPrimitive?.content
    standard == "legal"
}.getOrNull()
