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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import kotlinx.coroutines.launch

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
    /** 界面字体：0=系统无衬线 1=衬线 */
    uiFont: Int = 0,
    /** AUTO 模式需要系统明暗；自定义主题需要用户取色——旧实现漏传，AUTO+系统深色时状态栏图标是深色（看不见） */
    systemDark: Boolean = false,
    customPrimary: Color? = null,
    customSecondary: Color? = null,
    content: @Composable () -> Unit
) {
    val colors = theme.colors(mode, systemDark, customPrimary, customSecondary)


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
        typography = if (uiFont == 1) IrisSerifTypography else IrisTypography
    ) {
        // 圆角尺度下发给整棵树，所有 IrisShape.* 读取的都是这个值
        CompositionLocalProvider(
            LocalCorners provides IrisCorners(cornerBase.dp),
            LocalSurfaceStyle provides surfaceStyle,
            LocalGlassBlur provides glassBlur.coerceIn(GLASS_BLUR_MIN, GLASS_BLUR_MAX),
            // 设计铁律「无水波」：全局去掉 M3 涟漪。旧版只有走 irisPressable 的地方没水波，
            // 约 50 处裸 clickable（卡片、封面、排序胶囊、弹层行…）仍会冒灰色涟漪。
            androidx.compose.foundation.LocalIndication provides
                PressHighlight(if (colors.isDark) Color.White else Color.Black),
            // 全局界面字体：Text 未显式传 style 时继承 LocalTextStyle
            androidx.compose.material3.LocalTextStyle provides
                androidx.compose.ui.text.TextStyle(
                    fontFamily = if (uiFont == 1) FontFamily.Serif else FontFamily.SansSerif
                ),
            content = content
        )
    }
}

/**
 * 按压高亮（v5.0）：替代 M3 涟漪的统一按压反馈。
 *
 * 设计铁律是「无水波」，但完全没有反馈又会让约 50 处普通点击（设置行、选项块、弹层按钮、
 * 分区标题……）按下去像没点到。这里做成 iOS 式的整块轻微压暗/提亮：按下 90ms 升到 7%，
 * 松手 260ms 加速淡出——快点一下是一闪，按住是一层薄雾。形状跟随调用处在它之前的 clip。
 * 自带缩放手感的主控件（irisPressable）显式 indication=null，不叠加。
 */
private class PressHighlight(private val tint: Color) : androidx.compose.foundation.IndicationNodeFactory {
    override fun create(
        interactionSource: androidx.compose.foundation.interaction.InteractionSource
    ): androidx.compose.ui.node.DelegatableNode = PressHighlightNode(interactionSource, tint)
    override fun equals(other: Any?): Boolean = other is PressHighlight && other.tint == tint
    override fun hashCode(): Int = tint.hashCode()
}

private class PressHighlightNode(
    private val source: androidx.compose.foundation.interaction.InteractionSource,
    private val tint: Color
) : androidx.compose.ui.Modifier.Node(), androidx.compose.ui.node.DrawModifierNode {
    private val level = androidx.compose.animation.core.Animatable(0f)

    override fun onAttach() {
        coroutineScope.launch {
            var pressed = 0
            source.interactions.collect { i ->
                when (i) {
                    is androidx.compose.foundation.interaction.PressInteraction.Press -> {
                        pressed++
                        launch { level.animateTo(1f, androidx.compose.animation.core.tween(90)) }
                    }
                    is androidx.compose.foundation.interaction.PressInteraction.Release,
                    is androidx.compose.foundation.interaction.PressInteraction.Cancel -> {
                        pressed = (pressed - 1).coerceAtLeast(0)
                        if (pressed == 0) launch {
                            level.animateTo(0f, androidx.compose.animation.core.tween(260, easing = com.iris.music.ui.IrisEase.exit))
                        }
                    }
                }
            }
        }
    }

    override fun androidx.compose.ui.graphics.drawscope.ContentDrawScope.draw() {
        drawContent()
        val a = level.value
        if (a > 0.001f) drawRect(tint.copy(alpha = 0.07f * a))
    }
}
