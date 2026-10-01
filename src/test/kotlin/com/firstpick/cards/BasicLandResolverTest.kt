package com.firstpick.cards

import java.nio.file.Files
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import java.nio.file.Path

class BasicLandResolverTest {
    @Test
    fun joinsNewSetIdsAndNamesFromTheReadOnlyArenaSchema() {
        val sqlite = Path.of("/usr/bin/sqlite3")
        org.junit.Assume.assumeTrue(Files.isExecutable(sqlite))
        val dir = createTempDirectory("fp-arena-catalog")
        val db = dir.resolve("Raw_CardDatabase_fixture.mtga")
        val create = ProcessBuilder(sqlite.toString(), db.toString(), """
            CREATE TABLE Cards (GrpId INTEGER, TitleId INTEGER, TypeTextId INTEGER, ExpansionCode TEXT, IsPrimaryCard INTEGER);
            CREATE TABLE Localizations_enUS (LocId INTEGER, Loc TEXT, Formatted INTEGER);
            INSERT INTO Localizations_enUS VALUES (1, '<nobr>Blossom-Blessed</nobr> Angel', 1), (2, 'Creature', 1), (3, 'Heartwood Crafter', 1), (4, 'Seed Suture', 1);
            INSERT INTO Cards VALUES (106227, 1, 2, 'FRA', 1), (106339, 3, 2, 'FRA', 1), (106228, 4, 2, 'FRA', 0);
        """.trimIndent()).start()
        assertEquals(0, create.waitFor())
        val resolver = ArenaCardNameResolver(dir, sqlite)
        assertEquals(mapOf("blossom-blessed angel" to 106227, "heartwood crafter" to 106339), resolver.idsForSet("fra"))
        assertEquals("Blossom-Blessed Angel", resolver.resolve(106227))
        assertEquals("Seed Suture", resolver.resolve(106228))
        assertNull(resolver.resolve(123456))
        assertTrue(resolver.idsForSet("FRA'; DROP TABLE Cards;").isEmpty())
    }

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
