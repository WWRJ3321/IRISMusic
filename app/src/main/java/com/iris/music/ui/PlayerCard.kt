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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
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
    onToggleShuffle: () -> Unit,
    onCycleRepeat: () -> Unit,
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
    // 实验性：封面摇动。仅在活动卡 + 开关开启时注册加速度计。
    val shake = rememberShakeState(coverShakeEnabled && interactive)
    // 封面左下角单行歌词：仅在开启 + 活动卡解析。解析放 IO，切歌时重载。
    var coverLyrics by remember { mutableStateOf<List<LyricLine>>(emptyList()) }
    LaunchedEffect(artworkPath, coverLyricEnabled) {
        if (!coverLyricEnabled || !interactive) { coverLyrics = emptyList(); return@LaunchedEffect }
        coverLyrics = if (artworkPath == null) emptyList()
        else withContext(Dispatchers.IO) { runCatching { LyricParser.loadLyrics(artworkPath, durationMs) }.getOrDefault(emptyList()) }
    }
    // 次要文字：0.55 太淡，提到 0.72 保证小字也看得清
    val subOnCard = onCard.copy(alpha = 0.72f)

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

    Box(
        modifier = modifier
            .fillMaxWidth()
            .irisSurface(GlassLevel.PLAYER, colors, IrisShape.card, solid = cardColor)
            .padding(horizontal = 18.dp, vertical = 18.dp)
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {

            // 封面：点击显示歌词。
            // 底色不用死黑（0xFF1B1024）：封面解码间隙或无封面文件露出来的就是这块底，
            // 深色底下像"内黑"色块。改成按歌曲路径派生的 HSV 占位色，和歌单行/推荐卡一致。
            // 整张封面卡作为一个整体随摇动旋转/平移：clip+底色+点击都在同一层，
            // 旋转的是带圆角的整卡，露出的是播放器卡背景（不是纯色底）。
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f)
                    .coverShake(shake)
                    .clip(IrisShape.item)
                    .background(Color.hsv(
                        hue = (artworkPath?.hashCode()?.mod(360) ?: 0).toFloat(),
                        saturation = 0.35f,
                        value = if (colors.isDark) 0.18f else 0.92f
                    ))
                    .clickable { Haptics.tap(); onClickArtwork() }
            ) {
                AlbumArt(filePath = artworkPath)
                // 压暗层：固定盖满整卡，让频谱清晰可辨
                if (vizT.value > 0.01f) {
                    Box(
                        Modifier
                            .matchParentSize()
                            .background(Color.Black.copy(alpha = 0.3f * vizT.value))
                    )
                }
                // 封面左下角单行歌词：锁在封面上（随 shake 一起动），换词时模糊渐隐渐出。
                // 只在活动卡显示：非活动卡 positionMs 恒 0，显示也无意义还徒增解析开销。
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

            Spacer(Modifier.height(20.dp))

            // 信息区 ↔ 频谱区：叠在同一个固定高度容器里做交叉淡变。
            // 不能让两者各占布局空间——过渡中期两块同时存在会把下方内容顶开再收回，
            // 就是"突然变、移上又回来"的来源。固定高度 + Box 叠放彻底消除重排。
            // 高度 58dp：22sp 标题(约 28dp) + 4dp 间距 + 13sp 艺术家(约 17dp) ≈ 49dp，
            // 部分字体的行高更大，g/y/j 的降部在 52dp 时会被 Box 底边裁掉一截。
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(58.dp),
                contentAlignment = Alignment.Center
            ) {
                // 标题 / 作者：淡出并微微上移
                if (vizT.value < 0.995f) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier
                            .fillMaxWidth()
                            .graphicsLayer {
                                alpha = 1f - vizT.value
                                translationY = -10.dp.toPx()  * vizT.value
                            }
                    ) {
                        Text(
                            title,
                            color = onCard,
                            fontSize = 22.sp,
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

                // 频谱：淡入并从下方微微升起
                if (vizT.value > 0.005f) {
                    SpectrumBars(
                        accent = readableAccent,
                        playing = isPlaying,
                        tiltEnabled = tiltSpectrum,
                        height = 52.dp,
                        modifier = Modifier
                            .fillMaxWidth()
                            .graphicsLayer {
                                alpha = vizT.value
                                translationY = 10.dp.toPx() * (1f - vizT.value)
                            }
                    )
                }
            }

            Spacer(Modifier.height(18.dp))

            // 进度条
            NeonProgressBar(
                progress = progress,
                onSeek = onSeek,
                accent = readableAccent,
                track = trackColor,
                glass = glass,
                isDark = colors.isDark,
                interactive = interactive,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp)
            )

            Spacer(Modifier.height(6.dp))

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

            Spacer(Modifier.height(16.dp))

            // 主控制：上一曲 / 播放 / 下一曲（图标本身圆角，无底，按压缩放动画）
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                PressableIconButton(size = 62.dp, onClick = onPrev) {
                    Icon(Icons.Play, contentDescription = null, tint = onCard, modifier = Modifier.size(34.dp).graphicsLayer { scaleX = -1f })
                }
                PressableIconButton(size = 76.dp, onClick = onToggle) {
                    // 播放↔暂停图标切换：同点旋转 + 缩放淡变，不硬切。
                    // 两个三角形/双竖条都不是轴对称图形，直接 Crossfade 会有
                    // 突兀感，加上旋转 90° 的"翻面"动作观感更自然。
                    PlayPauseIcon(isPlaying, btnColor)
                }
                PressableIconButton(size = 62.dp, onClick = onNext) {
                    Icon(Icons.Play, contentDescription = null, tint = onCard, modifier = Modifier.size(34.dp))
                }
            }

            Spacer(Modifier.height(14.dp))

            // 副控制行：随机 / 循环 / 点赞 / 睡眠 / 均衡器 + 音频格式徽章
            //
            // 间距不能写死。五枚按钮是 5×38dp=190dp 的死宽，卡片模式下卡片要给邻卡
            // 或错位留边，内容区比列表模式窄 20~25dp，固定 12dp 间距会把最右边的
            // 徽章压到只剩一个字符（"OGG" 变成 "O"）。
            // 这里按可用宽度反算：徽章先按文字长度拿到自然宽度，剩下的才分给按钮间隙。
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                // 徽章预留宽度。用 11.sp.toDp() 而不是常数：系统字体放大后徽章会变宽，
                // 按 dp 常数估算会重新出现挤压。
                val glyph = with(LocalDensity.current) { 11.sp.toDp() } * 0.72f
                val badgeReserve =
                    if (audioFormat.isEmpty()) 0.dp else glyph * audioFormat.length + 20.dp
                // 除以 5 而不是 4：留一份给"均衡器 ↔ 徽章"之间的空隙，
                // 剩余空间由外层 SpaceBetween 补到那里，宽卡上观感与原来一致。
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
                        MiniControlButton(shuffle, onToggleShuffle, btnColor, onCard) { Icon(Icons.Shuffle, null, Modifier.size(20.dp), it) }
                        // 循环：OFF 灰、ALL 点亮、ONE 点亮且中心加点（三档必须在图标上分得出来）
                        MiniControlButton(repeatMode != RepeatMode.OFF, onCycleRepeat, btnColor, onCard) { RepeatIcon(repeatMode, it) }
                        // 点赞：已点赞显示实心爱心（主题色），未点赞空心
                        MiniControlButton(liked, onToggleLike, btnColor, onCard) {
                            Icon(if (liked) Icons.Favorite else Icons.FavoriteBorder, null, Modifier.size(20.dp), it)
                        }
                        // 睡眠定时器按钮：圆形计时器，激活时显示剩余时间进度环
                        SleepTimerButton(
                            active = sleepTimerMs > 0,
                            totalMs = sleepTimerMs,
                            endMs = sleepTimerEndMs,
                            onClick = onOpenSleepTimer,
                            accent = btnColor,
                            onCard = onCard
                        )
                        // 均衡器：曲线非全平时点亮（原先写死 true，永远是"开"的样子）
                        MiniControlButton(eqActive, onOpenEqualizer, btnColor, onCard) { Icon(Icons.Tune, null, Modifier.size(20.dp), it) }
                    }
                    // 音频格式徽章：同时是可视化开关。开启时填充主题色高亮
                    if (audioFormat.isNotEmpty()) {
                        val badgeBg = if (visualizerEnabled) readableAccent
                                      else readableAccent.copy(alpha = if (colors.isDark) 0.22f else 0.16f)
                        // 开启时文字落在实色主题底上，需要反算可读前景
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
                                // 宽度再不够也不折行、不裁字：宁可挤掉一点按钮间隙
                                 softWrap = false
                             )
                         }
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
    private fun icon(path: String): ImageVector = ImageVector.Builder(
        defaultWidth = 24.dp, defaultHeight = 24.dp,
        viewportWidth = 24f, viewportHeight = 24f
    ).apply {
        addPath(
            pathData = PathParser().parsePathString(path).toNodes(),
            fill = SolidColor(Color.Black)
        )
    }.build()

    // Material Symbols Rounded：圆角版本（尖角用弧线过渡，圆润自然）
    val Play by lazy { icon("M8,5.14v13.72c0,0.79 0.87,1.27 1.54,0.84l10.79,-6.86c0.62,-0.39 0.62,-1.29 0,-1.69L9.54,4.29C8.87,3.87 8,4.34 8,5.14z") }
    val Pause by lazy { icon("M8,19c1.1,0 2,-0.9 2,-2V7c0,-1.1 -0.9,-2 -2,-2S6,5.9 6,7v10C6,18.1 6.9,19 8,19zM16,19c1.1,0 2,-0.9 2,-2V7c0,-1.1 -0.9,-2 -2,-2s-2,0.9 -2,2v10C14,18.1 14.9,19 16,19z") }
    val Shuffle by lazy {
        icon("M10.59,9.17L5.41,4 4,5.41l5.17,5.17 1.42,-1.41zM14.5,4l2.04,2.04L4,18.59 5.41,20 17.96,7.46 20,9.5V4h-5.5zM14.83,13.41l-1.41,1.41 3.13,3.13L14.5,20H20v-5.5l-2.04,2.04 -3.13,-3.13z")
    }
    val Repeat by lazy {
        icon("M7,7h10v3l4,-4 -4,-4v3L5,5v6h2V7zM17,17H7v-3l-4,4 4,4v-3h12v-6h-2v4z")
    }
    val Bedtime by lazy {
        icon("M9.37,5.51C9.19,6.15,9.1,6.82,9.1,7.5c0,4.08,3.32,7.4,7.4,7.4c0.68,0,1.35,-0.09,1.99,-0.27C17.45,17.19,14.93,19,12,19c-3.86,0,-7,-3.14,-7,-7C5,9.07,6.81,6.55,9.37,5.51zM12,3c-4.97,0,-9,4.03,-9,9s4.03,9,9,9s9,-4.03,9,-9c0,-0.46,-0.04,-0.92,-0.1,-1.36c-0.98,1.37,-2.58,2.26,-4.4,2.26c-2.98,0,-5.4,-2.42,-5.4,-5.4c0,-1.81,0.89,-3.42,2.26,-4.4C12.92,3.04,12.46,3,12,3L12,3z")
    }
    /** 均衡器：Material Tune（三条推子） */
    val Tune by lazy {
        icon("M3,17v2h6v-2H3zM3,5v2h10V5H3zM13,21v-2h8v-2h-8v-2h-2v6H13zM7,9v2H3v2h4v2h2V9H7zM21,13v-2H11v2H21zM15,9h2V7h4V5h-4V3h-2V9z")
    }
    /** 点赞：Material Favorite（实心爱心） */
    val Favorite by lazy {
        icon("M12,21.35l-1.45,-1.32C5.4,15.36,2,12.28,2,8.5C2,5.42,4.42,3,7.5,3c1.74,0,3.41,0.81,4.5,2.09C13.09,3.81,14.76,3,16.5,3C19.58,3,22,5.42,22,8.5c0,3.78,-3.4,6.86,-8.55,11.54L12,21.35z")
    }
    /** 取消点赞：Material FavoriteBorder（空心爱心） */
    val FavoriteBorder by lazy {
        icon("M16.5,3c-1.74,0,-3.41,0.81,-4.5,2.09C10.91,3.81,9.24,3,7.5,3C4.42,3,2,5.42,2,8.5c0,3.78,3.4,6.86,8.55,11.54L12,21.35l1.45,-1.32C18.6,15.36,22,12.28,22,8.5C22,5.42,19.58,3,16.5,3zM12.1,18.55l-0.1,0.1l-0.1,-0.1C7.14,14.24,4,11.39,4,8.5C4,6.5,5.5,5,7.5,5c1.54,0,3.04,0.99,3.57,2.36h1.87C13.46,5.99,14.96,5,16.5,5c2,0,3.5,1.5,3.5,3.5C20,11.39,16.86,14.24,12.1,18.55z")
    }
}

/**
 * 循环指示：ALL 只点亮图标，ONE 在图标中心叠一个圆点。
 *
 * 三档模式（关 / 列表循环 / 单曲循环）只靠颜色只能表达两档，
 * 单曲循环必须在图形上有额外标记，否则和列表循环完全一样。
 */
@Composable
private fun RepeatIcon(mode: RepeatMode, color: Color) {
    // 单曲圆点：淡入 + 弹性放大，不硬出现
    val oneT by animateFloatAsState(
        targetValue = if (mode == RepeatMode.ONE) 1f else 0f,
        animationSpec = IrisMotion.pressScale(),
        label = "repeatOneDot"
    )
    Box(Modifier.size(20.dp), contentAlignment = Alignment.Center) {
        Icon(Icons.Repeat, contentDescription = null, tint = color, modifier = Modifier.size(20.dp))
        if (oneT > 0.01f) {
            Canvas(
                Modifier
                    .size(20.dp)
                    .graphicsLayer {
                        alpha = oneT
                        scaleX = oneT; scaleY = oneT
                    }
            ) {
                // 原先写的是裸像素 1.8f，在 3x 屏上只有 0.6dp，肉眼几乎看不见。
                // 改成 dp 换算，任何密度下都是同样大小的一颗点。
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
    var bitmap by remember { mutableStateOf(ArtworkLoader.peek(filePath)) }
    var loadedPath by remember { mutableStateOf<String?>(if (bitmap != null) filePath else null) }

    LaunchedEffect(filePath) {
        if (filePath == loadedPath) return@LaunchedEffect
        val bmp = if (filePath == null) null else ArtworkLoader.load(filePath)
        if (bmp != null || filePath == null) {
            bitmap = bmp
            loadedPath = filePath
        }
        // bmp == null 且 filePath != null：这首歌没封面，保留当前显示。
        // 占位色块（卡片底色）只在冷启动第一张无封面歌时出现。
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