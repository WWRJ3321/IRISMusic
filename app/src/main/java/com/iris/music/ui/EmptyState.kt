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

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.iris.music.ui.theme.IrisColors
import kotlin.math.PI
import kotlin.math.sin

/**
 * 曲库「读取中 / 空」统一占位（v5.0）。
 *
 * 列表、卡片、堆叠三种排布原来各写各的：卡片模式是一行灰字，列表模式干脆什么都不显示
 * （首次扫描或搜索无结果时是一整块空白，像卡死）。现在三处共用这一份：
 * 读取中 = 三点呼吸波 + 文案；空 = 文案。两态之间淡入淡出切换。
 */
@Composable
fun LibraryPlaceholder(
    loading: Boolean,
    colors: IrisColors,
    modifier: Modifier = Modifier,
    emptyText: String = L.emptyLibrary
) {
    AnimatedContent(
        targetState = loading,
        transitionSpec = { fadeIn(tween(260)) togetherWith fadeOut(tween(180)) },
        label = "libraryPlaceholder",
        modifier = modifier
    ) { isLoading ->
        Column(
            Modifier.fillMaxWidth().padding(vertical = 48.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            if (isLoading) {
                LoadingDots(colors.primary)
                Spacer(Modifier.height(14.dp))
            }
            Text(
                if (isLoading) L.loadingLibrary else emptyText,
                color = colors.subText,
                fontSize = 13.sp,
                lineHeight = 19.sp,
                textAlign = TextAlign.Center
            )
        }
    }
}

/** 三点呼吸波：单个 Canvas 只重绘不重组，离开组合即停 */
@Composable
fun LoadingDots(color: Color, modifier: Modifier = Modifier) {
    val t by rememberInfiniteTransition(label = "dots").animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1100, easing = LinearEasing), RepeatMode.Restart),
        label = "dotsPhase"
    )
    Canvas(modifier.size(width = 44.dp, height = 12.dp)) {
        val r = size.height / 2f * 0.7f
        val gap = size.width / 3f
        for (i in 0 until 3) {
            // 相位依次错开 1/3 周期：一道柔和的波从左向右走
            val phase = ((t - i / 3f) * 2f * PI).toFloat()
            val k = 0.5f + 0.5f * sin(phase)
            drawCircle(
                color = color.copy(alpha = 0.25f + 0.75f * k),
                radius = r * (0.7f + 0.3f * k),
                center = Offset(gap * (i + 0.5f), size.height / 2f)
            )
        }
    }
}
