package com.firstpick.ui

import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import javax.imageio.ImageIO
import kotlinx.coroutines.runBlocking
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
import kotlin.test.*

class CardImageLoaderTest {
    private fun png(): ByteArray {
        val image = BufferedImage(8, 8, BufferedImage.TYPE_INT_ARGB)
        for (y in 0 until 8) for (x in 0 until 8) image.setRGB(x, y, if (x < 4) 0xffff0000.toInt() else 0xff0000ff.toInt())
        return ByteArrayOutputStream().also { ImageIO.write(image, "png", it) }.toByteArray()
    }

    private fun webp(): ByteArray = Image.makeFromEncoded(png()).use { image ->
        requireNotNull(image.encodeToData(EncodedImageFormat.WEBP, 100)).use { it.bytes }
    }

    @Test
    fun webpReferencesDecodeEvenWithoutAnImageIoReader() {
        val bytes = webp()
        assertNull(ImageIO.read(ByteArrayInputStream(bytes)), "regression requires a format ImageIO cannot decode")
        val decoded = assertNotNull(CardImageLoader.decodeBufferedImage(bytes))
        assertEquals(8, decoded.width)
        assertEquals(8, decoded.height)
        assertTrue(decoded.getRGB(0, 4) ushr 16 and 255 > 150)
        assertTrue(decoded.getRGB(7, 4) and 255 > 150)
    }

    @Test
    fun pngPixelsStillUseTheExistingLosslessPath() {
        val decoded = assertNotNull(CardImageLoader.decodeBufferedImage(png()))
        assertEquals(0xffff0000.toInt(), decoded.getRGB(0, 0))
        assertEquals(0xff0000ff.toInt(), decoded.getRGB(7, 0))
        assertNull(CardImageLoader.decodeBufferedImage(byteArrayOf(1, 2, 3)))
    }

    @Test
    fun cachedWebpReferencesWorkWithoutAnyNetworkRequest() = runBlocking {
        val dir = Files.createTempDirectory("firstpick-webp-test")
        try {
            val url = "https://images.example.invalid/card.webp"
            val images = Files.createDirectories(dir.resolve("images"))
            Files.write(images.resolve(url.hashCode().toUInt().toString(16) + ".img"), webp())
            val loaded = assertNotNull(CardImageLoader.loadBufferedImage(url, dir))
            assertEquals(8, loaded.width)
        } finally { dir.toFile().deleteRecursively() }
    }
}
