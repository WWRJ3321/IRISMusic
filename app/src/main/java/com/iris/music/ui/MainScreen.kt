/*
 * This file is part of IRIS Music.
 * Copyright (C) 2026 WWRJ
 *
 * IRIS Music is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package com.iris.music.ui

import androidx.compose.animation.*
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.EaseInOutCubic
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.tween
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.*
import androidx.compose.runtime.*
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalConfiguration
import android.content.res.Configuration
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex

import com.iris.music.audio.BassHaptics
import com.iris.music.audio.EqualizerController
import com.iris.music.data.MusicFolder
import com.iris.music.data.LyricLine
import com.iris.music.data.LyricParser
import com.iris.music.data.RepeatMode
import com.iris.music.data.Playlist
import com.iris.music.data.Playlists
import com.iris.music.data.Song
import com.iris.music.data.formatLabel
import com.iris.music.player.PlayerUiState
import com.iris.music.player.PlayerViewModel
import com.iris.music.ui.theme.*
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.haze
import dev.chrisbanes.haze.hazeChild
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/** 主界面：列表 ↔ 播放器（左右滑动），右上角设置 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MainScreen(
    state: PlayerUiState,
    viewModel: PlayerViewModel,
    onToggle: () -> Unit,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onSeek: (Float) -> Unit,
    onSelect: (Int) -> Unit,
    onSearch: (String) -> Unit,
    onSelectFolder: (String?) -> Unit,
    onToggleLike: (Long) -> Unit,
    onToggleShuffle: () -> Unit,
    onCycleRepeat: () -> Unit,
    onToggleSettings: (Boolean) -> Unit,
    onThemeChange: (IrisTheme) -> Unit,
    onModeChange: (IrisMode) -> Unit,
    onRowSizeChange: (Int) -> Unit,
    onExplorationChange: (Float) -> Unit
) {
    // 横屏「天台夜航」场景已暂时下线（NightFlightScene 文件保留，恢复时在此处重新接回）。
    // 自定义背景图 SAF 选择器
    val bgPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri -> viewModel.setCustomBackground(uri) }
    // 果冻开关同步到全局动效中心：所有组件读 IrisMotion.jelly
androidx.compose.runtime.LaunchedEffect(state.jellyAnim) {
        IrisMotion.jelly = state.jellyAnim
    }
    // 主题色过渡：切主题/明暗时整套配色 450ms 渐变，不整屏硬闪。
    // 快速连续切主题：新动画总是从「当前显示的混色」出发（LaunchedEffect 重启时
    // holder 里正是上一段动画停下的帧），所以接续而不是跳变。
    val targetColors = state.theme.colors(
        state.mode, state.systemDark,
        customPrimary = Color(state.customPrimaryArgb),
        customSecondary = Color(state.customSecondaryArgb)
    )
    val colorAnim = remember { androidx.compose.animation.core.Animatable(1f) }
    var mixedColors by remember { mutableStateOf(targetColors) }
    LaunchedEffect(state.theme, state.mode, state.systemDark, state.customPrimaryArgb, state.customSecondaryArgb) {
        val from = mixedColors
        val to = targetColors
        if (from == to) { colorAnim.snapTo(1f); return@LaunchedEffect }
        colorAnim.snapTo(0f)
        colorAnim.animateTo(
            1f,
            androidx.compose.animation.core.tween(450, easing = androidx.compose.animation.core.EaseInOutCubic)
        ) { mixedColors = lerpIrisColors(from, to, value) }
    }
    val colors = if (colorAnim.value >= 1f) targetColors else mixedColors
    val pagerState = rememberPagerState(pageCount = { 2 })
    val glass = isLiquidGlass

    // 液态玻璃的取样背板：背景一层、页面内容一层。
    // 弹层（设置 / 均衡器）浮在页面之上，要同时折射这两层，
    // 才会出现"透过玻璃看见下面的卡片被压缩变形"这种真玻璃观感。
    val bgBackdrop = rememberIrisBackdrop()
    val pageBackdrop = rememberIrisBackdrop()
    val pageBackdrops = remember(glass, bgBackdrop) {
        if (glass) listOf(bgBackdrop) else emptyList()
    }
    val sheetBackdrops = remember(glass, bgBackdrop, pageBackdrop) {
        if (glass) listOf(bgBackdrop, pageBackdrop) else emptyList()
    }

    // 均衡器弹层
    var showEqualizer by remember { mutableStateOf(false) }
    val eqState by EqualizerController.state.collectAsState()

    // 睡眠定时器弹层：提到根层级，才能和设置/均衡器一样折射「背景 + 页面」两层
    var showSleepTimer by remember { mutableStateOf(false) }

    // 歌单管理弹层
    var showPlaylists by remember { mutableStateOf(false) }

    // 长按歌曲 → 待加入歌单的歌曲
    var pendingAddSong by remember { mutableStateOf<Song?>(null) }

    // 库页列表状态：提升到根层级，滚动条挂在根 Box 上（保证钉在屏幕右缘）
    val libraryListState = rememberLazyListState()
    // 快速滚动条是否正在拖动（拖动期间列表由滑条驱动，上栏需实时跟随）
    val scrollerDragging = remember { mutableStateOf(false) }

    Box(
        Modifier
            .fillMaxSize()
            .background(colors.background)
    ) {
        // 共享模糊背景（玻璃的最底层背板）
        AuroraBackground(
            state.currentSong?.filePath,
            isDark = colors.isDark,
            blurRadius = state.bgBlur,
            customUri = state.customBackgroundUri,
            modifier = if (glass) Modifier.irisBackdropSource(bgBackdrop) else Modifier
        )

        CompositionLocalProvider(LocalIrisBackdrops provides pageBackdrops) {
            // 卡片模式：整个 App 只剩一叠播放卡片，没有歌单页也没有上栏。
            // 不是"在 Pager 里加一页"，而是彻底替掉 Pager——两种结构的手势
            // （横滑翻页 / 横滑翻卡）会互相抢，同时存在必然打架。
            //
            // 用 Crossfade 包住切换：if/else 直接换树，两种结构的首帧
            // （玻璃背板重录、Pager 重建）在同一帧里裸奔，看着就是闪。
            Crossfade(
                targetState = state.layout,
                // 慢一点 + EaseInOut：180ms 直切快得像闪屏，420ms 缓入缓出
                // 才是"柔和过渡"的节奏。再长会拖泥带水——切布局是低频操作，
                // 用户愿意多等半秒看清楚，但不愿意等一秒。
                animationSpec = tween(420, easing = androidx.compose.animation.core.EaseInOut),
                label = "layoutSwitch"
            ) { layout ->
            if (layout.isDeck) {
                SongDeck(
                    state = state,
                    colors = colors,
                    modifier = if (glass) Modifier.irisBackdropSource(pageBackdrop) else Modifier,
                    onToggle = onToggle,
                    onPrev = onPrev,
                    onNext = onNext,
                    onSeek = onSeek,
                    onSelect = onSelect,
                    onToggleLike = onToggleLike,
                    onToggleShuffle = onToggleShuffle,
                    onCycleRepeat = onCycleRepeat,
                    onToggleVisualizer = { viewModel.setVisualizerEnabled(!state.visualizerEnabled) },
                    onOpenEqualizer = { showEqualizer = true },
                    onOpenSleepTimer = { showSleepTimer = true },
                    onOpenReport = { viewModel.toggleReport(true) },
                    onOpenSettings = { onToggleSettings(true) },
                    onCycleLayout = { viewModel.setLayout(state.layout.next) },
                    onOpenPlaylists = { showPlaylists = true },
                    onReload = { viewModel.reloadLibrary() },
                    eqActive = eqState.active
                )
            } else {
            HorizontalPager(
                state = pagerState,
                modifier = Modifier
                    .fillMaxSize()
                    .then(if (glass) Modifier.irisBackdropSource(pageBackdrop) else Modifier)
            ) { page ->
                when (page) {
                    0 -> LibraryPage(
                        state = state,
                        colors = colors,
                        listState = libraryListState,
                        onSelect = { index ->
                            onSelect(index)
                        },
                        onPlaySong = viewModel::playSong,
                        onSearch = onSearch,
                        onSettings = { onToggleSettings(true) },
                        onReport = { viewModel.toggleReport(true) },
                        onReload = { viewModel.reloadLibrary() },
                        onCycleLayout = { viewModel.setLayout(state.layout.next) },
                        onOpenPlaylists = { showPlaylists = true },
                        onAddToPlaylist = { song -> pendingAddSong = song },
                        scrollerDragging = scrollerDragging
                    )

                    1 -> PlayerPage(
                        state = state,
                        colors = colors,
                        viewModel = viewModel,
                        onToggle = onToggle,
                        onPrev = onPrev,
                        onNext = onNext,
                        onSeek = onSeek,
                        onToggleLike = onToggleLike,
                        onToggleShuffle = onToggleShuffle,
                        onCycleRepeat = onCycleRepeat,
                        onOpenEqualizer = { showEqualizer = true },
                        onOpenSleepTimer = { showSleepTimer = true },
                        eqActive = eqState.active
                    )
                }
            }
            }
            } // Crossfade
        }

        // ===== 快速滚动条：挂在根层级、Pager 之上，钉死屏幕右缘且不被页面遮挡 =====
        // 仅库页显示：翻到播放页或滑动中时隐藏。卡片模式下没有歌单，整条不存在。
val scrollerVisible by remember {
             derivedStateOf { pagerState.currentPage == 0 && !pagerState.isScrollInProgress }
         }
         val locateScope = rememberCoroutineScope()
         // 行程
         // 行程：原先居中占屏高 55%（22.5%~77.5%），拇指要伸到很靠上的位置才够。
        // 顶部再收 20%、底部收 10%（按原行程比例），落到 33.5%~72%，
        // 行程缩到 38.5% 屏高且整体下移，单手拖动更省力。
        if (!state.layout.isDeck) {
         Column(
            Modifier
                .align(Alignment.CenterEnd)
                .fillMaxHeight()
        ) {
            Spacer(Modifier.weight(0.335f))
            Box(Modifier.weight(0.385f)) {
                // 推荐区占据 LazyColumn 的 item 0，因此队列索引需要减去该偏移
                val headerOffset = if (state.allSongs.isNotEmpty() && state.searchQuery.isBlank()) 1 else 0
                FastScroller(
                    listState = libraryListState,
                    colors = colors,
                    topPadding = 0.dp,
                    bottomPadding = 0.dp,
visible = scrollerVisible,
                     onClick = {
                         Haptics.tap()
                         if (state.currentIndex >= 0) {
                             locateScope.launch { libraryListState.animateScrollToItem(state.currentIndex + headerOffset) }
                         }
                     },
                     labelForIndex = { i ->
                        state.queue.getOrNull(i - headerOffset)?.title
                    },
                    onDraggingChange = { scrollerDragging.value = it }
                )
            }
            Spacer(Modifier.weight(0.28f))
        }
        }

        // 设置面板 / 均衡器：作为最上层玻璃，折射「背景 + 页面」两层背板
        CompositionLocalProvider(LocalIrisBackdrops provides sheetBackdrops) {
            AnimatedVisibility(
                visible = state.showSettings,
                enter = fadeIn(tween(200)),
                exit = fadeOut(tween(180)),
                modifier = Modifier.fillMaxSize()
            ) {
                // 拖把手下滑关闭：面板根随手指位移，把手命中在 SettingsPanel 内部
                val settingsDrag = rememberSheetDragState()
                IrisSheet(
                    colors = colors,
                    onDismiss = { onToggleSettings(false) },
                    drag = settingsDrag,
                    panelModifier = Modifier.heightIn(max = 720.dp)
                ) {
                    SettingsPanel(
                            state = state,
                            colors = colors,
                            onSelectFolder = onSelectFolder,
                            onThemeChange = onThemeChange,
                            onModeChange = onModeChange,
                            onRowSizeChange = onRowSizeChange,
                            onRowCoverChange = viewModel::setShowRowCover,
                             onLikedBadgeChange = viewModel::setShowLikedBadge,
                            onLikedFilterChange = viewModel::setOnlyLiked,
                            onExplorationChange = onExplorationChange,
                            onCornerBaseChange = viewModel::setCornerBase,
                            onSurfaceStyleChange = viewModel::setSurfaceStyle,
                            onLayoutChange = viewModel::setLayout,
                            onGlassBlurChange = viewModel::setGlassBlur,
                            onBgBlurChange = viewModel::setBgBlur,
                            onFadeChange = viewModel::setFadeEnabled,
                            onFadeMsChange = viewModel::setFadeMs,
                            onHapticsChange = viewModel::setHapticsEnabled,
onShowRecsChange = viewModel::setShowRecommendations,
                           onJellyAnimChange = viewModel::setJellyAnim,
                           onSilenceSkipChange = viewModel::setSilenceSkip,
onPhysicsFxChange = viewModel::setPhysicsFx,
                             onCoverLyricChange = viewModel::setCoverLyric,
                             onBassHapticsChange = viewModel::setBassHaptics,
                            onBassHapticsIntensityChange = viewModel::setBassHapticsIntensity,
                            onBassHapticsPulseMsChange = viewModel::setBassHapticsPulseMs,
                            onBassHapticsSensitivityChange = viewModel::setBassHapticsSensitivity,
                            onBassHapticsAdaptiveChange = viewModel::setBassHapticsAdaptive,
                            onCustomColorsChange = viewModel::setCustomColors,
                            onPickBackground = { bgPickerLauncher.launch(arrayOf("image/*")) },
                            onClearBackground = { viewModel.setCustomBackground(null) },
                             sheetDrag = settingsDrag,
                             onDismiss = { onToggleSettings(false) }
                    )
                }
            }

            // 歌单管理弹层
            AnimatedVisibility(
                visible = showPlaylists,
                enter = fadeIn(tween(200)),
                exit = fadeOut(tween(180)),
                modifier = Modifier.fillMaxSize()
            ) {
                PlaylistSheet(
                    state = state,
                    colors = colors,
                    onDismiss = { showPlaylists = false },
                    onSelect = { viewModel.selectPlaylist(it) },
                    onCreate = { name -> viewModel.createPlaylist(name) },
                    onRename = { id, name -> viewModel.renamePlaylist(id, name) },
                    onDelete = { viewModel.deletePlaylist(it) }
                )
            }

            // 添加到歌单弹层（长按歌曲触发）
            AnimatedVisibility(
                visible = pendingAddSong != null,
                enter = fadeIn(tween(200)),
                exit = fadeOut(tween(180))
            ) {
                val song = pendingAddSong
                if (song != null) {
                    AddToPlaylistSheet(
                        song = song,
                        state = state,
                        colors = colors,
                        onDismiss = { pendingAddSong = null },
                        onCreateAndAdd = { name ->
                            val id = viewModel.createPlaylist(name)
                            viewModel.addToPlaylist(id, song.id)
                            pendingAddSong = null
                        },
                        onAdd = { pid -> viewModel.addToPlaylist(pid, song.id); pendingAddSong = null }
                    )
                }
            }

            // 均衡器弹层
            AnimatedVisibility(
                visible = showEqualizer,
                enter = fadeIn(tween(200)),
                exit = fadeOut(tween(180))
            ) {
                EqualizerSheet(
                    state = eqState,
                    onDismiss = { showEqualizer = false },
                    onSelectBoost = { EqualizerController.selectBoost(it) },
                    onToggleLoudness = { EqualizerController.setLoudness(it) },
                     onLoudnessStrengthChange = { EqualizerController.setLoudnessStrength(it) },
                    virtualBass = state.virtualBass,
                    virtualBassStrength = state.virtualBassStrength,
                    onVirtualBassChange = viewModel::setVirtualBass,
                    onVirtualBassStrengthChange = viewModel::setVirtualBassStrength,
                    rangeEnhancer = state.rangeEnhancer,
                    rangeEnhancerStrength = state.rangeEnhancerStrength,
                    onRangeEnhancerChange = viewModel::setRangeEnhancer,
                    onRangeEnhancerStrengthChange = viewModel::setRangeEnhancerStrength,
                    virtualSurround = state.virtualSurround,
                    virtualSurroundStrength = state.virtualSurroundStrength,
                    onVirtualSurroundChange = viewModel::setVirtualSurround,
                    onVirtualSurroundStrengthChange = viewModel::setVirtualSurroundStrength,
                    onSetEditMode = { EqualizerController.setEditMode(it) },
                    onBandLevel = { band, level -> EqualizerController.setBandLevel(band, level) },
                    onMoveAnchor = { index, freq, gain -> EqualizerController.moveAnchor(index, freq, gain) },
                    onCommitAnchors = { EqualizerController.commitAnchors() },
                    onAddAnchor = { freq, gain -> EqualizerController.addAnchor(freq, gain) },
                    onRemoveAnchor = { EqualizerController.removeAnchor(it) },
                    onReset = { EqualizerController.reset() },
                    colors = colors
                )
            }

            // 睡眠定时器弹层
            AnimatedVisibility(
                visible = showSleepTimer,
                enter = fadeIn(tween(200)),
                exit = fadeOut(tween(180))
            ) {
                SleepTimerSheet(
                    currentMinutes = if (state.sleepTimerMs > 0) (state.sleepTimerMs / 60000).toInt() else 0,
                    onDismiss = { showSleepTimer = false },
                    onSetMinutes = { minutes ->
                        if (minutes > 0) viewModel.setSleepTimer(minutes)
                        else viewModel.cancelSleepTimer()
                    },
                    colors = colors
                )
            }

            // 听歌报告弹层：和设置/均衡器同层，折射「背景 + 页面」两层背板
            AnimatedVisibility(
                visible = state.showReport,
                enter = fadeIn(tween(200)),
                exit = fadeOut(tween(180))
            ) {
                ListenReportSheet(
                    report = state.report,
                    onDismiss = { viewModel.toggleReport(false) },
                    onRangeChange = viewModel::setReportRange,
                    onPlaySong = { id ->
                        viewModel.playSongById(id)
                        viewModel.toggleReport(false)
                    },
                    colors = colors
                )
            }
        }
    }
}

// ==================== 列表页 ====================

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun LibraryPage(
    state: PlayerUiState,
    colors: IrisColors,
    listState: androidx.compose.foundation.lazy.LazyListState,
    scrollerDragging: MutableState<Boolean>,
    onSelect: (Int) -> Unit,
    onPlaySong: (Song) -> Unit,
    onSearch: (String) -> Unit,
    onSettings: () -> Unit,
    onReport: () -> Unit,
    onReload: () -> Unit,
    onCycleLayout: () -> Unit,
    onOpenPlaylists: () -> Unit,
    onAddToPlaylist: (Song) -> Unit
) {
    // 上栏高度（px）与当前偏移：上滑跟手滑出屏幕，下滑从任意位置随时拉回
    val density = LocalDensity.current
    val statusBarHeightDp = WindowInsets.statusBars.getTop(density)
    val barTopGap = 48.dp // 上栏距离状态栏底部的间距：明显靠下，不贴顶部
    val barSlotHeight = 84.dp // 上栏卡片估算高度（实际由 onSizeChanged 校准），用于给歌单顶部预留空间
    // 初始估算上栏高度，实际值由 onSizeChanged 更新
    val barHeight = remember { mutableStateOf(with(density) { barSlotHeight.toPx().toInt() }) }
    val barOffset = remember { mutableStateOf(0f) }

    // 歌单列表状态（由外部传入：滚动条挂在根层级共享）
    // 不再自动滚动到当前播放行：用户在浏览列表时切歌（推荐点歌/自动切歌）不打断浏览位置
    // 毛玻璃模糊源状态
    val hazeState = remember { HazeState() }
    // ===== 搜索输入焦点管理 =====
    // 用户诉求：输入时点任何地方都中断输入（收起键盘、失焦）。
    // 追踪搜索框是否聚焦 + 它在根坐标下的矩形；页面根层在聚焦期间监听按下，
    // 落点在搜索框外就 clearFocus()。监听只读不消费，不干扰列表点击/滚动。
    val focusManager = LocalFocusManager.current
    val searchInteraction = remember { MutableInteractionSource() }
    val searchFocused by searchInteraction.collectIsFocusedAsState()
    val searchBounds = remember { mutableStateOf(Rect.Zero) }
    val frosted = isFrostedGlass
    val frostedBlur = frostedBlurRadius
    val glass = isLiquidGlass

    // 上栏浮在歌单之上，除了背景还要折射歌单本身——"玻璃下面的歌名被压弯"是最能
    // 说明这是真玻璃的一处细节。歌单单独录一层背板，上栏取样【外层背板 + 歌单】。
    // 上栏是歌单的兄弟节点，不在歌单的录制内容里，所以不会互相失效。
    val listBackdrop = rememberIrisBackdrop()
    val outerBackdrops = LocalIrisBackdrops.current
    val barBackdrops = remember(glass, outerBackdrops, listBackdrop) {
        if (glass) outerBackdrops + listBackdrop else emptyList()
    }

    // 上栏隐藏行程：状态栏 + 间距 + 上栏实测高度。
    // 用 rememberUpdatedState 暴露给下面 remember 出来的连接体：直接让它捕获会锁死首帧值，
    // 上栏实测高度校准、insets 变化之后行程还是旧的。
    val hideDistancePx = statusBarHeightDp + with(density) { barTopGap.toPx() } + barHeight.value
    val hideDistanceState = rememberUpdatedState(hideDistancePx)
    val barScope = rememberCoroutineScope()
    var barSettleJob by remember { mutableStateOf<Job?>(null) }
    val settleBarTo: (Float) -> Unit = { target ->
        barSettleJob?.cancel()
        barSettleJob = barScope.launch {
            Animatable(barOffset.value).animateTo(
                targetValue = target,
                animationSpec = spring(dampingRatio = 0.9f, stiffness = Spring.StiffnessMediumLow)
            ) { barOffset.value = value }
        }
    }

    // 滑条拖动（scrollToItem 跳转）不经过嵌套滚动，上栏不会自动跟随。
    // 拖动中：完全按当前索引实时决定上栏；松手后交由 scrollConnection 的滚动增量驱动
    LaunchedEffect(listState, scrollerDragging.value) {
        if (!scrollerDragging.value) return@LaunchedEffect
        snapshotFlow { listState.firstVisibleItemIndex }
            .distinctUntilChanged()
            .collect { index ->
                barSettleJob?.cancel()
                barOffset.value = if (index == 0) 0f else -hideDistanceState.value
            }
    }

    // 列表真正回到顶部时上栏必须完整露出——这是整套位移逻辑的兜底。
    // 少了它，任何一次"上栏位移没跟上列表位移"都会把上栏永久留在屏幕外：
    // 到了顶部，下滑手势归下拉刷新，上栏再没有机会被拉回来。
    LaunchedEffect(listState) {
        snapshotFlow {
            listState.firstVisibleItemIndex == 0 && listState.firstVisibleItemScrollOffset == 0
        }
            .distinctUntilChanged()
            .collect { atTop -> if (atTop) settleBarTo(0f) }
    }
    // 内容不足一屏（搜索只剩几首）时列表根本不能滚，上栏一旦被推走就推不回来
    val listScrollable = listState.canScrollForward || listState.canScrollBackward
    LaunchedEffect(listScrollable) {
        if (!listScrollable && barOffset.value != 0f) settleBarTo(0f)
    }

    // ===== 下拉刷新：橡皮筋位移 + 弹性回弹 =====
    // 原先位移只喂给指示文字，界面本体不动，所以看着"不跟手"；现在位移直接作用在
    // 歌单与上栏上（见下方 graphicsLayer），整页跟着手指走。
    val refreshTriggerPx = with(density) { 96.dp.toPx() }
    // 拉到底的上限：再往下也不动，避免把整页拖出屏幕
    val maxPullPx = refreshTriggerPx * 1.7f
    // 刷新中内容停在这个位置，露出指示器
    val holdPullPx = refreshTriggerPx * 0.68f
    var refreshOffset by remember { mutableFloatStateOf(0f) }
    val isRefreshing = state.refreshing
    val pullScope = rememberCoroutineScope()
    var settleJob by remember { mutableStateOf<Job?>(null) }
    // 回弹用低阻尼 spring：过冲一点再回摆，这才是"Q弹"，直接置 0 是硬切
    suspend fun springOffsetTo(target: Float) {
        Animatable(refreshOffset).animateTo(
            targetValue = target,
            animationSpec = spring(dampingRatio = 0.45f, stiffness = Spring.StiffnessLow)
        ) { refreshOffset = value }
    }
    val settleTo: (Float) -> Unit = { target ->
        settleJob?.cancel()
        settleJob = pullScope.launch { springOffsetTo(target) }
    }
    // remember 块里的连接体不能直接读 state.refreshing / onReload：会永远拿到首帧的值
    val refreshingState = rememberUpdatedState(state.refreshing)
    val reloadState = rememberUpdatedState(onReload)

    // 下拉位移与上栏位移合并进同一个连接。
    // 拆成两个 nestedScroll 时，同一段手势会被两边各消化一次：下拉时上栏收到同样的
    // 增量，于是"下拉过程中往上滑，上栏跟着一起飞上去"。合并后每段手势只有一个归属。
    //
    // 上栏位移的驱动源是【列表真正消费掉的位移】(onPostScroll)，不是手势原始增量。
    // 用原始增量会失衡：列表到底/到顶还硬拖时列表不动、上栏却继续走，一段手势下来
    // 上栏比内容多走一截；等滑回顶部时上栏还差这一截没露出来，而顶部的下滑手势已经
    // 归下拉刷新，上栏就永久卡在屏幕外了。按消费量走，隐藏与拉回天然对称。
    val scrollConnection = remember {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                val delta = available.y
                if (delta == 0f) return Offset.Zero
                val byFinger = source == NestedScrollSource.UserInput

                // 1) 已经拉出下拉位移：这段手势先把位移收回，不传给列表也不动上栏
                if (byFinger && refreshOffset > 0f && delta < 0f) {
                    settleJob?.cancel()
                    val consumed = delta.coerceAtLeast(-refreshOffset)
                    refreshOffset += consumed
                    return Offset(0f, consumed)
                }

                // 2) 下滑且列表已在顶部：列表没法再吃这段位移，由上栏和下拉接手
                if (delta > 0f && !listState.canScrollBackward) {
                    // 2a) 上栏还没露全：先把它拉回来。这段自己消费掉，
                    //     否则会同时触发列表的 overscroll 拉伸与下拉刷新
                    if (barOffset.value < 0f) {
                        barSettleJob?.cancel()
                        val used = delta.coerceAtMost(-barOffset.value)
                        barOffset.value += used
                        return Offset(0f, used)
                    }
                    // 2b) 上栏已归位：橡皮筋下拉，越拉越沉（惯性阶段不触发刷新）
                    if (byFinger && !refreshingState.value) {
                        settleJob?.cancel()
                        val resistance = (1f - refreshOffset / maxPullPx).coerceIn(0.15f, 1f)
                        refreshOffset = (refreshOffset + delta * resistance).coerceAtMost(maxPullPx)
                        return Offset(0f, delta)
                    }
                }

                return Offset.Zero // 不消费，列表正常滚动，上栏交给 onPostScroll
            }

            override fun onPostScroll(
                consumed: Offset,
                available: Offset,
                source: NestedScrollSource
            ): Offset {
                // 上栏按列表实际滚动量 1:1 跟随：上滑滑出、下滑拉回。
                // consumed 只含列表自己吃掉的量（不含 onPreScroll 的消费，也不含 overscroll
                // 拉伸），所以到底/到顶硬拖时上栏不会白走一截。惯性阶段同样计入，
                // 否则 fling 时列表飞很远而上栏停着，松手后两者位置就对不上了。
                val delta = consumed.y
                if (delta != 0f) {
                    barSettleJob?.cancel()
                    barOffset.value =
                        (barOffset.value + delta).coerceIn(-hideDistanceState.value, 0f)
                }
                return Offset.Zero
            }

            override suspend fun onPreFling(available: Velocity): Velocity {
                if (refreshOffset <= 0f) return Velocity.Zero
                if (refreshOffset >= refreshTriggerPx && !refreshingState.value) {
                    reloadState.value.invoke()
                    settleJob?.cancel()
                    settleJob = pullScope.launch {
                        // 先收到指示位停住，再等刷新真正结束才弹回。
                        // 用 snapshotFlow 等待而不是只靠 LaunchedEffect(refreshing)：
                        // reload 被忽略（已在刷新中）时 refreshing 不变化，那样会永远停在半空。
                        springOffsetTo(holdPullPx)
                        snapshotFlow { refreshingState.value }.first { !it }
                        springOffsetTo(0f)
                    }
                } else if (!refreshingState.value) {
                    settleTo(0f)
                }
                // 吃掉这段惯性：位移已经消化了手势，再让列表 fling 会突然一跳
                return Velocity(0f, available.y)
            }
        }
    }
    // 回弹全部由 onPreFling 里那个协程负责（先停在指示位、等刷新结束再弹回）。
    // 这里不再放 LaunchedEffect(refreshing)：两个 Animatable 同时驱动同一个位移会互相打架。


    Box(
        Modifier
            .fillMaxSize()
            // 聚焦监听：仅搜索框聚焦时重启生效。按下落在搜索框外就 clearFocus() 收键盘。
            // 不消费事件——点列表项照常选中/播放，同时收起键盘（输入完点歌的直觉行为）；
            // 点空白处则单纯收键盘。落点在框内不动作，保持编辑。
            .pointerInput(searchFocused) {
                if (!searchFocused) return@pointerInput
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    if (!searchBounds.value.contains(down.position)) focusManager.clearFocus()
                }
            }
    ) {
        // ===== 歌单：毛玻璃模糊源，同时作为上栏的折射背板 =====
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .then(
                    if (glass) Modifier.irisBackdropSource(listBackdrop)
                    else if (frosted) Modifier.haze(state = hazeState, style = HazeStyle(blurRadius = frostedBlur))
                    else Modifier
                )
                // 下拉位移作用在歌单本体上，整列跟着手指走。
                // 放在背板/haze 之后（内层）：录进背板层的是已位移的内容，
                // 玻璃取样时才不会和屏幕上看到的错位。
                .graphicsLayer { translationY = refreshOffset }
                .nestedScroll(scrollConnection),
            verticalArrangement = Arrangement.spacedBy(6.dp),
            contentPadding = PaddingValues(
                start = 22.dp, end = 22.dp,
                // 预留上栏空间（状态栏 + barTopGap + 上栏实际高度），第一条歌单不被遮挡
                // barHeight 由 onSizeChanged 实时校准，预留空间自动跟随
                top = with(density) { statusBarHeightDp.toDp() + barTopGap + barHeight.value.toDp() + 8.dp },
                bottom = 50.dp
            )
        ) {
            // 推荐区：歌库非空即显示（推荐基于全库打分，与队列筛选无关），可在设置里关掉
            if (state.allSongs.isNotEmpty() && state.searchQuery.isBlank() && state.showRecommendations) {
                item {
                    // Crossfade 必须让 targetState 携带数据本身，否则淡出的旧内容
                    // 会读到已更新的 state.recommendations，看不出过渡效果。
                    Crossfade(
                        targetState = state.recommendationsVersion to state.recommendations,
                        animationSpec = tween(500, easing = androidx.compose.animation.core.EaseInOut),
                        label = "recommendationFade"
                    ) { (_, recs) ->
                        if (recs.isNotEmpty()) {
                            RecommendationSection(
                                recommendations = recs,
                                colors = colors,
                                onSelect = onPlaySong
                            )
                        } else {
                            // 后台计算中，显示占位
                            Box(
                                Modifier.fillMaxWidth().height(60.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text("推荐中…", color = colors.subText, fontSize = 12.sp)
                            }
                        }
                    }
                }
            }

            itemsIndexed(state.queue, key = { _, song -> "song-${song.id}" }) { index, song ->
                SongRow(
                    song = song,
                    active = index == state.currentIndex,
                    colors = colors,
                    rowSize = state.rowSize,
                    showCover = state.showRowCover,
                    showLikedBadge = state.showLikedBadge,
                    liked = song.id in state.likedSongIds,
                    playlistNames = Playlists.playlistsOfSong(song.id),
                    onClick = { onSelect(index) },
                    onLongPress = { onAddToPlaylist(song) }
                )
            }
        }

        // ===== 快速滚动条：已移到 MainScreen 根层级（见下方），此处移除 =====

        // ===== 下拉刷新指示器 =====
        // 落在「状态栏底部 ↔ 上栏顶边」这段随下拉张开的空隙正中，
        // 所以它不会被跟着下移的上栏压住。
        val refreshProgress = (refreshOffset / refreshTriggerPx).coerceIn(0f, 1f)
        if (refreshOffset > 0.5f) {
            val spin by rememberInfiniteTransition(label = "pullSpin").animateFloat(
                initialValue = 0f,
                targetValue = 360f,
                animationSpec = infiniteRepeatable(tween(900, easing = LinearEasing)),
                label = "pullSpinValue"
            )
            Box(
                Modifier
                    .align(Alignment.TopCenter)
                    .offset {
                        val gap = statusBarHeightDp + with(density) { barTopGap.toPx() } + refreshOffset
                        IntOffset(0, (gap * 0.5f - with(density) { 13.dp.toPx() }).toInt())
                    }
                    .graphicsLayer {
                        alpha = refreshProgress
                        // 到阈值前随进度长大，过阈值轻微过冲，给一点"胀开"的手感
                        val s = 0.6f + 0.5f * refreshProgress
                        scaleX = s; scaleY = s
                    },
                contentAlignment = Alignment.Center
            ) {
                Canvas(Modifier.size(26.dp)) {
                    // 不能写 2.6f * density：外层 LibraryPage 里有个同名的 Density 局部变量，
                    // 会盖住 DrawScope 自己的 density（Float），乘法解析不出来。
                    val stroke = 2.6.dp.toPx()
                    val inset = stroke / 2f
                    val arcSize = Size(size.width - stroke, size.height - stroke)
                    // 底环
                    drawArc(
                        color = colors.primary.copy(alpha = 0.18f),
                        startAngle = 0f, sweepAngle = 360f, useCenter = false,
                        topLeft = Offset(inset, inset),
                        size = arcSize,
                        style = androidx.compose.ui.graphics.drawscope.Stroke(
                            width = stroke,
                            cap = androidx.compose.ui.graphics.StrokeCap.Round
                        )
                    )
                    // 进度弧：刷新中改成定长弧持续旋转
                    drawArc(
                        color = colors.primary,
                        startAngle = if (isRefreshing) spin else -90f,
                        sweepAngle = if (isRefreshing) 100f else 360f * refreshProgress,
                        useCenter = false,
                        topLeft = Offset(inset, inset),
                        size = arcSize,
                        style = androidx.compose.ui.graphics.drawscope.Stroke(
                            width = stroke,
                            cap = androidx.compose.ui.graphics.StrokeCap.Round
                        )
                    )
                }
            }
        }

        // ===== 上栏：覆盖层 =====
        // 整体下移：状态栏下方 barTopGap 处开始，不贴顶部、不盖摄像头
        // tint 压暗/提亮：深色主题压暗、浅色主题提亮，保证与歌单的边界可读性
        // 玻璃模式下上栏走真折射（取样背景 + 歌单），不再叠 haze 与额外 tint
        val barTint = if (colors.isDark) Color.Black.copy(alpha = 0.38f) else Color.White.copy(alpha = 0.5f)
        // 折射背板要在 Modifier 链外先算好：glassSurface 是 @Composable，不能写在 .then{} 里
        val barGlass = if (glass) {
            Modifier.glassSurface(
                spec = GlassLevel.CARD.currentSpec(colors),
                shape = IrisShape.item,
                backdrops = barBackdrops
            )
        } else Modifier
        Column(
            Modifier
                .fillMaxWidth()
                .align(Alignment.TopCenter)
                .offset(y = with(density) { statusBarHeightDp.toDp() + barTopGap })
                .onSizeChanged { barHeight.value = it.height }
                // 上栏跟着下拉一起走，界面整体是一块在动；
                // 系数 0.55 让它比歌单慢一点，两层之间张开一点视差空隙给指示器
                .graphicsLayer { translationY = barOffset.value + refreshOffset * 0.55f }
        ) {
            // 卡片：毛玻璃背景贴合内容（22dp 水平 padding 内），不超出内容区域
            Box(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 22.dp)
                    // 搜索框需要接收完整点击/拖动，不能由上栏外层统一消费触摸。
                     .then(
                        if (glass) {
                            barGlass
                        } else if (frosted) {
                            Modifier.hazeChild(
                                state = hazeState,
                                style = HazeStyle(blurRadius = frostedBlur, tint = barTint),
                                shape = IrisShape.item
                            )
                        } else {
                            Modifier.clip(IrisShape.item).background(colors.surface)
                        }
                    )
            ) {
                GlassContent {
                Column(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 6.dp)
            ) {
                // 标题行
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        // 主色/辅色直接当文字色会翻车：辅色是按"与主色配对好看"挑的，
                        // 没人保证它在上栏底上可读。黑白主题的辅色两边都翻车——浅色模式是
                        // 纯白落在白上栏、深色模式是纯黑落在近黑上栏，对比度都在 1.1 以下。
                        // 基准取 colors.surface：实色模式就是上栏底色；玻璃/毛玻璃下底是半透明的，
                        // 实际会更偏背景色，按不透明底算属于偏保守，宁可修多一点。
                        // 用默认 4.5（正文级）而不是大字号的 3.0：黑白主题修到刚过 3.0 只能得到
                        // 一个发灰的 #8D8D8D，标题该有的力度撑不住。
                        val wordPrimary = colors.primary.readableOn(colors.surface)
                        val wordSecondary = colors.secondary.readableOn(colors.surface)
                        Text("IRIS", color = wordPrimary, fontSize = 17.sp, fontWeight = FontWeight.Black)
                        Spacer(Modifier.width(4.dp))
                        Text("MUSIC", color = wordSecondary, fontSize = 17.sp, fontWeight = FontWeight.Black)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        PlaylistButton(onClick = onOpenPlaylists, colors = colors, active = state.activePlaylistId != null)
                        Spacer(Modifier.width(8.dp))
                        LayoutButton(onClick = onCycleLayout, colors = colors)
                        Spacer(Modifier.width(8.dp))
                        ReportButton(onClick = onReport, colors = colors)
                        Spacer(Modifier.width(8.dp))
                        RefreshButton(onClick = onReload, colors = colors, isRefreshing = state.refreshing)
                        Spacer(Modifier.width(8.dp))
                        SettingsButton(onClick = onSettings, colors = colors)
                    }
                }

                Spacer(Modifier.height(8.dp))

                // 搜索框：灰底 + 放大镜图标，简约统一
                // 液态玻璃下改半透明底，否则实色方块会把整条玻璃切成两半
                val searchBg = if (glass) {
                    if (colors.isDark) Color(0x3DFFFFFF) else Color(0x52FFFFFF)
                } else {
                    if (colors.isDark) Color(0xFF2A2A32) else Color(0xFFE8E8EC)
                }
                val iconTint = colors.subText
                TextField(
                    value = state.searchQuery,
                    onValueChange = onSearch,
                    singleLine = true,
                    interactionSource = searchInteraction,
                    // 记录搜索框在根坐标下的矩形，供聚焦期"点框外收键盘"判定
                    modifier = Modifier
                        .fillMaxWidth()
                        .onGloballyPositioned { searchBounds.value = it.boundsInRoot() },
                    placeholder = { Text("搜索歌曲、艺术家…", color = colors.subText, fontSize = 12.sp) },
                    textStyle = TextStyle(color = colors.text, fontSize = 12.sp, fontWeight = FontWeight.Medium),
                    leadingIcon = {
                        Canvas(Modifier.size(15.dp)) {
                            val r = size.minDimension * 0.32f
                            // 圆心略微偏左上，让手柄指向右下，视觉居中
                            val cx = size.width * 0.38f
                            val cy = size.height * 0.38f
                            drawCircle(
                                color = iconTint,
                                radius = r,
                                center = Offset(cx, cy),
                                style = androidx.compose.ui.graphics.drawscope.Stroke(width = size.minDimension * 0.12f)
                            )
                            drawLine(
                                color = iconTint,
                                start = Offset(cx + r * 0.7f, cy + r * 0.7f),
                                end = Offset(size.width * 0.88f, size.height * 0.88f),
                                strokeWidth = size.minDimension * 0.12f,
                                cap = androidx.compose.ui.graphics.StrokeCap.Round
                            )
                        }
                    },
                    shape = IrisShape.item,
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = searchBg,
                        unfocusedContainerColor = searchBg,
                        disabledContainerColor = searchBg,
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                        disabledIndicatorColor = Color.Transparent,
                        cursorColor = colors.primary,
                        focusedTextColor = colors.text,
                        unfocusedTextColor = colors.text,
                     )
                )

                Spacer(Modifier.height(6.dp))

                // 计数
                val countLabel = when {
                    state.activePlaylistId != null ->
                        state.playlists.firstOrNull { it.id == state.activePlaylistId }?.let { "歌单「${it.name}」· ${state.queue.size} 首" } ?: "${state.queue.size} 首"
                    state.selectedFolders.isNotEmpty() -> "${state.queue.size} 首 · 已选 ${state.selectedFolders.size} 个文件夹"
                    state.onlyLiked -> "我的收藏 · ${state.queue.size} 首"
                    else -> "${state.queue.size} 首"
                }
                Text(
                    countLabel,
                    color = colors.subText,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier
                        .fillMaxWidth()
                        .wrapContentWidth(Alignment.CenterHorizontally)
                )
            }
            }
            }
        }
    }
}

// ==================== 播放器页 ====================

@Composable
private fun PlayerPage(
    state: PlayerUiState,
    colors: IrisColors,
    viewModel: PlayerViewModel,
    onToggle: () -> Unit,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onSeek: (Float) -> Unit,
    onToggleLike: (Long) -> Unit,
    onToggleShuffle: () -> Unit,
    onCycleRepeat: () -> Unit,
    onOpenEqualizer: () -> Unit,
    onOpenSleepTimer: () -> Unit,
    /** 均衡器曲线是否非全平，决定副控制行里那枚推子图标点不点亮 */
    eqActive: Boolean
) {
    // 歌词弹层状态
    var showLyrics by remember { mutableStateOf(false) }
    var lyricLines by remember { mutableStateOf<List<LyricLine>>(emptyList()) }
    var lyricLoading by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    // 歌词弹层毛玻璃：PlayerPage 作为模糊源，弹层作为模糊子层
    val lyricsHazeState = remember { HazeState() }
    val glass = isLiquidGlass
    val frosted = isFrostedGlass
    val frostedBlur = frostedBlurRadius

    // 歌词层浮在播放卡片之上，要折射「背景 + 卡片」两层。卡片单独录一层背板，
    // 歌词层是它的兄弟节点，取样方向单向，不会互相触发重绘。
    val cardBackdrop = rememberIrisBackdrop()
    val outerBackdrops = LocalIrisBackdrops.current
    val lyricsBackdrops = remember(glass, outerBackdrops, cardBackdrop) {
        if (glass) outerBackdrops + cardBackdrop else emptyList()
    }

    // 点击封面：读取歌词（内嵌优先，其次同名 .lrc）并弹出
    // try-catch 防止文件IO异常崩溃；filePath 非空检查避免无效路径
    val onClickArtwork: () -> Unit = {
        val song = state.currentSong
        if (song != null && song.filePath.isNotBlank()) {
            scope.launch {
                lyricLoading = true
                try {
                    val lines = withContext(Dispatchers.IO) {
                        LyricParser.loadLyrics(song.filePath, state.durationMs)
                    }
                    lyricLines = lines
                    showLyrics = true
                } catch (_: Exception) {
                    // 文件读取异常（路径不存在、权限问题等）静默忽略
                } finally {
                    lyricLoading = false
                }
            }
        }
    }

    Box(Modifier.fillMaxSize()) {
        // 播放卡片：毛玻璃模糊源 / 歌词层的折射背板
        Column(
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = 26.dp)
                .then(
                    if (glass) Modifier.irisBackdropSource(cardBackdrop)
                    else if (frosted) Modifier.haze(state = lyricsHazeState, style = HazeStyle(blurRadius = frostedBlur))
                    else Modifier
                ),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            PlayerCard(
                title = state.currentSong?.title ?: "未选择",
                artist = state.currentSong?.artist ?: "",
                positionMs = state.positionMs,
                durationMs = state.durationMs,
                shuffle = state.shuffle,
                repeatMode = state.repeatMode,
                artworkPath = state.currentSong?.filePath,
                progress = state.progress,
                isPlaying = state.isPlaying,
                onToggle = onToggle,
                onPrev = onPrev,
                onNext = onNext,
                onSeek = onSeek,
                onToggleShuffle = onToggleShuffle,
                onCycleRepeat = onCycleRepeat,
                onToggleLike = {
                    state.currentSong?.let { onToggleLike(it.id) }
                },
                liked = state.currentSong?.id in state.likedSongIds,
                onClickArtwork = onClickArtwork,
                colors = colors,
                modifier = Modifier.fillMaxWidth(),
                sleepTimerMs = state.sleepTimerMs,
                sleepTimerEndMs = state.sleepTimerEndMs,
                onOpenSleepTimer = onOpenSleepTimer,
                onOpenEqualizer = onOpenEqualizer,
                eqActive = eqActive,
                audioFormat = state.currentSong?.formatLabel ?: "",
visualizerEnabled = state.visualizerEnabled,
                   tiltSpectrum = state.tiltSpectrum,
                   coverShakeEnabled = state.coverShake,
                   coverLyricEnabled = state.coverLyric,
                   onToggleVisualizer = { viewModel.setVisualizerEnabled(!state.visualizerEnabled) }
              )
        }

        // 歌词弹层：毛玻璃背景 + 滚动歌词
        if (showLyrics) {
            LyricsOverlay(
                lines = lyricLines,
                loading = lyricLoading,
                positionMs = state.positionMs,
                title = state.currentSong?.title ?: "",
                artist = state.currentSong?.artist ?: "",
                colors = colors,
                hazeState = lyricsHazeState,
                backdrops = lyricsBackdrops,
                onDismiss = { showLyrics = false }
            )
        }
    }
}

/** 全屏歌词覆盖层：毛玻璃背景，当前行高亮，随播放进度滚动 */
@Composable
internal fun LyricsOverlay(
    lines: List<LyricLine>,
    loading: Boolean,
    positionMs: Long,
    title: String,
    artist: String,
    colors: IrisColors,
    hazeState: HazeState,
    backdrops: List<IrisBackdrop>,
    onDismiss: () -> Unit
) {
    val listState = rememberLazyListState()
    val current = LyricParser.currentIndex(lines, positionMs)
    val onCard = if (colors.isDark) Color.White else Color.Black
    val glass = isLiquidGlass
    val frosted = isFrostedGlass
    val frostedBlur = frostedBlurRadius
    // 玻璃下减轻遮罩：折射高光还要往上叠一层，遮罩太厚会把封面背景吃掉
    val scrim = if (glass) {
        if (colors.isDark) Color(0x8C121218) else Color(0xA6F4F4F8)
    } else {
        if (colors.isDark) Color(0xB3121218) else Color(0xCCF4F4F8)
    }
    // 玻璃背景：折射「模糊封面 + 播放卡片」，圆角给 0（整屏铺满）
    val lyricsGlass = if (glass) {
        Modifier.glassSurface(
            spec = GlassLevel.SHEET.currentSpec(colors),
            shape = RectangleShape,
            backdrops = backdrops
        )
    } else Modifier

    // 当前行滚动到可视区（居中）
    LaunchedEffect(current) {
        if (current >= 0) {
            listState.animateScrollToItem((current - 1).coerceAtLeast(0))
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .then(
                if (glass) {
                    lyricsGlass
                } else if (frosted) {
                    Modifier.hazeChild(
                        state = hazeState,
                        style = HazeStyle(blurRadius = frostedBlur, tint = scrim),
                        shape = IrisShape.item
                    )
                } else {
                    Modifier.background(scrim)
                }
            )
            // 点任意处关闭
            .clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) { onDismiss() }
            .statusBarsPadding()
            .navigationBarsPadding()
    ) {
        GlassContent {
        Column(
            Modifier
                .fillMaxSize()
                .padding(horizontal = 28.dp)
                .padding(top = 48.dp, bottom = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // 标题 + 作者 合并一行，紧凑
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(title, color = onCard, fontSize = 17.sp, fontWeight = FontWeight.ExtraBold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (artist.isNotBlank()) {
                    Text("  ·  $artist", color = colors.primary, fontSize = 13.sp, fontWeight = FontWeight.Medium,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            Spacer(Modifier.height(10.dp))

            when {
                loading -> Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                    Text("加载歌词…", color = colors.subText, fontSize = 14.sp)
                }
                lines.isEmpty() -> Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                    Text("未找到歌词\n（歌曲内嵌歌词或同目录同名 .lrc）", color = colors.subText,
                        fontSize = 14.sp, textAlign = TextAlign.Center)
                }
                else -> LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                    contentPadding = PaddingValues(vertical = 24.dp)
                ) {
                    // key 用 index 而不是 timeMs：LRC 同一时间戳常有多个文本行，
                    // 纯文本均分时间也可能出现重复 timeMs，重复 key 会让 LazyColumn
                    // 抛 "Key was already used" 直接崩溃（点封面开歌词偶发闪退的根因）。
                    itemsIndexed(lines, key = { index, _ -> "lyric-$index" }) { index, line ->
                        val isCurrent = index == current
                        Text(
                            line.text,
                            color = if (isCurrent) colors.primary else onCard.copy(alpha = 0.62f),
                            fontSize = if (isCurrent) 17.sp else 14.sp,
                            fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }

            Spacer(Modifier.height(8.dp))
            Text("点击任意处关闭", color = colors.subText, fontSize = 11.sp)
        }
        }
    }
}

// ==================== 组件 ====================

@Composable
internal fun SettingsButton(onClick: () -> Unit, colors: IrisColors, plain: Boolean = false) {
    Box(
        Modifier
            .size(34.dp)
            .then(if (plain) Modifier else Modifier.irisSurface(GlassLevel.CHIP, colors, IrisShape.chip))
            .irisPressable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Canvas(Modifier.size(18.dp)) {
            val c = colors.primary
            drawCircle(c, radius = 2.5f, center = Offset(size.width * 0.3f, size.height * 0.3f))
            drawCircle(c, radius = 2.5f, center = Offset(size.width * 0.7f, size.height * 0.3f))
            drawCircle(c, radius = 2.5f, center = Offset(size.width * 0.3f, size.height * 0.7f))
            drawCircle(c, radius = 2.5f, center = Offset(size.width * 0.7f, size.height * 0.7f))
            drawCircle(c, radius = 2.5f, center = Offset(size.width * 0.5f, size.height * 0.5f))
        }
    }
}

/**
 * 歌单入口：三横线列表图标，点击打开歌单管理弹层。
 * 点亮的判断交给外层（有自定义歌单/选中歌单时给个视觉暗示）。
 */
@Composable
internal fun PlaylistButton(onClick: () -> Unit, colors: IrisColors, active: Boolean = false) {
    Box(
        Modifier
            .size(34.dp)
            .irisSurface(GlassLevel.CHIP, colors, IrisShape.chip)
            .irisPressable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Canvas(Modifier.size(17.dp)) {
            val c = if (active) colors.primary else colors.subText
            val lw = size.minDimension * 0.14f
            // 左圆点 + 三条横线（列表 = 带项目标记的清单）
            drawCircle(c, radius = lw * 0.6f, center = Offset(size.width * 0.14f, size.height * 0.2f))
            drawCircle(c, radius = lw * 0.6f, center = Offset(size.width * 0.14f, size.height * 0.5f))
            drawCircle(c, radius = lw * 0.6f, center = Offset(size.width * 0.14f, size.height * 0.8f))
            (0..2).forEach { i ->
                val y = size.height * (0.2f + i * 0.3f)
                drawLine(
                    color = c,
                    start = Offset(size.width * 0.34f, y),
                    end = Offset(size.width * 0.94f, y),
                    strokeWidth = lw,
                    cap = androidx.compose.ui.graphics.StrokeCap.Round
                )
            }
        }
    }
}

/**
 * 听歌报告入口：柱状图图标（三根高低不同的柱子）。
 *
 * 和刷新/设置一样自绘：项目为了体积没引 material-icons-extended，
 * 三根圆头竖线已经足够表达"统计"，不必为一个图标加 5MB 依赖。
 */
@Composable
internal fun ReportButton(onClick: () -> Unit, colors: IrisColors, plain: Boolean = false) {
    Box(
        Modifier
            .size(34.dp)
            .then(if (plain) Modifier else Modifier.irisSurface(GlassLevel.CHIP, colors, IrisShape.chip))
            .irisPressable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Canvas(Modifier.size(17.dp)) {
            val c = colors.primary
            val w = size.width * 0.17f
            // 三根柱子的高度比例：矮-高-中，一眼能看出是图表而不是三条等长杠
            val heights = floatArrayOf(0.45f, 1f, 0.7f)
            heights.forEachIndexed { i, h ->
                val x = size.width * (0.2f + i * 0.3f)
                val barH = size.height * 0.78f * h
                drawLine(
                    color = c,
                    start = Offset(x, size.height * 0.88f),
                    end = Offset(x, size.height * 0.88f - barH),
                    strokeWidth = w,
                    cap = androidx.compose.ui.graphics.StrokeCap.Round
                )
            }
        }
    }
}

@Composable
internal fun RefreshButton(onClick: () -> Unit, colors: IrisColors, isRefreshing: Boolean = false) {
    // 刷新中：无限正转（用 Animatable 累加，不会像 360→0 那样结束瞬间倒转一圈）。
    // 静止时不转。整体绘制思路改为：3/4 圆弧 + 圆环末端一枚小箭头（收在圆周上，不再外突）。
    //
    // 这里不用 rememberInfiniteTransition：它一经创建就永久驻留帧回调，
    // isRefreshing=false 时仍在每帧算角度再被丢弃，白烧一路重组。
    // 改成 Animatable + LaunchedEffect(isRefreshing)，不刷新时协程挂起、零帧开销；
    // 停下时从当前角度平滑归零，而不是硬跳回 0°。
    val spin = remember { Animatable(0f) }
    LaunchedEffect(isRefreshing) {
        if (isRefreshing) {
            spin.animateTo(
                targetValue = spin.value + 3600f,
                animationSpec = infiniteRepeatable(tween(900, easing = LinearEasing))
            )
        } else if (spin.value != 0f) {
            // 收尾：转到下一个整圈再停，避免箭头停在半途
            val nextWhole = kotlin.math.ceil(spin.value / 360f) * 360f
            spin.animateTo(nextWhole, tween(320, easing = FastOutSlowInEasing))
            spin.snapTo(0f)
        }
    }
    val rotation = spin.value
    Box(
        Modifier
            .size(34.dp)
            .irisSurface(GlassLevel.CHIP, colors, IrisShape.chip)
            .irisPressable(enabled = !isRefreshing, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Canvas(Modifier.size(18.dp).graphicsLayer { this.rotationZ = rotation }) {
            val c = colors.primary
            val strokeW = size.minDimension * 0.11f
            // 圆弧：从 -30° 起扫 300°，缺口留在右上——箭头贴着缺口下端画
            val r = size.minDimension / 2f - strokeW / 2f - size.minDimension * 0.04f
            val inset = size.minDimension / 2f - r - strokeW / 2f
            drawArc(
                color = c,
                startAngle = -30f,
                sweepAngle = 300f,
                useCenter = false,
                topLeft = Offset(inset, inset),
                size = Size(size.width - inset * 2, size.height - inset * 2),
                style = androidx.compose.ui.graphics.drawscope.Stroke(
                    width = strokeW,
                    cap = androidx.compose.ui.graphics.StrokeCap.Round
                )
            )
            // 箭头：等腰三角形，顶点在圆弧终点、指向切线方向（顺时针），
            // 两条腿向切线后方 ±30° 张开——这才是标准 Material 刷新箭头
            val cx = size.width / 2f
            val cy = size.height / 2f
            // 圆弧终点角度 = -30 + 300 = 270°（正下方）
            val endRad = Math.toRadians(270.0)
            val ex = cx + (r * Math.cos(endRad)).toFloat()
            val ey = cy + (r * Math.sin(endRad)).toFloat()
            val s = size.minDimension * 0.14f
            // 顺时针切线方向 = 角度方向 + 90°
            val tangent = endRad + Math.PI / 2
            val half = Math.toRadians(30.0) // 顶角 60°，腿长 s
            val p1 = Offset(
                (ex + s * Math.cos(tangent - half)).toFloat(),
                (ey + s * Math.sin(tangent - half)).toFloat()
            )
            val p2 = Offset(
                (ex + s * Math.cos(tangent + half)).toFloat(),
                (ey + s * Math.sin(tangent + half)).toFloat()
            )
            drawLine(c, Offset(ex, ey), p1, strokeWidth = strokeW * 0.9f, cap = androidx.compose.ui.graphics.StrokeCap.Round)
            drawLine(c, Offset(ex, ey), p2, strokeWidth = strokeW * 0.9f, cap = androidx.compose.ui.graphics.StrokeCap.Round)
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SongRow(
    song: Song,
    active: Boolean,
    colors: IrisColors,
    rowSize: Int,
    showCover: Boolean = false,
    showLikedBadge: Boolean = false,
    liked: Boolean = false,
    playlistNames: List<String> = emptyList(),
    onClick: () -> Unit,
    onLongPress: () -> Unit
) {
    // 指示条高度动画：切歌时旧条收缩、新条生长，平滑过渡不瞬移
    val barH by animateDpAsState(if (active) 22.dp else 0.dp, tween(260), label = "cursorBar")
    // 激活态整体渐变：底色、文字色都走 260ms 补间，切歌时行内所有颜色一起过渡
    val activeT by animateFloatAsState(
        targetValue = if (active) 1f else 0f,
        animationSpec = tween(260),
        label = "rowActive"
    )
    val activeBg = lerp(colors.row, colors.secondary.copy(alpha = 0.12f), activeT)
    // 行大小：0=紧凑 1=标准 2=宽松
    val vPad = if (rowSize == 0) 5.dp else if (rowSize == 1) 10.dp else 15.dp
    val titleSize = if (rowSize == 0) 12.sp else if (rowSize == 1) 13.sp else 14.sp
    val subSize = if (rowSize == 0) 10.sp else if (rowSize == 1) 11.sp else 12.sp
    // 封面尺寸
    val coverSize = if (rowSize == 0) 34.dp else if (rowSize == 1) 44.dp else 50.dp
    Row(
        Modifier
            .fillMaxWidth()
            .irisSurface(
                level = GlassLevel.ROW,
                colors = colors,
                shape = IrisShape.item,
                solid = activeBg,
                accent = if (activeT > 0.5f) colors.secondary else null
            )
            .combinedClickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = { Haptics.tap(); onClick() },
                onLongClick = { Haptics.click(); onLongPress() }
            )
            .padding(start = 14.dp, end = 14.dp, top = vPad, bottom = vPad),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 行内指示条：当前播放行显示主色胶囊，天然钉在歌单上，滚动跟随、开APP必显示
        Box(
            Modifier
                .width(4.dp)
                .height(barH)
                .clip(RoundedCornerShape(2.dp))
                .background(colors.primary)
        )
        Spacer(Modifier.width(10.dp))
        if (showCover) {
            SongArtwork(
                filePath = song.filePath,
                seedId = song.id,
                artworkSize = coverSize,
                isDark = colors.isDark
            )
            Spacer(Modifier.width(12.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(song.title, color = lerp(colors.text, colors.primary.readableOn(colors.row, 3.2f), activeT),
                fontSize = titleSize, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(song.artist, color = colors.subText, fontSize = subSize,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(formatDuration(song.durationMs), color = colors.subText, fontSize = subSize, fontWeight = FontWeight.Medium)
            // 所属歌单名：右下角淡显示，多个歌单用「/」分隔
            if (playlistNames.isNotEmpty()) {
                Spacer(Modifier.height(2.dp))
                Text(
                    playlistNames.joinToString(" / "),
                    color = colors.primary.readableOn(colors.row, 3.0f),
                    fontSize = if (rowSize == 0) 9.sp else 10.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            // 点赞角标：弹性放大 + 淡入，不硬出现（赞/取消时同样平滑）
            val badgeT by animateFloatAsState(
                targetValue = if (liked) 1f else 0f,
                animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium),
                label = "likedBadge"
            )
            if (showLikedBadge && badgeT > 0.01f) {
                Spacer(Modifier.height(2.dp))
                Text("♥", color = colors.primary, fontSize = 11.sp, fontWeight = FontWeight.Black,
                    modifier = Modifier.graphicsLayer {
                        alpha = badgeT
                        scaleX = badgeT; scaleY = badgeT
                    })
            }
        }
    }
}

@Composable
internal fun FolderRow(name: String, count: String, active: Boolean, colors: IrisColors, onClick: () -> Unit) {
    // 选中渐变：底色/文字/色块一起补间，切换文件夹不再硬跳
    val t by animateFloatAsState(
        targetValue = if (active) 1f else 0f,
        animationSpec = tween(240),
        label = "folderActive"
    )
    Row(
        Modifier
            .fillMaxWidth()
            .irisSurface(
                level = GlassLevel.ROW,
                colors = colors,
                shape = IrisShape.item,
                solid = lerp(colors.row, colors.primary.copy(alpha = 0.12f), t),
                accent = if (t > 0.5f) colors.primary else null
            )
            .clickable { Haptics.tap(); onClick() }
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(18.dp).clip(IrisShape.chip)
            .background(lerp(colors.card, colors.primary, t)))
        Spacer(Modifier.width(12.dp))
        Text(name, Modifier.weight(1f), color = lerp(colors.text, colors.primary.readableOn(colors.row, 3.2f), t),
            fontSize = 14.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(count, color = colors.subText, fontSize = 11.sp)
    }
}


