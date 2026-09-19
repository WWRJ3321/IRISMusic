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

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Canvas as GCanvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import kotlin.math.abs
import kotlin.random.Random

/*
 * 城市夜景离屏渲染，输出 city + glow 两层：
 *  city —— 完整不透明画面。渲染时把发光体（亮窗/幕墙反光/街灯/地平辉光）
 *          作为"发光指令"收集进列表。
 *  glow —— 用发光指令在 1/4 分辨率上单独渲染；放大回全屏叠加即 bloom 辉光。
 * 纵深：楼群 7 排由近及远，越远越密越小越糊、向灭点收，楼缝点街灯。
 * 全程确定性随机种子，重建不闪。
 */
private const val CW = 1000f
private const val CH = 500f
private const val VPX = 540f
private const val VPY = 286f
private val HAZE = Color(0xFF4A6A90)
private const val GLOW_SCALE = 0.25f

private fun lf(a: Float, b: Float, t: Float): Float = a + (b - a) * t

internal class SceneLayers(val city: ImageBitmap, val glow: ImageBitmap)

private class GRect(val x: Float, val y: Float, val w: Float, val h: Float, val c: Color)
private class GRadial(val cx: Float, val cy: Float, val r: Float, val c: Color)

internal fun buildScene(wPx: Int, hPx: Int, density: Density): SceneLayers {
    val city = ImageBitmap(wPx, hPx)
    val rects = ArrayList<GRect>(20000)
    val radials = ArrayList<GRadial>(64)
    CanvasDrawScope().draw(density, LayoutDirection.Ltr, GCanvas(city), Size(wPx.toFloat(), hPx.toFloat())) {
        renderCity(rects, radials)
    }
    val gw = (wPx * GLOW_SCALE).toInt().coerceAtLeast(1)
    val gh = (hPx * GLOW_SCALE).toInt().coerceAtLeast(1)
    val glow = ImageBitmap(gw, gh)
    CanvasDrawScope().draw(density, LayoutDirection.Ltr, GCanvas(glow), Size(gw.toFloat(), gh.toFloat())) {
        renderGlow(rects, radials)
    }
    return SceneLayers(city, glow)
}

private fun DrawScope.renderGlow(rects: List<GRect>, radials: List<GRadial>) {
    val sx = size.width / CW
    val sy = size.height / CH
    for (g in radials) {
        val c = Offset(g.cx * sx, g.cy * sy)
        drawCircle(Brush.radialGradient(listOf(g.c, Color.Transparent), c, (g.r * sx).coerceAtLeast(.5f)), g.r * sx, c)
    }
    for (r in rects) drawRect(r.c, Offset(r.x * sx, r.y * sy), Size(r.w * sx, r.h * sy))
}

private fun DrawScope.renderCity(rects: ArrayList<GRect>, radials: ArrayList<GRadial>) {
    val sx = size.width / CW
    val sy = size.height / CH
    fun p(x: Float, y: Float) = Offset(x * sx, y * sy)

    drawRect(Brush.verticalGradient(listOf(Color(0xFF03060E), Color(0xFF0B1F39), Color(0xFF1E4468), Color(0xFF3C6C92))))
    val rng = Random(0x12026A17)
    repeat(150) {
        val yy = rng.nextFloat()
        drawCircle(Color(0xFFDCEBFF).copy(alpha = (1f - yy) * rng.nextFloat() * .5f),
            (.4f + rng.nextFloat() * .6f) * sx, Offset(rng.nextFloat() * size.width, yy * VPY * sy))
    }
    radials.add(GRadial(VPX, VPY, 470f, Color(0xFFE7B27A).copy(alpha = .5f)))
    val hz = p(VPX, VPY)
    drawCircle(Brush.radialGradient(
        listOf(Color(0xFF6E5A46).copy(alpha = .5f), Color(0xFF324761).copy(alpha = .45f), Color.Transparent), hz, 470f * sx), 470f * sx, hz)

    val rows = 7
    for (i in 0 until rows) {
        val d = i.toFloat() / (rows - 1)
        val base = lf(470f, 300f, d)
        val scale = lf(1f, 0.42f, d)
        val fog = lf(0.05f, 0.86f, d * d)
        val winBright = lf(0.72f, 0.12f, d)
        val row = Random(0x5EED + i)
        var x = -40f
        val gap = lf(3f, 1.5f, d)
        while (x < CW + 40f) {
            val tall = if (row.nextFloat() < lf(0.3f, 0.55f, d)) lf(20f, 60f, d) else 0f
            val w = lf(30f, 12f, d) * (0.7f + row.nextFloat() * 0.85f)
            val top = base - lf(120f, 60f, d) * (0.6f + row.nextFloat() * 0.95f) - tall
            box(x, w, top.coerceAtLeast(70f), base, fog, winBright, scale, row, sx, sy, rects)
            if (row.nextFloat() > .4f) {
                val a = lf(0.9f, 0.22f, d)
                radials.add(GRadial(x + w + gap * .5f, base, lf(6f, 2f, d), Color(0xFFFFC477).copy(alpha = a)))
            }
            x += w + gap + row.nextFloat() * lf(6f, 2f, d)
        }
        drawRect(Brush.verticalGradient(listOf(Color.Transparent, HAZE.copy(alpha = .28f * fog + .04f)), (base - 150f) * sy, base * sy),
            Offset(0f, (base - 150f) * sy), Size(size.width, 150f * sy))
    }

    landmarks(sx, sy, rects)
    rooftop(sx, sy)

    val grain = Random(77)
    repeat(2400) {
        drawRect(Color(0xFFFFFFFF).copy(alpha = grain.nextFloat() * .035f),
            Offset(grain.nextFloat() * size.width, grain.nextFloat() * size.height), Size(1f, 1f))
    }
}

private fun DrawScope.box(
    x: Float, w: Float, top: Float, base: Float, fog: Float, winBright: Float, scale: Float,
    row: Random, sx: Float, sy: Float, rects: ArrayList<GRect>
) {
    fun p(px: Float, py: Float) = Offset(px * sx, py * sy)
    val h = base - top
    val faceTop = lerp(Color(0xFF122132), HAZE, fog)
    val faceBot = lerp(Color(0xFF243C54), HAZE, fog)
    val side = lerp(Color(0xFF060D18), HAZE, fog * .82f)
    drawRect(Brush.verticalGradient(listOf(faceTop, faceBot), top * sy, base * sy), p(x, top), Size(w * sx, h * sy))
    val cx = x + w / 2f
    val dir = if (cx < VPX) 1f else -1f
    val sw = (w * .38f * (abs(cx - VPX) / CW * 2.3f)).coerceIn(0f, w * .55f)
    if (sw > .5f) {
        val ne = if (dir > 0) x + w else x
        val fe = ne + sw * dir
        val t = (sw / (abs(cx - VPX) + 55f)).coerceAtMost(.34f)
        drawPath(Path().apply {
            moveTo(ne * sx, top * sy); lineTo(fe * sx, (top + (VPY - top) * t) * sy)
            lineTo(fe * sx, (base + (VPY - base) * t) * sy); lineTo(ne * sx, base * sy); close()
        }, side)
    }
    drawLine(lerp(Color(0xFF6C92B8), HAZE, fog).copy(alpha = .85f), p(x, top), p(x + w, top), 1f * sy)
    val cw = (w / (2f + row.nextFloat() * 2f)).coerceAtMost(4.2f * scale + 1.2f)
    val chh = cw * 1.6f
    val cols = ((w - 1.5f) / cw).toInt()
    val rown = ((h - 2f) / chh).toInt()
    val warmBias = if (row.nextFloat() > .5f) .82f else .4f
    for (c in 0 until cols) for (r in 0 until rown) {
        if (row.nextFloat() > .44f) {
            val wc = if (row.nextFloat() < warmBias) Color(0xFFFFDDA0) else Color(0xFFA9DCF3)
            val a = winBright * (.35f + row.nextFloat() * .65f)
            val wx = x + 1f + c * cw
            val wy = top + 2.5f + r * chh
            val ww = (cw * .5f).coerceAtLeast(.7f)
            val wh = (chh * .5f).coerceAtLeast(.8f)
            drawRect(wc.copy(alpha = a), p(wx, wy), Size(ww * sx, wh * sy))
            if (a > .42f) rects.add(GRect(wx - .3f, wy - .3f, ww + .6f, wh + .6f, wc.copy(alpha = a * .85f)))
        }
    }
    if (h > 110f && row.nextFloat() > .55f) {
        val ax = x + w * (.3f + row.nextFloat() * .4f)
        drawLine(Color(0xFF0A1622), p(ax, top), p(ax, top - (10f + row.nextFloat() * 20f)), 1f * sx)
        drawCircle(Color(0xFFFF6B5A).copy(alpha = .8f), 1f * sx, p(ax, top - 10f))
    }
}

private fun DrawScope.landmarks(sx: Float, sy: Float, rects: ArrayList<GRect>) {
    fun p(x: Float, y: Float) = Offset(x * sx, y * sy)

    drawRect(Brush.verticalGradient(listOf(Color(0xFF123049), Color(0xFF0A1E33)), 150f * sy, 420f * sy), p(96f, 150f), Size(36f * sx, 270f * sy))
    drawRect(Color(0xFF050E19), p(132f, 156f), Size(12f * sx, 264f * sy))
    for (i in 0 until 26) {
        val a = .3f + (i % 5) * .07f
        drawRect(Color(0xFF66C8F2).copy(alpha = a), p(99f, 158f + i * 10f), Size(30f * sx, 3.6f * sy))
        rects.add(GRect(99f, 157f + i * 10f, 30f, 5f, Color(0xFF66C8F2).copy(alpha = a * .8f)))
    }
    drawLine(Color(0xFF8FD4F5).copy(alpha = .6f), p(96f, 150f), p(96f, 420f), 1f * sx)

    drawPath(Path().apply {
        moveTo(300f * sx, 420f * sy); lineTo(300f * sx, 150f * sy); lineTo(330f * sx, 104f * sy)
        lineTo(372f * sx, 104f * sy); lineTo(402f * sx, 150f * sy); lineTo(402f * sx, 420f * sy); close()
    }, Color(0xFF182E46))
    drawPath(Path().apply {
        moveTo(351f * sx, 104f * sy); lineTo(372f * sx, 104f * sy); lineTo(402f * sx, 150f * sy)
        lineTo(402f * sx, 420f * sy); lineTo(351f * sx, 420f * sy); close()
    }, Color(0xFF0B1E33))
    for (r in 0 until 32) drawLine(Color(0xFF6FA6C8).copy(alpha = .15f), p(302f, 150f + r * 8.4f), p(400f, 150f + r * 8.4f), .8f * sx)
    val tr = Random(0xC0FFEE)
    for (r in 0 until 30) for (c in 0 until 8) {
        if (tr.nextFloat() > .4f) {
            val a = .3f + tr.nextFloat() * .5f
            val wx = 304f + c * 12f
            val wy = 156f + r * 8.6f
            drawRect(Color(0xFFFFD79B).copy(alpha = a), p(wx, wy), Size(4.6f * sx, 3.4f * sy))
            if (a > .5f) rects.add(GRect(wx - .3f, wy - .3f, 5.6f, 4.6f, Color(0xFFFFD79B).copy(alpha = a * .75f)))
        }
    }
    drawLine(Color(0xFF9FC4DC), p(351f, 104f), p(351f, 52f), 1.3f * sx, StrokeCap.Round)

    drawRect(Brush.verticalGradient(listOf(Color(0xFF12283C), Color(0xFF081827)), 196f * sy, 452f * sy), p(800f, 196f), Size(160f * sx, 256f * sy))
    drawRect(Color(0xFF05101D), p(800f, 196f), Size(28f * sx, 256f * sy))
    drawRect(Color(0xFF35566F), p(832f, 190f), Size(116f * sx, 152f * sy))
    drawRect(Color(0xFF040D18), p(836f, 194f), Size(108f * sx, 144f * sy))
    for (i in 0 until 9) drawLine(Color(0xFF4E7DA0).copy(alpha = .16f), p(838f, 356f + i * 10f), p(952f, 356f + i * 10f), 1f * sx)
}

private fun DrawScope.rooftop(sx: Float, sy: Float) {
    fun p(x: Float, y: Float) = Offset(x * sx, y * sy)
    drawRect(Brush.verticalGradient(listOf(Color.Transparent, Color(0xFF223C58).copy(alpha = .5f)), 356f * sy, 456f * sy),
        Offset(0f, 356f * sy), Size(size.width, 100f * sy))
    drawPath(Path().apply {
        moveTo(0f, 430f * sy); lineTo(320f * sx, 440f * sy); lineTo(720f * sx, 500f * sy); lineTo(0f, 500f * sy); close()
    }, Color(0xFF02060D))
    drawLine(Color(0xFF5A7C9C).copy(alpha = .5f), p(0f, 430f), p(320f, 440f), 1.6f * sy)
    for (i in 0 until 7) drawLine(Color(0xFF14273B).copy(alpha = .8f), p(28f + i * 46f, 430f), p(28f + i * 46f, 456f), 1.4f * sx)
    drawCircle(Color(0xFF01040A), 12f * sy, p(214f, 360f))
    drawPath(Path().apply {
        moveTo(200f * sx, 375f * sy); quadraticBezierTo(215f * sx, 366f * sy, 230f * sx, 376f * sy)
        lineTo(241f * sx, 424f * sy); quadraticBezierTo(214f * sx, 440f * sy, 189f * sx, 422f * sy); close()
    }, Color(0xFF01040A))
    drawLine(Color(0xFF6E94B5).copy(alpha = .55f), p(201f, 375f), p(195f, 406f), 1.6f * sx, StrokeCap.Round)
    drawLine(Color(0xFF01040A), p(206f, 428f), p(224f, 466f), 9f * sy, StrokeCap.Round)
    drawLine(Color(0xFF01040A), p(227f, 430f), p(249f, 464f), 8f * sy, StrokeCap.Round)
    drawCircle(Color(0xFFFFB25E).copy(alpha = .18f), 30f * sy, p(290f, 424f))
    drawCircle(Color(0xFFFFCB85).copy(alpha = .55f), 9f * sy, p(290f, 424f))
    drawCircle(Color(0xFFFFEAC2), 3.6f * sy, p(290f, 424f))
}