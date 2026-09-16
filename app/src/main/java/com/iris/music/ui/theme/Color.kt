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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** 霓虹主题配色 */
object IrisPalette {
    val NeonCyan = Color(0xFF00F0FF)
    val NeonMagenta = Color(0xFFFF2E97)
    val NeonPurple = Color(0xFF9D4EDD)
    val NeonYellow = Color(0xFFFFE94E)
    val NeonLime = Color(0xFFB4FF39)
    val NeonOrange = Color(0xFFFF8A3C)
    val NeonRed = Color(0xFFFF4D6D)
    val NeonBlue = Color(0xFF3D7BFF)
    val NeonGreen = Color(0xFF2EE6A8)
    val NeonPink = Color(0xFFFF7EB6)

    val CardWhite = Color(0xFFFDFDFD)
    val PureBlack = Color(0xFF000000)
    val OutlineBlack = Color(0xFF0A0A0A)
    val SubText = Color(0xFF8A8A8A)
    val TrackGray = Color(0xFFE4E4E4)

    // 深色模式背景
    val DarkBg = Color(0xFF000000)
    val DarkSurface = Color(0xFF111111)
    val DarkCard = Color(0xFF1A1A1A)
    val DarkRow = Color(0xFF0D0D0D)

    // 浅色模式背景
    val LightBg = Color(0xFFF5F5F7)
    val LightSurface = Color(0xFFFFFFFF)
    val LightCard = Color(0xFFEBEBEF)
    val LightRow = Color(0xFFE8E8EC)
    val LightText = Color(0xFF111111)
}

/** 明暗模式 */
enum class IrisMode(val label: String) {
    DARK("深色"),
    LIGHT("浅色"),
    AUTO("自动")
}

/**
 * 表面材质。与 [IrisTheme]（配色）正交：任意配色都能配任意材质，
 * 所以不做成第 9 套主题色，而是单独一维。
 */
enum class IrisSurfaceStyle(val label: String) {
    /** 实色卡片，原有观感 */
    SOLID("实色"),
    /** 毛玻璃：卡片仍是实色，浮层（上栏 / 歌词）走 Haze 背景模糊 */
    FROSTED("毛玻璃"),
    /** 液态玻璃：半透明本体 + 折射边缘光 + 镜面高光 */
    LIQUID("液态玻璃")
}

/**
 * 界面排布。与配色（[IrisTheme]）、材质（[IrisSurfaceStyle]）都正交：
 * 这一维决定整个 App 的信息组织方式，不决定长什么颜色。
 *
 * [LIST] 是原有结构（歌单页 ↔ 播放页 左右翻）。另两种进入"卡片模式"：
 * 整个 App 只剩一叠播放卡片，一首歌一张卡，区别只在怎么排。
 */
enum class IrisLayout(val label: String) {
    /** 纵向排布：上栏 + 竖向歌单，右滑到播放页 */
    LIST("纵向排布"),
    /** 横向排布：卡片横向铺开，左右滑动翻歌，两侧露出邻卡 */
    CAROUSEL("横向排布"),
    /** 堆叠排布：卡片叠成一沓，拖走最上面那张切歌 */
    STACK("堆叠排布"),
    /** 紧凑排布：整屏紧凑歌单 + 底部常驻迷你播放条（Folia 式） */
    COMPACT("紧凑排布");

    /** 是否为卡片模式（没有歌单页，只有播放卡片） */
    val isDeck: Boolean get() = this != LIST

    /**
     * 上栏那枚按钮按一下跳到的下一种排布，走到末尾绕回开头。
     *
     * 绕回是必须的：卡片模式里这枚按钮是唯一的出口，
     * 循环保证按几下总能回到 [LIST]。
     */
    val next: IrisLayout get() = entries[(ordinal + 1) % entries.size]
}

/** 主题定义：主色/辅色（成对配色），明暗模式独立 */
enum class IrisTheme(
    val label: String,
    val primary: Color,
    val secondary: Color
) {
    MONO("黑白", Color.White, IrisPalette.PureBlack),
    MAGENTA("霓虹粉", IrisPalette.NeonMagenta, IrisPalette.NeonPurple),
    SUNSET("落日橙", IrisPalette.NeonOrange, IrisPalette.NeonMagenta),
    VIOLET("极光紫", IrisPalette.NeonPurple, IrisPalette.NeonCyan),
    OCEAN("深海蓝", IrisPalette.NeonBlue, IrisPalette.NeonCyan),
    BLOOD("血红", IrisPalette.NeonRed, IrisPalette.NeonPurple),
    GRASS("青草绿", IrisPalette.NeonGreen, IrisPalette.NeonLime),
    /** 自定义：主/次色由用户在设置里取色器决定，枚举值只是占位 */
    CUSTOM("自定义", IrisPalette.NeonCyan, IrisPalette.NeonMagenta)
}

/**
 * 主题在给定明暗模式下的实际色对。
 *
 * 枚举里写死的 primary/secondary 只对深色模式成立：黑白主题的 primary 是纯白，
 * 直接拿去画色板或当文字色，在浅色底上会整块消失。这里复用 [colors] 里的同一套
 * 反色规则，让色板预览和实际生效的配色保持一致。
 */
fun IrisTheme.resolvedPair(isDark: Boolean): Pair<Color, Color> =
    if (this == IrisTheme.MONO) {
        if (isDark) Color.White to IrisPalette.PureBlack
        else IrisPalette.PureBlack to Color.White
    } else primary to secondary

/** 由明暗模式 + 主题计算出的完整配色 */
data class IrisColors(
    val primary: Color,
    val secondary: Color,
    val background: Color,
    val surface: Color,
    val card: Color,
    val row: Color,
    val text: Color,
    val subText: Color,
    val accentBorder: Color,
    val isDark: Boolean
) {
    val textOnCard: Color get() = if (isDark) IrisPalette.OutlineBlack else IrisPalette.LightText
    val progressTrack: Color get() = if (isDark) IrisPalette.TrackGray else IrisPalette.LightCard
}

/** 相对亮度（WCAG） */
private fun Color.wcagLuminance(): Float {
    fun ch(c: Float) = if (c <= 0.03928f) c / 12.92f else Math.pow(((c + 0.055f) / 1.055f).toDouble(), 2.4).toFloat()
    return 0.2126f * ch(red) + 0.7152f * ch(green) + 0.0722f * ch(blue)
}

/**
 * readableOn 的记忆化缓存：主题 × 背景 × 阈值的组合只有几十种，
 * 但 SongRow 每行每次重组都会调一次 readableOn（12 次循环 × 每次 2 个 pow），
 * 长列表滚动时是纯浪费。组合键是确定性的（Color 是 ULong 打包值）。
 */
private val readableCache = HashMap<Long, Color>()
private fun cacheKey(c: Color, bg: Color, ratio: Float): Long =
    (c.value.toLong() shl 32) or bg.value.toLong() xor (ratio.toRawBits().toLong() shl 20)

/** WCAG 对比度（1..21） */
fun contrastRatio(a: Color, b: Color): Float {
    val la = a.wcagLuminance()
    val lb = b.wcagLuminance()
    return (maxOf(la, lb) + 0.05f) / (minOf(la, lb) + 0.05f)
}

/**
 * 保证前景色在给定背景上可读：对比度不足时朝背景反方向逐步调整（浅底压暗、深底提亮），
 * 最多 12 步；仍不达标则回退近黑/近白。用于霓虹主题色落在浅色卡片上的场景。
 */
fun Color.readableOn(background: Color, minRatio: Float = 4.5f): Color {
    val key = cacheKey(this, background, minRatio)
    readableCache[key]?.let { return it }
    val result = readableOnUncached(background, minRatio)
    readableCache[key] = result
    return result
}

private fun Color.readableOnUncached(background: Color, minRatio: Float): Color {
    if (contrastRatio(this, background) >= minRatio) return this
    val bgIsLight = background.wcagLuminance() > 0.18f
    var c = this
    repeat(12) {
        c = if (bgIsLight) {
            Color(c.red * 0.82f, c.green * 0.82f, c.blue * 0.82f, alpha)
        } else {
            Color(
                c.red + (1f - c.red) * 0.18f,
                c.green + (1f - c.green) * 0.18f,
                c.blue + (1f - c.blue) * 0.18f,
                alpha
            )
        }
        if (contrastRatio(c, background) >= minRatio) return c
    }
    return if (bgIsLight) Color(0xFF101010) else Color(0xFFF2F2F2)
}

/**
 * 以本色作为背景时，返回黑或白中更可读的那个。
 * 用于"实色药丸 + 文字"这类前景色需要反算的场景。
 */
fun Color.readableTextOn(): Color =
    if (contrastRatio(Color.White, this) >= contrastRatio(Color.Black, this))
        Color(0xFFFAFAFA) else Color(0xFF0A0A0A)

/**
 * 全局圆角尺度。以播放器卡片外框（[card]）为基准，其余层级按比例缩放，
 * 保证调节基准值时整个界面的圆润程度同步变化且视觉关系不失衡。
 *
 * 直接写死 dp 会导致大容器和小徽章各自为政（改了一处就要全局翻一遍），
 * 所以这里只暴露语义化档位，不暴露具体数值。
 */
@androidx.compose.runtime.Immutable
data class IrisCorners(val base: Dp) {
    /** 大卡片 / 播放器外框 */
    val card: Dp get() = base
    /** 底部弹层顶角 */
    val sheet: Dp get() = base * 0.74f
    /** 列表行 / 设置行 / 封面 */
    val item: Dp get() = base * 0.42f
    /** 小徽章 / 缩略图 / 次级按钮 */
    val chip: Dp get() = base * 0.26f
}

/** 圆角基准可调范围：0dp 为纯直角，44dp 接近胶囊 */
val CORNER_BASE_MIN = 0.dp
val CORNER_BASE_MAX = 44.dp
val CORNER_BASE_DEFAULT = 38.dp

val LocalCorners = staticCompositionLocalOf { IrisCorners(CORNER_BASE_DEFAULT) }

/**
 * 模糊浓度倍率可调范围。1.0 是各层级令牌里写死的基准值，
 * 用倍率而不是绝对 dp：毛玻璃只有一个半径，液态玻璃有五个层级各自的半径，
 * 想让"一个滑块同时管两种材质"就只能乘系数。
 *
 * 上限压在 2.0：液态玻璃的取样图层要按 模糊 + 折射带 往外扩边，
 * 半径翻倍意味着图层面积明显变大，再往上推低端机会掉帧。
 */
const val GLASS_BLUR_MIN = 0.3f
const val GLASS_BLUR_MAX = 2.0f
const val GLASS_BLUR_DEFAULT = 1.0f

/** 毛玻璃基准模糊半径（倍率 1.0 时的值） */
val FROSTED_BLUR_BASE = 14.dp

/**
 * 背景封面高斯模糊半径（dp）的可调范围。
 *
 * 和上面那组 GLASS_BLUR 是两件事：玻璃模糊管"浮层背后怎么糊"，
 * 这里管最底层那张封面本身糊到什么程度，实色材质下同样生效。
 * 0 = 封面原图直出，60dp 已经糊成纯色块。
 *
 * 注意 Modifier.blur 需要 API 31+，30 及以下这个值不起作用。
 */
const val BG_BLUR_MIN = 0f
const val BG_BLUR_MAX = 60f
const val BG_BLUR_DEFAULT = 42f

/** 当前模糊浓度倍率，由设置项驱动，下发给整棵树 */
val LocalGlassBlur = staticCompositionLocalOf { GLASS_BLUR_DEFAULT }

/** 当前表面材质，由设置项驱动，下发给整棵树 */
val LocalSurfaceStyle = staticCompositionLocalOf { IrisSurfaceStyle.SOLID }

/**
 * 圆角形状快捷入口。用 @Composable 属性读取 [LocalCorners]，
 * 调用点直接 `.clip(IrisShape.item)` 即可，无需在每个函数里声明局部变量。
 */
object IrisShape {
    val card: RoundedCornerShape
        @Composable get() = RoundedCornerShape(LocalCorners.current.card)
    val item: RoundedCornerShape
        @Composable get() = RoundedCornerShape(LocalCorners.current.item)
    val chip: RoundedCornerShape
        @Composable get() = RoundedCornerShape(LocalCorners.current.chip)

    /** 底部弹层：只圆上面两角 */
    val sheetTop: RoundedCornerShape
        @Composable get() = LocalCorners.current.sheet.let {
            RoundedCornerShape(topStart = it, topEnd = it)
        }
}

/**
 * 主题切换的柔和过渡：[IrisColors] 按字段线性插值。
 *
 * 用在 MainScreen 顶层：切主题/切明暗时整套配色（含玻璃背板、进度条、指示条、
 * readableOn 缓存键）在 450ms 内渐变过去，而不是整屏硬闪一帧换色。
 * isDark 不插值（布尔值没有中间态，且深浅底互换时背景色本身就在插值）。
 */
fun lerpIrisColors(from: IrisColors, to: IrisColors, fraction: Float): IrisColors {
    fun c(a: Color, b: Color) = lerp(a, b, fraction)
    return IrisColors(
        primary = c(from.primary, to.primary),
        secondary = c(from.secondary, to.secondary),
        background = c(from.background, to.background),
        surface = c(from.surface, to.surface),
        card = c(from.card, to.card),
        row = c(from.row, to.row),
        text = c(from.text, to.text),
        subText = c(from.subText, to.subText),
        accentBorder = c(from.accentBorder, to.accentBorder),
        isDark = to.isDark
    )
}

fun IrisTheme.colors(
    mode: IrisMode,
    systemDark: Boolean = false,
    customPrimary: Color? = null,
    customSecondary: Color? = null
): IrisColors {
    val dark = when (mode) {
        IrisMode.DARK -> true
        IrisMode.LIGHT -> false
        IrisMode.AUTO -> systemDark
    }
    // 黑白主题：主色跟随明暗反色（深色=白，浅色=黑），保证可见性
    val monoPrimary = if (dark) Color.White else IrisPalette.PureBlack
    val monoSecondary = if (dark) IrisPalette.PureBlack else Color.White
    // CUSTOM 主题用用户在取色器里选的色；其它主题忽略这两个参数。
    // accentBorder 跟随实际主色（原实现固定用枚举 primary，自定义时会错位）。
    val effPrimary = when {
        this == IrisTheme.CUSTOM -> customPrimary ?: primary
        this == IrisTheme.MONO -> monoPrimary
        else -> primary
    }
    val effSecondary = when {
        this == IrisTheme.CUSTOM -> customSecondary ?: secondary
        this == IrisTheme.MONO -> monoSecondary
        else -> secondary
    }
    return IrisColors(
        primary = effPrimary,
        secondary = effSecondary,
        background = if (dark) IrisPalette.DarkBg else IrisPalette.LightBg,
        surface = if (dark) IrisPalette.DarkSurface else IrisPalette.LightSurface,
        card = if (dark) IrisPalette.DarkCard else IrisPalette.LightCard,
        row = if (dark) IrisPalette.DarkRow else IrisPalette.LightRow,
        text = if (dark) IrisPalette.CardWhite else IrisPalette.LightText,
        subText = if (dark) IrisPalette.SubText else Color(0xFF6B6B6B),
        accentBorder = effPrimary,
        isDark = dark
    )
}