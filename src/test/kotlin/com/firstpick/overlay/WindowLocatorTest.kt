package com.firstpick.overlay

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class WindowLocatorTest {

    @Test
    fun parsesFoundBounds() {
        val b = WindowLocator.parse("""{"found":true,"x":97,"y":46,"w":1280,"h":748}""")
        assertEquals(WindowBounds(97, 46, 1280, 748), b)
    }

    @Test
    fun parsesNegativeCoordinates() {
        // A window on a secondary display can have negative origin.
        val b = WindowLocator.parse("""{"found":true,"x":-1440,"y":-200,"w":800,"h":600}""")
        assertEquals(WindowBounds(-1440, -200, 800, 600), b)
    }

    @Test
    fun returnsNullWhenNotFound() {
        assertNull(WindowLocator.parse("""{"found":false}"""))
    }

    @Test
    fun returnsNullOnGarbage() {
        assertNull(WindowLocator.parse(""))
        assertNull(WindowLocator.parse("not json"))
        // found:true but missing fields → null, not a partial bounds
        assertNull(WindowLocator.parse("""{"found":true,"x":10}"""))
    }
}
