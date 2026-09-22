package com.firstpick.overlay

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class NativeHelpersTest {
    @Test
    fun `packaged helpers stay tied to the launching bundle`() {
        val root = Files.createTempDirectory("firstpick-helpers").toFile()
        try {
            val contents = root.resolve("FirstPick.app/Contents")
            val helper = contents.resolve("Helpers/deck-reader")
            helper.parentFile.mkdirs()
            helper.writeText("helper")
            helper.setExecutable(true)
            assertEquals(helper, NativeHelpers.packagedHelper(
                contents.resolve("MacOS/FirstPick").path, "deck-reader"))
            assertFailsWith<IllegalStateException> {
                NativeHelpers.packagedHelper(contents.resolve("MacOS/FirstPick").path, "window-capture")
            }
            assertNull(NativeHelpers.packagedHelper(null, "deck-reader"))
            assertNull(NativeHelpers.packagedHelper(root.resolve("Other.app/Contents/MacOS/Other").path, "deck-reader"))
        } finally {
            root.deleteRecursively()
        }
    }
}
