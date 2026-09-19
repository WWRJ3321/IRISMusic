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

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
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
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
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
 *
 * 没有歌单也没有上栏，所以顶部留一行极简 chrome（队列位置 + 返回列表 / 报告 / 设置），
 * 否则进了这个模式就再也回不去、也够不到设置。
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
    onToggleShuffle: () -> Unit,
    onCycleRepeat: () -> Unit,
    onToggleVisualizer: () -> Unit,
    onOpenEqualizer: () -> Unit,
    onOpenSleepTimer: () -> Unit,
    onOpenReport: () -> Unit,
    onOpenSettings: () -> Unit,
    onCycleLayout: () -> Unit,
    onOpenPlaylists: () -> Unit,
    onReload: () -> Unit,
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
    var lyricLines by remember { mutableStateOf<List<LyricLine>>(emptyList()) }
    var lyricLoading by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

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
                    .navigationBarsPadding()
            ) {
                DeckTopBar(
                    colors = colors,
                    activePlaylist = state.activePlaylistId != null,
                    refreshing = state.refreshing,
                    onCycleLayout = onCycleLayout,
                    onOpenReport = onOpenReport,
                    onOpenSettings = onOpenSettings,
                    onOpenPlaylists = onOpenPlaylists,
                    onReload = onReload
                )

                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    if (state.queue.isEmpty()) {
                        Text(
                            if (state.loading) "读取音乐库…" else "队列是空的\n到设置里换个文件夹或取消筛选",
                            color = colors.subText,
                            fontSize = 13.sp,
                            lineHeight = 19.sp,
                            textAlign = TextAlign.Center
                        )
                    } else if (state.layout == IrisLayout.STACK) {
                        DeckStack(
                            state = state,
                            colors = colors,
                            onToggle = onToggle,
                            onPrev = onPrev,
                            onNext = onNext,
                            onSeek = onSeek,
                            onSelect = onSelect,
                            onToggleLike = onToggleLike,
                            onToggleShuffle = onToggleShuffle,
                            onCycleRepeat = onCycleRepeat,
                            onToggleVisualizer = onToggleVisualizer,
                            onOpenEqualizer = onOpenEqualizer,
                            onOpenSleepTimer = onOpenSleepTimer,
                            onClickArtwork = openLyrics,
                            eqActive = eqActive
                        )
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
                            onToggleShuffle = onToggleShuffle,
                            onCycleRepeat = onCycleRepeat,
                            onToggleVisualizer = onToggleVisualizer,
                            onOpenEqualizer = onOpenEqualizer,
                            onOpenSleepTimer = onOpenSleepTimer,
                            onClickArtwork = openLyrics,
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
    modifier: Modifier = Modifier
) {
    // 与主界面上栏完全一致：左 IRIS MUSIC 标题、右五个同款按钮，唯一区别是没有搜索框。
    // 三档透明度：静止 2 秒淡到 30%；交互内容区（拖动海报墙等）升到 50%；
    // 点顶栏本身升到 90%。任一交互都重置 2 秒回落计时。
    // 顶栏整块消费点击（含空白区），不再穿透到后面的海报墙/卡片。
    var interactionTick by remember { mutableIntStateOf(0) }   // 点顶栏
    val barAlpha = remember { Animatable(0.9f) }               // 进场算顶栏档，先亮
    val barScope = rememberCoroutineScope()
    var idleJob by remember { mutableStateOf<Job?>(null) }
    fun bump(target: Float) {
        idleJob?.cancel()
        // 先取消可能仍在跑的回落动画，再启动目标淡入（同 Animatable 新 animateTo 会接管）
        barScope.launch { barAlpha.animateTo(target, animationSpec = tween(250)) }
        idleJob = barScope.launch {
            delay(2000)
            barAlpha.animateTo(0.3f, animationSpec = tween(300))
        }
    }
    LaunchedEffect(Unit) {
        // 进场：2 秒无操作淡到 30%
        idleJob = barScope.launch { delay(2000); barAlpha.animateTo(0.3f, animationSpec = tween(300)) }
    }
    // 点顶栏与"动别处"会在同一次按下里同时 ++（覆盖全屏的海报墙观察层 requireUnconsumed=false
    // 必须穿透海报墙自身消费，故点顶栏也会 contentTick++）。用时间戳让"点顶栏"压制同帧的
    // "动别处"，保证点顶栏恒为 90% 而非被 50% 覆盖。
    var lastBarTouchAt by remember { androidx.compose.runtime.mutableLongStateOf(0L) }
    LaunchedEffect(interactionTick) {
        if (interactionTick > 0) { lastBarTouchAt = System.nanoTime(); bump(0.9f) }  // 点顶栏 → 90%
    }
    LaunchedEffect(contentTick) {
        if (contentTick > 0 && System.nanoTime() - lastBarTouchAt > 80_000_000L) bump(0.5f)  // 动别处 → 50%
    }
    Box(
        modifier
            .fillMaxWidth()
            .graphicsLayer { this.alpha = barAlpha.value }
            .padding(top = 48.dp)
            .padding(horizontal = 22.dp)
            // 唤醒：顶栏范围内任意按下（requireUnconsumed=false → 点按钮也算）都重置计时，
            // 但不消费事件，子按钮照常响应。
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        awaitFirstDown(requireUnconsumed = false)
                        interactionTick++
                    }
                }
            }
            // 防穿透：空白/标题处点击在此消费，不再穿到后面的内容。子按钮会优先消费自己的点击。
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
            // 标题：与主界面上栏同款 —— IRIS(primary) + MUSIC(secondary)
            Row(verticalAlignment = Alignment.CenterVertically) {
                val wordPrimary = colors.primary.readableOn(colors.surface)
                val wordSecondary = colors.secondary.readableOn(colors.surface)
                Text("IRIS", color = wordPrimary, fontSize = 17.sp, fontWeight = FontWeight.Black)
                Spacer(Modifier.width(4.dp))
                Text("MUSIC", color = wordSecondary, fontSize = 17.sp, fontWeight = FontWeight.Black)
            }
            // 按钮组：与主界面上栏完全同款同序（无搜索框）
            Row(verticalAlignment = Alignment.CenterVertically) {
                PlaylistButton(onClick = onOpenPlaylists, colors = colors, active = activePlaylist)
                Spacer(Modifier.width(8.dp))
                LayoutButton(onClick = onCycleLayout, colors = colors)
                Spacer(Modifier.width(8.dp))
                ReportButton(onClick = onOpenReport, colors = colors)
                Spacer(Modifier.width(8.dp))
                RefreshButton(onClick = onReload, colors = colors, isRefreshing = refreshing)
                Spacer(Modifier.width(8.dp))
                SettingsButton(onClick = onOpenSettings, colors = colors)
            }
        }
    }
}

/**
 * 切换排布：纵向 → 横向 → 堆叠 → 纵向循环。
 *
 * 画的是"下一种排布"的示意（复用上栏同款风格自绘），卡片模式下这是
 * 唯一的排布入口——循环保证按几下总能回列表。列表模式上栏的同一枚按钮
 * 在 MainScreen.kt，两者长得一样，用户认知上是一枚按钮。
 */
@Composable
internal fun LayoutButton(onClick: () -> Unit, colors: IrisColors, plain: Boolean = false) {
    Box(
        Modifier
            .size(34.dp)
            .then(if (plain) Modifier else Modifier.irisSurface(GlassLevel.CHIP, colors, IrisShape.chip))
            .irisPressable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Canvas(Modifier.size(17.dp)) {
            val c = colors.primary
            val stroke = 1.2.dp.toPx()
            val gap = 1.5.dp.toPx()
            val cw = (size.width - gap * 2f) / 3f
            val ch = size.height * 0.68f
            val cy = (size.height - ch) / 2f
            // 三张并排竖卡：中间满高，两侧矮一截——"换排布"看形状就懂
            for (i in 0..2) {
                val hh = if (i == 1) ch else ch * 0.62f
                drawRoundRect(
                    color = if (i == 1) c else c.copy(alpha = 0.45f),
                    topLeft = Offset(i * (cw + gap), (size.height - hh) / 2f),
                    size = Size(cw, hh),
                    cornerRadius = CornerRadius(1.5.dp.toPx(), 1.5.dp.toPx())
                )
            }
        }
    }
}

// ==================== 横向排布 ====================

/** 两侧留给邻卡的宽度：小了看不出旁边还有卡，大了当前卡就被挤得太窄 */
private val CAROUSEL_PEEK = 38.dp

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
    onToggleShuffle: () -> Unit,
    onCycleRepeat: () -> Unit,
    onToggleVisualizer: () -> Unit,
    onOpenEqualizer: () -> Unit,
    onOpenSleepTimer: () -> Unit,
    onClickArtwork: () -> Unit,
    eqActive: Boolean
) {
    val queue = state.queue
    val pagerState = rememberPagerState(
        initialPage = state.currentIndex.coerceAtLeast(0).coerceAtMost(queue.lastIndex.coerceAtLeast(0)),
        pageCount = { queue.size }
    )
    // 下面两个 LaunchedEffect 里要读最新值，直接捕获会锁死启动那一帧的快照
    val liveIndex = rememberUpdatedState(state.currentIndex)

    // 自动播完 / 通知栏切歌：卡片跟着滑过去
    LaunchedEffect(state.currentIndex) {
        val target = state.currentIndex
        if (target in queue.indices && target != pagerState.currentPage) {
            pagerState.animateScrollToPage(target)
        }
    }

    // 翻到哪张卡就放哪首。首次发射要跳过：那只是初始页，不是用户翻的，
    // 否则一进卡片模式就会自动开始播放。
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
                onToggleShuffle = onToggleShuffle,
                onCycleRepeat = onCycleRepeat,
                onToggleVisualizer = onToggleVisualizer,
                onOpenEqualizer = onOpenEqualizer,
                onOpenSleepTimer = onOpenSleepTimer,
                onClickArtwork = onClickArtwork,
                eqActive = eqActive,
                // 距离在 graphicsLayer 里算：这个 lambda 跑在绘制阶段，
                // 滑动时只重绘不重组（写在外面每一帧都要重组一整张卡）
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

/**
 * 后面露出几张。每张都是完整的 [PlayerCard]，张数直接乘绘制开销
 * （液态玻璃下每张还是一层真折射），所以给到三层就停。
 * 另外还会多画一张"上一首"（见 d = -1），同屏共 4 张。
 */
private const val STACK_DEPTH = 2

/**
 * 每往后一档上移多少。
 *
 * 必须明显大于缩放造成的内缩，否则后面的卡会被前面那张完全盖住：
 * 播放卡有 600dp 上下的高度，缩 1% 顶边就往下走 3dp，
 * 两者方向相反，抵消完剩下的才是能看见的那条边。
 */
private val STACK_STEP_Y = 16.dp

/** 每往后一档缩小的比例。给得很小，原因同 [STACK_STEP_Y] */
private const val STACK_SCALE_STEP = 0.02f

/** 两侧留白。与列表模式的播放页一致——卡片是居中的，不需要为错位额外留边 */
private val STACK_SIDE_PADDING = 26.dp

/**
 * 提交阈值占容器宽度的比例，以及它的下限。
 *
 * 原先写死 92dp。死值在窄屏上偏难划（要走掉四分之一屏宽），
 * 而阈值偏大最直接的表现就是"用力划了但没反应"。
 */
private const val STACK_THROW_FRACTION = 0.18f
private val STACK_THROW_MIN = 56.dp

/** 跟手倾斜的最大角度。几度就够有"抽卡"的手感，多了像坏掉 */
private const val STACK_TILT = 6f
/** 或者甩得够快也算（px/s）。距离不够但手势方向明确时，弹回去是错的 */
private const val STACK_FLING_VELOCITY = 420f

/**
 * 切歌所需的最小位移（px 见 minDragPx）。
 * 之前只要卡片挪动 ≥1px 就认定方向、再叠加极低的 fling 阈值，
 * 于是"手碰一下、轻轻挪一点"也会切歌。要求拖过这个门槛才可能切，
 * 没到就一律弹回，手只是拿稳/微挪不触发切歌。
 */
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
    onToggleShuffle: () -> Unit,
    onCycleRepeat: () -> Unit,
    onToggleVisualizer: () -> Unit,
    onOpenEqualizer: () -> Unit,
    onOpenSleepTimer: () -> Unit,
    onClickArtwork: () -> Unit,
    eqActive: Boolean
) {
    val queue = state.queue
    val base = state.currentIndex.coerceAtLeast(0).coerceAtMost(queue.lastIndex.coerceAtLeast(0))

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val stepY = with(density) { STACK_STEP_Y.toPx() }
        val flyOut = with(density) { maxWidth.toPx() }
        val throwPx = maxOf(
            flyOut * STACK_THROW_FRACTION,
            with(density) { STACK_THROW_MIN.toPx() }
        )
        // 切歌最小位移门槛：低于它无论甩多快都不切，直接弹回
        val minDragPx = with(density) { STACK_SWIPE_MIN.toPx() }

        // 手势/动画位移。只在 graphicsLayer 里读（绘制阶段），拖动不触发重组。
        var cardX by remember { mutableFloatStateOf(0f) }

        /**
         * 这一沓当前摆在最上面的是第几首。
         *
         * 不能直接用 [base]：`onSelect` 要绕一圈 MediaController 才会把 currentIndex 传回来，
         * 那一两帧里旧卡已经被甩出屏幕、新卡还没顶上来，看着就是"卡片凭空消失"。
         */
        var visualBase by remember { mutableIntStateOf(base) }

        /**
         * 正在提交（卡片飞出 / 上一张归位）。这段时间必须锁住手势。
         *
         * 之前允许中途打断，于是提交协程被取消，[cardX] 停在半空、[visualBase] 也没推进。
         * 下一次手势带着这个残留位移一起判定，就出现了两种症状：
         * 上一次滑动像"没生效"（其实是提交被自己的第二次触摸掐掉了），
         * 以及反向轻轻一碰就跨过阈值、甚至朝残留位移的方向切歌（"多划两个 / 划错方向"）。
         */
        var committing by remember { mutableStateOf(false) }
        val scope = rememberCoroutineScope()
        var snapJob by remember { mutableStateOf<Job?>(null) }
        /** 提交飞出协程。句柄存下来，是为了下一次拖拽能在 onDragStarted 里打断它。 */
        var commitJob by remember { mutableStateOf<Job?>(null) }

        // 外部改了当前曲目（自动播完、通知栏切歌、库刷新）时对齐。
        // committing 也在 key 里：提交结束后要再跑一次，让乐观值与真实值收敛。
        // 但不能立刻对齐——onSelect 要绕 MediaController 一圈才把 base 传回来，
        // 上一首的提交动画很短，结束那一刻 base 往往还是旧值，直接重置会把
        // visualBase 弹回上一首，等 base 到了再跳回来：先跳回再跳来，就是
        // "切上一曲卡片抖一下"。等一小会儿，base 没追上来才真的需要对齐。
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

        // 用 draggable 而不是 detectHorizontalDragGestures：只有它在松手时给出速度。
        // 没有速度就只能靠位移判定，快速轻甩会被判成"没拖够"而弹回去。
        val dragState = rememberDraggableState { delta -> cardX += delta }

        Box(
            Modifier
                .fillMaxSize()
                .draggable(
                    state = dragState,
                    orientation = Orientation.Horizontal,
                    onDragStarted = {
                        // 回弹与上一次的提交飞出都可被新拖拽打断：取消后提交协程的 finally
                        // 会把 visualBase 推到目标、cardX 归零，于是这次按下立即从新卡的静止位
                        // 开始跟手——连续快速滑动不再被"等飞出动画播完"卡住（旧版这里靠
                        // enabled=!committing 全程锁死 draggable，飞出动画 StiffnessLow 飞满
                        // 整屏要几百 ms，就是那段"滑完要等一下"的僵直来源）。
                        snapJob?.cancel()
                        commitJob?.cancel()
                    },
                    onDragStopped = { velocity ->
                        // 位移没超过最小门槛：手只是微挪/拿稳，一律弹回，不切歌
                        if (abs(cardX) < minDragPx) {
                            scope.launch {
                                Animatable(cardX, visibilityThreshold = 0.5f).animateTo(
                                    0f, spring(stiffness = Spring.StiffnessMediumLow)
                                ) { cardX = value }
                            }
                            return@draggable
                        }
                        // 方向以位移为准；几乎没位移的快甩用速度定向
                        val dir = when {
                            cardX <= -1f -> -1
                            cardX >= 1f -> 1
                            velocity < 0f -> -1
                            velocity > 0f -> 1
                            else -> 0
                        }
                        val strongFling = abs(velocity) > STACK_FLING_VELOCITY
                        val velDir = if (velocity < 0f) -1 else 1
                        // 甩得够快就按方向算，但必须与位移同向：
                        // "先往左拖、又往右弹回去"不该被判成切歌
                        val pass = dir != 0 &&
                            if (strongFling) velDir == dir else abs(cardX) >= throwPx
                        val rawTarget = when {
                            !pass -> -1
                            dir < 0 -> top + 1
                            else -> top - 1
                        }
                        // 到头/尾时循环（仅 ALL 模式）。OFF 模式弹回原位。
                        val target = when {
                            rawTarget !in queue.indices && state.repeatMode == RepeatMode.ALL -> {
                                if (rawTarget > queue.lastIndex) 0 else queue.lastIndex
                            }
                            else -> rawTarget
                        }

                        if (target !in queue.indices) {
                            // 到头了且不循环：带着手上的速度弹回原位
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
                            // 先置位再启协程：committing 决定 draggable 的 enabled，
                            // 放进协程里会晚一帧生效，那一帧足够挤进第二次手势。
                            committing = true
                            Haptics.click()
                            // 声音立刻跟上，不等动画。晚 200ms 换歌是能听出来的。
                            onSelect(target)
                            commitJob = scope.launch {
                                try {
                                    // 落点就是"下一帧的静止姿态"：
                                    // 下一首要把这张送出屏幕（-flyOut，正好是 d=-1 的停放点）；
                                    // 上一首是把回来那张拉到正中（走满一档即 throwPx）。
                                    // 所以提交时位移直接归零，画面上没有任何跳变。
                                    // spring 而不是 tween：速度从手上传下来，起跑无缝，
                                    // 曲线自然减速。参数偏"软"：
                                    // - StiffnessLow：飞出比 StiffnessMedium 慢一拍，之前太急
                                    // - 初速只接 35%：手甩速度直接全量传进去会让收尾猛冲，
                                    //   掐掉大头后动画由弹簧自己走，节奏稳定
                                    //
                                    // 上一首与下一首用同一条 spring、走同等的剩余行程：
                                    // 下一首 -flyOut、上一首 +flyOut（不是 throwPx——
                                    // throwPx 离手上的位置太近，弹簧几帧就走完）。
                                    // 上一首卡滑满剩余整屏的时间 = 下一首卡飞出的时间。
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
                                    // 即使协程被取消（退出卡片模式等）也要收干净，
                                    // 留下非零位移就会把下一次手势判错
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
            // 从最里层往外画：d 越大越靠后，越晚画的盖在越上面。
            //
            // 多画一张 d = -1（上一首）：它平时停在屏幕左外，右划时跟着手指滑进来。
            // 之前右划看着没动画就是因为少了它——被"推回来"的那张卡根本不在画面里，
            // 只能等状态更新后凭空出现。
            for (d in STACK_DEPTH downTo -1) {
                val index = top + d
                val song = queue.getOrNull(index) ?: continue
                val depth = d.toFloat()

                // key(index)：状态跟歌走，不跟槽位走。
                // 没有它时槽位 d=0 的 remember（封面等）属于"这个位置"，
                // visualBase 推进后新歌顶上来，位置上还是旧歌的封面状态——
                // 先闪一帧上一首的封面再换，就是"闪一下上一首歌"的来源。
                key(index) {

                Box(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = STACK_SIDE_PADDING)
                        .graphicsLayer {
                            // 手势进度：-1 = 左划满（下一首），+1 = 右划满（上一首）
                            val t = (cardX / throwPx).coerceIn(-1f, 1f)

                            if (d < 0) {
                                // 上一首卡：1:1 跟手（x = cardX - flyOut）。
                                // cardX=0 停在屏幕左外；提交动画把 cardX 推到 +flyOut
                                // 时正好落回正中央。之前用 -flyOut*(1-t)：手指只需走
                                // throwPx（~56dp）t 就到 1，它整屏横穿在指下完成，
                                // 右滑全程被压进这一小段，是"右滑比左滑快太多"的根源。
                                // 跟手后拖动阶段它只从左缘探进来一点（同左滑时顶卡
                                // 左移一点），剩余行程交给提交动画——与左滑完全对称。
                                //
                                // 倾斜：行程中段最陡、两端归零。停泊时若带倾角，
                                // 600dp 高的卡转角会往右凸 ~30dp，盖过 26dp 侧边距，
                                // 左侧会露出一条边。
                                val u = (cardX / flyOut).coerceIn(0f, 1f)
                                translationX = cardX - flyOut
                                rotationZ = -STACK_TILT * 4f * u * (1f - u)
                                return@graphicsLayer
                            }

                            if (d == 0) {
                                // 顶卡只有左划跟手（滑向下一首）。
                                // 右划不横移：顶卡留在横向原地，但参与档位动画
                                // （走下面的 eff 公式，跟着整沓下移一档）。
                                // 之前这里无条件 return，右划时顶卡钉死不动，
                                // 提交后 visualBase 减一它瞬间从 eff=0 跳到 eff=1
                                // ——就是"中间那张突然闪现"的来源。
                                if (cardX < 0f) {
                                    translationX = cardX
                                    rotationZ = t * STACK_TILT
                                    return@graphicsLayer
                                }
                            }

                            // 档位：左划时整沓往前顶一档，右划时往后退一档。
                            // d=0 也参与：右划是"把这张塞回去"，它该退到第二层，
                            // 而不是跟着手指跑出屏幕。
                            val eff = (depth + t).coerceAtLeast(0f)
                            translationY = -stepY * eff
                            val s = 1f - STACK_SCALE_STEP * eff
                            scaleX = s
                            scaleY = s
                            // 透明度在比渲染深度再深 0.6 档处归零，于是最深那张本身就很淡。
                            // 往下一首推进时队尾会补进一张新卡（再深一档没有渲染），
                            // 淡的层次让这次补位几乎看不出来。
                            alpha = (1f - (eff / (STACK_DEPTH + 0.6f)).pow(1.4f))
                                .coerceIn(0f, 1f)
                        }
                ) {
                    DeckCard(
                        song = song,
                        // 最上面那张就是"正在放的那首"。不写 index == state.currentIndex：
                        // 乐观推进的那一两帧里两者不等，进度条会瞬间归零再跳回来。
                        isCurrent = d == 0,
                        state = state,
                        colors = colors,
                        onActivate = { onSelect(index) },
                        onToggle = onToggle,
                        onPrev = onPrev,
                        onNext = onNext,
                        onSeek = onSeek,
                        onToggleLike = onToggleLike,
                        onToggleShuffle = onToggleShuffle,
                        onCycleRepeat = onCycleRepeat,
                        onToggleVisualizer = onToggleVisualizer,
                        onOpenEqualizer = onOpenEqualizer,
                        onOpenSleepTimer = onOpenSleepTimer,
                        onClickArtwork = onClickArtwork,
                        eqActive = eqActive,
                        fullWidth = false
                    )
                    // 其它卡整块不接受点击——露出来的那条边误触一下就跳歌，比它带来的便利更烦。
                    // 抽卡手势已经覆盖切歌，非当前卡不需要任何交互。
                }
                } // key(index)
            }
        }
    }
}

// ==================== 紧凑排布 ====================

/**
 * 紧凑排布：一屏看完整个队列。
 *
 * 借鉴 Folia 离线播放器的信息设计：一行 = 序号 + 标题 + 元信息（艺术家 · 时长 · 格式），
 * 不放封面不放点赞性价比最高；但用 IRIS 自己的材质体系（irisSurface + 可选液态玻璃）
 * 实现，并且列表本身参与折射背板——毛玻璃/液态玻璃模式下迷你播放条会折射到列表内容。
 *
 * 底部是常驻迷你播放条（标题 + 上一首/播放/下一首 + 细进度条），
 * 点标题/封面区开歌词浮层。与 Folia 的差异：进度条可拖动 seek。
 */
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
    // 自动切歌时列表滚到当前行，浏览位置会被抢走——只在"外部变化且仍在视口外"时才滚
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
                // 底部预留迷你播放条高度 + 安全区
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
            // 细进度条（可拖动 seek）：宽度 = 位置/时长
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
                // 三个按钮：⏮ ▶/⏸ ⏭ —— 项目不引图标库，用文字符号保持一致
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

/** 紧凑模式的歌曲行：序号/跳动条 + 标题 + 元信息，无封面无点赞（Folia 式极简） */
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
        // 左侧 22dp 槽位：普通行放序号，播放行放跳动条
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
                // 格式徽章（Folia 的 LRC 徽章同理，这里展示音频格式）
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

/** 播放中的跳动条指示器（3 根柱子，随播放/暂停启停） */
@Composable
private fun CompactBarsIndicator(playing: Boolean, color: Color) {
    // 相位只在 playing=true 时推进。
    // 原先用 rememberInfiniteTransition：即使暂停（画的是静态柱子）动画也照跑，
    // 每帧触发一次无意义重组——这在列表里是持续的后台耗电。
    // 改成 Animatable + LaunchedEffect(playing)：暂停即协程挂起，彻底零开销。
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
            // 暂停：三根静态柱子，仍然比纯序号醒目
            drawRoundRect(color, Offset(2f, size.height * 0.45f), Size(3f, size.height * 0.55f), CornerRadius(1.5f))
            drawRoundRect(color, Offset(6f, size.height * 0.25f), Size(3f, size.height * 0.75f), CornerRadius(1.5f))
            drawRoundRect(color, Offset(10f, size.height * 0.55f), Size(3f, size.height * 0.45f), CornerRadius(1.5f))
            return@Canvas
        }
        // 三根柱子相位错开 1.33，视觉上此起彼伏
        val p = phase.value
        for (i in 0 until 3) {
            val t = (p + i * 1.33f) % 4f
            // t: 0→1→3→4 映射高度 0.35→1→0.35（快升慢降）
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

/** 迷你播放条上的圆按钮 */
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

/** 迷你播放条的细进度条：轨道 3dp + 点击 seek */
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
    Box(
        modifier
            .fillMaxWidth()
            .height(18.dp)
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                detectTapGestures { offset ->
                    val ratio = (offset.x / size.width).coerceIn(0f, 1f)
                    onSeek(ratio)
                }
            }
    ) {
        // 轨道：稍加宽的命中区（18dp）内画 3dp 视觉轨道
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
                Size(size.width * progress, 3.dp.toPx()),
                CornerRadius(1.5.dp.toPx())
            )
        }
    }
}

/** 毫秒 → "m:ss" */
private fun fmtCompact(ms: Long): String {
    val t = ms / 1000
    return "%d:%02d".format(t / 60, t % 60)
}

// ==================== 单张卡 ====================

/**
 * 卡片模式里的一张卡。
 *
 * 非当前歌曲的卡上，所有控制都退化成"跳到这首歌"：显示别人的播放进度是错的，
 * 而进度条的水平拖动还会把翻页 / 抽卡手势整段吃掉（见 [PlayerCard.interactive]）。
 */
@Composable
private fun DeckCard(
    song: Song,
    isCurrent: Boolean,
    state: PlayerUiState,
    colors: IrisColors,
    /** 点非当前卡上的任意控制：先跳到这首歌 */
    onActivate: () -> Unit,
    onToggle: () -> Unit,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onSeek: (Float) -> Unit,
    onToggleLike: (Long) -> Unit,
    onToggleShuffle: () -> Unit,
    onCycleRepeat: () -> Unit,
    onToggleVisualizer: () -> Unit,
    onOpenEqualizer: () -> Unit,
    onOpenSleepTimer: () -> Unit,
    onClickArtwork: () -> Unit,
    eqActive: Boolean,
    modifier: Modifier = Modifier,
    /** true 时自己撑满可用宽度（横向排布由 Pager 给宽度），false 时由外层控制 */
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
            progress = if (isCurrent) state.progress else 0f,
            isPlaying = isCurrent && state.isPlaying,
            onToggle = if (isCurrent) onToggle else onActivate,
            onPrev = if (isCurrent) onPrev else onActivate,
            onNext = if (isCurrent) onNext else onActivate,
            onSeek = onSeek,
            onToggleShuffle = if (isCurrent) onToggleShuffle else onActivate,
            onCycleRepeat = if (isCurrent) onCycleRepeat else onActivate,
            onToggleLike = { if (isCurrent) onToggleLike(song.id) else onActivate() },
            liked = song.id in state.likedSongIds,
            onClickArtwork = if (isCurrent) onClickArtwork else onActivate,
            colors = colors,
            modifier = Modifier.fillMaxWidth(),
            sleepTimerMs = state.sleepTimerMs,
            sleepTimerEndMs = state.sleepTimerEndMs,
            onOpenSleepTimer = if (isCurrent) onOpenSleepTimer else onActivate,
            onOpenEqualizer = if (isCurrent) onOpenEqualizer else onActivate,
            eqActive = eqActive,
            audioFormat = song.formatLabel,
            // 频谱开关跟随全局状态，不带 isCurrent 条件：
            // 带 isCurrent 时切歌瞬间新顶卡 false→true、旧顶卡 true→false，
            // 两张卡一个淡入一个淡出（420ms morph），看着就是"关了再开"。
            // 所有卡都显示频谱区后，切歌只是内容无缝换人，无动画跳变。
            // 频谱数据来自全局单例，非当前卡画的内容与当前卡相同——
            // 它们被上面的卡压住只露一条边，重复绘制的开销可以忽略。
            visualizerEnabled = state.visualizerEnabled,
tiltSpectrum = state.tiltSpectrum,
             coverShakeEnabled = state.coverShake,
             coverLyricEnabled = state.coverLyric,
             onToggleVisualizer = if (isCurrent) onToggleVisualizer else onActivate,
            interactive = isCurrent
        )
    }
}
