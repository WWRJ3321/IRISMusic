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

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * 播放进度时钟（v5.0）。
 *
 * 进度原本是 [com.iris.music.player.PlayerUiState] 的一个字段：每 500ms 整份 87 字段状态
 * copy 一次，根节点拿到新对象 → 整棵界面树每秒重组两次。现在进度单独一条流，
 * 由 MainActivity 收集后经本 CompositionLocal 下发；**只有真正显示进度的叶子**
 * （进度条在绘制阶段、时间文字/歌词当前句经 derivedStateOf）读取它。
 *
 * 用法：需要进度的组件接收 `positionMs: () -> Long`（延迟读取），
 * 调用方传 [rememberLivePosition]；非当前卡片传 [ZeroPosition]。
 */
val LocalPlaybackPosition = staticCompositionLocalOf<State<Long>> { mutableLongStateOf(0L) }

/** 读取实时进度的稳定 lambda（同一个 State 下实例不变，不会破坏子组件 skip） */
@Composable
fun rememberLivePosition(): () -> Long {
    val s = LocalPlaybackPosition.current
    return remember(s) { { s.value } }
}

/** 非当前卡片用的恒零进度 */
val ZeroPosition: () -> Long = { 0L }
