package com.firstpick.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.firstpick.model.DraftPhase
import com.firstpick.sim.DraftSimulator

/** Quiet entry state: explain the next action without exposing internal model coverage. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun WaitingForDraft(state: DraftUiState, onSimulate: (String) -> Unit = {}, forDeckBuilder: Boolean = false) {
    val idle = state.phase == DraftPhase.IDLE
    BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            Modifier.widthIn(max = 460.dp).fillMaxWidth()
                .heightIn(max = maxHeight).verticalScroll(rememberScrollState())
                .padding(horizontal = if (LocalWorkbenchLayout.current.small) 12.dp else 24.dp, vertical = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            WorkbenchBrand(Modifier.size(if (LocalWorkbenchLayout.current.small) 64.dp else 84.dp))
            Spacer(Modifier.height(22.dp))
            Text(if (forDeckBuilder) "AWAITING YOUR DRAFTED CARDS" else "AWAITING YOUR NEXT PACK", style = AppTypography.sectionHeader, color = AppColors.textMuted)
            Spacer(Modifier.height(12.dp))
            Text(
                if (forDeckBuilder) "Your next deck starts here"
                else if (idle) "Your next draft starts here" else "Waiting for the next pack",
                fontSize = if (LocalWorkbenchLayout.current.small) 23.sp else 28.sp,
                lineHeight = 34.sp, fontWeight = FontWeight.SemiBold,
                letterSpacing = (-.6).sp, textAlign = TextAlign.Center, color = AppColors.textPrimary,
            )
            Spacer(Modifier.height(12.dp))
            Text(
                if (forDeckBuilder && idle) "Start a draft in MTG Arena. Suggested builds will appear here as your drafted pool develops."
                else if (forDeckBuilder) "Continue drafting in MTG Arena. Suggested builds will appear here as your drafted pool develops."
                else if (idle) "Start a draft in MTG Arena. Your cards and pick suggestions will appear here automatically."
                else "Continue drafting in MTG Arena. Your choices will appear here when the next pack arrives.",
                fontSize = 12.sp, lineHeight = 20.sp, textAlign = TextAlign.Center, color = AppColors.textSecondary,
            )
            Spacer(Modifier.height(24.dp))
            PipRow(listOf('W','U','B','R','G'), 15.dp)
            if (idle) {
                Spacer(Modifier.height(26.dp))
                Column(
                    Modifier.fillMaxWidth().background(AppColors.chrome.copy(alpha = .55f), RoundedCornerShape(10.dp))
                        .border(1.dp, AppColors.borderDefault, RoundedCornerShape(10.dp)).padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text("ONE-TIME ARENA SETUP", style = AppTypography.sectionHeader, color = AppColors.textMuted)
                    Spacer(Modifier.height(10.dp))
                    Text("In Arena, open Options → Account and enable Detailed Logs (Plugin Support).",
                        fontSize = 11.sp, lineHeight = 19.sp, textAlign = TextAlign.Center, color = AppColors.textSecondary)
                }
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
