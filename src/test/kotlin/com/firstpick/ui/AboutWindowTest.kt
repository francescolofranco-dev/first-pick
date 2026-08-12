package com.firstpick.ui

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
}
