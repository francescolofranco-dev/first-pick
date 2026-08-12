package com.firstpick.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogWindow
import androidx.compose.ui.window.rememberDialogState
import java.awt.Desktop
import java.awt.EventQueue

private const val APP_VERSION_PROPERTY = "firstpick.version"

@Composable
internal fun FirstPickAboutMenu(onOpenAbout: () -> Unit) {
    val currentOnOpenAbout by rememberUpdatedState(onOpenAbout)

    DisposableEffect(Unit) {
        val desktop = runCatching {
            Desktop.getDesktop().takeIf { Desktop.isDesktopSupported() }
        }.getOrNull()

        if (desktop != null && desktop.isSupported(Desktop.Action.APP_ABOUT)) {
            desktop.setAboutHandler {
                if (EventQueue.isDispatchThread()) currentOnOpenAbout()
                else EventQueue.invokeLater { currentOnOpenAbout() }
            }
            onDispose { runCatching { desktop.setAboutHandler(null) } }
        } else {
            onDispose {}
        }
    }
}

@Composable
internal fun FirstPickAboutWindow(onCloseRequest: () -> Unit) {
    DialogWindow(
        onCloseRequest = onCloseRequest,
        state = rememberDialogState(size = DpSize(420.dp, 310.dp)),
        title = "About FirstPick",
        resizable = false,
    ) {
        MaterialTheme(colorScheme = DraftColorScheme) {
            Surface(
                modifier = Modifier.fillMaxSize(),
                color = MaterialTheme.colorScheme.background,
            ) {
                Column(
                    modifier = Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    FirstPickAppMark(Modifier.size(104.dp))
                    Spacer(Modifier.height(18.dp))
                    Text(
                        text = "FirstPick",
                        color = MaterialTheme.colorScheme.onSurface,
                        fontSize = 28.sp,
                        fontWeight = FontWeight.Bold,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = aboutVersionLabel(System.getProperty(APP_VERSION_PROPERTY)),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 14.sp,
                    )
                    Spacer(Modifier.height(14.dp))
                    Text(
                        text = "MTG Arena draft companion",
                        color = MaterialTheme.colorScheme.primary,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                    )
                }
            }
        }
    }
}

internal fun aboutVersionLabel(version: String?): String {
    val clean = version?.trim()?.takeIf { it.isNotEmpty() && it != "unspecified" }
    return clean?.let { "Version $it" } ?: "Development build"
}

@Composable
private fun FirstPickAppMark(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(23.dp))
            .background(Color(0xFF19211F)),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.fillMaxSize()) {
            rotate(-11f, pivot = Offset(size.width * 0.39f, size.height * 0.54f)) {
                drawRoundRect(
                    color = Color(0xFF355F57),
                    topLeft = Offset(size.width * 0.21f, size.height * 0.29f),
                    size = Size(size.width * 0.35f, size.height * 0.55f),
                    cornerRadius = CornerRadius(size.width * 0.055f),
                )
            }
            rotate(11f, pivot = Offset(size.width * 0.61f, size.height * 0.54f)) {
                drawRoundRect(
                    color = Color(0xFF4B8C7F),
                    topLeft = Offset(size.width * 0.44f, size.height * 0.29f),
                    size = Size(size.width * 0.35f, size.height * 0.55f),
                    cornerRadius = CornerRadius(size.width * 0.055f),
                )
            }
            drawRoundRect(
                color = Color(0xFF7FD1C4),
                topLeft = Offset(size.width * 0.35f, size.height * 0.19f),
                size = Size(size.width * 0.30f, size.height * 0.58f),
                cornerRadius = CornerRadius(size.width * 0.055f),
            )
        }
        Text(
            text = "1",
            color = Color(0xFF19211F),
            fontSize = 38.sp,
            fontWeight = FontWeight.Black,
        )
    }
}
