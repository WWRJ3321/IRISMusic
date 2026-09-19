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

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin

/**
 * 虚拟环绕（V2.2 · 多维听感）：立体声声场展宽，增强左右分离度与空间感。
 *
 * 原理（M/S 中侧处理，非 3D 旋转）：
 * 立体声可分解为 中间分量 M=(L+R)/2 与 侧边分量 S=(L-R)/2。
 * 声音的"立体感"几乎全部来自 S——人耳对左右差异的感知。
 * 提升 S/M 比例 = 声场变宽，且是静态展宽，不产生旋转/移动感。
 *
 * 信号链（每帧，仅立体声）：
 * 1. S = (L-R)/2
 * 2. LPF @ 200Hz 提取侧边中的低频部分 S_lp（低频侧边=房间隆隆声/相位差，
 *    增强它会发虚——低音必须保持居中）
 * 3. 高频侧边 S_hf = S - S_lp
 * 4. 增量注入：L += w·S_hf, R -= w·S_hf（w = 强度映射的扩展量）
 *    增量式设计：强度 0 时输出与输入逐位相同
 *
 * 单声道输入直接直通（无侧边可增强）。
 */
@OptIn(UnstableApi::class)
object VirtualSurround : BaseAudioProcessor() {

    const val KEY_ENABLED = "virtual_surround_enabled"
    /** 强度 0-100 → 侧边高频扩展量 0%…150%，默认 40 */
    const val KEY_STRENGTH = "virtual_surround_strength"

    @Volatile var enabled: Boolean = false
        private set
    @Volatile var strength: Int = 40
        private set

    fun init(prefs: android.content.SharedPreferences) {
        enabled = prefs.getBoolean(KEY_ENABLED, false)
        strength = prefs.getInt(KEY_STRENGTH, 40).coerceIn(0, 100)
    }

    fun setEnabled(v: Boolean) { enabled = v }
    fun setStrength(v: Int) { strength = v.coerceIn(0, 100) }

    // ==================== DSP 状态（仅音频线程访问） ====================

    // LPF @200Hz 双二阶（巴特沃斯 Q=1/√2），作用于侧边信号
    private var lpB0 = 0f; private var lpB1 = 0f; private var lpB2 = 0f
    private var lpA1 = 0f; private var lpA2 = 0f
    private var lpX1 = 0f; private var lpX2 = 0f; private var lpY1 = 0f; private var lpY2 = 0f

    private var designedRate = 0

    // ==================== AudioProcessor ====================

    override fun onConfigure(inputAudioFormat: AudioFormat): AudioFormat {
        // 始终激活：运行时开关在 queueInput 内直通
        val e = inputAudioFormat.encoding
        if (e != C.ENCODING_PCM_16BIT && e != C.ENCODING_PCM_FLOAT) return AudioFormat.NOT_SET
        if (inputAudioFormat.channelCount < 1) return AudioFormat.NOT_SET
        return inputAudioFormat
    }

    override fun onFlush() {
        lpX1 = 0f; lpX2 = 0f; lpY1 = 0f; lpY2 = 0f
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val remaining = inputBuffer.remaining()
        if (remaining == 0) return
        val format = inputAudioFormat
        val channels = format.channelCount
        val is16 = format.encoding == C.ENCODING_PCM_16BIT
        val bytesPerFrame = if (is16) channels * 2 else channels * 4

        // 先整块拷贝，再原位改写（同 VirtualBass：结构上不可能丢数据）
        val out = replaceOutputBuffer(remaining)
        out.put(inputBuffer)
        out.flip()

        // 关闭或非立体声：纯直通（侧边增强只对 L/R 有意义）
        if (!enabled || channels < 2) return
        if (designedRate != format.sampleRate) designFilters(format.sampleRate)
        // 扩展量：0-100 → 0%…100%（1+w 倍侧边高频）。
        // 旧版上限 150%：过宽的侧边让 L/R 反相成分暴增，声像发虚、细节互相抵消；
        // 100% 已是可听上限（侧边翻倍），再高主要是失真而非空间感
        val w = (strength / 100f).coerceIn(0f, 1f)

        // 只处理前两个声道（L/R），多出的声道原样保留
        var i = 0
        while (i + bytesPerFrame <= remaining) {
            try {
                if (is16) {
                    val l = out.getShort(i) / 32768f
                    val r = out.getShort(i + 2) / 32768f
                    val s = (l - r) * 0.5f
                    // LPF 提取侧边低频，S_hf = S - S_lp
                    val sLp = lpB0 * s + lpB1 * lpX1 + lpB2 * lpX2 - lpA1 * lpY1 - lpA2 * lpY2
                    lpX2 = lpX1; lpX1 = s; lpY2 = lpY1; lpY1 = sLp
                    val add = (s - sLp) * w
                    if (add.isFinite() && add != 0f) {
                        // 软限幅：超界样本平滑饱和到 ±1（硬 coerceIn = 硬削波 = 撕裂）
                        out.putShort(i, softClipF(l + add).toPcm16())
                        out.putShort(i + 2, softClipF(r - add).toPcm16())
                    }
                } else {
                    val l = out.getFloat(i)
                    val r = out.getFloat(i + 4)
                    val s = (l - r) * 0.5f
                    val sLp = lpB0 * s + lpB1 * lpX1 + lpB2 * lpX2 - lpA1 * lpY1 - lpA2 * lpY2
                    lpX2 = lpX1; lpX1 = s; lpY2 = lpY1; lpY1 = sLp
                    val add = (s - sLp) * w
                    if (add.isFinite() && add != 0f) {
                        out.putFloat(i, softClipF(l + add))
                        out.putFloat(i + 4, softClipF(r - add))
                    }
                }
            } catch (_: Exception) {
                // 单帧异常：样本保持原样，继续下一帧
            }
            i += bytesPerFrame
        }
    }

    /** 软限幅：|x| ≤ 0.95 直通，超过后指数饱和渐近 ±1（C1 连续，无方波边沿）。
     *  v2.2.22：knee 0.9→0.95。展宽是把 S 叠加回 L/R，峰值经常越过 0.9，
     *  旧 knee 会把大量中高频峰值压成轻微平顶（细节损耗，M 分量 -2~3%） */
    private fun softClipF(x: Float): Float {
        val knee = 0.95f
        val head = 1f - knee
        return when {
            x > knee -> 1f - head * exp(-(x - knee) / head)
            x < -knee -> -(1f - head * exp(-(-x - knee) / head))
            else -> x
        }
    }

    private fun Float.toPcm16(): Short =
        ((this.coerceIn(-1f, 1f)) * 32767f).toInt().toShort()

    /** LPF @200Hz 系数设计（RBJ cookbook，巴特沃斯 Q=1/√2）。
     * 注意：LPF 分子是 (1−c)。曾误写 (1+c)：DC 增益 ≈ (1+c)/(1−c)≈13700 倍，
     * 侧边信号被放大成满幅——开环绕时满屏撕裂声的根源。 */
    private fun designFilters(sampleRate: Int) {
        designedRate = sampleRate
        if (sampleRate <= 0) return
        val rate = sampleRate.toFloat()
        val w0 = 2f * PI.toFloat() * 200f / rate
        val c = cos(w0); val a = sin(w0) / (2f * 0.70710678f); val a0 = 1f + a
        lpB0 = ((1f - c) / 2f) / a0
        lpB1 = (1f - c) / a0
        lpB2 = ((1f - c) / 2f) / a0
        lpA1 = (-2f * c) / a0
        lpA2 = (1f - a) / a0
    }
}