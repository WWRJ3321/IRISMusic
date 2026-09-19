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

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.iris.music.audio.EQ_FREQ_MAX
import com.iris.music.audio.EQ_FREQ_MIN
import com.iris.music.audio.EQ_MAX_ANCHORS
import com.iris.music.audio.EQ_MIN_ANCHORS
import com.iris.music.audio.EqBoost
import com.iris.music.audio.EqCurveInterpolator
import com.iris.music.audio.EqEditMode
import com.iris.music.audio.EqualizerState
import com.iris.music.ui.Haptics
import com.iris.music.ui.theme.GlassContent
import com.iris.music.ui.theme.GlassLevel
import com.iris.music.ui.theme.IrisColors
import com.iris.music.ui.theme.IrisShape
import com.iris.music.ui.theme.irisSurface
import com.iris.music.ui.theme.readableOn
import com.iris.music.ui.theme.readableTextOn
import java.util.Locale
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.roundToInt

/** 曲线面板高度 */
private val PLOT_HEIGHT = 200.dp

/** 命中锚点的触摸半径 */
private val HIT_RADIUS = 30.dp

/**
 * 左侧 dB 刻度栏宽度。
 *
 * 原来是 26dp，"-15"这种四字符文本在 11sp 下要 ~28dp，于是最后一位被裁掉。
 * 顺带给频率刻度行留够高度：12dp 装不下 11sp 的一行字（行高约 14dp），底部会被切。
 */
private val DB_AXIS_WIDTH = 34.dp
private val FREQ_AXIS_HEIGHT = 16.dp

/**
 * 均衡器面板：曲线模式（自由锚点） / 滑条模式。
 *
 * 用自绘覆盖层而非 ModalBottomSheet —— 后者自带下滑关闭手势，
 * 会和面板内的垂直拖动争抢触摸事件，导致节点拖不动。
 */
@Composable
fun EqualizerSheet(
    state: EqualizerState,
    onDismiss: () -> Unit,
    onSelectBoost: (EqBoost?) -> Unit,
    onToggleLoudness: (Boolean) -> Unit,
    onLoudnessStrengthChange: (Int) -> Unit = {},
    onSetEditMode: (EqEditMode) -> Unit,
    onBandLevel: (Int, Int) -> Unit,
    onMoveAnchor: (Int, Int, Int) -> Unit,
    onCommitAnchors: () -> Unit,
    onAddAnchor: (Int, Int) -> Unit,
    onRemoveAnchor: (Int) -> Unit,
    onReset: () -> Unit,
    // V2.2 声音增强（与 EQ 同属调音维度，挂在均衡器弹层）
    virtualBass: Boolean = false,
    virtualBassStrength: Int = 40,
    onVirtualBassChange: (Boolean) -> Unit = {},
    onVirtualBassStrengthChange: (Int) -> Unit = {},
    rangeEnhancer: Boolean = false,
    rangeEnhancerStrength: Int = 50,
    onRangeEnhancerChange: (Boolean) -> Unit = {},
    onRangeEnhancerStrengthChange: (Int) -> Unit = {},
    virtualSurround: Boolean = false,
    virtualSurroundStrength: Int = 40,
    onVirtualSurroundChange: (Boolean) -> Unit = {},
    onVirtualSurroundStrengthChange: (Int) -> Unit = {},
    colors: IrisColors
) {
    val onSheet = if (colors.isDark) Color.White else Color.Black
    // 弹层底是 colors.surface（浅色下近白），主题色直接当文字对比度不够
    val accent = colors.primary.readableOn(colors.surface, 3.2f)
    // 拖把手下滑关闭
    val drag = rememberSheetDragState()

    IrisSheet(
        colors = colors,
        onDismiss = onDismiss,
        drag = drag,
        // 本面板行多字密，玻璃下遮罩不减半：减半后背景会从行间透出来干扰刻度/文字
        glassScrim = false,
        panelModifier = Modifier.heightIn(max = 720.dp),
        contentModifier = Modifier
            .navigationBarsPadding()
            .padding(horizontal = 20.dp)
            .padding(top = 10.dp, bottom = 20.dp)
    ) {
            SheetDragHandle(drag, onDismiss, colors.card)

            Spacer(Modifier.height(14.dp))

            // 标题
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "音效",
                    Modifier.weight(1f),
                    color = onSheet,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Black
                )
            }

            if (!state.available) {
                Spacer(Modifier.height(20.dp))
                Text("当前设备不支持系统均衡器", color = colors.subText, fontSize = 13.sp, lineHeight = 20.sp)
                Spacer(Modifier.height(16.dp))
                DoneButton(colors, onDismiss)
            } else {

            Spacer(Modifier.height(14.dp))
            // 中段（增强项 + 编辑器）可滚动：全开时不会把重置/完成按钮挤出屏幕
            Column(
                Modifier
                    .weight(1f, fill = false)
                    .verticalScroll(rememberScrollState())
            ) {
            // 模式切换
            Row(
                Modifier
                    .fillMaxWidth()
                    .irisSurface(GlassLevel.ROW, colors, IrisShape.item)
                    .padding(3.dp)
            ) {
                ModeTab("曲线", state.editMode == EqEditMode.CURVE, colors, Modifier.weight(1f)) {
                    onSetEditMode(EqEditMode.CURVE)
                }
                ModeTab("滑条", state.editMode == EqEditMode.SLIDER, colors, Modifier.weight(1f)) {
                    onSetEditMode(EqEditMode.SLIDER)
                }
            }

            Spacer(Modifier.height(12.dp))

            // 增强预设：三档一行平分，点中的再点一次取消
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                EqBoost.entries.forEach { boost ->
                    PresetChip(
                        boost.label,
                        state.boost == boost,
                        colors,
                        Modifier.weight(1f)
                    ) { onSelectBoost(boost) }
                }
            }

            Spacer(Modifier.height(10.dp))

            // 等响曲线补偿：和三档增强是两个维度，可以同时开
            LoudnessRow(state.loudness, state.loudnessStrength, colors, { onToggleLoudness(!state.loudness) }, onLoudnessStrengthChange)
            Spacer(Modifier.height(6.dp))
            // V2.2 虚拟低音：心理声学谐波合成，为小喇叭补低音存在感
            EnhancerRow(
                title = "虚拟低音",
                subtitle = "",
                checked = virtualBass,
                strength = virtualBassStrength,
                strengthLabel = "混合强度",
                colors = colors,
                onToggle = { onVirtualBassChange(!virtualBass) },
                onStrengthChange = onVirtualBassStrengthChange
            )
            Spacer(Modifier.height(6.dp))
            // V2.2 动态范围增强：压缩 + 化妆增益
            EnhancerRow(
                title = "动态范围增强",
                subtitle = "",
                checked = rangeEnhancer,
                strength = rangeEnhancerStrength,
                strengthLabel = "增强强度",
                colors = colors,
                onToggle = { onRangeEnhancerChange(!rangeEnhancer) },
                onStrengthChange = onRangeEnhancerStrengthChange
            )
            Spacer(Modifier.height(6.dp))
            // V2.2 虚拟环绕：M/S 侧边增强，静态声场展宽（非 3D 旋转）
            EnhancerRow(
                title = "虚拟环绕",
                subtitle = "",
                checked = virtualSurround,
                strength = virtualSurroundStrength,
                strengthLabel = "扩展强度",
                colors = colors,
                onToggle = { onVirtualSurroundChange(!virtualSurround) },
                onStrengthChange = onVirtualSurroundStrengthChange
            )
            Spacer(Modifier.height(14.dp))

            when (state.editMode) {
                EqEditMode.CURVE -> CurveEditor(
                    state = state,
                    colors = colors,
                    onMoveAnchor = onMoveAnchor,
                    onCommitAnchors = onCommitAnchors,
                    onAddAnchor = onAddAnchor,
                    onRemoveAnchor = onRemoveAnchor
                )

                EqEditMode.SLIDER -> SliderEditor(
                    state = state,
                    colors = colors,
                    onBandLevel = onBandLevel
                )
            }
            } // end scrollable Column
            Spacer(Modifier.height(16.dp))

            // 重置 / 完成
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(
                    Modifier
                        .weight(1f)
                        .height(44.dp)
                        .clip(IrisShape.item)
                        .background(colors.card)
                        .border(1.dp, onSheet.copy(alpha = 0.25f), IrisShape.item)
                        .clickable { Haptics.tap(); onReset() },
                    contentAlignment = Alignment.Center
                ) {
                    Text("重置", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = onSheet.copy(alpha = 0.75f))
                }
                Box(
                    Modifier
                        .weight(1f)
                        .height(44.dp)
                        .clip(IrisShape.item)
                        .background(accent)
                        .clickable { Haptics.click(); onDismiss() },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        "完成",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = accent.readableTextOn()
                    )
                }
            } // end else
        }
    }
}

@Composable
private fun DoneButton(colors: IrisColors, onClick: () -> Unit) {
    Box(
        Modifier
            .fillMaxWidth()
            .height(44.dp)
            .clip(IrisShape.item)
            .background(colors.primary)
            .clickable { Haptics.click(); onClick() },
        contentAlignment = Alignment.Center
    ) {
        Text(
            "完成",
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
            color = colors.primary.readableTextOn()
        )
    }
}

// ==================== 曲线模式 ====================

/**
 * 曲线编辑器：按住节点直接拖动，空白处单击新增，节点双击删除。
 * 拖动时用本地状态渲染节点位置，保证 60fps 跟手，抬手后再提交排序。
 */
@Composable
private fun CurveEditor(
    state: EqualizerState,
    colors: IrisColors,
    onMoveAnchor: (Int, Int, Int) -> Unit,
    onCommitAnchors: () -> Unit,
    onAddAnchor: (Int, Int) -> Unit,
    onRemoveAnchor: (Int) -> Unit
) {
    val onSheet = if (colors.isDark) Color.White else Color.Black
    val dim = 1f
    val accent = colors.primary.copy(alpha = dim)
    val gridColor = onSheet.copy(alpha = 0.08f * dim)
    val axisColor = onSheet.copy(alpha = 0.22f * dim)

    val minMb = state.minLevelMb
    val maxMb = state.maxLevelMb
    val range = (maxMb - minMb).coerceAtLeast(1)
    val anchors = state.anchors

    /** 始终读取最新 anchors，避免 pointerInput 捕获旧值导致 hitTest 命中位置过时 */
    val anchorsState = rememberUpdatedState(anchors)

    var plotSize by remember { mutableStateOf(IntSize.Zero) }
    var dragIndex by remember { mutableStateOf(-1) }

    val hitRadiusPx = with(LocalDensity.current) { HIT_RADIUS.toPx() }

    val logMin = log10(EQ_FREQ_MIN)
    val logSpan = log10(EQ_FREQ_MAX) - logMin

    val maxDb = (maxMb / 100f).roundToInt()
    val minDb = (minMb / 100f).roundToInt()
    val dbTicks = listOf(maxDb, maxDb / 2, 0, minDb / 2, minDb)
    val freqTicks = listOf(31, 62, 125, 250, 500, 1000, 2000, 4000, 8000, 16000)

    fun xOfFreq(freq: Int): Float =
        ((log10(freq.coerceAtLeast(1).toFloat()) - logMin) / logSpan).coerceIn(0f, 1f)

    fun freqOfX(xNorm: Float): Int =
        10f.pow(logMin + xNorm.coerceIn(0f, 1f) * logSpan)
            .roundToInt()
            .coerceIn(EQ_FREQ_MIN.toInt(), EQ_FREQ_MAX.toInt())

    fun yOfGain(mb: Int): Float = 1f - ((mb - minMb).toFloat() / range).coerceIn(0f, 1f)

    fun gainOfY(yNorm: Float): Int =
        (minMb + (1f - yNorm.coerceIn(0f, 1f)) * range).roundToInt().coerceIn(minMb, maxMb)

    fun hitTest(pos: Offset): Int {
        if (plotSize.width == 0 || plotSize.height == 0) return -1
        val ans = anchorsState.value
        var best = -1
        var bestDist = Float.MAX_VALUE
        ans.forEachIndexed { i, a ->
            val ax = xOfFreq(a.freqHz) * plotSize.width
            val ay = yOfGain(a.gainMb) * plotSize.height
            val d = (pos.x - ax) * (pos.x - ax) + (pos.y - ay) * (pos.y - ay)
            if (d < bestDist) {
                bestDist = d
                best = i
            }
        }
        return if (bestDist <= hitRadiusPx * hitRadiusPx) best else -1
    }

    Column {
        Row(Modifier.fillMaxWidth()) {
            // 左侧 dB 刻度
            Column(
                Modifier.width(DB_AXIS_WIDTH).height(PLOT_HEIGHT),
                verticalArrangement = Arrangement.SpaceBetween,
                horizontalAlignment = Alignment.End
            ) {
                dbTicks.forEach { db ->
                    Text(
                        "$db",
                        color = onSheet.copy(alpha = 0.7f * dim),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        // 不给 softWrap=false 的话 "-15" 会在窄栏里折行，折行后下半截被裁
                        softWrap = false
                    )
                }
            }

            Spacer(Modifier.width(6.dp))

            Column(Modifier.weight(1f)) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(PLOT_HEIGHT)
                        .irisSurface(GlassLevel.CARD, colors, IrisShape.item)
                        .onSizeChanged { plotSize = it }
                        .pointerInput(minMb, maxMb, plotSize) {
                            var lastUpMs = 0L
                            var lastUpIndex = -1

                            awaitEachGesture {
                                val down = awaitFirstDown()
                                val hit = hitTest(down.position)
                                val now = System.currentTimeMillis()

                                // 双击同一节点 → 删除
                                if (hit >= 0 && hit == lastUpIndex && now - lastUpMs < 300L) {
                                    lastUpIndex = -1
                                    onRemoveAnchor(hit)
                                    // 消耗掉本次触摸剩余事件
                                    do {
                                        val e = awaitPointerEvent()
                                        e.changes.forEach { it.consume() }
                                    } while (e2Pressed(e))
                                    return@awaitEachGesture
                                }

                                if (hit < 0) {
                                    // 空白：消费 down 防止穿透，抬手且几乎未移动 → 新增节点
                                    down.consume()
                                    var moved = false
                                    val start = down.position
                                    while (true) {
                                        val event = awaitPointerEvent()
                                        val ptr = event.changes.firstOrNull { it.id == down.id } ?: break
                                        ptr.consume()
                                        if ((ptr.position - start).getDistance() > hitRadiusPx / 2f) moved = true
                                        if (!ptr.pressed) {
                                            if (!moved && plotSize.width > 0) {
                                                onAddAnchor(
                                                    freqOfX(start.x / plotSize.width),
                                                    gainOfY(start.y / plotSize.height)
                                                )
                                            }
                                            break
                                        }
                                    }
                                    return@awaitEachGesture
                                }

                                // 命中节点：立即进入拖动，全程消费事件
                                dragIndex = hit
                                down.consume()

                                while (true) {
                                    val event = awaitPointerEvent()
                                    val ptr = event.changes.firstOrNull { it.id == down.id } ?: break
                                    ptr.consume()

                                    if (plotSize.width > 0 && plotSize.height > 0) {
                                        onMoveAnchor(
                                            hit,
                                            freqOfX(ptr.position.x / plotSize.width),
                                            gainOfY(ptr.position.y / plotSize.height)
                                        )
                                    }
                                    if (!ptr.pressed) break
                                }

                                dragIndex = -1
                                lastUpMs = System.currentTimeMillis()
                                lastUpIndex = hit
                                onCommitAnchors()
                            }
                        }
                ) {
                    Canvas(Modifier.fillMaxSize()) {
                        val w = size.width
                        val h = size.height
                        val zeroY = yOfGain(0) * h

                        dbTicks.forEach { db ->
                            val y = yOfGain(db * 100) * h
                            drawLine(
                                color = if (db == 0) axisColor else gridColor,
                                start = Offset(0f, y),
                                end = Offset(w, y),
                                strokeWidth = if (db == 0) 1.2f.dp.toPx() else 1f.dp.toPx()
                            )
                        }
                        freqTicks.forEach { f ->
                            val x = xOfFreq(f) * w
                            drawLine(gridColor, Offset(x, 0f), Offset(x, h), strokeWidth = 1f.dp.toPx())
                        }

                        if (anchors.isEmpty()) return@Canvas

                        val interp = EqCurveInterpolator(anchors)
                        val curve = Path()
                        val steps = 96
                        for (i in 0..steps) {
                            val xNorm = i.toFloat() / steps
                            val gain = interp.gainAtLogX(logMin + xNorm * logSpan)
                            val x = xNorm * w
                            val y = yOfGain(gain.roundToInt()) * h
                            if (i == 0) curve.moveTo(x, y) else curve.lineTo(x, y)
                        }

                        val fill = Path().apply {
                            addPath(curve)
                            lineTo(w, zeroY)
                            lineTo(0f, zeroY)
                            close()
                        }
                        drawPath(
                            path = fill,
                            brush = Brush.verticalGradient(
                                listOf(accent.copy(alpha = 0.22f * dim), accent.copy(alpha = 0.04f * dim))
                            )
                        )
                        drawPath(
                            path = curve,
                            color = accent,
                            style = Stroke(width = 2.6f.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
                        )

                        anchors.forEachIndexed { i, a ->
                            val p = Offset(xOfFreq(a.freqHz) * w, yOfGain(a.gainMb) * h)
                            val isActive = i == dragIndex
                            if (isActive) {
                                drawLine(accent.copy(alpha = 0.3f), Offset(p.x, 0f), Offset(p.x, h), strokeWidth = 1.2f.dp.toPx())
                                drawLine(accent.copy(alpha = 0.3f), Offset(0f, p.y), Offset(w, p.y), strokeWidth = 1.2f.dp.toPx())
                                drawCircle(accent.copy(alpha = 0.2f), radius = 15.dp.toPx(), center = p)
                            }
                            val r = (if (isActive) 8f else 6f).dp.toPx()
                            drawCircle(colors.surface, radius = r, center = p)
                            drawCircle(accent, radius = r, center = p, style = Stroke(width = 2.4f.dp.toPx()))
                        }
                    }

                    Box(
                        Modifier
                            .align(Alignment.TopEnd)
                            .padding(8.dp)
                            .clip(IrisShape.chip)
                            .background(colors.surface.copy(alpha = 0.85f))
                            .padding(horizontal = 10.dp, vertical = 5.dp)
                    ) {
                        val a = anchors.getOrNull(dragIndex)
                        Text(
                            if (a != null) "${formatHz(a.freqHz)}  ${formatDb(a.gainMb)} dB"
                            else "${anchors.size} 个节点",
                            color = if (a != null) colors.primary else onSheet.copy(alpha = 0.72f),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                Spacer(Modifier.height(6.dp))

                // 高度必须够装一整行 11sp 文本（行高约 15dp），原来写死 12dp，
                // 数字下半截被容器裁掉，看起来像"数字被吃了"。
                Box(Modifier.fillMaxWidth().height(FREQ_AXIS_HEIGHT)) {
                    listOf(31, 125, 500, 2000, 8000, 16000).forEach { f ->
                        Text(
                            formatHz(f),
                            color = onSheet.copy(alpha = 0.7f * dim),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1,
                            softWrap = false,
                            modifier = Modifier.align(Alignment.CenterStart).layoutFreqTick(xOfFreq(f))
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(8.dp))

        Text(
            "拖动节点调节 · 点击空白新增 · 双击节点删除（$EQ_MIN_ANCHORS-$EQ_MAX_ANCHORS 个）",
            color = onSheet.copy(alpha = 0.68f),
            fontSize = 11.sp,
            lineHeight = 15.sp
        )
    }
}

/** 事件里是否仍有手指按下 */
private fun e2Pressed(event: androidx.compose.ui.input.pointer.PointerEvent): Boolean =
    event.changes.any { it.pressed }

/** 按归一化位置摆放频率刻度文本（文本中心对齐该位置） */
private fun Modifier.layoutFreqTick(fraction: Float): Modifier = this.then(
    Modifier.layout { measurable, constraints ->
        val placeable = measurable.measure(constraints.copy(minWidth = 0))
        layout(constraints.maxWidth, placeable.height) {
            val x = (constraints.maxWidth * fraction - placeable.width / 2f)
                .coerceIn(0f, (constraints.maxWidth - placeable.width).toFloat().coerceAtLeast(0f))
            placeable.placeRelative(x.roundToInt(), 0)
        }
    }
)

// ==================== 滑条模式 ====================

@Composable
private fun SliderEditor(
    state: EqualizerState,
    colors: IrisColors,
    onBandLevel: (Int, Int) -> Unit
) {
    // 滑条列整块玻璃背板（CARD 级：比增强行厚，条与刻度可读）
    Column(
        Modifier
            .fillMaxWidth()
            .irisSurface(GlassLevel.CARD, colors, IrisShape.item)
            .padding(vertical = 10.dp)
    ) {
        Row(Modifier.fillMaxWidth().height(PLOT_HEIGHT),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
        for (band in 0 until state.bandCount) {
            BandSlider(
                levelMb = state.levelsMb.getOrElse(band) { 0 },
                minMb = state.minLevelMb,
                maxMb = state.maxLevelMb,
                freqHz = state.centerFreqsHz.getOrElse(band) { 0 },
                dimmed = false,
                colors = colors,
                onLevelChange = { onBandLevel(band, it) },
                modifier = Modifier.weight(1f)
            )
        }
        }
    }
}
@Composable
private fun BandSlider(
    levelMb: Int,
    minMb: Int,
    maxMb: Int,
    freqHz: Int,
    dimmed: Boolean,
    colors: IrisColors,
    onLevelChange: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val onSheet = if (colors.isDark) Color.White else Color.Black
    val alpha = if (dimmed) 0.4f else 1f
    val accent = colors.primary.copy(alpha = alpha)

    var trackHeightPx by remember { mutableStateOf(0) }
    val range = (maxMb - minMb).coerceAtLeast(1)

    fun levelFromY(y: Float): Int {
        if (trackHeightPx <= 0) return levelMb
        val fraction = 1f - (y / trackHeightPx).coerceIn(0f, 1f)
        return (minMb + fraction * range).roundToInt().coerceIn(minMb, maxMb)
    }

    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            // 频段列很窄（8 段时每列不到 40dp），"+15.0" 这种五字符文本会被裁；
            // 滑条上方只需要看清抬了几个 dB，取整就够。
            formatDbCompact(levelMb),
            color = if (levelMb == 0) onSheet.copy(alpha = 0.68f * alpha) else accent,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            maxLines = 1,
            softWrap = false
        )
        Spacer(Modifier.height(6.dp))

        Box(
            Modifier
                .width(32.dp)
                .weight(1f)
                .onSizeChanged { trackHeightPx = it.height }
                .pointerInput(minMb, maxMb, trackHeightPx) {
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        down.consume()
                        onLevelChange(levelFromY(down.position.y))

                        while (true) {
                            val event = awaitPointerEvent()
                            val ptr = event.changes.firstOrNull { it.id == down.id } ?: break
                            ptr.consume()
                            onLevelChange(levelFromY(ptr.position.y))
                            if (!ptr.pressed) break
                        }
                    }
                },
            contentAlignment = Alignment.Center
        ) {
            Canvas(Modifier.fillMaxSize()) {
                val trackWidth = 4.dp.toPx()
                val cx = size.width / 2f
                val fraction = ((levelMb - minMb).toFloat() / range).coerceIn(0f, 1f)
                val thumbY = size.height * (1f - fraction)
                val zeroY = size.height * (1f - ((0 - minMb).toFloat() / range))

                drawRoundRect(
                    color = colors.card,
                    topLeft = Offset(cx - trackWidth / 2f, 0f),
                    size = Size(trackWidth, size.height),
                    cornerRadius = CornerRadius(trackWidth / 2f)
                )
                val top = minOf(zeroY, thumbY)
                val hh = abs(thumbY - zeroY)
                if (hh > 0.5f) {
                    drawRoundRect(
                        color = accent,
                        topLeft = Offset(cx - trackWidth / 2f, top),
                        size = Size(trackWidth, hh),
                        cornerRadius = CornerRadius(trackWidth / 2f)
                    )
                }
                drawRoundRect(
                    color = onSheet.copy(alpha = 0.18f * alpha),
                    topLeft = Offset(cx - 7.dp.toPx(), zeroY - 0.5f.dp.toPx()),
                    size = Size(14.dp.toPx(), 1.dp.toPx()),
                    cornerRadius = CornerRadius(0.5f.dp.toPx())
                )
                drawCircle(
                    color = accent,
                    radius = 7.dp.toPx(),
                    center = Offset(cx, thumbY.coerceIn(7.dp.toPx(), size.height - 7.dp.toPx()))
                )
            }
        }

        Spacer(Modifier.height(6.dp))
        Text(
            formatHz(freqHz),
            color = onSheet.copy(alpha = 0.72f * alpha),
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
            textAlign = TextAlign.Center,
            maxLines = 1,
            softWrap = false
        )
    }
}

// ==================== 公共小组件 ====================

@Composable
private fun ModeTab(
    label: String,
    selected: Boolean,
    colors: IrisColors,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val onSheet = if (colors.isDark) Color.White else Color.Black
    // 选中渐变：背景与文字色补间，切换编辑模式不硬跳
    val t by animateFloatAsState(
        targetValue = if (selected) 1f else 0f,
        animationSpec = tween(220),
        label = "modeTab"
    )
    Box(
        modifier
            .clip(IrisShape.chip)
            .background(lerp(Color.Transparent, colors.primary.copy(alpha = 0.18f), t))
            .clickable { Haptics.tap(); onClick() }
            .padding(vertical = 9.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            label,
            color = lerp(onSheet.copy(alpha = 0.72f), colors.primary.readableOn(colors.surface, 3.2f), t),
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold
        )
    }
}

@Composable
private fun PresetChip(
    label: String,
    selected: Boolean,
    colors: IrisColors,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val onSheet = if (colors.isDark) Color.White else Color.Black
    Box(
        modifier
            .irisSurface(GlassLevel.ROW, colors, IrisShape.item, accent = if (selected) colors.primary else null)
            .border(
                1.dp,
                if (selected) colors.primary else onSheet.copy(alpha = 0.12f),
                IrisShape.item
            )
            .clickable { Haptics.tap(); onClick() }
            .padding(horizontal = 10.dp, vertical = 9.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            label,
            color = if (selected) colors.primary.readableOn(colors.card, 3.2f) else onSheet.copy(alpha = 0.7f),
            fontSize = 12.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
            maxLines = 1,
            textAlign = TextAlign.Center
        )
    }
}
/** 等响补偿开关行：标题 + 一句解释 + 胶囊拨钮，开启时展开强度滑条 */
@Composable
private fun LoudnessRow(
    checked: Boolean,
    strength: Int,
    colors: IrisColors,
    onToggle: () -> Unit,
    onStrengthChange: (Int) -> Unit
) {
    val onSheet = if (colors.isDark) Color.White else Color.Black
    val knob by animateFloatAsState(if (checked) 1f else 0f, tween(200), label = "loudnessKnob")
    Column(
        Modifier
            .fillMaxWidth()
            .irisSurface(GlassLevel.ROW, colors, IrisShape.item)
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable { Haptics.tap(); onToggle() }
                .padding(horizontal = 14.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text("等响曲线", color = onSheet, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.width(10.dp))
        Box(
            Modifier
                .size(40.dp, 24.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(if (checked) colors.primary else onSheet.copy(alpha = 0.22f))
                .padding(2.dp),
            contentAlignment = androidx.compose.ui.BiasAlignment(
                horizontalBias = -1f + 2f * knob,
                verticalBias = 0f
            )
        ) {
            Box(Modifier.size(20.dp).clip(RoundedCornerShape(10.dp)).background(colors.surface))
        }
        }
        // 开启时展开补偿强度滑条（结构同 EnhancerRow）
        AnimatedVisibility(
            visible = checked,
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically()
        ) {
            Column(Modifier.padding(horizontal = 14.dp).padding(bottom = 10.dp)) {
                Text(
                    "补偿强度 · $strength%",
                    color = onSheet.copy(alpha = 0.6f),
                    fontSize = 10.sp
                )
                CompactSlider(
                    value = strength.toFloat(),
                    onValueChange = { onStrengthChange(it.toInt()) },
                    valueRange = 0f..100f,
                    activeColor = colors.primary,
                    inactiveColor = colors.surface
                )
            }
        }
    }
}

/**
 * V2.2 声音增强开关行：LoudnessRow 的扩展版，开启时下方展开强度滑条。
 * 开关行与滑条分开点击区域，避免误触。
 */
@Composable
private fun EnhancerRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    strength: Int,
    strengthLabel: String,
    colors: IrisColors,
    onToggle: () -> Unit,
    onStrengthChange: (Int) -> Unit
) {
    val onSheet = if (colors.isDark) Color.White else Color.Black
    val knob by animateFloatAsState(if (checked) 1f else 0f, tween(200), label = "enhKnob")
    Column(
        Modifier
            .fillMaxWidth()
            .irisSurface(GlassLevel.ROW, colors, IrisShape.item)
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable { Haptics.tap(); onToggle() }
                .padding(horizontal = 14.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(title, color = onSheet, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                if (subtitle.isNotEmpty()) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        subtitle,
                        color = onSheet.copy(alpha = 0.6f),
                        fontSize = 10.sp,
                        lineHeight = 14.sp
                    )
                }
            }
            Spacer(Modifier.width(10.dp))
            Box(
                Modifier
                    .size(40.dp, 24.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(if (checked) colors.primary else onSheet.copy(alpha = 0.22f))
                    .padding(2.dp),
                contentAlignment = androidx.compose.ui.BiasAlignment(
                    horizontalBias = -1f + 2f * knob,
                    verticalBias = 0f
                )
            ) {
                Box(Modifier.size(20.dp).clip(RoundedCornerShape(10.dp)).background(colors.surface))
            }
        }
        // 开启时展开强度滑条
        AnimatedVisibility(
            visible = checked,
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically()
        ) {
            Column(Modifier.padding(horizontal = 14.dp).padding(bottom = 10.dp)) {
                Text(
                    "$strengthLabel · $strength",
                    color = onSheet.copy(alpha = 0.6f),
                    fontSize = 10.sp
                )
                CompactSlider(
                    value = strength.toFloat(),
                    onValueChange = { onStrengthChange(it.toInt()) },
                    valueRange = 0f..100f,
                    activeColor = colors.primary,
                    inactiveColor = colors.surface
                )
            }
        }
    }
}
/** 毫贝 → dB 文本 */
private fun formatDb(levelMb: Int): String =
    if (levelMb == 0) "0" else String.format(Locale.US, "%+.1f", levelMb / 100f)

/** 毫贝 → 取整 dB 文本，供窄栏使用（滑条列宽装不下一位小数） */
private fun formatDbCompact(levelMb: Int): String =
    if (levelMb == 0) "0" else String.format(Locale.US, "%+d", (levelMb / 100f).roundToInt())

/** 频率文本：1000Hz 以上转 kHz */
private fun formatHz(hz: Int): String = when {
    hz <= 0 -> "—"
    hz < 1000 -> "$hz"
    hz % 1000 == 0 -> "${hz / 1000}k"
    else -> String.format(Locale.US, "%.1fk", hz / 1000f)
}