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
import kotlin.math.abs
import kotlin.math.pow

/**
 * 防失真限幅器（V3.11 · 只防撕裂）：**前瞻峰值限幅 + 软限幅兜底**。
 *
 * 解决什么：音量推高、母带本身满电平、或前级增强（虚拟低音/动态范围/虚拟环绕）
 * 把峰值叠过 0dBFS 时，PCM 会硬削波——波形被削出平顶，平顶含大量高次谐波，
 * 听感就是"撕裂/破音/毛刺"。本处理器放在 DSP 链末端，只把顶到天花板附近的峰值
 * 平滑压回，永不让样本触到 ±1.0，从根上杜绝硬削波。
 *
 * 职责边界（v3.11 修复）：本模块**只防撕裂**，不做响度整形。
 * 曾犯的错：1) 按系统 EQ 增益预扣天花板——低音预设 +6.5dB 会把天花板压到 −8dB；
 * 2) softClip 的 knee 跟着 ceiling 一路下探到 0.4；3) release 120ms + 默认 −1.8dB。
 * 三者叠加把整段鼓声的正常电平也压进饱和区，用户反馈"低音鼓点被削没了"。
 * 现在：天花板只在 −0.1~−3dB（默认 −1dB），knee 固定 0.995，EQ 溢出不归这里管。
 *
 * 信号链（每帧，linked 全声道，取声道峰值）：
 * 1. 前瞻缓冲：把信号延迟 lookahead(≈2ms)，让增益在峰值到来前就降下来，
 *    避免"削掉起始瞬态"（无前瞻的限幅器会切掉鼓点的第一下）。
 * 2. 峰值检测：读前瞻窗内最大 |sample|。
 * 3. 目标增益：峰值 ≤ ceiling 时增益 1；超过时 gain = ceiling/peak（把峰压回阈值）。
 * 4. 增益平滑：attack 快(≈1ms 抓住瞬态)、release(≈60ms 快速回原音量，不压暗鼓身)。
 * 5. 兜底软限幅：极端情况(增益还没跟上)时 tanh 型软饱和，仍保证不硬削。
 *
 * strength(0-100) → ceiling：0=−0.1dBFS(仅防溢出)…100=−3dBFS(留足余量、最干净)。
 * 默认 30 ≈ −1dBFS——只削最顶上的过冲，鼓点瞬态不受影响。
 *
 * 线程模型：queueInput 在音频线程(单线程)；开关/强度用 @Volatile 从 UI 改。
 */
@OptIn(UnstableApi::class)
object SafeLimiter : BaseAudioProcessor() {

    const val KEY_ENABLED = "safe_limiter_enabled"
    /** 强度 0-100 → 限幅余量(headroom)：越大越保守、失真风险越低，默认 30 */
    const val KEY_STRENGTH = "safe_limiter_strength"

    @Volatile var enabled: Boolean = false
        private set
    @Volatile var strength: Int = 30
        private set

    fun init(prefs: android.content.SharedPreferences) {
        enabled = prefs.getBoolean(KEY_ENABLED, false)
        strength = prefs.getInt(KEY_STRENGTH, 30).coerceIn(0, 100)
    }

    fun setEnabled(v: Boolean) { enabled = v }
    fun setStrength(v: Int) { strength = v.coerceIn(0, 100) }

    // ==================== 状态（仅音频线程） ====================

    private var designedRate = 0
    private var sampleRate = 44100
    private var attackCoef = 0f
    private var releaseCoef = 0f
    private var gain = 1f

    /** 前瞻环形缓冲（按帧存全声道样本，float）。lookahead≈2ms。 */
    private var look: FloatArray = FloatArray(0)
    private var lookFrames = 0
    private var lookCh = 0
    private var writePos = 0
    private var filled = 0
    /** 前瞻窗内的峰值（增量维护：写入/弹出时更新，避免每帧全扫） */
    private var lookPeak = 0f

    // ==================== AudioProcessor ====================

    override fun onConfigure(inputAudioFormat: AudioFormat): AudioFormat {
        // 始终激活：运行时开关在 queueInput 内直通
        val e = inputAudioFormat.encoding
        if (e != C.ENCODING_PCM_16BIT && e != C.ENCODING_PCM_FLOAT) return AudioFormat.NOT_SET
        if (inputAudioFormat.channelCount < 1) return AudioFormat.NOT_SET
        return inputAudioFormat
    }

    override fun onFlush() {
        gain = 1f
        writePos = 0
        filled = 0
        lookPeak = 0f
        if (look.isNotEmpty()) look.fill(0f)
    }

    private fun design(sampleRateIn: Int, channels: Int) {
        designedRate = sampleRateIn
        sampleRate = if (sampleRateIn > 0) sampleRateIn else 44100
        // attack 1ms（抓瞬态）/ release 60ms（快速回原音量——120ms 会把整段鼓身压暗）
        attackCoef = coefFor(0.001f)
        releaseCoef = coefFor(0.060f)
        // 前瞻窗 2ms
        lookFrames = (sampleRate * 0.002f).toInt().coerceAtLeast(1)
        lookCh = channels
        look = FloatArray(lookFrames * channels)
        writePos = 0
        filled = 0
        lookPeak = 0f
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val remaining = inputBuffer.remaining()
        if (remaining == 0) return
        val format = inputAudioFormat
        val channels = format.channelCount
        val is16 = format.encoding == C.ENCODING_PCM_16BIT
        val bytesPerFrame = if (is16) channels * 2 else channels * 4

        val out = replaceOutputBuffer(remaining)
        out.put(inputBuffer)
        out.flip()

        // 关闭时纯直通（归位增益，重开不带残留）
        if (!enabled || channels < 1) { gain = 1f; return }
        if (designedRate != format.sampleRate || lookCh != channels) design(format.sampleRate, channels)

        // ceiling：strength 0→-0.1dBFS，100→-3dBFS（线性插值 dB）。默认 30 ≈ -1dBFS。
        // 不预扣任何 EQ 增益——EQ 自身的溢出不归本模块兜（那会把正常电平也压暗）。
        val ceilingDb = -0.1f - 2.9f * (strength / 100f)
        val ceiling = 10f.pow(ceilingDb / 20f)

        val frame = FloatArray(channels)
        var i = 0
        while (i + bytesPerFrame <= remaining) {
            try {
                // 读当前帧样本
                if (is16) {
                    for (c in 0 until channels) frame[c] = out.getShort(i + c * 2) / 32768f
                } else {
                    for (c in 0 until channels) frame[c] = out.getFloat(i + c * 4)
                }

                // 帧峰值（全声道最大绝对值）
                var framePeak = 0f
                for (c in 0 until channels) {
                    val a = abs(frame[c])
                    if (a > framePeak) framePeak = a
                }

                // 推入前瞻缓冲，弹出最旧一帧作为"当前要输出"的帧
                val base = writePos * channels
                val delayed = FloatArray(channels)
                if (filled >= lookFrames) {
                    for (c in 0 until channels) delayed[c] = look[base + c]
                }

                // 更新前瞻峰值：写入新帧、（满时）移除旧帧后重算或增量维护。
                if (filled >= lookFrames) {
                    val removedPeak = frameAbsPeak(base, channels)
                    for (c in 0 until channels) look[base + c] = frame[c]
                    if (removedPeak >= lookPeak - 1e-6f) {
                        lookPeak = scanPeak(channels)
                    } else if (framePeak > lookPeak) {
                        lookPeak = framePeak
                    }
                } else {
                    for (c in 0 until channels) look[base + c] = frame[c]
                    if (framePeak > lookPeak) lookPeak = framePeak
                    filled++
                }
                writePos = (writePos + 1) % lookFrames

                // 目标增益：让前瞻窗内的峰值不超过 ceiling
                val peak = lookPeak
                val targetGain = if (peak > ceiling && peak > 1e-9f) ceiling / peak else 1f
                val coef = if (targetGain < gain) attackCoef else releaseCoef
                gain += coef * (targetGain - gain)

                // 输出延迟帧（缓冲已满）或当前帧（未满时直接过），乘增益 + 软限幅兜底
                val src = if (filled >= lookFrames) delayed else frame
                if (is16) {
                    for (c in 0 until channels) {
                        out.putShort(i + c * 2, softClip(src[c] * gain).toPcm16())
                    }
                } else {
                    for (c in 0 until channels) {
                        out.putFloat(i + c * 4, softClip(src[c] * gain))
                    }
                }
            } catch (_: Exception) {
                // 单帧异常：样本保持原样，继续
            }
            i += bytesPerFrame
        }
    }

    // ==================== 工具 ====================

    private fun frameAbsPeak(base: Int, channels: Int): Float {
        var p = 0f
        for (c in 0 until channels) {
            val a = abs(look[base + c])
            if (a > p) p = a
        }
        return p
    }

    private fun scanPeak(channels: Int): Float {
        var p = 0f
        val n = lookFrames * channels
        var j = 0
        while (j < n) {
            val a = abs(look[j])
            if (a > p) p = a
            j++
        }
        return p
    }

    /**
     * 兜底软限幅：只兜"增益平滑还没跟上"的真过冲。knee 固定 0.995——
     * 绝不跟随 ceiling（曾绑 ceiling，EQ 预扣后低至 0.4，正常信号也进 tanh 饱和区，
     * 鼓点瞬态被压平，正是"鼓声被削没"的元凶）。0.995 以下逐位直通。
     */
    private fun softClip(x: Float): Float {
        val lim = 0.995f
        if (x <= lim && x >= -lim) return x
        val head = 1f - lim
        return when {
            x > lim -> lim + head * kotlin.math.tanh((x - lim) / head)
            else -> -lim - head * kotlin.math.tanh((-x - lim) / head)
        }
    }

    private fun Float.toPcm16(): Short =
        ((this.coerceIn(-1f, 1f)) * 32767f).toInt().toShort()

    private fun coefFor(timeSec: Float): Float =
        1f - 10f.pow(-1f / (timeSec * sampleRate))
}