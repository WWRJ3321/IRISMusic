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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.platform.LocalContext
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
import coil.compose.AsyncImage
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
import com.iris.music.player.MusicComment
import com.iris.music.player.PlayerUiState
import com.iris.music.player.PlayerViewModel
import com.iris.music.player.TopListInfo
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
    onToggleSettings: (Boolean) -> Unit,
    modifier: Modifier = Modifier
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
    
    // ===== 新增：弹窗状态 =====
    var showTopListSheet by remember { mutableStateOf(false) }
    var showCommentsSheet by remember { mutableStateOf(false) }

    val onPlayerPage by remember { derivedStateOf { pagerState.currentPage == 1 } }
    val hasOverlay = pendingAddSong != null || showPlaylists || showEqualizer || showSleepTimer || state.showReport || state.showSettings || showTopListSheet || showCommentsSheet

    BackHandler(enabled = hasOverlay) {
        when {
            showCommentsSheet -> showCommentsSheet = false
            showTopListSheet -> showTopListSheet = false
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

    Box(modifier.fillMaxSize().background(colors.background)) {
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
                                onAddToPlaylist = { song -> 
                                    // 在线模式长按删除，本地模式长按加歌单
                                    if (state.isOnlineMode) viewModel.removeOnlineSong(song.id) else pendingAddSong = song
                                }, 
                                onOpenTopLists = { showTopListSheet = true },
                                scrollerDragging = scrollerDragging, showCount = showCountLabel
                            )
                            1 -> PlayerPage(
                                state = state, colors = colors, viewModel = viewModel, onToggle = onToggle, onPrev = onPrev, onNext = onNext, onSeek = onSeek,
                                onToggleLike = onToggleLike, onCyclePlayMode = onCyclePlayMode, onOpenEqualizer = { showEqualizer = true }, onOpenSleepTimer = { showSleepTimer = true }, 
                                eqActive = eqState.active, 
                                onOpenComments = { showCommentsSheet = true }
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
                            ((fadeIn(tween(360, delayMillis = 70)) + slideInVertically(tween(500, easing = EaseOutCubic)) { it / 8 }) togetherWith (fadeOut(tween(220)) + slideOutVertically(tween(240, easing = EaseInCubic)) { -it / 12 }))
                                .using(SizeTransform(clip = false) { _, _ -> tween(durationMillis = 500, easing = FastOutSlowInEasing) })
                        },
                        label = "settingsStyleSwap"
                    ) { useNew ->
                        if (useNew) {
                            NewSettingsPanel(state, colors, viewModel, { bgPickerLauncher.launch(arrayOf("image/*")) }, settingsDrag) { onToggleSettings(false) }
                        } else {
                            SettingsPanel(state, colors, viewModel, { bgPickerLauncher.launch(arrayOf("image/*")) }, settingsDrag) { onToggleSettings(false) }
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
            
            // ===== 排行榜弹窗 =====
            if (showTopListSheet) {
                ModalBottomSheet(onDismissRequest = { showTopListSheet = false }, containerColor = colors.background, contentColor = colors.text, scrimColor = Color.Black.copy(alpha = 0.5f)) {
                    Column(Modifier.padding(16.dp)) {
                        Text("官方排行榜", fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 16.dp))
                        LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            items(state.topLists.size) { index ->
                                val topList = state.topLists[index]
                                Row(Modifier.fillMaxWidth().clickable { viewModel.playTopList(topList.id); showTopListSheet = false }.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                    AsyncImage(model = topList.coverImgUrl, contentDescription = null, modifier = Modifier.size(60.dp).clip(RoundedCornerShape(8.dp)), contentScale = ContentScale.Crop)
                                    Spacer(Modifier.width(16.dp))
                                    Column {
                                        Text(topList.name, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                                        Text(topList.updateFrequency, fontSize = 12.sp, color = colors.subText)
                                    }
                                }
                            }
                        }
                        Spacer(Modifier.height(32.dp))
                    }
                }
            }

            // ===== 评论区弹窗 =====
            if (showCommentsSheet) {
                ModalBottomSheet(onDismissRequest = { showCommentsSheet = false }, containerColor = colors.background, contentColor = colors.text, scrimColor = Color.Black.copy(alpha = 0.5f)) {
                    Column(Modifier.padding(16.dp).fillMaxHeight(0.8f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("评论 (${state.currentCommentCount})", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                            Spacer(Modifier.weight(1f))
                            if (state.isCommentsLoading) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        }
                        Spacer(Modifier.height(16.dp))
                        LazyColumn(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                            items(state.currentComments.size) { index ->
                                val comment = state.currentComments[index]
                                Row {
                                    AsyncImage(model = comment.avatarUrl, contentDescription = null, modifier = Modifier.size(36.dp).clip(CircleShape).background(colors.subText), contentScale = ContentScale.Crop)
                                    Spacer(Modifier.width(12.dp))
                                    Column {
                                        Text(comment.nickname, fontSize = 14.sp, color = colors.subText)
                                        Spacer(Modifier.height(4.dp))
                                        Text(comment.content, fontSize = 15.sp)
                                        Spacer(Modifier.height(4.dp))
                                        Text("❤️ ${comment.likedCount}", fontSize = 12.sp, color = colors.subText)
                                    }
                                }
                            }
                        }
                    }
                }
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
    onCycleLayout: () -> Unit, onOpenPlaylists: () -> Unit, onSortOrder: (SortOrder) -> Unit, onSortDirection: (SortDirection) -> Unit, onAddToPlaylist: (Song) -> Unit, 
    onOpenTopLists: () -> Unit, showCount: Boolean = true
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

    LaunchedEffect(state.topBarAutoHide, listState.isScrollInProgress, barOffset.value) {
        if (state.topBarAutoHide && !listState.isScrollInProgress && barOffset.value == 0f) {
            delay(3000)
            if (!listState.isScrollInProgress && barOffset.value == 0f) settleBarTo(-hideDistanceState.value)
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
        val displayList = if (state.isOnlineMode) {
            if (state.searchQuery.isNotBlank()) state.onlineSearchResults else state.queue
        } else {
            state.queue
        }

        if (state.isOnlineMode && displayList.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(top = 120.dp), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    if (state.onlineSearchLoading) {
                        CircularProgressIndicator(color = colors.primary, modifier = Modifier.size(32.dp))
                        Spacer(Modifier.height(16.dp))
                        Text("正在全网搜索…", color = colors.subText, fontSize = 14.sp)
                    } else if (state.onlineErrorMsg != null) {
                        Text("请求失败", color = Color.Red, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(8.dp))
                        Text(state.onlineErrorMsg ?: "", color = colors.subText, fontSize = 12.sp, textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = 24.dp))
                    } else {
                        Text("搜索全网音乐", color = colors.subText, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(8.dp))
                        Text("在上方搜索框中输入歌名或歌手", color = colors.subText.copy(alpha = 0.7f), fontSize = 12.sp)
                    }
                }
            }
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize()
                    .then(if (glass) Modifier.irisBackdropSource(listBackdrop) else if (frosted) Modifier.haze(state = hazeState, style = HazeStyle(blurRadius = frostedBlur)) else Modifier)
                    .graphicsLayer { translationY = refreshOffset }
                    .nestedScroll(scrollConnection),
                verticalArrangement = Arrangement.spacedBy(6.dp),
                contentPadding = PaddingValues(start = 22.dp, end = 22.dp, top = with(density) { statusBarHeightDp.toDp() + barTopGap + barHeight.value.toDp() + 8.dp }, bottom = 50.dp)
            ) {
                if (state.showRecommendations && state.recommendations.isNotEmpty()) {
                    item {
                        Crossfade(targetState = state.recommendationsVersion to state.recommendations, animationSpec = tween(500, easing = androidx.compose.animation.core.EaseInOut), label = "recommendationFade") { (_, recs) ->
                            if (recs.isNotEmpty()) {
                                RecommendationSection(recommendations = recs, colors = colors, showArtist = state.showRowArtist, onSelect = onPlaySong)
                            }
                        }
                    }
                }
                
                itemsIndexed(displayList, key = { _, song -> "song-${song.id}" }) { index, song ->
                    SongRow(
                        song = song, 
                        active = if (state.isOnlineMode) state.currentSong?.id == song.id else index == state.currentIndex, 
                        colors = colors, 
                        rowSize = state.rowSize, 
                        showCover = state.showRowCover, 
                        showArtist = state.showRowArtist, 
                        showPlaylist = state.showRowPlaylist, 
                        showLikedBadge = state.showLikedBadge, 
                        liked = song.id in state.likedSongIds, 
                        playlistNames = Playlists.playlistsOfSong(song.id),
                        onClick = { if (state.isOnlineMode && state.searchQuery.isNotBlank()) onPlaySong(song) else onSelect(index) }, 
                        onLongPress = { onAddToPlaylist(song) }
                    )
                }
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
                                // ===== 网络模式下的排行榜按钮 =====
                                if (state.isOnlineMode) {
                                    Box(Modifier.size(34.dp).clip(IrisShape.chip).background(colors.primary.copy(alpha=0.15f)).clickable { onOpenTopLists() }, contentAlignment = Alignment.Center) {
                                        Text("榜", color = colors.primary, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                                    }
                                    Spacer(Modifier.width(8.dp))
                                }
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
private fun PlayerPage(
    state: PlayerUiState, colors: IrisColors, viewModel: PlayerViewModel, onToggle: () -> Unit, onPrev: () -> Unit, onNext: () -> Unit, onSeek: (Float) -> Unit, 
    onToggleLike: (Long) -> Unit, onCyclePlayMode: () -> Unit, onOpenEqualizer: () -> Unit, onOpenSleepTimer: () -> Unit, eqActive: Boolean,
    onOpenComments: () -> Unit
) {
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
            PlayerCard(
                title = state.currentSong?.title ?: "未选择", 
                artist = state.currentSong?.artist ?: "", 
                positionMs = state.positionMs, durationMs = state.durationMs, shuffle = state.shuffle, repeatMode = state.repeatMode, 
                artworkPath = state.currentSong?.filePath, 
                artworkUri = state.currentSong?.albumArtUri, // 传入网络封面
                progress = state.progress, isPlaying = state.isPlaying, onToggle = onToggle, onPrev = onPrev, onNext = onNext, onSeek = onSeek, onCyclePlayMode = onCyclePlayMode, 
                onToggleLike = { state.currentSong?.let { onToggleLike(it.id) } }, liked = state.currentSong?.id in state.likedSongIds, onClickArtwork = onClickArtwork, colors = colors, modifier = Modifier, 
                sleepTimerMs = state.sleepTimerMs, sleepTimerEndMs = state.sleepTimerEndMs, onOpenSleepTimer = onOpenSleepTimer, onOpenEqualizer = onOpenEqualizer, eqActive = eqActive, 
                audioFormat = state.currentSong?.formatLabel ?: "", visualizerEnabled = state.visualizerEnabled, tiltSpectrum = state.tiltSpectrum, coverShakeEnabled = state.coverShake, coverLyricEnabled = state.coverLyric, 
                onToggleVisualizer = { viewModel.setVisualizerEnabled(!state.visualizerEnabled) },
                onOpenComments = onOpenComments // 传入评论事件
            )
        }
        if (showLyrics) {
            LyricsOverlay(
                lines = lyricLines, loading = lyricLoading, positionMs = state.positionMs, durationMs = state.durationMs, title = state.currentSong?.title ?: "", artist = state.currentSong?.artist ?: "", colors = colors, hazeState = lyricsHazeState, backdrops = lyricsBackdrops, onSeek = onSeek, 
                lyricAlign = state.lyricAlign, lyricFont = state.lyricFont, lyricFontSize = state.lyricFontSize, karaoke = state.karaokeLyric, lyricUseCustomColor = state.lyricUseCustomColor, lyricTheme = state.lyricTheme, lyricCustomPrimary = state.lyricCustomPrimary, lyricCustomSecondary = state.lyricCustomSecondary, 
                onDismiss = { showLyrics = false },
                showTranslation = state.showTranslationLyric,
                songId = state.currentSong?.id ?: 0L // 供解析器寻找翻译
            )
        }
    }
}

// =========================================================================
// 内部的 PlayerCard 和 LyricsOverlay 实现
// =========================================================================

@Composable
internal fun PlayerCard(
    title: String, artist: String, positionMs: Long, durationMs: Long, shuffle: Boolean, repeatMode: RepeatMode, artworkPath: String?, 
    artworkUri: android.net.Uri?, 
    progress: Float, isPlaying: Boolean, onToggle: () -> Unit, onPrev: () -> Unit, onNext: () -> Unit, onSeek: (Float) -> Unit, onCyclePlayMode: () -> Unit, 
    onToggleLike: () -> Unit, liked: Boolean, onClickArtwork: () -> Unit, colors: IrisColors, modifier: Modifier, sleepTimerMs: Long, sleepTimerEndMs: Long, onOpenSleepTimer: () -> Unit, onOpenEqualizer: () -> Unit, eqActive: Boolean, 
    audioFormat: String, visualizerEnabled: Boolean, tiltSpectrum: Boolean, coverShakeEnabled: Boolean, coverLyricEnabled: Boolean, onToggleVisualizer: () -> Unit,
    onOpenComments: () -> Unit
) {
    val glass = isLiquidGlass
    val isOnline = artworkUri != null
    
    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(32.dp))
            .then(if (glass) Modifier.irisSurface(GlassLevel.SHEET, colors, RoundedCornerShape(32.dp)) else Modifier.background(colors.card))
            .padding(24.dp)
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
            // 封面
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f)
                    .clip(RoundedCornerShape(24.dp))
                    .clickable { onClickArtwork() },
                contentAlignment = Alignment.Center
            ) {
                if (isOnline && artworkUri != null) {
                    AsyncImage(model = artworkUri, contentDescription = "Cover", contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                } else if (!artworkPath.isNullOrBlank()) {
                    SongArtwork(filePath = artworkPath, seedId = title.hashCode().toLong(), artworkSize = 300.dp, isDark = colors.isDark, modifier = Modifier.fillMaxSize())
                } else {
                    Box(Modifier.fillMaxSize().background(colors.surface), contentAlignment = Alignment.Center) {
                        Text("Vv MUSIC", color = colors.subText, fontWeight = FontWeight.Bold)
                    }
                }
            }

            Spacer(Modifier.height(24.dp))

            // 信息
            Text(title, color = colors.text, fontSize = 22.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(4.dp))
            Text(artist, color = colors.subText, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            
            if (isOnline && audioFormat.isNotBlank()) {
                Spacer(Modifier.height(8.dp))
                Box(Modifier.clip(RoundedCornerShape(4.dp)).background(colors.primary.copy(alpha = 0.15f)).padding(horizontal = 6.dp, vertical = 2.dp)) {
                    Text(audioFormat, color = colors.primary, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                }
            }

            Spacer(Modifier.height(24.dp))

            // 进度条
            Slider(
                value = progress,
                onValueChange = onSeek,
                colors = SliderDefaults.colors(
                    thumbColor = colors.primary,
                    activeTrackColor = colors.primary,
                    inactiveTrackColor = colors.subText.copy(alpha = 0.2f)
                ),
                modifier = Modifier.fillMaxWidth().height(30.dp)
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(formatDuration(positionMs), color = colors.subText, fontSize = 11.sp)
                Text(formatDuration(durationMs), color = colors.subText, fontSize = 11.sp)
            }

            Spacer(Modifier.height(16.dp))

            // 播放控制
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onCyclePlayMode) {
                    val iconText = when (repeatMode) {
                        RepeatMode.ONE -> "🔂"
                        RepeatMode.ALL -> "🔁"
                        RepeatMode.OFF -> if (shuffle) "🔀" else "➡️"
                    }
                    Text(iconText, fontSize = 20.sp)
                }
                
                IconButton(onClick = onPrev) { Text("⏮️", fontSize = 28.sp) }
                
                Box(
                    Modifier.size(64.dp).clip(CircleShape).background(colors.primary).clickable { onToggle() },
                    contentAlignment = Alignment.Center
                ) {
                    Text(if (isPlaying) "⏸️" else "▶️", fontSize = 28.sp, color = colors.primary.readableTextOn())
                }
                
                IconButton(onClick = onNext) { Text("⏭️", fontSize = 28.sp) }
                
                IconButton(onClick = onToggleLike) {
                    Text(if (liked) "❤️" else "🤍", fontSize = 20.sp)
                }
            }

            Spacer(Modifier.height(16.dp))
            HorizontalDivider(color = colors.subText.copy(alpha = 0.1f))
            Spacer(Modifier.height(16.dp))

            // 功能栏
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                IconButton(onClick = onOpenComments) { Text("💬", fontSize = 20.sp) }
                IconButton(onClick = onOpenSleepTimer) { Text(if (sleepTimerMs > 0) "⏱️" else "⏳", fontSize = 20.sp) }
                IconButton(onClick = onOpenEqualizer) { Text("🎛️", fontSize = 20.sp) }
            }
        }
    }
}

@Composable
internal fun LyricsOverlay(
    lines: List<LyricLine>, loading: Boolean, positionMs: Long, durationMs: Long, title: String, artist: String, colors: IrisColors, hazeState: HazeState, backdrops: List<IrisBackdrop>, onSeek: (Float) -> Unit, 
    lyricAlign: Int = 1, lyricFont: Int = 0, lyricFontSize: Float = 20f, karaoke: Boolean = true, lyricUseCustomColor: Boolean = false, lyricTheme: IrisTheme = IrisTheme.MONO, lyricCustomPrimary: Long = 0xFF00F0FFL, lyricCustomSecondary: Long = 0xFFFF2E97L, onDismiss: () -> Unit,
    showTranslation: Boolean, songId: Long 
) {
    val listState = rememberLazyListState()
    val current = LyricParser.currentIndex(lines, positionMs)
    
    var translationLines by remember { mutableStateOf<List<LyricLine>>(emptyList()) }
    val context = LocalContext.current
    
    LaunchedEffect(songId, showTranslation) {
        if (showTranslation && songId > 0) {
            val tFile = java.io.File(context.cacheDir, "online_lyrics/online_${songId}_t.lrc")
            if (tFile.exists()) {
                translationLines = withContext(Dispatchers.IO) { 
                    LyricParser.loadLyrics(tFile.absolutePath, durationMs) ?: emptyList() 
                }
            } else {
                translationLines = emptyList()
            }
        } else {
            translationLines = emptyList()
        }
    }

    val boxAlign = when (lyricAlign) { 0 -> Alignment.CenterStart; 2 -> Alignment.CenterEnd; else -> Alignment.Center }
    val textAlign = when (lyricAlign) { 0 -> TextAlign.Start; 2 -> TextAlign.End; else -> TextAlign.Center }
    
    LaunchedEffect(current) { 
        if (current >= 0 && current < lines.size) {
            listState.animateScrollToItem(current, scrollOffset = -200)
        }
    }

    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.85f)).clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) { onDismiss() }) {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(horizontal = 28.dp)) {
            Spacer(Modifier.height(24.dp))
            Text(title, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
            Text(artist, color = Color.Gray, fontSize = 14.sp, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
            Spacer(Modifier.height(40.dp))

            if (loading) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = Color.White) }
            } else if (lines.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("暂无歌词", color = Color.Gray) }
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(24.dp),
                    contentPadding = PaddingValues(bottom = 150.dp)
                ) {
                    itemsIndexed(lines) { index, line ->
                        val isCurrent = index == current
                        val alpha by animateFloatAsState(if (isCurrent) 1f else 0.4f)
                        val scale by animateFloatAsState(if (isCurrent) 1.1f else 1f)
                        
                        val tLine = translationLines.find { Math.abs(it.timeMs - line.timeMs) < 200 }
                        
                        Column(
                            modifier = Modifier.fillMaxWidth().graphicsLayer { this.alpha = alpha; scaleX = scale; scaleY = scale },
                            horizontalAlignment = if (lyricAlign == 0) Alignment.Start else if (lyricAlign == 2) Alignment.End else Alignment.CenterHorizontally
                        ) {
                            if (isCurrent && karaoke) {
                                KaraokeLine(
                                    line = line, positionMs = positionMs, lineEndMs = lines.getOrNull(index + 1)?.timeMs ?: (line.timeMs + 4000),
                                    lyricUseCustomColor = lyricUseCustomColor, themePrimary = Color(lyricCustomPrimary), themeSecondary = Color(lyricCustomSecondary),
                                    originalColor = Color.White, unsungColor = Color.White.copy(alpha = 0.5f), textAlign = textAlign, fontSize = lyricFontSize
                                )
                            } else {
                                Text(
                                    text = line.text,
                                    color = Color.White,
                                    fontSize = lyricFontSize.sp,
                                    fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal,
                                    textAlign = textAlign
                                )
                            }
                            
                            if (tLine != null && tLine.text.isNotBlank() && showTranslation) {
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    text = tLine.text,
                                    color = if (isCurrent) Color.White.copy(alpha = 0.8f) else Color.Gray,
                                    fontSize = (lyricFontSize - 4).sp,
                                    fontWeight = FontWeight.Normal,
                                    textAlign = textAlign
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun KaraokeLine(
    line: LyricLine, positionMs: Long, lineEndMs: Long, lyricUseCustomColor: Boolean, themePrimary: Color, themeSecondary: Color, originalColor: Color, unsungColor: Color, 
    textAlign: TextAlign = TextAlign.Center, fontFamily: FontFamily = FontFamily.SansSerif, fontSize: Float = 20f, modifier: Modifier = Modifier
) {
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
