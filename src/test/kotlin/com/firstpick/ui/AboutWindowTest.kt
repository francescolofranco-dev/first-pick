package com.firstpick.ui

import com.firstpick.update.SemanticVersion
import com.firstpick.update.UpdateCheckResult
import kotlin.test.Test
import kotlin.test.assertEquals

class AboutWindowTest {
    @Test
    fun formatsTheConfiguredApplicationVersion() {
        assertEquals("Version 1.2.3", aboutVersionLabel(" 1.2.3 "))
    }

    @Test
    fun identifiesBuildsWithoutApplicationMetadataAsDevelopmentBuilds() {
        assertEquals("Development build", aboutVersionLabel(null))
        assertEquals("Development build", aboutVersionLabel(""))
        assertEquals("Development build", aboutVersionLabel("unspecified"))
    }

    @Test
    fun describesUpdateCheckResultsForTheAboutWindow() {
        val version = SemanticVersion(1, 2, 3)
        assertEquals(
            "FirstPick 1.2.3 is up to date.",
            updateResultMessage(UpdateCheckResult.UpToDate(version, version)),
        )
        assertEquals(
            "Update checks are unavailable for development builds.",
            updateResultMessage(UpdateCheckResult.NotCheckable(null)),
        )
        assertEquals(
            "Offline",
            updateResultMessage(UpdateCheckResult.Failed("Offline")),
        )
    }
}
