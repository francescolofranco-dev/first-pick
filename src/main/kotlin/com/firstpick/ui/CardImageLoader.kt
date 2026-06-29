package com.firstpick.ui

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import com.firstpick.cards.SeventeenLandsClient
import com.firstpick.core.AppPaths
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.skia.Image as SkiaImage
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap

object CardImageLoader {
    private val http: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(15))
        .build()
    private val memory = ConcurrentHashMap<String, ImageBitmap>()

    suspend fun load(url: String, cacheDir: Path = AppPaths.cacheDir): ImageBitmap? =
        withContext(Dispatchers.IO) {
            if (url.isBlank()) return@withContext null
            memory[url]?.let { return@withContext it }
            val bytes = cachedBytes(url, cacheDir) ?: return@withContext null
            val bitmap = runCatching { SkiaImage.makeFromEncoded(bytes).toComposeImageBitmap() }.getOrNull()
            bitmap?.also { memory[url] = it }
        }

    suspend fun loadBufferedImage(url: String, cacheDir: Path = AppPaths.cacheDir): java.awt.image.BufferedImage? =
        withContext(Dispatchers.IO) {
            if (url.isBlank()) return@withContext null
            val bytes = cachedBytes(url, cacheDir) ?: return@withContext null
            val img = runCatching { javax.imageio.ImageIO.read(java.io.ByteArrayInputStream(bytes)) }
                .onFailure { com.firstpick.core.Log.warn("CardImageLoader", "decode failed for $url: $it") }
                .getOrNull()
            if (img == null) com.firstpick.core.Log.warn("CardImageLoader", "no image decoded for $url")
            img
        }

    private fun cachedBytes(url: String, cacheDir: Path): ByteArray? {
        val dir = cacheDir.resolve("images")
        Files.createDirectories(dir)
        val file = dir.resolve(url.hashCode().toUInt().toString(16) + ".img")
        if (Files.exists(file) && Files.size(file) > 0) return Files.readAllBytes(file)
        val downloaded = download(url) ?: return null
        runCatching { Files.write(file, downloaded) }
        return downloaded
    }

    private fun download(url: String): ByteArray? = runCatching {
        val request = HttpRequest.newBuilder(URI.create(url))
            .header("User-Agent", SeventeenLandsClient.USER_AGENT)
            .timeout(Duration.ofSeconds(20))
            .GET()
            .build()
        val resp = http.send(request, HttpResponse.BodyHandlers.ofByteArray())
        if (resp.statusCode() == 200) resp.body() else null
    }.getOrNull()
}
