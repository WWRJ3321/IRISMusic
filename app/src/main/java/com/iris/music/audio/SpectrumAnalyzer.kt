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
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.TeeAudioProcessor
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * 实时频谱分析器：作为 [TeeAudioProcessor.AudioBufferSink] 挂在 ExoPlayer 的音频链上，
 * 旁路解码后的 PCM 做 FFT，产出对数分箱的频谱幅值供 UI 绘制。
 *
 * 相比 android.media.audiofx.Visualizer 的优势：不需要 RECORD_AUDIO 权限，
 * 且拿到的是完整精度 PCM 而非系统降采样后的 8-bit 数据。
 *
 * 线程模型：
 * - [handleBuffer] 在 ExoPlayer 音频线程调用，做窗函数 + FFT + 分箱
 * - UI 线程通过 [snapshot] 读取，用 volatile 引用交换避免加锁
 * - [enabled] 为 false 时 handleBuffer 直接返回，零开销
 *
 * 平滑（attack/release）不在这里做，交给 UI 按屏幕帧率处理，
 * 这样视觉节奏与刷新率同步，不受音频缓冲区大小影响。
 */
@OptIn(UnstableApi::class)
object SpectrumAnalyzer : TeeAudioProcessor.AudioBufferSink {

    /** FFT 点数：1024 点 @44.1kHz ≈ 23ms 窗口、43Hz 分辨率，兼顾时间与频率精度 */
    private const val FFT_SIZE = 1024
    private const val FFT_BITS = 10 // log2(1024)

    /** 输出频段数 */
    const val BANDS = 36

    /**
     * 低音区频段数：约 40–200Hz（按对数分箱，前 [BASS_BANDS] 个频段）。
     * 底鼓基频 50–120Hz、体感打击感延伸到 200Hz 左右，取 12 段才能盖全。
     */
    private const val BASS_BANDS = 12

    /** 分箱频率范围（Hz）：低于 40Hz 多是直流/隆隆声，高于 16kHz 基本听不到 */
    private const val FREQ_MIN = 40f
    private const val FREQ_MAX = 16000f

    /** 幅值动态范围（dB）：低于 -68dB 视为静音 */
    private const val DB_FLOOR = -68f

    /** 超过这个时间没收到新 PCM 就认为已暂停/停止，返回全零让 UI 自然落下 */
    private const val STALE_MS = 180L

    /** 关闭时跳过全部计算；由 UI 开关驱动 */
    @Volatile
    var enabled: Boolean = false
        set(value) {
            field = value
            if (!value) {
                published = FloatArray(BANDS)
                writeIndex = 0
            }
            refreshPipeline()
        }

    /**
     * 无声略过 / 低音震动的旁路开关。与频谱显示是两回事：
     * 频谱关着但旁路功能开着时，FFT 照算（只多发布 rms/bassLevel，
     * 不发布频谱数组），所以真正的计算闸门是 analysisActive 而非 enabled。
     */
    @Volatile
    var sidechainEnabled: Boolean = false
        set(value) {
            field = value
            refreshPipeline()
        }

    /** 分析管线总开关：频谱或旁路任一开启时为 true，handleBuffer 只看它 */
    @Volatile
    private var analysisActive: Boolean = false

    private fun refreshPipeline() {
        analysisActive = enabled || sidechainEnabled
        if (!analysisActive) {
            rms = 0f
            bassLevel = 0f
        }
    }

    // ---------- 音频格式（由 flush 提供） ----------
    @Volatile private var sampleRate = 44100
    @Volatile private var channels = 2
    @Volatile private var pcmEncoding = C.ENCODING_PCM_16BIT

    // ---------- 采样累积缓冲（仅音频线程访问） ----------
    private val monoBuffer = FloatArray(FFT_SIZE)
    private var writeIndex = 0

    // ---------- FFT 工作区（仅音频线程访问，复用避免 GC） ----------
    private val re = FloatArray(FFT_SIZE)
    private val im = FloatArray(FFT_SIZE)

    /** Hann 窗：预计算，抑制频谱泄漏 */
    private val window = FloatArray(FFT_SIZE) { i ->
        0.5f - 0.5f * cos(2.0 * Math.PI * i / (FFT_SIZE - 1)).toFloat()
    }

    /** 位反转置换表：预计算，FFT 重排阶段直接查表 */
    private val bitRev = IntArray(FFT_SIZE) { i ->
        var x = i
        var r = 0
        repeat(FFT_BITS) {
            r = (r shl 1) or (x and 1)
            x = x shr 1
        }
        r
    }

    /** 每个输出频段对应的 FFT bin 区间 [start, end)，随采样率变化时重算 */
    private var binStart = IntArray(BANDS)
    private var binEnd = IntArray(BANDS)
    private var binsBuiltForRate = 0

    // ---------- 发布区（跨线程） ----------
    @Volatile private var published = FloatArray(BANDS)
    @Volatile private var lastPublishMs = 0L

    /**
     * 取当前频谱快照，长度 [BANDS]，每项 0-1。
     * 播放暂停或数据过期时返回全零，UI 侧的 release 平滑会让柱子自然落下。
     */
    fun snapshot(): FloatArray {
        if (!enabled) return EMPTY
        if (System.currentTimeMillis() - lastPublishMs > STALE_MS) return EMPTY
        return published
    }

    /**
     * 旁路数据是否新鲜：超过 [STALE_MS] 没收到新 PCM（暂停/停止/解码失败）
     * 就算过期。bassLevel/rms 是裸读的 volatile，暂停后会冻结在最后一帧——
     * 低音震动若无视过期会永远震下去。
     */
    fun isFresh(): Boolean =
        System.currentTimeMillis() - lastPublishMs <= STALE_MS

    // ---------- 供无声略过 / 低音震动读取的旁路电平 ----------
    // 这些功能不需要完整 FFT 结果，只要每个窗口的均方根与低频能量，
    // 在 analyze() 里顺手算掉，跨线程只读 volatile 快照。

    /** 当前窗口整体 RMS（0-1 线性），静音判定用 */
    @Volatile
    var rms: Float = 0f
        private set

    /**
     * 低频段（前 [BASS_BANDS] 个频段）平均能量，0-1。
     * 低音马达震动以此为触发强度。band0 从 40Hz 起算，落在典型低音区。
     */
    @Volatile
    var bassLevel: Float = 0f
        private set

    /**
     * 节拍能量，0-1：低音频段均值与全频 RMS（同为 dB 归一化）取大者。
     * 鼓的 attack 是宽频瞬态——底鼓走低音、军鼓/踩镲的打击瞬间走全频，
     * 只看低音会漏掉一半的"鼓点"，所以两者谁强跟谁。
     */
    @Volatile
    var beatLevel: Float = 0f
        private set

    private val EMPTY = FloatArray(BANDS)

    // ==================== AudioBufferSink ====================

    override fun flush(sampleRateHz: Int, channelCount: Int, encoding: Int) {
        sampleRate = if (sampleRateHz > 0) sampleRateHz else 44100
        channels = channelCount.coerceAtLeast(1)
        pcmEncoding = encoding
        writeIndex = 0
        published = FloatArray(BANDS)
        buildBins()
    }

    override fun handleBuffer(buffer: ByteBuffer) {
        if (!analysisActive) return

        // TeeAudioProcessor 传入的是只读视图；position 会被我们读到 limit，
        // 但上游用的是 duplicate，不影响真实播放数据。
        val buf = buffer.order(ByteOrder.nativeOrder())
        val ch = channels

        when (pcmEncoding) {
            C.ENCODING_PCM_16BIT -> {
                val sh = buf.asShortBuffer()
                val frames = sh.remaining() / ch
                var f = 0
                while (f < frames) {
                    // 多声道下混为单声道
                    var sum = 0f
                    for (c in 0 until ch) sum += sh.get(f * ch + c) / 32768f
                    push(sum / ch)
                    f++
                }
            }
            C.ENCODING_PCM_FLOAT -> {
                val fb = buf.asFloatBuffer()
                val frames = fb.remaining() / ch
                var f = 0
                while (f < frames) {
                    var sum = 0f
                    for (c in 0 until ch) sum += fb.get(f * ch + c)
                    push(sum / ch)
                    f++
                }
            }
            else -> return // 其它编码（含压缩直通）不做分析
        }
    }

    /** 累积单声道样本，满一窗就做一次 FFT */
    private fun push(sample: Float) {
        monoBuffer[writeIndex++] = sample
        if (writeIndex < FFT_SIZE) return
        writeIndex = 0
        analyze()
    }

    // ==================== DSP ====================

    private fun analyze() {
        // 加窗 + 位反转重排，一次遍历完成
        for (i in 0 until FFT_SIZE) {
            re[bitRev[i]] = monoBuffer[i] * window[i]
            im[i] = 0f
        }

        fft()

        // 频段计算范围：频谱开着算全部；只开旁路（无声略过/低音震动）时
        // 只算低频那几段——BASS_BANDS 覆盖 40Hz 起的低音区，够用了。
        val bandCount = when {
            enabled -> BANDS
            sidechainEnabled -> BASS_BANDS
            else -> 0
        }

        // 新数组再发布，保持快照原子性：UI 线程读到的要么是旧一帧
        // 要么是新一帧，不会是半填充状态（复用 published 会破坏这一点）。
        val out = FloatArray(BANDS)
        for (b in 0 until bandCount) {
            var peak = 0f
            var i = binStart[b]
            val end = binEnd[b]
            while (i < end) {
                val mag = hypot(re[i], im[i])
                if (mag > peak) peak = mag
                i++
            }
            // 归一化：FFT 输出需除以 N/2，Hann 窗再补偿 2 倍增益
            val norm = peak / (FFT_SIZE / 4f)
            val db = 20f * log10(norm.coerceAtLeast(1e-6f))
            var v = ((db - DB_FLOOR) / -DB_FLOOR).coerceIn(0f, 1f)
            // 轻微提升低电平段的视觉存在感
            v = v.pow(0.85f)
            out[b] = v
        }

        published = out
        lastPublishMs = System.currentTimeMillis()

        // 旁路电平：RMS 用加窗样本算；低频取前 BASS_BANDS 个频段均值
        var acc = 0f
        for (i in 0 until FFT_SIZE) {
            val x = monoBuffer[i] * window[i]
            acc += x * x
        }
        val rmsLin = sqrt(acc / FFT_SIZE)
        rms = rmsLin
        if (bandCount >= BASS_BANDS) {
            var bAcc = 0f
            for (b in 0 until BASS_BANDS) bAcc += out[b]
            bassLevel = bAcc / BASS_BANDS
        } else {
            bassLevel = 0f
        }

        // 节拍能量：RMS 转到与频段相同的 dB 归一化标尺（floor/幂映射一致），
        // 再与低频均值取大。RMS 需要补偿：能量分散在全频段，单频段幅值
        // 高于它，不补偿的话 RMS 永远被低频均值压着，混合就失去意义。
        val rmsDb = 20f * log10(rmsLin.coerceAtLeast(1e-6f))
        var rmsNorm = ((rmsDb - DB_FLOOR) / -DB_FLOOR).coerceIn(0f, 1f)
        rmsNorm = rmsNorm.pow(0.85f)
        beatLevel = maxOf(bassLevel, rmsNorm)
    }

    /** 迭代式 radix-2 Cooley-Tukey，输入已按位反转重排，原地计算 */
    private fun fft() {
        var len = 2
        while (len <= FFT_SIZE) {
            val ang = -2.0 * Math.PI / len
            val wRe = cos(ang).toFloat()
            val wIm = kotlin.math.sin(ang).toFloat()
            var i = 0
            while (i < FFT_SIZE) {
                // 每个蝶形组内递推旋转因子，避免逐点调用三角函数
                var curRe = 1f
                var curIm = 0f
                val half = len / 2
                for (j in 0 until half) {
                    val a = i + j
                    val b = a + half
                    val tRe = re[b] * curRe - im[b] * curIm
                    val tIm = re[b] * curIm + im[b] * curRe
                    re[b] = re[a] - tRe
                    im[b] = im[a] - tIm
                    re[a] += tRe
                    im[a] += tIm
                    val nextRe = curRe * wRe - curIm * wIm
                    curIm = curRe * wIm + curIm * wRe
                    curRe = nextRe
                }
                i += len
            }
            len = len shl 1
        }
    }

    /**
     * 按对数频率划分 bin 区间。线性分箱会把低频全挤进最左一根柱子，
     * 而人耳对频率的感知接近对数，所以这里按几何级数切分。
     *
     * 低频段的坑：44.1kHz 下 bin 宽约 43Hz，而 40→90Hz 之间的几个频带边界
     * 全落在同一个 bin 里（比如 40/47/55/65/76Hz 全都映射到 bin 1）。
     * 若放任相邻频段共享同一个 bin，中央镜像排布（低频居中）会让中间
     * 五六根柱子永远读同一个值、一起升降，看上去就是"中间是平的"。
     * 所以这里保证每个频段独占至少一个 bin，且区间严格递增不重叠。
     */
    private fun buildBins() {
        if (binsBuiltForRate == sampleRate) return
        binsBuiltForRate = sampleRate

        val half = FFT_SIZE / 2
        val binHz = sampleRate.toFloat() / FFT_SIZE
        val ratio = (FREQ_MAX / FREQ_MIN).toDouble().pow(1.0 / BANDS)

        val s = IntArray(BANDS)
        val e = IntArray(BANDS)
        var freq = FREQ_MIN
        var lastEnd = 1 // 已占用的 bin 上界（不含），保证严格递增
        for (b in 0 until BANDS) {
            val next = (freq * ratio).toFloat()
            var lo = (freq / binHz).toInt().coerceAtLeast(1)
            var hi = (next / binHz).toInt().coerceAtLeast(lo + 1)
            // 不与前一频段重叠：起点顶到前一段的末尾之后
            lo = lo.coerceAtLeast(lastEnd)
            hi = hi.coerceAtLeast(lo + 1)
            hi = hi.coerceAtMost(half)
            if (hi <= lo) hi = lo + 1
            s[b] = lo
            e[b] = hi
            lastEnd = hi
            freq = next
        }
        binStart = s
        binEnd = e
    }
}
