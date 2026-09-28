/*
 * This file is part of IRIS Music.
 *
 * IRIS Music is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * IRIS Music is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with IRIS Music.  If not, see <https://www.gnu.org/licenses/>.
 */
package com.iris.music.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

/**
 * 全局矢量图标集（Material Symbols Rounded 路径）。
 *
 * 项目不引 material-icons-extended（省 5MB+），图标一律用内联 path 现建。
 * 原先 PlayerCard / PosterWall / NightFlightScene 各自复制了一份 `icon()`
 * 构建器和 Play/Pause 的路径字符串——同一份数据散在三处，改一个漏两个。
 * 这里收拢成唯一来源：所有 ImageVector 都 [lazy] 构建、构建一次全进程复用。
 */
internal object IrisIcons {
    /** 24dp viewport 的单路径图标构建器。填充色固定黑，实际着色由 Icon(tint=) 决定。 */
    private fun icon(path: String): ImageVector = ImageVector.Builder(
        defaultWidth = 24.dp, defaultHeight = 24.dp,
        viewportWidth = 24f, viewportHeight = 24f
    ).apply {
        addPath(
            pathData = PathParser().parsePathString(path).toNodes(),
            fill = SolidColor(Color.Black)
        )
    }.build()

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
