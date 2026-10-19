package com.iris.music.ui

import androidx.compose.animation.*
import androidx.compose.animation.core.*
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
    val bgPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri -> viewModel.setCustomBackground(uri) }
    androidx.compose.runtime.LaunchedEffect(state.jellyAnim) { IrisMotion.jelly = state.jellyAnim }
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
        colorAnim.animateTo(1f, androidx.compose.animation.core.tween(450, easing = androidx.compose.animation.core.EaseInOutCubic)) { mixedColors = lerpIrisColors(from, to, value) }
    }
    val colors = if (colorAnim.value >= 1f) targetColors else mixedColors
    val pagerState = rememberPagerState(pageCount = { 2 })
    val glass = isLiquidGlass
    val bgBackdrop = rememberIrisBackdrop()
    val pageBackdrop = rememberIrisBackdrop()
    val pageBackdrops = remember(glass, bgBackdrop) { if (glass) listOf(bgBackdrop) else emptyList() }
    val sheetBackdrops = remember(glass, bgBackdrop, pageBackdrop) { if (glass) listOf(bgBackdrop, pageBackdrop) else emptyList() }

    var showEqualizer by remember { mutableStateOf(false) }
    val eqState by EqualizerController.state.collectAsState()
    var showSleepTimer by remember { mutableStateOf(false) }
    var showPlaylists by remember { mutableStateOf(false) }
    var pendingAddSong by remember { mutableStateOf<Song?>(null) }
    val onPlayerPage by remember { derivedStateOf { pagerState.currentPage == 1 } }
    val hasOverlay = pendingAddSong != null || showPlaylists || showEqualizer || showSleepTimer || state.showReport || state.showSettings

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

    val backScope = rememberCoroutineScope()
    BackHandler(enabled = !hasOverlay && !state.layout.isDeck && onPlayerPage) { backScope.launch { pagerState.animateScrollToPage(0) } }
    val libraryListState = rememberLazyListState()
    val scrollerDragging = remember { mutableStateOf(false) }
    var showCountLabel by remember { mutableStateOf(true) }
    LaunchedEffect(Unit) { delay(4000); showCountLabel = false }

    Box(Modifier.fillMaxSize().background(colors.background)) {
        AuroraBackground(
            state.currentSong?.filePath,
            isDark = colors.isDark,
            blurRadius = state.bgBlur,
            customUri = state.customBackgroundUri,
            modifier = if (glass) Modifier.irisBackdropSource(bgBackdrop) else Modifier
        )
        CompositionLocalProvider(LocalIrisBackdrops provides pageBackdrops) {
            Crossfade(
                targetState = state.layout,
                animationSpec = if (IrisMotion.jelly) IrisMotion.appear() else tween(420, easing = androidx.compose.animation.core.EaseInOut),
                label = "layoutSwitch"
            ) { layout ->
                if (layout.isDeck) {
                    SongDeck(
                        state = state, colors = colors,
                        modifier = if (glass) Modifier.irisBackdropSource(pageBackdrop) else Modifier,
                        onToggle = onToggle, onPrev = onPrev, onNext = onNext, onSeek = onSeek, onSelect = onSelect,
                        onToggleLike = onToggleLike, onCyclePlayMode = onCyclePlayMode, onToggleVisualizer = { viewModel.setVisualizerEnabled(!state.visualizerEnabled) },
                        onOpenEqualizer = { showEqualizer = true }, onOpenSleepTimer = { showSleepTimer = true }, onOpenReport = { viewModel.toggleReport(true) },
                        onOpenSettings = { onToggleSettings(true) }, onCycleLayout = { viewModel.setLayout(state.layout.next) }, onOpenPlaylists = { showPlaylists = true },
                        onReload = { viewModel.reloadLibrary() }, eqActive = eqState.active
                    )
                } else {
                    HorizontalPager(
                        state = pagerState,
                        modifier = Modifier.fillMaxSize().then(if (glass) Modifier.irisBackdropSource(pageBackdrop) else Modifier)
                    ) { page ->
                        when (page) {
                            0 -> LibraryPage(
                                state = state, colors = colors, listState = libraryListState,
                                onSelect = { index -> onSelect(index) }, onPlaySong = viewModel::playSong, onSearch = onSearch,
                                onSettings = { onToggleSettings(true) }, onReport = { viewModel.toggleReport(true) }, onReload = { viewModel.reloadLibrary() },
                                onCycleLayout = { viewModel.setLayout(state.layout.next) }, onOpenPlaylists = { showPlaylists = true },
                                onSortOrder = viewModel::setSortOrder, onSortDirection = viewModel::setSortDirection,
                                onAddToPlaylist = { song -> pendingAddSong = song }, scrollerDragging = scrollerDragging, showCount = showCountLabel
                            )
                            1 -> PlayerPage(
                                state = state, colors = colors, viewModel = viewModel, onToggle = onToggle, onPrev = onPrev, onNext = onNext, onSeek = onSeek,
                                onToggleLike = onToggleLike, onCyclePlayMode = onCyclePlayMode, onOpenEqualizer = { showEqualizer = true }, onOpenSleepTimer = { showSleepTimer = true }, eqActive = eqState.active
                            )
                        }
                    }
                }
            }
        }

        val scrollerVisible by remember { derivedStateOf { pagerState.currentPage == 0 && !pagerState.isScrollInProgress } }
        val locateScope = rememberCoroutineScope()
        if (!state.layout.isDeck) {
            Column(Modifier.align(Alignment.CenterEnd).fillMaxHeight()) {
                Spacer(Modifier.weight(0.335f))
                Box(Modifier.weight(0.385f)) {
                    val recPoolNotEmpty = if (state.selectedFolders.isEmpty()) state.allSongs.isNotEmpty() else state.allSongs.any { it.folderPath in state.selectedFolders }
                    val headerOffset = if (recPoolNotEmpty && state.searchQuery.isBlank() && state.showRecommendations) 1 else 0
                    FastScroller(
                        listState = libraryListState, colors = colors, topPadding = 0.dp, bottomPadding = 0.dp, visible = scrollerVisible,
                        onClick = { Haptics.tap(); if (state.currentIndex >= 0) { locateScope.launch { libraryListState.animateScrollToItem(state.currentIndex + headerOffset) } } },
                        labelForIndex = { i -> state.queue.getOrNull(i - headerOffset)?.title },
                        onDraggingChange = { scrollerDragging.value = it }
                    )
                }
                Spacer(Modifier.weight(0.28f))
            }
        }

        CompositionLocalProvider(LocalIrisBackdrops provides sheetBackdrops) {
            AnimatedVisibility(visible = state.showSettings, enter = if (IrisMotion.jelly) fadeIn(IrisMotion.appear()) + scaleIn(initialScale = 0.96f, animationSpec = IrisMotion.appear()) else fadeIn(tween(200)), exit = fadeOut(tween(180)), modifier = Modifier.fillMaxSize()) {
                val settingsDrag = rememberSheetDragState()
                IrisSheet(colors = colors, onDismiss = { onToggleSettings(false) }, drag = settingsDrag, panelModifier = Modifier.heightIn(max = 720.dp)) {
                    AnimatedContent(
                        targetState = state.useNewSettingsStyle,
                        transitionSpec = {
                            ((fadeIn(tween(360, delayMillis = 70)) +
                                slideInVertically(tween(500, easing = EaseOutCubic)) { it / 8 }) togetherWith
                                (fadeOut(tween(220)) +
                                    slideOutVertically(tween(240, easing = EaseInCubic)) { -it / 12 }))
                                .using(
                                    SizeTransform(clip = false) { _, _ ->
                                        tween(durationMillis = 500, easing = FastOutSlowInEasing)
                                    }
                                )
                        },
                        label = "settingsStyleSwap"
                    ) { useNew ->
                        if (useNew) {
                            NewSettingsPanel(
                                state = state,
                                colors = colors,
                                viewModel = viewModel,
                                onPickBackground = { bgPickerLauncher.launch(arrayOf("image/*")) },
                                sheetDrag = settingsDrag,
                                onDismiss = { onToggleSettings(false) }
                            )
                        } else {
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
                }
            }
            AnimatedVisibility(visible = showPlaylists, enter = if (IrisMotion.jelly) fadeIn(IrisMotion.appear()) + scaleIn(initialScale = 0.96f, animationSpec = IrisMotion.appear()) else fadeIn(tween(200)), exit = fadeOut(tween(180)), modifier = Modifier.fillMaxSize()) {
                PlaylistSheet(state = state, colors = colors, onDismiss = { showPlaylists = false }, onSelect = { viewModel.selectPlaylist(it) }, onCreate = { name -> viewModel.createPlaylist(name) }, onRename = { id, name -> viewModel.renamePlaylist(id, name) }, onDelete = { viewModel.deletePlaylist(it) })
            }
            AnimatedVisibility(visible = pendingAddSong != null, enter = if (IrisMotion.jelly) fadeIn(IrisMotion.appear()) + scaleIn(initialScale = 0.96f, animationSpec = IrisMotion.appear()) else fadeIn(tween(200)), exit = fadeOut(tween(180))) {
                val song = pendingAddSong
                if (song != null) {
                    AddToPlaylistSheet(song = song, state = state, colors = colors, onDismiss = { pendingAddSong = null }, onCreateAndAdd = { name -> val id = viewModel.createPlaylist(name); viewModel.addToPlaylist(id, song.id); pendingAddSong = null }, onAdd = { pid -> viewModel.addToPlaylist(pid, song.id) }, onRemove = { pid -> viewModel.removeFromPlaylist(pid, song.id) })
                }
            }
            AnimatedVisibility(visible = showEqualizer, enter = if (IrisMotion.jelly) fadeIn(IrisMotion.appear()) + scaleIn(initialScale = 0.96f, animationSpec = IrisMotion.appear()) else fadeIn(tween(200)), exit = fadeOut(tween(180))) {
                EqualizerSheet(
                    state = eqState, onDismiss = { showEqualizer = false }, onSelectBoost = { EqualizerController.selectBoost(it) }, onToggleLoudness = { EqualizerController.setLoudness(it) }, onLoudnessStrengthChange = { EqualizerController.setLoudnessStrength(it) },
                    virtualBass = state.virtualBass, virtualBassStrength = state.virtualBassStrength, onVirtualBassChange = viewModel::setVirtualBass, onVirtualBassStrengthChange = viewModel::setVirtualBassStrength,
                    rangeEnhancer = state.rangeEnhancer, rangeEnhancerStrength = state.rangeEnhancerStrength, onRangeEnhancerChange = viewModel::setRangeEnhancer, onRangeEnhancerStrengthChange = viewModel::setRangeEnhancerStrength,
                    virtualSurround = state.virtualSurround, virtualSurroundStrength = state.virtualSurroundStrength, onVirtualSurroundChange = viewModel::setVirtualSurround, onVirtualSurroundStrengthChange = viewModel::setVirtualSurroundStrength,
                    spatialWide = state.spatialWide, spatialWideStrength = state.spatialWideStrength, onSpatialWideChange = viewModel::setSpatialWide, onSpatialWideStrengthChange = viewModel::setSpatialWideStrength,
                    safeLimiter = state.safeLimiter, safeLimiterStrength = state.safeLimiterStrength, onSafeLimiterChange = viewModel::setSafeLimiter, onSafeLimiterStrengthChange = viewModel::setSafeLimiterStrength,
                    loudnorm = state.loudnorm, loudnormStrength = state.loudnormStrength, onLoudnormChange = viewModel::setLoudnorm, onLoudnormStrengthChange = viewModel::setLoudnormStrength,
                    onSetEditMode = { EqualizerController.setEditMode(it) }, onBandLevel = { band, level -> EqualizerController.setBandLevel(band, level) }, onMoveAnchor = { index, freq, gain -> EqualizerController.moveAnchor(index, freq, gain) }, onCommitAnchors = { EqualizerController.commitAnchors() }, onAddAnchor = { freq, gain -> EqualizerController.addAnchor(freq, gain) }, onRemoveAnchor = { EqualizerController.removeAnchor(it) }, onReset = { EqualizerController.reset() }, colors = colors
                )
            }
            AnimatedVisibility(visible = showSleepTimer, enter = if (IrisMotion.jelly) fadeIn(IrisMotion.appear()) + scaleIn(initialScale = 0.96f, animationSpec = IrisMotion.appear()) else fadeIn(tween(200)), exit = fadeOut(tween(180))) {
                SleepTimerSheet(currentMinutes = if (state.sleepTimerMs > 0) (state.sleepTimerMs / 60000).toInt() else 0, onDismiss = { showSleepTimer = false }, onSetMinutes = { minutes -> if (minutes > 0) viewModel.setSleepTimer(minutes) else viewModel.cancelSleepTimer() }, colors = colors)
            }
            AnimatedVisibility(visible = state.showReport, enter = if (IrisMotion.jelly) fadeIn(IrisMotion.appear()) + scaleIn(initialScale = 0.96f, animationSpec = IrisMotion.appear()) else fadeIn(tween(200)), exit = fadeOut(tween(180))) {
                ListenReportSheet(report = state.report, onDismiss = { viewModel.toggleReport(false) }, onRangeChange = viewModel::setReportRange, onPlaySong = { id -> viewModel.playSongById(id); viewModel.toggleReport(false) }, colors = colors)
            }
        }
    }
}

@Composable
private fun LibrarySortBar(order: SortOrder, direction: SortDirection, colors: IrisColors, glass: Boolean, onOrderChange: (SortOrder) -> Unit, onDirectionChange: (SortDirection) -> Unit) {
    val chipHeight = 30.dp
    val chipBg = if (glass) { if (colors.isDark) Color(0x3DFFFFFF) else Color(0x52FFFFFF) } else { if (colors.isDark) Color(0xFF2A2A32) else Color(0xFFE8E8EC) }
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(modifier = Modifier.height(chipHeight).clip(IrisShape.item).background(chipBg).clickable { Haptics.tap(); val next = SortOrder.entries[(order.ordinal + 1) % SortOrder.entries.size]; onOrderChange(next) }.padding(horizontal = 12.dp), contentAlignment = Alignment.Center) {
            Text(order.label, color = colors.text, fontSize = 12.sp, fontWeight = FontWeight.Medium)
        }
        Box(modifier = Modifier.height(chipHeight).clip(IrisShape.item).background(chipBg).clickable { Haptics.tap(); onDirectionChange(if (direction == SortDirection.ASCENDING) SortDirection.DESCENDING else SortDirection.ASCENDING) }.padding(horizontal = 12.dp), contentAlignment = Alignment.Center) {
            Text(if (direction == SortDirection.ASCENDING) "正序 ↑" else "倒序 ↓", color = colors.text, fontSize = 12.sp, fontWeight = FontWeight.Medium)
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun LibraryPage(
    state: PlayerUiState, colors: IrisColors, listState: androidx.compose.foundation.lazy.LazyListState, scrollerDragging: MutableState<Boolean>,
    onSelect: (Int) -> Unit, onPlaySong: (Song) -> Unit, onSearch: (String) -> Unit, onSettings: () -> Unit, onReport: () -> Unit, onReload: () -> Unit,
    onCycleLayout: () -> Unit, onOpenPlaylists: () -> Unit, onSortOrder: (SortOrder) -> Unit, onSortDirection: (SortDirection) -> Unit, onAddToPlaylist: (Song) -> Unit, showCount: Boolean = true
) {
    val density = LocalDensity.current
    val statusBarHeightDp = WindowInsets.statusBars.getTop(density)
    val barTopGap = 20.dp
    val barSlotHeight = 84.dp
    val barHeight = remember { mutableStateOf(with(density) { barSlotHeight.toPx().toInt() }) }
    val barOffset = remember { mutableStateOf(0f) }
    val hazeState = remember { HazeState() }
    val focusManager = LocalFocusManager.current
    val searchInteraction = remember { MutableInteractionSource() }
    val searchFocused by searchInteraction.collectIsFocusedAsState()
    val searchBounds = remember { mutableStateOf(Rect.Zero) }
    var searchExpanded by remember { mutableStateOf(false) }
    val searchFocusRequester = remember { FocusRequester() }
    val showSearch = state.searchPersistent || searchExpanded
    LaunchedEffect(searchExpanded, state.searchPersistent) { if (!state.searchPersistent) { if (searchExpanded) { delay(150); runCatching { searchFocusRequester.requestFocus() } } } }
    LaunchedEffect(searchExpanded, searchFocused, state.searchQuery, state.searchPersistent) { if (!state.searchPersistent && searchExpanded && !searchFocused && state.searchQuery.isBlank()) { delay(4000); searchExpanded = false } }
    val frosted = isFrostedGlass
    val frostedBlur = frostedBlurRadius
    val glass = isLiquidGlass
    val listBackdrop = rememberIrisBackdrop()
    val outerBackdrops = LocalIrisBackdrops.current
    val barBackdrops = remember(glass, outerBackdrops, listBackdrop) { if (glass) outerBackdrops + listBackdrop else emptyList() }
    val hideDistancePx = statusBarHeightDp + with(density) { barTopGap.toPx() } + barHeight.value
    val hideDistanceState = rememberUpdatedState(hideDistancePx)
    val barScope = rememberCoroutineScope()
    val sortBarExposed by remember { derivedStateOf { barOffset.value > -hideDistanceState.value * 0.1f } }
    var sortBarDismissed by remember { mutableStateOf(false) }
    var sortInteractionTick by remember { mutableIntStateOf(0) }
    LaunchedEffect(sortBarExposed) { if (!sortBarExposed) sortBarDismissed = false }
    LaunchedEffect(sortInteractionTick) { if (sortInteractionTick > 0) { delay(1400); sortBarDismissed = true } }
    val sortBarVisible = state.showSortBar && sortBarExposed && !sortBarDismissed
    var barSettleJob by remember { mutableStateOf<Job?>(null) }
    val settleBarTo: (Float) -> Unit = { target -> barSettleJob?.cancel(); barSettleJob = barScope.launch { Animatable(barOffset.value).animateTo(targetValue = target, animationSpec = spring(dampingRatio = 0.9f, stiffness = Spring.StiffnessMediumLow)) { barOffset.value = value } } }
    LaunchedEffect(listState, scrollerDragging.value) { if (!scrollerDragging.value) return@LaunchedEffect; snapshotFlow { listState.firstVisibleItemIndex }.distinctUntilChanged().collect { index -> barSettleJob?.cancel(); barOffset.value = if (index == 0) 0f else -hideDistanceState.value } }
    LaunchedEffect(listState) { snapshotFlow { listState.firstVisibleItemIndex == 0 && listState.firstVisibleItemScrollOffset == 0 }.distinctUntilChanged().collect { atTop -> if (atTop) settleBarTo(0f) } }
    val listScrollable = listState.canScrollForward || listState.canScrollBackward
    LaunchedEffect(listScrollable) { if (!listScrollable && barOffset.value != 0f) settleBarTo(0f) }

    // === 新增：静止时自动隐藏上栏 ===
    LaunchedEffect(state.topBarAutoHide, listState.isScrollInProgress, barOffset.value) {
        if (state.topBarAutoHide && !listState.isScrollInProgress && barOffset.value == 0f) {
            delay(3000) // 静止 3 秒后隐藏
            if (!listState.isScrollInProgress && barOffset.value == 0f) {
                settleBarTo(-hideDistanceState.value)
            }
        }
    }

    val refreshTriggerPx = with(density) { 96.dp.toPx() }
    val maxPullPx = refreshTriggerPx * 1.7f
    val holdPullPx = refreshTriggerPx * 0.68f
    var refreshOffset by remember { mutableFloatStateOf(0f) }
    var refreshHeld by remember { mutableStateOf(false) }
    val isRefreshing = state.refreshing
    val pullScope = rememberCoroutineScope()
    var settleJob by remember { mutableStateOf<Job?>(null) }
    suspend fun springOffsetTo(target: Float) { Animatable(refreshOffset).animateTo(targetValue = target, animationSpec = spring(dampingRatio = 0.45f, stiffness = Spring.StiffnessLow)) { refreshOffset = value } }
    val settleTo: (Float) -> Unit = { target -> settleJob?.cancel(); settleJob = pullScope.launch { springOffsetTo(target) } }
    val refreshingState = rememberUpdatedState(state.refreshing)
    val reloadState = rememberUpdatedState(onReload)
    val heldState = rememberUpdatedState(refreshHeld)
    val runRefresh: () -> Unit = { settleJob?.cancel(); refreshHeld = true; settleJob = pullScope.launch { reloadState.value.invoke(); springOffsetTo(holdPullPx); delay(700); snapshotFlow { refreshingState.value }.first { !it }; springOffsetTo(0f); refreshHeld = false } }
    val runRefreshState = rememberUpdatedState(runRefresh)
    LaunchedEffect(Unit) { snapshotFlow { listState.isScrollInProgress }.distinctUntilChanged().collect { inProgress -> if (inProgress) return@collect; val off = refreshOffset; if (off > 0f && !refreshingState.value && !heldState.value) { if (off >= refreshTriggerPx) runRefreshState.value.invoke() else settleTo(0f) } } }
    val scrollConnection = remember {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                val delta = available.y
                if (delta == 0f) return Offset.Zero
                val byFinger = source == NestedScrollSource.UserInput
                if (byFinger && refreshOffset > 0f && delta < 0f && !heldState.value) { settleJob?.cancel(); val consumed = delta.coerceAtLeast(-refreshOffset); refreshOffset += consumed; return Offset(0f, consumed) }
                if (delta > 0f && !listState.canScrollBackward) {
                    if (barOffset.value < 0f) { barSettleJob?.cancel(); val used = delta.coerceAtMost(-barOffset.value); barOffset.value += used; return Offset(0f, used) }
                    if (byFinger && !heldState.value) { settleJob?.cancel(); val resistance = (1f - refreshOffset / maxPullPx).coerceIn(0.15f, 1f); refreshOffset = (refreshOffset + delta * resistance).coerceAtMost(maxPullPx); return Offset(0f, delta) }
                }
                return Offset.Zero
            }
            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                val delta = consumed.y
                if (delta == 0f) return Offset.Zero
                if (delta > 0f) {
                    val byFinger = source == NestedScrollSource.UserInput
                    if (byFinger) { if (barOffset.value < 0f && barSettleJob?.isActive != true) settleBarTo(0f) }
                    else if (listState.firstVisibleItemIndex <= 1) { barSettleJob?.cancel(); barOffset.value = (barOffset.value + delta).coerceIn(-hideDistanceState.value, 0f) }
                } else { barSettleJob?.cancel(); barOffset.value = (barOffset.value + delta).coerceIn(-hideDistanceState.value, 0f) }
                return Offset.Zero
            }
            override suspend fun onPreFling(available: Velocity): Velocity {
                if (refreshOffset <= 0f) return Velocity.Zero
                if (refreshOffset >= refreshTriggerPx && !heldState.value) runRefreshState.value.invoke() else if (!refreshingState.value && !heldState.value) settleTo(0f)
                return Velocity(0f, available.y)
            }
        }
    }

    Box(Modifier.fillMaxSize().pointerInput(searchFocused) { if (!searchFocused) return@pointerInput; awaitEachGesture { val down = awaitFirstDown(requireUnconsumed = false); if (!searchBounds.value.contains(down.position)) focusManager.clearFocus() } }) {
        val recPool = remember(state.allSongs, state.selectedFolders) { val sel = state.selectedFolders; if (sel.isEmpty()) state.allSongs else state.allSongs.filter { it.folderPath in sel } }
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize().then(if (glass) Modifier.irisBackdropSource(listBackdrop) else if (frosted) Modifier.haze(state = hazeState, style = HazeStyle(blurRadius = frostedBlur)) else Modifier).graphicsLayer { translationY = refreshOffset }.nestedScroll(scrollConnection),
            verticalArrangement = Arrangement.spacedBy(6.dp),
            contentPadding = PaddingValues(start = 22.dp, end = 22.dp, top = with(density) { statusBarHeightDp.toDp() + barTopGap + barHeight.value.toDp() + 8.dp }, bottom = 50.dp)
        ) {
            if (recPool.isNotEmpty() && state.searchQuery.isBlank() && state.showRecommendations) {
                item {
                    Crossfade(targetState = state.recommendationsVersion to state.recommendations, animationSpec = tween(500, easing = androidx.compose.animation.core.EaseInOut), label = "recommendationFade") { (_, recs) ->
                        if (recs.isNotEmpty()) {
                            RecommendationSection(recommendations = recs, colors = colors, showArtist = state.showRowArtist, onSelect = onPlaySong)
                        } else { Box(Modifier.fillMaxWidth().height(60.dp), contentAlignment = Alignment.Center) { Text("推荐中…", color = colors.subText, fontSize = 12.sp) } }
                    }
                }
            }
            itemsIndexed(state.queue, key = { _, song -> "song-${song.id}" }) { index, song ->
                SongRow(
                    song = song, active = index == state.currentIndex, colors = colors, rowSize = state.rowSize, showCover = state.showRowCover, showArtist = state.showRowArtist, showPlaylist = state.showRowPlaylist, showLikedBadge = state.showLikedBadge, liked = song.id in state.likedSongIds, playlistNames = Playlists.playlistsOfSong(song.id),
                    onClick = { onSelect(index) }, onLongPress = { onAddToPlaylist(song) }
                )
            }
        }
        val refreshProgress = (refreshOffset / refreshTriggerPx).coerceIn(0f, 1f)
        if (refreshOffset > 0.5f) {
            val spin by rememberInfiniteTransition(label = "pullSpin").animateFloat(initialValue = 0f, targetValue = 360f, animationSpec = infiniteRepeatable(tween(900, easing = LinearEasing)), label = "pullSpinValue")
            Box(Modifier.align(Alignment.TopCenter).offset { val gap = statusBarHeightDp + with(density) { barTopGap.toPx() } + refreshOffset; IntOffset(0, (gap * 0.5f - with(density) { 13.dp.toPx() }).toInt()) }.graphicsLayer { alpha = refreshProgress; val s = 0.6f + 0.5f * refreshProgress; scaleX = s; scaleY = s }, contentAlignment = Alignment.Center) {
                Canvas(Modifier.size(26.dp)) {
                    val stroke = 2.6.dp.toPx(); val inset = stroke / 2f; val arcSize = Size(size.width - stroke, size.height - stroke)
                    drawArc(color = colors.primary.copy(alpha = 0.18f), startAngle = 0f, sweepAngle = 360f, useCenter = false, topLeft = Offset(inset, inset), size = arcSize, style = androidx.compose.ui.graphics.drawscope.Stroke(width = stroke, cap = androidx.compose.ui.graphics.StrokeCap.Round))
                    val spinning = isRefreshing || refreshHeld
                    drawArc(color = colors.primary, startAngle = if (spinning) spin else -90f, sweepAngle = if (spinning) 100f else 360f * refreshProgress, useCenter = false, topLeft = Offset(inset, inset), size = arcSize, style = androidx.compose.ui.graphics.drawscope.Stroke(width = stroke, cap = androidx.compose.ui.graphics.StrokeCap.Round))
                }
            }
        }
        val barTint = if (colors.isDark) Color.Black.copy(alpha = 0.38f) else Color.White.copy(alpha = 0.5f)
        val barGlass = if (glass) { Modifier.glassSurface(spec = GlassLevel.CARD.currentSpec(colors), shape = IrisShape.item, backdrops = barBackdrops) } else Modifier
        Column(
            Modifier.then(if (LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE) Modifier.widthIn(max = LANDSCAPE_BAR_MAX_WIDTH) else Modifier)
                .fillMaxWidth().align(Alignment.TopCenter).offset(y = with(density) { statusBarHeightDp.toDp() + barTopGap }).onSizeChanged { barHeight.value = it.height }.graphicsLayer { translationY = barOffset.value + refreshOffset * 0.55f }
        ) {
            Box(Modifier.fillMaxWidth().padding(horizontal = 22.dp).then(if (glass) { barGlass } else if (frosted) { Modifier.hazeChild(state = hazeState, style = HazeStyle(blurRadius = frostedBlur, tint = barTint), shape = IrisShape.item) } else { Modifier.clip(IrisShape.item).background(colors.surface) })) {
                GlassContent {
                    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clip(IrisShape.item).clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) { Haptics.tap(); if (state.searchPersistent) return@clickable; if (searchExpanded) { if (state.searchQuery.isNotBlank()) onSearch(""); focusManager.clearFocus(); searchExpanded = false } else { searchExpanded = true } }) {
                                val wordPrimary = colors.primary.readableOn(colors.surface)
                                val wordSecondary = colors.secondary.readableOn(colors.surface)
                                Text("Vv", color = wordPrimary, fontSize = 17.sp, fontWeight = FontWeight.Black)
                                Spacer(Modifier.width(4.dp))
                                Text("MUSIC", color = wordSecondary, fontSize = 17.sp, fontWeight = FontWeight.Black)
                            }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                PlaylistButton(onClick = onOpenPlaylists, colors = colors, active = state.activePlaylistId != null)
                                Spacer(Modifier.width(8.dp)); LayoutButton(onClick = onCycleLayout, colors = colors)
                                Spacer(Modifier.width(8.dp)); ReportButton(onClick = onReport, colors = colors)
                                Spacer(Modifier.width(8.dp)); SettingsButton(onClick = onSettings, colors = colors)
                            }
                        }
                        AnimatedVisibility(visible = sortBarVisible, enter = fadeIn(tween(220)) + expandVertically(tween(260, easing = EaseOutCubic)), exit = fadeOut(tween(160)) + shrinkVertically(tween(200, easing = EaseOutCubic))) {
                            Column { Spacer(Modifier.height(6.dp)); LibrarySortBar(order = state.sortOrder, direction = state.sortDirection, colors = colors, glass = glass, onOrderChange = { onSortOrder(it); sortInteractionTick++ }, onDirectionChange = { onSortDirection(it); sortInteractionTick++ }) }
                        }
                        val searchBg = if (glass) { if (colors.isDark) Color(0x3DFFFFFF) else Color(0x52FFFFFF) } else { if (colors.isDark) Color(0xFF2A2A32) else Color(0xFFE8E8EC) }
                        val iconTint = colors.subText
                        AnimatedVisibility(visible = showSearch, enter = fadeIn(tween(220)) + expandVertically(tween(260, easing = EaseOutCubic)), exit = fadeOut(tween(180)) + shrinkVertically(tween(220, easing = EaseOutCubic))) {
                            Column {
                                Spacer(Modifier.height(8.dp))
                                TextField(
                                    value = state.searchQuery, onValueChange = onSearch, singleLine = true, interactionSource = searchInteraction,
                                    modifier = Modifier.fillMaxWidth().focusRequester(searchFocusRequester).onGloballyPositioned { searchBounds.value = it.boundsInRoot() },
                                    placeholder = { Text("搜索歌曲、艺术家…", color = colors.subText, fontSize = 12.sp) },
                                    textStyle = TextStyle(color = colors.text, fontSize = 12.sp, fontWeight = FontWeight.Medium),
                                    leadingIcon = { Canvas(Modifier.size(15.dp)) { val r = size.minDimension * 0.32f; val cx = size.width * 0.38f; val cy = size.height * 0.38f; drawCircle(color = iconTint, radius = r, center = Offset(cx, cy), style = androidx.compose.ui.graphics.drawscope.Stroke(width = size.minDimension * 0.12f)); drawLine(color = iconTint, start = Offset(cx + r * 0.7f, cy + r * 0.7f), end = Offset(size.width * 0.88f, size.height * 0.88f), strokeWidth = size.minDimension * 0.12f, cap = androidx.compose.ui.graphics.StrokeCap.Round) } },
                                    shape = IrisShape.item,
                                    colors = TextFieldDefaults.colors(focusedContainerColor = searchBg, unfocusedContainerColor = searchBg, disabledContainerColor = searchBg, focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent, disabledIndicatorColor = Color.Transparent, cursorColor = colors.primary, focusedTextColor = colors.text, unfocusedTextColor = colors.text)
                                )
                            }
                        }
                        AnimatedVisibility(visible = showCount, enter = fadeIn(tween(300)) + expandVertically(tween(300)), exit = fadeOut(tween(400)) + shrinkVertically(tween(400))) {
                            Column {
                                Spacer(Modifier.height(6.dp))
                                val countLabel = when { state.activePlaylistId != null -> state.playlists.firstOrNull { it.id == state.activePlaylistId }?.let { "歌单「${it.name}」· ${state.queue.size} 首" } ?: "${state.queue.size} 首"; state.selectedFolders.isNotEmpty() -> "${state.queue.size} 首 · 已选 ${state.selectedFolders.size} 个文件夹"; state.onlyLiked -> "我的收藏 · ${state.queue.size} 首"; else -> "${state.queue.size} 首" }
                                Text(countLabel, color = colors.subText, fontSize = 11.sp, fontWeight = FontWeight.Medium, modifier = Modifier.fillMaxWidth().wrapContentWidth(Alignment.CenterHorizontally))
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PlayerPage(state: PlayerUiState, colors: IrisColors, viewModel: PlayerViewModel, onToggle: () -> Unit, onPrev: () -> Unit, onNext: () -> Unit, onSeek: (Float) -> Unit, onToggleLike: (Long) -> Unit, onCyclePlayMode: () -> Unit, onOpenEqualizer: () -> Unit, onOpenSleepTimer: () -> Unit, eqActive: Boolean) {
    var showLyrics by remember { mutableStateOf(false) }
    var lyricLines by remember { mutableStateOf<List<LyricLine>>(emptyList()) }
    var lyricLoading by remember { mutableStateOf(false) }
    BackHandler(enabled = showLyrics) { showLyrics = false }
    val scope = rememberCoroutineScope()
    var lyricPath by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(showLyrics, state.currentSong?.filePath, state.durationMs) {
        val path = state.currentSong?.filePath
        if (showLyrics && !path.isNullOrBlank() && path != lyricPath) { lyricLines = emptyList(); lyricLoading = true; try { lyricLines = withContext(Dispatchers.IO) { LyricParser.loadLyrics(path, state.durationMs) }; lyricPath = path } catch (_: Exception) { } finally { lyricLoading = false } }
    }
    val lyricsHazeState = remember { HazeState() }
    val glass = isLiquidGlass
    val frosted = isFrostedGlass
    val frostedBlur = frostedBlurRadius
    val cardBackdrop = rememberIrisBackdrop()
    val outerBackdrops = LocalIrisBackdrops.current
    val lyricsBackdrops = remember(glass, outerBackdrops, cardBackdrop) { if (glass) outerBackdrops + cardBackdrop else emptyList() }
    val onClickArtwork: () -> Unit = {
        val song = state.currentSong
        if (song != null && song.filePath.isNotBlank()) { scope.launch { lyricLoading = true; try { val lines = withContext(Dispatchers.IO) { LyricParser.loadLyrics(song.filePath, state.durationMs) }; lyricLines = lines; lyricPath = song.filePath; showLyrics = true } catch (_: Exception) { } finally { lyricLoading = false } } }
    }
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(horizontal = 26.dp).then(if (glass) Modifier.irisBackdropSource(cardBackdrop) else if (frosted) Modifier.haze(state = lyricsHazeState, style = HazeStyle(blurRadius = frostedBlur)) else Modifier), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            PlayerCard(title = state.currentSong?.title ?: "未选择", artist = state.currentSong?.artist ?: "", positionMs = state.positionMs, durationMs = state.durationMs, shuffle = state.shuffle, repeatMode = state.repeatMode, artworkPath = state.currentSong?.filePath, progress = state.progress, isPlaying = state.isPlaying, onToggle = onToggle, onPrev = onPrev, onNext = onNext, onSeek = onSeek, onCyclePlayMode = onCyclePlayMode, onToggleLike = { state.currentSong?.let { onToggleLike(it.id) } }, liked = state.currentSong?.id in state.likedSongIds, onClickArtwork = onClickArtwork, colors = colors, modifier = Modifier, sleepTimerMs = state.sleepTimerMs, sleepTimerEndMs = state.sleepTimerEndMs, onOpenSleepTimer = onOpenSleepTimer, onOpenEqualizer = onOpenEqualizer, eqActive = eqActive, audioFormat = state.currentSong?.formatLabel ?: "", visualizerEnabled = state.visualizerEnabled, tiltSpectrum = state.tiltSpectrum, coverShakeEnabled = state.coverShake, coverLyricEnabled = state.coverLyric, onToggleVisualizer = { viewModel.setVisualizerEnabled(!state.visualizerEnabled) })
        }
        if (showLyrics) {
            LyricsOverlay(lines = lyricLines, loading = lyricLoading, positionMs = state.positionMs, durationMs = state.durationMs, title = state.currentSong?.title ?: "", artist = state.currentSong?.artist ?: "", colors = colors, hazeState = lyricsHazeState, backdrops = lyricsBackdrops, onSeek = onSeek, lyricAlign = state.lyricAlign, lyricFont = state.lyricFont, lyricFontSize = state.lyricFontSize, karaoke = state.karaokeLyric, lyricUseCustomColor = state.lyricUseCustomColor, lyricTheme = state.lyricTheme, lyricCustomPrimary = state.lyricCustomPrimary, lyricCustomSecondary = state.lyricCustomSecondary, onDismiss = { showLyrics = false })
        }
    }
}

private val LyricEase = CubicBezierEasing(0.22f, 1f, 0.36f, 1f)
private const val LYRIC_ANIM_MS = 620

@Composable
internal fun LyricsOverlay(lines: List<LyricLine>, loading: Boolean, positionMs: Long, durationMs: Long, title: String, artist: String, colors: IrisColors, hazeState: HazeState, backdrops: List<IrisBackdrop>, onSeek: (Float) -> Unit, lyricAlign: Int = 1, lyricFont: Int = 0, lyricFontSize: Float = 20f, karaoke: Boolean = true, lyricUseCustomColor: Boolean = false, lyricTheme: IrisTheme = IrisTheme.MONO, lyricCustomPrimary: Long = 0xFF00F0FFL, lyricCustomSecondary: Long = 0xFFFF2E97L, onDismiss: () -> Unit) {
    val listState = rememberLazyListState()
    val current = LyricParser.currentIndex(lines, positionMs)
    val boxAlign = when (lyricAlign) { 0 -> Alignment.CenterStart; 2 -> Alignment.CenterEnd; else -> Alignment.Center }
    val textAlign = when (lyricAlign) { 0 -> TextAlign.Start; 2 -> TextAlign.End; else -> TextAlign.Center }
    val lyricFamily = when (lyricFont) { 1 -> FontFamily.Serif; else -> FontFamily.SansSerif }
    val isLand = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    val onCard = if (colors.isDark) Color.White else Color.Black
    val glass = isLiquidGlass
    val frosted = isFrostedGlass
    val frostedBlur = frostedBlurRadius
    val scrim = if (glass) { if (colors.isDark) Color(0x8C121218) else Color(0xA6F4F4F8) } else { if (colors.isDark) Color(0xB3121218) else Color(0xCCF4F4F8) }
    val (lPrimary, lSecondary) = remember(lyricTheme, colors.isDark, lyricCustomPrimary, lyricCustomSecondary) { when (lyricTheme) { IrisTheme.MONO -> if (colors.isDark) Color.White to Color.Black else Color.Black to Color.White; IrisTheme.OCEAN -> IrisPalette.NeonBlue to IrisPalette.NeonCyan; IrisTheme.GRASS -> IrisPalette.NeonGreen to IrisPalette.NeonLime; IrisTheme.CUSTOM -> Color(lyricCustomPrimary) to Color(lyricCustomSecondary); else -> Color.White to Color.Gray } }
    val lyricBrush = remember(lPrimary, lSecondary) { Brush.horizontalGradient(listOf(lPrimary, lSecondary)) }
    val unsungColor = if (colors.isDark) Color.White.copy(alpha = 0.4f) else Color.Black.copy(alpha = 0.4f)
    val lyricsGlass = if (glass) { Modifier.glassSurface(spec = GlassLevel.SHEET.currentSpec(colors), shape = RectangleShape, backdrops = backdrops) } else Modifier
    val scrubScope = rememberCoroutineScope()
    var scrubHideJob by remember { mutableStateOf<Job?>(null) }
    var showTimeTag by remember { mutableStateOf(false) }
    val focusIndex by remember { derivedStateOf { val info = listState.layoutInfo; val vis = info.visibleItemsInfo; if (vis.isEmpty()) return@derivedStateOf -1; val focusFrac = if (isLand) 0.42f else 0.38f; val focusPx = info.viewportStartOffset + (info.viewportEndOffset - info.viewportStartOffset) * focusFrac; vis.minByOrNull { kotlin.math.abs((it.offset + it.size / 2f) - focusPx) }?.index ?: -1 } }
    val scrolling = listState.interactionSource.collectIsDraggedAsState().value
    LaunchedEffect(scrolling) { if (scrolling) { scrubHideJob?.cancel(); showTimeTag = true } else { scrubHideJob?.cancel(); scrubHideJob = scrubScope.launch { delay(900); showTimeTag = false } } }
    LaunchedEffect(current) { if (current < 0) return@LaunchedEffect; val scrollSpec = tween<Float>(durationMillis = LYRIC_ANIM_MS, easing = LyricEase); val visible = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == current }; if (visible == null) { listState.scrollToItem(current) }; val info = listState.layoutInfo; val vpStart = info.viewportStartOffset; val vpEnd = info.viewportEndOffset; val focusFrac = if (isLand) 0.42f else 0.38f; val focusLine = vpStart + (vpEnd - vpStart) * focusFrac; val t = info.visibleItemsInfo.firstOrNull { it.index == current }; if (t != null) { listState.animateScrollBy(t.offset + t.size / 2f - focusLine, scrollSpec) } }
    Box(Modifier.fillMaxSize().then(if (glass) { lyricsGlass } else if (frosted) { Modifier.hazeChild(state = hazeState, style = HazeStyle(blurRadius = frostedBlur, tint = scrim), shape = IrisShape.item) } else { Modifier.background(scrim) }).clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) { onDismiss() }.statusBarsPadding().navigationBarsPadding()) {
        val tagAlpha by animateFloatAsState(if (showTimeTag && focusIndex in lines.indices) 1f else 0f, animationSpec = tween(180), label = "timeTagAlpha")
        if (focusIndex in lines.indices) {
            val focusFrac = if (isLand) 0.42f else 0.38f
            val onRight = lyricAlign != 2
            val animMs by animateFloatAsState(targetValue = lines[focusIndex].timeMs.toFloat(), animationSpec = tween(durationMillis = 260, easing = LyricEase), label = "timeTagMs")
            val tagText = formatDuration(animMs.toLong())
            BoxWithConstraints(Modifier.fillMaxSize()) {
                val tagY = maxHeight * focusFrac
                Row(Modifier.align(if (onRight) Alignment.TopEnd else Alignment.TopStart).padding(top = tagY - 7.dp, start = if (onRight) 0.dp else 12.dp, end = if (onRight) 12.dp else 0.dp).alpha(tagAlpha), verticalAlignment = Alignment.CenterVertically) {
                    if (onRight) { Box(Modifier.width(10.dp).height(2.dp).background(colors.primary)); Spacer(Modifier.width(5.dp)); Text(tagText, color = colors.primary, fontSize = 11.sp, fontWeight = FontWeight.Bold) } else { Text(tagText, color = colors.primary, fontSize = 11.sp, fontWeight = FontWeight.Bold); Spacer(Modifier.width(5.dp)); Box(Modifier.width(10.dp).height(2.dp).background(colors.primary)) }
                }
            }
        }
        GlassContent {
            Column(Modifier.fillMaxSize().padding(horizontal = 28.dp).padding(top = 16.dp, bottom = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                    Text(title, color = onCard, fontSize = 17.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (artist.isNotBlank()) { Text("  \u00b7  $artist", color = colors.primary, fontSize = 13.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                }
                Spacer(Modifier.height(6.dp))
                when {
                    loading -> Box(Modifier.weight(1f), contentAlignment = Alignment.Center) { Text("\u52a0\u8f7d\u6b4c\u8bcd\u2026", color = colors.subText, fontSize = 14.sp) }
                    lines.isEmpty() -> Box(Modifier.weight(1f), contentAlignment = Alignment.Center) { Text("\u672a\u627e\u5230\u6b4c\u8bcd\n\uff08\u6b4c\u66f2\u5185\u5d4c\u6b4c\u8bcd\u6216\u540c\u76ee\u5f55\u540c\u540d .lrc\uff09", color = colors.subText, fontSize = 14.sp, textAlign = TextAlign.Center) }
                    else -> LazyColumn(
                        state = listState,
                        modifier = Modifier.weight(1f).fillMaxWidth().padding(start = if (lyricAlign == 0) (if (isLand) 52.dp else 10.dp) else 0.dp, end = if (lyricAlign == 2) (if (isLand) 52.dp else 10.dp) else 0.dp).graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }.drawWithContent {
                            drawContent()
                            val fade = 56.dp.toPx()
                            drawRect(brush = Brush.verticalGradient(0f to Color.Transparent, 1f to Color.Black, startY = 0f, endY = fade), blendMode = BlendMode.DstIn)
                            drawRect(brush = Brush.verticalGradient(0f to Color.Black, 1f to Color.Transparent, startY = size.height - fade, endY = size.height), blendMode = BlendMode.DstIn)
                        },
                        verticalArrangement = Arrangement.spacedBy(if (isLand) 14.dp else 18.dp),
                        contentPadding = PaddingValues(vertical = if (isLand) 56.dp else 120.dp)
                    ) {
                        itemsIndexed(lines, key = { index, _ -> "lyric-$index" }) { index, line ->
                            val isCurrent = index == current
                            val dist = if (current < 0) 0 else kotlin.math.abs(index - current)
                            val stagger = (dist.coerceAtMost(6)) * 22
                            val scale by animateFloatAsState(targetValue = if (isCurrent) 1f else (0.9f - (dist - 1).coerceAtLeast(0) * 0.015f).coerceAtLeast(0.86f), animationSpec = tween(durationMillis = LYRIC_ANIM_MS, delayMillis = stagger, easing = LyricEase), label = "lyricScale")
                            val rowAlpha by animateFloatAsState(targetValue = if (isCurrent) 1f else (0.66f - (dist - 1).coerceAtLeast(0) * 0.12f).coerceAtLeast(0.26f), animationSpec = tween(durationMillis = LYRIC_ANIM_MS, delayMillis = stagger, easing = LyricEase), label = "lyricAlpha")
                            Box(Modifier.fillMaxWidth().graphicsLayer { scaleX = scale; scaleY = scale; alpha = rowAlpha; transformOrigin = when (lyricAlign) { 0 -> androidx.compose.ui.graphics.TransformOrigin(0f, 0.5f); 2 -> androidx.compose.ui.graphics.TransformOrigin(1f, 0.5f); else -> androidx.compose.ui.graphics.TransformOrigin(0.5f, 0.5f) } }.clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) { onDismiss() }.padding(vertical = if (isCurrent) 6.dp else 0.dp), contentAlignment = boxAlign) {
                                val seekModifier = Modifier.clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) { if (durationMs > 0L) { onSeek((line.timeMs.toFloat() / durationMs).coerceIn(0f, 1f)) } }
                                val lineColor by animateColorAsState(targetValue = if (isCurrent) colors.primary else onCard, animationSpec = tween(durationMillis = LYRIC_ANIM_MS, easing = LyricEase), label = "lyricColor")
                                if (isCurrent && karaoke) {
                                    KaraokeLine(line = line, positionMs = positionMs, lineEndMs = lines.getOrNull(index + 1)?.timeMs ?: (if (durationMs > 0) durationMs else line.timeMs + 4000), lyricUseCustomColor = lyricUseCustomColor, themePrimary = lPrimary, themeSecondary = lSecondary, originalColor = onCard, unsungColor = unsungColor, textAlign = textAlign, fontFamily = lyricFamily, fontSize = lyricFontSize, modifier = seekModifier)
                                } else {
                                    val useBrush = if (lyricUseCustomColor) lyricBrush else SolidColor(lineColor)
                                    Text(line.text, style = androidx.compose.ui.text.TextStyle(brush = useBrush, fontSize = lyricFontSize.sp, fontFamily = lyricFamily, fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Medium, textAlign = textAlign), modifier = seekModifier)
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
private fun KaraokeLine(line: LyricLine, positionMs: Long, lineEndMs: Long, lyricUseCustomColor: Boolean, themePrimary: Color, themeSecondary: Color, originalColor: Color, unsungColor: Color, textAlign: TextAlign = TextAlign.Center, fontFamily: FontFamily = FontFamily.SansSerif, fontSize: Float = 20f, modifier: Modifier = Modifier) {
    val text = line.text
    val baseWall = remember { mutableStateOf(0L) }
    val basePos = remember { mutableStateOf(0L) }
    LaunchedEffect(positionMs) { basePos.value = positionMs; baseWall.value = System.currentTimeMillis() }
    var estMs by remember { mutableStateOf(positionMs) }
    LaunchedEffect(line) { while (true) { withFrameMillis { estMs = basePos.value + (System.currentTimeMillis() - baseWall.value) } } }
    val charProgress = if (line.hasWords && text.isNotEmpty()) { LyricParser.wordProgressCharsF(line, estMs, lineEndMs).coerceIn(0f, text.length.toFloat()) } else { val span = (lineEndMs - line.timeMs).coerceAtLeast(1L); val est = (text.length * 300L).coerceAtLeast(600L); val singMs = minOf(est, span).coerceAtLeast((span * 0.92f).toLong()); val f = ((estMs - line.timeMs).toFloat() / singMs).coerceIn(0f, 1f); f * text.length }
    val frac = (charProgress / text.length.coerceAtLeast(1)).coerceIn(0f, 1f)
    val soft = 0.10f
    val half = soft / 2f
    val center = frac * (1f + soft) - half
    val start = (center - half).coerceIn(0f, 1f)
    val end = (center + half).coerceIn(0f, 1f)
    val finalBrush: Brush = if (frac >= 0.999f) { if (lyricUseCustomColor) { Brush.horizontalGradient(listOf(themePrimary, themeSecondary)) } else { SolidColor(originalColor) } } else if (end <= start) { if (frac >= 0.5f) { if (lyricUseCustomColor) SolidColor(themePrimary) else SolidColor(originalColor) } else { SolidColor(unsungColor) } } else {
        val stops = buildList {
            if (lyricUseCustomColor) { add(0f to themePrimary); if (start > 0f) { val startColor = lerp(themePrimary, themeSecondary, start); add(start to startColor) }; val endColor = lerp(themePrimary, themeSecondary, end); add(end to unsungColor) }
            else { add(0f to originalColor); if (start > 0f) add(start to originalColor); add(end to unsungColor) }
            if (end < 1f) add(1f to unsungColor)
        }.toTypedArray()
        Brush.horizontalGradient(colorStops = stops)
    }
    Text(text, textAlign = textAlign, modifier = modifier, style = androidx.compose.ui.text.TextStyle(brush = finalBrush, fontFamily = fontFamily, fontSize = fontSize.sp, fontWeight = FontWeight.Bold, textAlign = textAlign))
}

@Composable
internal fun SettingsButton(onClick: () -> Unit, colors: IrisColors, plain: Boolean = false) {
    Box(Modifier.size(34.dp).then(if (plain) Modifier else Modifier.irisSurface(GlassLevel.CHIP, colors, IrisShape.chip)).irisPressable(onClick = onClick), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(18.dp)) { val c = colors.primary; drawCircle(c, radius = 2.5f, center = Offset(size.width * 0.3f, size.height * 0.3f)); drawCircle(c, radius = 2.5f, center = Offset(size.width * 0.7f, size.height * 0.3f)); drawCircle(c, radius = 2.5f, center = Offset(size.width * 0.3f, size.height * 0.7f)); drawCircle(c, radius = 2.5f, center = Offset(size.width * 0.7f, size.height * 0.7f)); drawCircle(c, radius = 2.5f, center = Offset(size.width * 0.5f, size.height * 0.5f)) }
    }
}

@Composable
internal fun PlaylistButton(onClick: () -> Unit, colors: IrisColors, active: Boolean = false) {
    Box(Modifier.size(34.dp).irisSurface(GlassLevel.CHIP, colors, IrisShape.chip).irisPressable(onClick = onClick), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(17.dp)) { val c = if (active) colors.primary else colors.subText; val lw = size.minDimension * 0.14f; drawCircle(c, radius = lw * 0.6f, center = Offset(size.width * 0.14f, size.height * 0.2f)); drawCircle(c, radius = lw * 0.6f, center = Offset(size.width * 0.14f, size.height * 0.5f)); drawCircle(c, radius = lw * 0.6f, center = Offset(size.width * 0.14f, size.height * 0.8f)); (0..2).forEach { i -> val y = size.height * (0.2f + i * 0.3f); drawLine(color = c, start = Offset(size.width * 0.34f, y), end = Offset(size.width * 0.94f, y), strokeWidth = lw, cap = androidx.compose.ui.graphics.StrokeCap.Round) } }
    }
}

@Composable
internal fun ReportButton(onClick: () -> Unit, colors: IrisColors, plain: Boolean = false) {
    Box(Modifier.size(34.dp).then(if (plain) Modifier else Modifier.irisSurface(GlassLevel.CHIP, colors, IrisShape.chip)).irisPressable(onClick = onClick), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(17.dp)) { val c = colors.primary; val w = size.width * 0.17f; val heights = floatArrayOf(0.45f, 1f, 0.7f); heights.forEachIndexed { i, h -> val x = size.width * (0.2f + i * 0.3f); val barH = size.height * 0.78f * h; drawLine(color = c, start = Offset(x, size.height * 0.88f), end = Offset(x, size.height * 0.88f - barH), strokeWidth = w, cap = androidx.compose.ui.graphics.StrokeCap.Round) } }
    }
}

@Composable
internal fun RefreshButton(onClick: () -> Unit, colors: IrisColors, isRefreshing: Boolean = false) {
    val spin = remember { Animatable(0f) }
    LaunchedEffect(isRefreshing) { if (isRefreshing) { spin.animateTo(targetValue = spin.value + 3600f, animationSpec = infiniteRepeatable(tween(900, easing = LinearEasing))) } else if (spin.value != 0f) { val nextWhole = kotlin.math.ceil(spin.value / 360f) * 360f; spin.animateTo(nextWhole, tween(320, easing = FastOutSlowInEasing)); spin.snapTo(0f) } }
    val rotation = spin.value
    Box(Modifier.size(34.dp).irisSurface(GlassLevel.CHIP, colors, IrisShape.chip).irisPressable(enabled = !isRefreshing, onClick = onClick), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(18.dp).graphicsLayer { this.rotationZ = rotation }) {
            val c = colors.primary; val strokeW = size.minDimension * 0.11f; val r = size.minDimension / 2f - strokeW / 2f - size.minDimension * 0.04f; val inset = size.minDimension / 2f - r - strokeW / 2f
            drawArc(color = c, startAngle = -30f, sweepAngle = 300f, useCenter = false, topLeft = Offset(inset, inset), size = Size(size.width - inset * 2, size.height - inset * 2), style = androidx.compose.ui.graphics.drawscope.Stroke(width = strokeW, cap = androidx.compose.ui.graphics.StrokeCap.Round))
            val cx = size.width / 2f; val cy = size.height / 2f; val endRad = Math.toRadians(270.0); val ex = cx + (r * Math.cos(endRad)).toFloat(); val ey = cy + (r * Math.sin(endRad)).toFloat(); val s = size.minDimension * 0.14f; val tangent = endRad + Math.PI / 2; val half = Math.toRadians(30.0)
            val p1 = Offset((ex + s * Math.cos(tangent - half)).toFloat(), (ey + s * Math.sin(tangent - half)).toFloat()); val p2 = Offset((ex + s * Math.cos(tangent + half)).toFloat(), (ey + s * Math.sin(tangent + half)).toFloat())
            drawLine(c, Offset(ex, ey), p1, strokeWidth = strokeW * 0.9f, cap = androidx.compose.ui.graphics.StrokeCap.Round); drawLine(c, Offset(ex, ey), p2, strokeWidth = strokeW * 0.9f, cap = androidx.compose.ui.graphics.StrokeCap.Round)
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SongRow(song: Song, active: Boolean, colors: IrisColors, rowSize: Int, showCover: Boolean = false, showArtist: Boolean = true, showPlaylist: Boolean = true, showLikedBadge: Boolean = false, liked: Boolean = false, playlistNames: List<String> = emptyList(), onClick: () -> Unit, onLongPress: () -> Unit) {
    val barH by animateDpAsState(if (active) 22.dp else 0.dp, IrisMotion.moveDp(), label = "cursorBar")
    val activeT by animateFloatAsState(targetValue = if (active) 1f else 0f, animationSpec = IrisMotion.move(), label = "rowActive")
    val activeBg = lerp(colors.row, colors.secondary.copy(alpha = 0.12f), activeT)
    val vPad = if (rowSize == 0) 5.dp else if (rowSize == 1) 10.dp else 15.dp
    val titleSize = if (rowSize == 0) 12.sp else if (rowSize == 1) 13.sp else 14.sp
    val subSize = if (rowSize == 0) 10.sp else if (rowSize == 1) 11.sp else 12.sp
    val coverSize = if (rowSize == 0) 34.dp else if (rowSize == 1) 44.dp else 50.dp
    Row(Modifier.fillMaxWidth().irisSurface(level = GlassLevel.ROW, colors = colors, shape = IrisShape.item, solid = activeBg, accent = if (activeT > 0.5f) colors.secondary else null).combinedClickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = { Haptics.tap(); onClick() }, onLongClick = { Haptics.click(); onLongPress() }).padding(start = 14.dp, end = 14.dp, top = vPad, bottom = vPad), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.width(4.dp).height(barH).clip(RoundedCornerShape(2.dp)).background(colors.primary))
        Spacer(Modifier.width(10.dp))
        if (showCover) { SongArtwork(filePath = song.filePath, seedId = song.id, artworkSize = coverSize, isDark = colors.isDark); Spacer(Modifier.width(12.dp)) }
        Column(Modifier.weight(1f)) {
            Text(song.title, color = lerp(colors.text, colors.primary.readableOn(colors.row, 3.2f), activeT), fontSize = titleSize, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (showArtist) { Text(song.artist, color = colors.subText, fontSize = subSize, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(formatDuration(song.durationMs), color = colors.subText, fontSize = subSize, fontWeight = FontWeight.Medium)
            if (showPlaylist && playlistNames.isNotEmpty()) { Spacer(Modifier.height(2.dp)); Text(playlistNames.joinToString(" / "), color = colors.primary.readableOn(colors.row, 3.0f), fontSize = if (rowSize == 0) 9.sp else 10.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            val badgeT by animateFloatAsState(targetValue = if (liked) 1f else 0f, animationSpec = if (IrisMotion.jelly) IrisMotion.pressScale() else spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium), label = "likedBadge")
            if (showLikedBadge && badgeT > 0.01f) { Spacer(Modifier.height(2.dp)); Text("♥", color = colors.primary, fontSize = 11.sp, fontWeight = FontWeight.Black, modifier = Modifier.graphicsLayer { alpha = badgeT; scaleX = badgeT; scaleY = badgeT }) }
        }
    }
}

@Composable
internal fun FolderRow(name: String, count: String, active: Boolean, colors: IrisColors, onClick: () -> Unit) {
    val t by animateFloatAsState(targetValue = if (active) 1f else 0f, animationSpec = tween(240), label = "folderActive")
    Row(Modifier.fillMaxWidth().irisSurface(level = GlassLevel.ROW, colors = colors, shape = IrisShape.item, solid = lerp(colors.row, colors.primary.copy(alpha = 0.12f), t), accent = if (t > 0.5f) colors.primary else null).clickable { Haptics.tap(); onClick() }.padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(18.dp).clip(IrisShape.chip).background(lerp(colors.card, colors.primary, t)))
        Spacer(Modifier.width(12.dp))
        Text(name, Modifier.weight(1f), color = lerp(colors.text, colors.primary.readableOn(colors.row, 3.2f), t), fontSize = 14.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(count, color = colors.subText, fontSize = 11.sp)
    }
}
