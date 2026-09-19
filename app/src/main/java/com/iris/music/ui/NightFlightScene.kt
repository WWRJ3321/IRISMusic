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

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.iris.music.data.LyricLine
import com.iris.music.data.LyricParser
import com.iris.music.player.PlayerUiState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/*
 * 横屏「天台夜航」沉浸场景。渲染层见 NightCity2.kt（离屏出 city + glow 两张位图）。
 * 这里负责合成：city → glow 放大叠加(bloom) → 暗角；再叠实时动态层
 * （障碍灯呼吸、流星、封面立牌、歌词、矢量按键）。
 */
private const val SC_W = 1000f
private const val SC_H = 500f

private object NfIcons {
    private fun icon(path: String): ImageVector = ImageVector.Builder(
        defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f
    ).apply { addPath(pathData = PathParser().parsePathString(path).toNodes(), fill = SolidColor(Color.Black)) }.build()
    val Play by lazy { icon("M8,5.14v13.72c0,0.79 0.87,1.27 1.54,0.84l10.79,-6.86c0.62,-0.39 0.62,-1.29 0,-1.69L9.54,4.29C8.87,3.87 8,4.34 8,5.14z") }
    val Pause by lazy { icon("M8,19c1.1,0 2,-0.9 2,-2V7c0,-1.1 -0.9,-2 -2,-2S6,5.9 6,7v10C6,18.1 6.9,19 8,19zM16,19c1.1,0 2,-0.9 2,-2V7c0,-1.1 -0.9,-2 -2,-2s-2,0.9 -2,2v10C14,18.1 14.9,19 16,19z") }
}

@Composable
fun NightFlightScene(state: PlayerUiState, onToggle: () -> Unit, onPrev: () -> Unit, onNext: () -> Unit) {
    val path = state.currentSong?.filePath
    val art by produceState<ImageBitmap?>(null, path) {
        value = null
        value = ArtworkLoader.load(path)?.asImageBitmap()
    }
    val lines by produceState<List<LyricLine>>(emptyList(), path, state.durationMs) {
        value = emptyList()
        if (!path.isNullOrBlank()) {
            try { value = withContext(Dispatchers.IO) { LyricParser.loadLyrics(path, state.durationMs) } }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { value = emptyList() }
        }
    }
    val index = lines.indexOfLast { it.timeMs <= state.positionMs }
    val meteor = remember { Animatable(1f) }
    LaunchedEffect(path, index) {
        if (index >= 0) { meteor.snapTo(0f); meteor.animateTo(1f, tween(2400)) }
    }
    val blink by rememberInfiniteTransition(label = "bl").animateFloat(
        0.25f, 1f, infiniteRepeatable(tween(1900, easing = LinearEasing), RepeatMode.Reverse), label = "blv"
    )
    var controls by remember { mutableStateOf(true) }
    LaunchedEffect(controls, state.isPlaying) {
        if (controls && state.isPlaying) { delay(5000); controls = false }
    }

    BoxWithConstraints(
        Modifier.fillMaxSize().background(Color(0xFF03060E)).clickable { controls = !controls }
    ) {
        val w = maxWidth
        val h = maxHeight
        val density = LocalDensity.current
        val wPx = with(density) { w.roundToPx() }
        val hPx = with(density) { h.roundToPx() }
        // 城市 + 发光两层，尺寸不变只渲染一次
        val layers = remember(wPx, hPx) {
            if (wPx <= 0 || hPx <= 0) null else buildScene(wPx, hPx, density)
        }

        Canvas(Modifier.fillMaxSize()) {
            val city = layers?.city
            if (city != null) {
                drawImage(city)
                // bloom：发光层用双线性放大回全屏，叠两次（近距锐 + 大半径柔）
                val glow = layers.glow
                drawImage(glow, alpha = 0.85f, filterQuality = FilterQuality.High)
                // 扩散层：略微放大 glow 制造大半径光晕（中心对齐）
                val ex = size.width * 0.06f
                val ey = size.height * 0.06f
                drawImage(
                    glow,
                    dstOffset = androidx.compose.ui.unit.IntOffset((-ex).toInt(), (-ey).toInt()),
                    dstSize = androidx.compose.ui.unit.IntSize((size.width + ex * 2).toInt(), (size.height + ey * 2).toInt()),
                    alpha = 0.5f, filterQuality = FilterQuality.High
                )
            }
            val sx = size.width / SC_W
            val sy = size.height / SC_H
            fun p(x: Float, y: Float) = Offset(x * sx, y * sy)

            // 障碍灯呼吸（地标塔顶）
            drawCircle(Color(0xFFFF6B5A).copy(alpha = blink * .16f), 9f * sx, p(351f, 52f))
            drawCircle(Color(0xFFFF6B5A).copy(alpha = blink), 2.2f * sx, p(351f, 52f))

            // 流星：头亮尾散，落向歌词区
            if (index >= 0 && meteor.value < 1f) {
                val t = meteor.value
                val head = p(640f - 360f * t, 36f + 96f * t)
                val tail = head + Offset(150f * sx, -70f * sy)
                drawLine(Brush.linearGradient(listOf(Color(0xFFE7F4FF).copy(alpha = 1f - t), Color(0xFFE7F4FF).copy(alpha = 0f)), head, tail), head, tail, 1.8f * sx, StrokeCap.Round)
                drawCircle(Color.White.copy(alpha = 1f - t), 2.4f * sx, head)
                drawCircle(Color(0xFFBFE0FF).copy(alpha = (1f - t) * .4f), 6f * sx, head)
            }

            // 暗角
            drawRect(Brush.radialGradient(
                listOf(Color.Transparent, Color.Transparent, Color(0xFF01040A).copy(alpha = .72f)),
                center = Offset(size.width * .48f, size.height * .46f), radius = size.width * .78f
            ))
        }

        // 封面立牌
        Box(
            Modifier.offset(x = w * .834f, y = h * .384f).width(w * .108f).height(h * .296f)
                .background(Color(0xFF050F1C)).border(1.dp, Color(0xFF5E87A8).copy(alpha = .5f)),
            contentAlignment = Alignment.Center
        ) {
            if (art != null) Image(art!!, "封面立牌", Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            else Text("IRIS", color = Color(0xFFB9D6EA), fontSize = 13.sp)
            Box(Modifier.fillMaxSize().background(Brush.linearGradient(listOf(Color.White.copy(alpha = .1f), Color.Transparent, Color.Transparent))))
        }

        // 歌词：上一行淡出留在上，当前行大字水平停留
        Column(Modifier.offset(x = w * .06f, y = h * .15f).width(w * .48f)) {
            if (index > 0) Text(lines[index - 1].text, color = Color(0xFF9DB8D2).copy(alpha = .3f), fontSize = 13.sp, maxLines = 2)
            Spacer(Modifier.height(4.dp))
            Text(
                if (index >= 0) lines[index].text else (state.currentSong?.title ?: "今夜，让城市陪你听歌"),
                color = Color(0xFFEAF3FC), fontSize = 23.sp, maxLines = 3
            )
        }

        if (controls) {
            Text("天台夜航 · 转回竖屏退出", Modifier.align(Alignment.TopStart).statusBarsPadding().padding(20.dp),
                color = Color(0xFFB4CBE2).copy(alpha = .8f), fontSize = 12.sp)
            Row(
                Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 26.dp),
                horizontalArrangement = Arrangement.spacedBy(30.dp), verticalAlignment = Alignment.CenterVertically
            ) {
                SceneCircle(onClick = onPrev, size = 52) {
                    Icon(NfIcons.Play, null, tint = Color(0xFFE6F0FA), modifier = Modifier.size(24.dp).graphicsLayer { scaleX = -1f })
                }
                SceneCircle(onClick = onToggle, size = 74, ring = true) {
                    Icon(if (state.isPlaying) NfIcons.Pause else NfIcons.Play, null, tint = Color.White, modifier = Modifier.size(34.dp))
                }
                SceneCircle(onClick = onNext, size = 52) {
                    Icon(NfIcons.Play, null, tint = Color(0xFFE6F0FA), modifier = Modifier.size(24.dp))
                }
            }
        }
    }
}

/** 场景圆形图标按钮：半透明玻璃圆底，主按钮带描边 */
@Composable
private fun SceneCircle(onClick: () -> Unit, size: Int, ring: Boolean = false, content: @Composable () -> Unit) {
    Box(
        Modifier.size(size.dp).background(Color(0x660A1424), CircleShape)
            .then(if (ring) Modifier.border(1.5.dp, Color(0x66AFCBDE), CircleShape) else Modifier)
            .clickable { Haptics.tap(); onClick() },
        contentAlignment = Alignment.Center
    ) { content() }
}