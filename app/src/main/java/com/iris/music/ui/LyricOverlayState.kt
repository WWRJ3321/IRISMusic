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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.iris.music.data.LyricLine
import com.iris.music.data.LyricParser
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 全屏歌词层的状态（v5.0 抽出）。
 *
 * 列表排布（MainScreen）与卡片排布（SongDeck）原来各有一份逐字复制的
 * 「显示开关 + 歌词 + 加载中 + 所属文件 + 切歌重载 + 点封面加载」样板。
 * 而且点封面要等 IO 解析完才弹出歌词层——大文件上点了半天没反应。
 * 现在点封面立即弹出（层内显示加载态），加载由统一的副作用负责。
 */
@Stable
class LyricOverlayState {
    var visible by mutableStateOf(false)
    var lines by mutableStateOf<List<LyricLine>>(emptyList())
        internal set
    var loading by mutableStateOf(false)
        internal set
    /** 当前 [lines] 属于哪个文件；切歌后不一致即重载 */
    internal var loadedPath: String? = null

    fun open() { visible = true }
    fun close() { visible = false }
}

/**
 * @param path 当前播放歌曲的文件路径
 * @param durationMs 当前歌曲时长（纯文本歌词按时长均分行）
 */
@Composable
fun rememberLyricOverlayState(path: String?, durationMs: Long): LyricOverlayState {
    val st = remember { LyricOverlayState() }
    // 只在歌词层可见时加载；开着歌词层切歌 → 旧词清空，按新歌重解析
    LaunchedEffect(st.visible, path, durationMs) {
        if (!st.visible || path.isNullOrBlank() || path == st.loadedPath) return@LaunchedEffect
        st.lines = emptyList()
        st.loading = true
        try {
            st.lines = withContext(Dispatchers.IO) {
                runCatching { LyricParser.loadLyrics(path, durationMs) }.getOrDefault(emptyList())
            }
            st.loadedPath = path
        } catch (e: CancellationException) {
            throw e
        } finally {
            st.loading = false
        }
    }
    return st
}
