package com.firstpick.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.firstpick.model.DraftPhase
import com.firstpick.sim.DraftSimulator

/** Quiet entry state: explain the next action without exposing internal model coverage. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun WaitingForDraft(state: DraftUiState, onSimulate: (String) -> Unit = {}) {
    val idle = state.phase == DraftPhase.IDLE
    BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            Modifier.widthIn(max = 460.dp).fillMaxWidth()
                .heightIn(max = maxHeight).verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Canvas(Modifier.size(64.dp)) {
                val gold = AppColors.warning
                drawCircle(gold.copy(alpha = .08f), radius = size.minDimension * .46f, style = Stroke(1.dp.toPx()))
                val c = center
                val r = size.minDimension * .30f
                val mark = Path().apply {
                    moveTo(c.x, c.y-r); lineTo(c.x+r*.22f,c.y-r*.22f)
                    lineTo(c.x+r,c.y); lineTo(c.x+r*.22f,c.y+r*.22f)
                    lineTo(c.x,c.y+r); lineTo(c.x-r*.22f,c.y+r*.22f)
                    lineTo(c.x-r,c.y); lineTo(c.x-r*.22f,c.y-r*.22f); close()
                }
                drawPath(mark, gold.copy(alpha = .8f), style = Stroke(1.dp.toPx()))
                drawCircle(gold, radius = 2.dp.toPx(), center = Offset(c.x,c.y))
            }
            Spacer(Modifier.height(22.dp))
            Text("AWAITING YOUR NEXT PACK", style = AppTypography.sectionHeader, color = AppColors.textMuted)
            Spacer(Modifier.height(12.dp))
            Text(
                if (idle) "Your next draft starts here" else "Waiting for the next pack",
                fontSize = if (LocalWorkbenchLayout.current.small) 25.sp else 28.sp,
                lineHeight = 34.sp, fontWeight = FontWeight.SemiBold,
                letterSpacing = (-.6).sp, textAlign = TextAlign.Center, color = AppColors.textPrimary,
            )
            Spacer(Modifier.height(12.dp))
            Text(
                if (idle) "Start a draft in MTG Arena. Your cards and pick suggestions will appear here automatically."
                else "Continue drafting in MTG Arena. Your choices will appear here when the next pack arrives.",
                fontSize = 12.sp, lineHeight = 20.sp, textAlign = TextAlign.Center, color = AppColors.textSecondary,
            )
            Spacer(Modifier.height(24.dp))
            PipRow(listOf('W','U','B','R','G'), 15.dp)
            if (idle) {
                Spacer(Modifier.height(30.dp)); Hairline(); Spacer(Modifier.height(20.dp))
                Text("ONE-TIME ARENA SETUP", style = AppTypography.sectionHeader, color = AppColors.textMuted)
                Spacer(Modifier.height(10.dp))
                Text("In Arena, open Options → Account and enable\nDetailed Logs (Plugin Support).",
                    fontSize = 11.sp, lineHeight = 19.sp, textAlign = TextAlign.Center, color = AppColors.textSecondary)
            }
            if (DevFlags.demoEnabled && idle && !state.simulating) {
                Spacer(Modifier.height(24.dp))
                Text("Try a demo draft", fontSize = 11.sp, color = AppColors.textMuted)
                Spacer(Modifier.height(10.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    DraftSimulator.SETS.forEach { set -> WorkbenchButton(onClick = { onSimulate(set) }) { Text(set) } }
                }
            }
        }
    }
}
