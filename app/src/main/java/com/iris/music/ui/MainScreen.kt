package com.iris.music.ui

import androidx.compose.animation.*
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.EaseInOutCubic
import androidx.compose.animation.core.EaseOutCubic
import androidx.compose.animation.core.CubicBezierEasing
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
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.derivedStateOf
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsDraggedAsState
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
import androidx.compose.ui.draw.alpha
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.withStyle
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
import com.iris.music.data.SortDirection
import com.iris.music.data.SortOrder
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
import kotlinx.coroutines.delay
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
    onToggleLike: (Long) -> Unit,
    onCyclePlayMode: () -> Unit,
    onToggleSettings: (Boolean) -> Unit
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

    /**
     * 播放页（Pager 第 2 页）按返回回到库页，而不是退出应用。
     * 只在没有任何浮层时生效——有浮层时上面那个 BackHandler 会先接管。
     */
    val onPlayerPage by remember { derivedStateOf { pagerState.currentPage == 1 } }

    /**
     * 返回键分级处理。
     *
     * 这些二级界面都是状态驱动的浮层而不是独立 Activity/路由，
     * 系统返回键默认直接落到 Activity 上导致退出应用——用户预期是「退回上一级」。
     *
     * 判定顺序 = 视觉堆叠顺序，永远只关最上面那一层：
     * 加歌单 > 歌单管理 > 均衡器 > 睡眠定时 > 收听报告 > 设置 > 播放页。
     * 全都关着时不拦截，交还系统（此时返回 = 退出，符合预期）。
     */
    val hasOverlay = pendingAddSong != null || showPlaylists || showEqualizer ||
        showSleepTimer || state.showReport || state.showSettings
    BackHandler(enabled = hasOverlay) {
        when {
            pendingAddSong != null -> pendingAddSong = null
            showPlaylists -> showPlaylists = false
            showEqualizer -> showEqualizer = false
            showSleepTimer -> showSleepTimer = false
            state.showReport -> viewModel.toggleReport(false)
            state.showSettings -> onToggleSettings(false)
        }
    }

    // 库页 ← 播放页。卡片模式（isDeck）没有 Pager 结构，不适用。
    val backScope = rememberCoroutineScope()
    BackHandler(enabled = !hasOverlay && !state.layout.isDeck && onPlayerPage) {
        backScope.launch { pagerState.animateScrollToPage(0) }
    }

    // 库页列表状态：提升到根层级，滚动条挂在根 Box 上（保证钉在屏幕右缘）
    val libraryListState = rememberLazyListState()
    // 快速滚动条是否正在拖动（拖动期间列表由滑条驱动，上栏需实时跟随）
    val scrollerDragging = remember { mutableStateOf(false) }

    // 上栏计数（几首 / 已选文件夹）：启动时显示 4 秒后自动隐藏，之后不再常驻。
    // 状态放在根层级而不是 LibraryPage：切到播放页再切回来不会重新计时显示。
    var showCountLabel by remember { mutableStateOf(true) }
    LaunchedEffect(Unit) {
        delay(4000)
        showCountLabel = false
    }

    ThemeRevealHost {
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
                    animationSpec = if (IrisMotion.jelly) IrisMotion.appear() else tween(420, easing = androidx.compose.animation.core.EaseInOut),
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
                        onCyclePlayMode = onCyclePlayMode,
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
                             onSortOrder = viewModel::setSortOrder,
                             onSortDirection = viewModel::setSortDirection,
                             onAddToPlaylist = { song -> pendingAddSong = song },
                            scrollerDragging = scrollerDragging,
                            showCount = showCountLabel
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
                            onCyclePlayMode = onCyclePlayMode,
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
                    // 推荐区占据 LazyColumn 的 item 0，因此队列索引需要减去该偏移。
                    // 条件必须与 LibraryPage 里推荐区 item 的渲染条件逐字一致
                    // （recPool 非空 && 无搜索词 && showRecommendations）——
                    // 曾因漏掉 showRecommendations：关掉推荐后列表少一个 item 而这里仍按
                    // 有算，侧边条整体错位一格（气泡显示上一首、定位跳到下一首）。
                    val recPoolNotEmpty = if (state.selectedFolders.isEmpty())
                        state.allSongs.isNotEmpty()
                    else state.allSongs.any { it.folderPath in state.selectedFolders }
                    val headerOffset =
                        if (recPoolNotEmpty && state.searchQuery.isBlank() && state.showRecommendations) 1 else 0
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
                    enter = if (IrisMotion.jelly) {
                        fadeIn(IrisMotion.appear()) + scaleIn(initialScale = 0.96f, animationSpec = IrisMotion.appear())
                    } else fadeIn(tween(200)),
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
                            viewModel = viewModel,
                            onPickBackground = { bgPickerLauncher.launch(arrayOf("image/*")) },
                            sheetDrag = settingsDrag,
                            onDismiss = { onToggleSettings(false) }
                        )
                    }
                }

                // 歌单管理弹层
                AnimatedVisibility(
                    visible = showPlaylists,
                    enter = if (IrisMotion.jelly) {
                        fadeIn(IrisMotion.appear()) + scaleIn(initialScale = 0.96f, animationSpec = IrisMotion.appear())
                    } else fadeIn(tween(200)),
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
                    enter = if (IrisMotion.jelly) {
                        fadeIn(IrisMotion.appear()) + scaleIn(initialScale = 0.96f, animationSpec = IrisMotion.appear())
                    } else fadeIn(tween(200)),
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
                            onAdd = { pid -> viewModel.addToPlaylist(pid, song.id) },
                            // 移出同样不关弹层：状态实时翻转，可连续操作
                            onRemove = { pid -> viewModel.removeFromPlaylist(pid, song.id) }
                        )
                    }
                }

                // 均衡器弹层
                AnimatedVisibility(
                    visible = showEqualizer,
                    enter = if (IrisMotion.jelly) {
                        fadeIn(IrisMotion.appear()) + scaleIn(initialScale = 0.96f, animationSpec = IrisMotion.appear())
                    } else fadeIn(tween(200)),
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
                        spatialWide = state.spatialWide,
                        spatialWideStrength = state.spatialWideStrength,
                        onSpatialWideChange = viewModel::setSpatialWide,
                        onSpatialWideStrengthChange = viewModel::setSpatialWideStrength,
                        safeLimiter = state.safeLimiter,
                        safeLimiterStrength = state.safeLimiterStrength,
                        onSafeLimiterChange = viewModel::setSafeLimiter,
                        onSafeLimiterStrengthChange = viewModel::setSafeLimiterStrength,
                        loudnorm = state.loudnorm,
                        loudnormStrength = state.loudnormStrength,
                        onLoudnormChange = viewModel::setLoudnorm,
                        onLoudnormStrengthChange = viewModel::setLoudnormStrength,
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
                    enter = if (IrisMotion.jelly) {
                        fadeIn(IrisMotion.appear()) + scaleIn(initialScale = 0.96f, animationSpec = IrisMotion.appear())
                    } else fadeIn(tween(200)),
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
                    enter = if (IrisMotion.jelly) {
                        fadeIn(IrisMotion.appear()) + scaleIn(initialScale = 0.96f, animationSpec = IrisMotion.appear())
                    } else fadeIn(tween(200)),
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
}

/**
 * 曲库排序栏：上栏卡片的扩展行（与搜索框同级）。
 *
 * 交互是"点一下切换"，没有弹出选单——Popup 悬浮层无法折射上栏背板、
 * 嵌套圆角和主题适配都容易出缝，循环切换从根上没有这些问题：
 * 左胶囊点击循环 标题→艺术家→时长→修改/添加时间，右胶囊切换 正序/倒序。
 * 胶囊与搜索框同形：同圆角（IrisShape.item）、同底色、同字号。
 */
@Composable
private fun LibrarySortBar(
    order: SortOrder,
    direction: SortDirection,
    colors: IrisColors,
    glass: Boolean,
    onOrderChange: (SortOrder) -> Unit,
    onDirectionChange: (SortDirection) -> Unit
) {
    val chipHeight = 30.dp
    val chipBg = if (glass) {
        if (colors.isDark) Color(0x3DFFFFFF) else Color(0x52FFFFFF)
    } else {
        if (colors.isDark) Color(0xFF2A2A32) else Color(0xFFE8E8EC)
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Box(
            modifier = Modifier
                .height(chipHeight)
                .clip(IrisShape.item)
                .background(chipBg)
                .clickable {
                    Haptics.tap()
                    val next = SortOrder.entries[(order.ordinal + 1) % SortOrder.entries.size]
                    onOrderChange(next)
                }
                .padding(horizontal = 12.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                order.label,
                color = colors.text,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium
            )
        }
        Box(
            modifier = Modifier
                .height(chipHeight)
                .clip(IrisShape.item)
                .background(chipBg)
                .clickable {
                    Haptics.tap()
                    onDirectionChange(
                        if (direction == SortDirection.ASCENDING) SortDirection.DESCENDING
                        else SortDirection.ASCENDING
                    )
                }
                .padding(horizontal = 12.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                if (direction == SortDirection.ASCENDING) "正序 ↑" else "倒序 ↓",
                color = colors.text,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium
            )
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
    onSortOrder: (SortOrder) -> Unit,
    onSortDirection: (SortDirection) -> Unit,
    onAddToPlaylist: (Song) -> Unit,
    showCount: Boolean = true
) {
    // 上栏高度（px）与当前偏移：上滑跟手滑出屏幕，下滑从任意位置随时拉回
    val density = LocalDensity.current
    val statusBarHeightDp = WindowInsets.statusBars.getTop(density)
    val barTopGap = 20.dp // 上栏距离状态栏底部的间距：贴近顶部，不压到屏幕中间
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
    // ===== 搜索框常驻开关 =====
    // 关闭常驻后：搜索框收起，点标题「IRIS MUSIC」展开并自动聚焦；
    // 再点标题收起（有查询词时一并清空回到全库）；聚焦后又闲置且清空了词，几秒后自动收起。
    var searchExpanded by remember { mutableStateOf(false) }
    val searchFocusRequester = remember { FocusRequester() }
    val showSearch = state.searchPersistent || searchExpanded
    // 展开后自动聚焦（等 TextField 进入组合再请求焦点）
    LaunchedEffect(searchExpanded, state.searchPersistent) {
        if (!state.searchPersistent) {
            if (searchExpanded) {
                delay(150)
                runCatching { searchFocusRequester.requestFocus() }
            }
        }
    }
    // 闲置自动收起：非展开开关周期内、未聚焦、无查询词 → 4 秒后收起
    LaunchedEffect(searchExpanded, searchFocused, state.searchQuery, state.searchPersistent) {
        if (!state.searchPersistent && searchExpanded && !searchFocused && state.searchQuery.isBlank()) {
            delay(4000)
            searchExpanded = false
        }
    }
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
    // 排序行是上栏的扩展：上栏几乎完整露出（≈90%）才展开，滚走时随上栏收起。
    // 与搜索框同款"闲置自动收起"：选完排序/方向后短暂停留自动收起，
    // 上栏完全收起再重新拉出时恢复展开。不占列表条目，滚动条索引不受影响。
    val sortBarExposed by remember {
        derivedStateOf { barOffset.value > -hideDistanceState.value * 0.1f }
    }
    var sortBarDismissed by remember { mutableStateOf(false) }
    var sortInteractionTick by remember { mutableIntStateOf(0) }
    LaunchedEffect(sortBarExposed) { if (!sortBarExposed) sortBarDismissed = false }
    LaunchedEffect(sortInteractionTick) {
        if (sortInteractionTick > 0) {
            delay(1400)
            sortBarDismissed = true
        }
    }
    val sortBarVisible = state.showSortBar && sortBarExposed && !sortBarDismissed
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
    // 刷新序列是否进行中（触发的瞬间置位，回弹归零后复位）。
    // 圆圈旋转原本只看 state.refreshing：触发后要先弹簧停到指示位（~300ms），
    // 扫描又快时圆圈"露脸"时 refreshing 已经false——用户看到圆圈整个刷新期间
    // 一动不动（v4.6.15 修复）。旋转与最短驻留改由这个"held"状态驱动。
    var refreshHeld by remember { mutableStateOf(false) }
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
    val heldState = rememberUpdatedState(refreshHeld)
    // 统一触发：置 held（圆圈立即开始转）、弹簧停在指示位、驻留到扫描结束
    // 且至少转满一圈，再回弹。慢速松手与甩动两条触发路径都走这里；
    // reloadLibrary 自带 refreshing 去重，双路径先后调用也只刷一次。
    val runRefresh: () -> Unit = {
        settleJob?.cancel()
        refreshHeld = true
        settleJob = pullScope.launch {
            reloadState.value.invoke()
            springOffsetTo(holdPullPx)
            delay(700)
            snapshotFlow { refreshingState.value }.first { !it }
            springOffsetTo(0f)
            refreshHeld = false
        }
    }
    val runRefreshState = rememberUpdatedState(runRefresh)
    // 慢速松手兜底：拖过阈值后缓慢释放不产生 fling，onPreFling 根本不会被调——
    // 位移晾在半空、不触发也不回弹（"圆圈不转"的主根因）。列表停止滚动时统一收尾。
    LaunchedEffect(Unit) {
        snapshotFlow { listState.isScrollInProgress }
            .distinctUntilChanged()
            .collect { inProgress ->
                if (inProgress) return@collect
                val off = refreshOffset
                if (off > 0f && !refreshingState.value && !heldState.value) {
                    if (off >= refreshTriggerPx) runRefreshState.value.invoke()
                    else settleTo(0f)
                }
            }
    }

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

                // 1) 已经拉出下拉位移：这段手势先把位移收回，不传给列表也不动上栏。
                //    held（刷新序列进行中）时不放行：cancel 会杀掉驱动协程，
                //    位移与 held 状态都会卡在半途。
                if (byFinger && refreshOffset > 0f && delta < 0f && !heldState.value) {
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
                    if (byFinger && !heldState.value) {
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
                // 上栏跟随列表实际滚动量：上滑滑出、下滑拉回。
                // consumed 只含列表自己吃掉的量（不含 onPreScroll 的消费，也不含 overscroll
                // 拉伸），所以到底/到顶硬拖时上栏不会白走一截。惯性阶段同样计入，
                // 否则 fling 时列表飞很远而上栏停着，松手后两者位置就对不上了。
                val delta = consumed.y
                if (delta == 0f) return Offset.Zero
                if (delta > 0f) {
                    // 下滑露出上栏。区分手指与惯性：
                    // - 手指主动下拉（UserInput）：任何位置都一次性动画完整滑出。
                    //   之前按列表消费量逐帧累加，列表在中间能一直回滚吃掉位移，
                    //   上栏每帧只涨一点，单次手势拉不满——得连拉几次才全露。改成
                    //   检测到手指下拉就 settle 到 0，一拉到底。
                    // - fling 惯性：仅在接近顶部（前两条内）才跟手，否则一次长距离甩动会把
                    //   上栏在列表还远没到顶时就整块拉出来提前遮挡内容。
                    val byFinger = source == NestedScrollSource.UserInput
                    if (byFinger) {
                        if (barOffset.value < 0f && barSettleJob?.isActive != true) settleBarTo(0f)
                    } else if (listState.firstVisibleItemIndex <= 1) {
                        barSettleJob?.cancel()
                        barOffset.value = (barOffset.value + delta).coerceIn(-hideDistanceState.value, 0f)
                    }
                } else {
                    // 上滑隐藏：任何位置都 1:1 收起，保证滚动时不挡内容
                    barSettleJob?.cancel()
                    barOffset.value = (barOffset.value + delta).coerceIn(-hideDistanceState.value, 0f)
                }
                return Offset.Zero
            }

            override suspend fun onPreFling(available: Velocity): Velocity {
                if (refreshOffset <= 0f) return Velocity.Zero
                if (refreshOffset >= refreshTriggerPx && !heldState.value) {
                    runRefreshState.value.invoke()
                } else if (!refreshingState.value && !heldState.value) {
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
        // 推荐候选池：跟随选中文件夹。解释卡用的池必须与打分的池同一个，
        // 否则"排名靠前"的口径会和实际排序对不上。
        // 算在 LazyColumn 之外——LazyListScope 的 content 不是 Composable 上下文，
        // 里面不能用 remember。
        val recPool = remember(state.allSongs, state.selectedFolders) {
            val sel = state.selectedFolders
            if (sel.isEmpty()) state.allSongs
            else state.allSongs.filter { it.folderPath in sel }
        }
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
            // 推荐区：候选池非空即显示（池跟随选中文件夹），可在设置里关掉
            if (recPool.isNotEmpty() && state.searchQuery.isBlank() && state.showRecommendations) {
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
                                showArtist = state.showRowArtist,
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
                    showArtist = state.showRowArtist,
                    showPlaylist = state.showRowPlaylist,
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
                    // 进度弧：刷新序列中（held，含扫描已完成但仍在最短驻留期）改成定长弧持续旋转。
                    // 只看 state.refreshing 会因"扫描快于动画"整场不转（v4.6.15 修复）。
                    val spinning = isRefreshing || refreshHeld
                    drawArc(
                        color = colors.primary,
                        startAngle = if (spinning) spin else -90f,
                        sweepAngle = if (spinning) 100f else 360f * refreshProgress,
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
                // 横屏窄化：上栏玻璃与横屏播放卡片同宽（LANDSCAPE_BAR_MAX_WIDTH
                // 已含玻璃外 22dp padding 的补偿）；竖屏 fillMaxWidth 原样。
                // ⚠ 封顶必须在 fillMaxWidth 之外（先 widthIn 再 fillMaxWidth）：
                // 反过来写 fillMaxWidth 会先把外层尺寸定成整屏、minWidth 抵消封顶
                // （同款教训见本页播放卡片注释），上栏变成整屏宽、玻璃靠左，
                // 与居中的 680 播放卡片完全对不齐。
                .then(
                    if (LocalConfiguration.current.orientation ==
                        android.content.res.Configuration.ORIENTATION_LANDSCAPE
                    ) Modifier.widthIn(max = LANDSCAPE_BAR_MAX_WIDTH) else Modifier
                )
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
                    Row(verticalAlignment = Alignment.CenterVertically,
                        // 搜索框收起时点标题展开；已展开时再点收起（有词则清空回全库）
                        modifier = Modifier
                            .clip(IrisShape.item)
                            .clickable(
                                indication = null,
                                interactionSource = remember { MutableInteractionSource() }
                            ) {
                                Haptics.tap()
                                if (state.searchPersistent) return@clickable
                                if (searchExpanded) {
                                    if (state.searchQuery.isNotBlank()) onSearch("")
                                    focusManager.clearFocus()
                                    searchExpanded = false
                                } else {
                                    searchExpanded = true
                                }
                            }
                    ) {
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
                        SettingsButton(onClick = onSettings, colors = colors)
                    }
                }

                // 排序行：上栏的扩展，上栏基本露出后才展开，随上栏收起
                AnimatedVisibility(
                    visible = sortBarVisible,
                    enter = fadeIn(tween(220)) + expandVertically(tween(260, easing = EaseOutCubic)),
                    exit = fadeOut(tween(160)) + shrinkVertically(tween(200, easing = EaseOutCubic))
                ) {
                    Column {
                        Spacer(Modifier.height(6.dp))
                        LibrarySortBar(
                            order = state.sortOrder,
                            direction = state.sortDirection,
                            colors = colors,
                            glass = glass,
                            onOrderChange = {
                                onSortOrder(it)
                                sortInteractionTick++
                            },
                            onDirectionChange = {
                                onSortDirection(it)
                                sortInteractionTick++
                            }
                        )
                    }
                }

                // 搜索框：灰底 + 放大镜图标，简约统一
                // 液态玻璃下改半透明底，否则实色方块会把整条玻璃切成两半
                val searchBg = if (glass) {
                    if (colors.isDark) Color(0x3DFFFFFF) else Color(0x52FFFFFF)
                } else {
                    if (colors.isDark) Color(0xFF2A2A32) else Color(0xFFE8E8EC)
                }
                val iconTint = colors.subText
                AnimatedVisibility(
                    visible = showSearch,
                    enter = fadeIn(tween(220)) + expandVertically(tween(260, easing = EaseOutCubic)),
                    exit = fadeOut(tween(180)) + shrinkVertically(tween(220, easing = EaseOutCubic))
                ) {
                    Column {
                        Spacer(Modifier.height(8.dp))
                TextField(
                    value = state.searchQuery,
                    onValueChange = onSearch,
                    singleLine = true,
                    interactionSource = searchInteraction,
                    // 记录搜索框在根坐标下的矩形，供聚焦期"点框外收键盘"判定
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(searchFocusRequester)
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
                    }
                }

                // 计数：启动后显示 4 秒自动隐藏（showCount 由根层级控制），淡出后收回高度
                AnimatedVisibility(
                    visible = showCount,
                    enter = fadeIn(tween(300)) + expandVertically(tween(300)),
                    exit = fadeOut(tween(400)) + shrinkVertically(tween(400))
                ) {
                    Column {
                        Spacer(Modifier.height(6.dp))
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
    onCyclePlayMode: () -> Unit,
    onOpenEqualizer: () -> Unit,
    onOpenSleepTimer: () -> Unit,
    /** 均衡器曲线是否非全平，决定副控制行里那枚推子图标点不点亮 */
    eqActive: Boolean
) {
    // 歌词弹层状态
    var showLyrics by remember { mutableStateOf(false) }
    var lyricLines by remember { mutableStateOf<List<LyricLine>>(emptyList()) }
    var lyricLoading by remember { mutableStateOf(false) }

    // 歌词层比根层级的那些弹层更靠内：它开着时先关它，不要退出应用。
    // 这个 BackHandler 注册得比 MainScreen 根部那个晚，因此优先级更高，
    // 正好匹配它在视觉上更靠上的层级。
    BackHandler(enabled = showLyrics) { showLyrics = false }
    val scope = rememberCoroutineScope()
    // 歌词层当前显示的歌词属于哪个文件：用于"开着歌词层切歌"时判断需要重新加载。
    var lyricPath by remember { mutableStateOf<String?>(null) }
    // 歌词层开着时自动切歌：旧词必须跟着走，否则整屏还停在上一首。
    // 路径一变先清空再按新歌重解析（LyricParser 自带缓存，重复加载只走内存）。
    LaunchedEffect(showLyrics, state.currentSong?.filePath, state.durationMs) {
        val path = state.currentSong?.filePath
        if (showLyrics && !path.isNullOrBlank() && path != lyricPath) {
            lyricLines = emptyList()
            lyricLoading = true
            try {
                lyricLines = withContext(Dispatchers.IO) {
                    LyricParser.loadLyrics(path, state.durationMs)
                }
                lyricPath = path
            } catch (_: Exception) {
            } finally {
                lyricLoading = false
            }
        }
    }
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
                    lyricPath = song.filePath
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
        // 横屏窄化只在 PlayerCard 内部单层封顶（LANDSCAPE_PLAYER_MAX_WIDTH），
        // 外层不再封顶——双层套娃会把卡片压得比封顶值还窄一圈，
        // 和 CAROUSEL/STACK 横屏的宽度对不齐。列本身水平居中即可。
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
                onCyclePlayMode = onCyclePlayMode,
                onToggleLike = {
                    state.currentSong?.let { onToggleLike(it.id) }
                },
                liked = state.currentSong?.id in state.likedSongIds,
                onClickArtwork = onClickArtwork,
                colors = colors,
                // 宽度完全交给 PlayerCard 内部决定（横屏封顶居中 / 竖屏铺满）。
                // 这里绝不能传 fillMaxWidth——它在 widthIn(max) 之前设置
                // minWidth=屏宽，封顶会被抵消，横屏卡片又变回整屏宽。
                modifier = Modifier,
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
                durationMs = state.durationMs,
                title = state.currentSong?.title ?: "",
                artist = state.currentSong?.artist ?: "",
                colors = colors,
                hazeState = lyricsHazeState,
                backdrops = lyricsBackdrops,
                onSeek = onSeek,
                lyricAlign = state.lyricAlign,
                lyricFont = state.lyricFont,
                lyricFontSize = state.lyricFontSize,
                karaoke = state.karaokeLyric,
                lyricUseCustomColor = state.lyricUseCustomColor,
                lyricTheme = state.lyricTheme,
                lyricCustomPrimary = state.lyricCustomPrimary,
                lyricCustomSecondary = state.lyricCustomSecondary,
                onDismiss = { showLyrics = false }
            )
        }
    }
}

/** 全屏歌词覆盖层：毛玻璃背景，当前行高亮，随播放进度滚动 */
// 歌词专用缓动：起步利落、尾部长长地缓缓收住（easeOutQuint 风格贝塞尔）。
// 比对称的 EaseInOutCubic 更"丝滑"——换行不是机械地加速又减速，
// 而是干脆起步后优雅滑停，这是苹果那类歌词页顺滑感的关键。
private val LyricEase = CubicBezierEasing(0.22f, 1f, 0.36f, 1f)
private const val LYRIC_ANIM_MS = 620

@Composable
internal fun LyricsOverlay(
    lines: List<LyricLine>,
    loading: Boolean,
    positionMs: Long,
    durationMs: Long,
    title: String,
    artist: String,
    colors: IrisColors,
    hazeState: HazeState,
    backdrops: List<IrisBackdrop>,
    onSeek: (Float) -> Unit,
    lyricAlign: Int = 1,
    lyricFont: Int = 0,
    lyricFontSize: Float = 20f,
    karaoke: Boolean = true,
    lyricUseCustomColor: Boolean = false,
    lyricTheme: IrisTheme = IrisTheme.MONO,
    lyricCustomPrimary: Long = 0xFF00F0FFL,
    lyricCustomSecondary: Long = 0xFFFF2E97L,
    onDismiss: () -> Unit
) {
    val listState = rememberLazyListState()
    val current = LyricParser.currentIndex(lines, positionMs)
    val colAlign = when (lyricAlign) { 0 -> Alignment.Start; 2 -> Alignment.End; else -> Alignment.CenterHorizontally }
    val boxAlign = when (lyricAlign) { 0 -> Alignment.CenterStart; 2 -> Alignment.CenterEnd; else -> Alignment.Center }
    val textAlign = when (lyricAlign) { 0 -> TextAlign.Start; 2 -> TextAlign.End; else -> TextAlign.Center }
    val lyricFamily = when (lyricFont) {
        1 -> FontFamily.Serif
        else -> FontFamily.SansSerif
    }
    // 横屏：高度矮，且刘海/挖孔多在左侧——靠左时给更大的左边距避开，
    // 同时压缩列表上下留白、隐藏底部提示，把纵向空间尽量还给歌词。
    val isLand = androidx.compose.ui.platform.LocalConfiguration.current.orientation ==
        android.content.res.Configuration.ORIENTATION_LANDSCAPE
    val onCard = if (colors.isDark) Color.White else Color.Black
    val glass = isLiquidGlass
    val frosted = isFrostedGlass
    val frostedBlur = frostedBlurRadius
    val scrim = if (glass) {
        if (colors.isDark) Color(0x8C121218) else Color(0xA6F4F4F8)
    } else {
        if (colors.isDark) Color(0xB3121218) else Color(0xCCF4F4F8)
    }
    
    // 全屏歌词渐变色计算
    val (lPrimary, lSecondary) = remember(lyricTheme, colors.isDark, lyricCustomPrimary, lyricCustomSecondary) {
        when (lyricTheme) {
            IrisTheme.MONO -> if (colors.isDark) Color.White to Color.Black else Color.Black to Color.White
            IrisTheme.OCEAN -> IrisPalette.NeonBlue to IrisPalette.NeonCyan
            IrisTheme.GRASS -> IrisPalette.NeonGreen to IrisPalette.NeonLime
            IrisTheme.CUSTOM -> Color(lyricCustomPrimary) to Color(lyricCustomSecondary)
            else -> Color.White to Color.Gray
        }
    }
    val lyricBrush = remember(lPrimary, lSecondary) {
        Brush.horizontalGradient(listOf(lPrimary, lSecondary))
    }
    val unsungColor = if (colors.isDark) Color.White.copy(alpha = 0.4f) else Color.Black.copy(alpha = 0.4f)

    val lyricsGlass = if (glass) {
        Modifier.glassSurface(
            spec = GlassLevel.SHEET.currentSpec(colors),
            shape = RectangleShape,
            backdrops = backdrops
        )
    } else Modifier
    val scrubScope = rememberCoroutineScope()
    var scrubHideJob by remember { mutableStateOf<Job?>(null) }
    var showTimeTag by remember { mutableStateOf(false) }
    val focusIndex by remember {
        derivedStateOf {
            val info = listState.layoutInfo
            val vis = info.visibleItemsInfo
            if (vis.isEmpty()) return@derivedStateOf -1
            val focusFrac = if (isLand) 0.42f else 0.38f
            val focusPx = info.viewportStartOffset +
                (info.viewportEndOffset - info.viewportStartOffset) * focusFrac
            vis.minByOrNull { kotlin.math.abs((it.offset + it.size / 2f) - focusPx) }?.index ?: -1
        }
    }
    val scrolling = listState.interactionSource.collectIsDraggedAsState().value
    LaunchedEffect(scrolling) {
        if (scrolling) {
            scrubHideJob?.cancel()
            showTimeTag = true
        } else {
            scrubHideJob?.cancel()
            scrubHideJob = scrubScope.launch {
                delay(900)
                showTimeTag = false
            }
        }
    }
    LaunchedEffect(current) {
        if (current < 0) return@LaunchedEffect
        val scrollSpec = tween<Float>(durationMillis = LYRIC_ANIM_MS, easing = LyricEase)
        val visible = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == current }
        if (visible == null) {
            listState.scrollToItem(current)
        }
        val info = listState.layoutInfo
        val vpStart = info.viewportStartOffset
        val vpEnd = info.viewportEndOffset
        val focusFrac = if (isLand) 0.42f else 0.38f
        val focusLine = vpStart + (vpEnd - vpStart) * focusFrac
        val t = info.visibleItemsInfo.firstOrNull { it.index == current }
        if (t != null) {
            listState.animateScrollBy(t.offset + t.size / 2f - focusLine, scrollSpec)
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
            .clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) { onDismiss() }
            .statusBarsPadding()
            .navigationBarsPadding()
    ) {
        val tagAlpha by animateFloatAsState(
            if (showTimeTag && focusIndex in lines.indices) 1f else 0f,
            animationSpec = tween(180), label = "timeTagAlpha"
        )
        if (focusIndex in lines.indices) {
            val focusFrac = if (isLand) 0.42f else 0.38f
            val onRight = lyricAlign != 2
            val animMs by animateFloatAsState(
                targetValue = lines[focusIndex].timeMs.toFloat(),
                animationSpec = tween(durationMillis = 260, easing = LyricEase),
                label = "timeTagMs"
            )
            val tagText = formatDuration(animMs.toLong())
            BoxWithConstraints(Modifier.fillMaxSize()) {
                val tagY = maxHeight * focusFrac
                Row(
                    Modifier
                        .align(if (onRight) Alignment.TopEnd else Alignment.TopStart)
                        .padding(top = tagY - 7.dp, start = if (onRight) 0.dp else 12.dp, end = if (onRight) 12.dp else 0.dp)
                        .alpha(tagAlpha),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (onRight) {
                        Box(Modifier.width(10.dp).height(2.dp).background(colors.primary))
                        Spacer(Modifier.width(5.dp))
                        Text(tagText,
                            color = colors.primary, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    } else {
                        Text(tagText,
                            color = colors.primary, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.width(5.dp))
                        Box(Modifier.width(10.dp).height(2.dp).background(colors.primary))
                    }
                }
            }
        }
        GlassContent {
        Column(
            Modifier
                .fillMaxSize()
                .padding(horizontal = 28.dp)
                .padding(top = 16.dp, bottom = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(title, color = onCard, fontSize = 17.sp, fontWeight = FontWeight.ExtraBold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (artist.isNotBlank()) {
                    Text("  \u00b7  $artist", color = colors.primary, fontSize = 13.sp, fontWeight = FontWeight.Medium,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            Spacer(Modifier.height(6.dp))
            when {
                loading -> Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                    Text("\u52a0\u8f7d\u6b4c\u8bcd\u2026", color = colors.subText, fontSize = 14.sp)
                }
                lines.isEmpty() -> Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                    Text("\u672a\u627e\u5230\u6b4c\u8bcd\n\uff08\u6b4c\u66f2\u5185\u5d4c\u6b4c\u8bcd\u6216\u540c\u76ee\u5f55\u540c\u540d .lrc\uff09", color = colors.subText,
                        fontSize = 14.sp, textAlign = TextAlign.Center)
                }
                else -> LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(
                            start = if (lyricAlign == 0) (if (isLand) 52.dp else 10.dp) else 0.dp,
                            end = if (lyricAlign == 2) (if (isLand) 52.dp else 10.dp) else 0.dp
                        )
                        .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                        .drawWithContent {
                            drawContent()
                            val fade = 56.dp.toPx()
                            drawRect(
                                brush = Brush.verticalGradient(
                                    0f to Color.Transparent, 1f to Color.Black,
                                    startY = 0f, endY = fade
                                ),
                                blendMode = BlendMode.DstIn
                            )
                            drawRect(
                                brush = Brush.verticalGradient(
                                    0f to Color.Black, 1f to Color.Transparent,
                                    startY = size.height - fade, endY = size.height
                                ),
                                blendMode = BlendMode.DstIn
                            )
                        },
                    verticalArrangement = Arrangement.spacedBy(if (isLand) 14.dp else 18.dp),
                    contentPadding = PaddingValues(vertical = if (isLand) 56.dp else 120.dp)
                ) {
                    itemsIndexed(lines, key = { index, _ -> "lyric-$index" }) { index, line ->
                        val isCurrent = index == current
                        val dist = if (current < 0) 0 else kotlin.math.abs(index - current)
                        val stagger = (dist.coerceAtMost(6)) * 22
                        val scale by animateFloatAsState(
                            targetValue = if (isCurrent) 1f else (0.9f - (dist - 1).coerceAtLeast(0) * 0.015f).coerceAtLeast(0.86f),
                            animationSpec = tween(durationMillis = LYRIC_ANIM_MS, delayMillis = stagger, easing = LyricEase),
                            label = "lyricScale"
                        )
                        val rowAlpha by animateFloatAsState(
                            targetValue = if (isCurrent) 1f else (0.66f - (dist - 1).coerceAtLeast(0) * 0.12f).coerceAtLeast(0.26f),
                            animationSpec = tween(durationMillis = LYRIC_ANIM_MS, delayMillis = stagger, easing = LyricEase),
                            label = "lyricAlpha"
                        )
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .graphicsLayer {
                                    scaleX = scale
                                    scaleY = scale
                                    alpha = rowAlpha
                                    transformOrigin = when (lyricAlign) {
                                        0 -> androidx.compose.ui.graphics.TransformOrigin(0f, 0.5f)
                                        2 -> androidx.compose.ui.graphics.TransformOrigin(1f, 0.5f)
                                        else -> androidx.compose.ui.graphics.TransformOrigin(0.5f, 0.5f)
                                    }
                                }
                                .clickable(
                                    indication = null,
                                    interactionSource = remember { MutableInteractionSource() }
                                ) { onDismiss() }
                                .padding(vertical = if (isCurrent) 6.dp else 0.dp),
                            contentAlignment = boxAlign
                        ) {
                            val seekModifier = Modifier.clickable(
                                indication = null,
                                interactionSource = remember { MutableInteractionSource() }
                            ) {
                                if (durationMs > 0L) {
                                    onSeek((line.timeMs.toFloat() / durationMs).coerceIn(0f, 1f))
                                }
                            }
                            val lineColor by animateColorAsState(
                                targetValue = if (isCurrent) colors.primary else onCard,
                                animationSpec = tween(durationMillis = LYRIC_ANIM_MS, easing = LyricEase),
                                label = "lyricColor"
                            )
                            if (isCurrent && karaoke) {
                                KaraokeLine(
                                    line = line,
                                    positionMs = positionMs,
                                    lineEndMs = lines.getOrNull(index + 1)?.timeMs
                                        ?: (if (durationMs > 0) durationMs else line.timeMs + 4000),
                                    lyricUseCustomColor = lyricUseCustomColor,
                                    themePrimary = lPrimary,
                                    themeSecondary = lSecondary,
                                    originalColor = onCard,
                                    unsungColor = unsungColor,
                                    textAlign = textAlign,
                                    fontFamily = lyricFamily,
                                    fontSize = lyricFontSize,
                                    modifier = seekModifier
                                )
                            } else {
                                val useBrush = if (lyricUseCustomColor) lyricBrush else SolidColor(lineColor)
                                Text(
                                    line.text,
                                    style = androidx.compose.ui.text.TextStyle(
                                        brush = useBrush,
                                        fontSize = lyricFontSize.sp,
                                        fontFamily = lyricFamily,
                                        fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Medium,
                                        textAlign = textAlign
                                    ),
                                    modifier = seekModifier
                                )
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            if (!isLand) Text("\u70b9\u6b4c\u8bcd\u8df3\u8f6c\uff0c\u70b9\u7a7a\u767d\u5904\u5173\u95ed", color = colors.subText, fontSize = 11.sp)
        }
        }
    }
}

@Composable
private fun KaraokeLine(
    line: LyricLine,
    positionMs: Long,
    lineEndMs: Long,
    lyricUseCustomColor: Boolean,
    themePrimary: Color,
    themeSecondary: Color,
    originalColor: Color,
    unsungColor: Color,
    textAlign: TextAlign = TextAlign.Center,
    fontFamily: FontFamily = FontFamily.SansSerif,
    fontSize: Float = 20f,
    modifier: Modifier = Modifier
) {
    val text = line.text
    val baseWall = remember { mutableStateOf(0L) }
    val basePos = remember { mutableStateOf(0L) }
    LaunchedEffect(positionMs) {
        basePos.value = positionMs
        baseWall.value = System.currentTimeMillis()
    }
    var estMs by remember { mutableStateOf(positionMs) }
    LaunchedEffect(line) {
        while (true) {
            withFrameMillis {
                estMs = basePos.value + (System.currentTimeMillis() - baseWall.value)
            }
        }
    }
    val charProgress = if (line.hasWords && text.isNotEmpty()) {
        LyricParser.wordProgressCharsF(line, estMs, lineEndMs).coerceIn(0f, text.length.toFloat())
    } else {
        val span = (lineEndMs - line.timeMs).coerceAtLeast(1L)
        val est = (text.length * 300L).coerceAtLeast(600L)
        val singMs = minOf(est, span).coerceAtLeast((span * 0.92f).toLong())
        val f = ((estMs - line.timeMs).toFloat() / singMs).coerceIn(0f, 1f)
        f * text.length
    }
    val frac = (charProgress / text.length.coerceAtLeast(1)).coerceIn(0f, 1f)
    val soft = 0.10f
    val half = soft / 2f
    val center = frac * (1f + soft) - half
    val start = (center - half).coerceIn(0f, 1f)
    val end = (center + half).coerceIn(0f, 1f)
    
    val sweep: Brush = if (end <= start) {
        if (frac >= 0.5f) {
            if (lyricUseCustomColor) SolidColor(themePrimary) else SolidColor(originalColor)
        } else {
            SolidColor(unsungColor)
        }
    } else {
        val stops = buildList {
            if (lyricUseCustomColor) {
                add(0f to themePrimary)
                if (start > 0f) {
                    val startColor = lerp(themePrimary, themeSecondary, start)
                    add(start to startColor)
                }
                val endColor = lerp(themePrimary, themeSecondary, end)
                add(end to unsungColor)
            } else {
                add(0f to originalColor)
                if (start > 0f) add(start to originalColor)
                add(end to unsungColor)
            }
            if (end < 1f) add(1f to unsungColor)
        }.toTypedArray()
        Brush.horizontalGradient(colorStops = stops)
    }

    val finalBrush: Brush = if (frac >= 0.999f) {
        if (lyricUseCustomColor) {
            Brush.horizontalGradient(listOf(themePrimary, themeSecondary))
        } else {
            SolidColor(originalColor)
        }
    } else {
        sweep
    }
    
    Text(
        text,
        textAlign = textAlign,
        modifier = modifier,
        style = androidx.compose.ui.text.TextStyle(
            brush = finalBrush,
            fontFamily = fontFamily,
            fontSize = fontSize.sp,
            fontWeight = FontWeight.Bold,
            textAlign = textAlign
        )
    )
}

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
    val spin = remember { Animatable(0f) }
    LaunchedEffect(isRefreshing) {
        if (isRefreshing) {
            spin.animateTo(
                targetValue = spin.value + 3600f,
                animationSpec = infiniteRepeatable(tween(900, easing = LinearEasing))
            )
        } else if (spin.value != 0f) {
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
            val cx = size.width / 2f
            val cy = size.height / 2f
            val endRad = Math.toRadians(270.0)
            val ex = cx + (r * Math.cos(endRad)).toFloat()
            val ey = cy + (r * Math.sin(endRad)).toFloat()
            val s = size.minDimension * 0.14f
            val tangent = endRad + Math.PI / 2
            val half = Math.toRadians(30.0)
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
    showArtist: Boolean = true,
    showPlaylist: Boolean = true,
    showLikedBadge: Boolean = false,
    liked: Boolean = false,
    playlistNames: List<String> = emptyList(),
    onClick: () -> Unit,
    onLongPress: () -> Unit
) {
    val barH by animateDpAsState(if (active) 22.dp else 0.dp, IrisMotion.moveDp(), label = "cursorBar")
    val activeT by animateFloatAsState(
        targetValue = if (active) 1f else 0f,
        animationSpec = IrisMotion.move(),
        label = "rowActive"
    )
    val activeBg = lerp(colors.row, colors.secondary.copy(alpha = 0.12f), activeT)
    val vPad = if (rowSize == 0) 5.dp else if (rowSize == 1) 10.dp else 15.dp
    val titleSize = if (rowSize == 0) 12.sp else if (rowSize == 1) 13.sp else 14.sp
    val subSize = if (rowSize == 0) 10.sp else if (rowSize == 1) 11.sp else 12.sp
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
            if (showArtist) {
                Text(song.artist, color = colors.subText, fontSize = subSize,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(formatDuration(song.durationMs), color = colors.subText, fontSize = subSize, fontWeight = FontWeight.Medium)
            if (showPlaylist && playlistNames.isNotEmpty()) {
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
            val badgeT by animateFloatAsState(
                targetValue = if (liked) 1f else 0f,
                animationSpec = if (IrisMotion.jelly) IrisMotion.pressScale() else spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium),
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
