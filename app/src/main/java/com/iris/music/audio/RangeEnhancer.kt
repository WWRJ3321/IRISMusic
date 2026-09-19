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
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.pow

/**
 * 音频动态范围增强（V2.2 · 多维听感）：**非对称半扩展器 + 补偿增益**。
 *
 * v2.2.22 重写（第四版）：前三版都是"对称扩展"——轻段被压 5-6dB。
 * 实测轻段承载镲片/混响等细节（Copines 8k-16k 能量被压掉 27%），
 * 压轻段 = 压细节，人耳立刻听出"细节没了、发闷、没提升"。而且响段
 * +3dB 顶在满电平母带上触发 softclip 平顶，进一步吃高频。
 *
 * 这一版只做两件事：
 * 1. **响的段落抬**（最多 +1.2dB/强度档，斜率 k=0.3）——副歌/重拍更有冲击；
 * 2. **恒定补偿增益** makeup（+1.2dB×强度）——轻段几乎不压（−0.5dB 封顶），
 *    只被整体抬一点。结果：平均响度上升、响段比轻段抬得更多，
 *    听感"更有劲"而不是"变小声"，轻段细节原样保留。
 *
 * 信号链（每帧，linked 全声道）：
 * 1. 短时包络 instDb：s² 一阶平滑 τ≈10ms（跟瞬时响度）
 * 2. 自适应基准 refDb：同一信号 τ≈3s 长期平滑（每首歌自己的"平均响度"）
 * 3. delta = instDb − refDb：当前比平均响/轻多少
 * 4. gainDb = 非对称映射：
 *    delta>0：+min(k·delta, +1.2)dB；delta<0：−0.5dB 封顶（保护轻段）
 *    最后整体加 makeupDb×强度
 * 5. 增益平滑（attack 10ms / release 250ms）后乘到全声道
 * 6. 输出软限幅 knee=0.95：只在源本身已满电平时轻微饱和，不产生平顶失真
 */
@OptIn(UnstableApi::class)
object RangeEnhancer : BaseAudioProcessor() {

    const val KEY_ENABLED = "range_enhancer_enabled"
    /** 强度 0-100：缩放扩展深度，0 时直通，默认 70 */
    const val KEY_STRENGTH = "range_enhancer_strength"

    @Volatile var enabled: Boolean = false
        private set
    @Volatile var strength: Int = 70
        private set

    fun init(prefs: android.content.SharedPreferences) {
        enabled = prefs.getBoolean(KEY_ENABLED, false)
        strength = prefs.getInt(KEY_STRENGTH, 70).coerceIn(0, 100)
    }

    fun setEnabled(v: Boolean) { enabled = v }
    fun setStrength(v: Int) { strength = v.coerceIn(0, 100) }

    // ==================== 参数 ====================

    /** 扩展斜率：delta(dB) → gain(dB) 的比例（只作用于响段） */
    private val k = 0.3f
    /** 增益下限（dB）：轻段最多压 0.5dB——轻段承载高频细节，深压=细节丢失 */
    private val minGainDb = -0.5f
    /** 增益上限（dB）：响段最多抬 1.2dB（v2.2.20 是 +3dB，会顶满电平触发 softclip） */
    private val maxGainDb = 1.2f
    /** 补偿增益（dB）：每档强度叠加的恒定提升，让"增强"平均响度只升不降 */
    private val makeupDb = 1.2f

    // ==================== 状态（仅音频线程） ====================

    private var sampleRate = 44100
    private var designedRate = 0
    private var smoothedGain = 1f
    private var attackCoef = 0f
    private var releaseCoef = 0f
    /** 短时包络（s² 的一阶平滑，τ≈10ms）：瞬时响度 */
    private var envSq = 0f
    private var envCoefRms = 0f
    /** 长期基准（s² 的一阶平滑，τ≈3s）：这首歌自己的平均响度 */
    private var refSq = 1e-4f
    private var refCoef = 0f

    // ==================== AudioProcessor ====================

    override fun onConfigure(inputAudioFormat: AudioFormat): AudioFormat {
        // 始终激活：运行时开关在 queueInput 内直通
        val e = inputAudioFormat.encoding
        if (e != C.ENCODING_PCM_16BIT && e != C.ENCODING_PCM_FLOAT) return AudioFormat.NOT_SET
        if (inputAudioFormat.channelCount < 1) return AudioFormat.NOT_SET
        return inputAudioFormat
    }

    override fun onFlush() {
        smoothedGain = 1f
        envSq = 0f
        refSq = 1e-4f
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

        // 关闭时纯直通（处理器仍在链上，开关即时切换）
        if (!enabled || channels < 1) return
        if (designedRate != format.sampleRate) {
            designedRate = format.sampleRate
            sampleRate = if (format.sampleRate > 0) format.sampleRate else 44100
            // 10ms attack / 250ms release / 10ms 检测 / 3s 基准
            attackCoef = coefFor(0.010f)
            releaseCoef = coefFor(0.250f)
            envCoefRms = coefFor(0.010f)
            refCoef = coefFor(3.0f)
        }
        // 强度缩放压缩深度
        val amount = strength / 100f

        var i = 0
        while (i + bytesPerFrame <= remaining) {
            try {
                var sumSq = 0f
                if (is16) {
                    for (c in 0 until channels) {
                        val s = out.getShort(i + c * 2) / 32768f
                        sumSq += s * s
                    }
                } else {
                    for (c in 0 until channels) {
                        val s = out.getFloat(i + c * 4)
                        sumSq += s * s
                    }
                }
                val gain = gainFor(sumSq / channels, amount)
                if (gain.isFinite() && gain != 1f) {
                    if (is16) {
                        for (c in 0 until channels) {
                            val orig = out.getShort(i + c * 2) / 32768f
                            out.putShort(i + c * 2, softClip(orig * gain).toPcm16())
                        }
                    } else {
                        for (c in 0 until channels) {
                            val orig = out.getFloat(i + c * 4)
                            out.putFloat(i + c * 4, softClip(orig * gain))
                        }
                    }
                }
            } catch (_: Exception) {
                // 单帧异常：样本保持原样，继续下一帧
            }
            i += bytesPerFrame
        }
    }

    /** 输出软限幅：|x| ≤ 0.95 直通，超过后指数饱和渐近 ±1。
     *  knee=0.95（v2.2.20 为 0.9）：源已是满电平时只有轻微饱和，
     *  不会把响段的波形压出明显平顶（平顶=高频谐波被削掉=细节丢失） */
    private fun softClip(x: Float): Float {
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

    // ==================== DSP 核心 ====================

    /**
     * 单帧增益（非对称半扩展器 + 补偿增益）：
     * 短时包络 vs 长期基准 → delta。
     * - 响段（delta>0）：抬 min(k·delta, +maxGain)dB——副歌更有冲击
     * - 轻段（delta<0）：最多压 minGain(0.5)dB——轻段承载镲片/混响等
     *   高频细节，深压会让"细节消失、声音发闷"
     * 整体加 makeupDb×强度：平均响度只升不降，听感"更有劲"而非"更小声"。
     */
    private fun gainFor(meanSq: Float, amount: Float): Float {
        envSq += envCoefRms * (meanSq - envSq)
        refSq += refCoef * (meanSq - refSq)
        val instDb = 20f * ln(envSq.coerceAtLeast(1e-10f)) / ln(10f)
        // 静音保护：低于 -65dBFS 冻结当前增益，不抬底噪
        if (instDb < -65f) return smoothedGain
        val refDb = 20f * ln(refSq.coerceAtLeast(1e-10f)) / ln(10f)
        // 比这首歌平均响度响/轻多少
        val delta = instDb - refDb
        val targetDb = when {
            delta >= 0f -> minOf(maxGainDb, delta * k * amount)
            else -> maxOf(minGainDb, delta * k * amount)
        } + makeupDb * amount
        val targetGain = 10f.pow(targetDb / 20f)
        val coef = if (targetGain < smoothedGain) attackCoef else releaseCoef
        smoothedGain += coef * (targetGain - smoothedGain)
        return smoothedGain
    }

    private fun coefFor(timeSec: Float): Float =
        1f - 10f.pow(-1f / (timeSec * sampleRate))
}