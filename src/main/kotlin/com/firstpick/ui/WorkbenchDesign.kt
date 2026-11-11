package com.firstpick.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** CSS breakpoint measurements from the approved HTML, in logical pixels/dp. */
internal data class WorkbenchLayout(val width: Dp) {
    val small = width <= 760.dp
    val rail = when { small -> 110.dp; width <= 1100.dp -> 145.dp; else -> 166.dp }
    val inspector = when { small -> 220.dp; width <= 1100.dp -> 250.dp; else -> 274.dp }
    val gutter = when { small -> 12.dp; width <= 1200.dp -> 16.dp; else -> 24.dp }
    val top = when { small -> 20.dp; width <= 1200.dp -> 24.dp; else -> 27.dp }
    val heading = if (small) 23.sp else 28.sp
}

internal val LocalWorkbenchLayout = staticCompositionLocalOf { WorkbenchLayout(1280.dp) }

@Composable
internal fun WorkbenchButton(
    onClick: () -> Unit, modifier: Modifier = Modifier, active: Boolean = false,
    outlined: Boolean = true, enabled: Boolean = true,
    contentPadding: PaddingValues = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
    content: @Composable RowScope.() -> Unit,
) {
    val source = remember { MutableInteractionSource() }
    val hovered by source.collectIsHoveredAsState()
    val shape = RoundedCornerShape(5.dp)
    val ink = if (!enabled) AppColors.textMuted else if (outlined || active) Color(0xFFD3C9E8) else AppColors.textSecondary
    Row(modifier.heightIn(min = 26.dp).clip(shape)
        .background(if (active) AppColors.accent.copy(alpha = .12f) else if (hovered) AppColors.accent.copy(alpha = .07f) else if (outlined) AppColors.accent.copy(alpha = .025f) else Color.Transparent)
        .then(if (outlined) Modifier.border(1.dp, AppColors.accent.copy(alpha = .22f), shape) else Modifier)
        .hoverable(source).clickable(source, indication = null, enabled = enabled, role = Role.Button, onClick = onClick)
        .focusable(enabled).padding(contentPadding), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
        CompositionLocalProvider(LocalContentColor provides ink) {
            ProvideTextStyle(AppTypography.caption.copy(fontSize = 11.sp, fontWeight = FontWeight.Normal)) { content() }
        }
    }
}

@Composable
internal fun WorkbenchTabs(tabs: List<SegmentedTabItem>, modifier: Modifier = Modifier, underlined: Boolean = false, compact: Boolean = false) {
    Row(modifier.height(if (underlined) 39.dp else if(compact)26.dp else 32.dp)
        .then(if (underlined) Modifier else Modifier.background(Color(0x44080B14), RoundedCornerShape(7.dp)).padding(3.dp)),
        horizontalArrangement = Arrangement.spacedBy(if (underlined) 20.dp else 5.dp), verticalAlignment = Alignment.CenterVertically) {
        tabs.forEach { tab ->
            val small = LocalWorkbenchLayout.current.small
            Box(Modifier.fillMaxHeight().then(if (underlined) Modifier.drawBehind { if (tab.selected) drawLine(AppColors.accent, androidx.compose.ui.geometry.Offset(0f,size.height-1.dp.toPx()), androidx.compose.ui.geometry.Offset(size.width,size.height-1.dp.toPx()),2.dp.toPx()) } else Modifier.clip(RoundedCornerShape(5.dp)).background(if (tab.selected) AppColors.accent.copy(alpha = .085f) else Color.Transparent))
                .semantics { selected = tab.selected }.clickable(enabled = tab.enabled, role = Role.Tab, onClick = tab.onClick).focusable(tab.enabled)
                .padding(horizontal = if (underlined) 0.dp else if(compact)9.dp else if (small) 7.dp else 18.dp), contentAlignment = Alignment.Center) {
                Text(tab.label, color = if (tab.selected) AppColors.textPrimary else AppColors.textSecondary, fontSize = if(compact)11.sp else 12.sp, fontWeight = FontWeight.Normal, maxLines = 1)
            }
        }
    }
}

@Composable internal fun WorkbenchHeading(text: String, modifier: Modifier = Modifier) {
    Text(text, style = AppTypography.paneTitle.copy(fontSize = LocalWorkbenchLayout.current.heading, lineHeight = (LocalWorkbenchLayout.current.heading.value * 1.2f).sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-.7).sp), color = Color(0xFFF0EDF8), modifier = modifier)
}

/** The same FirstPick icon used by the application bundle and Dock. */
@Composable internal fun WorkbenchBrand(modifier: Modifier = Modifier) {
    Image(painterResource("icons/app_icon.png"),contentDescription="FirstPick logo",modifier=modifier.size(28.dp))
}

@Composable internal fun WorkbenchInspector(title: String, intro: String? = null, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.width(LocalWorkbenchLayout.current.inspector).fillMaxHeight().testTag("workbench-inspector")
        .background(Brush.verticalGradient(listOf(Color(0xFF141827), AppColors.chrome), endY=600f))
        .drawBehind { drawLine(Color(0x1AB8ACE5), androidx.compose.ui.geometry.Offset.Zero, androidx.compose.ui.geometry.Offset(0f,size.height),1.dp.toPx()) }
        .verticalScroll(rememberScrollState()).padding(horizontal=if(LocalWorkbenchLayout.current.small)12.dp else 20.dp,vertical=if(LocalWorkbenchLayout.current.small)24.dp else 28.dp), verticalArrangement=Arrangement.spacedBy(0.dp)) {
        Text(title, color=AppColors.textPrimary,style=AppTypography.body.copy(fontSize=14.sp,fontWeight=FontWeight.Medium))
        if(intro!=null){Spacer(Modifier.height(8.dp));Text(intro,color=AppColors.textSecondary,style=AppTypography.caption.copy(lineHeight=19.sp))}
        Spacer(Modifier.height(20.dp));content()
    }
}
@Composable internal fun InspectorSection(title: String, modifier: Modifier=Modifier, content: @Composable ColumnScope.()->Unit) {
    Column(modifier.fillMaxWidth()) { Hairline();Spacer(Modifier.height(22.dp));Text(title,style=AppTypography.caption,color=AppColors.textPrimary);Spacer(Modifier.height(12.dp));content();Spacer(Modifier.height(22.dp)) }
}
@Composable internal fun InspectorLine(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical=9.dp),verticalAlignment=Alignment.CenterVertically) {Text(label,style=AppTypography.caption,color=AppColors.textSecondary,modifier=Modifier.weight(1f));Text(value,style=AppTypography.micro.copy(fontWeight=FontWeight.Normal),color=AppColors.textPrimary,maxLines=2,overflow=TextOverflow.Ellipsis)}
}
