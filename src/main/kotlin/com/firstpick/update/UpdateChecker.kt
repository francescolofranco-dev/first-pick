package com.firstpick.update

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/** A Semantic Versioning 2.0.0 version. Build metadata is ignored for precedence. */
data class SemanticVersion(
    val major: Int,
    val minor: Int,
    val patch: Int,
    val preRelease: List<String> = emptyList(),
) : Comparable<SemanticVersion> {
    override fun compareTo(other: SemanticVersion): Int {
        major.compareTo(other.major).takeIf { it != 0 }?.let { return it }
        minor.compareTo(other.minor).takeIf { it != 0 }?.let { return it }
        patch.compareTo(other.patch).takeIf { it != 0 }?.let { return it }

        if (preRelease.isEmpty() && other.preRelease.isNotEmpty()) return 1
        if (preRelease.isNotEmpty() && other.preRelease.isEmpty()) return -1

        for (index in 0 until minOf(preRelease.size, other.preRelease.size)) {
            compareIdentifier(preRelease[index], other.preRelease[index]).takeIf { it != 0 }?.let { return it }
        }
        return preRelease.size.compareTo(other.preRelease.size)
    }

    override fun toString(): String = buildString {
        append("$major.$minor.$patch")
        if (preRelease.isNotEmpty()) append("-${preRelease.joinToString(".")}")
    }

    companion object {
        private val VERSION_PATTERN = Regex(
            "^[vV]?(0|[1-9]\\d*)\\.(0|[1-9]\\d*)\\.(0|[1-9]\\d*)" +
                "(?:-([0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*))?" +
                "(?:\\+[0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*)?$",
        )

        fun parse(value: String?): SemanticVersion? {
            val match = VERSION_PATTERN.matchEntire(value?.trim().orEmpty()) ?: return null
            val major = match.groupValues[1].toIntOrNull() ?: return null
            val minor = match.groupValues[2].toIntOrNull() ?: return null
            val patch = match.groupValues[3].toIntOrNull() ?: return null
            val preRelease = match.groupValues[4]
                .takeIf(String::isNotEmpty)
                ?.split('.')
                ?: emptyList()
            if (preRelease.any { it.length > 1 && it.all(Char::isDigit) && it.startsWith('0') }) return null
            return SemanticVersion(major, minor, patch, preRelease)
        }

        private fun compareIdentifier(left: String, right: String): Int {
            val leftNumeric = left.all(Char::isDigit)
            val rightNumeric = right.all(Char::isDigit)
            return when {
                leftNumeric && rightNumeric -> compareNumericStrings(left, right)
                leftNumeric -> -1
                rightNumeric -> 1
                else -> left.compareTo(right)
            }
        }

        private fun compareNumericStrings(left: String, right: String): Int =
            left.length.compareTo(right.length).takeIf { it != 0 } ?: left.compareTo(right)
    }
}

enum class CpuArchitecture {
    ARM64,
    X86_64,
    UNKNOWN;

    internal fun matches(assetName: String): Boolean {
        val name = assetName.lowercase()
        return when (this) {
            ARM64 -> ARCH_ARM64.containsMatchIn(name)
            X86_64 -> ARCH_X86_64.containsMatchIn(name)
            UNKNOWN -> false
        }
    }

    companion object {
        private val ARCH_ARM64 = Regex("(?:^|[-_.])(arm64|aarch64)(?:[-_.]|$)")
        private val ARCH_X86_64 = Regex("(?:^|[-_.])(x86_64|amd64|x64)(?:[-_.]|$)")

        fun current(osArch: String = System.getProperty("os.arch").orEmpty()): CpuArchitecture =
            when (osArch.lowercase()) {
                "aarch64", "arm64" -> ARM64
                "amd64", "x86_64", "x64" -> X86_64
                else -> UNKNOWN
            }
    }
}

data class ReleaseAsset(
    val name: String,
    val downloadUrl: URI,
    val sizeBytes: Long,
)

data class RemoteRelease(
    val tagName: String,
    val title: String,
    val pageUrl: URI,
    val assets: List<ReleaseAsset>,
) {
    fun dmgFor(architecture: CpuArchitecture): ReleaseAsset? = assets.firstOrNull { asset ->
        asset.name.lowercase().endsWith(".dmg") && architecture.matches(asset.name)
    }
}

fun interface LatestReleaseSource {
    suspend fun latestRelease(): RemoteRelease
}

sealed interface UpdateCheckResult {
    data class Available(
        val currentVersion: SemanticVersion,
        val latestVersion: SemanticVersion,
        val release: RemoteRelease,
        val asset: ReleaseAsset?,
    ) : UpdateCheckResult

    data class UpToDate(
        val currentVersion: SemanticVersion,
        val latestVersion: SemanticVersion,
    ) : UpdateCheckResult

    data class NotCheckable(val currentVersion: String?) : UpdateCheckResult

    data class Failed(val message: String) : UpdateCheckResult
}

/**
 * Checks release metadata and returns links for the UI to present. It deliberately never downloads
 * or installs release assets.
 */
class UpdateChecker(
    private val source: LatestReleaseSource = GitHubLatestReleaseSource(),
    private val architecture: CpuArchitecture = CpuArchitecture.current(),
) {
    suspend fun check(
        currentVersion: String? = System.getProperty(APP_VERSION_PROPERTY),
    ): UpdateCheckResult {
        val current = SemanticVersion.parse(currentVersion)
            ?: return UpdateCheckResult.NotCheckable(currentVersion)
        return try {
            val release = source.latestRelease()
            val latest = SemanticVersion.parse(release.tagName)
                ?: return UpdateCheckResult.Failed("The latest release has an invalid version tag.")
            if (latest > current) {
                UpdateCheckResult.Available(current, latest, release, release.dmgFor(architecture))
            } else {
                UpdateCheckResult.UpToDate(current, latest)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            UpdateCheckResult.Failed("FirstPick couldn't check for updates.")
        }
    }

    companion object {
        const val APP_VERSION_PROPERTY = "firstpick.version"
    }
}

class GitHubLatestReleaseSource(
    private val repository: String = DEFAULT_REPOSITORY,
    private val http: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build(),
) : LatestReleaseSource {
    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun latestRelease(): RemoteRelease = withContext(Dispatchers.IO) {
        val request = HttpRequest.newBuilder(latestReleaseUri(repository))
            .header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", "2022-11-28")
            .header("User-Agent", "FirstPick-update-checker")
            .timeout(Duration.ofSeconds(15))
            .GET()
            .build()
        val response = http.send(request, HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() != 200) {
            throw IOException("GitHub latest-release request returned HTTP ${response.statusCode()}")
        }
        decodeRelease(response.body())
    }

    internal fun decodeRelease(body: String): RemoteRelease {
        val dto = json.decodeFromString<GitHubReleaseDto>(body)
        if (dto.draft) throw IOException("GitHub returned a draft as the latest release")
        return RemoteRelease(
            tagName = dto.tagName,
            title = dto.name?.takeIf(String::isNotBlank) ?: dto.tagName,
            pageUrl = URI.create(dto.htmlUrl),
            assets = dto.assets.mapNotNull { asset ->
                runCatching {
                    ReleaseAsset(asset.name, URI.create(asset.downloadUrl), asset.size.coerceAtLeast(0))
                }.getOrNull()
            },
        )
    }

    companion object {
        const val DEFAULT_REPOSITORY = "francescolofranco-dev/first-pick"

        internal fun latestReleaseUri(repository: String): URI {
            require(REPOSITORY_PATTERN.matches(repository)) { "repository must be in owner/name form" }
            return URI.create("https://api.github.com/repos/$repository/releases/latest")
        }

        private val REPOSITORY_PATTERN = Regex("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+")
    }
}

@Serializable
private data class GitHubReleaseDto(
    @SerialName("tag_name") val tagName: String,
    val name: String? = null,
    @SerialName("html_url") val htmlUrl: String,
    val draft: Boolean = false,
    val assets: List<GitHubAssetDto> = emptyList(),
)

@Serializable
private data class GitHubAssetDto(
    val name: String,
    @SerialName("browser_download_url") val downloadUrl: String,
    val size: Long = 0,
)
