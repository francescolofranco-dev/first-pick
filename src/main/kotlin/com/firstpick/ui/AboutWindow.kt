package com.firstpick.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
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
import com.firstpick.core.AppVersion
import com.firstpick.update.UpdateCheckResult
import com.firstpick.update.UpdateChecker
import kotlinx.coroutines.launch
import java.awt.Desktop
import java.awt.EventQueue
import java.net.URI

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
    val updateChecker = remember { UpdateChecker() }
    val scope = rememberCoroutineScope()
    var checking by remember { mutableStateOf(false) }
    var updateResult by remember { mutableStateOf<UpdateCheckResult?>(null) }
    DialogWindow(
        onCloseRequest = onCloseRequest,
        state = rememberDialogState(size = DpSize(400.dp, 360.dp)),
        title = "About FirstPick",
        resizable = false,
    ) {
        LaunchedEffect(window) {
            MacOverlay.configureDarkWindow(window)
        }
        AppTheme {
            Surface(
                modifier = Modifier.fillMaxSize(),
                color = AppTheme.colors.background,
            ) {
                Box(
                    modifier = Modifier.fillMaxSize().padding(18.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .appPanel(padding = 24.dp, radius = AppTheme.radii.xl),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        FirstPickAppMark(Modifier.size(76.dp))
                        Spacer(Modifier.height(14.dp))
                        Text(
                            text = "FirstPick",
                            color = AppTheme.colors.textPrimary,
                            fontSize = 22.sp,
                            fontWeight = FontWeight.SemiBold,
                            letterSpacing = (-0.4).sp,
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = aboutVersionLabel(AppVersion.value),
                            color = AppTheme.colors.textSecondary,
                            style = AppTheme.typography.monoSmall,
                        )
                        Spacer(Modifier.height(10.dp))
                        Text(
                            text = "MTG Arena draft companion",
                            color = AppTheme.colors.accent,
                            style = AppTheme.typography.body.copy(fontWeight = FontWeight.Medium),
                        )
                        Spacer(Modifier.height(18.dp))
                        UpdateControls(
                            checking = checking,
                            result = updateResult,
                            onCheck = {
                                if (!checking) {
                                    checking = true
                                    scope.launch {
                                        updateResult = updateChecker.check()
                                        checking = false
                                    }
                                }
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun UpdateControls(
    checking: Boolean,
    result: UpdateCheckResult?,
    onCheck: () -> Unit,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        AppButton(
            onClick = onCheck,
            enabled = !checking,
            variant = AppButtonVariant.Tonal,
            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
        ) {
            Text(
                if (checking) "Checking…" else "Check for updates",
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                color = AppTheme.colors.accent,
            )
        }
        val message = result?.let(::updateResultMessage)
        if (message != null) {
            Spacer(Modifier.height(8.dp))
            Text(
                message,
                color = when (result) {
                    is UpdateCheckResult.Failed -> AppTheme.colors.danger
                    is UpdateCheckResult.Available -> AppTheme.colors.accent
                    else -> AppTheme.colors.textSecondary
                },
                style = AppTheme.typography.caption,
            )
        }
        val available = result as? UpdateCheckResult.Available
        if (available != null) {
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AppButton(
                    onClick = { openExternal(available.release.pageUrl) },
                    variant = AppButtonVariant.Subtle,
                ) {
                    Text("View release", fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = AppTheme.colors.textPrimary)
                }
                available.asset?.let { asset ->
                    AppButton(
                        onClick = { openExternal(asset.downloadUrl) },
                        variant = AppButtonVariant.Primary,
                    ) {
                        Text(
                            "Get ${asset.name.substringAfterLast('-')}",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = AppTheme.colors.textOnAccent,
                        )
                    }
                }
            }
        }
    }
}

internal fun updateResultMessage(result: UpdateCheckResult): String = when (result) {
    is UpdateCheckResult.Available -> "Version ${result.latestVersion} is available."
    is UpdateCheckResult.UpToDate -> "FirstPick ${result.currentVersion} is up to date."
    is UpdateCheckResult.NotCheckable -> "Update checks are unavailable for development builds."
    is UpdateCheckResult.Failed -> result.message
}

private fun openExternal(uri: URI) {
    runCatching {
        if (Desktop.isDesktopSupported()) Desktop.getDesktop().browse(uri)
    }
}

internal fun aboutVersionLabel(version: String?): String {
    val clean = version?.trim()?.takeIf { it.isNotEmpty() && it != "unspecified" }
    return clean?.let { "Version $it" } ?: "Development build"
}

@Composable
private fun FirstPickAppMark(modifier: Modifier = Modifier) {
    AppBrandMark(
        modifier = modifier,
        size = 76.dp,
        cornerRadius = 18.dp,
    )
}
