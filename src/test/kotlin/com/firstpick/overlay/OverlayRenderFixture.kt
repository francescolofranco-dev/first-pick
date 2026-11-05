package com.firstpick.overlay

import androidx.compose.runtime.Composable
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import org.jetbrains.skia.Image

/** Geometry/color snapshots should inspect fully presented advice, after its entrance. */
@OptIn(ExperimentalComposeUiApi::class)
internal fun renderSettledOverlay(width: Int, height: Int, content: @Composable () -> Unit): Image {
    val scene = ImageComposeScene(width, height, content = content)
    var time = 1_000_000_000L
    try {
        repeat(24) { scene.render(time).close(); time += 16_666_667L }
        return scene.render(time)
    } finally { scene.close() }
}
