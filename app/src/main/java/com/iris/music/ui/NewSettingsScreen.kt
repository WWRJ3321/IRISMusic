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
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
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

    // === 手风琴展开状态 ===
    var expandedKey by remember { mutableStateOf<String?>(null) }
    // === 沉浸式背景模糊状态 ===
    var isScrubbingBlur by remember { mutableStateOf(false) }

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

    val panelBg = if (colors.isDark) Color(0xFF000000) else Color(0xFFF2F2F7)
    val isScrolled = scrollState.value > 50

    BoxWithConstraints(Modifier.fillMaxWidth().background(panelBg)) {
        val maxW = maxWidth
        
        Column(Modifier.fillMaxWidth()) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .weight(1f, fill = false)
                    .verticalScroll(scrollState)
                    .padding(horizontal = 16.dp)
            ) {
                SheetDragHandle(sheetDrag, onDismiss, colors.card)
                Spacer(Modifier.height(10.dp))
                // === 缩减顶部留白 ===
                Box(Modifier.fillMaxWidth().height(64.dp))
                Spacer(Modifier.height(4.dp))

                // ==================== 外观 ====================
                SettingsGroup("外观", state.surfaceStyle, colors) {
                    InlineOptionRow(
                        title = "明暗模式",
                        options = listOf("深色", "浅色", "自动"),
                        selectedIndex = IrisMode.entries.indexOf(state.mode),
                        surfaceStyle = state.surfaceStyle,
                        colors = colors,
                        isExpanded = expandedKey == "明暗模式",
                        onToggleExpand = { expandedKey = if (expandedKey == "明暗模式") null else "明暗模式" }
                    ) { viewModel.setMode(IrisMode.entries[it]) }

                    SettingActionRow(
                        title = "背景图片",
                        trailingText = if (state.customBackgroundUri != null) "重置" else "自定义",
                        showDivider = true,
                        surfaceStyle = state.surfaceStyle,
                        colors = colors
                    ) {
                        if (state.customBackgroundUri != null) {
                            viewModel.setCustomBackground(null)
                        } else {
                            onPickBackground()
                        }
                    }

                    InlineOptionRow(
                        title = "按钮颜色",
                        options = listOf(IrisTheme.MONO, IrisTheme.OCEAN, IrisTheme.GRASS, IrisTheme.CUSTOM).map { it.label },
                        selectedIndex = listOf(IrisTheme.MONO, IrisTheme.OCEAN, IrisTheme.GRASS, IrisTheme.CUSTOM).indexOf(state.theme),
                        surfaceStyle = state.surfaceStyle,
                        colors = colors,
                        isExpanded = expandedKey == "按钮颜色",
                        onToggleExpand = { expandedKey = if (expandedKey == "按钮颜色") null else "按钮颜色" }
                    ) { index ->
                        viewModel.setTheme(listOf(IrisTheme.MONO, IrisTheme.OCEAN, IrisTheme.GRASS, IrisTheme.CUSTOM)[index])
                    }

                    AnimatedVisibility(
                        visible = state.theme == IrisTheme.CUSTOM,
                        enter = expandVertically(tween(300, easing = FastOutSlowInEasing)) + fadeIn(tween(200)),
                        exit = shrinkVertically(tween(300, easing = FastOutSlowInEasing)) + fadeOut(tween(200))
                    ) {
                        InlineColorRow(
                            title = "自定义按钮颜色",
                            primaryArgb = state.customPrimaryArgb,
                            secondaryArgb = state.customSecondaryArgb,
                            surfaceStyle = state.surfaceStyle,
                            colors = colors,
                            isExpanded = expandedKey == "自定义按钮颜色",
                            onToggleExpand = { expandedKey = if (expandedKey == "自定义按钮颜色") null else "自定义按钮颜色" }
                        ) { p, s -> viewModel.setCustomColors(p, s) }
                    }

                    val allowedStyles = listOf(IrisSurfaceStyle.SOLID, IrisSurfaceStyle.LIQUID)
                    InlineOptionRow(
                        title = "材质分级",
                        options = allowedStyles.map { it.label },
                        selectedIndex = allowedStyles.indexOf(state.surfaceStyle).coerceAtLeast(0),
                        surfaceStyle = state.surfaceStyle,
                        colors = colors,
                        isExpanded = expandedKey == "材质分级",
                        onToggleExpand = { expandedKey = if (expandedKey == "材质分级") null else "材质分级" }
                    ) { viewModel.setSurfaceStyle(allowedStyles[it]) }

                    // === 移除玻璃模糊 ===

                    InlineSliderRow(
                        title = "背景模糊",
                        value = state.bgBlur,
                        valueRange = BG_BLUR_MIN..BG_BLUR_MAX,
                        valueFormat = { "${it.toInt()}" },
                        surfaceStyle = state.surfaceStyle,
                        colors = colors,
                        isExpanded = expandedKey == "背景模糊",
                        onToggleExpand = { expandedKey = if (expandedKey == "背景模糊") null else "背景模糊" },
                        onScrubStart = { isScrubbingBlur = true },
                        onScrubEnd = { isScrubbingBlur = false }
                    ) { viewModel.setBgBlur(it) }

                    InlineSliderRow(
                        title = "界面圆角",
                        value = state.cornerBase,
                        valueRange = 0f..44f,
                        valueFormat = { "${it.toInt()}" },
                        surfaceStyle = state.surfaceStyle,
                        colors = colors,
                        isExpanded = expandedKey == "界面圆角",
                        onToggleExpand = { expandedKey = if (expandedKey == "界面圆角") null else "界面圆角" }
                    ) { viewModel.setCornerBase(it) }

                    var pendingFontScale by remember(state.fontScale) { mutableStateOf(state.fontScale) }
                    InlineSliderRow(
                        title = "字体大小",
                        value = pendingFontScale,
                        valueRange = 0.85f..1.30f,
                        valueFormat = { "${(it * 100).toInt()}%" },
                        surfaceStyle = state.surfaceStyle,
                        colors = colors,
                        isExpanded = expandedKey == "字体大小",
                        onToggleExpand = { expandedKey = if (expandedKey == "字体大小") null else "字体大小" },
                        onValueChangeFinished = { viewModel.setFontScale(pendingFontScale) }
                    ) { pendingFontScale = it }

                    InlineOptionRow(
                        title = "界面字体",
                        options = listOf("系统", "衬线"),
                        selectedIndex = if (state.uiFont == 0) 0 else 1,
                        surfaceStyle = state.surfaceStyle,
                        colors = colors,
                        isExpanded = expandedKey == "界面字体",
                        onToggleExpand = { expandedKey = if (expandedKey == "界面字体") null else "界面字体" }
                    ) { viewModel.setUiFont(it) }

                    InlineOptionRow(
                        title = "界面排布",
                        options = IrisLayout.entries.map { it.label },
                        selectedIndex = IrisLayout.entries.indexOf(state.layout),
                        surfaceStyle = state.surfaceStyle,
                        showDivider = false,
                        colors = colors,
                        isExpanded = expandedKey == "界面排布",
                        onToggleExpand = { expandedKey = if (expandedKey == "界面排布") null else "界面排布" }
                    ) { viewModel.setLayout(IrisLayout.entries[it]) }
                }

                // ==================== 列表 ====================
                if (!state.layout.isDeck) {
                    SettingsGroup("列表", state.surfaceStyle, colors) {
                        SettingSwitchRow("搜索框常驻", checked = state.searchPersistent, surfaceStyle = state.surfaceStyle, colors = colors) {
                            viewModel.setSearchPersistent(!state.searchPersistent)
                        }
                        SettingSwitchRow("行显示封面", checked = state.showRowCover, surfaceStyle = state.surfaceStyle, colors = colors) {
                            viewModel.setShowRowCover(!state.showRowCover)
                        }
                        SettingSwitchRow("行显示歌手", checked = state.showRowArtist, surfaceStyle = state.surfaceStyle, colors = colors) {
                            viewModel.setShowRowArtist(!state.showRowArtist)
                        }
                        SettingSwitchRow("行显示所属歌单", checked = state.showRowPlaylist, surfaceStyle = state.surfaceStyle, colors = colors) {
                            viewModel.setShowRowPlaylist(!state.showRowPlaylist)
                        }
                        SettingSwitchRow(
                            "行显示点赞角标",
                            checked = state.showLikedBadge,
                            surfaceStyle = state.surfaceStyle,
                            colors = colors
                        ) { viewModel.setShowLikedBadge(!state.showLikedBadge) }

                        InlineOptionRow(
                            title = "卡片距离",
                            options = listOf("紧凑", "标准", "宽松"),
                            selectedIndex = state.rowSize,
                            surfaceStyle = state.surfaceStyle,
                            showDivider = false,
                            colors = colors,
                            isExpanded = expandedKey == "卡片距离",
                            onToggleExpand = { expandedKey = if (expandedKey == "卡片距离") null else "卡片距离" }
                        ) { viewModel.setRowSize(it) }
                    }
                }

                // ==================== 播放 ====================
                SettingsGroup("播放", state.surfaceStyle, colors) {
                    SettingSwitchRow("上栏静止时隐藏", checked = state.topBarAutoHide, surfaceStyle = state.surfaceStyle, colors = colors) {
                        viewModel.setTopBarAutoHide(!state.topBarAutoHide)
                    }
                    SettingSwitchRow("无声略过", checked = state.silenceSkip, surfaceStyle = state.surfaceStyle, colors = colors) {
                        viewModel.setSilenceSkip(!state.silenceSkip)
                    }
                    SettingSwitchRow("淡入淡出", checked = state.fadeEnabled, surfaceStyle = state.surfaceStyle, showDivider = !state.fadeEnabled, colors = colors) {
                        viewModel.setFadeEnabled(!state.fadeEnabled)
                    }
                    AnimatedVisibility(
                        visible = state.fadeEnabled,
                        enter = expandVertically(tween(300, easing = FastOutSlowInEasing)) + fadeIn(tween(200)),
                        exit = shrinkVertically(tween(300, easing = FastOutSlowInEasing)) + fadeOut(tween(200))
                    ) {
                        InlineSliderRow(
                            title = "淡入淡出时长",
                            value = state.fadeMs.toFloat(),
                            valueRange = 500f..5000f,
                            valueFormat = { String.format(java.util.Locale.US, "%.1f s", it / 1000f) },
                            surfaceStyle = state.surfaceStyle,
                            showDivider = false,
                            colors = colors,
                            isExpanded = expandedKey == "淡入淡出时长",
                            onToggleExpand = { expandedKey = if (expandedKey == "淡入淡出时长") null else "淡入淡出时长" }
                        ) { viewModel.setFadeMs(it.toLong()) }
                    }
                }

                // ==================== 歌词 ====================
                SettingsGroup("歌词", state.surfaceStyle, colors) {
                    SettingSwitchRow("封面歌词", checked = state.coverLyric, surfaceStyle = state.surfaceStyle, colors = colors) {
                        viewModel.setCoverLyric(!state.coverLyric)
                    }
                    SettingSwitchRow("逐字流光", checked = state.karaokeLyric, surfaceStyle = state.surfaceStyle, colors = colors) {
                        viewModel.setKaraokeLyric(!state.karaokeLyric)
                    }
                    
                    val themeOptions = listOf(IrisTheme.MONO, IrisTheme.OCEAN, IrisTheme.GRASS, IrisTheme.CUSTOM)
                    InlineSwitchOptionRow(
                        title = "歌词颜色",
                        checked = state.lyricUseCustomColor,
                        options = themeOptions.map { it.label },
                        selectedIndex = themeOptions.indexOf(state.lyricTheme),
                        surfaceStyle = state.surfaceStyle,
                        showDivider = !(state.lyricUseCustomColor && state.lyricTheme == IrisTheme.CUSTOM),
                        colors = colors,
                        isExpanded = expandedKey == "歌词颜色",
                        onToggleExpand = { expandedKey = if (expandedKey == "歌词颜色") null else "歌词颜色" },
                        onToggle = { viewModel.setLyricUseCustomColor(it) },
                        onSelect = { index -> viewModel.setLyricTheme(themeOptions[index]) }
                    )

                    AnimatedVisibility(
                        visible = state.lyricUseCustomColor && state.lyricTheme == IrisTheme.CUSTOM,
                        enter = expandVertically(tween(300, easing = FastOutSlowInEasing)) + fadeIn(tween(200)),
                        exit = shrinkVertically(tween(300, easing = FastOutSlowInEasing)) + fadeOut(tween(200))
                    ) {
                        InlineColorRow(
                            title = "自定义歌词色",
                            primaryArgb = state.lyricCustomPrimary,
                            secondaryArgb = state.lyricCustomSecondary,
                            surfaceStyle = state.surfaceStyle,
                            colors = colors,
                            isExpanded = expandedKey == "自定义歌词色",
                            onToggleExpand = { expandedKey = if (expandedKey == "自定义歌词色") null else "自定义歌词色" }
                        ) { p, s -> viewModel.setLyricCustomColors(p, s) }
                    }

                    InlineOptionRow(
                        title = "歌词对齐",
                        options = listOf("靠左", "居中", "靠右"),
                        selectedIndex = state.lyricAlign,
                        surfaceStyle = state.surfaceStyle,
                        colors = colors,
                        isExpanded = expandedKey == "歌词对齐",
                        onToggleExpand = { expandedKey = if (expandedKey == "歌词对齐") null else "歌词对齐" }
                    ) { viewModel.setLyricAlign(it) }

                    InlineOptionRow(
                        title = "歌词字体",
                        options = listOf("系统", "衬线"),
                        selectedIndex = if (state.lyricFont == 0) 0 else 1,
                        surfaceStyle = state.surfaceStyle,
                        colors = colors,
                        isExpanded = expandedKey == "歌词字体",
                        onToggleExpand = { expandedKey = if (expandedKey == "歌词字体") null else "歌词字体" }
                    ) { viewModel.setLyricFont(it) }

                    InlineSliderRow(
                        title = "歌词字号",
                        value = state.lyricFontSize,
                        valueRange = 14f..32f,
                        valueFormat = { "${it.toInt()}" },
                        surfaceStyle = state.surfaceStyle,
                        showDivider = false,
                        colors = colors,
                        isExpanded = expandedKey == "歌词字号",
                        onToggleExpand = { expandedKey = if (expandedKey == "歌词字号") null else "歌词字号" }
                    ) { viewModel.setLyricFontSize(it) }
                }

                // ==================== 悬浮歌词 ====================
                SettingsGroup("悬浮歌词", state.surfaceStyle, colors) {
                    FloatingLyricGroup(colors, state.surfaceStyle, expandedKey, { expandedKey = it })
                }

                // ==================== 动效与反馈 ====================
                SettingsGroup("动效与反馈", state.surfaceStyle, colors) {
                    SettingSwitchRow("触感反馈", checked = state.hapticsEnabled, surfaceStyle = state.surfaceStyle, colors = colors) {
                        viewModel.setHapticsEnabled(!state.hapticsEnabled)
                    }
                    SettingSwitchRow(
                        "物理动效",
                        checked = state.tiltSpectrum || state.coverShake,
                        surfaceStyle = state.surfaceStyle,
                        colors = colors
                    ) { viewModel.setPhysicsFx(!(state.tiltSpectrum || state.coverShake)) }
                    SettingSwitchRow("灵动动效", checked = state.jellyAnim, surfaceStyle = state.surfaceStyle, colors = colors) {
                        viewModel.setJellyAnim(!state.jellyAnim)
                    }
                    SettingSwitchRow(
                        "低音马达震动",
                        checked = state.bassHaptics,
                        surfaceStyle = state.surfaceStyle,
                        showDivider = !state.bassHaptics,
                        colors = colors
                    ) { viewModel.setBassHaptics(!state.bassHaptics) }

                    AnimatedVisibility(
                        visible = state.bassHaptics,
                        enter = expandVertically(tween(300, easing = FastOutSlowInEasing)) + fadeIn(tween(200)),
                        exit = shrinkVertically(tween(300, easing = FastOutSlowInEasing)) + fadeOut(tween(200))
                    ) {
                        Column {
                            InlineOptionRow(
                                title = "震动强度",
                                options = listOf("轻点", "标准", "重击"),
                                selectedIndex = state.bassHapticsIntensity,
                                surfaceStyle = state.surfaceStyle,
                                colors = colors,
                                isExpanded = expandedKey == "震动强度",
                                onToggleExpand = { expandedKey = if (expandedKey == "震动强度") null else "震动强度" }
                            ) { viewModel.setBassHapticsIntensity(it) }

                            InlineSliderRow(
                                title = "低频截止",
                                value = state.bassHapticsLowCutoff.toFloat(),
                                valueRange = 120f..320f,
                                valueFormat = { "${it.toInt()} Hz" },
                                surfaceStyle = state.surfaceStyle,
                                showDivider = false,
                                colors = colors,
                                isExpanded = expandedKey == "低频截止",
                                onToggleExpand = { expandedKey = if (expandedKey == "低频截止") null else "低频截止" }
                            ) { viewModel.setBassHapticsLowCutoff(it.toInt()) }
                        }
                    }
                }

                // ==================== 推荐 ====================
                SettingsGroup("推荐", state.surfaceStyle, colors) {
                    InlineSliderRow(
                        title = "探索度",
                        value = state.exploration,
                        valueRange = 0f..1f,
                        valueFormat = { "${(it * 100).toInt()}%" },
                        surfaceStyle = state.surfaceStyle,
                        colors = colors,
                        isExpanded = expandedKey == "探索度",
                        onToggleExpand = { expandedKey = if (expandedKey == "探索度") null else "探索度" }
                    ) { viewModel.setExploration(it) }

                    SettingSwitchRow(
                        "歌单页显示推荐区",
                        checked = state.showRecommendations,
                        surfaceStyle = state.surfaceStyle,
                        showDivider = false,
                        colors = colors
                    ) { viewModel.setShowRecommendations(!state.showRecommendations) }
                }

                // ==================== 数据 ====================
                SettingsGroup("数据", state.surfaceStyle, colors) {
                    SettingActionRow(
                        title = "扫描文件夹",
                        trailingText = if (state.scanRoot != null) "已指定" else "全部音乐",
                        showDivider = true,
                        surfaceStyle = state.surfaceStyle,
                        colors = colors
                    ) { folderPickerLauncher.launch(null) }

                    if (state.scanRoot != null) {
                        SettingActionRow(
                            title = "恢复全部文件夹",
                            trailingText = "重置",
                            surfaceStyle = state.surfaceStyle,
                            colors = colors
                        ) { viewModel.setScanRoot(null) }
                    }

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

                    SettingSwitchRow(
                        "上栏显示排序",
                        checked = state.showSortBar,
                        surfaceStyle = state.surfaceStyle,
                        colors = colors
                    ) { viewModel.setShowSortBar(!state.showSortBar) }

                    SettingActionRow(
                        title = "导出数据",
                        trailingText = if (transferBusy) "请稍候" else null,
                        enabled = !transferBusy,
                        surfaceStyle = state.surfaceStyle,
                        colors = colors
                    ) { exportLauncher.launch(DataTransfer.SUGGESTED_FILE_NAME) }

                    SettingActionRow(
                        title = "导入数据",
                        enabled = !transferBusy,
                        showDivider = false,
                        surfaceStyle = state.surfaceStyle,
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

                // ==================== 动画 ====================
                SettingsGroup("动画", state.surfaceStyle, colors) {
                    SettingSwitchRow("启动动画", checked = state.splashEnabled, surfaceStyle = state.surfaceStyle, showDivider = !state.splashEnabled, colors = colors) {
                        viewModel.setSplashEnabled(!state.splashEnabled)
                    }
                    AnimatedVisibility(
                        visible = state.splashEnabled,
                        enter = expandVertically(tween(300, easing = FastOutSlowInEasing)) + fadeIn(tween(200)),
                        exit = shrinkVertically(tween(300, easing = FastOutSlowInEasing)) + fadeOut(tween(200))
                    ) {
                        InlineOptionRow(
                            title = "动画风格",
                            options = listOf("简约", "温柔", "细腻"),
                            selectedIndex = state.splashStyle,
                            surfaceStyle = state.surfaceStyle,
                            showDivider = false,
                            colors = colors,
                            isExpanded = expandedKey == "动画风格",
                            onToggleExpand = { expandedKey = if (expandedKey == "动画风格") null else "动画风格" }
                        ) { viewModel.setSplashStyle(it) }
                    }
                }

                // ==================== 设置样式 ====================
                SettingsGroup("设置样式", state.surfaceStyle, colors) {
                    SettingSwitchRow(
                        "切换回经典设置面板",
                        subtitle = "关闭后使用经典列表样式",
                        checked = !state.useNewSettingsStyle,
                        surfaceStyle = state.surfaceStyle,
                        showDivider = false,
                        colors = colors
                    ) { viewModel.setUseNewSettingsStyle(!state.useNewSettingsStyle) }
                }

                Spacer(Modifier.height(30.dp))
            }

            // 底部完成按钮
            Spacer(Modifier.height(10.dp))
            Box(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp)
                    .clip(IrisShape.item)
                    .background(colors.primary)
                    .clickable { onDismiss() }
                    .padding(vertical = 14.dp)
                    .zIndex(5f),
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

        // ==================== 顶部与底部渐变遮罩 ====================
        // 顶部遮罩
        Box(
            Modifier
                .fillMaxWidth()
                .height(110.dp)
                .background(
                    Brush.verticalGradient(
                        colors = listOf(panelBg, panelBg.copy(alpha = 0.95f), panelBg.copy(alpha = 0.7f), Color.Transparent)
                    )
                )
                .zIndex(1f)
        )
        
        // 底部边缘虚化遮罩
        Box(
            Modifier
                .fillMaxWidth()
                .height(100.dp)
                .align(Alignment.BottomCenter)
                .background(
                    Brush.verticalGradient(
                        colors = listOf(Color.Transparent, panelBg.copy(alpha = 0.7f), panelBg.copy(alpha = 0.95f), panelBg)
                    )
                )
                .zIndex(1f)
        )

        // 顶部折叠标题
        val targetX = if (isScrolled) 20.dp else (maxW / 2f - 32.dp)
        // 根据修改后的留白，调整 targetY
        val targetY = if (isScrolled) 46.dp else 54.dp
        val targetSize = if (isScrolled) 18f else 24f

        val animatedX by animateDpAsState(targetX, spring(dampingRatio = 0.8f, stiffness = 400f))
        val animatedY by animateDpAsState(targetY, spring(dampingRatio = 0.8f, stiffness = 400f))
        val animatedSize by animateFloatAsState(targetSize, spring(dampingRatio = 0.8f, stiffness = 400f))

        Text(
            "设置",
            color = colors.text,
            fontSize = animatedSize.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier
                .align(Alignment.TopStart)
                .offset(x = animatedX, y = animatedY)
                .zIndex(2f)
        )

        // ==================== 沉浸式背景模糊浮层 ====================
        if (isScrubbingBlur) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(panelBg.copy(alpha = 0.95f))
                    .zIndex(10f)
                    .clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) {}, // 拦截点击
                contentAlignment = Alignment.Center
            ) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("背景模糊", color = colors.text, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(10.dp))
                    Text("${state.bgBlur.toInt()}", color = colors.primary, fontSize = 48.sp, fontWeight = FontWeight.Black)
                    Spacer(Modifier.height(30.dp))
                    RoundSlider(
                        value = state.bgBlur,
                        valueRange = BG_BLUR_MIN..BG_BLUR_MAX,
                        colors = colors,
                        onChange = { viewModel.setBgBlur(it) },
                        onFinished = { isScrubbingBlur = false }, // 松开恢复
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }
    }
}

// ==================== 组件区（受控版） ====================

@Composable
private fun SettingsGroup(
    title: String,
    surfaceStyle: IrisSurfaceStyle,
    colors: IrisColors,
    content: @Composable ColumnScope.() -> Unit
) {
    val isLiquid = surfaceStyle == IrisSurfaceStyle.LIQUID
    val groupModifier = if (isLiquid) {
        Modifier.irisSurface(GlassLevel.CARD, colors, RoundedCornerShape(20.dp))
    } else {
        Modifier
            .shadow(1.dp, RoundedCornerShape(20.dp))
            .background(if (colors.isDark) Color(0xFF1C1C1E) else Color.White, RoundedCornerShape(20.dp))
    }

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
                .then(groupModifier)
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
            .padding(horizontal = 16.dp)
            .height(0.5.dp)
            .background(colors.subText.copy(alpha = 0.12f))
    )
}

@Composable
private fun SettingSwitchRow(
    title: String,
    subtitle: String? = null,
    checked: Boolean,
    surfaceStyle: IrisSurfaceStyle,
    showDivider: Boolean = true,
    colors: IrisColors,
    onToggle: () -> Unit
) {
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 54.dp)
                .clickable(
                    indication = null,
                    interactionSource = remember { MutableInteractionSource() }
                ) { Haptics.tap(); onToggle() }
                .padding(horizontal = 16.dp),
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
    surfaceStyle: IrisSurfaceStyle,
    colors: IrisColors,
    onClick: () -> Unit
) {
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 54.dp)
                .clickable(
                    enabled = enabled,
                    indication = null,
                    interactionSource = remember { MutableInteractionSource() }
                ) { Haptics.tap(); onClick() }
                .padding(horizontal = 16.dp),
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
    surfaceStyle: IrisSurfaceStyle,
    colors: IrisColors,
    isExpanded: Boolean,
    onToggleExpand: () -> Unit,
    showDivider: Boolean = true,
    onSelect: (Int) -> Unit
) {
    Box(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 54.dp)
            .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
            .drawWithContent {
                drawContent()
                val fade = 16.dp.toPx().coerceAtMost(size.width / 2f)
                val f = (fade / size.width).coerceIn(0f, 0.5f)
                drawRect(
                    brush = Brush.horizontalGradient(
                        0f to Color.Transparent,
                        f to Color.Black,
                        (1f - f) to Color.Black,
                        1f to Color.Transparent
                    ),
                    blendMode = BlendMode.DstIn
                )
            }
            .padding(horizontal = 16.dp)
    ) {
        AnimatedContent(
            targetState = isExpanded,
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
        ) { expanded ->
            if (expanded) {
                Row(
                    Modifier.fillMaxWidth().height(54.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    options.forEachIndexed { i, opt ->
                        val sel = i == selectedIndex
                        val isLiquid = surfaceStyle == IrisSurfaceStyle.LIQUID
                        val btnBg = if (sel) {
                            colors.primary.copy(alpha = 0.16f)
                        } else {
                            if (isLiquid) colors.subText.copy(alpha = 0.08f) else Color.White
                        }
                        val btnModifier = if (isLiquid) {
                            Modifier.irisSurface(GlassLevel.CHIP, colors, RoundedCornerShape(10.dp), solid = btnBg)
                        } else {
                            Modifier.background(btnBg, RoundedCornerShape(10.dp))
                        }

                        Row(
                            Modifier
                                .weight(1f)
                                .height(36.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .then(btnModifier)
                                .clickable(
                                    indication = null,
                                    interactionSource = remember { MutableInteractionSource() }
                                ) {
                                    Haptics.tap()
                                    onSelect(i)
                                    onToggleExpand() // 选完收回
                                }
                                .padding(horizontal = 4.dp),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            val autoSize = if (opt.length > 3) 10.sp else 12.sp
                            Text(
                                opt,
                                color = if (sel) colors.primary else colors.text,
                                fontSize = autoSize,
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
                        .height(54.dp)
                        .clickable(
                            indication = null,
                            interactionSource = remember { MutableInteractionSource() }
                        ) {
                            Haptics.tap()
                            onToggleExpand() // 展开
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
    if (showDivider) SettingDivider(colors)
}

@Composable
private fun InlineSwitchOptionRow(
    title: String,
    checked: Boolean,
    options: List<String>,
    selectedIndex: Int,
    surfaceStyle: IrisSurfaceStyle,
    colors: IrisColors,
    isExpanded: Boolean,
    onToggleExpand: () -> Unit,
    showDivider: Boolean = true,
    onToggle: (Boolean) -> Unit,
    onSelect: (Int) -> Unit
) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 54.dp)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.weight(1f).padding(end = 12.dp)) {
            AnimatedContent(
                targetState = isExpanded,
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
                label = "switchOption"
            ) { expanded ->
                if (expanded) {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        options.forEachIndexed { i, opt ->
                            val sel = i == selectedIndex
                            val isLiquid = surfaceStyle == IrisSurfaceStyle.LIQUID
                            val btnBg = if (sel) {
                                colors.primary.copy(alpha = 0.16f)
                            } else {
                                if (isLiquid) colors.subText.copy(alpha = 0.08f) else Color.White
                            }
                            val btnModifier = if (isLiquid) {
                                Modifier.irisSurface(GlassLevel.CHIP, colors, RoundedCornerShape(10.dp), solid = btnBg)
                            } else {
                                Modifier.background(btnBg, RoundedCornerShape(10.dp))
                            }

                            Row(
                                Modifier
                                    .weight(1f)
                                    .height(36.dp)
                                    .clip(RoundedCornerShape(10.dp))
                                    .then(btnModifier)
                                    .clickable(
                                        indication = null,
                                        interactionSource = remember { MutableInteractionSource() }
                                    ) {
                                        Haptics.tap()
                                        onSelect(i)
                                    }
                                    .padding(horizontal = 4.dp),
                                horizontalArrangement = Arrangement.Center,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                val autoSize = if (opt.length > 3) 10.sp else 12.sp
                                Text(
                                    opt,
                                    color = if (sel) colors.primary else colors.text,
                                    fontSize = autoSize,
                                    fontWeight = if (sel) FontWeight.Bold else FontWeight.Medium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                } else {
                    Text(
                        title,
                        color = colors.text,
                        fontSize = 16.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (!checked) {
                // 未开启时显示开关
                LiquidSwitch(checked = false, colors = colors, onCheckedChange = { onToggle(true); onToggleExpand() })
            } else {
                // 开启时显示收回按钮或直接用开关控制
                LiquidSwitch(checked = true, colors = colors, onCheckedChange = { 
                    onToggle(false)
                    if (isExpanded) onToggleExpand()
                })
            }
        }
    }
    if (showDivider) SettingDivider(colors)
}

@Composable
private fun InlineSliderRow(
    title: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    valueFormat: (Float) -> String,
    surfaceStyle: IrisSurfaceStyle,
    colors: IrisColors,
    isExpanded: Boolean,
    onToggleExpand: () -> Unit,
    showDivider: Boolean = true,
    onValueChangeFinished: (() -> Unit)? = null,
    onScrubStart: (() -> Unit)? = null,
    onScrubEnd: (() -> Unit)? = null,
    onValueChange: (Float) -> Unit
) {
    Column(
        Modifier
            .fillMaxWidth()
            .animateContentSize(tween(320, easing = FastOutSlowInEasing))
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 54.dp)
                .clickable(
                    indication = null,
                    interactionSource = remember { MutableInteractionSource() }
                ) { Haptics.tap(); onToggleExpand() }
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(title, color = colors.text, fontSize = 16.sp, modifier = Modifier.weight(1f))
            Text(valueFormat(value), color = colors.subText, fontSize = 14.sp)
        }
        AnimatedVisibility(
            visible = isExpanded,
            enter = expandVertically(tween(280, easing = EaseOutCubic)) + fadeIn(tween(200)),
            exit = shrinkVertically(tween(240, easing = EaseInCubic)) + fadeOut(tween(160))
        ) {
            RoundSlider(
                value = value,
                valueRange = valueRange,
                colors = colors,
                onChange = onValueChange,
                onFinished = { 
                    onValueChangeFinished?.invoke()
                    onScrubEnd?.invoke()
                },
                onStart = { onScrubStart?.invoke() },
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
            )
        }
        if (showDivider) SettingDivider(colors)
    }
}

@Composable
private fun RoundSlider(
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    colors: IrisColors,
    onChange: (Float) -> Unit,
    onFinished: () -> Unit,
    onStart: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val span = (valueRange.endInclusive - valueRange.start).takeIf { it > 0f } ?: 1f
    val frac = ((value - valueRange.start) / span).coerceIn(0f, 1f)
    val thumbD = 22.dp
    val thumbPx = with(LocalDensity.current) { thumbD.toPx() }
    BoxWithConstraints(
        modifier
            .fillMaxWidth()
            .height(32.dp)
            .pointerInput(valueRange) {
                detectDragGestures(
                    onDragStart = { onStart?.invoke() },
                    onDragEnd = { onFinished() },
                    onDragCancel = { onFinished() }
                ) { change, _ ->
                    val usable = size.width - thumbPx
                    val f = if (usable > 0f) {
                        ((change.position.x - thumbPx / 2f) / usable).coerceIn(0f, 1f)
                    } else 0f
                    onChange(valueRange.start + f * span)
                }
            }
            .pointerInput(valueRange) {
                detectTapGestures { offset ->
                    val usable = size.width - thumbPx
                    val f = if (usable > 0f) {
                        ((offset.x - thumbPx / 2f) / usable).coerceIn(0f, 1f)
                    } else 0f
                    onChange(valueRange.start + f * span)
                    onFinished()
                }
            },
        contentAlignment = Alignment.CenterStart
    ) {
        val usable = maxWidth - thumbD
        Box(
            Modifier
                .fillMaxWidth()
                .height(4.dp)
                .clip(CircleShape)
                .background(colors.subText.copy(alpha = 0.22f))
        )
        Box(
            Modifier
                .fillMaxWidth(frac)
                .height(4.dp)
                .clip(CircleShape)
                .background(colors.primary)
        )
        Box(
            Modifier
                .offset(x = usable * frac)
                .size(thumbD)
                .shadow(3.dp, CircleShape)
                .background(Color.White, CircleShape)
                .border(0.5.dp, Color.Black.copy(alpha = 0.08f), CircleShape)
        )
    }
}

@Composable
private fun InlineColorRow(
    title: String,
    primaryArgb: Long,
    secondaryArgb: Long,
    surfaceStyle: IrisSurfaceStyle,
    colors: IrisColors,
    isExpanded: Boolean,
    onToggleExpand: () -> Unit,
    showDivider: Boolean = true,
    onColorsChange: (Long, Long) -> Unit
) {
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
                .heightIn(min = 54.dp)
                .clickable(
                    indication = null,
                    interactionSource = remember { MutableInteractionSource() }
                ) { Haptics.tap(); onToggleExpand() }
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(title, color = colors.text, fontSize = 16.sp, modifier = Modifier.weight(1f))
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Box(Modifier.size(12.dp).clip(CircleShape).background(Color(primaryArgb)))
                Box(Modifier.size(12.dp).clip(CircleShape).background(Color(secondaryArgb)))
            }
        }
        AnimatedVisibility(
            visible = isExpanded,
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
                    )
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
                    )
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
                    )
                ) { v ->
                    val arr = activeHsv.copyOf(); arr[2] = v
                    if (editSecondary) secondaryHsv = arr else primaryHsv = arr
                    onColorsChange(argbOf(primaryHsv), argbOf(secondaryHsv))
                }
                Spacer(Modifier.height(6.dp))
            }
        }
        if (showDivider) SettingDivider(colors)
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
    onChange: (Float) -> Unit
) {
    val span = (range.endInclusive - range.start).takeIf { it > 0f } ?: 1f
    val frac = ((value - range.start) / span).coerceIn(0f, 1f)
    val thumbD = 22.dp
    val thumbPx = with(LocalDensity.current) { thumbD.toPx() }
    BoxWithConstraints(
        Modifier
            .fillMaxWidth()
            .height(32.dp)
            .pointerInput(range) {
                detectTapGestures { offset ->
                    val usable = size.width - thumbPx
                    val f = if (usable > 0f) {
                        ((offset.x - thumbPx / 2f) / usable).coerceIn(0f, 1f)
                    } else 0f
                    onChange(range.start + f * span)
                }
            }
            .pointerInput(range) {
                detectDragGestures { change, _ ->
                    val usable = size.width - thumbPx
                    val f = if (usable > 0f) {
                        ((change.position.x - thumbPx / 2f) / usable).coerceIn(0f, 1f)
                    } else 0f
                    onChange(range.start + f * span)
                }
            },
        contentAlignment = Alignment.CenterStart
    ) {
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
                .shadow(3.dp, CircleShape)
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
    val thumbOffset = 2.dp + travel * progress
    
    val iosGreen = Color(0xFF34C759)
    val trackColor = lerp(colors.subText.copy(alpha = 0.3f), iosGreen, progress)
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
private fun FloatingLyricGroup(
    colors: IrisColors, 
    surfaceStyle: IrisSurfaceStyle,
    expandedKey: String?,
    onToggleExpand: (String?) -> Unit
) {
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
        surfaceStyle = surfaceStyle,
        showDivider = !enabled,
        colors = colors
    ) {
        enabled = !enabled
        writeBool(FloatingLyric.KEY_ENABLED, enabled)
        if (enabled && !FloatingLyric.hasPermission(ctx)) FloatingLyric.requestPermission(ctx)
    }

    AnimatedVisibility(
        visible = enabled,
        enter = expandVertically(tween(300, easing = FastOutSlowInEasing)) + fadeIn(tween(200)),
        exit = shrinkVertically(tween(300, easing = FastOutSlowInEasing)) + fadeOut(tween(200))
    ) {
        Column {
            if (!granted) {
                SettingActionRow(
                    title = "授予悬浮窗权限",
                    trailingText = "去授权",
                    surfaceStyle = surfaceStyle,
                    colors = colors
                ) { FloatingLyric.requestPermission(ctx) }
            }

            SettingSwitchRow("逐字流光", checked = karaoke, surfaceStyle = surfaceStyle, colors = colors) {
                karaoke = !karaoke; writeBool(FloatingLyric.KEY_KARAOKE, karaoke)
            }

            InlineSwitchOptionRow(
                title = "固定位置",
                checked = fixed,
                options = listOf("居中", "完全固定"),
                selectedIndex = fixMode,
                surfaceStyle = surfaceStyle,
                colors = colors,
                isExpanded = expandedKey == "固定位置",
                onToggleExpand = { onToggleExpand(if (expandedKey == "固定位置") null else "固定位置") },
                onToggle = { fixed = it; writeBool(FloatingLyric.KEY_FIXED, it) },
                onSelect = { fixMode = it; writeInt(FloatingLyric.KEY_FIX_MODE, it) }
            )

            val themeOptions = listOf(IrisTheme.MONO, IrisTheme.OCEAN, IrisTheme.GRASS, IrisTheme.CUSTOM)
            InlineSwitchOptionRow(
                title = "悬浮歌词颜色",
                checked = useCustomColor,
                options = themeOptions.map { it.label },
                selectedIndex = themeOptions.indexOf(floatTheme),
                surfaceStyle = surfaceStyle,
                showDivider = !(useCustomColor && floatTheme == IrisTheme.CUSTOM),
                colors = colors,
                isExpanded = expandedKey == "悬浮歌词颜色",
                onToggleExpand = { onToggleExpand(if (expandedKey == "悬浮歌词颜色") null else "悬浮歌词颜色") },
                onToggle = { useCustomColor = it; writeBool(FloatingLyric.KEY_USE_CUSTOM, it) },
                onSelect = { index ->
                    floatTheme = themeOptions[index]
                    prefs.edit().putString(FloatingLyric.KEY_THEME, floatTheme.name).apply()
                }
            )

            AnimatedVisibility(
                visible = useCustomColor && floatTheme == IrisTheme.CUSTOM,
                enter = expandVertically(tween(300, easing = FastOutSlowInEasing)) + fadeIn(tween(200)),
                exit = shrinkVertically(tween(300, easing = FastOutSlowInEasing)) + fadeOut(tween(200))
            ) {
                InlineColorRow(
                    title = "自定义悬浮歌词色",
                    primaryArgb = customPrimary,
                    secondaryArgb = customSecondary,
                    surfaceStyle = surfaceStyle,
                    colors = colors,
                    isExpanded = expandedKey == "自定义悬浮歌词色",
                    onToggleExpand = { onToggleExpand(if (expandedKey == "自定义悬浮歌词色") null else "自定义悬浮歌词色") },
                    onColorsChange = { p, s ->
                        customPrimary = p; customSecondary = s
                        prefs.edit()
                            .putLong(FloatingLyric.KEY_CUSTOM_PRIMARY, p)
                            .putLong(FloatingLyric.KEY_CUSTOM_SECONDARY, s)
                            .apply()
                    }
                )
            }

            InlineSliderRow(
                title = "字号",
                value = font,
                valueRange = 12f..40f,
                valueFormat = { "${it.toInt()}" },
                surfaceStyle = surfaceStyle,
                colors = colors,
                isExpanded = expandedKey == "字号",
                onToggleExpand = { onToggleExpand(if (expandedKey == "字号") null else "字号") }
            ) { font = it; writeFloat(FloatingLyric.KEY_FONT, it) }

            InlineSliderRow(
                title = "宽度",
                value = widthFrac,
                valueRange = 0.3f..1f,
                valueFormat = { "${(it * 100).toInt()}%" },
                surfaceStyle = surfaceStyle,
                colors = colors,
                isExpanded = expandedKey == "宽度",
                onToggleExpand = { onToggleExpand(if (expandedKey == "宽度") null else "宽度") }
            ) { widthFrac = it; writeFloat(FloatingLyric.KEY_WIDTH, it) }

            InlineSliderRow(
                title = "背景透明度",
                value = bg,
                valueRange = 0f..1f,
                valueFormat = { "${(it * 100).toInt()}%" },
                surfaceStyle = surfaceStyle,
                showDivider = false,
                colors = colors,
                isExpanded = expandedKey == "背景透明度",
                onToggleExpand = { onToggleExpand(if (expandedKey == "背景透明度") null else "背景透明度") }
            ) { bg = it; writeFloat(FloatingLyric.KEY_BG, it) }
        }
    }
}
