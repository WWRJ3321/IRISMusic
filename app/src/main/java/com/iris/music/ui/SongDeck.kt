package com.iris.music.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PageSize
import androidx.compose.foundation.gestures.snapping.SnapPosition
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.util.lerp
import com.iris.music.data.LyricLine
import com.iris.music.data.LyricParser
import com.iris.music.data.RepeatMode
import com.iris.music.data.Song
import com.iris.music.data.formatLabel
import com.iris.music.player.PlayerUiState
import com.iris.music.ui.theme.*
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.haze
import dev.chrisbanes.haze.hazeChild
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.pow

/**
 * 卡片模式：整个 App 只剩一叠播放卡片，一首歌一张卡。
 *
 * 三种排布里两种共用同一张卡（[PlayerCard]），区别只在怎么摆：
 * - [IrisLayout.CAROUSEL] 横向排布：卡片横向铺开，两侧露出邻卡，左右滑翻歌
 * - [IrisLayout.STACK] 堆叠排布：卡片叠成一沓，把最上面那张拖走切歌
 * - [IrisLayout.COMPACT] 紧凑排布：不放卡，整屏紧凑歌单 + 底部常驻迷你播放条
 */
@Composable
fun SongDeck(
    state: PlayerUiState,
    colors: IrisColors,
    modifier: Modifier = Modifier,
    onToggle: () -> Unit,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onSeek: (Float) -> Unit,
    onSelect: (Int) -> Unit,
    onToggleLike: (Long) -> Unit,
    onCyclePlayMode: () -> Unit,
    onToggleVisualizer: () -> Unit,
    onOpenEqualizer: () -> Unit,
    onOpenSleepTimer: () -> Unit,
    onOpenReport: () -> Unit,
    onOpenSettings: () -> Unit,
    onCycleLayout: () -> Unit,
    onOpenPlaylists: () -> Unit,
    onReload: () -> Unit,
    /** 点击评论按钮的回调：卡片模式里所有卡片的评论入口都指向当前歌曲 */
    onOpenComments: () -> Unit,
    eqActive: Boolean
) {
    val glass = isLiquidGlass
    val frosted = isFrostedGlass
    val frostedBlur = frostedBlurRadius

    // 歌词层浮在卡片之上，要折射「背景 + 卡片」两层。和 PlayerPage 同构：
    // 卡片区单独录一层背板，歌词层是它的兄弟节点，取样方向单向不互相触发重绘。
    val cardBackdrop = rememberIrisBackdrop()
    val outerBackdrops = LocalIrisBackdrops.current
    val lyricsBackdrops = remember(glass, outerBackdrops, cardBackdrop) {
        if (glass) outerBackdrops + cardBackdrop else emptyList()
    }
    val lyricsHazeState = remember { HazeState() }

    var showLyrics by remember { mutableStateOf(false) }

    // 卡片模式的歌词层同样要吃掉返回键，否则直接退出应用
    androidx.activity.compose.BackHandler(enabled = showLyrics) { showLyrics = false }
    var lyricLines by remember { mutableStateOf<List<LyricLine>>(emptyList()) }
    var lyricLoading by remember { mutableStateOf(false) }
    // 歌词层当前歌词所属文件：开着歌词层切歌时据此判断要重新加载
    var lyricPath by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    // 歌词层开着时自动切歌：旧词跟着走，路径一变先清空再按新歌重解析
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

    val openLyrics: () -> Unit = {
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
                    // 文件读取异常静默忽略
                } finally {
                    lyricLoading = false
                }
            }
        }
    }

// 顶栏三档透明度联动：deck 内容区（非顶栏）任意按下 → contentTick++，顶栏升到中档。
    var contentTick by remember { mutableIntStateOf(0) }

    Box(modifier.fillMaxSize()) {
        if (state.layout == IrisLayout.COMPACT) {
            // 海报墙铺满整屏（延伸进状态栏），顶部按钮作为浮层叠在最上层
            Box(
                Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) {
                        // 只观察、不消费：海报墙拖拽照常，这里仅统计"别处交互"
                        awaitPointerEventScope {
                            while (true) { awaitFirstDown(requireUnconsumed = false); contentTick++ }
                        }
                    }
            ) {
                DeckPosterWall(
                    state = state,
                    colors = colors,
                    onToggle = onToggle,
                    onPrev = onPrev,
                    onNext = onNext,
                    onSeek = onSeek,
                    onSelect = onSelect,
                    jelly = state.jellyAnim
                )
            }
            DeckTopBar(
                colors = colors,
                activePlaylist = state.activePlaylistId != null,
                refreshing = state.refreshing,
                onCycleLayout = onCycleLayout,
                onOpenReport = onOpenReport,
                onOpenSettings = onOpenSettings,
                onOpenPlaylists = onOpenPlaylists,
                onReload = onReload,
                contentTick = contentTick,
                autoHide = state.topBarAutoHide,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .statusBarsPadding()
            )
        } else {
            Column(
                Modifier
                    .fillMaxSize()
                    .then(
                        if (glass) Modifier.irisBackdropSource(cardBackdrop)
                        else if (frosted) Modifier.haze(state = lyricsHazeState, style = HazeStyle(blurRadius = frostedBlur))
                        else Modifier
                    )
                    .statusBarsPadding()
                    .navigationBarsPadding(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                DeckTopBar(
                    colors = colors,
                    activePlaylist = state.activePlaylistId != null,
                    refreshing = state.refreshing,
                    onCycleLayout = onCycleLayout,
                    onOpenReport = onOpenReport,
                    onOpenSettings = onOpenSettings,
                    onOpenPlaylists = onOpenPlaylists,
                    onReload = onReload,
                    autoHide = state.topBarAutoHide,
                    // 横屏窄化：顶栏玻璃与下方播放卡片同宽（用补偿值抵消 padding 差，
                    // 唱片墙分支不走这里）
                    modifier = if (LocalConfiguration.current.orientation ==
                        android.content.res.Configuration.ORIENTATION_LANDSCAPE
                    ) Modifier.widthIn(max = LANDSCAPE_BAR_MAX_WIDTH) else Modifier
                )

                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    if (state.queue.isEmpty()) {
                        Text(
                                if (state.loading) "读取音乐库…" else "还没有歌曲\n去设置里换个文件夹，或取消当前筛选",
                            color = colors.subText,
                            fontSize = 13.sp,
                            lineHeight = 19.sp,
                            textAlign = TextAlign.Center
                        )
                    } else if (state.layout == IrisLayout.STACK) {
                        val stackLandscape = LocalConfiguration.current.orientation ==
                            android.content.res.Configuration.ORIENTATION_LANDSCAPE
                        val stackCur = state.queue.getOrNull(state.currentIndex)
                        if (stackLandscape) {
                            // 横屏：左侧封面堆叠（复用原手势），右侧固定控制区
                            LandscapeDeckPlayer(
                                title = stackCur?.title ?: "未选择",
                                artist = stackCur?.artist ?: "",
                                positionMs = state.positionMs,
                                durationMs = state.durationMs,
                                shuffle = state.shuffle,
                                repeatMode = state.repeatMode,
                                progress = state.progress,
                                isPlaying = state.isPlaying,
                                onToggle = onToggle,
                                onPrev = onPrev,
                                onNext = onNext,
                                onSeek = onSeek,
                                onCyclePlayMode = onCyclePlayMode,
                                onToggleLike = { stackCur?.let { onToggleLike(it.id) } },
                                liked = stackCur?.id in state.likedSongIds,
                                onOpenComments = onOpenComments,
                                colors = colors,
                                sleepTimerMs = state.sleepTimerMs,
                                sleepTimerEndMs = state.sleepTimerEndMs,
                                onOpenSleepTimer = onOpenSleepTimer,
                                eqActive = eqActive,
                                onOpenEqualizer = onOpenEqualizer,
                                audioFormat = stackCur?.formatLabel ?: "",
                                visualizerEnabled = state.visualizerEnabled,
                                onToggleVisualizer = onToggleVisualizer,
                                tiltSpectrum = state.tiltSpectrum,
                                // 堆叠往右后方，舞台留出余量给后面几层露头
                                coverAspect = 1.30f
                            ) {
                                DeckStack(
                                    state = state,
                                    colors = colors,
                                    onToggle = onToggle,
                                    onPrev = onPrev,
                                    onNext = onNext,
                                    onSeek = onSeek,
                                    onSelect = onSelect,
                                    onToggleLike = onToggleLike,
                                    onCyclePlayMode = onCyclePlayMode,
                                    onToggleVisualizer = onToggleVisualizer,
                                    onOpenEqualizer = onOpenEqualizer,
                                    onOpenSleepTimer = onOpenSleepTimer,
                                    onClickArtwork = openLyrics,
                                    onOpenComments = onOpenComments,
                                    eqActive = eqActive,
                                    landscape = true
                                )
                            }
                        } else {
                        DeckStack(
                            state = state,
                            colors = colors,
                            onToggle = onToggle,
                            onPrev = onPrev,
                            onNext = onNext,
                            onSeek = onSeek,
                            onSelect = onSelect,
                            onToggleLike = onToggleLike,
                            onCyclePlayMode = onCyclePlayMode,
                            onToggleVisualizer = onToggleVisualizer,
                            onOpenEqualizer = onOpenEqualizer,
                            onOpenSleepTimer = onOpenSleepTimer,
                            onClickArtwork = openLyrics,
                            onOpenComments = onOpenComments,
                            eqActive = eqActive
                        )
                        }
                    } else {
                        DeckCarousel(
                            state = state,
                            colors = colors,
                            onToggle = onToggle,
                            onPrev = onPrev,
                            onNext = onNext,
                            onSeek = onSeek,
                            onSelect = onSelect,
                            onToggleLike = onToggleLike,
                            onCyclePlayMode = onCyclePlayMode,
                            onToggleVisualizer = onToggleVisualizer,
                            onOpenEqualizer = onOpenEqualizer,
                            onOpenSleepTimer = onOpenSleepTimer,
                            onClickArtwork = openLyrics,
                            onOpenComments = onOpenComments,
                            eqActive = eqActive
                        )
                    }
                }
            }
        }

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
                lyricTheme = state.lyricTheme,
                lyricCustomPrimary = state.lyricCustomPrimary,
                lyricCustomSecondary = state.lyricCustomSecondary,
                onDismiss = { showLyrics = false },
                showTranslation = state.showTranslationLyric,
                songId = state.currentSong?.id ?: 0L
            )
        }
    }
}

// ==================== 顶部 chrome ====================

@Composable
private fun DeckTopBar(
    colors: IrisColors,
    activePlaylist: Boolean,
    refreshing: Boolean,
    onCycleLayout: () -> Unit,
    onOpenReport: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenPlaylists: () -> Unit,
    onReload: () -> Unit,
    /** 内容区（非顶栏）交互计数：>0 时顶栏升到中档透明度 */
    contentTick: Int = 0,
    /** 开启后：完全静止时顶栏淡到全透明（关=停在半透明 30%） */
    autoHide: Boolean = false,
    modifier: Modifier = Modifier
) {
    // 静止时的回落透明度：开关开启则完全透明。
    val idleAlpha = if (autoHide) 0f else 0.3f
    var interactionTick by remember { mutableIntStateOf(0) }   // 点顶栏
    val barAlpha = remember { Animatable(0.9f) }               // 进场算顶栏档，先亮
    val barScope = rememberCoroutineScope()
    var idleJob by remember { mutableStateOf<Job?>(null) }
    fun bump(target: Float) {
        idleJob?.cancel()
        barScope.launch { barAlpha.animateTo(target, animationSpec = tween(250)) }
        idleJob = barScope.launch {
            delay(2000)
            barAlpha.animateTo(idleAlpha, animationSpec = tween(300))
        }
    }
    LaunchedEffect(autoHide) {
        idleJob = barScope.launch { delay(2000); barAlpha.animateTo(idleAlpha, animationSpec = tween(300)) }
    }
    var lastBarTouchAt by remember { androidx.compose.runtime.mutableLongStateOf(0L) }
    LaunchedEffect(interactionTick) {
        if (interactionTick > 0) { lastBarTouchAt = System.nanoTime(); bump(0.9f) }
    }
    LaunchedEffect(contentTick) {
        if (contentTick > 0 && System.nanoTime() - lastBarTouchAt > 80_000_000L) bump(0.5f)
    }
    Box(
        modifier
            .fillMaxWidth()
            .graphicsLayer { this.alpha = barAlpha.value }
            .padding(top = 20.dp)
            .padding(horizontal = 22.dp)
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        awaitFirstDown(requireUnconsumed = false)
                        interactionTick++
                    }
                }
            }
            .clickable(
                indication = null,
                interactionSource = remember { MutableInteractionSource() }
            ) { }
            .irisSurface(GlassLevel.CARD, colors, IrisShape.item, solid = colors.surface)
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                val wordPrimary = colors.primary.readableOn(colors.surface)
                val wordSecondary = colors.secondary.readableOn(colors.surface)
                Text("Vv", color = wordPrimary, fontSize = 17.sp, fontWeight = FontWeight.Black)
                Spacer(Modifier.width(4.dp))
                Text("MUSIC", color = wordSecondary, fontSize = 17.sp, fontWeight = FontWeight.Black)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                PlaylistButton(onClick = onOpenPlaylists, colors = colors, active = activePlaylist)
                Spacer(Modifier.width(8.dp))
                LayoutButton(onClick = onCycleLayout, colors = colors)
                Spacer(Modifier.width(8.dp))
                ReportButton(onClick = onOpenReport, colors = colors)
                Spacer(Modifier.width(8.dp))
                SettingsButton(onClick = onOpenSettings, colors = colors)
            }
        }
    }
}

// ==================== 横向排布 ====================

/** 两侧留给邻卡的宽度：小了看不出旁边还有卡，大了当前卡就被挤得太窄 */
private val CAROUSEL_PEEK = 38.dp

/**
 * 横屏封面 Pager 的页宽占舞台宽度的比例。
 */
private val CoverPageSize = object : PageSize {
    override fun Density.calculateMainAxisPageSize(
        availableSpace: Int,
        pageSpacing: Int
    ): Int = (availableSpace * 0.77f).toInt()
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DeckCarousel(
    state: PlayerUiState,
    colors: IrisColors,
    onToggle: () -> Unit,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onSeek: (Float) -> Unit,
    onSelect: (Int) -> Unit,
    onToggleLike: (Long) -> Unit,
    onCyclePlayMode: () -> Unit,
    onToggleVisualizer: () -> Unit,
    onOpenEqualizer: () -> Unit,
    onOpenSleepTimer: () -> Unit,
    onClickArtwork: () -> Unit,
    onOpenComments: () -> Unit,
    eqActive: Boolean
) {
    val queue = state.queue
    val pagerState = rememberPagerState(
        initialPage = state.currentIndex.coerceAtLeast(0).coerceAtMost(queue.lastIndex.coerceAtLeast(0)),
        pageCount = { queue.size }
    )
    val liveIndex = rememberUpdatedState(state.currentIndex)

    // 自动播完 / 通知栏切歌：卡片跟着滑过去
    LaunchedEffect(state.currentIndex) {
        val target = state.currentIndex
        if (target in queue.indices && target != pagerState.currentPage) {
            pagerState.animateScrollToPage(target)
        }
    }

    // 翻到哪张卡就放哪首。首次发射要跳过：那只是初始页，不是用户翻的。
    LaunchedEffect(pagerState) {
        var first = true
        snapshotFlow { pagerState.settledPage }
            .distinctUntilChanged()
            .collect { page ->
                if (first) {
                    first = false
                } else if (page != liveIndex.value) {
                    onSelect(page)
                }
            }
    }

    val isLandscape = LocalConfiguration.current.orientation ==
        android.content.res.Configuration.ORIENTATION_LANDSCAPE

    if (isLandscape) {
        // 横屏：封面 Pager（露左右邻曲）在左，固定控制区在右
        val cur = queue.getOrNull(state.currentIndex)
        LandscapeDeckPlayer(
            title = cur?.title ?: "未选择",
            artist = cur?.artist ?: "",
            positionMs = state.positionMs,
            durationMs = state.durationMs,
            shuffle = state.shuffle,
            repeatMode = state.repeatMode,
            progress = state.progress,
            isPlaying = state.isPlaying,
            onToggle = onToggle,
            onPrev = onPrev,
            onNext = onNext,
            onSeek = onSeek,
            onCyclePlayMode = onCyclePlayMode,
            onToggleLike = { cur?.let { onToggleLike(it.id) } },
            liked = cur?.id in state.likedSongIds,
            onOpenComments = onOpenComments,
            colors = colors,
            sleepTimerMs = state.sleepTimerMs,
            sleepTimerEndMs = state.sleepTimerEndMs,
            onOpenSleepTimer = onOpenSleepTimer,
            eqActive = eqActive,
            onOpenEqualizer = onOpenEqualizer,
            audioFormat = cur?.formatLabel ?: "",
            visualizerEnabled = state.visualizerEnabled,
            onToggleVisualizer = onToggleVisualizer,
            tiltSpectrum = state.tiltSpectrum,
            // 舞台比封面宽 30%：多出的部分左右均分，正好给邻曲露头
            coverAspect = 1.30f
        ) {
            // 封面 Pager：封面本体清晰。左右边界做「向背景柔化淡出」——
            // 用横向 DstIn 渐变只在最外侧约 24% 范围把封面 alpha 平滑降到 0。
            HorizontalPager(
                state = pagerState,
                modifier = Modifier
                    .fillMaxHeight()
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(18.dp))
                    .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                    .drawWithContent {
                        drawContent()
                        drawRect(
                            brush = Brush.horizontalGradient(
                                0.00f to Color.Black.copy(alpha = 0f),
                                0.06f to Color.Black.copy(alpha = 0.35f),
                                0.13f to Color.Black.copy(alpha = 0.70f),
                                0.20f to Color.Black.copy(alpha = 0.90f),
                                0.27f to Color.Black.copy(alpha = 1f),
                                0.73f to Color.Black.copy(alpha = 1f),
                                0.80f to Color.Black.copy(alpha = 0.90f),
                                0.87f to Color.Black.copy(alpha = 0.70f),
                                0.94f to Color.Black.copy(alpha = 0.35f),
                                1.00f to Color.Black.copy(alpha = 0f)
                            ),
                            blendMode = BlendMode.DstIn
                        )
                    },
                pageSize = CoverPageSize,
                snapPosition = SnapPosition.Center,
                contentPadding = PaddingValues(0.dp),
                pageSpacing = 0.dp,
                beyondViewportPageCount = 1,
                verticalAlignment = Alignment.CenterVertically
            ) { page ->
                val song = queue.getOrNull(page)
                if (song != null) {
                    PlayerCoverArt(
                        artworkPath = song.filePath,
                        artworkUri = song.albumArtUri,
                        defaultCoverUri = state.defaultCoverUri,
                        positionMs = if (page == state.currentIndex) state.positionMs else 0L,
                        isDark = colors.isDark,
                        vizAlpha = 0f,
                        shake = rememberShakeState(false),
                        coverLyricEnabled = state.coverLyric,
                        interactive = page == state.currentIndex,
                        coverLyrics = emptyList(),
                        onClickArtwork = { if (page == state.currentIndex) onClickArtwork() else onSelect(page) },
                        modifier = Modifier
                            .fillMaxHeight()
                            .aspectRatio(1f)
                            .graphicsLayer {
                                // 邻曲轻微景深：越靠边越小越暗。封面本体不模糊。
                                val dist = abs((page - pagerState.currentPage) - pagerState.currentPageOffsetFraction)
                                    .coerceIn(0f, 1f)
                                val near = 1f - dist
                                val s = lerp(0.92f, 1f, near)
                                scaleX = s
                                scaleY = s
                                alpha = lerp(0.72f, 1f, near)
                            }
                    )
                }
            }
        }
        return
    }

    HorizontalPager(
        state = pagerState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = CAROUSEL_PEEK),
        pageSpacing = 12.dp,
        beyondViewportPageCount = 1
    ) { page ->
        val song = queue.getOrNull(page)
        if (song != null) {
            DeckCard(
                song = song,
                isCurrent = page == state.currentIndex,
                state = state,
                colors = colors,
                onActivate = { onSelect(page) },
                onToggle = onToggle,
                onPrev = onPrev,
                onNext = onNext,
                onSeek = onSeek,
                onToggleLike = onToggleLike,
                onCyclePlayMode = onCyclePlayMode,
                onToggleVisualizer = onToggleVisualizer,
                onOpenEqualizer = onOpenEqualizer,
                onOpenSleepTimer = onOpenSleepTimer,
                onClickArtwork = onClickArtwork,
                onOpenComments = onOpenComments,
                eqActive = eqActive,
                modifier = Modifier.graphicsLayer {
                    val dist = abs((page - pagerState.currentPage) - pagerState.currentPageOffsetFraction)
                        .coerceIn(0f, 1f)
                    val s = lerp(0.90f, 1f, 1f - dist)
                    scaleX = s
                    scaleY = s
                    alpha = lerp(0.4f, 1f, 1f - dist)
                }
            )
        }
    }
}

// ==================== 堆叠排布 ====================

private const val STACK_DEPTH = 2
private val STACK_STEP_Y = 16.dp
private const val STACK_SCALE_STEP = 0.02f
private val STACK_SIDE_PADDING = 26.dp
private const val STACK_THROW_FRACTION = 0.18f
private val STACK_THROW_MIN = 56.dp
private const val STACK_TILT = 6f
private const val STACK_FLING_VELOCITY = 420f
private val STACK_SWIPE_MIN = 24.dp

@Composable
private fun DeckStack(
    state: PlayerUiState,
    colors: IrisColors,
    onToggle: () -> Unit,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onSeek: (Float) -> Unit,
    onSelect: (Int) -> Unit,
    onToggleLike: (Long) -> Unit,
    onCyclePlayMode: () -> Unit,
    onToggleVisualizer: () -> Unit,
    onOpenEqualizer: () -> Unit,
    onOpenSleepTimer: () -> Unit,
    onClickArtwork: () -> Unit,
    onOpenComments: () -> Unit,
    eqActive: Boolean,
    landscape: Boolean = false
) {
    val queue = state.queue
    val base = state.currentIndex.coerceAtLeast(0).coerceAtMost(queue.lastIndex.coerceAtLeast(0))

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val stepY = with(density) { STACK_STEP_Y.toPx() }
        // 飞出行程。
        val flyOut = with(density) { maxWidth.toPx() } * (if (landscape) 1.6f else 1f)
        val throwPx = maxOf(
            with(density) { maxWidth.toPx() } * STACK_THROW_FRACTION,
            with(density) { STACK_THROW_MIN.toPx() }
        )
        val minDragPx = with(density) { STACK_SWIPE_MIN.toPx() }

        var cardX by remember { mutableFloatStateOf(0f) }
        var visualBase by remember { mutableIntStateOf(base) }
        var committing by remember { mutableStateOf(false) }
        val scope = rememberCoroutineScope()
        var snapJob by remember { mutableStateOf<Job?>(null) }
        var commitJob by remember { mutableStateOf<Job?>(null) }

        LaunchedEffect(base, queue.size, committing) {
            if (!committing && visualBase != base) {
                delay(160)
                if (!committing && visualBase != base) {
                    visualBase = base
                    cardX = 0f
                }
            }
        }
        val top = visualBase.coerceIn(0, queue.lastIndex.coerceAtLeast(0))

        val dragState = rememberDraggableState { delta -> cardX += delta }

        Box(
            Modifier
                .fillMaxSize()
                .then(if (landscape) Modifier.clipToBounds() else Modifier)
                .draggable(
                    state = dragState,
                    orientation = Orientation.Horizontal,
                    onDragStarted = {
                        snapJob?.cancel()
                        commitJob?.cancel()
                    },
                    onDragStopped = { velocity ->
                        if (abs(cardX) < minDragPx) {
                            scope.launch {
                                Animatable(cardX, visibilityThreshold = 0.5f).animateTo(
                                    0f, spring(stiffness = Spring.StiffnessMediumLow)
                                ) { cardX = value }
                            }
                            return@draggable
                        }
                        val dir = when {
                            cardX <= -1f -> -1
                            cardX >= 1f -> 1
                            velocity < 0f -> -1
                            velocity > 0f -> 1
                            else -> 0
                        }
                        val strongFling = abs(velocity) > STACK_FLING_VELOCITY
                        val velDir = if (velocity < 0f) -1 else 1
                        val pass = dir != 0 &&
                            if (strongFling) velDir == dir else abs(cardX) >= throwPx
                        val rawTarget = when {
                            !pass -> -1
                            dir < 0 -> top + 1
                            else -> top - 1
                        }
                        val target = when {
                            rawTarget !in queue.indices && state.repeatMode == RepeatMode.ALL -> {
                                if (rawTarget > queue.lastIndex) 0 else queue.lastIndex
                            }
                            else -> rawTarget
                        }

                        if (target !in queue.indices) {
                            snapJob?.cancel()
                            snapJob = scope.launch {
                                Animatable(cardX, visibilityThreshold = 0.5f).animateTo(
                                    0f,
                                    if (IrisMotion.jelly) spring(dampingRatio = 0.62f, stiffness = Spring.StiffnessMediumLow, visibilityThreshold = 0.5f)
                                    else spring(dampingRatio = 0.72f, stiffness = Spring.StiffnessMediumLow),
                                    initialVelocity = velocity
                                ) { cardX = value }
                            }
                        } else {
                            snapJob?.cancel()
                            committing = true
                            Haptics.click()
                            onSelect(target)
                            commitJob = scope.launch {
                                try {
                                    val targetX = if (target > top) -flyOut else flyOut
                                    Animatable(cardX, visibilityThreshold = 0.5f).animateTo(
                                        targetX,
                                        if (IrisMotion.jelly) spring(
                                            dampingRatio = 0.68f,
                                            stiffness = Spring.StiffnessMediumLow,
                                            visibilityThreshold = 0.5f
                                        ) else spring(
                                            dampingRatio = 0.78f,
                                            stiffness = Spring.StiffnessMediumLow,
                                            visibilityThreshold = 0.5f
                                        ),
                                        initialVelocity = velocity * 0.35f
                                    ) { cardX = value }
                                } finally {
                                    visualBase = target
                                    cardX = 0f
                                    committing = false
                                }
                            }
                        }
                    }
                ),
            contentAlignment = Alignment.Center
        ) {
            for (d in STACK_DEPTH downTo -1) {
                val index = top + d
                val song = queue.getOrNull(index) ?: continue
                val depth = d.toFloat()

                key(index) {
                Box(
                    Modifier
                        .then(
                            if (landscape) Modifier
                                .fillMaxHeight(0.88f)
                                .aspectRatio(1f)
                            else Modifier.fillMaxWidth().padding(horizontal = STACK_SIDE_PADDING)
                        )
                        .graphicsLayer {
                            val t = (cardX / throwPx).coerceIn(-1f, 1f)

                            if (d < 0) {
                                val u = (cardX / flyOut).coerceIn(0f, 1f)
                                translationX = cardX - flyOut
                                rotationZ = -STACK_TILT * 4f * u * (1f - u)
                                return@graphicsLayer
                            }

                            if (d == 0) {
                                if (cardX < 0f) {
                                    translationX = cardX
                                    rotationZ = t * STACK_TILT
                                    return@graphicsLayer
                                }
                            }

                            val eff = (depth + t).coerceAtLeast(0f)
                            if (landscape) {
                                translationX = stepY * 1.35f * eff
                                translationY = stepY * 0.28f * eff
                            } else {
                                translationY = -stepY * eff
                            }
                            val s = 1f - STACK_SCALE_STEP * eff
                            scaleX = s
                            scaleY = s
                            alpha = (1f - (eff / (STACK_DEPTH + 0.6f)).pow(1.4f))
                                .coerceIn(0f, 1f)
                        }
                ) {
                    if (landscape) {
                        // 横屏：只堆封面，控制区在外层固定。
                        PlayerCoverArt(
                            artworkPath = song.filePath,
                            artworkUri = song.albumArtUri,
                            defaultCoverUri = state.defaultCoverUri,
                            positionMs = if (d == 0) state.positionMs else 0L,
                            isDark = colors.isDark,
                            vizAlpha = 0f,
                            shake = rememberShakeState(false),
                            coverLyricEnabled = state.coverLyric,
                            interactive = d == 0,
                            coverLyrics = emptyList(),
                            onClickArtwork = { if (d == 0) onClickArtwork() else onSelect(index) },
                            modifier = Modifier.fillMaxSize()
                        )
                    } else {
                    DeckCard(
                        song = song,
                        isCurrent = d == 0,
                        state = state,
                        colors = colors,
                        onActivate = { onSelect(index) },
                        onToggle = onToggle,
                        onPrev = onPrev,
                        onNext = onNext,
                        onSeek = onSeek,
                        onToggleLike = onToggleLike,
                        onCyclePlayMode = onCyclePlayMode,
                        onToggleVisualizer = onToggleVisualizer,
                        onOpenEqualizer = onOpenEqualizer,
                        onOpenSleepTimer = onOpenSleepTimer,
                        onClickArtwork = onClickArtwork,
                        onOpenComments = onOpenComments,
                        eqActive = eqActive,
                        fullWidth = false
                    )
                    }
                }
                } // key(index)
            }
        }
    }
}

// ==================== 紧凑排布 ====================

@Composable
private fun DeckCompact(
    state: PlayerUiState,
    colors: IrisColors,
    onToggle: () -> Unit,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onSeek: (Float) -> Unit,
    onSelect: (Int) -> Unit,
    onClickArtwork: () -> Unit
) {
    val queue = state.queue
    val listState = rememberLazyListState()
    LaunchedEffect(state.currentIndex, queue.size) {
        if (queue.isEmpty()) return@LaunchedEffect
        val target = state.currentIndex
        if (target in queue.indices) {
            val info = listState.layoutInfo.visibleItemsInfo
            val visible = info.any { it.index == target }
            if (!visible) listState.animateScrollToItem(target)
        }
    }

    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(4.dp),
            contentPadding = PaddingValues(
                start = 16.dp, end = 16.dp, top = 4.dp,
                bottom = 86.dp
            )
        ) {
            itemsIndexed(queue, key = { _, song -> "song-${song.id}" }) { index, song ->
                CompactSongRow(
                    song = song,
                    index = index,
                    active = index == state.currentIndex,
                    playing = index == state.currentIndex && state.isPlaying,
                    colors = colors,
                    onClick = { onSelect(index) }
                )
            }
        }

        // ===== 常驻迷你播放条 =====
        val song = queue.getOrNull(state.currentIndex)
        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .navigationBarsPadding(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            val duration = state.durationMs
            val position = state.positionMs
            val ratio = if (duration > 0) (position.toFloat() / duration).coerceIn(0f, 1f) else 0f
            CompactSeekBar(
                progress = ratio,
                enabled = song != null && duration > 0,
                onSeek = onSeek,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 6.dp),
                colors = colors
            )
            Spacer(Modifier.height(2.dp))
            Row(
                Modifier
                    .fillMaxWidth()
                    .irisSurface(GlassLevel.CARD, colors, IrisShape.item)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) { Haptics.tap(); onClickArtwork() },
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(
                    Modifier
                        .weight(1f)
                        .padding(start = 14.dp, top = 10.dp, bottom = 10.dp)
                ) {
                    Text(
                        song?.title ?: "未播放",
                        color = colors.text,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(Modifier.height(2.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            song?.artist ?: "选一首开始播放",
                            color = colors.subText,
                            fontSize = 10.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false)
                        )
                        if (song != null) {
                            Spacer(Modifier.width(6.dp))
                            Text(
                                "${fmtCompact(position)} / ${fmtCompact(duration)}",
                                color = colors.subText,
                                fontSize = 10.sp
                            )
                        }
                    }
                }
                Spacer(Modifier.width(10.dp))
                CompactPlayButton("⏮", colors = colors, enabled = song != null) { Haptics.tap(); onPrev() }
                CompactPlayButton(
                    if (state.isPlaying) "⏸" else "▶",
                    colors = colors,
                    primary = true,
                    enabled = song != null
                ) { Haptics.tap(); onToggle() }
                CompactPlayButton("⏭", colors = colors, enabled = song != null) { Haptics.tap(); onNext() }
                Spacer(Modifier.width(10.dp))
            }
        }
    }
}

@Composable
private fun CompactSongRow(
    song: Song,
    index: Int,
    active: Boolean,
    playing: Boolean,
    colors: IrisColors,
    onClick: () -> Unit
) {
    Row(
        Modifier
            .fillMaxWidth()
            .irisSurface(
                GlassLevel.ROW,
                colors,
                IrisShape.item,
                solid = if (active) colors.row.copy(alpha = 0.8f) else colors.row,
                accent = if (active) colors.primary else null
            )
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = { Haptics.tap(); onClick() }
            )
            .padding(horizontal = 12.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier.width(24.dp),
            contentAlignment = Alignment.Center
        ) {
            if (active) {
                CompactBarsIndicator(playing = playing, color = colors.primary.readableOn(colors.row, 3.2f))
            } else {
                Text(
                    "${index + 1}",
                    color = colors.subText,
                    fontSize = 11.sp,
                    textAlign = TextAlign.Center,
                    maxLines = 1
                )
            }
        }
        Spacer(Modifier.width(6.dp))
        Column(Modifier.weight(1f)) {
            Text(
                song.title,
                color = if (active) colors.primary.readableOn(colors.row, 3.2f) else colors.text,
                fontSize = 13.sp,
                fontWeight = if (active) FontWeight.Bold else FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(1.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    song.artist,
                    color = colors.subText,
                    fontSize = 10.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
                if (song.artist.isNotBlank()) Spacer(Modifier.width(6.dp))
                Text(
                    fmtCompact(song.durationMs),
                    color = colors.subText,
                    fontSize = 10.sp
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    song.formatLabel,
                    color = colors.primary.readableOn(colors.row, 3.2f).copy(alpha = 0.8f),
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .border(
                            width = 1.dp,
                            color = colors.primary.copy(alpha = 0.4f),
                            shape = RoundedCornerShape(4.dp)
                        )
                        .padding(horizontal = 4.dp, vertical = 1.dp)
                )
            }
        }
    }
}

@Composable
private fun CompactBarsIndicator(playing: Boolean, color: Color) {
    val phase = remember { Animatable(0f) }
    LaunchedEffect(playing) {
        if (playing) {
            phase.animateTo(
                targetValue = phase.value + 4f * 1000f,
                animationSpec = infiniteRepeatable(
                    animation = tween(880, easing = LinearEasing),
                    repeatMode = androidx.compose.animation.core.RepeatMode.Restart
                )
            )
        }
    }
    Canvas(Modifier.size(width = 14.dp, height = 14.dp)) {
        if (!playing) {
            drawRoundRect(color, Offset(2f, size.height * 0.45f), Size(3f, size.height * 0.55f), CornerRadius(1.5f))
            drawRoundRect(color, Offset(6f, size.height * 0.25f), Size(3f, size.height * 0.75f), CornerRadius(1.5f))
            drawRoundRect(color, Offset(10f, size.height * 0.55f), Size(3f, size.height * 0.45f), CornerRadius(1.5f))
            return@Canvas
        }
        val p = phase.value
        for (i in 0 until 3) {
            val t = (p + i * 1.33f) % 4f
            val h = when {
                t < 1f -> 0.35f + 0.65f * t
                else -> 1f - 0.65f * ((t - 1f) / 3f)
            }
            val barH = size.height * h
            drawRoundRect(
                color = color,
                topLeft = Offset(2f + i * 4f, (size.height - barH) / 2f),
                size = Size(3f, barH),
                cornerRadius = CornerRadius(1.5f)
            )
        }
    }
}

@Composable
private fun CompactPlayButton(
    label: String,
    colors: IrisColors,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    primary: Boolean = false,
    onClick: () -> Unit
) {
    val btnColor = if (primary) colors.primary else colors.card
    Box(
        modifier
            .size(if (primary) 42.dp else 36.dp)
            .clip(RoundedCornerShape(percent = 50))
            .background(btnColor)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                enabled = enabled,
                onClick = { Haptics.tap(); onClick() }
            ),
        contentAlignment = Alignment.Center
    ) {
        Text(
            label,
            color = if (primary) colors.primary.readableTextOn() else colors.text,
            fontSize = if (primary) 16.sp else 14.sp
        )
    }
}

@Composable
private fun CompactSeekBar(
    progress: Float,
    enabled: Boolean,
    onSeek: (Float) -> Unit,
    modifier: Modifier = Modifier,
    colors: IrisColors
) {
    val trackColor = colors.card
    val fillColor = colors.primary
    val dragPos = remember { mutableFloatStateOf(-1f) }
    val shown = if (dragPos.floatValue >= 0f) dragPos.floatValue else progress
    Box(
        modifier
            .fillMaxWidth()
            .height(18.dp)
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                detectTapGestures { offset ->
                    onSeek((offset.x / size.width).coerceIn(0f, 1f))
                }
            }
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                detectHorizontalDragGestures(
                    onDragStart = { dragPos.floatValue = (it.x / size.width).coerceIn(0f, 1f) },
                    onHorizontalDrag = { change, _ ->
                        change.consume()
                        dragPos.floatValue = (change.position.x / size.width).coerceIn(0f, 1f)
                    },
                    onDragEnd = {
                        onSeek(dragPos.floatValue)
                        dragPos.floatValue = -1f
                    },
                    onDragCancel = { dragPos.floatValue = -1f }
                )
            }
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val trackY = (size.height - 3.dp.toPx()) / 2f
            drawRoundRect(
                trackColor,
                Offset(0f, trackY),
                Size(size.width, 3.dp.toPx()),
                CornerRadius(1.5.dp.toPx())
            )
            drawRoundRect(
                fillColor,
                Offset(0f, trackY),
                Size(size.width * shown, 3.dp.toPx()),
                CornerRadius(1.5.dp.toPx())
            )
        }
    }
}

private fun fmtCompact(ms: Long): String {
    val t = ms / 1000
    return "%d:%02d".format(t / 60, t % 60)
}

// ==================== 单张卡 ====================

/**
 * 卡片模式里的一张卡。
 *
 * 非当前歌曲的卡上，所有控制都退化成"跳到这首歌"。
 */
@Composable
private fun DeckCard(
    song: Song,
    isCurrent: Boolean,
    state: PlayerUiState,
    colors: IrisColors,
    onActivate: () -> Unit,
    onToggle: () -> Unit,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onSeek: (Float) -> Unit,
    onToggleLike: (Long) -> Unit,
    onCyclePlayMode: () -> Unit,
    onToggleVisualizer: () -> Unit,
    onOpenEqualizer: () -> Unit,
    onOpenSleepTimer: () -> Unit,
    onClickArtwork: () -> Unit,
    onOpenComments: () -> Unit,
    eqActive: Boolean,
    modifier: Modifier = Modifier,
    fullWidth: Boolean = true
) {
    Box(
        modifier
            .then(if (fullWidth) Modifier.fillMaxWidth() else Modifier),
        contentAlignment = Alignment.Center
    ) {
        PlayerCard(
            title = song.title,
            artist = song.artist,
            positionMs = if (isCurrent) state.positionMs else 0L,
            durationMs = if (isCurrent && state.durationMs > 0) state.durationMs else song.durationMs,
            shuffle = state.shuffle,
            repeatMode = state.repeatMode,
            artworkPath = song.filePath,
            artworkUri = song.albumArtUri,
            defaultCoverUri = state.defaultCoverUri,
            progress = if (isCurrent) state.progress else 0f,
            isPlaying = isCurrent && state.isPlaying,
            onToggle = if (isCurrent) onToggle else onActivate,
            onPrev = if (isCurrent) onPrev else onActivate,
            onNext = if (isCurrent) onNext else onActivate,
            onSeek = onSeek,
            onCyclePlayMode = if (isCurrent) onCyclePlayMode else onActivate,
            onToggleLike = { if (isCurrent) onToggleLike(song.id) else onActivate() },
            liked = song.id in state.likedSongIds,
            onClickArtwork = if (isCurrent) onClickArtwork else onActivate,
            // 评论按钮：非当前卡 → 切到这首；当前卡 → 打开评论
            onOpenComments = if (isCurrent) onOpenComments else onActivate,
            colors = colors,
            modifier = Modifier.fillMaxWidth(),
            sleepTimerMs = state.sleepTimerMs,
            sleepTimerEndMs = state.sleepTimerEndMs,
            onOpenSleepTimer = if (isCurrent) onOpenSleepTimer else onActivate,
            onOpenEqualizer = if (isCurrent) onOpenEqualizer else onActivate,
            eqActive = eqActive,
            audioFormat = song.formatLabel,
            visualizerEnabled = state.visualizerEnabled,
            tiltSpectrum = state.tiltSpectrum,
            coverShakeEnabled = state.coverShake,
            coverLyricEnabled = state.coverLyric,
            onToggleVisualizer = if (isCurrent) onToggleVisualizer else onActivate,
            interactive = isCurrent
        )
    }
}
