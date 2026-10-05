package com.iris.music.ui

import android.graphics.Bitmap
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
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
import androidx.compose.material3.Slider
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
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
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
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
    colors: IrisColors,
    modifier: Modifier = Modifier,
    // 睡眠定时器（弹层在根层级，这里只负责按钮与倒计时环）
    sleepTimerMs: Long = 0L,
    sleepTimerEndMs: Long = 0L,
    onOpenSleepTimer: () -> Unit = {},
    // 均衡器
    /** 均衡器当前是否有生效的增益（曲线非全平）。均衡器本身常开，没有开关概念 */
    eqActive: Boolean = false,
    onOpenEqualizer: () -> Unit = {},
    /** 音频格式标签（MP3 / FLAC / OGG…），显示在副控制行右侧 */
    audioFormat: String = "",
    /** 可视化频谱开关状态；徽章即开关 */
    visualizerEnabled: Boolean = false,
    onToggleVisualizer: () -> Unit = {},
    /** 实验性：可视化随手机倾斜变化 */
    tiltSpectrum: Boolean = false,
    /** 实验性：摇动手机封面跟着一晃一晃（duangduang） */
    coverShakeEnabled: Boolean = false,
    /** 封面左下角单行歌词开关（锁在封面上，换词时模糊渐隐渐出） */
    coverLyricEnabled: Boolean = false,
    /**
     * 是否是"活的"卡片。
     *
     * 卡片模式（[IrisLayout.isDeck]）下屏幕上同时有好几张卡，只有正在播的那张该响应
     * 拖动类手势——进度条的水平拖动会把翻页 / 抽卡手势整段吃掉，旁边的卡一拖就变成
     * 给当前歌曲 seek。非活动卡只保留点击（点一下跳到那首）。
     */
    interactive: Boolean = true
) {
    // 深色模式：深色卡片 + 白字 + 白图标；浅色模式：白色卡片 + 黑字 + 黑图标
    val cardColor = if (colors.isDark) Color(0xFF1E1E26) else Color(0xFFFFFFFF)
    val glass = isLiquidGlass
    val btnColor = colors.primary.readableOn(cardColor, 3.2f)
    val trackColor = if (colors.isDark) Color(0xFF3A3A44) else Color(0xFFD8D8DE)
    // 卡片上的图标/主文字颜色：深色=白，浅色=黑
    val onCard = if (colors.isDark) Color.White else Color.Black
    // 主题色在卡片背景上的可读版本（霓虹黄/青落在白卡上会看不清）
    val readableAccent = btnColor
    // 次要文字：0.55 太淡，提到 0.72 保证小字也看得清
    val subOnCard = onCard.copy(alpha = 0.72f)

    // 实验性：封面摇动。仅在活动卡 + 开关开启时注册加速度计。
    val shake = rememberShakeState(coverShakeEnabled && interactive)
    // 封面左下角单行歌词：仅在开启 + 活动卡解析。解析放 IO，切歌时重载。
    var coverLyrics by remember { mutableStateOf<List<LyricLine>>(emptyList()) }
    LaunchedEffect(artworkPath, coverLyricEnabled) {
        if (!coverLyricEnabled || !interactive) { coverLyrics = emptyList(); return@LaunchedEffect }
        coverLyrics = if (artworkPath == null) emptyList()
        else withContext(Dispatchers.IO) { runCatching { LyricParser.loadLyrics(artworkPath, durationMs) }.getOrDefault(emptyList()) }
    }

    // 可视化模式的过渡进度。
    // 关键：新卡片实例直接从当前开关状态起步（Animatable 构造值）——
    // 堆叠/横向排布下切歌后顶上来的是全新的 composable 实例，
    // animateFloatAsState 的初始值恒为 0，会把 420ms 的展开动画整个重播，
    // 看着就像"切歌把频谱关了又开"。列表模式是同一张卡原地换内容，没这问题。
    // Animatable + animateTo：只有 visualizerEnabled 真正变化时才播动画。
    val vizT = remember { androidx.compose.animation.core.Animatable(if (visualizerEnabled) 1f else 0f) }
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
            // 横屏窄化：内容不再拉满整个横屏宽度，统一封顶后由外层 Column 居中。
            // 竖屏保持 fillMaxWidth 原样（竖屏宽度本就小于封顶值，但 tablet 竖屏
            // 可能超过，所以只在横屏生效）。
            .then(
                if (isLandscape) Modifier.widthIn(max = LANDSCAPE_PLAYER_MAX_WIDTH)
                else Modifier.fillMaxWidth()
            )
            .irisSurface(GlassLevel.PLAYER, colors, IrisShape.card, solid = cardColor)
            .padding(horizontal = 18.dp, vertical = 18.dp)
    ) {
        if (isLandscape) {
            // 横屏：左封面 + 右控制，垂直居中，控制区高度克制不铺满
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(24.dp)
            ) {
                PlayerCoverArt(
                    artworkPath = artworkPath,
                    positionMs = positionMs,
                    isDark = colors.isDark,
                    vizAlpha = vizT.value,
                    // 横屏禁用摇动封面物理：横屏时加速度计轴向变了，
                    // 摇晃检测会误触发。整个物理效果横屏一律关掉。
                    shake = rememberShakeState(false),
                    coverLyricEnabled = coverLyricEnabled,
                    interactive = interactive,
                    coverLyrics = coverLyrics,
                    onClickArtwork = onClickArtwork,
                    modifier = Modifier
                        // 横屏按屏高定尺寸，正方形自然收窄宽度，不撑爆
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
                        // 竖屏/矮屏封面高度上限：屏高 46%，竖屏时该值远大于卡宽、不生效
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
 * 封面块（点击开歌词、频谱压暗层、封面歌词），从 PlayerCard 抽出以便横屏时
 * 单独承担翻页/堆叠而控制区固定。尺寸由外部 modifier 决定（竖屏按宽、横屏按高）。
 */
@Composable
internal fun PlayerCoverArt(
    artworkPath: String?,
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
        AlbumArt(filePath = artworkPath)
        // 压暗层：固定盖满整卡，让频谱清晰可辨
        if (vizAlpha > 0.01f) {
            Box(
                Modifier
                    .matchParentSize()
                    .background(Color.Black.copy(alpha = 0.3f * vizAlpha))
            )
        }
        // 封面左下角单行歌词：锁在封面上（随 shake 一起动），换词时模糊渐隐渐出。
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
 * 控制块（信息/频谱、进度、时间、主控制、副控制），从 PlayerCard 抽出。
 * compact=true 为横屏紧凑排布：间距收窄、按钮略小、居中不铺满。
 */
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
    // 横屏紧凑：信息区更矮、间距更小、主控制按钮略缩。
    // 信息区要能完整放下标题(17sp≈23dp) + 间距4 + 艺术家(13sp≈18dp)，
    // 给到 48dp 才不会被下面的进度条压住第二行。
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
        // 信息区 ↔ 频谱区：叠在同一个固定高度容器里做交叉淡变。
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
                        // blur 要 API 31，minSdk 24 上低版本机型完全看不到效果
                        // （就是"切换非常直接、没有模糊"）。所以过渡不依赖 blur：
                        // 用透明度 + 轻微缩放 + 纵向位移复合，任何版本都柔和。
                        // 有 blur 的机型再叠一层真模糊，锦上添花。
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
                            // 只做频谱切换的纵向位移；切歌过渡不再纵向移动——
                            // 之前的 +8dp 下移会把艺术家那行推出固定高度的信息区被裁掉。
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
                    // 横屏(compact)禁用倾斜频谱：横屏时设备本就横放，
                    // 加速度计分量与竖屏不同轴，倾斜增益会乱跳影响可视化。
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

        // 进度条
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

        // 当前时间 / 总时长
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

        // 主控制：上一曲 / 播放 / 下一曲
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

        // 副控制行：随机 / 循环 / 点赞 / 睡眠 / 均衡器 + 音频格式徽章
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val glyph = with(LocalDensity.current) { 11.sp.toDp() } * 0.72f
            val badgeReserve =
                if (audioFormat.isEmpty()) 0.dp else glyph * audioFormat.length + 20.dp
            val gap = ((maxWidth - MINI_BTN_SIZE * 5 - badgeReserve) / 5)
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

/**
 * 封面左下角单行歌词：纯文字无底框，固定屏幕坐标（不随封面摇动），盖在封面左下角。
 *
 * 换词时「模糊渐隐 → 换字 → 模糊渐出」：先淡出到 0（伴随轻微模糊），切到新词后
 * 再淡入回到清晰。整段用一个 [Animatable] 驱动 visibility，alpha 与 blur 都从它
 * 派生——淡出时 blur 0→峰值、淡入时峰值→0，中段最糊，正好是字切换的瞬间。
 */
@Composable
private fun CoverLyricLine(text: String, modifier: Modifier = Modifier) {
    var displayText by remember { mutableStateOf(text) }
    val visibility = remember { Animatable(1f) }
    // 短词切换不突兀、无词/首词直接落位不重复播过渡
    LaunchedEffect(text) {
        if (displayText == text) return@LaunchedEffect
        if (displayText.isEmpty() || text.isEmpty()) { displayText = text; return@LaunchedEffect }
        visibility.animateTo(0f, tween(240))
        displayText = text
        visibility.animateTo(1f, tween(320))
    }
    // blur 峰值 6dp：visibility 0 处最糊，1 处清晰
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

/** 主控制按钮：无底图标，按压缩放动画（0.85x），图标本身圆角 */
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

/**
 * 副控制按钮的固定边长。副控制行要按可用宽度反算间距（见 [PlayerCard]），
 * 所以这个尺寸必须是一处定义、多处引用，不能各写一遍 38.dp。
 */
private val MINI_BTN_SIZE = 38.dp

/**
 * 横屏时播放界面（含上栏）的统一宽度封顶。
 *
 * 三个布局共用这一个值：LIST 播放页的卡片、CAROUSEL/STACK 横屏的
 * LandscapeDeckPlayer、卡片模式顶栏 DeckTopBar——封顶相同、都水平居中，
 * 上栏与播放卡片左右边缘严格对齐。宽度只在这一层封顶，外层容器不再
 * 二次封顶（曾双层套娃导致 LIST 卡片比另外两个布局窄一圈）。
 * 唱片墙（COMPACT）明确排除——它的设计就是铺满整屏。
 */
internal val LANDSCAPE_PLAYER_MAX_WIDTH = 680.dp

/**
 * 上栏横屏封顶的补偿值：DeckTopBar / LIST 上栏的玻璃都在 padding(horizontal=22)
 * 之后绘制，玻璃可见宽 = 封顶 - 44。给上栏封顶 680+44，玻璃才和 680 宽的
 * 播放卡片左右缘严格对齐。
 */
internal val LANDSCAPE_BAR_MAX_WIDTH = 724.dp

/**
 * 副控制按钮未激活时的图标不透明度。
 *
 * 原值 0.72 是这个 bug 的根源：黑白主题下激活色就是纯黑/纯白，
 * 白卡上 72% 的黑落在 #494949，和激活的 #000000 都是"很深的颜色"，分不出开关。
 * 压到 0.32 后未激活是 #ADADAD 这种明确的关闭灰，和实色隔着一大截明度，
 * 20dp 的线条图标在这个灰度下依然看得清轮廓。
 */
private const val MINI_INACTIVE_ALPHA = 0.32f

/**
 * 副控制按钮：无底纯图标 + 按压缩放动画。
 *
 * 状态只由图标颜色表达，不加底、不加点 —— 这一行的调子就是裸图标。
 * 所以未激活和激活之间的明度差必须拉得足够开，见 [MINI_INACTIVE_ALPHA]。
 */
@Composable
private fun MiniControlButton(
    active: Boolean,
    onClick: () -> Unit,
    accent: Color,
    onCard: Color,
    icon: @Composable (Color) -> Unit
) {
    // 颜色走短过渡，点一下不硬跳
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

/** 播放↔暂停图标：旋转 90° 翻面 + 缩放淡变的组合过渡 */
@Composable
private fun PlayPauseIcon(isPlaying: Boolean, tint: Color) {
    // 播放中显示暂停图标、暂停时显示播放图标（按钮表达"点击后的动作"）
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

/** 睡眠定时器按钮：未激活显示月亮图标；激活后外圈进度环 + 中央倒计时数字（每分钟跳字） */
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

    // 每秒刷新剩余毫秒
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
    // 圆环平滑过渡，不跳变
    val fraction by animateFloatAsState(
        targetValue = rawFraction,
        animationSpec = tween(durationMillis = 900, easing = LinearEasing),
        label = "sleepRing"
    )
    // 剩余分钟数（向上取整，每秒跳动）
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
            // 进度环（平滑动画）
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
            // 中央倒计时数字
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

/**
 * 睡眠定时器弹层：与均衡器面板同构的自绘覆盖层 —— 遮罩 + 底部玻璃卡片。
 *
 * 不用 ModalBottomSheet：它自带的 Surface 是实色容器，玻璃材质塞不进去
 * （containerColor 只能给一个颜色，拿不到折射背板），液态玻璃下会露出一块实心方块。
 * 自绘之后这层和设置 / 均衡器走同一套 [irisSurface]，三个弹层观感统一。
 *
 * 放在根层级调用，才能折射到「背景 + 页面」两层背板。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SleepTimerSheet(
    currentMinutes: Int,
    onDismiss: () -> Unit,
    onSetMinutes: (Int) -> Unit,
    colors: IrisColors
) {
    val minutesOptions = listOf(5, 10, 15, 20, 30, 45, 60)
    // 默认选中当前定时值；无定时时落在 5 分钟档
    var selectedIndex by remember { mutableIntStateOf(minutesOptions.indexOf(currentMinutes).coerceAtLeast(0)) }
    val selectedMinutes = minutesOptions[selectedIndex]
    // 弹层底是 colors.surface（浅色下接近纯白），主题色直接当文字会糊掉，
    // 统一走可读修正；深色下修正函数原样返回。
    val accent = colors.primary.readableOn(colors.surface, 3.2f)
    val onSheet = if (colors.isDark) Color.White else Color.Black
    val glass = isLiquidGlass
    // 拖把手下滑关闭
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

                    // 圆形进度环 + 分钟数（80dp 紧凑尺寸）
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        Canvas(Modifier.size(80.dp)) {
                            val stroke = 6f * density
                            val inset = stroke / 2f + 2.dp.toPx()
                            val arcSize = Size(size.width - inset * 2, size.height - inset * 2)
                            drawArc(
                                // 玻璃上轨道要用半透明白/黑，实色轨道会和折射背景糊成一体
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

                    Slider(
                        value = selectedIndex.toFloat(),
                        onValueChange = { selectedIndex = it.roundToInt().coerceIn(0, minutesOptions.size - 1) },
                        valueRange = 0f..(minutesOptions.size - 1).toFloat(),
                        steps = minutesOptions.size - 2,
                        modifier = Modifier.fillMaxWidth(),
                        thumb = {
                            Box(
                                Modifier
                                    .size(16.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(Color.White)
                            )
                        },
                        track = { sliderState ->
                            val fraction = if (sliderState.valueRange.endInclusive - sliderState.valueRange.start == 0f) 0f
                                else (sliderState.value - sliderState.valueRange.start) / (sliderState.valueRange.endInclusive - sliderState.valueRange.start)
                            Box(
                                Modifier
                                    .fillMaxWidth()
                                    .height(16.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(if (glass) onSheet.copy(alpha = 0.18f) else colors.card)
                            ) {
                                Box(
                                    Modifier
                                        .fillMaxWidth(fraction)
                                        .height(16.dp)
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(accent)
                                )
                            }
                        }
                    )

                    // 刻度标签
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

                    // 取消 / 关闭定时器 / 设置
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
                        // 设置：主题色实心，玻璃上也要保持醒目，所以不走玻璃材质
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

/** 弹层里的描边按钮：底走当前材质（玻璃下是嵌套叠加层），描边给语义色 */
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

/** 标准 Material 图标（手写 ImageVector，避免引 5MB 的 icons-extended 库） */
private object Icons {
    // 图标定义已收拢到全局 IrisIcons（唯一来源）。此处保留 typealias 式转发，
    // 避免大面积改调用点，同时消除三份重复的 path 字符串与构建器。
    val Play get() = IrisIcons.Play
    val Pause get() = IrisIcons.Pause
    val Shuffle get() = IrisIcons.Shuffle
    val Repeat get() = IrisIcons.Repeat
    val Bedtime get() = IrisIcons.Bedtime
    val Tune get() = IrisIcons.Tune
    val Favorite get() = IrisIcons.Favorite
    val FavoriteBorder get() = IrisIcons.FavoriteBorder
}

/**
 * 四合一播放模式指示（原 随机+循环 两枚按钮合并后的图标）：
 * 关 = 灰色随机图标；随机 = 点亮随机；列表循环 = 点亮循环；单曲循环 = 点亮循环 + 中心圆点。
 *
 * 图标形状表示"当前是哪一档"，颜色深浅表示开/关；随机 ↔ 循环切换时做
 * 同点旋转淡变（和播放/暂停同款手法），两图标交叉切换不硬切。
 */
@Composable
private fun PlayModeIcon(shuffle: Boolean, repeatMode: RepeatMode, color: Color) {
    // 单曲圆点：淡入 + 弹性放大，不硬出现
    val oneT by animateFloatAsState(
        targetValue = if (repeatMode == RepeatMode.ONE) 1f else 0f,
        animationSpec = IrisMotion.pressScale(),
        label = "playModeOneDot"
    )
    // 随机(0) ↔ 循环(1) 图标翻面过渡
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
                // 单曲循环必须在图形上有额外标记，否则和列表循环完全一样
                drawCircle(
                    color = color,
                    radius = 1.9.dp.toPx(),
                    center = Offset(size.width / 2f, size.height / 2f)
                )
            }
        }
    }
}

/** 时长格式化统一用 MainScreen.kt 里的 internal fun formatDuration */

/** 无 Coil 的封面加载：直接从音频文件读内嵌封面（比 MediaStore 缓存准确） */
@Composable
private fun AlbumArt(filePath: String?) {
    // 不用 remember(filePath)：切歌时 key 变化会把 bitmap 重置 null，
    // 新封面解码的几百毫秒里露出占位色块、解码完又突然出现——就是切歌"闪一下"。
    // 改成保留旧图、新图就绪才替换 + Crossfade 淡入。
    // 初始值先同步窥缓存：翻卡前这张封面就在旁边的卡上显示过、必在缓存里，
    // 首帧直接命中，连那一次 280ms 淡入都省了（淡入本身也是"闪"的一种）。
    // 高清优先：若这张之前已在大卡看过、命中高清内存缓存，首帧直接上高清。
    var bitmap by remember { mutableStateOf(ArtworkLoader.peekHiRes(filePath) ?: ArtworkLoader.peek(filePath)) }
    var loadedPath by remember { mutableStateOf<String?>(if (bitmap != null) filePath else null) }
    // 当前显示的是否已是高清：避免高清就绪后又被后到的缩略图覆盖回去。
    var hiResPath by remember { mutableStateOf<String?>(if (ArtworkLoader.peekHiRes(filePath) != null) filePath else null) }

    LaunchedEffect(filePath) {
        // 阶段一：缩略图立即到位（磁盘缓存命中≈瞬时），先保证"不割裂"。
        if (filePath != loadedPath) {
            val bmp = if (filePath == null) null else ArtworkLoader.load(filePath)
            // 若高清已在阶段二先就位，别用低清盖回去。
            if ((bmp != null || filePath == null) && hiResPath != filePath) {
                bitmap = bmp
                loadedPath = filePath
            }
        }
        // 阶段二：后台解码高清原图，就绪后无缝换上，保证"又清"。
        // 大图只在播放大卡这一处用，内存开销可控（见 ArtworkLoader.HIRES_CACHE_MAX）。
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

/** 进度条 */
@Composable
private fun NeonProgressBar(
    progress: Float,
    onSeek: (Float) -> Unit,
    accent: Color,
    track: Color,
    modifier: Modifier = Modifier,
    /** 液态玻璃模式：轨道背后是任意亮度的模糊封面，实色轨道会融进背景 */
    glass: Boolean = false,
    isDark: Boolean = true,
    /** false 时只画不响应手势（卡片模式里非当前那几张卡） */
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
                // 玻璃上不能用实色轨道：卡片是半透明的，轨道会和模糊封面糊成一体。
                // 改成"凹槽"——压暗一层 + 一圈亮描边，无论背后封面是亮是暗都看得见沟槽轮廓。
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
            // 玻璃下给已播部分补一圈同色高光：主题色若与封面撞色，靠这圈亮边仍能分辨进度位置
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

/**
 * 横屏卡片模式播放器：左侧封面舞台由外部 [coverSlot] 提供（承担翻页/堆叠动画），
 * 右侧是固定的控制区。切歌时右侧标题/艺术家做模糊淡变过渡（title/artist 变化触发）。
 *
 * 复用 PlayerControls（与竖屏/LIST 横屏同一套控件与动画），颜色/频谱过渡在此自算。
 */
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
    /**
     * 封面舞台的宽高比。
     *
     * 舞台必须按高度算出宽度，不能用 weight 吃满剩余空间：
     * 封面是正方形（宽=高），舞台却被拉到几百 dp 宽时，
     * 页与页之间就空出一大条，邻曲被推到舞台边缘外。
     * 1.0 = 刚好一张封面；大于 1 的部分就是留给邻曲/堆叠露头的余量。
     */
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

    // 可视化过渡（同 PlayerCard）
    val vizT = remember { Animatable(if (visualizerEnabled) 1f else 0f) }
    LaunchedEffect(visualizerEnabled) {
        vizT.animateTo(
            targetValue = if (visualizerEnabled) 1f else 0f,
            animationSpec = tween(420, easing = androidx.compose.animation.core.EaseInOut)
        )
    }

    // 切歌时资料模糊淡变：title+artist 变化 → 淡出模糊 → 换字 → 淡入清晰。
    // 用一个 Animatable 驱动 alpha 与 blur（同封面歌词的换词动画节奏）。
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
        // 横屏窄化：整行封顶 LANDSCAPE_PLAYER_MAX_WIDTH 并居中（本组件只在横屏
        // 卡片模式使用）。唱片墙不经过这里，不受影响。
        // 背板与 LIST 播放页的 PlayerCard 完全同款（GlassLevel.PLAYER 玻璃 +
        // 同圆角同底色）——此前这里只有一排裸控件悬在背景上，没有卡片包裹，
        // 三个布局里只有它有玻璃背板，观感断裂。
        Row(
            Modifier
                .fillMaxHeight()
                .widthIn(max = LANDSCAPE_PLAYER_MAX_WIDTH)
                .irisSurface(GlassLevel.PLAYER, colors, IrisShape.card, solid = cardColor)
                .padding(horizontal = 18.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // 左：封面舞台（翻页/堆叠由外部提供）。
            // 宽度由 coverAspect 按高度算出——封面是正方形，舞台宽度就该等于
            // 高度（加上邻曲露头的余量），不能吃满剩余宽度。
            Box(
                Modifier
                    .fillMaxHeight()
                    .aspectRatio(coverAspect),
                contentAlignment = Alignment.Center
            ) { coverSlot() }

            // 右：控制区，拿走剩下的横向空间并居中。
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