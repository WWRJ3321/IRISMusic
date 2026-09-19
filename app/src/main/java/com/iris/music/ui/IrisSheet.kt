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

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import com.iris.music.ui.theme.GlassContentColumn
import com.iris.music.ui.theme.GlassLevel
import com.iris.music.ui.theme.IrisColors
import com.iris.music.ui.theme.IrisShape
import com.iris.music.ui.theme.isLiquidGlass
import com.iris.music.ui.theme.irisSurface

/**
 * 底部弹层的统一外壳：遮罩 + 底部玻璃卡片。
 *
 * 设置 / 均衡器 / 睡眠定时 / 歌单 / 加入歌单 / 听歌报告六个弹层原本是六份逐字
 * 复制的样板：遮罩色、拦截手势、入场动画、拖把手位移、玻璃表面、吃 down 的 clickable、
 * GlassContent 包裹，每处十几行。改一次弹层行为要找六个文件，还已经出现实际漂移
 * （均衡器忘了玻璃减半、睡眠定时用的是装饰性假把手、面板限高一个 520 一个 720）。
 *
 * 这里收拢成单一真源，差异全部显式成参数。
 *
 * 不用 ModalBottomSheet：它自带的 Surface 是实色容器，拿不到折射背板，
 * 液态玻璃下会露出一块实心方块（containerColor 只能给一个颜色）。
 *
 * 放在根层级调用，面板才能折射到「背景 + 页面」两层背板。
 *
 * @param drag 传入 [rememberSheetDragState] 即启用「拖把手下滑关闭」；把手本身由内容顶部
 *   自己放一个 [SheetDragHandle]。传 null 表示该弹层不可拖拽关闭。
 * @param glassScrim 玻璃下遮罩是否减半。默认减半（弹层自带折射与染色，遮罩太厚会把折射
 *   内容吃掉）；文字极密的弹层（均衡器）可传 false 保持全厚压暗。
 * @param swipeBarrier 遮罩是否用 barrier 消费整段手势。true 用于面板自身带滚动手势的
 *   场景；false 保持普通 clickable（只吃点击）。
 * @param panelModifier 面板根的额外尺寸约束，如 [Modifier.heightIn] / [Modifier.fillMaxHeight]。
 * @param contentModifier 面板内容区的内边距/滚动，挂在面板表面**之内**。
 */
@Composable
fun IrisSheet(
    colors: IrisColors,
    onDismiss: () -> Unit,
    drag: SheetDragState? = null,
    glassScrim: Boolean = true,
    swipeBarrier: Boolean = true,
    panelModifier: Modifier = Modifier,
    contentModifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    Box(
        Modifier
            .fillMaxSize()
            .background(irisSheetScrim(colors, glassScrim))
            // 遮罩必须是命中目标才能挡住背后的界面：只画 background 的 Box 没有 pointer 节点，
            // 事件会直接落到后面的歌单 / Pager 上（点歌、横滑翻页都还能用）。
            .then(
                if (swipeBarrier) Modifier.sheetGestureBarrier(onTap = onDismiss, requireUnconsumed = true)
                else Modifier.noIndicationClick(onClick = onDismiss)
            )
    ) {
        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .then(panelModifier)
                // 入场自底部上滑归位（遮罩只淡入，不跟着位移——避免黑影）
                .irisSheetSlideIn()
                // 拖把手下滑关闭：必须放在 irisSurface 之前，平移图层才包住卡片背景整体
                .then(if (drag != null) Modifier.sheetDragTranslate(drag) else Modifier)
                .irisSurface(GlassLevel.SHEET, colors, IrisShape.sheetTop)
                // 面板本体吃掉 down，遮罩那层的 requireUnconsumed 就不会误判成"点空白"
                .noIndicationClick(onClick = {})
                .then(contentModifier)
        ) {
            // 面板内的卡片处在弹层玻璃内部：叠加式染色，不再各自取样背板
            GlassContentColumn(content = content)
        }
    }
}

/**
 * 弹层遮罩颜色。
 *
 * 玻璃模式下减半：弹层自己有折射与染色，遮罩太厚会把折射内容吃掉。
 * [dimBehindGlass] = false 时按实色材质处理（文字极密的弹层需要全厚压暗）。
 */
@Composable
fun irisSheetScrim(colors: IrisColors, dimBehindGlass: Boolean = true): Color {
    val glass = isLiquidGlass && dimBehindGlass
    return Color.Black.copy(
        alpha = when {
            glass && colors.isDark -> 0.34f
            glass -> 0.18f
            colors.isDark -> 0.6f
            else -> 0.35f
        }
    )
}

/** 无水波点击：弹层面板本体用它吃掉点击，避免点面板内容触发遮罩关闭。 */
@Composable
private fun Modifier.noIndicationClick(onClick: () -> Unit): Modifier = this.clickable(
    interactionSource = remember { MutableInteractionSource() },
    indication = null,
    onClick = onClick
)

/**
 * 弹层用的手势拦截。
 *
 * Compose 的命中测试会把重叠的兄弟子树都收进事件派发路径，所以只画一层半透明遮罩
 * 并不能挡住背后的界面——手指会穿过去点歌、横滑翻页。`clickable` 也不够：它只吃点击，
 * 不消费拖动增量，底下的列表照样能滚。
 *
 * 这里在 Main 通道把整段手势的所有 change 都消费掉（子节点先收，剩下的才到这里），
 * 并把"没超过 touchSlop 的手势"视为点击。
 *
 * @param onTap 非空时作为"点空白处关闭"的回调；面板本体传 null 只做拦截。
 * @param requireUnconsumed true 用于遮罩：面板本体已在 Main 通道吃掉 down，
 *        所以点面板内部不会被误判成点遮罩。
 */
internal fun Modifier.sheetGestureBarrier(
    onTap: (() -> Unit)? = null,
    requireUnconsumed: Boolean = false
): Modifier = this.pointerInput(onTap, requireUnconsumed) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = requireUnconsumed)
        down.consume()
        val start = down.position
        val slop = viewConfiguration.touchSlop
        var moved = false
        while (true) {
            val event = awaitPointerEvent()
            event.changes.forEach { change ->
                if ((change.position - start).getDistance() > slop) moved = true
                change.consume()
            }
            if (event.changes.all { !it.pressed }) break
        }
        if (!moved) onTap?.invoke()
    }
}
