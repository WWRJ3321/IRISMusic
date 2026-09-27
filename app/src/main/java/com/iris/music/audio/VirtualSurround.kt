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
import kotlin.math.pow
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
 * 单声道处理（v3.11）：单声道源（channelCount=1，或双声道但 L≡R 的"双单声道"）
 * 侧边恒为 0，原逻辑等于假开。现检测到单声道后用**一阶全通滤波**对一路做相位旋转
 * （幅度恒为 1、只改相位 = 无梳状滤波凹陷），两路产生相位差 → 伪立体声 S ≠ 0，
 * 后续展宽逻辑随之生效。检测带时间常数：出现差异 30ms 内立刻按立体声处理，
 * 持续无差异 500ms 才判为单声道（防独奏居中段落误触发）。
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

    // 单声道检测 + 全通去相关状态（仅音频线程）
    /** 单声道置信度 0..1：d<eps 时慢速升到 1（500ms），出现差异时快速降回 0（30ms） */
    private var monoAmount = 0f
    private var monoKIn = 0f
    private var monoKOut = 0f
    /** 一阶全通 y = c·x[n] + x[n−1] − c·y[n−1]，c 越接近 1 相位旋转越强 */
    private var apC = 0.75f
    private var apX1 = 0f
    private var apY1 = 0f

    /** L/R 差异小于此值视作单声道候选（−80dB，解码器复制同一样本时为 bit-exact 0） */
    private const val MONO_EPS = 1e-4f

    // ==================== AudioProcessor ====================

    override fun onConfigure(inputAudioFormat: AudioFormat): AudioFormat {
        // 始终激活：运行时开关在 queueInput 内直通
        val e = inputAudioFormat.encoding
        if (e != C.ENCODING_PCM_16BIT && e != C.ENCODING_PCM_FLOAT) return AudioFormat.NOT_SET
        if (inputAudioFormat.channelCount < 1) return AudioFormat.NOT_SET
        // 单声道输入 → 输出协商为立体声（去相关伪立体声需要两路；
        // 关闭时 queueInput 里复制成 L=R，与原单声道听感一致）
        if (inputAudioFormat.channelCount == 1) {
            return AudioFormat(inputAudioFormat.sampleRate, 2, e)
        }
        return inputAudioFormat
    }

    override fun onFlush() {
        lpX1 = 0f; lpX2 = 0f; lpY1 = 0f; lpY2 = 0f
        monoAmount = 0f
        apX1 = 0f; apY1 = 0f
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val remaining = inputBuffer.remaining()
        if (remaining == 0) return
        val format = inputAudioFormat
        val inCh = format.channelCount
        val is16 = format.encoding == C.ENCODING_PCM_16BIT
        val smp = if (is16) 2 else 4
        val inFrameBytes = inCh * smp
        val frames = remaining / inFrameBytes
        if (frames <= 0) return
        val outCh = if (inCh == 1) 2 else inCh
        val outFrameBytes = outCh * smp

        val out = replaceOutputBuffer(frames * outFrameBytes)

        // 关闭 + 非单声道：整块字节直通，位精确（单声道关闭时仍需复制成 L=R 以匹配协商的立体声输出）
        if (!enabled && inCh >= 2) {
            out.put(inputBuffer)
            out.flip()
            return
        }

        if (enabled && designedRate != format.sampleRate) designFilters(format.sampleRate)
        // 扩展量：0-100 → 0%…100%（1+w 倍侧边高频）。
        // 旧版上限 150%：过宽的侧边让 L/R 反相成分暴增，声像发虚、细节互相抵消；
        // 100% 已是可听上限（侧边翻倍），再高主要是失真而非空间感
        val w = (strength / 100f).coerceIn(0f, 1f)
        // 单声道判定时间常数：出现立体声证据 30ms 内退出，持续单声道 500ms 才进入
        if (monoKIn == 0f) {
            monoKIn = 1f - 10f.pow(-1f / (0.500f * format.sampleRate))
            monoKOut = 1f - 10f.pow(-1f / (0.030f * format.sampleRate))
        }

        var i = 0   // 输入字节偏移
        var o = 0   // 输出字节偏移
        while (i + inFrameBytes <= remaining) {
            // 读输入样本（绝对读，不动输入 position；L=R 的单声道只读一次）
            val l: Float
            val r: Float
            if (is16) {
                l = inputBuffer.getShort(i).toInt() / 32768f
                r = if (inCh == 1) l else inputBuffer.getShort(i + smp).toInt() / 32768f
            } else {
                l = inputBuffer.getFloat(i)
                r = if (inCh == 1) l else inputBuffer.getFloat(i + smp)
            }

            var lo = l
            var ro = r

            // 全通状态（开关关闭也要推进，避免重开瞬间相位跳变）
            val ap = apC * r + apX1 - apC * apY1
            apX1 = r; apY1 = ap

            if (enabled) {
                // 单声道检测：|L−R| 持续 <eps → 置信度慢升（500ms），有差异 → 快降（30ms）
                val d = if (l - r < 0f) r - l else l - r
                val k = if (d < MONO_EPS) monoKIn else monoKOut
                val target = if (d < MONO_EPS) 1f else 0f
                monoAmount += k * (target - monoAmount)

                // 全通去相关：R' = (1−a)·R + a·allpass(R)，幅度近似不变、只产生相位差
                val a = monoAmount * w
                if (a > 1e-3f) ro = r * (1f - a) + ap * a

                // M/S 展宽（原逻辑），作用于可能已被去相关的 L/R
                val s = (lo - ro) * 0.5f
                val sLp = lpB0 * s + lpB1 * lpX1 + lpB2 * lpX2 - lpA1 * lpY1 - lpA2 * lpY2
                lpX2 = lpX1; lpX1 = s; lpY2 = lpY1; lpY1 = sLp
                val add = (s - sLp) * w
                if (add.isFinite() && add != 0f) {
                    lo = softClipF(lo + add)
                    ro = softClipF(ro - add)
                }
            }

            // 写 L/R（mono→stereo 复制；inCh≥2 时覆盖原两声道）
            if (is16) {
                out.putShort(o, lo.toPcm16())
                out.putShort(o + smp, ro.toPcm16())
            } else {
                out.putFloat(o, lo)
                out.putFloat(o + smp, ro)
            }
            // 3+ 声道：其余声道原样搬运（绝对读写，不依赖 backing array —— direct buffer 没有 array()）
            var ch = 2
            while (ch < inCh) {
                val si = i + ch * smp
                val di = o + ch * smp
                if (is16) out.putShort(di, inputBuffer.getShort(si))
                else out.putFloat(di, inputBuffer.getFloat(si))
                ch++
            }
            i += inFrameBytes
            o += outFrameBytes
        }
        // 消费完输入；输出按实际写入字节设 limit（capacity 可能大于写入量）
        inputBuffer.position(inputBuffer.limit())
        out.position(o)
        out.flip()
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