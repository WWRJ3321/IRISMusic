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

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import com.iris.music.audio.SpectrumAnalyzer

/** 倾斜对升幅的最大增益：tilt=±1 时一侧 +85%、另一侧 -85%。 */
private const val TILT_GAIN = 0.85f

/**
 * 实时频谱条：从 [SpectrumAnalyzer] 拉取 FFT 结果并绘制成对称柱状图。
 *
 * 性能要点——不要把频谱数据放进 State：
 * 音频线程每 ~23ms 产出一帧，若用 StateFlow 驱动会导致每秒 40+ 次全量重组。
 * 这里改用 [withFrameNanos] 按屏幕刷新率主动拉取，写入非 State 的 FloatArray，
 * 只靠一个自增的 frameTick 触发 Canvas 重绘，重组次数为零。
 *
 * 视觉处理（决定"廉价抖动"还是"有质感的呼吸"）：
 * - 非对称平滑：上升快、下落慢，模拟真实 VU 表的惯性
 * - 峰值保持：峰帽悬停后缓慢下坠
 * - 中央镜像排布：低频居中、高频向两侧展开，比左低右高更对称好看
 *
 * 实验性 · 倾斜响应：读取重力传感器的横滚分量，向哪侧倾斜，
 * 那一侧的柱子升幅加大、另一侧抑制，频谱像朝倾斜方向"积聚"。
 * 跟随可视化开关一同显示，无独立入口；传感器不可用时静默退化为普通频谱。
 */
@Composable
fun SpectrumBars(
    accent: Color,
    /** 是否正在播放：暂停时柱子平滑归零而非突然消失 */
    playing: Boolean,
    /** 实验性：随手机倾斜变化。关闭时退化为普通频谱，且不读取传感器。 */
    tiltEnabled: Boolean = false,
    modifier: Modifier = Modifier,
    height: Dp = 46.dp
) {
    val bands = SpectrumAnalyzer.BANDS

    // 平滑后的显示值与峰帽位置，均为普通数组（非 State），只在绘制阶段读取
    val level = remember { FloatArray(bands) }
    val peak = remember { FloatArray(bands) }
    val peakVel = remember { FloatArray(bands) }

    // 唯一的 State：帧计数器，自增用于触发重绘（不触发重组内容 lambda 之外的部分）
    var frameTick by remember { mutableStateOf(0) }

    // —— 实验性 · 倾斜 ——
    // 原始横滚加速度计分量（非 State，由传感器回调写、帧循环读）与平滑后的 tilt。
    val context = LocalContext.current
    val rawTilt = remember { FloatArray(1) }   // 已归一化并限幅到 [-1,1]，左倾为正
    val tilt = remember { FloatArray(1) }      // 低通平滑后的显示用 tilt
    DisposableEffect(tiltEnabled) {
        val sm = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
        val acc = sm?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        // 关闭时不注册传感器，省功耗；tilt 平滑自然停在 0，退化为普通频谱。
        if (!tiltEnabled || acc == null) {
            rawTilt[0] = 0f
            return@DisposableEffect onDispose { }
        }
        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                if (event.sensor.type != Sensor.TYPE_ACCELEROMETER) return
                // 设备坐标 x 向右。实测向左倾时 ax 为正，直接除以 g 即"左倾为正"。
                val ax = event.values[0]
                rawTilt[0] = (ax / SensorManager.GRAVITY_EARTH).coerceIn(-1f, 1f)
            }
            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
        }
        if (acc != null) sm?.registerListener(listener, acc, SensorManager.SENSOR_DELAY_GAME)
        onDispose { sm?.unregisterListener(listener) }
    }

    // 整体淡入淡出：开关切换与暂停时不硬切
    val alpha by animateFloatAsState(
        targetValue = if (playing) 1f else 0.35f,
        animationSpec = tween(400),
        label = "spectrumAlpha"
    )

    // playing 必须用 rememberUpdatedState 包一层：
    // 下面的 LaunchedEffect(Unit) 只启动一次，直接读参数会把首次组合时的值
    // 永久捕获进闭包——在未播放时开启可视化就会锁死为 false，柱子永远不动。
    val playingState = rememberUpdatedState(playing)

    LaunchedEffect(Unit) {
        var lastNanos = 0L
        while (true) {
            withFrameNanos { now ->
                // 归一化到 60fps 基准，保证高刷屏上平滑速度一致
                val dt = if (lastNanos == 0L) 1f
                         else ((now - lastNanos) / 16_666_667f).coerceIn(0.2f, 3f)
                lastNanos = now

                // tilt 低通平滑：更快跟手，倾斜响应更激进连贯
                tilt[0] += (rawTilt[0] - tilt[0]) * (0.32f * dt).coerceAtMost(1f)

                val isPlaying = playingState.value
                val src = SpectrumAnalyzer.snapshot()
                for (i in 0 until bands) {
                    val target = if (isPlaying) src.getOrElse(i) { 0f } else 0f
                    val cur = level[i]
                    // 非对称平滑：attack 激进地冲上去抓瞬态，release 缓慢回落显出波浪
                    val k = if (target > cur) 0.65f else 0.12f
                    level[i] = cur + (target - cur) * (k * dt).coerceAtMost(1f)

                    // 峰帽：被顶起时瞬时跟随，之后重力加速下坠
                    if (level[i] >= peak[i]) {
                        peak[i] = level[i]
                        peakVel[i] = 0f
                    } else {
                        peakVel[i] += 0.0022f * dt
                        peak[i] = (peak[i] - peakVel[i] * dt).coerceAtLeast(level[i])
                    }
                }
                frameTick++
            }
        }
    }

    Canvas(
        modifier
            .fillMaxWidth()
            .height(height)
    ) {
        // 读一次 frameTick 建立重绘依赖；不读的话数组更新不会触发 draw
        @Suppress("UNUSED_EXPRESSION") frameTick

        val w = size.width
        val h = size.height
        if (w <= 0f || h <= 0f) return@Canvas

        val t = tilt[0]

        // 中央镜像：低频在中间，高频向两侧铺开，视觉上更平衡
        val halfCount = bands
        val totalSlots = halfCount * 2
        val slotW = w / totalSlots
        val barW = slotW * 0.62f
        val gap = (slotW - barW) / 2f
        val radius = CornerRadius(barW / 2f)
        val minBar = barW * 0.9f

        fun drawBar(slot: Int, v: Float, p: Float) {
            val x = slot * slotW + gap
            // 该柱在画布上的水平位置 [-1,1]：左=-1，右=+1。
            // 倾斜增益：与倾斜同侧(pos 与 t 同号，这里用 -t 因为左倾 t>0 想让左侧放大)放大，异侧抑制。
            val center = x + barW / 2f
            val pos = ((center / w) * 2f - 1f).coerceIn(-1f, 1f)
            val gain = (1f + TILT_GAIN * t * (-pos)).coerceAtLeast(0.15f)

            val barH = (v * gain * h).coerceAtLeast(minBar)
            val y = h - barH

            // 柱体：底部主题色、顶部渐淡，避免大色块压迫感
            drawRoundRect(
                brush = Brush.verticalGradient(
                    colors = listOf(
                        accent.copy(alpha = 0.55f * alpha),
                        accent.copy(alpha = 0.95f * alpha)
                    ),
                    startY = y,
                    endY = h
                ),
                topLeft = Offset(x, y),
                size = Size(barW, barH),
                cornerRadius = radius
            )

            // 峰帽：细亮条，比柱体更高更亮，给出打击感
            if (p > 0.04f) {
                val capH = barW * 0.4f
                val capY = (h - p * gain * h - capH).coerceAtLeast(0f)
                drawRoundRect(
                    color = accent.copy(alpha = 0.7f * alpha),
                    topLeft = Offset(x, capY),
                    size = Size(barW, capH),
                    cornerRadius = CornerRadius(capH / 2f)
                )
            }
        }

        for (i in 0 until halfCount) {
            val v = level[i]
            val p = peak[i]
            // 右半：从中心往右
            drawBar(halfCount + i, v, p)
            // 左半：镜像
            drawBar(halfCount - 1 - i, v, p)
        }
    }
}