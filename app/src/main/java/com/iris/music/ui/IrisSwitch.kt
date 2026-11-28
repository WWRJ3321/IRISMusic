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

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.iris.music.ui.theme.IrisColors

/**
 * 全 App 唯一的开关拨钮（v5.0 收拢）。
 *
 * 之前设置页一份（走 IrisMotion.knob 果冻弹性）、音效面板两份（写死 tween(200)、
 * 尺寸 40×24 与 36×21 混用、关闭色一个 subText 一个 onSheet），同一个 App 里三种手感。
 * 现在：位置走 [IrisMotion.knob]（果冻开关统一生效），底色同步补间而不是硬切。
 *
 * 只负责外观，点击由所在行处理（整行可点，热区更大）。
 *
 * @param compact 紧凑尺寸（音效面板里四个增强项叠放时用）
 */
@Composable
fun IrisSwitch(
    checked: Boolean,
    colors: IrisColors,
    modifier: Modifier = Modifier,
    compact: Boolean = false
) {
    val t by animateFloatAsState(if (checked) 1f else 0f, IrisMotion.knob(), label = "irisSwitch")
    val track by animateColorAsState(
        if (checked) colors.primary else colors.text.copy(alpha = 0.22f),
        label = "irisSwitchTrack"
    )
    val w = if (compact) 36.dp else 40.dp
    val h = if (compact) 21.dp else 24.dp
    Box(
        modifier
            .size(w, h)
            .clip(RoundedCornerShape(h / 2))
            .background(track)
            .padding(2.dp),
        contentAlignment = BiasAlignment(horizontalBias = -1f + 2f * t, verticalBias = 0f)
    ) {
        Box(Modifier.size(h - 4.dp).clip(CircleShape).background(colors.surface))
    }
}
