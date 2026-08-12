package com.firstpick.cards

import java.nio.file.Files
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class BasicLandResolverTest {
    @Test
    fun resolvesCanonicalBasicFromArenaDatabaseAndCachesTheResult() {
        val dir = createTempDirectory("fp-arena-cards")
        Files.writeString(dir.resolve("Raw_CardDatabase_fixture.mtga"), "fixture")
        val fakeSqlite = dir.resolve("sqlite3")
        Files.writeString(
            fakeSqlite,
            """
                #!/bin/sh
                case "${'$'}*" in
                  *92374*) printf 'Island\n' ;;
                  *75329*) printf '<nobr>Snow-Covered</nobr> Island\n' ;;
                  *) printf 'Lightning Bolt\n' ;;
                esac
            """.trimIndent(),
        )
        fakeSqlite.toFile().setExecutable(true)
        val resolver = ArenaBasicLandResolver(dir, fakeSqlite)

        val island = resolver.resolve(92374)

        assertEquals("Island", island?.name)
        assertEquals(
            "https://api.scryfall.com/cards/arena/92374?format=image&version=normal",
            island?.imageUrl,
        )
        assertEquals(island, resolver.resolve(92374))
        assertEquals("Snow-Covered Island", resolver.resolve(75329)?.name)
        assertNull(resolver.resolve(42), "non-basic database rows must remain unresolved")
    }

    @Test
    fun retriesAnUnresolvedIdWhenArenaDatabaseAppearsAfterStartup() {
        val dir = createTempDirectory("fp-arena-cards-late")
        val fakeSqlite = dir.resolve("sqlite3")
        Files.writeString(fakeSqlite, "#!/bin/sh\nprintf 'Island\\n'\n")
        fakeSqlite.toFile().setExecutable(true)
        val resolver = ArenaBasicLandResolver(dir, fakeSqlite)

        assertNull(resolver.resolve(92374))

        Files.writeString(dir.resolve("Raw_CardDatabase_late.mtga"), "fixture")
        assertEquals("Island", resolver.resolve(92374)?.name)
    }
}
