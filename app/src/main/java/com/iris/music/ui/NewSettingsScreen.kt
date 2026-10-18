package com.iris.music.ui

import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.EaseInCubic
import androidx.compose.animation.core.EaseOutCubic
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.iris.music.data.DataTransfer
import com.iris.music.playback.FloatingLyric
import com.iris.music.player.PlayerUiState
import com.iris.music.player.PlayerViewModel
import com.iris.music.ui.theme.*
import kotlinx.coroutines.launch

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
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    var scanRootStatus by remember { mutableStateOf("") }
    var transferStatus by remember { mutableStateOf("") }
    var transferBusy by remember { mutableStateOf(false) }

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
            val isOtherAppSandbox = path.contains("/Android/data/") && !path.contains(ctx.packageName)
            when {
                isOtherAppSandbox -> scanRootStatus = "其他应用的 Android/data 目录系统禁止读取。"
                probe == null -> scanRootStatus = "无法读取该文件夹，请先开启「所有文件访问」。"
                else -> { viewModel.setScanRoot(path); scanRootStatus = "" }
            }
        }
    }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        transferBusy = true; transferStatus = "正在导出…"
        scope.launch {
            transferStatus = if (DataTransfer.export(ctx, uri, state.allSongs)) "已导出" else "导出失败"
            transferBusy = false
        }
    }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        transferBusy = true; transferStatus = "正在导入…"
        scope.launch {
            transferStatus = when (val r = DataTransfer.import(ctx, uri, state.allSongs)) {
                is DataTransfer.ImportResult.Success -> "导入完成：匹配 ${r.summary.playedSongs} 首"
                DataTransfer.ImportResult.BadFormat -> "导入失败：格式错误"
                DataTransfer.ImportResult.Failed -> "导入失败：文件无法读取"
            }
            transferBusy = false
        }
    }

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
            Text(
                "设置",
                color = colors.text,
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 12.dp)
            )
            Spacer(Modifier.height(18.dp))

            SettingsGroup("外观", colors) {
                InlineOptionRow(
                    title = "明暗模式",
                    options = listOf("深色", "浅色", "自动"),
                    selectedIndex = IrisMode.entries.indexOf(state.mode),
                    colors = colors
                ) { viewModel.setMode(IrisMode.entries[it]) }

                InlineOptionRow(
                    title = "主题",
                    options = listOf(IrisTheme.MONO, IrisTheme.OCEAN, IrisTheme.GRASS, IrisTheme.CUSTOM).map { it.label },
                    selectedIndex = listOf(IrisTheme.MONO, IrisTheme.OCEAN, IrisTheme.GRASS, IrisTheme.CUSTOM).indexOf(state.theme),
                    optionDots = listOf(IrisTheme.MONO, IrisTheme.OCEAN, IrisTheme.GRASS, IrisTheme.CUSTOM)
                        .map { it.resolvedPair(colors.isDark) },
                    colors = colors
                ) { index ->
                    viewModel.setTheme(listOf(IrisTheme.MONO, IrisTheme.OCEAN, IrisTheme.GRASS, IrisTheme.CUSTOM)[index])
                }

                if (state.theme == IrisTheme.CUSTOM) {
                    InlineColorRow(
                        title = "自定义主题色",
                        primaryArgb = state.customPrimaryArgb,
                        secondaryArgb = state.customSecondaryArgb,
                        colors = colors
                    ) { p, s -> viewModel.setCustomColors(p, s) }
                }

                InlineOptionRow(
                    title = "材质分级",
                    options = IrisSurfaceStyle.entries.map { it.label },
                    selectedIndex = IrisSurfaceStyle.entries.indexOf(state.surfaceStyle),
                    colors = colors
                ) { viewModel.setSurfaceStyle(IrisSurfaceStyle.entries[it]) }

                if (state.surfaceStyle != IrisSurfaceStyle.SOLID) {
                    InlineSliderRow(
                        title = "玻璃模糊",
                        value = state.glassBlur,
                        valueRange = GLASS_BLUR_MIN..GLASS_BLUR_MAX,
                        valueFormat = { "${it.toInt()}" },
                        colors = colors
                    ) { viewModel.setGlassBlur(it) }
                }

                InlineSliderRow(
                    title = "背景模糊",
                    value = state.bgBlur,
                    valueRange = BG_BLUR_MIN..BG_BLUR_MAX,
                    valueFormat = { "${it.toInt()} dp" },
                    colors = colors
                ) { viewModel.setBgBlur(it) }

                InlineSliderRow(
                    title = "界面圆角",
                    value = state.cornerBase,
                    valueRange = 0f..44f,
                    valueFormat = { "${it.toInt()} dp" },
                    colors = colors
                ) { viewModel.setCornerBase(it) }

                InlineSliderRow(
                    title = "字体大小",
                    value = state.fontScale,
                    valueRange = 0.85f..1.30f,
                    valueFormat = { "${(it * 100).toInt()}%" },
                    colors = colors
                ) { viewModel.setFontScale(it) }

                InlineOptionRow(
                    title = "界面字体",
                    options = listOf("系统", "衬线"),
                    selectedIndex = if (state.uiFont == 0) 0 else 1,
                    colors = colors
                ) { viewModel.setUiFont(it) }

                InlineOptionRow(
                    title = "界面排布",
                    options = IrisLayout.entries.map { it.label },
                    selectedIndex = IrisLayout.entries.indexOf(state.layout),
                    colors = colors
                ) { viewModel.setLayout(IrisLayout.entries[it]) }

                SettingActionRow(
                    title = "背景图片",
                    trailingText = if (state.customBackgroundUri != null) "自定义" else "跟随封面",
                    showDivider = state.customBackgroundUri != null,
                    colors = colors
                ) { onPickBackground() }

                if (state.customBackgroundUri != null) {
                    SettingActionRow(
                        title = "恢复封面背景",
                        trailingText = "重置",
                        showDivider = false,
                        colors = colors
                    ) { viewModel.setCustomBackground(null) }
                }
            }

            if (!state.layout.isDeck) {
                SettingsGroup("列表", colors) {
                    InlineOptionRow(
                        title = "行尺寸",
                        options = listOf("紧凑", "标准", "宽松"),
                        selectedIndex = state.rowSize,
                        colors = colors
                    ) { viewModel.setRowSize(it) }

                    SettingSwitchRow("行显示封面", checked = state.showRowCover, colors = colors) {
                        viewModel.setShowRowCover(!state.showRowCover)
                    }
                    SettingSwitchRow("行显示歌手", checked = state.showRowArtist, colors = colors) {
                        viewModel.setShowRowArtist(!state.showRowArtist)
                    }
                    SettingSwitchRow("行显示所属歌单", checked = state.showRowPlaylist, colors = colors) {
                        viewModel.setShowRowPlaylist(!state.showRowPlaylist)
                    }
                    SettingSwitchRow("搜索框常驻", checked = state.searchPersistent, colors = colors) {
                        viewModel.setSearchPersistent(!state.searchPersistent)
                    }
                    SettingSwitchRow(
                        "行显示点赞角标",
                        checked = state.showLikedBadge,
                        showDivider = false,
                        colors = colors
                    ) { viewModel.setShowLikedBadge(!state.showLikedBadge) }
                }
            }

            SettingsGroup("播放与歌词", colors) {
                SettingSwitchRow("封面歌词", checked = state.coverLyric, colors = colors) {
                    viewModel.setCoverLyric(!state.coverLyric)
                }
                SettingSwitchRow("逐字流光", checked = state.karaokeLyric, colors = colors) {
                    viewModel.setKaraokeLyric(!state.karaokeLyric)
                }
                SettingSwitchRow("歌词颜色", checked = state.lyricUseCustomColor, colors = colors) {
                    viewModel.setLyricUseCustomColor(!state.lyricUseCustomColor)
                }

                if (state.lyricUseCustomColor) {
                    InlineOptionRow(
                        title = "歌词主题",
                        options = listOf(IrisTheme.MONO, IrisTheme.OCEAN, IrisTheme.GRASS, IrisTheme.CUSTOM).map { it.label },
                        selectedIndex = listOf(IrisTheme.MONO, IrisTheme.OCEAN, IrisTheme.GRASS, IrisTheme.CUSTOM).indexOf(state.lyricTheme),
                        optionDots = listOf(IrisTheme.MONO, IrisTheme.OCEAN, IrisTheme.GRASS, IrisTheme.CUSTOM)
                            .map { it.resolvedPair(colors.isDark) },
                        colors = colors
                    ) { index ->
                        viewModel.setLyricTheme(listOf(IrisTheme.MONO, IrisTheme.OCEAN, IrisTheme.GRASS, IrisTheme.CUSTOM)[index])
                    }
                    if (state.lyricTheme == IrisTheme.CUSTOM) {
                        InlineColorRow(
                            title = "自定义歌词色",
                            primaryArgb = state.lyricCustomPrimary,
                            secondaryArgb = state.lyricCustomSecondary,
                            colors = colors
                        ) { p, s -> viewModel.setLyricCustomColors(p, s) }
                    }
                }

                InlineOptionRow(
                    title = "歌词对齐",
                    options = listOf("靠左", "居中", "靠右"),
                    selectedIndex = state.lyricAlign,
                    colors = colors
                ) { viewModel.setLyricAlign(it) }

                InlineOptionRow(
                    title = "歌词字体",
                    options = listOf("系统", "衬线"),
                    selectedIndex = if (state.lyricFont == 0) 0 else 1,
                    colors = colors
                ) { viewModel.setLyricFont(it) }

                InlineSliderRow(
                    title = "歌词字号",
                    value = state.lyricFontSize,
                    valueRange = 14f..32f,
                    valueFormat = { "${it.toInt()}" },
                    colors = colors
                ) { viewModel.setLyricFontSize(it) }

                SettingSwitchRow("淡入淡出", checked = state.fadeEnabled, colors = colors) {
                    viewModel.setFadeEnabled(!state.fadeEnabled)
                }
                if (state.fadeEnabled) {
                    InlineSliderRow(
                        title = "淡入淡出时长",
                        value = state.fadeMs.toFloat(),
                        valueRange = 500f..5000f,
                        valueFormat = { String.format(java.util.Locale.US, "%.1f s", it / 1000f) },
                        colors = colors
                    ) { viewModel.setFadeMs(it.toLong()) }
                }

                SettingSwitchRow("无声略过", checked = state.silenceSkip, colors = colors) {
                    viewModel.setSilenceSkip(!state.silenceSkip)
                }
                SettingSwitchRow(
                    "上栏静止时隐藏",
                    checked = state.topBarAutoHide,
                    showDivider = false,
                    colors = colors
                ) { viewModel.setTopBarAutoHide(!state.topBarAutoHide) }
            }

            SettingsGroup("悬浮歌词", colors) {
                FloatingLyricGroup(colors)
            }

            SettingsGroup("动效与反馈", colors) {
                SettingSwitchRow("触感反馈", checked = state.hapticsEnabled, colors = colors) {
                    viewModel.setHapticsEnabled(!state.hapticsEnabled)
                }
                SettingSwitchRow(
                    "物理动效",
                    checked = state.tiltSpectrum || state.coverShake,
                    colors = colors
                ) { viewModel.setPhysicsFx(!(state.tiltSpectrum || state.coverShake)) }
                SettingSwitchRow("灵动动效", checked = state.jellyAnim, colors = colors) {
                    viewModel.setJellyAnim(!state.jellyAnim)
                }
                SettingSwitchRow(
                    "低音马达震动",
                    checked = state.bassHaptics,
                    showDivider = state.bassHaptics,
                    colors = colors
                ) { viewModel.setBassHaptics(!state.bassHaptics) }

                if (state.bassHaptics) {
                    InlineOptionRow(
                        title = "震动强度",
                        options = listOf("轻点", "标准", "重击"),
                        selectedIndex = state.bassHapticsIntensity,
                        colors = colors
                    ) { viewModel.setBassHapticsIntensity(it) }

                    InlineSliderRow(
                        title = "低频截止",
                        value = state.bassHapticsLowCutoff.toFloat(),
                        valueRange = 120f..320f,
                        valueFormat = { "${it.toInt()} Hz" },
                        colors = colors
                    ) { viewModel.setBassHapticsLowCutoff(it.toInt()) }
                }
            }

            SettingsGroup("推荐", colors) {
                InlineSliderRow(
                    title = "探索度",
                    value = state.exploration,
                    valueRange = 0f..1f,
                    valueFormat = { "${(it * 100).toInt()}%" },
                    colors = colors
                ) { viewModel.setExploration(it) }

                SettingSwitchRow(
                    "歌单页显示推荐区",
                    checked = state.showRecommendations,
                    showDivider = false,
                    colors = colors
                ) { viewModel.setShowRecommendations(!state.showRecommendations) }
            }

            SettingsGroup("数据", colors) {
                SettingActionRow(
                    title = "扫描文件夹",
                    trailingText = if (state.scanRoot != null) "已指定" else "全部音乐",
                    showDivider = true,
                    colors = colors
                ) { folderPickerLauncher.launch(null) }

                if (state.scanRoot != null) {
                    SettingActionRow(
                        title = "恢复全部文件夹",
                        trailingText = "重置",
                        colors = colors
                    ) { viewModel.setScanRoot(null) }
                }

                SettingSwitchRow(
                    "上栏显示排序",
                    checked = state.showSortBar,
                    showDivider = false,
                    colors = colors
                ) { viewModel.setShowSortBar(!state.showSortBar) }

                SettingDivider(colors)

                LazyColumn(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(max = 220.dp)
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    item {
                        FolderRow(
                            "全部音乐",
                            "${state.folders.sumOf { it.songCount }} 首",
                            state.selectedFolders.isEmpty() && !state.onlyLiked,
                            colors
                        ) { viewModel.clearFolders(); viewModel.setOnlyLiked(false) }
                    }
                    item {
                        FolderRow(
                            "我的收藏",
                            "${state.likedSongIds.size} 首",
                            state.onlyLiked,
                            colors
                        ) { viewModel.setOnlyLiked(true) }
                    }
                    itemsIndexed(state.folders, key = { _, f -> "folder-${f.path}" }) { _, f ->
                        FolderRow(
                            f.name,
                            "${f.songCount} 首",
                            f.path in state.selectedFolders,
                            colors
                        ) { viewModel.toggleFolder(f.path) }
                    }
                }
                SettingDivider(colors)

                SettingActionRow(
                    title = "导出数据",
                    trailingText = if (transferBusy) "请稍候" else null,
                    enabled = !transferBusy,
                    colors = colors
                ) { exportLauncher.launch(DataTransfer.SUGGESTED_FILE_NAME) }

                SettingActionRow(
                    title = "导入数据",
                    enabled = !transferBusy,
                    showDivider = false,
                    colors = colors
                ) { importLauncher.launch(arrayOf("application/json", "text/plain", "*/*")) }
            }

            if (transferStatus.isNotBlank() || scanRootStatus.isNotBlank()) {
                Column(Modifier.padding(horizontal = 28.dp)) {
                    Spacer(Modifier.height(2.dp))
                    if (scanRootStatus.isNotBlank()) {
                        Text(scanRootStatus, color = colors.subText, fontSize = 12.sp)
                    }
                    if (transferStatus.isNotBlank()) {
                        Text(transferStatus, color = colors.subText, fontSize = 12.sp)
                    }
                }
                Spacer(Modifier.height(12.dp))
            }

            SettingsGroup("动画", colors) {
                SettingSwitchRow("启动动画", checked = state.splashEnabled, colors = colors) {
                    viewModel.setSplashEnabled(!state.splashEnabled)
                }
                if (state.splashEnabled) {
                    InlineOptionRow(
                        title = "动画风格",
                        options = listOf("简约", "温柔", "细腻"),
                        selectedIndex = state.splashStyle,
                        colors = colors
                    ) { viewModel.setSplashStyle(it) }
                }
            }

            SettingsGroup("设置样式", colors) {
                SettingSwitchRow(
                    "切换回经典设置面板",
                    subtitle = "关闭后使用经典列表样式",
                    checked = !state.useNewSettingsStyle,
                    showDivider = false,
                    colors = colors
                ) { viewModel.setUseNewSettingsStyle(!state.useNewSettingsStyle) }
            }

            Spacer(Modifier.height(8.dp))
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
            Text(
                "完成",
                color = colors.primary.readableTextOn(),
                fontSize = 15.sp,
                fontWeight = FontWeight.Black
            )
        }
        Spacer(Modifier.height(18.dp))
    }
}

@Composable
private fun SettingsGroup(
    title: String,
    colors: IrisColors,
    content: @Composable ColumnScope.() -> Unit
) {
    val groupBg = if (colors.isDark) Color.White.copy(alpha = 0.06f) else Color.White.copy(alpha = 0.72f)
    Column(Modifier.fillMaxWidth().padding(bottom = 22.dp)) {
        Text(
            title,
            color = colors.subText,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(start = 16.dp, bottom = 8.dp)
        )
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(20.dp))
                .background(groupBg)
                .padding(vertical = 2.dp),
            content = content
        )
    }
}

@Composable
private fun SettingDivider(colors: IrisColors) {
    Box(
        Modifier
            .fillMaxWidth()
            .padding(start = 16.dp)
            .height(0.5.dp)
            .background(colors.subText.copy(alpha = 0.12f))
    )
}

@Composable
private fun SettingSwitchRow(
    title: String,
    subtitle: String? = null,
    checked: Boolean,
    showDivider: Boolean = true,
    colors: IrisColors,
    onToggle: () -> Unit
) {
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable(
                    indication = null,
                    interactionSource = remember { MutableInteractionSource() }
                ) { Haptics.tap(); onToggle() }
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(title, color = colors.text, fontSize = 16.sp)
                if (subtitle != null) {
                    Text(
                        subtitle,
                        color = colors.subText,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                }
            }
            LiquidSwitch(checked = checked, colors = colors, onCheckedChange = { onToggle() })
        }
        if (showDivider) SettingDivider(colors)
    }
}

@Composable
private fun SettingActionRow(
    title: String,
    trailingText: String? = null,
    enabled: Boolean = true,
    showDivider: Boolean = true,
    colors: IrisColors,
    onClick: () -> Unit
) {
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable(
                    enabled = enabled,
                    indication = null,
                    interactionSource = remember { MutableInteractionSource() }
                ) { Haptics.tap(); onClick() }
                .padding(horizontal = 16.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                title,
                color = if (enabled) colors.text else colors.subText,
                fontSize = 16.sp,
                modifier = Modifier.weight(1f)
            )
            if (trailingText != null) {
                Text(trailingText, color = colors.subText, fontSize = 14.sp)
            }
        }
        if (showDivider) SettingDivider(colors)
    }
}

@Composable
private fun InlineOptionRow(
    title: String,
    options: List<String>,
    selectedIndex: Int,
    colors: IrisColors,
    optionDots: List<Pair<Color, Color>>? = null,
    onSelect: (Int) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    Box(
        Modifier
            .fillMaxWidth()
            .height(54.dp)
            .padding(horizontal = 16.dp)
    ) {
        AnimatedContent(
            targetState = expanded,
            transitionSpec = {
                if (targetState) {
                    (fadeIn(tween(240, delayMillis = 40)) +
                        slideInHorizontally(tween(360, delayMillis = 40, easing = EaseOutCubic)) { -it / 2 }) togetherWith
                        (fadeOut(tween(150)) +
                            slideOutHorizontally(tween(200, easing = EaseInCubic)) { -it / 3 })
                } else {
                    (fadeIn(tween(260, delayMillis = 120)) +
                        slideInHorizontally(tween(360, delayMillis = 120, easing = EaseOutCubic)) { -it / 2 }) togetherWith
                        (fadeOut(tween(140)) +
                            slideOutHorizontally(tween(200, easing = EaseInCubic)) { -it / 3 })
                }
            },
            modifier = Modifier.fillMaxWidth(),
            label = "inlineOption"
        ) { isExpanded ->
            if (isExpanded) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    options.forEachIndexed { i, opt ->
                        val sel = i == selectedIndex
                        Row(
                            Modifier
                                .weight(1f)
                                .height(36.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(
                                    if (sel) colors.primary.copy(alpha = 0.16f)
                                    else colors.subText.copy(alpha = 0.08f)
                                )
                                .clickable(
                                    indication = null,
                                    interactionSource = remember { MutableInteractionSource() }
                                ) {
                                    Haptics.tap()
                                    onSelect(i)
                                    expanded = false
                                }
                                .padding(horizontal = 4.dp),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            if (optionDots != null) {
                                val (p, s) = optionDots[i]
                                Box(Modifier.size(9.dp).clip(CircleShape).background(p))
                                Spacer(Modifier.width(2.dp))
                                Box(Modifier.size(9.dp).clip(CircleShape).background(s))
                                Spacer(Modifier.width(5.dp))
                            }
                            Text(
                                opt,
                                color = if (sel) colors.primary else colors.text,
                                fontSize = 12.sp,
                                fontWeight = if (sel) FontWeight.Bold else FontWeight.Medium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            } else {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable(
                            indication = null,
                            interactionSource = remember { MutableInteractionSource() }
                        ) {
                            Haptics.tap()
                            expanded = true
                        },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(title, color = colors.text, fontSize = 16.sp, modifier = Modifier.weight(1f))
                    Text(
                        options.getOrElse(selectedIndex) { "" },
                        color = colors.subText,
                        fontSize = 14.sp
                    )
                }
            }
        }
    }
    SettingDivider(colors)
}

@Composable
private fun InlineSliderRow(
    title: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    valueFormat: (Float) -> String,
    colors: IrisColors,
    onValueChange: (Float) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    Column(
        Modifier
            .fillMaxWidth()
            .animateContentSize(tween(320, easing = FastOutSlowInEasing))
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable(
                    indication = null,
                    interactionSource = remember { MutableInteractionSource() }
                ) { Haptics.tap(); expanded = !expanded }
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(title, color = colors.text, fontSize = 16.sp, modifier = Modifier.weight(1f))
            Text(valueFormat(value), color = colors.subText, fontSize = 14.sp)
        }
        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically(tween(280, easing = EaseOutCubic)) + fadeIn(tween(200)),
            exit = shrinkVertically(tween(240, easing = EaseInCubic)) + fadeOut(tween(160))
        ) {
            Slider(
                value = value,
                onValueChange = onValueChange,
                valueRange = valueRange,
                colors = SliderDefaults.colors(
                    thumbColor = Color.White,
                    activeTrackColor = colors.primary,
                    inactiveTrackColor = colors.subText.copy(alpha = 0.22f),
                    activeTickColor = Color.Transparent,
                    inactiveTickColor = Color.Transparent
                ),
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp)
            )
        }
        SettingDivider(colors)
    }
}

@Composable
private fun InlineColorRow(
    title: String,
    primaryArgb: Long,
    secondaryArgb: Long,
    colors: IrisColors,
    onColorsChange: (Long, Long) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    var editSecondary by remember { mutableStateOf(false) }
    var primaryHsv by remember { mutableStateOf(hsvOf(primaryArgb)) }
    var secondaryHsv by remember { mutableStateOf(hsvOf(secondaryArgb)) }
    val activeHsv = if (editSecondary) secondaryHsv else primaryHsv

    Column(
        Modifier
            .fillMaxWidth()
            .animateContentSize(tween(320, easing = FastOutSlowInEasing))
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable(
                    indication = null,
                    interactionSource = remember { MutableInteractionSource() }
                ) { Haptics.tap(); expanded = !expanded }
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(title, color = colors.text, fontSize = 16.sp, modifier = Modifier.weight(1f))
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Box(Modifier.size(12.dp).clip(CircleShape).background(Color(primaryArgb)))
                Box(Modifier.size(12.dp).clip(CircleShape).background(Color(secondaryArgb)))
            }
        }
        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically(tween(280, easing = EaseOutCubic)) + fadeIn(tween(200)),
            exit = shrinkVertically(tween(240, easing = EaseInCubic)) + fadeOut(tween(160))
        ) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    ColorChip(
                        Color(argbOf(primaryHsv)),
                        "主色",
                        !editSecondary,
                        colors,
                        Modifier.weight(1f)
                    ) { editSecondary = false }
                    ColorChip(
                        Color(argbOf(secondaryHsv)),
                        "次色",
                        editSecondary,
                        colors,
                        Modifier.weight(1f)
                    ) { editSecondary = true }
                }
                Spacer(Modifier.height(12.dp))

                GradientSlider(
                    value = activeHsv[0],
                    range = 0f..360f,
                    brush = Brush.horizontalGradient(
                        listOf(
                            Color.Red, Color.Yellow, Color.Green,
                            Color.Cyan, Color.Blue, Color.Magenta, Color.Red
                        )
                    ),
                    colors = colors
                ) { v ->
                    val arr = activeHsv.copyOf(); arr[0] = v
                    if (editSecondary) secondaryHsv = arr else primaryHsv = arr
                    onColorsChange(argbOf(primaryHsv), argbOf(secondaryHsv))
                }
                Spacer(Modifier.height(8.dp))

                GradientSlider(
                    value = activeHsv[1],
                    range = 0f..1f,
                    brush = Brush.horizontalGradient(
                        listOf(
                            Color(android.graphics.Color.HSVToColor(floatArrayOf(activeHsv[0], 0f, activeHsv[2]))),
                            Color(android.graphics.Color.HSVToColor(floatArrayOf(activeHsv[0], 1f, activeHsv[2])))
                        )
                    ),
                    colors = colors
                ) { v ->
                    val arr = activeHsv.copyOf(); arr[1] = v
                    if (editSecondary) secondaryHsv = arr else primaryHsv = arr
                    onColorsChange(argbOf(primaryHsv), argbOf(secondaryHsv))
                }
                Spacer(Modifier.height(8.dp))

                GradientSlider(
                    value = activeHsv[2],
                    range = 0f..1f,
                    brush = Brush.horizontalGradient(
                        listOf(
                            Color(android.graphics.Color.HSVToColor(floatArrayOf(activeHsv[0], activeHsv[1], 0f))),
                            Color(android.graphics.Color.HSVToColor(floatArrayOf(activeHsv[0], activeHsv[1], 1f)))
                        )
                    ),
                    colors = colors
                ) { v ->
                    val arr = activeHsv.copyOf(); arr[2] = v
                    if (editSecondary) secondaryHsv = arr else primaryHsv = arr
                    onColorsChange(argbOf(primaryHsv), argbOf(secondaryHsv))
                }
                Spacer(Modifier.height(6.dp))
            }
        }
        SettingDivider(colors)
    }
}

@Composable
private fun ColorChip(
    color: Color,
    label: String,
    selected: Boolean,
    colors: IrisColors,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Column(
        modifier
            .height(48.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(color)
            .then(
                if (selected) Modifier.border(2.dp, colors.text, RoundedCornerShape(12.dp))
                else Modifier.border(0.5.dp, Color.Black.copy(alpha = 0.08f), RoundedCornerShape(12.dp))
            )
            .clickable(
                indication = null,
                interactionSource = remember { MutableInteractionSource() }
            ) { Haptics.tap(); onClick() },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(label, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = color.readableTextOn())
    }
}

@Composable
private fun GradientSlider(
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    brush: Brush,
    colors: IrisColors,
    onChange: (Float) -> Unit
) {
    val span = (range.endInclusive - range.start).takeIf { it > 0f } ?: 1f
    val frac = ((value - range.start) / span).coerceIn(0f, 1f)
    BoxWithConstraints(
        Modifier
            .fillMaxWidth()
            .height(30.dp)
            .pointerInput(range) {
                detectTapGestures { offset ->
                    val f = (offset.x / size.width.toFloat()).coerceIn(0f, 1f)
                    onChange(range.start + f * span)
                }
            }
            .pointerInput(range) {
                detectHorizontalDragGestures { change, _ ->
                    val f = (change.position.x / size.width.toFloat()).coerceIn(0f, 1f)
                    onChange(range.start + f * span)
                }
            },
        contentAlignment = Alignment.CenterStart
    ) {
        val thumbD = 20.dp
        val usable = maxWidth - thumbD
        Box(
            Modifier
                .fillMaxWidth()
                .height(6.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(brush)
        )
        Box(
            Modifier
                .offset(x = usable * frac)
                .size(thumbD)
                .shadow(2.dp, CircleShape)
                .background(Color.White, CircleShape)
                .border(0.5.dp, Color.Black.copy(alpha = 0.08f), CircleShape)
        )
    }
}

private fun argbOf(hsv: FloatArray): Long =
    (android.graphics.Color.HSVToColor(hsv).toLong() and 0xFFFFFFFFL)

private fun hsvOf(argb: Long): FloatArray {
    val out = FloatArray(3)
    android.graphics.Color.colorToHSV(argb.toInt(), out)
    return out
}

@Composable
private fun LiquidSwitch(
    checked: Boolean,
    colors: IrisColors,
    onCheckedChange: (Boolean) -> Unit
) {
    val progress by animateFloatAsState(
        targetValue = if (checked) 1f else 0f,
        animationSpec = spring(dampingRatio = 0.62f, stiffness = 520f),
        label = "liquidSwitch"
    )
    val trackW = 51.dp
    val trackH = 31.dp
    val thumbD = 27.dp
    val travel = trackW - thumbD - 4.dp
    val thumbOffset by animateDpAsState(
        targetValue = 2.dp + travel * progress,
        animationSpec = spring(dampingRatio = 0.62f, stiffness = 520f),
        label = "thumbOffset"
    )
    val trackColor = lerp(colors.subText.copy(alpha = 0.3f), colors.primary, progress)
    Box(
        Modifier
            .size(trackW, trackH)
            .clip(CircleShape)
            .background(trackColor)
            .clickable(
                indication = null,
                interactionSource = remember { MutableInteractionSource() }
            ) { Haptics.tap(); onCheckedChange(!checked) }
    ) {
        Box(
            Modifier
                .matchParentSize()
                .background(
                    Brush.verticalGradient(
                        0f to Color.White.copy(alpha = 0.22f),
                        0.5f to Color.White.copy(alpha = 0.04f),
                        1f to Color.Black.copy(alpha = 0.05f)
                    )
                )
        )
        Box(
            Modifier
                .matchParentSize()
                .border(0.6.dp, Color.White.copy(alpha = 0.25f), CircleShape)
        )
        Box(
            Modifier
                .offset(x = thumbOffset, y = 2.dp)
                .size(thumbD)
                .shadow(3.dp, CircleShape, clip = false)
                .background(Color.White, CircleShape)
                .border(0.5.dp, Color.Black.copy(alpha = 0.06f), CircleShape)
        )
    }
}

@Composable
private fun FloatingLyricGroup(colors: IrisColors) {
    val ctx = LocalContext.current
    val prefs = ctx.getSharedPreferences("iris_prefs", Context.MODE_PRIVATE)

    var enabled by remember { mutableStateOf(prefs.getBoolean(FloatingLyric.KEY_ENABLED, false)) }
    var granted by remember { mutableStateOf(FloatingLyric.hasPermission(ctx)) }
    var karaoke by remember { mutableStateOf(prefs.getBoolean(FloatingLyric.KEY_KARAOKE, false)) }
    var fixed by remember { mutableStateOf(prefs.getBoolean(FloatingLyric.KEY_FIXED, false)) }
    var fixMode by remember { mutableIntStateOf(prefs.getInt(FloatingLyric.KEY_FIX_MODE, 0)) }
    var useCustomColor by remember { mutableStateOf(prefs.getBoolean(FloatingLyric.KEY_USE_CUSTOM, false)) }
    var floatTheme by remember {
        mutableStateOf(
            runCatching {
                IrisTheme.valueOf(prefs.getString(FloatingLyric.KEY_THEME, IrisTheme.MONO.name)!!)
            }.getOrDefault(IrisTheme.MONO)
        )
    }
    var customPrimary by remember {
        mutableLongStateOf(prefs.getLong(FloatingLyric.KEY_CUSTOM_PRIMARY, 0xFF00F0FFL))
    }
    var customSecondary by remember {
        mutableLongStateOf(prefs.getLong(FloatingLyric.KEY_CUSTOM_SECONDARY, 0xFFFF2E97L))
    }
    var font by remember { mutableFloatStateOf(prefs.getFloat(FloatingLyric.KEY_FONT, FloatingLyric.FONT_DEFAULT)) }
    var widthFrac by remember { mutableFloatStateOf(prefs.getFloat(FloatingLyric.KEY_WIDTH, FloatingLyric.WIDTH_DEFAULT)) }
    var bg by remember { mutableFloatStateOf(prefs.getFloat(FloatingLyric.KEY_BG, FloatingLyric.BG_DEFAULT)) }

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

    SettingSwitchRow(
        "桌面悬浮歌词",
        checked = enabled,
        showDivider = true,
        colors = colors
    ) {
        enabled = !enabled
        writeBool(FloatingLyric.KEY_ENABLED, enabled)
        if (enabled && !FloatingLyric.hasPermission(ctx)) FloatingLyric.requestPermission(ctx)
    }

    if (enabled) {
        if (!granted) {
            SettingActionRow(
                title = "授予悬浮窗权限",
                trailingText = "去授权",
                colors = colors
            ) { FloatingLyric.requestPermission(ctx) }
        }

        SettingSwitchRow("逐字流光", checked = karaoke, colors = colors) {
            karaoke = !karaoke; writeBool(FloatingLyric.KEY_KARAOKE, karaoke)
        }
        SettingSwitchRow("固定位置", checked = fixed, colors = colors) {
            fixed = !fixed; writeBool(FloatingLyric.KEY_FIXED, fixed)
        }

        if (fixed) {
            InlineOptionRow(
                title = "固定模式",
                options = listOf("居中", "完全固定"),
                selectedIndex = fixMode,
                colors = colors
            ) { fixMode = it; writeInt(FloatingLyric.KEY_FIX_MODE, it) }
        }

        SettingSwitchRow("悬浮歌词颜色", checked = useCustomColor, colors = colors) {
            useCustomColor = !useCustomColor
            writeBool(FloatingLyric.KEY_USE_CUSTOM, useCustomColor)
        }

        if (useCustomColor) {
            val themeOptions = listOf(IrisTheme.MONO, IrisTheme.OCEAN, IrisTheme.GRASS, IrisTheme.CUSTOM)
            InlineOptionRow(
                title = "悬浮歌词主题",
                options = themeOptions.map { it.label },
                selectedIndex = themeOptions.indexOf(floatTheme),
                optionDots = themeOptions.map { it.resolvedPair(colors.isDark) },
                colors = colors
            ) { index ->
                floatTheme = themeOptions[index]
                prefs.edit().putString(FloatingLyric.KEY_THEME, floatTheme.name).apply()
            }

            if (floatTheme == IrisTheme.CUSTOM) {
                InlineColorRow(
                    title = "自定义悬浮歌词色",
                    primaryArgb = customPrimary,
                    secondaryArgb = customSecondary,
                    colors = colors
                ) { p, s ->
                    customPrimary = p; customSecondary = s
                    prefs.edit()
                        .putLong(FloatingLyric.KEY_CUSTOM_PRIMARY, p)
                        .putLong(FloatingLyric.KEY_CUSTOM_SECONDARY, s)
                        .apply()
                }
            }
        }

        InlineSliderRow(
            title = "字号",
            value = font,
            valueRange = 12f..40f,
            valueFormat = { "${it.toInt()}" },
            colors = colors
        ) { font = it; writeFloat(FloatingLyric.KEY_FONT, it) }

        InlineSliderRow(
            title = "宽度",
            value = widthFrac,
            valueRange = 0.3f..1f,
            valueFormat = { "${(it * 100).toInt()}%" },
            colors = colors
        ) { widthFrac = it; writeFloat(FloatingLyric.KEY_WIDTH, it) }

        InlineSliderRow(
            title = "背景透明度",
            value = bg,
            valueRange = 0f..1f,
            valueFormat = { "${(it * 100).toInt()}%" },
            colors = colors
        ) { bg = it; writeFloat(FloatingLyric.KEY_BG, it) }
    }
}
