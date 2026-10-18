package com.iris.music.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.iris.music.player.PlayerUiState
import com.iris.music.player.PlayerViewModel
import com.iris.music.ui.theme.*

@Composable
internal fun NewSettingsPanel(
    state: PlayerUiState,
    colors: IrisColors,
    viewModel: PlayerViewModel,
    onPickBackground: () -> Unit,
    sheetDrag: SheetDragState,
    onDismiss: () -> Unit
) {
    val scrollState = rememberScrollState()
    val headerColor = colors.primary.readableOn(colors.surface, 3.5f)
    val groupBg = if (colors.isDark) Color(0xFF1A1A1E) else Color(0xFFF2F2F6)

    // 统一定义所有弹窗的状态
    var showModeDialog by remember { mutableStateOf(false) }
    var showThemeDialog by remember { mutableStateOf(false) }
    var showMaterialDialog by remember { mutableStateOf(false) }
    var showBgBlurDialog by remember { mutableStateOf(false) }
    var showCornerDialog by remember { mutableStateOf(false) }
    var showLayoutDialog by remember { mutableStateOf(false) }
    var showSplashStyleDialog by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxWidth()) {
        Column(
            Modifier
                .fillMaxWidth()
                .weight(1f, fill = false)
                .verticalScroll(scrollState)
                .padding(horizontal = 16.dp)
        ) {
            SheetDragHandle(sheetDrag, onDismiss, colors.card)
            Spacer(Modifier.height(14.dp))
            Text("设置", color = colors.text, fontSize = 24.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(16.dp))

            // ==================== 外观 ====================
            Text("外观", color = headerColor, fontSize = 13.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 16.dp, bottom = 8.dp))
            Column(Modifier.clip(RoundedCornerShape(16.dp)).background(groupBg)) {
                
                NewSettingRowArrow(
                    title = "明暗模式",
                    trailingText = when (state.mode) {
                        IrisMode.DARK -> "深色模式"
                        IrisMode.LIGHT -> "亮色模式"
                        IrisMode.AUTO -> "跟随系统"
                    },
                    colors = colors
                ) { showModeDialog = true }
                
                Divider(color = colors.subText.copy(alpha = 0.1f), thickness = 1.dp, modifier = Modifier.padding(horizontal = 16.dp))

                NewSettingRowArrow(
                    title = "主题",
                    trailingText = state.theme.label,
                    colors = colors
                ) { showThemeDialog = true }

                Divider(color = colors.subText.copy(alpha = 0.1f), thickness = 1.dp, modifier = Modifier.padding(horizontal = 16.dp))

                NewSettingRowArrow(
                    title = "材质分级",
                    trailingText = when (state.surfaceStyle) {
                        IrisSurfaceStyle.SOLID -> "实色"
                        IrisSurfaceStyle.FROSTED -> "毛玻璃"
                        IrisSurfaceStyle.LIQUID -> "液态玻璃"
                    },
                    subtitle = "提供完整的液态玻璃和光感视效",
                    colors = colors
                ) { showMaterialDialog = true }

                Divider(color = colors.subText.copy(alpha = 0.1f), thickness = 1.dp, modifier = Modifier.padding(horizontal = 16.dp))

                NewSettingRowArrow(
                    title = "背景模糊",
                    trailingText = "${state.bgBlur.toInt()}dp",
                    colors = colors
                ) { showBgBlurDialog = true }

                Divider(color = colors.subText.copy(alpha = 0.1f), thickness = 1.dp, modifier = Modifier.padding(horizontal = 16.dp))

                NewSettingRowArrow(
                    title = "界面圆角",
                    trailingText = "${state.cornerBase.toInt()}dp",
                    colors = colors
                ) { showCornerDialog = true }

                Divider(color = colors.subText.copy(alpha = 0.1f), thickness = 1.dp, modifier = Modifier.padding(horizontal = 16.dp))

                NewSettingRowArrow(
                    title = "界面排布",
                    trailingText = state.layout.label,
                    colors = colors
                ) { showLayoutDialog = true }
            }
            Spacer(Modifier.height(24.dp))

            // ==================== 播放与歌词 ====================
            Text("播放与歌词", color = headerColor, fontSize = 13.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 16.dp, bottom = 8.dp))
            Column(Modifier.clip(RoundedCornerShape(16.dp)).background(groupBg)) {
                NewSettingRowSwitch(
                    title = "逐字流光",
                    checked = state.karaokeLyric,
                    colors = colors
                ) { viewModel.setKaraokeLyric(!state.karaokeLyric) }

                Divider(color = colors.subText.copy(alpha = 0.1f), thickness = 1.dp, modifier = Modifier.padding(horizontal = 16.dp))

                NewSettingRowSwitch(
                    title = "封面歌词",
                    checked = state.coverLyric,
                    colors = colors
                ) { viewModel.setCoverLyric(!state.coverLyric) }

                Divider(color = colors.subText.copy(alpha = 0.1f), thickness = 1.dp, modifier = Modifier.padding(horizontal = 16.dp))

                NewSettingRowSwitch(
                    title = "淡入淡出",
                    checked = state.fadeEnabled,
                    colors = colors
                ) { viewModel.setFadeEnabled(!state.fadeEnabled) }
            }
            Spacer(Modifier.height(24.dp))

            // ==================== 动画 ====================
            Text("动画", color = headerColor, fontSize = 13.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 16.dp, bottom = 8.dp))
            Column(Modifier.clip(RoundedCornerShape(16.dp)).background(groupBg)) {
                NewSettingRowSwitch(
                    title = "启动动画",
                    checked = state.splashEnabled,
                    colors = colors
                ) { viewModel.setSplashEnabled(!state.splashEnabled) }

                if (state.splashEnabled) {
                    Divider(color = colors.subText.copy(alpha = 0.1f), thickness = 1.dp, modifier = Modifier.padding(horizontal = 16.dp))
                    NewSettingRowArrow(
                        title = "动画风格",
                        trailingText = when (state.splashStyle) {
                            0 -> "简约"
                            1 -> "温柔"
                            else -> "细腻"
                        },
                        colors = colors
                    ) { showSplashStyleDialog = true }
                }

                Divider(color = colors.subText.copy(alpha = 0.1f), thickness = 1.dp, modifier = Modifier.padding(horizontal = 16.dp))

                NewSettingRowSwitch(
                    title = "切换回经典设置面板",
                    checked = !state.useNewSettingsStyle,
                    colors = colors
                ) { viewModel.setUseNewSettingsStyle(!state.useNewSettingsStyle) }
            }
            Spacer(Modifier.height(30.dp))
        }

        Spacer(Modifier.height(10.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .clip(IrisShape.item)
                .background(colors.primary)
                .clickable { onDismiss() }
                .padding(vertical = 14.dp),
            contentAlignment = Alignment.Center
        ) {
            Text("完成", color = colors.primary.readableTextOn(), fontSize = 15.sp, fontWeight = FontWeight.Black)
        }
        Spacer(Modifier.height(18.dp))
    }

    // ==================== 弹窗渲染 ====================
    if (showModeDialog) {
        SelectionDialog(
            title = "明暗模式",
            options = listOf("深色模式", "亮色模式", "跟随系统"),
            selectedIndex = state.mode.ordinal,
            colors = colors,
            onDismiss = { showModeDialog = false },
            onSelect = { index ->
                viewModel.setMode(IrisMode.entries[index])
                showModeDialog = false
            }
        )
    }

    if (showThemeDialog) {
        SelectionDialog(
            title = "主题",
            options = listOf("黑白", "深海蓝", "青草绿", "自定义"),
            selectedIndex = listOf(IrisTheme.MONO, IrisTheme.OCEAN, IrisTheme.GRASS, IrisTheme.CUSTOM).indexOf(state.theme),
            colors = colors,
            onDismiss = { showThemeDialog = false },
            onSelect = { index ->
                viewModel.setTheme(listOf(IrisTheme.MONO, IrisTheme.OCEAN, IrisTheme.GRASS, IrisTheme.CUSTOM)[index])
                showThemeDialog = false
            }
        )
    }

    if (showMaterialDialog) {
        SelectionDialog(
            title = "材质分级",
            options = listOf("实色", "毛玻璃", "液态玻璃"),
            selectedIndex = IrisSurfaceStyle.entries.indexOf(state.surfaceStyle),
            colors = colors,
            onDismiss = { showMaterialDialog = false },
            onSelect = { index ->
                viewModel.setSurfaceStyle(IrisSurfaceStyle.entries[index])
                showMaterialDialog = false
            }
        )
    }

    if (showBgBlurDialog) {
        SliderDialog(
            title = "背景模糊",
            value = state.bgBlur,
            valueRange = BG_BLUR_MIN..BG_BLUR_MAX,
            colors = colors,
            onDismiss = { showBgBlurDialog = false },
            onValueChange = { viewModel.setBgBlur(it) }
        )
    }

    if (showCornerDialog) {
        SliderDialog(
            title = "界面圆角",
            value = state.cornerBase,
            valueRange = 0f..44f,
            colors = colors,
            onDismiss = { showCornerDialog = false },
            onValueChange = { viewModel.setCornerBase(it) }
        )
    }

    if (showLayoutDialog) {
        SelectionDialog(
            title = "界面排布",
            options = IrisLayout.entries.map { it.label },
            selectedIndex = IrisLayout.entries.indexOf(state.layout),
            colors = colors,
            onDismiss = { showLayoutDialog = false },
            onSelect = { index ->
                viewModel.setLayout(IrisLayout.entries[index])
                showLayoutDialog = false
            }
        )
    }

    if (showSplashStyleDialog) {
        SelectionDialog(
            title = "动画风格",
            options = listOf("简约", "温柔", "细腻"),
            selectedIndex = state.splashStyle,
            colors = colors,
            onDismiss = { showSplashStyleDialog = false },
            onSelect = { index ->
                viewModel.setSplashStyle(index)
                showSplashStyleDialog = false
            }
        )
    }
}

// ==================== 封装的新版 UI 组件 ====================

@Composable
fun NewSettingRowArrow(
    title: String,
    trailingText: String,
    subtitle: String? = null,
    colors: IrisColors,
    onClick: () -> Unit
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(horizontal = 16.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, color = colors.text, fontSize = 16.sp)
            if (subtitle != null) {
                Text(subtitle, color = colors.subText, fontSize = 12.sp)
            }
        }
        Text(trailingText, color = colors.subText, fontSize = 14.sp)
        Spacer(Modifier.width(4.dp))
        // 画一个向右的小箭头 >
        Canvas(Modifier.size(12.dp)) {
            val stroke = 1.5.dp.toPx()
            drawLine(
                color = colors.subText,
                start = Offset(0f, 0f),
                end = Offset(size.width * 0.6f, size.height * 0.5f),
                strokeWidth = stroke,
                cap = androidx.compose.ui.graphics.StrokeCap.Round
            )
            drawLine(
                color = colors.subText,
                start = Offset(size.width * 0.6f, size.height * 0.5f),
                end = Offset(0f, size.height),
                strokeWidth = stroke,
                cap = androidx.compose.ui.graphics.StrokeCap.Round
            )
        }
    }
}

@Composable
fun NewSettingRowSwitch(
    title: String,
    checked: Boolean,
    colors: IrisColors,
    subtitle: String? = null,
    onToggle: () -> Unit
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable { onToggle() }
            .padding(horizontal = 16.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, color = colors.text, fontSize = 16.sp)
            if (subtitle != null) {
                Text(subtitle, color = colors.subText, fontSize = 12.sp)
            }
        }
        Switch(
            checked = checked,
            onCheckedChange = { onToggle() },
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.White,
                checkedTrackColor = colors.primary,
                uncheckedThumbColor = Color.White,
                uncheckedTrackColor = colors.subText.copy(alpha = 0.3f)
            )
        )
    }
}

// ==================== 弹窗组件 ====================

@Composable
fun SelectionDialog(
    title: String,
    options: List<String>,
    selectedIndex: Int,
    colors: IrisColors,
    onDismiss: () -> Unit,
    onSelect: (Int) -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(24.dp))
                .background(if (colors.isDark) Color(0xFF2C2C30) else Color(0xFFF9F9FB))
                .padding(8.dp)
        ) {
            Text(
                title,
                color = colors.subText,
                fontSize = 14.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp)
            )
            options.forEachIndexed { index, option ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(if (index == selectedIndex) colors.primary.copy(alpha = 0.1f) else Color.Transparent)
                        .clickable { onSelect(index) }
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        option,
                        color = if (index == selectedIndex) colors.primary else colors.text,
                        fontSize = 16.sp,
                        modifier = Modifier.weight(1f)
                    )
                    if (index == selectedIndex) {
                        Text("✓", color = colors.primary, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            Box(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .clickable { onDismiss() }
                    .padding(vertical = 14.dp),
                contentAlignment = Alignment.Center
            ) {
                Text("取消", color = colors.text, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
fun SliderDialog(
    title: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    colors: IrisColors,
    onDismiss: () -> Unit,
    onValueChange: (Float) -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(24.dp))
                .background(if (colors.isDark) Color(0xFF2C2C30) else Color(0xFFF9F9FB))
                .padding(20.dp)
        ) {
            Text(title, color = colors.text, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(20.dp))
            Slider(
                value = value,
                onValueChange = onValueChange,
                valueRange = valueRange,
                colors = SliderDefaults.colors(
                    thumbColor = Color.White,
                    activeTrackColor = colors.primary,
                    inactiveTrackColor = colors.subText.copy(alpha = 0.3f)
                )
            )
            Spacer(Modifier.height(20.dp))
            Box(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(colors.primary)
                    .clickable { onDismiss() }
                    .padding(vertical = 14.dp),
                contentAlignment = Alignment.Center
            ) {
                Text("确定", color = colors.primary.readableTextOn(), fontSize = 16.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}
