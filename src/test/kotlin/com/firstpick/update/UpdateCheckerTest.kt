package com.firstpick.update

import kotlinx.coroutines.test.runTest
import java.net.URI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class UpdateCheckerTest {
    @Test
    fun followsSemanticVersionPrecedence() {
        val ordered = listOf(
            "1.0.0-alpha",
            "1.0.0-alpha.1",
            "1.0.0-alpha.beta",
            "1.0.0-beta",
            "1.0.0-beta.2",
            "1.0.0-beta.11",
            "1.0.0-rc.1",
            "1.0.0",
            "1.0.1",
            "1.1.0",
            "2.0.0",
        ).map { SemanticVersion.parse(it)!! }

        assertEquals(ordered, ordered.shuffled().sorted())
        assertEquals(SemanticVersion.parse("v1.2.3"), SemanticVersion.parse("1.2.3+build.9"))
        assertNull(SemanticVersion.parse("1.2"))
        assertNull(SemanticVersion.parse("1.2.3-01"))
    }

    @Test
    fun selectsTheAppleSiliconDmgForAnAvailableUpdate() = runTest {
        val release = release("v1.3.0")
        val result = UpdateChecker({ release }, CpuArchitecture.ARM64).check("1.2.3")

        val available = assertIs<UpdateCheckResult.Available>(result)
        assertEquals("FirstPick-1.3.0-arm64.dmg", available.asset?.name)
        assertEquals(URI("https://example.test/FirstPick-1.3.0-arm64.dmg"), available.asset?.downloadUrl)
    }

    @Test
    fun selectsTheIntelDmgForAnAvailableUpdate() = runTest {
        val result = UpdateChecker({ release("v2.0.0") }, CpuArchitecture.X86_64).check("1.9.9")

        val available = assertIs<UpdateCheckResult.Available>(result)
        assertEquals("FirstPick-2.0.0-x86_64.dmg", available.asset?.name)
    }

    @Test
    fun reportsAnAvailableReleaseEvenWhenItHasNoCompatibleDmg() = runTest {
        val result = UpdateChecker({ release("v2.0.0") }, CpuArchitecture.UNKNOWN).check("1.9.9")

        assertNull(assertIs<UpdateCheckResult.Available>(result).asset)
    }

    @Test
    fun doesNotFetchForDevelopmentBuilds() = runTest {
        var fetched = false
        val checker = UpdateChecker(
            source = {
                fetched = true
                release("v2.0.0")
            },
        )

        assertIs<UpdateCheckResult.NotCheckable>(checker.check("unspecified"))
        assertEquals(false, fetched)
    }

    @Test
    fun reportsCurrentAndNewerLocalBuildsAsUpToDate() = runTest {
        val source = LatestReleaseSource { release("v1.2.3") }

        assertIs<UpdateCheckResult.UpToDate>(UpdateChecker(source).check("1.2.3"))
        assertIs<UpdateCheckResult.UpToDate>(UpdateChecker(source).check("1.3.0"))
    }

    @Test
    fun turnsSourceErrorsIntoAStableUiResult() = runTest {
        val checker = UpdateChecker(source = { error("offline") })

        assertEquals(
            UpdateCheckResult.Failed("FirstPick couldn't check for updates."),
            checker.check("1.0.0"),
        )
    }

    @Test
    fun decodesGitHubReleaseMetadataAndIgnoresUnknownFields() {
        val source = GitHubLatestReleaseSource()
        val result = source.decodeRelease(
            """
            {
              "tag_name": "v1.4.0",
              "name": "FirstPick 1.4",
              "html_url": "https://github.com/example/project/releases/tag/v1.4.0",
              "draft": false,
              "unknown": "ignored",
              "assets": [{
                "name": "FirstPick-1.4.0-arm64.dmg",
                "browser_download_url": "https://example.test/arm64.dmg",
                "size": 1234
              }]
            }
            """.trimIndent(),
        )

        assertEquals("FirstPick 1.4", result.title)
        assertEquals(1234, result.assets.single().sizeBytes)
    }

    @Test
    fun mapsCommonJvmArchitectureNames() {
        assertEquals(CpuArchitecture.ARM64, CpuArchitecture.current("aarch64"))
        assertEquals(CpuArchitecture.ARM64, CpuArchitecture.current("arm64"))
        assertEquals(CpuArchitecture.X86_64, CpuArchitecture.current("amd64"))
        assertEquals(CpuArchitecture.X86_64, CpuArchitecture.current("x86_64"))
        assertEquals(CpuArchitecture.UNKNOWN, CpuArchitecture.current("riscv64"))
    }

    private fun release(tag: String): RemoteRelease = RemoteRelease(
        tagName = tag,
        title = "FirstPick $tag",
        pageUrl = URI("https://example.test/releases/$tag"),
        assets = listOf(
            asset("FirstPick-${tag.removePrefix("v")}-arm64.dmg"),
            asset("FirstPick-${tag.removePrefix("v")}-x86_64.dmg"),
            asset("FirstPick-${tag.removePrefix("v")}-arm64.dmg.sha256"),
        ),
    )

    private fun asset(name: String) = ReleaseAsset(
        name = name,
        downloadUrl = URI("https://example.test/$name"),
        sizeBytes = 42,
    )
}
