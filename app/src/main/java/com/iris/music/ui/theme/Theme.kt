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

package com.iris.music.ui.theme
import android.app.Activity
import android.os.Build
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat

@Composable
fun IRISMusicTheme(
    theme: IrisTheme = IrisTheme.MONO,
    mode: IrisMode = IrisMode.DARK,
    /** 全局圆角基准（dp），由设置项驱动 */
    cornerBase: Float = 38f,
    /** 表面材质：实色 / 毛玻璃 / 液态玻璃 */
    surfaceStyle: IrisSurfaceStyle = IrisSurfaceStyle.SOLID,
    /** 模糊浓度倍率，1.0 为基准 */
    glassBlur: Float = GLASS_BLUR_DEFAULT,
    content: @Composable () -> Unit
) {
    val colors = theme.colors(mode)


    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            // 系统栏透明：背景由 App 内容铺满（无黑白底），图标深浅跟随明暗模式
            window.statusBarColor = android.graphics.Color.TRANSPARENT
            window.navigationBarColor = android.graphics.Color.TRANSPARENT
            // 关闭系统栏对比度 scrim：Android 会强制在深色背景上的系统栏加灰色面，
            // 顶部状态栏和底部小白条位置的那些"灰块"就是它。关掉后真正透明。
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                window.isNavigationBarContrastEnforced = false
                window.isStatusBarContrastEnforced = false
            }
            val controller = WindowCompat.getInsetsController(window, view)
            controller.isAppearanceLightStatusBars = !colors.isDark
            controller.isAppearanceLightNavigationBars = !colors.isDark
        }
    }

    val colorScheme = if (colors.isDark) {
        darkColorScheme(
            primary = colors.primary,
            secondary = colors.secondary,
            tertiary = IrisPalette.NeonPurple,
            background = colors.background,
            surface = colors.surface,
            onPrimary = IrisPalette.OutlineBlack,
            onBackground = colors.text,
            onSurface = colors.text
        )
    } else {
        lightColorScheme(
            primary = colors.primary,
            secondary = colors.secondary,
            tertiary = IrisPalette.NeonPurple,
            background = colors.background,
            surface = colors.surface,
            onPrimary = IrisPalette.CardWhite,
            onBackground = colors.text,
            onSurface = colors.text
        )
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = IrisTypography
    ) {
        // 圆角尺度下发给整棵树，所有 IrisShape.* 读取的都是这个值
        CompositionLocalProvider(
            LocalCorners provides IrisCorners(cornerBase.dp),
            LocalSurfaceStyle provides surfaceStyle,
            LocalGlassBlur provides glassBlur.coerceIn(GLASS_BLUR_MIN, GLASS_BLUR_MAX),
            content = content
        )
    }
}