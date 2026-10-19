package com.iris.music.ui

import android.graphics.Bitmap
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.blur
import androidx.compose.foundation.border
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.iris.music.data.LyricLine
import com.iris.music.data.LyricParser
import com.iris.music.data.RepeatMode
import com.iris.music.ui.theme.GlassContent
import com.iris.music.ui.theme.GlassLevel
import com.iris.music.ui.theme.IrisColors
import com.iris.music.ui.theme.IrisShape
import com.iris.music.ui.theme.irisSurface
import com.iris.music.ui.theme.isLiquidGlass
import com.iris.music.ui.theme.readableOn
import com.iris.music.ui.theme.readableTextOn
import com.iris.music.player.PlayerViewModel

/** 中央播放器卡片：白色圆角卡片 + 封面 + 标题/作者 + 进度条 + 时间 + 三枚圆角矢量按钮 + 副控制 */
@Composable
fun PlayerCard(
    title: String,
    artist: String,
    positionMs: Long,
    durationMs: Long,
    shuffle: Boolean,
    repeatMode: RepeatMode,
    artworkPath: String?,
    /** 网络音乐封面 URI（仅在线音乐）。非空时优先用它 */
    artworkUri: android.net.Uri? = null,
    /** 默认封面图：在线封面加载失败或超 3 秒时兜底 */
    defaultCoverUri: android.net.Uri? = null,
    progress: Float,
    isPlaying: Boolean,
    onToggle: () -> Unit,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onSeek: (Float) -> Unit,
    onCyclePlayMode: () -> Unit,
    onToggleLike: () -> Unit = {},
    liked: Boolean = false,
    onClickArtwork: () -> Unit = {},
    /** 点击评论按钮：打开当前歌曲评论弹层 */
    onOpenComments: () -> Unit = {},
    colors: IrisColors,
    modifier: Modifier = Modifier,
    sleepTimerMs: Long = 0L,
    sleepTimerEndMs: Long = 0L,
    onOpenSleepTimer: () -> Unit = {},
    eqActive: Boolean = false,
    onOpenEqualizer: () -> Unit = {},
    audioFormat: String = "",
    visualizerEnabled: Boolean = false,
    onToggleVisualizer: () -> Unit = {},
    tiltSpectrum: Boolean = false,
    coverShakeEnabled: Boolean = false,
    coverLyricEnabled: Boolean = false,
    interactive: Boolean = true
) {
    val cardColor = if (colors.isDark) Color(0xFF1E1E26) else Color(0xFFFFFFFF)
    val glass = isLiquidGlass
    val btnColor = colors.primary.readableOn(cardColor, 3.2f)
    val trackColor = if (colors.isDark) Color(0xFF3A3A44) else Color(0xFFD8D8DE)
    val onCard = if (colors.isDark) Color.White else Color.Black
    val readableAccent = btnColor
    val subOnCard = onCard.copy(alpha = 0.72f)

    val shake = rememberShakeState(coverShakeEnabled && interactive)
    var coverLyrics by remember { mutableStateOf<List<LyricLine>>(emptyList()) }
    LaunchedEffect(artworkPath, coverLyricEnabled) {
        if (!coverLyricEnabled || !interactive) { coverLyrics = emptyList(); return@LaunchedEffect }
        coverLyrics = if (artworkPath == null) emptyList()
        else withContext(Dispatchers.IO) { runCatching { LyricParser.loadLyrics(artworkPath, durationMs) }.getOrDefault(emptyList()) }
    }

    val vizT = remember { Animatable(if (visualizerEnabled) 1f else 0f) }
    LaunchedEffect(visualizerEnabled) {
        vizT.animateTo(
            targetValue = if (visualizerEnabled) 1f else 0f,
            animationSpec = tween(420, easing = androidx.compose.animation.core.EaseInOut)
        )
    }

    val isLandscape = LocalConfiguration.current.orientation ==
        android.content.res.Configuration.ORIENTATION_LANDSCAPE

    Box(
        modifier = modifier
            .then(
                if (isLandscape) Modifier.widthIn(max = LANDSCAPE_PLAYER_MAX_WIDTH)
                else Modifier.fillMaxWidth()
            )
            .irisSurface(GlassLevel.PLAYER, colors, IrisShape.card, solid = cardColor)
            .padding(horizontal = 18.dp, vertical = 18.dp)
    ) {
        if (isLandscape) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(24.dp)
            ) {
                PlayerCoverArt(
                    artworkPath = artworkPath,
                    artworkUri = artworkUri,
                    defaultCoverUri = defaultCoverUri,
                    positionMs = positionMs,
                    isDark = colors.isDark,
                    vizAlpha = vizT.value,
                    shake = rememberShakeState(false),
                    coverLyricEnabled = coverLyricEnabled,
                    interactive = interactive,
                    coverLyrics = coverLyrics,
                    onClickArtwork = onClickArtwork,
                    modifier = Modifier
                        .fillMaxHeight(0.72f)
                        .aspectRatio(1f)
                )
                PlayerControls(
                    title = title,
                    artist = artist,
                    positionMs = positionMs,
                    durationMs = durationMs,
                    shuffle = shuffle,
                    repeatMode = repeatMode,
                    progress = progress,
                    isPlaying = isPlaying,
                    onToggle = onToggle,
                    onPrev = onPrev,
                    onNext = onNext,
                    onSeek = onSeek,
                    onCyclePlayMode = onCyclePlayMode,
                    onToggleLike = onToggleLike,
                    liked = liked,
                    onOpenComments = onOpenComments,
                    sleepTimerMs = sleepTimerMs,
                    sleepTimerEndMs = sleepTimerEndMs,
                    onOpenSleepTimer = onOpenSleepTimer,
                    eqActive = eqActive,
                    onOpenEqualizer = onOpenEqualizer,
                    audioFormat = audioFormat,
                    visualizerEnabled = visualizerEnabled,
                    onToggleVisualizer = onToggleVisualizer,
                    tiltSpectrum = tiltSpectrum,
                    vizAlpha = vizT.value,
                    onCard = onCard,
                    subOnCard = subOnCard,
                    readableAccent = readableAccent,
                    btnColor = btnColor,
                    trackColor = trackColor,
                    glass = glass,
                    isDark = colors.isDark,
                    interactive = interactive,
                    compact = true,
                    modifier = Modifier.weight(1f)
                )
            }
        } else {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                PlayerCoverArt(
                    artworkPath = artworkPath,
                    artworkUri = artworkUri,
                    defaultCoverUri = defaultCoverUri,
                    positionMs = positionMs,
                    isDark = colors.isDark,
                    vizAlpha = vizT.value,
                    shake = shake,
                    coverLyricEnabled = coverLyricEnabled,
                    interactive = interactive,
                    coverLyrics = coverLyrics,
                    onClickArtwork = onClickArtwork,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = LocalConfiguration.current.screenHeightDp.dp * 0.46f)
                        .aspectRatio(1f)
                )

                Spacer(Modifier.height(20.dp))

                PlayerControls(
                    title = title,
                    artist = artist,
                    positionMs = positionMs,
                    durationMs = durationMs,
                    shuffle = shuffle,
                    repeatMode = repeatMode,
                    progress = progress,
                    isPlaying = isPlaying,
                    onToggle = onToggle,
                    onPrev = onPrev,
                    onNext = onNext,
                    onSeek = onSeek,
                    onCyclePlayMode = onCyclePlayMode,
                    onToggleLike = onToggleLike,
                    liked = liked,
                    onOpenComments = onOpenComments,
                    sleepTimerMs = sleepTimerMs,
                    sleepTimerEndMs = sleepTimerEndMs,
                    onOpenSleepTimer = onOpenSleepTimer,
                    eqActive = eqActive,
                    onOpenEqualizer = onOpenEqualizer,
                    audioFormat = audioFormat,
                    visualizerEnabled = visualizerEnabled,
                    onToggleVisualizer = onToggleVisualizer,
                    tiltSpectrum = tiltSpectrum,
                    vizAlpha = vizT.value,
                    onCard = onCard,
                    subOnCard = subOnCard,
                    readableAccent = readableAccent,
                    btnColor = btnColor,
                    trackColor = trackColor,
                    glass = glass,
                    isDark = colors.isDark,
                    interactive = interactive,
                    compact = false,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}

/**
 * 封面块（点击开歌词、频谱压暗层、封面歌词）。
 *
 * 封面来源优先级：
 * 1. [artworkUri] 非空（在线音乐）→ Coil 加载网络图，3 秒超时 + 失败时用 [defaultCoverUri] 兜底
 * 2. 否则 → 原版 [AlbumArt]，从本地音频文件读内嵌封面
 */
@Composable
internal fun PlayerCoverArt(
    artworkPath: String?,
    artworkUri: android.net.Uri? = null,
    defaultCoverUri: android.net.Uri? = null,
    positionMs: Long,
    isDark: Boolean,
    vizAlpha: Float,
    shake: CoverShake,
    coverLyricEnabled: Boolean,
    interactive: Boolean,
    coverLyrics: List<LyricLine>,
    onClickArtwork: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .coverShake(shake)
            .clip(IrisShape.item)
            .background(Color.hsv(
                hue = (artworkPath?.hashCode()?.mod(360) ?: 0).toFloat(),
                saturation = 0.35f,
                value = if (isDark) 0.18f else 0.92f
            ))
            .clickable { Haptics.tap(); onClickArtwork() }
    ) {
        if (artworkUri != null) {
            NetworkCoverArt(uri = artworkUri, fallbackUri = defaultCoverUri)
        } else {
            AlbumArt(filePath = artworkPath)
        }
        if (vizAlpha > 0.01f) {
            Box(
                Modifier
                    .matchParentSize()
                    .background(Color.Black.copy(alpha = 0.3f * vizAlpha))
            )
        }
        if (coverLyricEnabled && interactive) {
            val curIdx = LyricParser.currentIndex(coverLyrics, positionMs)
            val curText = if (curIdx in coverLyrics.indices) coverLyrics[curIdx].text else ""
            CoverLyricLine(
                text = curText,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(start = 14.dp, bottom = 14.dp)
            )
        }
    }
}

/**
 * 网络封面：Coil AsyncImage + 3 秒超时 + 失败兜底。
 *
 * 超时用协程实现（不依赖 Coil 2.6 的 httpRequestTimeoutMillis 扩展，那个 API 需要额外 import），
 * 3 秒内没有 onSuccess 就切到 fallbackUri。onError 也立即切。
 */
@Composable
private fun NetworkCoverArt(
    uri: android.net.Uri,
    fallbackUri: android.net.Uri?
) {
    var useFallback by remember(uri) { mutableStateOf(false) }
    var success by remember(uri) { mutableStateOf(false) }

    LaunchedEffect(uri) {
        delay(3000)
        if (!success) useFallback = true
    }

    val effectiveUri = if (useFallback && fallbackUri != null) fallbackUri else uri

    AsyncImage(
        model = effectiveUri,
        contentDescription = null,
        contentScale = ContentScale.Crop,
        onSuccess = { if (!useFallback) success = true },
        onError = { if (fallbackUri != null) useFallback = true },
        modifier = Modifier.fillMaxSize()
    )
}

@Composable
private fun PlayerControls(
    title: String,
    artist: String,
    positionMs: Long,
    durationMs: Long,
    shuffle: Boolean,
    repeatMode: RepeatMode,
    progress: Float,
    isPlaying: Boolean,
    onToggle: () -> Unit,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onSeek: (Float) -> Unit,
    onCyclePlayMode: () -> Unit,
    onToggleLike: () -> Unit,
    liked: Boolean,
    onOpenComments: () -> Unit,
    sleepTimerMs: Long,
    sleepTimerEndMs: Long,
    onOpenSleepTimer: () -> Unit,
    eqActive: Boolean,
    onOpenEqualizer: () -> Unit,
    audioFormat: String,
    visualizerEnabled: Boolean,
    onToggleVisualizer: () -> Unit,
    tiltSpectrum: Boolean,
    vizAlpha: Float,
    onCard: Color,
    subOnCard: Color,
    readableAccent: Color,
    btnColor: Color,
    trackColor: Color,
    glass: Boolean,
    isDark: Boolean,
    interactive: Boolean,
    compact: Boolean,
    infoFade: Float = 1f,
    modifier: Modifier = Modifier
) {
    val infoHeight = if (compact) 52.dp else 60.dp
    val titleSize = if (compact) 17.sp else 22.sp
    val prevNextBtn = if (compact) 48.dp else 62.dp
    val prevNextIcon = if (compact) 26.dp else 34.dp
    val playBtn = if (compact) 58.dp else 76.dp
    val gapAfterCover = if (compact) 14.dp else 18.dp
    val gapAfterInfo = if (compact) 2.dp else 6.dp
    val gapBeforeMain = if (compact) 6.dp else 16.dp
    val gapBeforeMini = if (compact) 8.dp else 14.dp

    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(infoHeight),
            contentAlignment = Alignment.Center
        ) {
            if (vizAlpha < 0.995f) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .fillMaxWidth()
                        .then(
                            if (infoFade < 0.999f && android.os.Build.VERSION.SDK_INT >= 31)
                                Modifier.blur(
                                    ((1f - infoFade) * 10).dp,
                                    edgeTreatment = androidx.compose.ui.draw.BlurredEdgeTreatment.Unbounded
                                )
                            else Modifier
                        )
                        .graphicsLayer {
                            alpha = (1f - vizAlpha) * infoFade
                            translationY = -10.dp.toPx() * vizAlpha
                            val s = 0.94f + 0.06f * infoFade
                            scaleX = s
                            scaleY = s
                        }
                ) {
                    Text(
                        title,
                        color = onCard,
                        fontSize = titleSize,
                        fontWeight = FontWeight.ExtraBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        artist,
                        color = readableAccent,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            if (vizAlpha > 0.005f) {
                SpectrumBars(
                    accent = readableAccent,
                    playing = isPlaying,
                    tiltEnabled = tiltSpectrum && !compact,
                    height = if (compact) 40.dp else 52.dp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .graphicsLayer {
                            alpha = vizAlpha
                            translationY = 10.dp.toPx() * (1f - vizAlpha)
                        }
                )
            }
        }

        Spacer(Modifier.height(gapAfterCover))

        NeonProgressBar(
            progress = progress,
            onSeek = onSeek,
            accent = readableAccent,
            track = trackColor,
            glass = glass,
            isDark = isDark,
            interactive = interactive,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp)
        )

        Spacer(Modifier.height(gapAfterInfo))

        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                formatDuration(positionMs),
                color = subOnCard,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium
            )
            Text(
                formatDuration(durationMs),
                color = subOnCard,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium
            )
        }

        Spacer(Modifier.height(gapBeforeMain))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            PressableIconButton(size = prevNextBtn, onClick = onPrev) {
                Icon(Icons.Play, contentDescription = null, tint = onCard, modifier = Modifier.size(prevNextIcon).graphicsLayer { scaleX = -1f })
            }
            PressableIconButton(size = playBtn, onClick = onToggle) {
                PlayPauseIcon(isPlaying, btnColor)
            }
            PressableIconButton(size = prevNextBtn, onClick = onNext) {
                Icon(Icons.Play, contentDescription = null, tint = onCard, modifier = Modifier.size(prevNextIcon))
            }
        }

        Spacer(Modifier.height(gapBeforeMini))

        // 副控制行：随机 / 点赞 / 评论 / 睡眠 / 均衡器 + 音频格式徽章
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val glyph = with(LocalDensity.current) { 11.sp.toDp() } * 0.72f
            val badgeReserve =
                if (audioFormat.isEmpty()) 0.dp else glyph * audioFormat.length + 20.dp
            val gap = ((maxWidth - MINI_BTN_SIZE * 6 - badgeReserve) / 6)
                .coerceIn(3.dp, 12.dp)

            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(gap)
                ) {
                    MiniControlButton(shuffle || repeatMode != RepeatMode.OFF, onCyclePlayMode, btnColor, onCard) {
                        PlayModeIcon(shuffle, repeatMode, it)
                    }
                    MiniControlButton(liked, onToggleLike, btnColor, onCard) {
                        Icon(if (liked) Icons.Favorite else Icons.FavoriteBorder, null, Modifier.size(20.dp), it)
                    }
                    MiniControlButton(false, onOpenComments, btnColor, onCard) {
                        CommentIcon(it)
                    }
                    SleepTimerButton(
                        active = sleepTimerMs > 0,
                        totalMs = sleepTimerMs,
                        endMs = sleepTimerEndMs,
                        onClick = onOpenSleepTimer,
                        accent = btnColor,
                        onCard = onCard
                    )
                    MiniControlButton(eqActive, onOpenEqualizer, btnColor, onCard) { Icon(Icons.Tune, null, Modifier.size(20.dp), it) }
                }
                if (audioFormat.isNotEmpty()) {
                    val badgeBg = if (visualizerEnabled) readableAccent
                                  else readableAccent.copy(alpha = if (isDark) 0.22f else 0.16f)
                    val badgeFg = if (visualizerEnabled) readableAccent.readableTextOn()
                                  else readableAccent
                    Box(
                        Modifier
                            .clip(IrisShape.chip)
                            .background(badgeBg)
                            .clickable { Haptics.tap(); onToggleVisualizer() }
                            .padding(horizontal = 9.dp, vertical = 4.dp)
                    ) {
                        Text(
                            audioFormat,
                            color = badgeFg,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Black,
                            letterSpacing = 0.6.sp,
                            maxLines = 1,
                            softWrap = false
                        )
                    }
                }
            }
        }
    }
}

/** 评论图标：手绘圆角气泡 + 三圆点 */
@Composable
private fun CommentIcon(tint: Color) {
    Canvas(Modifier.size(20.dp)) {
        val w = size.width
        val h = size.height
        val stroke = w * 0.11f
        drawRoundRect(
            color = tint,
            topLeft = Offset(w * 0.08f, h * 0.12f),
            size = Size(w * 0.84f, h * 0.58f),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(w * 0.16f, w * 0.16f),
            style = Stroke(width = stroke)
        )
        val tail = Path().apply {
            moveTo(w * 0.28f, h * 0.68f)
            lineTo(w * 0.30f, h * 0.88f)
            lineTo(w * 0.52f, h * 0.68f)
        }
        drawPath(tail, color = tint, style = Stroke(width = stroke, join = StrokeJoin.Round, cap = StrokeCap.Round))
        val dotR = w * 0.045f
        val cy = h * 0.41f
        drawCircle(tint, dotR, Offset(w * 0.34f, cy))
        drawCircle(tint, dotR, Offset(w * 0.50f, cy))
        drawCircle(tint, dotR, Offset(w * 0.66f, cy))
    }
}

@Composable
private fun CoverLyricLine(text: String, modifier: Modifier = Modifier) {
    var displayText by remember { mutableStateOf(text) }
    val visibility = remember { Animatable(1f) }
    LaunchedEffect(text) {
        if (displayText == text) return@LaunchedEffect
        if (displayText.isEmpty() || text.isEmpty()) { displayText = text; return@LaunchedEffect }
        visibility.animateTo(0f, tween(240))
        displayText = text
        visibility.animateTo(1f, tween(320))
    }
    val blur = (1f - visibility.value) * 6f
    Text(
        displayText,
        color = Color.White,
        fontSize = 11.sp,
        fontWeight = FontWeight.Medium,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        style = TextStyle(
            shadow = Shadow(
                color = Color.Black.copy(alpha = 0.55f),
                offset = Offset(0f, 1.2f),
                blurRadius = 3f
            )
        ),
        modifier = modifier
            .graphicsLayer { alpha = visibility.value }
            .blur(blur.dp)
    )
}

@Composable
private fun PressableIconButton(
    size: Dp,
    onClick: () -> Unit,
    content: @Composable () -> Unit
) {
    Box(
        modifier = Modifier
            .size(size)
            .irisPressable(
                scaleDown = PRESS_SCALE_STRONG,
                feedback = PressFeedback.CLICK,
                onClick = onClick
            ),
        contentAlignment = Alignment.Center
    ) {
        content()
    }
}

private val MINI_BTN_SIZE = 38.dp

internal val LANDSCAPE_PLAYER_MAX_WIDTH = 680.dp

internal val LANDSCAPE_BAR_MAX_WIDTH = 724.dp

private const val MINI_INACTIVE_ALPHA = 0.32f

@Composable
private fun MiniControlButton(
    active: Boolean,
    onClick: () -> Unit,
    accent: Color,
    onCard: Color,
    icon: @Composable (Color) -> Unit
) {
    val activeT by animateFloatAsState(
        targetValue = if (active) 1f else 0f,
        animationSpec = tween(180),
        label = "miniBtnActive"
    )
    val iconColor = lerp(onCard.copy(alpha = MINI_INACTIVE_ALPHA), accent, activeT)
    Box(
        Modifier
            .size(MINI_BTN_SIZE)
            .irisPressable(scaleDown = 0.82f, feedback = PressFeedback.TAP, onClick = onClick),
        contentAlignment = Alignment.Center
    ) { icon(iconColor) }
}

@Composable
private fun PlayPauseIcon(isPlaying: Boolean, tint: Color) {
    val t by animateFloatAsState(
        targetValue = if (isPlaying) 1f else 0f,
        animationSpec = tween(260, easing = FastOutSlowInEasing),
        label = "playPauseFlip"
    )
    Box(Modifier.size(39.dp), contentAlignment = Alignment.Center) {
        Icon(
            Icons.Play, contentDescription = null, tint = tint,
            modifier = Modifier
                .size(39.dp)
                .graphicsLayer {
                    rotationZ = -90f * t
                    alpha = 1f - t
                    scaleX = 1f - 0.15f * t
                    scaleY = 1f - 0.15f * t
                }
        )
        Icon(
            Icons.Pause, contentDescription = null, tint = tint,
            modifier = Modifier
                .size(39.dp)
                .graphicsLayer {
                    rotationZ = 90f * (1f - t)
                    alpha = t
                    scaleX = 0.85f + 0.15f * (1f - t)
                    scaleY = 0.85f + 0.15f * (1f - t)
                }
        )
    }
}

@Composable
private fun SleepTimerButton(
    active: Boolean,
    totalMs: Long,
    endMs: Long,
    onClick: () -> Unit,
    accent: Color,
    onCard: Color
) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.82f else 1f,
        animationSpec = IrisMotion.pressScale(),
        label = "sleepBtnScale"
    )

    var remainingMs by remember { mutableLongStateOf(0L) }
    LaunchedEffect(active, totalMs, endMs) {
        if (!active || totalMs <= 0) { remainingMs = 0L; return@LaunchedEffect }
        while (true) {
            remainingMs = (endMs - System.currentTimeMillis()).coerceAtLeast(0L)
            if (remainingMs <= 0L) break
            delay(1000)
        }
    }
    val rawFraction = if (totalMs > 0) (remainingMs.toFloat() / totalMs).coerceIn(0f, 1f) else 0f
    val fraction by animateFloatAsState(
        targetValue = rawFraction,
        animationSpec = tween(durationMillis = 900, easing = LinearEasing),
        label = "sleepRing"
    )
    val remainMinutes = ((remainingMs + 59999) / 60000).toInt().coerceAtLeast(0)

    val iconColor = if (active) accent else onCard.copy(alpha = MINI_INACTIVE_ALPHA)

    Box(
        Modifier
            .size(MINI_BTN_SIZE)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = { Haptics.tap(); onClick() }
            ),
        contentAlignment = Alignment.Center
    ) {
        if (active) {
            Canvas(Modifier.size(38.dp)) {
                val stroke = 2.6f * density
                val inset = stroke / 2f + 1.dp.toPx()
                val arcSize = Size(size.width - inset * 2, size.height - inset * 2)
                drawArc(
                    color = onCard.copy(alpha = 0.15f),
                    startAngle = -90f,
                    sweepAngle = 360f,
                    useCenter = false,
                    topLeft = Offset(inset, inset),
                    size = arcSize,
                    style = Stroke(width = stroke)
                )
                drawArc(
                    color = accent,
                    startAngle = -90f,
                    sweepAngle = 360f * fraction,
                    useCenter = false,
                    topLeft = Offset(inset, inset),
                    size = arcSize,
                    style = Stroke(width = stroke, cap = StrokeCap.Round)
                )
            }
            Text(
                text = "$remainMinutes",
                color = accent,
                fontSize = 10.sp,
                fontWeight = FontWeight.ExtraBold
            )
        } else {
            Icon(Icons.Bedtime, contentDescription = null, tint = iconColor, modifier = Modifier.size(20.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SleepTimerSheet(
    currentMinutes: Int,
    onDismiss: () -> Unit,
    onSetMinutes: (Int) -> Unit,
    colors: IrisColors
) {
    val minutesOptions = listOf(5, 10, 15, 20, 30, 45, 60)
    var selectedIndex by remember { mutableIntStateOf(minutesOptions.indexOf(currentMinutes).coerceAtLeast(0)) }
    val selectedMinutes = minutesOptions[selectedIndex]
    val accent = colors.primary.readableOn(colors.surface, 3.2f)
    val onSheet = if (colors.isDark) Color.White else Color.Black
    val glass = isLiquidGlass
    val drag = rememberSheetDragState()

    IrisSheet(
        colors = colors,
        onDismiss = onDismiss,
        drag = drag,
        contentModifier = Modifier
            .verticalScroll(rememberScrollState())
            .navigationBarsPadding()
            .padding(horizontal = 24.dp)
            .padding(top = 10.dp, bottom = 24.dp)
    ) {
            SheetDragHandle(drag, onDismiss, colors.card)
            Spacer(Modifier.height(14.dp))
                    Text("睡眠定时器", color = onSheet, fontSize = 20.sp, fontWeight = FontWeight.Black)
                    Spacer(Modifier.height(18.dp))

                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        Canvas(Modifier.size(80.dp)) {
                            val stroke = 6f * density
                            val inset = stroke / 2f + 2.dp.toPx()
                            val arcSize = Size(size.width - inset * 2, size.height - inset * 2)
                            drawArc(
                                color = if (glass) onSheet.copy(alpha = 0.18f) else colors.card,
                                startAngle = -90f,
                                sweepAngle = 360f,
                                useCenter = false,
                                topLeft = Offset(inset, inset),
                                size = arcSize,
                                style = Stroke(width = stroke)
                            )
                            val fraction = (selectedIndex + 1).toFloat() / minutesOptions.size
                            drawArc(
                                color = accent,
                                startAngle = -90f,
                                sweepAngle = 360f * fraction,
                                useCenter = false,
                                topLeft = Offset(inset, inset),
                                size = arcSize,
                                style = Stroke(width = stroke, cap = StrokeCap.Round)
                            )
                        }
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                "$selectedMinutes",
                                fontSize = 26.sp,
                                fontWeight = FontWeight.ExtraBold,
                                color = accent
                            )
                        }
                    }

                    Spacer(Modifier.height(20.dp))

                    CompactSlider(
                        value = selectedIndex.toFloat(),
                        onValueChange = { selectedIndex = it.roundToInt().coerceIn(0, minutesOptions.size - 1) },
                        valueRange = 0f..(minutesOptions.size - 1).toFloat(),
                        steps = minutesOptions.size - 2,
                        modifier = Modifier.fillMaxWidth(),
                        activeColor = accent,
                        inactiveColor = if (glass) onSheet.copy(alpha = 0.18f) else colors.card
                    )

                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        minutesOptions.forEach { min ->
                            Text(
                                "$min",
                                fontSize = 10.sp,
                                color = if (min == selectedMinutes) accent else onSheet.copy(alpha = 0.62f),
                                fontWeight = if (min == selectedMinutes) FontWeight.Bold else FontWeight.Normal
                            )
                        }
                    }

                    Spacer(Modifier.height(20.dp))

                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        SheetActionButton(
                            label = "取消",
                            labelColor = onSheet.copy(alpha = 0.75f),
                            borderColor = onSheet.copy(alpha = 0.25f),
                            colors = colors,
                            modifier = Modifier.weight(1f),
                            onClick = { Haptics.tap(); onDismiss() }
                        )
                        SheetActionButton(
                            label = "关闭",
                            labelColor = Color(0xFFFF4D6D),
                            borderColor = Color(0xFFFF4D6D).copy(alpha = 0.65f),
                            colors = colors,
                            modifier = Modifier.weight(1f),
                            onClick = { Haptics.click(); onSetMinutes(0); onDismiss() }
                        )
                        Box(
                            Modifier
                                .weight(1f)
                                .height(44.dp)
                                .clip(IrisShape.item)
                                .background(accent)
                                .clickable { Haptics.click(); onSetMinutes(selectedMinutes); onDismiss() },
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                "设置",
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold,
                                color = accent.readableTextOn()
                            )
                        }
                    }
    }
}

@Composable
private fun SheetActionButton(
    label: String,
    labelColor: Color,
    borderColor: Color,
    colors: IrisColors,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Box(
        modifier
            .height(44.dp)
            .irisSurface(GlassLevel.CARD, colors, IrisShape.item)
            .border(1.dp, borderColor, IrisShape.item)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(label, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = labelColor)
    }
}

private object Icons {
    val Play get() = IrisIcons.Play
    val Pause get() = IrisIcons.Pause
    val Shuffle get() = IrisIcons.Shuffle
    val Repeat get() = IrisIcons.Repeat
    val Bedtime get() = IrisIcons.Bedtime
    val Tune get() = IrisIcons.Tune
    val Favorite get() = IrisIcons.Favorite
    val FavoriteBorder get() = IrisIcons.FavoriteBorder
}

@Composable
private fun PlayModeIcon(shuffle: Boolean, repeatMode: RepeatMode, color: Color) {
    val oneT by animateFloatAsState(
        targetValue = if (repeatMode == RepeatMode.ONE) 1f else 0f,
        animationSpec = IrisMotion.pressScale(),
        label = "playModeOneDot"
    )
    val iconT by animateFloatAsState(
        targetValue = if (shuffle) 0f else 1f,
        animationSpec = tween(220, easing = FastOutSlowInEasing),
        label = "playModeIconFlip"
    )
    Box(Modifier.size(20.dp), contentAlignment = Alignment.Center) {
        Icon(
            Icons.Shuffle, contentDescription = null, tint = color,
            modifier = Modifier
                .size(20.dp)
                .graphicsLayer {
                    rotationZ = -90f * iconT
                    alpha = 1f - iconT
                    scaleX = 0.85f + 0.15f * (1f - iconT)
                    scaleY = 0.85f + 0.15f * (1f - iconT)
                }
        )
        Icon(
            Icons.Repeat, contentDescription = null, tint = color,
            modifier = Modifier
                .size(20.dp)
                .graphicsLayer {
                    rotationZ = 90f * (1f - iconT)
                    alpha = iconT
                    scaleX = 0.85f + 0.15f * iconT
                    scaleY = 0.85f + 0.15f * iconT
                }
        )
        if (oneT > 0.01f) {
            Canvas(
                Modifier
                    .size(20.dp)
                    .graphicsLayer {
                        alpha = oneT
                        scaleX = oneT; scaleY = oneT
                    }
            ) {
                drawCircle(
                    color = color,
                    radius = 1.9.dp.toPx(),
                    center = Offset(size.width / 2f, size.height / 2f)
                )
            }
        }
    }
}

@Composable
private fun AlbumArt(filePath: String?) {
    var bitmap by remember { mutableStateOf(ArtworkLoader.peekHiRes(filePath) ?: ArtworkLoader.peek(filePath)) }
    var loadedPath by remember { mutableStateOf<String?>(if (bitmap != null) filePath else null) }
    var hiResPath by remember { mutableStateOf<String?>(if (ArtworkLoader.peekHiRes(filePath) != null) filePath else null) }

    LaunchedEffect(filePath) {
        if (filePath != loadedPath) {
            val bmp = if (filePath == null) null else ArtworkLoader.load(filePath)
            if ((bmp != null || filePath == null) && hiResPath != filePath) {
                bitmap = bmp
                loadedPath = filePath
            }
        }
        if (filePath != null && hiResPath != filePath) {
            val hi = ArtworkLoader.loadHiRes(filePath)
            if (hi != null && filePath == loadedPath) {
                bitmap = hi
                hiResPath = filePath
            }
        }
    }

    val bmp = bitmap
    if (bmp != null) {
        androidx.compose.animation.Crossfade(targetState = bmp, animationSpec = tween(280), label = "albumArt") { b ->
            Image(
                bitmap = b.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}

@Composable
private fun NeonProgressBar(
    progress: Float,
    onSeek: (Float) -> Unit,
    accent: Color,
    track: Color,
    modifier: Modifier = Modifier,
    glass: Boolean = false,
    isDark: Boolean = true,
    interactive: Boolean = true
) {
    Box(
        modifier
            .height(14.dp)
            .then(
                if (interactive) Modifier
                    .pointerInput(Unit) {
                        detectTapGestures { offset ->
                            onSeek((offset.x / size.width).coerceIn(0f, 1f))
                        }
                    }
                    .pointerInput(Unit) {
                        detectHorizontalDragGestures { change, _ ->
                            onSeek((change.position.x / size.width).coerceIn(0f, 1f))
                        }
                    }
                else Modifier
            )
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val barH = 11f * density
            val y = (size.height - barH) / 2f
            val radius = barH / 2f
            val corner = androidx.compose.ui.geometry.CornerRadius(radius, radius)

            if (glass) {
                drawRoundRect(
                    color = Color.Black.copy(alpha = if (isDark) 0.45f else 0.22f),
                    topLeft = Offset(0f, y),
                    size = Size(size.width, barH),
                    cornerRadius = corner
                )
                val rimW = 1f * density
                drawRoundRect(
                    color = Color.White.copy(alpha = if (isDark) 0.30f else 0.55f),
                    topLeft = Offset(rimW / 2f, y + rimW / 2f),
                    size = Size(size.width - rimW, barH - rimW),
                    cornerRadius = corner,
                    style = Stroke(width = rimW)
                )
            } else {
                drawRoundRect(
                    color = track,
                    topLeft = Offset(0f, y),
                    size = Size(size.width, barH),
                    cornerRadius = corner
                )
            }

            val filled = (size.width * progress).coerceAtLeast(barH)
            drawRoundRect(
                color = accent,
                topLeft = Offset(0f, y),
                size = Size(filled, barH),
                cornerRadius = corner
            )
            if (glass) {
                drawRoundRect(
                    color = Color.White.copy(alpha = if (isDark) 0.22f else 0.30f),
                    topLeft = Offset(0f, y),
                    size = Size(filled, barH),
                    cornerRadius = corner,
                    style = Stroke(width = 1f * density)
                )
            }
        }
    }
}

@Composable
internal fun LandscapeDeckPlayer(
    title: String,
    artist: String,
    positionMs: Long,
    durationMs: Long,
    shuffle: Boolean,
    repeatMode: RepeatMode,
    progress: Float,
    isPlaying: Boolean,
    onToggle: () -> Unit,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onSeek: (Float) -> Unit,
    onCyclePlayMode: () -> Unit,
    onToggleLike: () -> Unit,
    liked: Boolean,
    onOpenComments: () -> Unit = {},
    colors: IrisColors,
    sleepTimerMs: Long,
    sleepTimerEndMs: Long,
    onOpenSleepTimer: () -> Unit,
    eqActive: Boolean,
    onOpenEqualizer: () -> Unit,
    audioFormat: String,
    visualizerEnabled: Boolean,
    onToggleVisualizer: () -> Unit,
    tiltSpectrum: Boolean,
    modifier: Modifier = Modifier,
    coverAspect: Float = 1f,
    coverSlot: @Composable () -> Unit
) {
    val cardColor = if (colors.isDark) Color(0xFF1E1E26) else Color(0xFFFFFFFF)
    val glass = isLiquidGlass
    val btnColor = colors.primary.readableOn(cardColor, 3.2f)
    val trackColor = if (colors.isDark) Color(0xFF3A3A44) else Color(0xFFD8D8DE)
    val onCard = if (colors.isDark) Color.White else Color.Black
    val readableAccent = btnColor
    val subOnCard = onCard.copy(alpha = 0.72f)

    val vizT = remember { Animatable(if (visualizerEnabled) 1f else 0f) }
    LaunchedEffect(visualizerEnabled) {
        vizT.animateTo(
            targetValue = if (visualizerEnabled) 1f else 0f,
            animationSpec = tween(420, easing = androidx.compose.animation.core.EaseInOut)
        )
    }

    val infoFade = remember { Animatable(1f) }
    val shownTitle = remember { mutableStateOf(title) }
    val shownArtist = remember { mutableStateOf(artist) }
    LaunchedEffect(title, artist) {
        if (shownTitle.value == title && shownArtist.value == artist) return@LaunchedEffect
        infoFade.animateTo(0f, tween(210, easing = androidx.compose.animation.core.FastOutSlowInEasing))
        shownTitle.value = title
        shownArtist.value = artist
        infoFade.animateTo(1f, tween(360, easing = androidx.compose.animation.core.EaseOutCubic))
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 14.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center
    ) {
        Row(
            Modifier
                .fillMaxHeight()
                .widthIn(max = LANDSCAPE_PLAYER_MAX_WIDTH)
                .irisSurface(GlassLevel.PLAYER, colors, IrisShape.card, solid = cardColor)
                .padding(horizontal = 18.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Box(
                Modifier
                    .fillMaxHeight()
                    .aspectRatio(coverAspect),
                contentAlignment = Alignment.Center
            ) { coverSlot() }

            Box(
                Modifier
                    .weight(1f)
                    .fillMaxHeight(),
                contentAlignment = Alignment.Center
            ) {
            PlayerControls(
                title = shownTitle.value,
                artist = shownArtist.value,
                positionMs = positionMs,
                durationMs = durationMs,
                shuffle = shuffle,
                repeatMode = repeatMode,
                progress = progress,
                isPlaying = isPlaying,
                onToggle = onToggle,
                onPrev = onPrev,
                onNext = onNext,
                onSeek = onSeek,
                onCyclePlayMode = onCyclePlayMode,
                onToggleLike = onToggleLike,
                liked = liked,
                onOpenComments = onOpenComments,
                sleepTimerMs = sleepTimerMs,
                sleepTimerEndMs = sleepTimerEndMs,
                onOpenSleepTimer = onOpenSleepTimer,
                eqActive = eqActive,
                onOpenEqualizer = onOpenEqualizer,
                audioFormat = audioFormat,
                visualizerEnabled = visualizerEnabled,
                onToggleVisualizer = onToggleVisualizer,
                tiltSpectrum = tiltSpectrum,
                vizAlpha = vizT.value,
                onCard = onCard,
                subOnCard = subOnCard,
                readableAccent = readableAccent,
                btnColor = btnColor,
                trackColor = trackColor,
                glass = glass,
                isDark = colors.isDark,
                interactive = true,
                compact = true,
                infoFade = infoFade.value,
                modifier = Modifier.fillMaxWidth()
)
            }
        }
    }
}
