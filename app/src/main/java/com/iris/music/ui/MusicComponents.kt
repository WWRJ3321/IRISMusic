package com.iris.music.ui

import androidx.compose.animation.*
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.EaseInOutCubic
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.tween
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
import androidx.compose.ui.window.Dialog
import com.iris.music.data.Recommender
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.collectLatest
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
import androidx.compose.ui.platform.LocalDensity
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
// ==================== 推荐与工具 ====================

internal fun formatDuration(ms: Long): String {
    val t = ms / 1000
    return "%d:%02d".format(t / 60, t % 60)
}

@Composable
internal fun RecommendationSection(
    recommendations: List<Song>,
    colors: IrisColors,
    onSelect: (Song) -> Unit,
    /** 卡片是否显示歌手（跟随设置「歌单行显示歌手」） */
    showArtist: Boolean = true
) {
    val onSheet = if (colors.isDark) Color.White else Color.Black
    val scrollState = rememberScrollState()

    Column(Modifier.padding(bottom = 8.dp)) {
        Row(
            Modifier.fillMaxWidth().padding(bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "为你推荐",
                color = onSheet,
                fontSize = 15.sp,
                fontWeight = FontWeight.Black
            )
        }
        // 横滑卡片：内容两端 alpha 渐变淡出（对内容本身混合，适配任何背景）
        Box {
            val fadeBrush = Brush.horizontalGradient(
                0.00f to Color.Transparent,
                0.04f to Color.Black,
                0.95f to Color.Black,
                1.00f to Color.Transparent
            )
            Row(
                Modifier
                    .fillMaxWidth()
                    .graphicsLayer { compositingStrategy = androidx.compose.ui.graphics.CompositingStrategy.Offscreen }
                    .drawWithCache {
                        onDrawWithContent {
                            drawContent()
                            drawRect(brush = fadeBrush, blendMode = BlendMode.DstIn)
                        }
                    }
                    .horizontalScroll(scrollState),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                recommendations.forEach { song ->
                    RecommendCard(
                        song = song,
                        colors = colors,
                        showArtist = showArtist,
                        onClick = { onSelect(song) }
                    )
                }
            }
        }
        Spacer(Modifier.height(14.dp))
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun RecommendCard(
    song: Song,
    colors: IrisColors,
    showArtist: Boolean = true,
    onClick: () -> Unit
) {
    val onSheet = if (colors.isDark) Color.White else Color.Black

    Column(
        Modifier
            .width(120.dp)
            .irisSurface(GlassLevel.CARD, colors, IrisShape.item)
            .clickable { Haptics.tap(); onClick() }
            .padding(10.dp)
    ) {
        // 封面：SongArtwork（异步加载 + HSV 占位 + 音符图标）
        SongArtwork(
            filePath = song.filePath,
            seedId = song.id,
            modifier = Modifier.fillMaxWidth().height(100.dp),
            isDark = colors.isDark,
            noteIcon = true
        )
        Spacer(Modifier.height(8.dp))
        Text(
            song.title,
            color = onSheet,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            // 固定占两行：无论歌名长短，卡片高度一致
            minLines = 2,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            lineHeight = 14.sp
        )
        if (showArtist) {
            Text(
                song.artist,
                color = colors.subText,
                fontSize = 11.sp,
                minLines = 1,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

// ==================== 歌曲封面 ====================

/**
 * 通用歌曲封面：异步加载内嵌封面，无封面时显示按歌曲 ID 生成的 HSV 占位色块。
 * 首帧先同步窥缓存（ArtworkLoader.peek），滚动复用/翻卡时命中缓存不闪占位。
 */
@Composable
internal fun SongArtwork(
    filePath: String?,
    seedId: Long,
    modifier: Modifier = Modifier,
    artworkSize: androidx.compose.ui.unit.Dp? = null,
    isDark: Boolean,
    /** 占位是否画音符图标（推荐卡要，行内小图不要） */
    noteIcon: Boolean = false
) {
    var bitmap by remember(filePath) { mutableStateOf(ArtworkLoader.peek(filePath)) }
    // 内存直接命中时不做淡入（图在首帧就位，淡入反而是多余的一次"闪"）；
    // 走磁盘/解码异步补上的才淡入，避免占位色块硬跳成封面。
    val cameFromCache = remember(filePath) { bitmap != null }
    LaunchedEffect(filePath) {
        ArtworkLoader.load(filePath)?.let { bitmap = it }
    }
    val fade by animateFloatAsState(
        targetValue = if (bitmap != null) 1f else 0f,
        animationSpec = if (cameFromCache) snap() else tween(220, easing = LinearEasing),
        label = "artworkFade"
    )
    val hue = (seedId % 360).toFloat()
    val placeholderColor = Color.hsv(hue, 0.35f, if (isDark) 0.18f else 0.92f)
    Box(
        modifier
            .then(artworkSize?.let { Modifier.size(it) } ?: Modifier)
            .clip(IrisShape.chip)
            .background(placeholderColor),
        contentAlignment = Alignment.Center
    ) {
        val bmp = bitmap
        if (bmp != null) {
            androidx.compose.foundation.Image(
                bitmap = bmp.asImageBitmap(),
                contentDescription = null,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer { alpha = fade },
                contentScale = androidx.compose.ui.layout.ContentScale.Crop
            )
        } else if (noteIcon) {
            Canvas(Modifier.size(28.dp)) {
                drawCircle(
                    color = Color.White.copy(alpha = 0.6f),
                    radius = size.minDimension * 0.28f,
                    center = Offset(size.width * 0.42f, size.height * 0.42f),
                    style = androidx.compose.ui.graphics.drawscope.Stroke(width = size.minDimension * 0.1f)
                )
                drawLine(
                    color = Color.White.copy(alpha = 0.6f),
                    start = Offset(size.width * 0.62f, size.height * 0.62f),
                    end = Offset(size.width * 0.88f, size.height * 0.88f),
                    strokeWidth = size.minDimension * 0.1f,
                    cap = androidx.compose.ui.graphics.StrokeCap.Round
                )
            }
        } else {
            Canvas(Modifier.size(artworkSize?.div(2) ?: 28.dp)) {
                drawCircle(
                    color = Color.White.copy(alpha = 0.6f),
                    radius = size.minDimension * 0.28f,
                    center = Offset(size.width * 0.42f, size.height * 0.42f),
                    style = androidx.compose.ui.graphics.drawscope.Stroke(width = size.minDimension * 0.1f)
                )
            }
        }
    }
}

// ==================== 快速滚动条 ====================

@Composable
internal fun FastScroller(
    listState: androidx.compose.foundation.lazy.LazyListState,
    colors: IrisColors,
    topPadding: androidx.compose.ui.unit.Dp,
    bottomPadding: androidx.compose.ui.unit.Dp,
visible: Boolean = true,
    onClick: () -> Unit = {},
     /** 索引 → 该行歌曲标题（用于拖动气泡），返回 null 表示无标签 */
    labelForIndex: (Int) -> String? = { null },
    onDraggingChange: (Boolean) -> Unit = {}
) {
    val onSheet = if (colors.isDark) Color.White else Color.Black
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current

    val minThumbPx = with(density) { 48.dp.toPx() }
    val edgeGapPx = with(density) { 4.dp.toPx() }
    val idleWpx = with(density) { 4.dp.toPx() }
    val activeWpx = with(density) { 9.dp.toPx() }
    val dragWpx = with(density) { 14.dp.toPx() }

    var isDragging by remember { mutableStateOf(false) }
    // 拖动时 thumb 跟手指的即时分数，不回读列表 scrollProgress（那有量化+延迟，会抽动）
    var dragFraction by remember { mutableStateOf(0f) }
    var trackH by remember { mutableStateOf(0f) }
    var dragIndex by remember { mutableStateOf(0) }
    LaunchedEffect(isDragging) { onDraggingChange(isDragging) }

    val totalItems by remember { derivedStateOf { listState.layoutInfo.totalItemsCount } }

    // 滚动进度：所有状态都在 lambda 内读取，保证列表滚动时实时更新
    val scrollProgress by remember {
        derivedStateOf {
            val info = listState.layoutInfo
            val total = info.totalItemsCount
            if (total <= 1) return@derivedStateOf 0f
            val first = info.visibleItemsInfo.firstOrNull() ?: return@derivedStateOf 0f
            val exactIndex = first.index +
                (if (first.size > 0) (-first.offset.toFloat() / first.size) else 0f)
            (exactIndex / (total - 1).coerceAtLeast(1)).coerceIn(0f, 1f)
        }
    }

    /** 第一可见行可达的最大索引（LazyList 滚到底会钳制在这里）。
     *  拖动目标必须先在 [0, max] 内钳制再换算回分数：若直接映射到 total-1，
     *  滚到底部区间会被列表强制钳回，列表停在原地而滑块继续跟手指——读回
     *  scrollProgress 时滑块从指尖位置回跳，即"拖过头再弹回来"。 */
    fun currentMaxFirstIndex(): Int {
        val info = listState.layoutInfo
        return (info.totalItemsCount - info.visibleItemsInfo.size).coerceAtLeast(0)
    }

    // 活跃态：滚动或拖动时展开，静止 1.1s 后自动收细
    val moving by remember { derivedStateOf { isDragging || listState.isScrollInProgress } }
    var active by remember { mutableStateOf(false) }
    LaunchedEffect(moving) {
        if (moving) active = true else { kotlinx.coroutines.delay(1100); active = false }
    }

    // 三条动画：展开（弹簧）/ 拖动强调（弹簧）/ 翻页淡出（补间）
    val expand by animateFloatAsState(
        targetValue = if (active) 1f else 0f,
        animationSpec = spring(dampingRatio = 0.8f, stiffness = Spring.StiffnessLow),
        label = "scrollerExpand"
    )
    val dragT by animateFloatAsState(
        targetValue = if (isDragging) 1f else 0f,
        animationSpec = spring(dampingRatio = 0.62f, stiffness = Spring.StiffnessMediumLow),
        label = "scrollerDrag"
    )
    val visAlpha by animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = tween(220),
        label = "scrollerAlpha"
    )

    // 滑块几何：thumbHpx 只随 trackH/总数变化（低频），留在组合层。
    // thumbYpx/thumbWpx 依赖每帧变化的 dragFraction / expand / dragT——
    // 若在组合层求值，拖动时每帧都触发整块重组（Canvas + 气泡）。改为在
    // 绘制/布局阶段（Canvas draw lambda、气泡 offset lambda）内读取这些状态，
    // 让拖动只走重绘、不走重组，是这条滑条跟手流畅度的关键。
    val maxThumbPx = (trackH * 0.35f).coerceAtLeast(minThumbPx)
    val thumbHpx = if (trackH > 0f)
        (trackH / totalItems.coerceAtLeast(1)).coerceIn(minThumbPx, maxThumbPx) else minThumbPx
    // 供 draw / offset 内复用的即时几何（读取快照状态，仅在对应阶段求值）
    fun currentThumbY(): Float =
        (if (isDragging) dragFraction else scrollProgress) * (trackH - thumbHpx).coerceAtLeast(0f)
    fun currentThumbW(): Float =
        idleWpx + (activeWpx - idleWpx) * expand + (dragWpx - activeWpx) * dragT

    // 拖动：只把手指 y 坐标换算成目标分数写入 dragFraction，不在这里启动滚动。
    // 真正的滚动由下方单一消费者 LaunchedEffect 驱动——每次拖动事件都 launch 一个
    // scrollToItem 协程会导致几十个协程并发竞争、乱序打断，这才是抽动的根因。
    fun updateDragFraction(y: Float) {
        if (trackH <= 0f) return
        val total = listState.layoutInfo.totalItemsCount
        if (total <= 1) return
        val span = trackH - thumbHpx
        val f = if (span > 1f) ((y - thumbHpx / 2f) / span).coerceIn(0f, 1f)
                else (y / trackH).coerceIn(0f, 1f)
        // 先把目标行钳到列表真正可达的范围（第一可见行最大 = total - 可见行数），
        // 再把钳后的值写回 dragFraction：滑块与列表停在同一处。
        // 若直通 f*(total-1) 映射到底，最后 1/14 行程列表已经滚不动、滑块却继续
        // 跟手指，松手切回读回的 scrollProgress 时滑块从指尖位置弹回去。
        val exact = (f * (total - 1)).coerceIn(0f, currentMaxFirstIndex().toFloat())
        dragFraction = exact / (total - 1)
        // 跨行刻度震动（滑过同一行不重复）
        val idx = exact.toInt().coerceIn(0, total - 1)
        if (idx != dragIndex) Haptics.tick()
        dragIndex = idx
    }

    // 单一消费者：拖动中订阅 dragFraction，用 collectLatest 让过时的滚动请求
    // 被自动取消，任何时刻只执行最新目标。scrollBy 走 UI 帧同步、无跳变。
    LaunchedEffect(isDragging) {
        if (!isDragging) return@LaunchedEffect
        snapshotFlow { dragFraction }
            .collectLatest { f ->
                val total = listState.layoutInfo.totalItemsCount
                if (total <= 1) return@collectLatest
                val exact = f * (total - 1)
                val targetIndex = exact.toInt().coerceIn(0, total - 1)
                val avgItemPx = listState.layoutInfo.visibleItemsInfo
                    .firstOrNull()?.size?.toFloat() ?: 0f
                val offsetPx = ((exact - targetIndex) * avgItemPx).toInt()
                listState.scrollToItem(targetIndex, offsetPx)
            }
    }

    // 触摸区：40dp 宽贴右缘，滑块绘制在最右侧
    Box(
        Modifier
            .fillMaxHeight()
            .width(40.dp)
            .padding(top = topPadding, bottom = bottomPadding)
.graphicsLayer { this.alpha = visAlpha }
             .clickable(
                 interactionSource = remember { MutableInteractionSource() },
                 indication = null,
                 enabled = visible,
                 onClick = onClick
             )
             .pointerInput(visible) {
                // 不可见时（翻到播放页/歌词页覆盖）不注册手势，避免这条右缘热区
                // 拦截点击（误触震动）或吞掉歌词页的关闭点击
                if (!visible) return@pointerInput
                detectVerticalDragGestures(
                    onDragStart = { offset ->
                        isDragging = true
                        Haptics.click()
                        updateDragFraction(offset.y)
                    },
                    onVerticalDrag = { change, _ ->
                        change.consume()
                        updateDragFraction(change.position.y)
                    },
                    onDragEnd = { isDragging = false },
                    onDragCancel = { isDragging = false }
                )
            }
    ) {
        Canvas(
            Modifier
                .fillMaxWidth()
                .fillMaxHeight()
                .onSizeChanged { trackH = it.height.toFloat() }
        ) {
            val h = size.height
            if (h <= 0f) return@Canvas
            val rightEdge = size.width - edgeGapPx

            // 轨道：静止时几乎隐形，活跃时淡入
            val trackW = idleWpx * 0.5f + idleWpx * 0.5f * expand
            drawRoundRect(
                color = onSheet.copy(alpha = 0.05f + 0.10f * expand),
                topLeft = Offset(rightEdge - trackW, 0f),
                size = Size(trackW, h),
                cornerRadius = CornerRadius(trackW / 2f)
            )

            // 滑块：药丸，拖动时变主题色并膨胀。几何在 draw 阶段实时求值，
            // 读取 dragFraction/expand/dragT 快照——状态变化只让本 Canvas 重绘，不重组。
            val thumbWpx = currentThumbW()
            val thumbYpx = currentThumbY()
            val thumbColor = androidx.compose.ui.graphics.lerp(
                onSheet.copy(alpha = 0.28f + 0.42f * expand),
                colors.primary,
                dragT
            )
            drawRoundRect(
                color = thumbColor,
                topLeft = Offset(rightEdge - thumbWpx, thumbYpx),
                size = Size(thumbWpx, thumbHpx),
                cornerRadius = CornerRadius(thumbWpx / 2f)
            )
        }

        // 拖动气泡：显示当前歌曲首字 + 序号，跟随滑块中心。
        // dragT 只用于决定气泡是否存在（低频），位置在 offset lambda 内实时算——
        // 拖动时只重跑布局偏移，不重组整个气泡。
        if (dragT > 0.01f) {
            val label = labelForIndex(dragIndex)
            // 只显示 A-Z 首字母，中文/日文/数字/符号统一归为 #
            val fc = label?.trim()?.firstOrNull()?.uppercaseChar()
            val head = if (fc != null && fc in 'A'..'Z') fc.toString() else "#"
            val bubbleSize = 58.dp
            val bubbleHalfPx = with(density) { bubbleSize.toPx() } / 2f
            val bubbleXpx = with(density) { 24.dp.roundToPx() }
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .offset {
                        IntOffset(
                            x = -bubbleXpx,
                            y = (currentThumbY() + thumbHpx / 2f - bubbleHalfPx).roundToInt()
                        )
                    }
                    .graphicsLayer {
                        alpha = dragT
                        scaleX = 0.82f + 0.18f * dragT
                        scaleY = 0.82f + 0.18f * dragT
                        transformOrigin = androidx.compose.ui.graphics.TransformOrigin(1f, 0.5f)
                    }
                    .size(bubbleSize)
                    .clip(IrisShape.item)
                    // 不透明底色 + 主题色描边：任何背景上都看得清
                    .background(colors.surface)
                    .border(1.5.dp, colors.primary.copy(alpha = 0.55f), IrisShape.item),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        head,
                        color = colors.text,
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Black,
                        maxLines = 1
                    )
                    Text(
                        "${dragIndex + 1}",
                        color = colors.primary.readableOn(colors.surface, 3.5f),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1
                    )
                }
            }
        }
    }
}
