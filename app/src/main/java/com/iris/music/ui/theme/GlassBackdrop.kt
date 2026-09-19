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

import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.RenderEffect as AndroidRenderEffect
import android.graphics.RuntimeShader
import android.graphics.Shader as AndroidShader
import android.os.Build
import androidx.annotation.ChecksSdkIntAtLeast
import androidx.annotation.RequiresApi
import androidx.compose.foundation.shape.CornerBasedShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.neverEqualPolicy
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection

/**
 * 液态玻璃的背板系统与光学内核。
 *
 * 关键认知：液态玻璃的"通透"来自**位移取样**——边缘的像素去采样更靠内的背景，
 * 背景于是在玻璃边缘被压缩、拉伸，看起来真像透过一块有厚度的玻璃。
 * 这件事画渐变永远做不到，只能得到"雾"。所以必须拿到背板像素：
 *
 * 1. [irisBackdropSource] 把一段内容录进 [GraphicsLayer]；
 * 2. 玻璃元件把这些层重画进自己的图层，挂上 RenderEffect 链：
 *    色彩（对比度/饱和度）→ 模糊 → AGSL 折射；
 * 3. 再用第二个 AGSL（SDF 法向 · 光源点积）画镜面内缘。
 *
 * SDF 折射与高光算法参考 Kyant0/AndroidLiquidGlass（Apache-2.0）。
 * AGSL 需要 API 33，31~32 退化为"真模糊 + 调色 + 渐变内缘"，30 及以下退化为绘制近似。
 */

// ==================== 背板 ====================

/**
 * 一块可被玻璃取样的背板。
 *
 * [coordinates] 用 neverEqualPolicy：位置每次上报都算变化，玻璃元件在绘制里读它就能跟随滚动。
 *
 * 这里刻意**没有**"内容变了"的通知状态。在绘制阶段写 state 会引发每帧互相失效的死循环：
 * 任何图层失效都会 `ownerView.invalidate()` → 根显示列表重录 → 背板的 drawWithContent 重跑
 * → 又写一次 state → 玻璃失效 → 循环。
 *
 * 内容更新靠 RenderNode 引用本身：玻璃把背板层画进自己的图层时，记录的是一条指向同一个
 * RenderNode 的引用，背板重录后 HWUI 沿引用链传递脏区，玻璃那层同帧重新渲染。
 *
 * 铁律：**玻璃元件不能取样包住它自己的背板**，否则递归引用。
 * 取样必须单向：背板只能是玻璃元件的兄弟，或祖先的兄弟。
 */
@Stable
class IrisBackdrop internal constructor(internal val layer: GraphicsLayer?) {

    internal var coordinates: LayoutCoordinates? by mutableStateOf(null, neverEqualPolicy())
}

@Composable
fun rememberIrisBackdrop(): IrisBackdrop {
    val layer = if (isRenderEffectSupported) rememberGraphicsLayer() else null
    return remember(layer) { IrisBackdrop(layer) }
}

/**
 * 把这段内容照常画到屏幕上，同时录进背板层。
 *
 * 玻璃元件本身不能放进被录制的内容里，否则会自己取样自己（见 [IrisBackdrop] 的铁律）。
 */
fun Modifier.irisBackdropSource(backdrop: IrisBackdrop): Modifier = this
    .onGloballyPositioned { backdrop.coordinates = it }
    .drawWithContent {
        val layer = backdrop.layer
        if (layer == null) {
            drawContent()
            return@drawWithContent
        }
        layer.record { this@drawWithContent.drawContent() }
        drawLayer(layer)
    }

/**
 * 当前可用的背板栈，按绘制顺序从后到前。
 *
 * 用栈而不是单块：上栏浮在歌单之上，要同时折射「模糊封面 + 歌单」两层。
 */
val LocalIrisBackdrops = staticCompositionLocalOf<List<IrisBackdrop>> { emptyList() }

/** 把背板栈对齐到当前元件的坐标系后画出来 */
internal fun DrawScope.drawBackdropStack(
    backdrops: List<IrisBackdrop>,
    target: LayoutCoordinates?
) {
    if (target == null || !target.isAttached) return
    backdrops.forEach { backdrop ->
        val layer = backdrop.layer ?: return@forEach
        val source = backdrop.coordinates ?: return@forEach
        if (!source.isAttached) return@forEach
        // 元件原点在背板层坐标系中的位置：反向平移即可让层内容与屏幕对齐
        val offset = try {
            source.localPositionOf(target, Offset.Zero)
        } catch (_: Throwable) {
            target.positionInWindow() - source.positionInWindow()
        }
        translate(-offset.x, -offset.y) {
            drawLayer(layer)
        }
    }
}

// ==================== 能力探测 ====================

/** AGSL RuntimeShader：API 33 起 */
internal val isRuntimeShaderSupported: Boolean
    @ChecksSdkIntAtLeast(api = Build.VERSION_CODES.TIRAMISU)
    get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU

/** RenderEffect（模糊 / 色彩矩阵）与 GraphicsLayer 取样：API 31 起 */
internal val isRenderEffectSupported: Boolean
    @ChecksSdkIntAtLeast(api = Build.VERSION_CODES.S)
    get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

// ==================== AGSL ====================

/** 圆角矩形 SDF：距离场 + 边缘法向，折射与内缘高光共用 */
private const val ROUNDED_RECT_SDF = """
float irisRadiusAt(float2 c, float4 radii) {
    if (c.x >= 0.0) {
        return c.y <= 0.0 ? radii.y : radii.z;
    } else {
        return c.y <= 0.0 ? radii.x : radii.w;
    }
}

float irisSd(float2 c, float2 hs, float r) {
    float2 q = abs(c) - (hs - float2(r));
    return length(max(q, 0.0)) - r + min(max(q.x, q.y), 0.0);
}

float2 irisGrad(float2 c, float2 hs, float r) {
    float2 q = abs(c) - (hs - float2(r));
    if (q.x >= 0.0 || q.y >= 0.0) {
        return sign(c) * normalize(max(q, float2(0.0001)));
    } else {
        float gx = step(q.y, q.x);
        return sign(c) * float2(gx, 1.0 - gx);
    }
}

float2 irisSafeNorm(float2 v) {
    float l = length(v);
    return l > 0.0001 ? v / l : float2(0.0, 0.0);
}

float irisCircleMap(float x) {
    return 1.0 - sqrt(max(1.0 - x * x, 0.0));
}
"""

/**
 * 折射：距边缘 height 以内的像素，沿边缘法向向内偏移取样。
 *
 * 偏移量走 circleMap（圆弧曲线）：贴边处变化最急、往里迅速趋零，
 * 这正是玻璃厚度切面的光学分布。depth > 0 时法向再往中心掰一点，
 * 边缘就从"平板玻璃"变成"有弧度的透镜"。
 */
private const val REFRACTION_SHADER = """
uniform shader content;
uniform float2 size;
uniform float2 offset;
uniform float4 cornerRadii;
uniform float height;
uniform float amount;
uniform float depth;

$ROUNDED_RECT_SDF

half4 main(float2 coord) {
    float2 hs = size * 0.5;
    float2 cc = (coord + offset) - hs;
    float r = irisRadiusAt(cc, cornerRadii);

    float sd = irisSd(cc, hs, r);
    if (-sd >= height) {
        return content.eval(coord);
    }
    sd = min(sd, 0.0);

    float d = irisCircleMap(1.0 - (-sd / height)) * -amount;
    float gr = min(max(r, 1.0) * 1.5, min(hs.x, hs.y));
    float2 g = normalize(irisGrad(cc, hs, gr) + depth * irisSafeNorm(cc));

    return content.eval(coord + d * g);
}
"""

/** 带色散的折射：RGB 三通道错开取样，四角最强，边缘泛出极淡的虹彩 */
private const val REFRACTION_DISPERSION_SHADER = """
uniform shader content;
uniform float2 size;
uniform float2 offset;
uniform float4 cornerRadii;
uniform float height;
uniform float amount;
uniform float depth;
uniform float dispersion;

$ROUNDED_RECT_SDF

half4 main(float2 coord) {
    float2 hs = size * 0.5;
    float2 cc = (coord + offset) - hs;
    float r = irisRadiusAt(cc, cornerRadii);

    float sd = irisSd(cc, hs, r);
    if (-sd >= height) {
        return content.eval(coord);
    }
    sd = min(sd, 0.0);

    float d = irisCircleMap(1.0 - (-sd / height)) * -amount;
    float gr = min(max(r, 1.0) * 1.5, min(hs.x, hs.y));
    float2 g = normalize(irisGrad(cc, hs, gr) + depth * irisSafeNorm(cc));

    float2 base = coord + d * g;
    float k = dispersion * ((cc.x * cc.y) / (hs.x * hs.y));
    float2 shift = d * g * k;

    half4 red = content.eval(base + shift);
    half4 green = content.eval(base);
    half4 blue = content.eval(base - shift);

    return half4(red.r, green.g, blue.b, (red.a + green.a + blue.a) / 3.0);
}
"""

/**
 * 镜面内缘：SDF 法向与光源方向的点积取幂作强度。
 *
 * 迎光侧亮、背光侧暗，沿轮廓描一圈就是玻璃的边缘切面。
 * 两侧要分两次画（迎光用 Plus 叠加才够亮，背光用 SrcOver 才压得出暗边），
 * 所以用 alpha 开关让某一侧不出图。输出预乘。
 */
private const val EDGE_SHADER = """
uniform float2 size;
uniform float4 cornerRadii;
layout(color) uniform half4 lightColor;
layout(color) uniform half4 darkColor;
uniform float lightAlpha;
uniform float darkAlpha;
uniform float angle;
uniform float falloff;

$ROUNDED_RECT_SDF

half4 main(float2 coord) {
    float2 hs = size * 0.5;
    float2 cc = coord - hs;
    float r = irisRadiusAt(cc, cornerRadii);

    float gr = min(max(r, 1.0) * 1.5, min(hs.x, hs.y));
    float2 g = irisGrad(cc, hs, gr);
    float d = dot(g, float2(cos(angle), sin(angle)));
    float intensity = pow(abs(d), falloff);

    float3 rgb = d >= 0.0 ? float3(lightColor.rgb) : float3(darkColor.rgb);
    float a = (d >= 0.0 ? lightAlpha : darkAlpha) * intensity;
    return half4(half3(rgb * a), half(a));
}
"""

/** 单个玻璃元件的 shader 缓存：RuntimeShader 必须复用实例，uniform 每次重设 */
internal class GlassShaders {

    private val cache = HashMap<String, RuntimeShader>(4)

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    fun obtain(key: String, source: String): RuntimeShader =
        cache.getOrPut(key) { RuntimeShader(source) }
}

private const val DISPERSION_STRENGTH = 0.6f

/**
 * 背板效果链：调色 → 模糊 → 折射。
 *
 * 顺序有讲究。先调色（提对比度/饱和度）让背景颜色更足——玻璃里有东西可折射才不像塑料；
 * 折射必须放在模糊之后，否则位移取样会把未模糊的锐利像素拽到边缘，出现重影。
 */
@RequiresApi(Build.VERSION_CODES.S)
internal fun buildGlassEffect(
    shaders: GlassShaders,
    spec: GlassSpec,
    size: Size,
    cornerRadii: FloatArray,
    padding: Float,
    blurPx: Float,
    refractionPx: Float
): AndroidRenderEffect? {
    var effect: AndroidRenderEffect? = null

    if (spec.contrast != 1f || spec.saturation != 1f || spec.brightness != 0f) {
        effect = AndroidRenderEffect.createColorFilterEffect(
            colorControlsFilter(spec.brightness, spec.contrast, spec.saturation)
        )
    }

    if (blurPx > 0f) {
        effect = if (effect == null) {
            AndroidRenderEffect.createBlurEffect(blurPx, blurPx, AndroidShader.TileMode.CLAMP)
        } else {
            AndroidRenderEffect.createBlurEffect(blurPx, blurPx, effect, AndroidShader.TileMode.CLAMP)
        }
    }

    if (refractionPx > 0f && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        val shader = if (spec.dispersion) {
            shaders.obtain("dispersion", REFRACTION_DISPERSION_SHADER).apply {
                setFloatUniform("dispersion", DISPERSION_STRENGTH)
            }
        } else {
            shaders.obtain("refraction", REFRACTION_SHADER)
        }
        shader.apply {
            setFloatUniform("size", size.width, size.height)
            setFloatUniform("offset", -padding, -padding)
            setFloatUniform("cornerRadii", cornerRadii)
            setFloatUniform("height", refractionPx)
            setFloatUniform("amount", refractionPx * spec.refractionScale)
            setFloatUniform("depth", spec.depth)
        }
        val lens = AndroidRenderEffect.createRuntimeShaderEffect(shader, "content")
        effect = if (effect == null) lens else AndroidRenderEffect.createChainEffect(lens, effect)
    }

    return effect
}

/** 内缘 shader。[light] = true 取迎光侧（配 Plus 叠加），false 取背光侧（配 SrcOver）。 */
@RequiresApi(Build.VERSION_CODES.TIRAMISU)
internal fun buildEdgeShader(
    shaders: GlassShaders,
    spec: GlassSpec,
    size: Size,
    cornerRadii: FloatArray,
    light: Boolean
): AndroidShader =
    shaders.obtain(if (light) "edgeLight" else "edgeDark", EDGE_SHADER).apply {
        setFloatUniform("size", size.width, size.height)
        setFloatUniform("cornerRadii", cornerRadii)
        setColorUniform("lightColor", spec.lightEdge.copy(alpha = 1f).toArgb())
        setColorUniform("darkColor", spec.darkEdge.copy(alpha = 1f).toArgb())
        setFloatUniform("lightAlpha", if (light) spec.lightEdge.alpha else 0f)
        setFloatUniform("darkAlpha", if (light) 0f else spec.darkEdge.alpha)
        setFloatUniform("angle", Math.toRadians(spec.edgeAngle.toDouble()).toFloat())
        setFloatUniform("falloff", spec.edgeFalloff)
    }

/**
 * 亮度 / 对比度 / 饱和度矩阵。
 *
 * 这是"不泛白"的核心手段：想让玻璃里的背景更清楚，就提对比度和饱和度，
 * 而不是往上叠白——叠白会把背后封面的颜色一起冲淡，冲淡了就没有可折射的东西。
 */
private fun colorControlsFilter(
    brightness: Float,
    contrast: Float,
    saturation: Float
): ColorMatrixColorFilter {
    val invSat = 1f - saturation
    val r = 0.213f * invSat
    val g = 0.715f * invSat
    val b = 0.072f * invSat
    val t = (0.5f - contrast * 0.5f + brightness) * 255f
    val cr = contrast * r
    val cg = contrast * g
    val cb = contrast * b
    val cs = contrast * saturation
    return ColorMatrixColorFilter(
        ColorMatrix(
            floatArrayOf(
                cr + cs, cg, cb, 0f, t,
                cr, cg + cs, cb, 0f, t,
                cr, cg, cb + cs, 0f, t,
                0f, 0f, 0f, 1f, 0f
            )
        )
    )
}

/** 形状四角半径 → shader 用的 (左上, 右上, 右下, 左下) */
internal fun Density.glassCornerRadii(
    shape: Shape,
    size: Size,
    layoutDirection: LayoutDirection
): FloatArray {
    val max = size.minDimension / 2f
    val corners = shape as? CornerBasedShape ?: return FloatArray(4) { 0f }
    val ltr = layoutDirection == LayoutDirection.Ltr
    val topLeft = (if (ltr) corners.topStart else corners.topEnd).toPx(size, this)
    val topRight = (if (ltr) corners.topEnd else corners.topStart).toPx(size, this)
    val bottomRight = (if (ltr) corners.bottomEnd else corners.bottomStart).toPx(size, this)
    val bottomLeft = (if (ltr) corners.bottomStart else corners.bottomEnd).toPx(size, this)
    return floatArrayOf(
        topLeft.coerceAtMost(max),
        topRight.coerceAtMost(max),
        bottomRight.coerceAtMost(max),
        bottomLeft.coerceAtMost(max)
    )
}