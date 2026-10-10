package com.iris.music.audio

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import kotlin.math.PI
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 链内十段参量均衡器（RBJ peaking biquad 级联）。
 *
 * 为什么替掉 android.media.audiofx.Equalizer（v4.6.9）：
 * 1. **顺序错误**：系统 EQ 挂在 AudioTrack 输出段，位于本 App DSP 链（含
 *    SafeLimiter）之后。用户在 EQ 上抬 +10dB 时，限幅器早已放行，信号在
 *    系统混音层硬削波——限幅形同虚设，"防失真"是假的。
 * 2. **无预增益**：系统 EQ 不做 pre-amp，抬哪个频段哪个爆。
 * 3. 各 ROM 频段数/中心频率/量程不一致，同一份曲线在不同机器上响得不一样。
 *
 * 现在：固定 10 段 ISO 中心频率（31.5Hz~16kHz），每段一个 peaking biquad
 * （Q=1.1，近似 1 倍频程），级联后自动预增益：曲线最大提升 >0dB 时整体
 * 下压同量，保证 EQ 级输出不顶破 0dBFS——增益全给目标频段，不拿来换失真。
 * 位置在 SafeLimiter 之前，前级总峰值仍由限幅器统一兜底。
 *
 * 增益经 [setLevelsMb] 注入（毫贝），由 EqualizerController 统一维护曲线、
 * 等响补偿与持久化；本类只做 DSP。
 *
 * 线程模型：音频线程 queueInput；[setLevelsMb] 来自 UI 线程，用 @Volatile +
 * 系数双缓冲（UI 只写 gains，音频线程在下个处理块重算系数）。
 */
@OptIn(UnstableApi::class)
object EqProcessor : BaseAudioProcessor() {

    /** 十段 ISO 标准中心频率（Hz），与 UI 频段滑条一一对应 */
    val BAND_FREQS = intArrayOf(31, 63, 125, 250, 500, 1000, 2000, 4000, 8000, 16000)

    /** 单一来源的量程（mB），与 UI 显示一致 */
    const val MIN_LEVEL_MB = -1500
    const val MAX_LEVEL_MB = 1500

    private const val Q = 1.1f

    /** UI 写入的目标增益（mB）；音频线程检测到变化后重算系数 */
    @Volatile
    private var gainsMb: IntArray = IntArray(BAND_FREQS.size)

    /** 系数脏标记：UI 改增益置 true，音频线程消费 */
    @Volatile
    private var dirty = true

    // ── 仅音频线程访问 ──
    private var sampleRate = 44100
    private var channels = 2
    /** 每段系数 b0,b1,b2,a1,a2（a0 归一），按段展平 */
    private var coefs = FloatArray(BAND_FREQS.size * 5)
    /** 已生效增益（用于自动预增益），与 coefs 同代 */
    private var activeGains = IntArray(BAND_FREQS.size)
    /** 每声道 × 每段的双二阶状态 [ch][band*4 + {x1,x2,y1,y2}] */
    private var state = Array(2) { FloatArray(BAND_FREQS.size * 4) }
    private var bypassed = true
    private var preGain = 1f

    /** 由 EqualizerController 调用；levels 为含等响补偿后的每段增益（mB）。 */
    fun setLevelsMb(levels: List<Int>) {
        val next = IntArray(BAND_FREQS.size) { i ->
            levels.getOrNull(i)?.coerceIn(MIN_LEVEL_MB, MAX_LEVEL_MB) ?: 0
        }
        gainsMb = next
        dirty = true
    }

    /** 曲线是否全平（等响补偿已折叠进 levels，全 0 即完全直通）。 */
    fun isFlat(levels: List<Int>): Boolean = levels.all { it == 0 }

    override fun onConfigure(inputAudioFormat: AudioFormat): AudioFormat {
        val e = inputAudioFormat.encoding
        if (e != C.ENCODING_PCM_16BIT && e != C.ENCODING_PCM_FLOAT) return AudioFormat.NOT_SET
        if (inputAudioFormat.channelCount < 1) return AudioFormat.NOT_SET
        return inputAudioFormat
    }

    override fun onFlush() {
        for (s in state) s.fill(0f)
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val remaining = inputBuffer.remaining()
        if (remaining == 0) return
        val format = inputAudioFormat
        val ch = format.channelCount
        val is16 = format.encoding == C.ENCODING_PCM_16BIT

        val out = replaceOutputBuffer(remaining)
        out.put(inputBuffer)
        out.flip()

        // UI 改过增益 → 重算系数与预增益
        if (dirty) rebuild(filterSampleRate(format.sampleRate))
        if (format.sampleRate != sampleRate || ch != channels) {
            sampleRate = filterSampleRate(format.sampleRate)
            channels = ch
            state = Array(ch.coerceAtLeast(1)) { FloatArray(BAND_FREQS.size * 4) }
            rebuild(sampleRate)
        }

        if (bypassed || ch < 1) return

        val bytesPerFrame = if (is16) ch * 2 else ch * 4
        val frame = FloatArray(ch)
        var i = 0
        while (i + bytesPerFrame <= remaining) {
            if (is16) {
                for (c in 0 until ch) frame[c] = out.getShort(i + c * 2) / 32768f
            } else {
                for (c in 0 until ch) frame[c] = out.getFloat(i + c * 4)
            }

            for (c in 0 until ch) {
                var x = frame[c] * preGain
                val st = state[c]
                for (b in 0 until BAND_FREQS.size) {
                    val o = b * 5
                    val s = b * 4
                    val x1 = st[s]; val x2 = st[s + 1]; val y1 = st[s + 2]; val y2 = st[s + 3]
                    val y = coefs[o] * x + coefs[o + 1] * x1 + coefs[o + 2] * x2 -
                        coefs[o + 3] * y1 - coefs[o + 4] * y2
                    st[s] = x; st[s + 1] = x1; st[s + 2] = y; st[s + 3] = y1
                    x = y
                }
                // 级间可能瞬时越界（预增益只保证稳态），16bit 定点输出前防卷绕
                frame[c] = x.coerceIn(-1f, 1f)
            }

            if (is16) {
                for (c in 0 until ch) {
                    val v = (frame[c] * 32767.5f).toInt().coerceIn(-32768, 32767)
                    out.putShort(i + c * 2, v.toShort())
                }
            } else {
                for (c in 0 until ch) out.putFloat(i + c * 4, frame[c])
            }
            i += bytesPerFrame
        }
    }

    private fun filterSampleRate(sr: Int): Int = if (sr > 0) sr else 44100

    /** 重算全部双二阶系数与预增益（音频线程调用）。 */
    private fun rebuild(sr: Int) {
        val gains = gainsMb
        sampleRate = sr
        var maxBoost = 0
        for (g in gains) if (g > maxBoost) maxBoost = g
        // 自动预增益：最大提升量整体下移，稳态输出不顶破 0dBFS
        preGain = if (maxBoost > 0) 10f.pow(-maxBoost / 2000f) else 1f
        bypassed = maxBoost == 0 && gains.all { it == 0 }
        activeGains = gains
        dirty = false
        val nyq = sr / 2f
        for (b in BAND_FREQS.indices) {
            val f = BAND_FREQS[b].toFloat().coerceAtMost(nyq * 0.9f)
            designPeaking(f / sr, gains[b] / 100f, Q, b * 5)
        }
    }

    /** RBJ peaking EQ，系数写入 coefs[base..base+4]：b0,b1,b2,a1,a2（a0 已归一） */
    private fun designPeaking(normFreq: Float, gainDb: Float, q: Float, base: Int) {
        if (gainDb == 0f) {
            // 直通段：单位系数，省判断
            coefs[base] = 1f; coefs[base + 1] = 0f; coefs[base + 2] = 0f
            coefs[base + 3] = 0f; coefs[base + 4] = 0f
            return
        }
        val w = (2.0 * PI * normFreq).toFloat()
        val cw = kotlin.math.cos(w.toDouble()).toFloat()
        val sw = sin(w).toFloat()
        val a = 10f.pow(gainDb / 40f)          // sqrt(10^(dB/20))
        val alpha = sw / (2f * q)
        val a0 = 1f + alpha / a
        val inv = 1f / a0
        coefs[base] = (1f + alpha * a) * inv
        coefs[base + 1] = (-2f * cw) * inv
        coefs[base + 2] = (1f - alpha * a) * inv
        coefs[base + 3] = (-2f * cw) * inv
        coefs[base + 4] = (1f - alpha / a) * inv
    }
}