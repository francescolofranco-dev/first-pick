package com.firstpick.cards

/** Why a network fetch couldn't return fresh data — lets the UI say *what* went wrong. */
enum class FetchFailure {
    RATE_LIMITED, // HTTP 429
    NOT_FOUND,    // HTTP 404 (e.g. set/format not on 17Lands)
    SERVER_ERROR, // HTTP 5xx
    OFFLINE,      // connection/timeout
    BAD_DATA,     // got a response but couldn't parse it
}

/**
 * Thrown when ESSENTIAL data (17Lands card ratings) can't be produced — neither a
 * fresh fetch nor a stale cache. Optional data (Scryfall meta, archetype strengths)
 * degrades silently instead. Callers map [reason] to a user-facing message.
 */
class DataUnavailableException(val reason: FetchFailure, cause: Throwable? = null) :
    Exception("data unavailable: $reason", cause)
