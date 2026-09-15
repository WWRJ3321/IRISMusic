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

package com.iris.music.audio

import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** 曲线锚点：freqHz 频率（Hz），gainMb 增益（毫贝，1dB = 100mB） */
data class EqAnchor(val freqHz: Int, val gainMb: Int)

/** 曲线面板的频率轴范围（人耳可闻范围） */
const val EQ_FREQ_MIN = 20f
const val EQ_FREQ_MAX = 20000f

/** 锚点数量上下限 */
const val EQ_MIN_ANCHORS = 2
const val EQ_MAX_ANCHORS = 12

/**
 * 内置增强预设。
 *
 * 替掉系统 Equalizer 自带的那张预设表（Normal / Rock / Jazz / Heavy Metal…）：
 * 那些曲线来自厂商固件，各机不同、面板上也画不出来，点下去只能靠耳朵猜发生了什么。
 * 这里改成三条自己定义的锚点曲线，选中后曲线面板立刻画出形状，所见即所得，
 * 而且可以在此基础上继续拖动微调（一拖就变回"自定义"）。
 *
 * 增益单位是毫贝，600 = +6dB。峰值压在 7dB 以内：更高会顶到多数设备的 ±15dB 量程边缘，
 * 也容易把总电平推爆产生削波。
 */
enum class EqBoost(val label: String, val anchors: List<EqAnchor>) {
    /** 低音增强：60Hz 抬起来，300Hz 以上回到平直，不糊中频人声 */
    BASS(
        "低音增强",
        listOf(
            EqAnchor(32, 650),
            EqAnchor(70, 600),
            EqAnchor(150, 350),
            EqAnchor(320, 80),
            EqAnchor(1000, 0),
            EqAnchor(16000, 0)
        )
    ),

    /** 中音增强：人声与主奏乐器所在的 800Hz~3kHz 抬起，两端略压以突出中频 */
    MID(
        "中音增强",
        listOf(
            EqAnchor(32, -150),
            EqAnchor(160, -60),
            EqAnchor(700, 350),
            EqAnchor(2000, 550),
            EqAnchor(4500, 250),
            EqAnchor(16000, -100)
        )
    ),

    /** 高音增强：5kHz 以上抬起，增加空气感与细节，低频不动 */
    TREBLE(
        "高音增强",
        listOf(
            EqAnchor(32, 0),
            EqAnchor(800, 0),
            EqAnchor(2500, 150),
            EqAnchor(6000, 500),
            EqAnchor(11000, 650),
            EqAnchor(16000, 600)
        )
    )
}

/**
 * 等响曲线补偿（近似 ISO 226 的低听感音量修正，即老功放上的 Loudness）。
 *
 * 人耳的灵敏度随频率和声压变化：小声听时低频和极高频衰减得比中频快得多，
 * 于是音乐听起来"薄、闷、没劲"。这条曲线把两端补回来、中频保持不动，
 * 让小音量下的音色平衡接近大音量时的听感。
 *
 * 数值是相对补偿量（mB），叠加在用户曲线之上，不写进用户曲线本身 ——
 * 关掉开关时曲线面板上画的还是用户自己那条。
 */
private val LOUDNESS_ANCHORS = listOf(
    // v2.2.19：整体削峰。旧表 20Hz+9dB 会在满电平母带上把 EQ 推过量程顶（顶格=失真），
    // 而且手机喇叭 20-40Hz 本就发不出声，补了也听不到。新表最大 +6dB（100Hz 附近，
    // 等响曲线在低听感下最需要的频段），高频峰值压到 +4dB 以内
    EqAnchor(20, 450),
    EqAnchor(32, 520),
    EqAnchor(60, 600),
    EqAnchor(120, 420),
    EqAnchor(250, 190),
    EqAnchor(500, 60),
    EqAnchor(1000, 0),
    EqAnchor(2500, 0),
    EqAnchor(4000, -60),
    EqAnchor(6000, 60),
    EqAnchor(10000, 240),
    EqAnchor(16000, 380),
    EqAnchor(20000, 420)
)

/** 补偿曲线只依赖常量，构造一次复用 */
private val loudnessCurve by lazy { EqCurveInterpolator(LOUDNESS_ANCHORS) }

/** 指定频率处的等响补偿量（mB），amount 为补偿比例 0..1（等响强度滑条） */
fun loudnessCompensationMb(freqHz: Int, amount: Float = 1f): Int =
    (loudnessCurve.gainAtFreq(freqHz.coerceAtLeast(1).toFloat()) * amount.coerceIn(0f, 1f)).roundToInt()

/**
 * 单调三次插值（Fritsch–Carlson），在 log10(频率) 轴上进行。
 * 相比普通 Catmull-Rom 不会在锚点之间过冲，避免出现用户没画出来的假峰。
 */
class EqCurveInterpolator(anchors: List<EqAnchor>) {

    private val xs: FloatArray
    private val ys: FloatArray
    private val m: FloatArray

    init {
        val sorted = anchors.sortedBy { it.freqHz }
        xs = FloatArray(sorted.size) { log10(sorted[it].freqHz.coerceAtLeast(1).toFloat()) }
        ys = FloatArray(sorted.size) { sorted[it].gainMb.toFloat() }
        m = FloatArray(sorted.size)

        val n = sorted.size
        if (n >= 2) {
            val d = FloatArray(n - 1)
            for (i in 0 until n - 1) {
                val dx = (xs[i + 1] - xs[i]).let { if (abs(it) < 1e-6f) 1e-6f else it }
                d[i] = (ys[i + 1] - ys[i]) / dx
            }
            m[0] = d[0]
            m[n - 1] = d[n - 2]
            for (i in 1 until n - 1) {
                // 极值点处切线归零，保证单调段之间平滑衔接
                m[i] = if (d[i - 1] * d[i] <= 0f) 0f else (d[i - 1] + d[i]) / 2f
            }
            // 限制切线幅度，抑制过冲
            for (i in 0 until n - 1) {
                if (d[i] == 0f) {
                    m[i] = 0f
                    m[i + 1] = 0f
                    continue
                }
                val a = m[i] / d[i]
                val b = m[i + 1] / d[i]
                val s = a * a + b * b
                if (s > 9f) {
                    val t = 3f / sqrt(s)
                    m[i] = t * a * d[i]
                    m[i + 1] = t * b * d[i]
                }
            }
        }
    }

    /** 按 log10(频率) 求增益（毫贝） */
    fun gainAtLogX(x: Float): Float {
        if (xs.isEmpty()) return 0f
        if (xs.size == 1) return ys[0]
        if (x <= xs.first()) return ys.first()
        if (x >= xs.last()) return ys.last()

        var i = 0
        while (i < xs.size - 2 && x > xs[i + 1]) i++

        val h = (xs[i + 1] - xs[i]).let { if (abs(it) < 1e-6f) 1e-6f else it }
        val t = ((x - xs[i]) / h).coerceIn(0f, 1f)
        val t2 = t * t
        val t3 = t2 * t

        val h00 = 2f * t3 - 3f * t2 + 1f
        val h10 = t3 - 2f * t2 + t
        val h01 = -2f * t3 + 3f * t2
        val h11 = t3 - t2

        return h00 * ys[i] + h10 * h * m[i] + h01 * ys[i + 1] + h11 * h * m[i + 1]
    }

    /** 按频率（Hz）求增益（毫贝） */
    fun gainAtFreq(freqHz: Float): Float = gainAtLogX(log10(freqHz.coerceAtLeast(1f)))
}