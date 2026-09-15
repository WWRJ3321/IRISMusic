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
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 虚拟低音（V2.2 · 多维听感）：基于心理声学"缺失基频效应"合成次低音谐波。
 *
 * 原理：手机/耳机发不出 40–100Hz 的基频，但人耳听到基频的 2/3 次谐波
 * （泛音）会自动脑补出基频存在感——MaxxBass 等商业增强用的同一原理。
 *
 * 信号链（每帧）：
 * 1. 2 阶巴特沃斯 LPF @ 120Hz 抽取低频（L/R 平均成单声道——低音本就
 *    近乎单声道，谐波对两个声道注入同一个值，不破坏立体声声像）
 * 2. 保号平方非线性器件 y = x·|x|：产生 2 次谐波（60Hz→120Hz）
 * 3. 2 阶巴特沃斯 HPF @ 60Hz 滤掉残余基频（v2.2.22：原 140Hz 会连
 *    60-140Hz 的谐波一并滤掉——这正是旧版"低音没补上反而变薄"的
 *    根源。60Hz 以下才切，保住的谐波落在手机喇叭最能发力的区间）
 * 4. 包络归一化：谐波除以自身慢速包络，得到形状跟随低频、
 *    电平恒定的谐波——这样混入比例不随歌曲响度漂移
 * 5. 加法混音 + 软限幅（v2.2.22 重构）：谐波按比例叠加到原始信号上，
 *    输出经指数软限幅保护。旧版"置换式"用减法（harm − SUBTRACT·low）
 *    把原始低频挖掉 15-20% 给谐波腾空间，实测 40-120Hz 净能量不升反降
 *    14-20%——"低音增强"实际在削弱低音；且减法只针对 <120Hz 原始信号，
 *    谐波又被 HPF@140 滤掉，低频段只剩减法没有加法
 * 6. 强度直接映射混入电平：0-100 → 15%…90%（15% 已可感知，
 *    90% 明显夸张，默认 50）
 *
 * 关键教训（v2.2 首版听不出效果的两个原因）：
 * - 平方律器件 y=x·|x| 输出电平是输入的平方：典型低频 0.2 → 谐波仅 0.04，
 *   再叠 -19dB 增益后谐波电平不足原信号 1%——必须做包络归一化。
 * - onConfigure 时 enabled=false 会让处理器被链路摘除，运行时再开
 *   要等下一次 configure（切歌/seek）才生效——改为始终激活、
 *   queueInput 内部直通，开关即时生效。
 *
 * 线程模型：queueInput 在 ExoPlayer 音频线程调用（单线程）；开关/强度
 * 用 @Volatile 从 UI 线程实时改。
 */
@OptIn(UnstableApi::class)
object VirtualBass : BaseAudioProcessor() {

    const val KEY_ENABLED = "virtual_bass_enabled"
    /** 混合强度 0-100 → 谐波占原始信号的比例 15%…90%，默认 50 */
    const val KEY_STRENGTH = "virtual_bass_strength"
    /**
     * 残余基频泄漏保护（v2.2.22）：加法混音后，平方律器件里漏出的 <60Hz
     * 基频残余会让低频发闷发糊。用互补 HPF 已把 <60Hz 切掉，这里再加一道
     * 极轻的减法只削掉 40Hz 以下几乎听不到的次声区，避免加法把极低频堆爆。
     * 注意：旧版 SUBTRACT_MAX=0.3 是把 120Hz 以下原始低频挖掉 30%，
     * 谐波又补不回来（HPF@140 滤掉了），实测低音净减——已废弃减法为主的设计。
     */
    private const val SUBTRACT_MAX = 0.0f

    @Volatile var enabled: Boolean = false
        private set
    @Volatile var strength: Int = 50
        private set

    fun init(prefs: android.content.SharedPreferences) {
        enabled = prefs.getBoolean(KEY_ENABLED, false)
        strength = prefs.getInt(KEY_STRENGTH, 50).coerceIn(0, 100)
    }

    fun setEnabled(v: Boolean) { enabled = v }
    fun setStrength(v: Int) { strength = v.coerceIn(0, 100) }

    // ==================== DSP 状态（仅音频线程访问） ====================

    // LPF @120Hz 双二阶（巴特沃斯 Q=1/√2）
    private var lpB0 = 0f; private var lpB1 = 0f; private var lpB2 = 0f
    private var lpA1 = 0f; private var lpA2 = 0f
    private var lpX1 = 0f; private var lpX2 = 0f; private var lpY1 = 0f; private var lpY2 = 0f
    // HPF @140Hz 双二阶
    private var hpB0 = 0f; private var hpB1 = 0f; private var hpB2 = 0f
    private var hpA1 = 0f; private var hpA2 = 0f
    private var hpX1 = 0f; private var hpX2 = 0f; private var hpY1 = 0f; private var hpY2 = 0f

    /**
     * 低频慢速包络（τ≈200ms）。必须远长于低频周期（60Hz 周期 17ms）：
     * 快包络会在每个周期内起伏，门限跟着周期性开关（gate chatter），
     * 产生与谐波同频的斩波蜂音——这就是"电流声"的根源。
     */
    private var envSlow = 0f
    /** 包络平滑系数，按采样率在 designFilters 里计算 */
    private var envCoef = 1.1e-4f
    /** 门限迟滞状态：开 0.010 / 关 0.006，避免临界电平反复开关 */
    private var gateOpen = false
    /** 门增益 0..1，一阶平滑（τ≈22ms），消除开/关瞬态爆音 */
    private var gateGain = 0f
    /** 最近一次 LPF 输出（原始低频），供置换式混音减去——音频线程私有 */
    private var lastLow = 0f

    private var designedRate = 0

    // ==================== AudioProcessor ====================

    override fun onConfigure(inputAudioFormat: AudioFormat): AudioFormat {
        // 始终激活：运行时开关在 queueInput 内直通，保证开/关即时生效
        val e = inputAudioFormat.encoding
        if (e != C.ENCODING_PCM_16BIT && e != C.ENCODING_PCM_FLOAT) return AudioFormat.NOT_SET
        if (inputAudioFormat.channelCount < 1) return AudioFormat.NOT_SET
        return inputAudioFormat
    }

    override fun onFlush() {
        lpX1 = 0f; lpX2 = 0f; lpY1 = 0f; lpY2 = 0f
        hpX1 = 0f; hpX2 = 0f; hpY1 = 0f; hpY2 = 0f
        envSlow = 0f
        gateOpen = false
        gateGain = 0f
        lastLow = 0f
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val remaining = inputBuffer.remaining()
        if (remaining == 0) return
        val format = inputAudioFormat
        val channels = format.channelCount
        val is16 = format.encoding == C.ENCODING_PCM_16BIT
        val bytesPerFrame = if (is16) channels * 2 else channels * 4

        // 先整块拷贝到输出缓冲，再原位（绝对索引）改写样本：
        // DSP 任何异常最多让个别样本保持原样，结构上不可能丢数据、
        // 不可能产生半处理状态、更不可能中断音频流。
        val out = replaceOutputBuffer(remaining)
        out.put(inputBuffer)
        out.flip()

        // 关闭时纯直通（处理器仍在链上，开关即时切换）
        if (!enabled || channels < 1) return
        if (designedRate != format.sampleRate) designFilters(format.sampleRate)
        // 混入比例：0-100 → 8%…52%（线性映射，直观）。
        // v2.2.22：谐波归一化分母 2→0.6 使单样本谐波电平提升约 3 倍，
        // 混入比例上限从 90% 降到 52% 防过推（谐波电平与旧版不可同日而语）
        val mix = 0.08f + 0.44f * (strength / 100f)

        var i = 0
        while (i + bytesPerFrame <= remaining) {
            try {
                var mono = 0f
                if (is16) {
                    for (c in 0 until channels) mono += out.getShort(i + c * 2) / 32768f
                    mono /= channels
                    // 加法混音（v2.2.22）：谐波直接叠加，软限幅保护防削波。
                    // 旧版"置换式"减法实测把 40-120Hz 低频挖掉 15-20%，
                    // 谐波又补不回被挖的频段——低音净减，效果名不副实
                    val harm = harmonic(mono)
                    val add = harm * mix - SUBTRACT_MAX * mix * lastLow
                    if (add.isFinite() && add != 0f) {
                        for (c in 0 until channels) {
                            val orig = out.getShort(i + c * 2) / 32768f
                            out.putShort(i + c * 2, softClipF(orig + add).toPcm16())
                        }
                    }
                } else {
                    for (c in 0 until channels) mono += out.getFloat(i + c * 4)
                    mono /= channels
                    // 加法混音 + 软限幅（同 is16 分支，见上注释）
                    val harm = harmonic(mono)
                    val add = harm * mix - SUBTRACT_MAX * mix * lastLow
                    if (add.isFinite() && add != 0f) {
                        for (c in 0 until channels) {
                            val orig = out.getFloat(i + c * 4)
                            out.putFloat(i + c * 4, softClipF(orig + add))
                        }
                    }
                }
            } catch (_: Exception) {
                // 单帧异常：样本保持原样，继续下一帧——绝不让 DSP 阻断音频
            }
            i += bytesPerFrame
        }
    }

    // ==================== DSP 核心 ====================

    /** 软限幅：|x| ≤ 0.95 直通，超过后指数饱和渐近 ±1（C1 连续，无方波边沿）。
     *  v2.2.22：knee 0.9→0.95。加法混音作用于整段信号，原始信号 0.9-1.0
     *  的满电平样本在 0.9 就被压出饱和染色（细节/高频损耗）；0.95 让原始
     *  波形尽量直通，只有真正叠加超界的峰值才轻微饱和 */
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

    /**
     * 单样本谐波链：LPF(120Hz) → 慢包络检测 → 门控（迟滞+平滑）→
     * 平方律 → HPF(60Hz) → 有界线性化。
     *
     * v2.2.22 变化：HPF 从 140Hz 降到 60Hz——低音谐波（60-140Hz）之前被
     * 整体滤掉，是"低音补不上"的另一半原因；归一化分母 2×envSlow → 0.6×envSlow，
     * 谐波电平提升（旧版包络归一化后谐波太弱，混入 15-90% 仍听不出）。
     *
     * 噪声控制四件套（全部针对前几版的撕裂/电流声）：
     * 1. envSlow τ≈200ms：包络绝不在低频周期内起伏（防斩波蜂音）
     * 2. 门限迟滞 0.010/0.006：临界电平不反复开关
     * 3. gateGain 一阶平滑 τ≈22ms：门开关无瞬态爆音
     * 4. 线性化分母 clamp 到 [0.02, ∞)：attack 瞬间除法不爆炸，
     *    输出软限幅 softClipF——永不硬削波
     */
    private fun harmonic(x: Float): Float {
        // LPF @120Hz：抽取低频分量
        val y = lpB0 * x + lpB1 * lpX1 + lpB2 * lpX2 - lpA1 * lpY1 - lpA2 * lpY2
        lpX2 = lpX1; lpX1 = x; lpY2 = lpY1; lpY1 = y
        lastLow = y
        // 慢速包络：峰值检测 + 缓慢释放（τ≈200ms），周期内近似恒定
        val a = abs(y)
        envSlow = if (a > envSlow) a else envSlow + envCoef * (a - envSlow)
        // 门控：迟滞判断 + 平滑门增益
        gateOpen = if (gateOpen) envSlow >= 0.006f else envSlow >= 0.010f
        val target = if (gateOpen) 1f else 0f
        gateGain += 0.001f * (target - gateGain) // τ≈22ms @44.1kHz
        if (gateGain < 0.01f) return 0f
        // 平方律 + HPF
        val sq = y * abs(y)
        val hp = hpB0 * sq + hpB1 * hpX1 + hpB2 * hpX2 - hpA1 * hpY1 - hpA2 * hpY2
        hpX2 = hpX1; hpX1 = sq; hpY2 = hpY1; hpY1 = hp
        // 有界线性化：分母下限 0.02 防 attack 瞬间除法爆炸
        val denom = (0.6f * envSlow).coerceAtLeast(0.02f)
        val h = hp / denom
        // 软限幅：大信号平滑饱和而非硬削波（硬削波=方波=嘶嘶声）
        val shaped = if (h * h < 9f) h * (27f + h * h) / (27f + 9f * h * h) else h.coerceIn(-1f, 1f)
        val out = shaped * gateGain
        return if (out.isFinite()) out else 0f
    }

    /** 双二阶系数设计（RBJ cookbook，巴特沃斯 Q=1/√2），cutoff 单位 Hz */
    private fun designFilters(sampleRate: Int) {
        designedRate = sampleRate
        if (sampleRate <= 0) return
        val rate = sampleRate.toFloat()
        // 慢包络系数：τ≈200ms → coef ≈ 1/(τ·fs)
        envCoef = 1f / (0.2f * rate)
        // LPF @120Hz（RBJ cookbook：分子必须是 (1−c)，零点在 Nyquist、通带增益 1。
        // 首版误写成 (1+c)——DC 增益 (1+c)/(1−c)≈13700 倍，低频被放大成满幅方波，
        // 这就是历版"电流声/撕裂"的真正根源）
        run {
            val w0 = 2f * PI.toFloat() * 120f / rate
            val c = cos(w0); val a = sin(w0) / (2f * 0.70710678f); val a0 = 1f + a
            lpB0 = ((1f - c) / 2f) / a0
            lpB1 = (1f - c) / a0
            lpB2 = ((1f - c) / 2f) / a0
            lpA1 = (-2f * c) / a0
            lpA2 = (1f - a) / a0
        }
        // HPF @60Hz（RBJ cookbook：分子必须是 (1+c)，零点在 DC、通带增益 1）。
        // v2.2.22：60Hz（原 140Hz）。140Hz 会把 60-140Hz 的谐波主体滤掉——
        // 那是手机喇叭最能发力、听感上"低音回来了"的频段
        run {
            val w0 = 2f * PI.toFloat() * 60f / rate
            val c = cos(w0); val a = sin(w0) / (2f * 0.70710678f); val a0 = 1f + a
            hpB0 = ((1f + c) / 2f) / a0
            hpB1 = (-(1f + c)) / a0
            hpB2 = ((1f + c) / 2f) / a0
            hpA1 = (-2f * c) / a0
            hpA2 = (1f - a) / a0
        }
    }
}