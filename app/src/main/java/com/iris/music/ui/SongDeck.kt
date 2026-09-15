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
 * 卡片模式共用同一张卡（[PlayerCard]），区别只在怎么摆：
 * - [IrisLayout.CAROUSEL] 横向排布：卡片横向铺开，两侧露出邻卡，左右滑翻歌
 *
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
    // 三档透明度：静止 2 秒淡到 30%；交互内容区（拖动卡片等）升到 50%；
    // 点顶栏本身升到 90%。任一交互都重置 2 秒回落计时。
    // 顶栏整块消费点击（含空白区），不再穿透到后面的卡片。
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
    // 点顶栏与"动别处"会在同一次按下里同时 ++（覆盖全屏的内容观察层 requireUnconsumed=false
    // 必须穿透内容层自身消费，故点顶栏也会 contentTick++）。用时间戳让"点顶栏"压制同帧的
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
            onToggleVisualizer = if (isCurrent) onToggleVisualizer else onActivate,
            interactive = isCurrent
        )
    }
}
