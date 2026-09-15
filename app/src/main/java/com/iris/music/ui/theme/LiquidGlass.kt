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

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.neverEqualPolicy
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlin.math.ceil

/**
 * 液态玻璃材质层。
 *
 * v1.40 起是真折射：玻璃元件把 [LocalIrisBackdrops] 里的背板层重画进自己的图层，
 * 挂上「调色 → 模糊 → AGSL 位移取样」效果链，再叠 SDF 镜面内缘。
 * 光学原理与降级策略见 GlassBackdrop.kt。
 *
 * 分级思路：本体染色（[GlassSpec.tint]）只负责保证文字可读，玻璃质感全部交给
 * 折射深度、模糊半径、对比度/饱和度与内缘高光。这样才不会泛白——
 * 叠白色会把背后封面的颜色冲淡，颜色一淡折射就无从谈起，只剩乳白塑料。
 *
 * 与 [IrisTheme] 正交：材质只管透光与边缘光学，配色照旧由主题给。
 */

/** 表面层级：决定玻璃厚度（折射深度 / 模糊 / 染色浓度） */
enum class GlassLevel {
    /** 底部弹层：盖住内容且承载大量文字，玻璃最厚 */
    SHEET,
    /** 播放器主卡片 */
    PLAYER,
    /** 普通卡片 / 设置项 / 上栏 */
    CARD,
    /** 歌单行：数量多，玻璃最薄，避免叠加成一片白 */
    ROW,
    /** 小徽章 / 圆形按钮 */
    CHIP
}

/**
 * 是否处在另一块玻璃内部。
 *
 * 玻璃里的玻璃不能再去取样最底层背板：外壳已经把背景调色压过一遍，内层若重新取原始背景，
 * 会比外壳更亮更艳，看着像"浮在玻璃上的另一块背景"，层次立刻散掉。
 * 真玻璃里的元件只是更厚的一层，所以内层退化成叠加式染色 + 内缘高光——
 * 既符合光学，也省下大量 GPU（设置页二十多行，每行一个模糊图层是不可接受的）。
 */
val LocalGlassOnGlass = staticCompositionLocalOf { false }

/** 包住玻璃容器的内容，让里面的玻璃自动降级为叠加层 */
@Composable
fun GlassContent(content: @Composable () -> Unit) =
    CompositionLocalProvider(LocalGlassOnGlass provides true, content = content)

/**
 * [GlassContent] 的 ColumnScope 版本：在 Column 内部包住内容。
 *
 * 用于把弹层外壳统一收拢成一个组件时——外壳内部就是 Column，需要把 ColumnScope
 * 一路透传给内容，让里面的 `Modifier.weight(...)` 照常可用，而
 * [GlassContent] 自身的 lambda 没有接收者，透传不下去。
 */
@Composable
fun ColumnScope.GlassContentColumn(content: @Composable ColumnScope.() -> Unit) =
    CompositionLocalProvider(LocalGlassOnGlass provides true, content = { content() })

/** 玻璃材质令牌。调这里就能整体改质感。 */
@Immutable
data class GlassSpec(
    /** 本体染色（含 alpha）。只为文字可读性服务，越低越通透。 */
    val tint: Color,
    /** 背板模糊半径 */
    val blur: Dp,
    /** 折射带宽度：距边缘多少 dp 以内发生位移取样 */
    val refraction: Dp,
    /** 折射强度系数（相对 [refraction]） */
    val refractionScale: Float,
    /** 透镜弧度：0 = 平板玻璃，1 = 边缘明显外凸 */
    val depth: Float,
    /** 是否开色散（RGB 错位取样）。只给大面积表面开，小元件上只会变成噪点。 */
    val dispersion: Boolean,
    /**
     * 是否取样背板做真折射。
     *
     * 关掉后仍画 [tint] 染色 + 镜面内缘，只是不再把背板录进自己的图层。
     * 给「薄且量大」的表面用（见 [GlassLevel.ROW]）：一块元件要取样就得建一个
     * GraphicsLayer、每帧把整块背板 record 进来、再跑「调色 → 模糊 → AGSL」链，
     * 并且靠 onGloballyPositioned 每帧失效对齐。这些开销和元件多大无关，只和**数量**有关——
     * 一屏十几行就是十几条全背板流水线。而 ROW 只有 9dp 模糊 + 11dp 折射带，且背景本身
     * 已经是高斯糊过的封面，肉眼分辨不出这一层有没有取样，观感几乎不变。
     */
    val refracts: Boolean = true,
    /** 背板对比度：> 1 让玻璃里的内容更"有东西" */
    val contrast: Float,
    /** 背板饱和度：> 1 保住封面颜色，玻璃才不像灰塑料 */
    val saturation: Float,
    /** 背板亮度偏移：浅色模式需要正偏移压住深色封面，保证黑字可读 */
    val brightness: Float,
    /** 内缘迎光侧颜色（Plus 叠加） */
    val lightEdge: Color,
    /** 内缘背光侧颜色（SrcOver） */
    val darkEdge: Color,
    /** 内缘描边宽度 */
    val edgeWidth: Dp,
    /** 光源方向（度）。-45 = 左上打光。 */
    val edgeAngle: Float,
    /** 内缘衰减指数：越大高光越集中在正对光源的那一段 */
    val edgeFalloff: Float,
    /** 嵌在另一块玻璃里时的叠加色（不重新取样背板，见 [LocalGlassOnGlass]） */
    val nestedBody: Color,
    /** 拿不到背板时的回退填充（纯绘制近似，不透背景） */
    val fallbackBody: Color
)

/** SOLID 模式下该层级对应的实色，保证切回实色时观感与旧版一致 */
fun GlassLevel.solidColor(colors: IrisColors): Color = when (this) {
    GlassLevel.SHEET -> colors.surface
    GlassLevel.PLAYER -> if (colors.isDark) Color(0xFF1E1E26) else Color(0xFFFFFFFF)
    GlassLevel.CARD -> colors.card
    GlassLevel.ROW -> colors.row
    GlassLevel.CHIP -> colors.card
}

fun GlassLevel.glassSpec(colors: IrisColors, blurScale: Float = GLASS_BLUR_DEFAULT): GlassSpec {
    val dark = colors.isDark
    val scale = blurScale.coerceIn(GLASS_BLUR_MIN, GLASS_BLUR_MAX)
    // 染色只保证可读性。深色模式用近黑烟熏而不是白色叠加——白色会把封面颜色冲淡；
    // 浅色模式不得不用白，但配合 brightness 正偏移，让深色封面也压不住黑字。
    val tint = when (this) {
        GlassLevel.SHEET -> if (dark) Color(0x8F22222C) else Color(0x94FFFFFF)
        GlassLevel.PLAYER -> if (dark) Color(0x5220202A) else Color(0x5CFFFFFF)
        GlassLevel.CARD -> if (dark) Color(0x3D20202A) else Color(0x47FFFFFF)
        GlassLevel.ROW -> if (dark) Color(0x331E1E28) else Color(0x3DFFFFFF)
        GlassLevel.CHIP -> if (dark) Color(0x2E20202A) else Color(0x3AFFFFFF)
    }
    return GlassSpec(
        tint = tint,
        // 模糊别太狠：糊成一片就没有可辨识的折射纹理，反而更像塑料。
        // 倍率只乘模糊，不动折射带宽度——折射带决定"玻璃有多厚"，
        // 跟着模糊一起变会让低浓度下边缘光学整体塌掉，只剩一块平板。
        blur = when (this) {
            GlassLevel.SHEET -> 20.dp
            GlassLevel.PLAYER -> 16.dp
            GlassLevel.CARD -> 12.dp
            GlassLevel.ROW -> 9.dp
            GlassLevel.CHIP -> 7.dp
        } * scale,
        // 折射带越宽玻璃越厚。小元件不能给太宽，否则中心也被吃掉。
        refraction = when (this) {
            GlassLevel.SHEET -> 24.dp
            GlassLevel.PLAYER -> 22.dp
            GlassLevel.CARD -> 15.dp
            GlassLevel.ROW -> 11.dp
            GlassLevel.CHIP -> 8.dp
        },
        refractionScale = when (this) {
            GlassLevel.SHEET -> 1.0f
            GlassLevel.PLAYER -> 1.15f
            GlassLevel.CARD -> 1.0f
            GlassLevel.ROW -> 0.9f
            GlassLevel.CHIP -> 0.85f
        },
        depth = when (this) {
            // 大表面给弧度，读起来像一整块凸透镜
            GlassLevel.SHEET, GlassLevel.PLAYER -> 0.5f
            GlassLevel.CARD -> 0.35f
            else -> 0f
        },
        dispersion = this == GlassLevel.PLAYER || this == GlassLevel.SHEET,
        // 列表行量大且薄，关掉逐行取样（见 GlassSpec.refracts）：
        // 直接在糊背景上画 tint，观感一致，省下每帧 N 条全背板流水线。
        refracts = this != GlassLevel.ROW,
        contrast = if (dark) 1.18f else 1.12f,
        saturation = if (dark) 1.45f else 1.3f,
        // 浅色模式提亮背板：遇到全黑专辑封面也要保住黑字
        brightness = if (dark) -0.02f else 0.14f,
        lightEdge = if (dark) Color(0x26FFFFFF) else Color(0x33FFFFFF),
        darkEdge = if (dark) Color(0x4D000000) else Color(0x38000000),
        edgeWidth = if (this == GlassLevel.ROW || this == GlassLevel.CHIP) 1.dp else 1.4.dp,
        edgeAngle = -45f,
        edgeFalloff = 1.2f,
        // 玻璃里的玻璃：只在外壳上加薄薄一层厚度感，深色提亮、浅色压暗
        nestedBody = when (this) {
            GlassLevel.SHEET -> if (dark) Color(0x59191922) else Color(0x59FFFFFF)
            GlassLevel.PLAYER -> if (dark) Color(0x2EFFFFFF) else Color(0x14000000)
            GlassLevel.CARD -> if (dark) Color(0x1FFFFFFF) else Color(0x12000000)
            GlassLevel.ROW -> if (dark) Color(0x14FFFFFF) else Color(0x0D000000)
            GlassLevel.CHIP -> if (dark) Color(0x1AFFFFFF) else Color(0x0F000000)
        },
        // 没有背板可取样时退回不透明近似，至少保证可读
        fallbackBody = if (dark) Color(0xB8181820) else Color(0xC7FFFFFF)
    )
}

/**
 * 当前模糊浓度下的材质令牌。
 * 调用点不必自己读 [LocalGlassBlur]，避免有的地方带浓度、有的地方漏掉。
 */
@Composable
fun GlassLevel.currentSpec(colors: IrisColors): GlassSpec =
    glassSpec(colors, LocalGlassBlur.current)

/** 毛玻璃当前模糊半径（基准 × 浓度倍率） */
val frostedBlurRadius: Dp
    @Composable get() = FROSTED_BLUR_BASE * LocalGlassBlur.current

/** Outline → Path，用于把绘制限制在任意形状内（含只圆上两角的弹层） */
private fun Outline.toPath(): Path = Path().apply {
    when (val o = this@toPath) {
        is Outline.Rectangle -> addRect(o.rect)
        is Outline.Rounded -> addRoundRect(o.roundRect)
        is Outline.Generic -> addPath(o.path)
    }
}

private fun Color.scaleAlpha(f: Float) = copy(alpha = (alpha * f).coerceIn(0f, 1f))

/**
 * 液态玻璃表面。
 *
 * 绘制顺序：折射后的背板 → 染色 → 强调色 → 镜面内缘。
 *
 * 三档，按能力自动选：
 * - 有背板 + API 31+：真取样。API 33+ 再加 AGSL 位移折射与色散。
 * - 嵌在别的玻璃里（[LocalGlassOnGlass]）：叠加染色 + 内缘，不重复取样。
 * - 拿不到背板：[GlassSpec.fallbackBody] 实心近似 + 渐变内缘。
 *
 * @param backdrops 要折射的背板栈，默认取 [LocalIrisBackdrops]。
 *   浮层（上栏、歌词层）需要额外折射它盖住的那层内容，就显式传进来。
 */
@Composable
fun Modifier.glassSurface(
    spec: GlassSpec,
    shape: Shape,
    accent: Color? = null,
    elevation: Dp = 0.dp,
    backdrops: List<IrisBackdrop> = LocalIrisBackdrops.current
): Modifier {
    val nested = LocalGlassOnGlass.current
    val useBackdrop = !nested && spec.refracts && backdrops.isNotEmpty() && isRenderEffectSupported
    val shaders = remember { GlassShaders() }
    val layer = if (useBackdrop) rememberGraphicsLayer() else null
    // 元件自身坐标。用 neverEqualPolicy 的状态而不是普通字段：滚动时元件相对背板的位置在变，
    // 必须让绘制跟着失效，否则玻璃会把背景"粘"在身上一起走。
    val position = if (useBackdrop) remember { PositionHolder() } else null

    return this
        .then(if (elevation > 0.dp) Modifier.shadow(elevation, shape, clip = false) else Modifier)
        .then(
            if (position != null) Modifier.onGloballyPositioned { position.value = it }
            else Modifier
        )
        .clip(shape)
        .drawWithCache {
            if (size.minDimension <= 1f) return@drawWithCache onDrawBehind { }

            val path = shape.createOutline(size, layoutDirection, this).toPath()
            val radii = glassCornerRadii(shape, size, layoutDirection)
            val refractionPx = spec.refraction.toPx().coerceAtMost(size.minDimension * 0.45f)
            val blurPx = spec.blur.toPx()
            val edgePx = spec.edgeWidth.toPx()

            // 折射向外取样、模糊要邻域，图层必须比元件大一圈，否则四边取到空白会出暗带。
            // 取整避免半像素错位——错半格边缘就会出现一条重影。
            val pad = if (layer != null) ceil(blurPx + refractionPx).toInt() else 0

            if (layer != null) {
                layer.renderEffect = buildGlassEffect(
                    shaders = shaders,
                    spec = spec,
                    size = size,
                    cornerRadii = radii,
                    padding = pad.toFloat(),
                    blurPx = blurPx,
                    refractionPx = refractionPx
                )?.asComposeRenderEffect()
            }

            val edgeBrushes = if (isRuntimeShaderSupported) {
                ShaderBrush(buildEdgeShader(shaders, spec, size, radii, light = true)) to
                    ShaderBrush(buildEdgeShader(shaders, spec, size, radii, light = false))
            } else null

            // AGSL 不可用时的内缘：左上亮 → 中部暗 → 右下回亮，模拟双向折射
            val fallbackEdge = Brush.linearGradient(
                0.00f to spec.lightEdge,
                0.28f to spec.lightEdge.scaleAlpha(0.3f),
                0.55f to spec.darkEdge,
                0.82f to spec.lightEdge.scaleAlpha(0.5f),
                1.00f to spec.lightEdge.scaleAlpha(0.85f),
                start = Offset.Zero,
                end = Offset(size.width, size.height)
            )

            onDrawBehind {
                clipPath(path) {
                    when {
                        layer != null -> {
                            drawRefractedBackdrop(layer, backdrops, position?.value, pad)
                            drawRect(spec.tint)
                        }
                        nested -> drawRect(spec.nestedBody)
                        // 不取样的薄表面（列表行）：背景本来就铺在底下且已被高斯糊过，
                        // 直接叠 tint 即可，不能用 fallbackBody 那块实心近似——会糊死。
                        !spec.refracts -> drawRect(spec.tint)
                        else -> drawRect(spec.fallbackBody)
                    }
                    if (accent != null) drawRect(accent.copy(alpha = 0.16f))
                }
                if (edgeBrushes != null) {
                    // 迎光侧用 Plus：叠加而非覆盖，才有金属反光那种"发光"感
                    drawPath(
                        path,
                        brush = edgeBrushes.first,
                        style = Stroke(width = edgePx * 2f),
                        blendMode = BlendMode.Plus
                    )
                    drawPath(path, brush = edgeBrushes.second, style = Stroke(width = edgePx * 2f))
                } else {
                    drawPath(path, brush = fallbackEdge, style = Stroke(width = edgePx * 2f))
                }
            }
        }
}

/**
 * 元件坐标。写在布局阶段（onGloballyPositioned）、读在绘制阶段，
 * neverEqualPolicy 保证"位置又上报了一次"也算变化——滚动中每帧都要重新对齐背板。
 */
private class PositionHolder {
    var value: LayoutCoordinates? by mutableStateOf(null, neverEqualPolicy())
}

/**
 * 把背板栈录进玻璃自己的图层（renderEffect 作用其上），再画出来。
 *
 * 不需要"内容变了"的通知：录进来的是指向背板 RenderNode 的引用，
 * 背板重录后脏区会沿引用链传上来，本图层同帧重新渲染（见 [IrisBackdrop]）。
 */
private fun DrawScope.drawRefractedBackdrop(
    layer: GraphicsLayer,
    backdrops: List<IrisBackdrop>,
    coordinates: LayoutCoordinates?,
    pad: Int
) {
    layer.record(
        IntSize(size.width.toInt() + pad * 2, size.height.toInt() + pad * 2)
    ) {
        translate(pad.toFloat(), pad.toFloat()) {
            drawBackdropStack(backdrops, coordinates)
        }
    }
    layer.topLeft = IntOffset(-pad, -pad)
    drawLayer(layer)
}

/**
 * 统一表面入口：按当前材质设置在实色与液态玻璃之间切换。
 *
 * @param solid 实色模式下的填充色（激活态请由调用点自行折算后传入）
 * @param accent 玻璃模式下叠在本体上的强调色（选中 / 播放中的行）
 */
@Composable
fun Modifier.irisSurface(
    level: GlassLevel,
    colors: IrisColors,
    shape: Shape,
    solid: Color = level.solidColor(colors),
    accent: Color? = null,
    elevation: Dp = 0.dp
): Modifier = if (LocalSurfaceStyle.current == IrisSurfaceStyle.LIQUID) {
    glassSurface(level.currentSpec(colors), shape, accent, elevation)
} else {
    clip(shape).background(solid)
}

/** 当前是否为液态玻璃材质。用于个别需要改文字/图标对比度的地方。 */
val isLiquidGlass: Boolean
    @Composable get() = LocalSurfaceStyle.current == IrisSurfaceStyle.LIQUID

/**
 * 当前是否为毛玻璃材质（Haze 背景模糊）。
 *
 * 与液态玻璃互斥：毛玻璃只糊浮层背后的内容，卡片本体照旧实色；
 * 液态玻璃是整套折射光学。两者叠在一起会模糊两次，等于把折射素材糊没。
 */
val isFrostedGlass: Boolean
    @Composable get() = LocalSurfaceStyle.current == IrisSurfaceStyle.FROSTED

/** 本机能否跑真折射（AGSL）。不能则玻璃退化为半透明近似。 */
val isTrueRefractionSupported: Boolean
    get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU