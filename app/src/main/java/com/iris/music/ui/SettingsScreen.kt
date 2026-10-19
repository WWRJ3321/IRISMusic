package com.iris.music.ui

import com.iris.music.BuildConfig
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import com.iris.music.audio.BassHaptics
import com.iris.music.playback.FloatingLyric
import com.iris.music.data.DataTransfer
import com.iris.music.data.Song
import com.iris.music.player.PlayerUiState
import com.iris.music.ui.theme.*
import kotlinx.coroutines.launch

@Composable
internal fun SettingsPanel(
    state: PlayerUiState,
    colors: IrisColors,
    viewModel: com.iris.music.player.PlayerViewModel,
    onPickBackground: () -> Unit,
    sheetDrag: SheetDragState,
    onDismiss: () -> Unit
) {
    val scrollState = rememberScrollState()
    val panelContext = LocalContext.current
    var scanRootStatus by remember { mutableStateOf("") }
    
    var expandedSection by remember { mutableStateOf<String?>(null) }

    val folderPickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val path = viewModel.resolveScanRoot(uri)
        if (path == null) {
            scanRootStatus = "无法获取该文件夹的真实路径，请从本地存储中选择"
        } else {
            val dir = java.io.File(path)
            val probe = runCatching { dir.listFiles() }.getOrNull()
            val isOtherAppSandbox = path.contains("/Android/data/") && !path.contains(panelContext.packageName)
            when {
                isOtherAppSandbox -> scanRootStatus = "其他应用的 Android/data 目录系统禁止读取。"
                probe == null -> scanRootStatus = "无法读取该文件夹，请先开启「所有文件访问」。"
                else -> {
                    viewModel.setScanRoot(path)
                    scanRootStatus = ""
                }
            }
        }
    }
    val headerColor = colors.primary.readableOn(colors.surface, 3.5f)
    
    Column(Modifier.fillMaxWidth()) {
        Column(
            Modifier
                .fillMaxWidth()
                .weight(1f, fill = false)
                .verticalScroll(scrollState)
                .padding(horizontal = 20.dp)
        ) {
            SheetDragHandle(sheetDrag, onDismiss, colors.card)
            Spacer(Modifier.height(14.dp))
            Text("设置", color = colors.text, fontSize = 20.sp, fontWeight = FontWeight.Black)
            Spacer(Modifier.height(16.dp))

            // ==================== 外观 ====================
            CollapsibleSection(
                title = "外观",
                colors = colors,
                isExpanded = expandedSection == "外观",
                onToggle = { expandedSection = if (expandedSection == "外观") null else "外观" }
            ) {
                Text("明暗模式", color = headerColor, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ModeSwatch("深色", state.mode == IrisMode.DARK, colors, Modifier.weight(1f)) { viewModel.setMode(IrisMode.DARK) }
                    ModeSwatch("浅色", state.mode == IrisMode.LIGHT, colors, Modifier.weight(1f)) { viewModel.setMode(IrisMode.LIGHT) }
                    ModeSwatch("自动", state.mode == IrisMode.AUTO, colors, Modifier.weight(1f)) { viewModel.setMode(IrisMode.AUTO) }
                }
                Spacer(Modifier.height(14.dp))

                Text("主题", color = headerColor, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(10.dp))
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(IrisTheme.MONO, IrisTheme.OCEAN, IrisTheme.GRASS, IrisTheme.CUSTOM).chunked(4).forEach { row ->
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            row.forEach { theme ->
                                ThemeSwatch(theme, state.theme == theme, colors, Modifier.weight(1f)) { viewModel.setTheme(theme) }
                            }
                            repeat(4 - row.size) { Spacer(Modifier.weight(1f)) }
                        }
                    }
                }

                AnimatedVisibility(visible = state.theme == IrisTheme.CUSTOM) {
                    Column(Modifier.padding(top = 14.dp)) {
                        CustomColorSection(state.customPrimaryArgb, state.customSecondaryArgb, colors, viewModel::setCustomColors)
                    }
                }
                Spacer(Modifier.height(14.dp))

                Text("界面材质", color = headerColor, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    IrisSurfaceStyle.entries.forEach { style ->
                        ModeSwatch(style.label, state.surfaceStyle == style, colors, Modifier.weight(1f)) { viewModel.setSurfaceStyle(style) }
                    }
                }

                AnimatedVisibility(visible = state.surfaceStyle != IrisSurfaceStyle.SOLID) {
                    Column(Modifier.padding(top = 12.dp)) {
                        SettingsSliderWithLabel(
                            label = "玻璃模糊度",
                            value = state.glassBlur,
                            valueRange = GLASS_BLUR_MIN..GLASS_BLUR_MAX,
                            valueText = "${state.glassBlur.toInt()}",
                            activeColor = colors.primary,
                            inactiveColor = colors.surface,
                            onValueChange = { viewModel.setGlassBlur(it) }
                        )
                    }
                }

                Column(Modifier.padding(top = 14.dp)) {
                    SettingsSliderWithLabel(
                        label = "背景模糊度",
                        value = state.bgBlur,
                        valueRange = BG_BLUR_MIN..BG_BLUR_MAX,
                        valueText = "${state.bgBlur.toInt()}",
                        activeColor = colors.primary,
                        inactiveColor = colors.surface,
                        onValueChange = { viewModel.setBgBlur(it) }
                    )
                }

                Spacer(Modifier.height(14.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Box(
                        Modifier.weight(1f).height(40.dp).clip(IrisShape.item).background(if (state.theme == IrisTheme.CUSTOM) colors.primary else colors.primary.copy(alpha = 0.18f)).clickable { Haptics.tap(); onPickBackground() },
                        contentAlignment = Alignment.Center
                    ) {
                        Text("选择图片", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = if (state.theme == IrisTheme.CUSTOM) colors.primary.readableTextOn() else colors.primary)
                    }
                    if (state.customBackgroundUri != null) {
                        Box(
                            Modifier.weight(1f).height(40.dp).clip(IrisShape.item).background(colors.surface).clickable { Haptics.tap(); viewModel.setCustomBackground(null) },
                            contentAlignment = Alignment.Center
                        ) {
                            Text("恢复封面", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = colors.text)
                        }
                    }
                }

                Spacer(Modifier.height(14.dp))
                SettingsSliderWithLabel(
                    label = "界面圆角大小",
                    value = state.cornerBase,
                    valueRange = 0f..44f,
                    valueText = "${state.cornerBase.toInt()} dp",
                    activeColor = colors.primary,
                    inactiveColor = colors.surface,
                    onValueChange = { viewModel.setCornerBase(it) }
                )

                Spacer(Modifier.height(14.dp))
                var pendingFontScale by remember(state.fontScale) { mutableStateOf(state.fontScale) }
                SettingsSliderWithLabel(
                    label = "全局字体缩放",
                    value = pendingFontScale,
                    valueRange = 0.85f..1.30f,
                    valueText = "${(pendingFontScale * 100).toInt()}%",
                    activeColor = colors.primary,
                    inactiveColor = colors.surface,
                    onValueChange = { pendingFontScale = it },
                    onValueChangeFinished = { viewModel.setFontScale(pendingFontScale) }
                )

                Spacer(Modifier.height(14.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ModeSwatch("系统", state.uiFont == 0, colors, Modifier.weight(1f)) { viewModel.setUiFont(0) }
                    ModeSwatch("衬线", state.uiFont == 1, colors, Modifier.weight(1f)) { viewModel.setUiFont(1) }
                }

                Spacer(Modifier.height(14.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    IrisLayout.entries.forEach { layout ->
                        LayoutSwatch(layout, state.layout == layout, colors, Modifier.weight(1f)) { viewModel.setLayout(layout) }
                    }
                }

                Spacer(Modifier.height(14.dp))
                AnimatedVisibility(visible = !state.layout.isDeck) {
                    Column {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            ModeSwatch("紧凑", state.rowSize == 0, colors, Modifier.weight(1f)) { viewModel.setRowSize(0) }
                            ModeSwatch("标准", state.rowSize == 1, colors, Modifier.weight(1f)) { viewModel.setRowSize(1) }
                            ModeSwatch("宽松", state.rowSize == 2, colors, Modifier.weight(1f)) { viewModel.setRowSize(2) }
                        }
                        Spacer(Modifier.height(14.dp))
                        SettingToggleRow("行显示封面", checked = state.showRowCover, subtitle = "在歌曲列表中显示专辑封面", colors = colors) { viewModel.setShowRowCover(!state.showRowCover) }
                        Spacer(Modifier.height(10.dp))
                        SettingToggleRow("行显示歌手", checked = state.showRowArtist, subtitle = "在歌曲列表中显示歌手名称", colors = colors) { viewModel.setShowRowArtist(!state.showRowArtist) }
                        Spacer(Modifier.height(10.dp))
                        SettingToggleRow("行显示所属歌单", checked = state.showRowPlaylist, subtitle = "在歌曲列表中显示歌曲所属的歌单", colors = colors) { viewModel.setShowRowPlaylist(!state.showRowPlaylist) }
                        Spacer(Modifier.height(10.dp))
                        SettingToggleRow("搜索框常驻", checked = state.searchPersistent, subtitle = "始终在顶部显示搜索框", colors = colors) { viewModel.setSearchPersistent(!state.searchPersistent) }
                        Spacer(Modifier.height(10.dp))
                        SettingToggleRow("行显示点赞角标", checked = state.showLikedBadge, subtitle = "收藏的歌曲旁显示小红心", colors = colors) { viewModel.setShowLikedBadge(!state.showLikedBadge) }
                    }
                }
                Spacer(Modifier.height(18.dp))
            }

            // ==================== 播放 ====================
            CollapsibleSection("播放", colors, isExpanded = expandedSection == "播放", onToggle = { expandedSection = if (expandedSection == "播放") null else "播放" }) {
                SettingToggleRow("淡入淡出", checked = state.fadeEnabled, subtitle = "播放和暂停时音量平缓过渡", colors = colors) { viewModel.setFadeEnabled(!state.fadeEnabled) }
                AnimatedVisibility(visible = state.fadeEnabled) {
                    Column(Modifier.padding(top = 8.dp)) {
                        SettingsSliderWithLabel(
                            label = "淡入淡出时长",
                            value = state.fadeMs.toFloat(),
                            valueRange = 500f..5000f,
                            valueText = String.format(java.util.Locale.US, "%.1f s", state.fadeMs / 1000f),
                            activeColor = colors.primary,
                            inactiveColor = colors.surface,
                            onValueChange = { viewModel.setFadeMs(it.toLong()) }
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                SettingToggleRow("无声略过", checked = state.silenceSkip, subtitle = "自动跳过开头或结尾的无声片段", colors = colors) { viewModel.setSilenceSkip(!state.silenceSkip) }
                SettingToggleRow("上栏静止时隐藏", checked = state.topBarAutoHide, subtitle = "无操作3秒后自动隐藏顶部栏，滑动时出现", colors = colors) { viewModel.setTopBarAutoHide(!state.topBarAutoHide) }
            }

            // ==================== 歌词 ====================
            CollapsibleSection("歌词", colors, isExpanded = expandedSection == "歌词", onToggle = { expandedSection = if (expandedSection == "歌词") null else "歌词" }) {
                SettingToggleRow("封面歌词", checked = state.coverLyric, subtitle = "在播放页的专辑封面上显示歌词", colors = colors) { viewModel.setCoverLyric(!state.coverLyric) }
                SettingToggleRow("逐字流光", checked = state.karaokeLyric, subtitle = "歌词随节奏逐字点亮", colors = colors) { viewModel.setKaraokeLyric(!state.karaokeLyric) }
                SettingToggleRow("歌词颜色", checked = state.lyricUseCustomColor, subtitle = "开启后可自定义歌词的主题颜色", colors = colors) { viewModel.setLyricUseCustomColor(!state.lyricUseCustomColor) }
                AnimatedVisibility(visible = state.lyricUseCustomColor) {
                    Column(Modifier.padding(top = 8.dp)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf(IrisTheme.MONO, IrisTheme.OCEAN, IrisTheme.GRASS, IrisTheme.CUSTOM).forEach { theme ->
                                ThemeSwatch(theme, state.lyricTheme == theme, colors, Modifier.weight(1f)) { viewModel.setLyricTheme(theme) }
                            }
                        }
                        AnimatedVisibility(visible = state.lyricTheme == IrisTheme.CUSTOM) {
                            Column(Modifier.padding(top = 10.dp)) {
                                CustomColorSection(state.lyricCustomPrimary, state.lyricCustomSecondary, colors, viewModel::setLyricCustomColors)
                            }
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ModeSwatch("靠左", state.lyricAlign == 0, colors, Modifier.weight(1f)) { viewModel.setLyricAlign(0) }
                    ModeSwatch("居中", state.lyricAlign == 1, colors, Modifier.weight(1f)) { viewModel.setLyricAlign(1) }
                    ModeSwatch("靠右", state.lyricAlign == 2, colors, Modifier.weight(1f)) { viewModel.setLyricAlign(2) }
                }
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ModeSwatch("系统字体", state.lyricFont == 0, colors, Modifier.weight(1f)) { viewModel.setLyricFont(0) }
                    ModeSwatch("衬线字体", state.lyricFont == 1, colors, Modifier.weight(1f)) { viewModel.setLyricFont(1) }
                }
                Spacer(Modifier.height(8.dp))
                SettingsSliderWithLabel(
                    label = "歌词字体大小",
                    value = state.lyricFontSize,
                    valueRange = 14f..32f,
                    valueText = "${state.lyricFontSize.toInt()}",
                    activeColor = colors.primary,
                    inactiveColor = colors.surface,
                    onValueChange = { viewModel.setLyricFontSize(it) }
                )
                Spacer(Modifier.height(8.dp))
                FloatingLyricSection(colors, headerColor)
            }

            // ==================== 动效 ====================
            CollapsibleSection("动效与反馈", colors, isExpanded = expandedSection == "动效与反馈", onToggle = { expandedSection = if (expandedSection == "动效与反馈") null else "动效与反馈" }) {
                SettingToggleRow("触感反馈", checked = state.hapticsEnabled, subtitle = "点击和操作时提供轻微震动反馈", colors = colors) { viewModel.setHapticsEnabled(!state.hapticsEnabled) }
                SettingToggleRow("物理动效", checked = state.tiltSpectrum || state.coverShake, subtitle = "开启专辑封面倾斜与频谱晃动", colors = colors) { viewModel.setPhysicsFx(!(state.tiltSpectrum || state.coverShake)) }
                SettingToggleRow("灵动动效", checked = state.jellyAnim, subtitle = "界面切换和点击时带有果冻弹跳效果", colors = colors) { viewModel.setJellyAnim(!state.jellyAnim) }
                SettingToggleRow("低音马达震动", checked = state.bassHaptics, subtitle = "随音乐低音节奏触发马达震动", colors = colors) { viewModel.setBassHaptics(!state.bassHaptics) }
                AnimatedVisibility(visible = state.bassHaptics) {
                    Column(Modifier.padding(top = 8.dp)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            ModeSwatch("轻点", state.bassHapticsIntensity == 0, colors, Modifier.weight(1f)) { viewModel.setBassHapticsIntensity(0) }
                            ModeSwatch("标准", state.bassHapticsIntensity == 1, colors, Modifier.weight(1f)) { viewModel.setBassHapticsIntensity(1) }
                            ModeSwatch("重击", state.bassHapticsIntensity == 2, colors, Modifier.weight(1f)) { viewModel.setBassHapticsIntensity(2) }
                        }
                        Spacer(Modifier.height(8.dp))
                        SettingsSliderWithLabel(
                            label = "低频截止频率",
                            value = (state.bassHapticsLowCutoff - 120) / 200f,
                            valueRange = 0f..1f,
                            valueText = "${state.bassHapticsLowCutoff} Hz",
                            activeColor = colors.primary,
                            inactiveColor = colors.surface,
                            onValueChange = { val hz = (120 + (it * 200f)).toInt().let { v -> (v / 10) * 10 }.coerceIn(120, 320); viewModel.setBassHapticsLowCutoff(hz) }
                        )
                    }
                }
            }

            // ==================== 推荐 ====================
            CollapsibleSection("推荐", colors, isExpanded = expandedSection == "推荐", onToggle = { expandedSection = if (expandedSection == "推荐") null else "推荐" }) {
                SettingsSliderWithLabel(
                    label = "推荐探索度",
                    value = state.exploration,
                    valueRange = 0f..1f,
                    valueText = "${(state.exploration * 100).toInt()}%",
                    activeColor = colors.primary,
                    inactiveColor = colors.surface,
                    onValueChange = { viewModel.setExploration(it) }
                )
                Spacer(Modifier.height(8.dp))
                SettingToggleRow("歌单页显示推荐区", checked = state.showRecommendations, subtitle = "在歌曲列表顶部显示系统推荐的歌曲", colors = colors) { viewModel.setShowRecommendations(!state.showRecommendations) }
            }

            // ==================== 数据 ====================
            CollapsibleSection("数据", colors, isExpanded = expandedSection == "数据", onToggle = { expandedSection = if (expandedSection == "数据") null else "数据" }) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Box(Modifier.weight(1f).height(40.dp).clip(IrisShape.item).background(colors.primary.copy(alpha=0.18f)).clickable { folderPickerLauncher.launch(null) }, contentAlignment = Alignment.Center) {
                        Text("选择文件夹", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = colors.primary)
                    }
                    if (state.scanRoot != null) {
                        Box(Modifier.weight(1f).height(40.dp).clip(IrisShape.item).background(colors.surface).clickable { viewModel.setScanRoot(null) }, contentAlignment = Alignment.Center) {
                            Text("恢复全部", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = colors.text)
                        }
                    }
                }
                if (scanRootStatus.isNotBlank()) {
                    Spacer(Modifier.height(8.dp))
                    Text(scanRootStatus, color = colors.subText, fontSize = 11.sp)
                }
                Spacer(Modifier.height(8.dp))
                SettingToggleRow("上栏显示排序", checked = state.showSortBar, subtitle = "在顶部栏显示歌曲排序与方向按钮", colors = colors) { viewModel.setShowSortBar(!state.showSortBar) }
                
                Spacer(Modifier.height(12.dp))
                LazyColumn(Modifier.heightIn(max = 200.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    item { FolderRow("全部音乐", "${state.folders.sumOf { it.songCount }} 首", state.selectedFolders.isEmpty() && !state.onlyLiked, colors) { viewModel.clearFolders(); viewModel.setOnlyLiked(false) } }
                    item { FolderRow("我的收藏", "${state.likedSongIds.size} 首", state.onlyLiked, colors) { viewModel.setOnlyLiked(true) } }
                    itemsIndexed(state.folders, key = { _, f -> "folder-${f.path}" }) { _, f -> FolderRow(f.name, "${f.songCount} 首", f.path in state.selectedFolders, colors) { viewModel.toggleFolder(f.path) } }
                }
            }

            // ==================== 动画 ====================
            CollapsibleSection("动画", colors, isExpanded = expandedSection == "动画", onToggle = { expandedSection = if (expandedSection == "动画") null else "动画" }) {
                SettingToggleRow("启动动画", checked = state.splashEnabled, subtitle = "打开应用时播放 Hello 手写文字开场动画", colors = colors) { viewModel.setSplashEnabled(!state.splashEnabled) }
                AnimatedVisibility(visible = state.splashEnabled) {
                    Column(Modifier.padding(top = 8.dp)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            ModeSwatch("简约", state.splashStyle == 0, colors, Modifier.weight(1f)) { viewModel.setSplashStyle(0) }
                            ModeSwatch("温柔", state.splashStyle == 1, colors, Modifier.weight(1f)) { viewModel.setSplashStyle(1) }
                            ModeSwatch("细腻", state.splashStyle == 2, colors, Modifier.weight(1f)) { viewModel.setSplashStyle(2) }
                        }
                    }
                }
                Spacer(Modifier.height(10.dp))
                // === 切换设置样式开关 ===
                SettingToggleRow("切换设置样式", checked = state.useNewSettingsStyle, subtitle = "开启极简高级的卡片式设置界面", colors = colors) { 
                    viewModel.setUseNewSettingsStyle(!state.useNewSettingsStyle) 
                }
            }

            // ==================== 我的数据 ====================
            CollapsibleSection("我的数据", colors, isExpanded = expandedSection == "我的数据", onToggle = { expandedSection = if (expandedSection == "我的数据") null else "我的数据" }) {
                DataTransferSection(colors, state.allSongs)
            }

            Spacer(Modifier.height(24.dp))
        }

        Spacer(Modifier.height(10.dp))
        Box(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp).clip(IrisShape.item).background(colors.primary).clickable { onDismiss() }.padding(vertical = 14.dp),
            contentAlignment = Alignment.Center
        ) {
            Text("完成", color = colors.primary.readableTextOn(), fontSize = 15.sp, fontWeight = FontWeight.Black)
        }
        Spacer(Modifier.height(18.dp))
    }
}

// ==================== 新增：带有标签和数值的滑条 ====================
@Composable
private fun SettingsSliderWithLabel(
    label: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    valueText: String,
    activeColor: Color,
    inactiveColor: Color,
    onValueChange: (Float) -> Unit,
    onValueChangeFinished: (() -> Unit)? = null
) {
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(label, color = inactiveColor.copy(alpha = 0.7f), fontSize = 11.sp)
            Text(valueText, color = inactiveColor.copy(alpha = 0.7f), fontSize = 11.sp)
        }
        CompactSlider(
            value = value,
            onValueChange = onValueChange,
            onValueChangeFinished = onValueChangeFinished,
            valueRange = valueRange,
            activeColor = activeColor,
            inactiveColor = inactiveColor
        )
    }
}

@Composable
private fun CollapsibleSection(
    title: String,
    colors: IrisColors,
    isExpanded: Boolean,
    onToggle: () -> Unit,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(Modifier.fillMaxWidth().padding(bottom = 6.dp)) {
        Row(
            Modifier.fillMaxWidth().clip(IrisShape.item).clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) { Haptics.tap(); onToggle() }.padding(vertical = 12.dp, horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(title, color = colors.text, fontSize = 15.sp, fontWeight = FontWeight.Black)
            val rot by animateFloatAsState(targetValue = if (isExpanded) 90f else 0f, animationSpec = tween(220, easing = EaseInOutCubic), label = "sectionChevron")
            Canvas(Modifier.size(16.dp).graphicsLayer { rotationZ = rot }) {
                val c = colors.subText
                val w = size.width
                val h = size.height
                val mid = h / 2f
                val stroke = w * 0.12f
                drawLine(c, Offset(w * 0.38f, h * 0.22f), Offset(w * 0.66f, mid), strokeWidth = stroke, cap = androidx.compose.ui.graphics.StrokeCap.Round)
                drawLine(c, Offset(w * 0.66f, mid), Offset(w * 0.38f, h * 0.78f), strokeWidth = stroke, cap = androidx.compose.ui.graphics.StrokeCap.Round)
            }
        }
        AnimatedVisibility(
            visible = isExpanded,
            enter = fadeIn(tween(200)) + expandVertically(animationSpec = spring(dampingRatio = 0.8f, stiffness = Spring.StiffnessMediumLow)),
            exit = fadeOut(tween(200)) + shrinkVertically(animationSpec = spring(dampingRatio = 0.8f, stiffness = Spring.StiffnessMediumLow))
        ) {
            Column(Modifier.padding(top = 4.dp), content = content)
        }
    }
}

@Composable
private fun DataTransferSection(colors: IrisColors, songs: List<Song>) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }

    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        busy = true; status = "正在导出…"
        scope.launch { status = if (DataTransfer.export(ctx, uri, songs)) "已导出" else "导出失败"; busy = false }
    }

    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        busy = true; status = "正在导入…"
        scope.launch {
            status = when (val r = DataTransfer.import(ctx, uri, songs)) {
                is DataTransfer.ImportResult.Success -> "导入完成：匹配 ${r.summary.playedSongs} 首"
                DataTransfer.ImportResult.BadFormat -> "导入失败：格式错误"
                DataTransfer.ImportResult.Failed -> "导入失败：文件无法读取"
            }
            busy = false
        }
    }

    Column {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(Modifier.weight(1f).height(40.dp).clip(IrisShape.item).background(if (busy) colors.primary.copy(alpha=0.18f) else colors.primary).clickable(enabled = !busy) { exportLauncher.launch(DataTransfer.SUGGESTED_FILE_NAME) }, contentAlignment = Alignment.Center) {
                Text("导出数据", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = if (busy) colors.primary else colors.primary.readableTextOn())
            }
            Box(Modifier.weight(1f).height(40.dp).clip(IrisShape.item).background(colors.primary.copy(alpha=0.18f)).clickable(enabled = !busy) { importLauncher.launch(arrayOf("application/json", "text/plain", "*/*")) }, contentAlignment = Alignment.Center) {
                Text("导入数据", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = colors.primary)
            }
        }
        if (status.isNotBlank()) {
            Spacer(Modifier.height(10.dp))
            Text(status, color = colors.subText, fontSize = 12.sp)
        }
    }
}

@Composable
private fun FloatingLyricSection(colors: IrisColors, headerColor: Color) {
    val ctx = LocalContext.current
    val prefs = ctx.getSharedPreferences("iris_prefs", Context.MODE_PRIVATE)

    var enabled by remember { mutableStateOf(prefs.getBoolean(FloatingLyric.KEY_ENABLED, false)) }
    var granted by remember { mutableStateOf(FloatingLyric.hasPermission(ctx)) }
    var font by remember { mutableFloatStateOf(prefs.getFloat(FloatingLyric.KEY_FONT, FloatingLyric.FONT_DEFAULT)) }
    var widthFrac by remember { mutableFloatStateOf(prefs.getFloat(FloatingLyric.KEY_WIDTH, FloatingLyric.WIDTH_DEFAULT)) }
    var bg by remember { mutableFloatStateOf(prefs.getFloat(FloatingLyric.KEY_BG, FloatingLyric.BG_DEFAULT)) }
    
    var useCustomColor by remember { mutableStateOf(prefs.getBoolean(FloatingLyric.KEY_USE_CUSTOM, false)) }
    var karaoke by remember { mutableStateOf(prefs.getBoolean(FloatingLyric.KEY_KARAOKE, false)) }
    var fixed by remember { mutableStateOf(prefs.getBoolean(FloatingLyric.KEY_FIXED, false)) }
    var fixMode by remember { mutableIntStateOf(prefs.getInt(FloatingLyric.KEY_FIX_MODE, 0)) }
    var floatTheme by remember { mutableStateOf(runCatching { IrisTheme.valueOf(prefs.getString(FloatingLyric.KEY_THEME, IrisTheme.MONO.name)!!) }.getOrDefault(IrisTheme.MONO)) }
    var customPrimary by remember { mutableLongStateOf(prefs.getLong(FloatingLyric.KEY_CUSTOM_PRIMARY, 0xFF00F0FFL)) }
    var customSecondary by remember { mutableLongStateOf(prefs.getLong(FloatingLyric.KEY_CUSTOM_SECONDARY, 0xFFFF2E97L)) }

    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val obs = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                granted = FloatingLyric.hasPermission(ctx)
                FloatingLyric.refresh()
            }
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }

    fun writeBool(key: String, v: Boolean) = prefs.edit().putBoolean(key, v).apply()
    fun writeFloat(key: String, v: Float) = prefs.edit().putFloat(key, v).apply()
    fun writeInt(key: String, v: Int) = prefs.edit().putInt(key, v).apply()

    SettingToggleRow("桌面悬浮歌词", checked = enabled, subtitle = "在手机桌面上显示歌词悬浮窗", colors = colors) {
        val next = !enabled
        enabled = next
        writeBool(FloatingLyric.KEY_ENABLED, next)
        if (next && !FloatingLyric.hasPermission(ctx)) FloatingLyric.requestPermission(ctx)
    }

    AnimatedVisibility(visible = enabled) {
        Column(Modifier.padding(top = 6.dp)) {
            if (!granted) {
                Spacer(Modifier.height(6.dp))
                Box(Modifier.fillMaxWidth().clip(IrisShape.item).background(colors.surface).clickable { FloatingLyric.requestPermission(ctx) }.padding(vertical = 12.dp), contentAlignment = Alignment.Center) {
                    Text("去授予悬浮窗权限", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = colors.primary)
                }
            }

            Spacer(Modifier.height(8.dp))
            SettingToggleRow("逐字流光", checked = karaoke, subtitle = "歌词随节奏逐字点亮", colors = colors) { karaoke = !karaoke; writeBool(FloatingLyric.KEY_KARAOKE, karaoke) }
            SettingToggleRow("固定位置", checked = fixed, subtitle = "将悬浮窗锁定在屏幕固定位置", colors = colors) { fixed = !fixed; writeBool(FloatingLyric.KEY_FIXED, fixed) }

            AnimatedVisibility(visible = fixed) {
                Column(Modifier.padding(top = 6.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ModeSwatch("居中", fixMode == 0, colors, Modifier.weight(1f)) { fixMode = 0; writeInt(FloatingLyric.KEY_FIX_MODE, 0) }
                        ModeSwatch("完全固定", fixMode == 1, colors, Modifier.weight(1f)) { fixMode = 1; writeInt(FloatingLyric.KEY_FIX_MODE, 1) }
                    }
                }
            }

            Spacer(Modifier.height(8.dp))
            SettingToggleRow("悬浮歌词颜色", checked = useCustomColor, subtitle = "开启后可自定义悬浮窗歌词颜色", colors = colors) { useCustomColor = !useCustomColor; writeBool(FloatingLyric.KEY_USE_CUSTOM, useCustomColor) }

            AnimatedVisibility(visible = useCustomColor) {
                Column(Modifier.padding(top = 8.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(IrisTheme.MONO, IrisTheme.OCEAN, IrisTheme.GRASS, IrisTheme.CUSTOM).forEach { theme ->
                            ThemeSwatch(theme, floatTheme == theme, colors, Modifier.weight(1f)) { floatTheme = theme; prefs.edit().putString(FloatingLyric.KEY_THEME, theme.name).apply() }
                        }
                    }
                    AnimatedVisibility(visible = floatTheme == IrisTheme.CUSTOM) {
                        Column(Modifier.padding(top = 10.dp)) {
                            CustomColorSection(customPrimary, customSecondary, colors) { p, s ->
                                customPrimary = p; customSecondary = s
                                prefs.edit().putLong(FloatingLyric.KEY_CUSTOM_PRIMARY, p).putLong(FloatingLyric.KEY_CUSTOM_SECONDARY, s).apply()
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(8.dp))
            SettingsSliderWithLabel(
                label = "悬浮歌词字号",
                value = font,
                valueRange = 12f..40f,
                valueText = "${font.toInt()}",
                activeColor = colors.primary,
                inactiveColor = colors.surface,
                onValueChange = { font = it; writeFloat(FloatingLyric.KEY_FONT, it) }
            )
            Spacer(Modifier.height(8.dp))
            SettingsSliderWithLabel(
                label = "悬浮歌词宽度",
                value = widthFrac,
                valueRange = 0.3f..1f,
                valueText = "${(widthFrac * 100).toInt()}%",
                activeColor = colors.primary,
                inactiveColor = colors.surface,
                onValueChange = { widthFrac = it; writeFloat(FloatingLyric.KEY_WIDTH, it) }
            )
            Spacer(Modifier.height(8.dp))
            SettingsSliderWithLabel(
                label = "背景透明度",
                value = bg,
                valueRange = 0f..1f,
                valueText = "${(bg * 100).toInt()}%",
                activeColor = colors.primary,
                inactiveColor = colors.surface,
                onValueChange = { bg = it; writeFloat(FloatingLyric.KEY_BG, it) }
            )
        }
    }
}

@Composable
private fun SettingToggleRow(
    label: String,
    checked: Boolean,
    subtitle: String? = null,
    colors: IrisColors,
    onToggle: () -> Unit
) {
    Column(
        Modifier.fillMaxWidth().irisSurface(GlassLevel.CARD, colors, IrisShape.item).clickable { Haptics.tap(); onToggle() }.padding(horizontal = 14.dp, vertical = 12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, color = colors.text, fontSize = 13.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            val knobAlign by animateFloatAsState(targetValue = if (checked) 1f else 0f, animationSpec = IrisMotion.knob(), label = "toggleKnob")
            Box(Modifier.size(40.dp, 24.dp).clip(RoundedCornerShape(12.dp)).background(if (checked) colors.primary else colors.subText.copy(alpha = 0.55f)).padding(2.dp), contentAlignment = androidx.compose.ui.BiasAlignment(horizontalBias = -1f + 2f * knobAlign, verticalBias = 0f)) {
                Box(Modifier.size(20.dp).clip(RoundedCornerShape(10.dp)).background(colors.surface))
            }
        }
        if (subtitle != null) {
            Text(subtitle, color = colors.subText, fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp))
        }
    }
}

@Composable
private fun ModeSwatch(label: String, selected: Boolean, colors: IrisColors, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(modifier.irisSurface(GlassLevel.CARD, colors, IrisShape.item, solid = if (selected) colors.card.copy(alpha = 0.6f) else colors.card, accent = if (selected) colors.primary else null).clickable { Haptics.tap(); onClick() }.padding(vertical = 10.dp), contentAlignment = Alignment.Center) {
        Text(label, color = if (selected) colors.primary.readableOn(colors.card, 3.2f) else colors.subText, fontSize = 12.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun LayoutSwatch(layout: IrisLayout, selected: Boolean, colors: IrisColors, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val accent = colors.primary.readableOn(colors.card, 3.2f)
    Column(modifier.irisSurface(GlassLevel.CARD, colors, IrisShape.item, solid = if (selected) colors.card.copy(alpha = 0.6f) else colors.card, accent = if (selected) colors.primary else null).clickable { Haptics.tap(); onClick() }.padding(vertical = 10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Canvas(Modifier.size(width = 26.dp, height = 34.dp)) {
            val stroke = 1.4.dp.toPx()
            val frame = accent.copy(alpha = if (selected) 0.9f else 0.42f)
            val fill = accent.copy(alpha = if (selected) 0.55f else 0.28f)
            val r = CornerRadius(4.dp.toPx(), 4.dp.toPx())
            val inner = CornerRadius(2.dp.toPx(), 2.dp.toPx())
            drawRoundRect(frame, Offset(stroke/2f, stroke/2f), Size(size.width - stroke, size.height - stroke), r, style = androidx.compose.ui.graphics.drawscope.Stroke(width = stroke))
            val pad = 4.dp.toPx(); val w = size.width - pad * 2; val h = size.height - pad * 2
            when (layout) {
                IrisLayout.LIST -> { val gap = 2.5.dp.toPx(); val rowH = (h - gap * 4f) / 5f; drawRoundRect(fill, Offset(pad, pad), Size(w, rowH * 0.72f), inner); for (row in 0 until 4) drawRoundRect(fill.copy(alpha = fill.alpha * 0.82f), Offset(pad, pad + rowH * (row + 1.05f)), Size(w, rowH * 0.72f), inner) }
                IrisLayout.CAROUSEL -> { val cw = w * 0.46f; val gap = w * 0.10f; val cy = pad + h * 0.16f; val ch = h * 0.68f; for (i in -1..1) drawRoundRect(if (i == 0) fill else fill.copy(alpha = fill.alpha * 0.5f), Offset(pad + w/2f - cw/2f + i * (cw + gap), cy), Size(cw, ch), inner) }
                IrisLayout.STACK -> { val cw = w * 0.62f; val ch = h * 0.60f; val stepX = w * 0.09f; val stepY = h * 0.05f; for (d in 2 downTo 0) drawRoundRect(fill.copy(alpha = fill.alpha * (1f - 0.28f * d)), Offset(pad + w - cw - stepX * d - w * 0.04f, pad + h/2f - ch/2f - stepY * d), Size(cw, ch), inner) }
                IrisLayout.COMPACT -> { val cols = 3; val rows = 4; val cellGap = 1.6.dp.toPx(); val cellW = (w - cellGap * (cols - 1)) / cols; val cellH = (h - cellGap * (rows - 1)) / rows; for (row in 0 until rows) for (col in 0 until cols) drawRoundRect(fill.copy(alpha = if ((row + col) % 2 == 0) fill.alpha else fill.alpha * 0.6f), Offset(pad + col * (cellW + cellGap), pad + row * (cellH + cellGap)), Size(cellW, cellH), inner) }
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(layout.label, color = if (selected) accent else colors.subText, fontSize = 10.sp, fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false)
    }
}

@Composable
private fun ThemeSwatch(theme: IrisTheme, selected: Boolean, colors: IrisColors, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val (swatchPrimary, swatchSecondary) = theme.resolvedPair(colors.isDark)
    Column(modifier.irisSurface(GlassLevel.CARD, colors, IrisShape.item, solid = if (selected) swatchPrimary.copy(alpha = 0.15f) else colors.card, accent = if (selected) swatchPrimary else null).clickable { Haptics.tap(); onClick() }.padding(vertical = 10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Row {
            val dotBorder = colors.subText.copy(alpha = 0.45f)
            Box(Modifier.size(12.dp).clip(RoundedCornerShape(6.dp)).background(swatchPrimary).border(1.dp, dotBorder, RoundedCornerShape(6.dp)))
            Spacer(Modifier.width(3.dp))
            Box(Modifier.size(12.dp).clip(RoundedCornerShape(6.dp)).background(swatchSecondary).border(1.dp, dotBorder, RoundedCornerShape(6.dp)))
        }
        Spacer(Modifier.height(6.dp))
        Text(theme.label, color = if (selected) swatchPrimary.readableOn(colors.card) else colors.text.copy(alpha = 0.8f), fontSize = 11.sp, fontWeight = FontWeight.Bold, maxLines = 1)
    }
}

private fun argbOf(hsv: FloatArray): Long = (android.graphics.Color.HSVToColor(hsv).toLong() and 0xFFFFFFFFL)
private fun hsvOf(argb: Long): FloatArray { val out = FloatArray(3); android.graphics.Color.colorToHSV(argb.toInt(), out); return out }

@Composable
private fun CustomColorSection(primaryArgb: Long, secondaryArgb: Long, colors: IrisColors, onColorsChange: (Long, Long) -> Unit) {
    var primaryHsv by remember { mutableStateOf(hsvOf(primaryArgb)) }
    var secondaryHsv by remember { mutableStateOf(hsvOf(secondaryArgb)) }
    var editSecondary by remember { mutableStateOf(false) }
    val activeHsv = if (editSecondary) secondaryHsv else primaryHsv
    val onSlider: (Int, Float) -> Unit = { index, v ->
        val updated = activeHsv.copyOf()
        updated[index] = v.coerceIn(0f, if (index == 0) 360f else 1f)
        if (editSecondary) secondaryHsv = updated else primaryHsv = updated
        onColorsChange(argbOf(primaryHsv), argbOf(secondaryHsv))
    }
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        ColorPreviewBox(argbOf(primaryHsv), "主色", !editSecondary, colors, Modifier.weight(1f)) { editSecondary = false }
        ColorPreviewBox(argbOf(secondaryHsv), "次色", editSecondary, colors, Modifier.weight(1f)) { editSecondary = true }
    }
    Spacer(Modifier.height(12.dp))
    SettingsSliderWithLabel(
        label = "色相",
        value = activeHsv[0],
        valueRange = 0f..360f,
        valueText = "${activeHsv[0].toInt()}°",
        activeColor = colors.primary,
        inactiveColor = colors.surface,
        onValueChange = { onSlider(0, it) }
    )
    Spacer(Modifier.height(8.dp))
    SettingsSliderWithLabel(
        label = "饱和度",
        value = activeHsv[1],
        valueRange = 0f..1f,
        valueText = "${(activeHsv[1] * 100).toInt()}%",
        activeColor = colors.primary,
        inactiveColor = colors.surface,
        onValueChange = { onSlider(1, it) }
    )
    Spacer(Modifier.height(8.dp))
    SettingsSliderWithLabel(
        label = "明度",
        value = activeHsv[2],
        valueRange = 0f..1f,
        valueText = "${(activeHsv[2] * 100).toInt()}%",
        activeColor = colors.primary,
        inactiveColor = colors.surface,
        onValueChange = { onSlider(2, it) }
    )
}

@Composable
private fun ColorPreviewBox(argb: Long, label: String, selected: Boolean, colors: IrisColors, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val c = Color(argb)
    Column(modifier.height(52.dp).clip(IrisShape.item).background(c).then(if (selected) Modifier.border(2.dp, colors.text, IrisShape.item) else Modifier).clickable { Haptics.tap(); onClick() }, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Text(label, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = c.readableTextOn())
    }
}
