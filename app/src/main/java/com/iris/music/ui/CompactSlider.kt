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

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/**
 * 紧凑滑条：胶囊轨道 + 圆点嵌在条内滑动（非 Material 的圆点穿在轨道上）。
 * 整体高度 16dp，比默认 Slider（44dp）矮 2/3，用于减小设置项的垂直体积。
 *
 * 交互（v2.2.21 重做）：**相对拖动**——按压不改变值，只按拖动位移移动圆点。
 * 旧版"点击即跳转到按压位置"在 16dp 细条上手指精度不够，轻轻一碰就跳到
 * 指压点（偏右即满值 100），无法微调。相对拖动下轻触无副作用，拖多远走多远。
 * steps>0 时吸附到档位（含首尾）。
 */
@Composable
fun CompactSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
    modifier: Modifier = Modifier,
    steps: Int = 0,
    onValueChangeFinished: (() -> Unit)? = null,
    activeColor: Color,
    inactiveColor: Color
) {
    val start = valueRange.start
    val end = valueRange.endInclusive
    val span = (end - start).takeIf { it != 0f } ?: return
    fun coerce(v: Float): Float {
        val raw = v.coerceIn(start, end)
        if (steps > 0) {
            val stepSize = span / (steps + 1)
            return (start + ((raw - start) / stepSize).roundToInt() * stepSize).coerceIn(start, end)
        }
        return raw
    }
    // 拖动中的本地值（拖动时先更新本地，避免外层 state 回流迟滞）
    var dragging by remember { mutableFloatStateOf(Float.NaN) }
    val current = if (dragging.isNaN()) value else dragging
    val fraction = ((current - start) / span).coerceIn(0f, 1f)
    // pointerInput 的 lambda 只在 key 变化时重建，直接捕获 fraction 会拿到
    // 首次组合的旧值（外部值变化后第一次拖动从旧位置起跳）——用最新值快照
    val latestFraction by rememberUpdatedState(fraction)

    Box(
        modifier
            .fillMaxWidth()
            .height(16.dp)
            .pointerInput(start, end, steps) {
                // 自带手势循环，不用 detectHorizontalDragGestures / detectDragGestures。
                //
                // 根因：这两个工具在 touch-slop 判定阶段【不消费】position change。
                // 父级是 verticalScroll，它的 slop 阈值和滑块几乎相同（~18px）。手指按住
                // 滑块时只要有任意竖直抖动先越过 slop，父列表就在 Main pass 抢先开始滚动、
                // 滑块的 onDragCancel 触发 → 值纹丝不动。灵敏度滑条在低音区最底部、竞争最
                // 激烈，所以"完全拉不动"。
                //
                // 解法：在 Initial pass 里，从按下后的第一个 move 起就把 position change
                // consume() 掉。子节点在 Initial pass 比父节点先收到事件，这里消费后，
                // 父 verticalScroll 在 Main pass 看到的是已消费事件、无法累积 slop → 永远抢不走。
                // 值只由水平位移驱动，竖直分量忽略（既不滚列表也不影响滑块）。
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        val move = event.changes.firstOrNull { it.pressed }
                        if (move == null) break
                        // 抢占：消费位置变化，阻止父级 verticalScroll 接管为滚动
                        move.consume()
                        val dx = move.position.x - move.previousPosition.x
                        val base = if (dragging.isNaN()) latestFraction else (dragging - start) / span
                        val f = (base + dx / size.width).coerceIn(0f, 1f)
                        val v = coerce(start + f * span)
                        if (v != dragging) {
                            dragging = v
                            onValueChange(v)
                        }
                    }
                    dragging = Float.NaN
                    onValueChangeFinished?.invoke()
                }
            }
    ) {
        Canvas(Modifier.matchParentSize()) {
            // 轨道占满组件高度（16dp 胶囊），圆点（10dp）嵌在条内，不突出
            val trackHeight = size.height
            val cy = size.height / 2f
            val thumbR = 5.dp.toPx()
            val corner = CornerRadius(trackHeight / 2f)
            // 未激活段
            drawRoundRect(
                color = inactiveColor,
                topLeft = Offset(0f, cy - trackHeight / 2f),
                size = Size(size.width, trackHeight),
                cornerRadius = corner
            )
            // 激活段：延伸到圆点右边缘——圆点始终完整地包在填充色内，
            // 而不是横跨在两种颜色的分界线上
            // 圆点四周留白均等：上下 (trackHeight-2r)/2 = 3dp，左右同为 3dp，
            // 像药丸胶囊里嵌着一颗点，滑到头也不顶贴边缘
            val inset = (trackHeight - 2f * thumbR) / 2f + thumbR
            val cx = inset + (size.width - 2f * inset) * fraction
            val activeW = cx + thumbR + (trackHeight - 2f * thumbR) / 2f
            if (activeW > 0f) {
                drawRoundRect(
                    color = activeColor,
                    topLeft = Offset(0f, cy - trackHeight / 2f),
                    size = Size(activeW, trackHeight),
                    cornerRadius = corner
                )
            }
            // 圆点：嵌在轨道内滑动（不突出于条外）
            drawCircle(color = Color.White, radius = thumbR, center = Offset(cx, cy))
            // 深色描边保证圆点在任何底色上可见
            drawCircle(
                color = Color.Black.copy(alpha = 0.15f),
                radius = thumbR,
                center = Offset(cx, cy),
                style = Stroke(width = 1.dp.toPx())
            )
        }
    }
}