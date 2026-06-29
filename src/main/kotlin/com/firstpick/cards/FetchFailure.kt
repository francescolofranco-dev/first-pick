package com.firstpick.cards

enum class FetchFailure {
    RATE_LIMITED,
    NOT_FOUND,
    SERVER_ERROR,
    OFFLINE,
    BAD_DATA,
}

class DataUnavailableException(val reason: FetchFailure, cause: Throwable? = null) :
    Exception("data unavailable: $reason", cause)
