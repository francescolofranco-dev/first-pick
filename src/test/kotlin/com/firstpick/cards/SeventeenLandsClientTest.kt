package com.firstpick.cards

import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertTrue

class SeventeenLandsClientTest {

    @Test
    fun ratingsUrlAlwaysIncludesADateRange() {
        // Regression guard: dropping the date range makes the API return null win
        // rates for every non-current set (the "all values equal 37" bug).
        val url = SeventeenLandsClient.ratingsUrl("mkm", "PremierDraft", today = LocalDate.of(2026, 6, 15))
        assertTrue("expansion=MKM" in url, url)
        assertTrue("format=PremierDraft" in url, url)
        assertTrue("start_date=2019-01-01" in url, url)
        assertTrue("end_date=2026-06-15" in url, url)

        // colors param narrows to an archetype.
        val wu = SeventeenLandsClient.ratingsUrl("mkm", "PremierDraft", colors = "WU", today = LocalDate.of(2026, 6, 15))
        assertTrue("colors=WU" in wu, wu)
    }
}
