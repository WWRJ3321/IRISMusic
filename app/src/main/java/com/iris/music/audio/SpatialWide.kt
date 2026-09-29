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
 * 宽场环绕（V4.4 · 空间感增强）：**多频段声场展宽 + 去相关早期反射**。
 *
 * 与 [VirtualSurround]（单一侧边高频展宽 + 单声道去相关）的分工：
 * VirtualSurround 解决"变宽"，本模块在其之上叠加"包围感与纵深"——它是
 * 一台更完整的空间处理器，两者可各自独立开关、叠加使用。
 *
 * 为什么单一展宽不够（VirtualSurround 的局限）：
 * 1. 对整段侧边高频统一增益：人声（居中、集中在中频）会被一并推散，声像发虚；
 * 2. 只把声像往两边拉，没有"声音绕到耳朵周围"的包围感与前后纵深。
 *
 * 本模块两条信号链（仅立体声，channelCount≥2；单声道纯直通）：
 *
 * A. 多频段 M/S 展宽（分频治理，替代一刀切）：
 *    S = (L−R)/2 拆成三段，各段独立展宽量：
 *    - 低频 <200Hz：宽度 0——低音必须居中，展宽会让低频发虚、失去下盘
 *    - 中频 200–2kHz：温和展宽（0.4×强度）——保护人声与主奏，不推散
 *    - 高频 >2kHz：大幅展宽（1.0×强度）——空气感、器乐泛音向两侧铺开，是"宽"的主来源
 *    分频用两个巴特沃斯 LPF（@200 / @2000）级联切出三段。
 *
 * B. 去相关早期反射（包围感/纵深的主来源）：
 *    把侧边信号存入延迟线，取两个错开的抽头（≈13ms / ≈19ms，左右非对称
 *    → 天然去相关），各经一阶低通去掉高频毛刺后，以相反极性极轻量混回
 *    L/R。人耳把 10–40ms 的延迟副本解读为"房间早期反射" → 声音像是在
 *    一个空间里发生，而非贴在两只耳朵上。反射量随强度线性缩放，且被
 *    刻意压得很轻（避免糊成混响、破坏定位）。
 *
 * 强度 0 时输出与输入逐样本相同（宽度 0、反射 0），可安全常开直通。
 * 输出软限幅 knee=0.95：展宽 + 反射叠加后峰值可能越界，轻微饱和不硬削。
 * 末端 [SafeLimiter] 仍会兜底。
 *
 * 链路位置：VirtualSurround 之后、VirtualBass 之前——先展好宽场，再让
 * VirtualBass 把低音谐波居中注入（若先注入再展宽会把谐波摊向两侧、破坏下盘）。
 *
 * 线程模型：queueInput 在音频线程（单线程）；开关/强度用 @Volatile 从 UI 改。
 */
@OptIn(UnstableApi::class)
object SpatialWide : BaseAudioProcessor() {

    const val KEY_ENABLED = "spatial_wide_enabled"
    /** 强度 0-100 → 展宽量与反射量整体缩放，默认 50 */
    const val KEY_STRENGTH = "spatial_wide_strength"

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

    // ==================== 参数 ====================

    /** 中频（200–2kHz）展宽系数：×强度。温和，保护人声不被推散 */
    private const val WIDTH_MID = 0.4f
    /** 高频（>2kHz）展宽系数：×强度。空气感/泛音铺开，"宽"的主来源 */
    private const val WIDTH_HIGH = 1.0f
    /** 早期反射整体增益：×强度。刻意压轻，避免糊成混响、破坏定位 */
    private const val REFLECT_GAIN = 0.18f
    /** 反射抽头低通截止（Hz）：滤掉延迟副本的高频毛刺，反射听感更"暖"更自然 */
    private const val REFLECT_LP_HZ = 6000f

    // ==================== DSP 状态（仅音频线程访问） ====================

    // 作用于侧边 S 的两个巴特沃斯 LPF（Q=1/√2）：@200Hz 与 @2000Hz
    private var lp200B0 = 0f; private var lp200B1 = 0f; private var lp200B2 = 0f
    private var lp200A1 = 0f; private var lp200A2 = 0f
    private var lp200X1 = 0f; private var lp200X2 = 0f; private var lp200Y1 = 0f; private var lp200Y2 = 0f

    private var lp2kB0 = 0f; private var lp2kB1 = 0f; private var lp2kB2 = 0f
    private var lp2kA1 = 0f; private var lp2kA2 = 0f
    private var lp2kX1 = 0f; private var lp2kX2 = 0f; private var lp2kY1 = 0f; private var lp2kY2 = 0f

    // 早期反射延迟线（存侧边 S），两个非对称抽头
    private var delayBuf: FloatArray = FloatArray(0)
    private var delayPos = 0
    private var tap1 = 0
    private var tap2 = 0
    // 反射抽头一阶低通状态（L/R 各一路）
    private var reflLpCoef = 0f
    private var reflLpL = 0f
    private var reflLpR = 0f

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
        lp200X1 = 0f; lp200X2 = 0f; lp200Y1 = 0f; lp200Y2 = 0f
        lp2kX1 = 0f; lp2kX2 = 0f; lp2kY1 = 0f; lp2kY2 = 0f
        if (delayBuf.isNotEmpty()) delayBuf.fill(0f)
        delayPos = 0
        reflLpL = 0f
        reflLpR = 0f
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val remaining = inputBuffer.remaining()
        if (remaining == 0) return
        val format = inputAudioFormat
        val channels = format.channelCount
        val is16 = format.encoding == C.ENCODING_PCM_16BIT
        val bytesPerFrame = if (is16) channels * 2 else channels * 4

        // 先整块拷贝，再原位改写（结构上不可能丢数据）
        val out = replaceOutputBuffer(remaining)
        out.put(inputBuffer)
        out.flip()

        // 关闭 / 单声道 / 非法：纯直通（单声道侧边恒 0，展宽无意义）
        if (!enabled || channels < 2) return
        if (designedRate != format.sampleRate) designFilters(format.sampleRate)

        val w = (strength / 100f).coerceIn(0f, 1f)
        val wMid = WIDTH_MID * w
        val wHigh = WIDTH_HIGH * w
        val reflG = REFLECT_GAIN * w
        val n = delayBuf.size

        var i = 0
        while (i + bytesPerFrame <= remaining) {
            try {
                val l: Float
                val r: Float
                if (is16) {
                    l = out.getShort(i) / 32768f
                    r = out.getShort(i + 2) / 32768f
                } else {
                    l = out.getFloat(i)
                    r = out.getFloat(i + 4)
                }

                val m = (l + r) * 0.5f
                val s = (l - r) * 0.5f

                // 分频：LPF200 → 低段；LPF2000 → 低+中段；剩余为高段
                val sLow = lp200B0 * s + lp200B1 * lp200X1 + lp200B2 * lp200X2 -
                    lp200A1 * lp200Y1 - lp200A2 * lp200Y2
                lp200X2 = lp200X1; lp200X1 = s; lp200Y2 = lp200Y1; lp200Y1 = sLow

                val sLowMid = lp2kB0 * s + lp2kB1 * lp2kX1 + lp2kB2 * lp2kX2 -
                    lp2kA1 * lp2kY1 - lp2kA2 * lp2kY2
                lp2kX2 = lp2kX1; lp2kX1 = s; lp2kY2 = lp2kY1; lp2kY1 = sLowMid

                val sMid = sLowMid - sLow
                val sHigh = s - sLowMid

                // 多频段展宽：低段锁定居中（宽度 0），中段温和，高段大幅
                val newS = sLow + sMid * (1f + wMid) + sHigh * (1f + wHigh)

                // 早期反射：读两个非对称抽头（延迟副本），一阶低通后反极性极轻混回
                var refL = 0f
                var refR = 0f
                if (n > 0 && reflG > 1e-4f) {
                    val a = delayBuf[(delayPos - tap1 + n) % n]
                    val b = delayBuf[(delayPos - tap2 + n) % n]
                    reflLpL += reflLpCoef * (a - reflLpL)
                    reflLpR += reflLpCoef * (b - reflLpR)
                    // 左右取不同抽头 + 相反极性 → 去相关，制造包围感与纵深
                    refL = reflG * reflLpL
                    refR = -reflG * reflLpR
                    delayBuf[delayPos] = s
                    delayPos = (delayPos + 1) % n
                }

                val lo = softClipF(m + newS + refL)
                val ro = softClipF(m - newS + refR)

                if (is16) {
                    out.putShort(i, lo.toPcm16())
                    out.putShort(i + 2, ro.toPcm16())
                } else {
                    out.putFloat(i, lo)
                    out.putFloat(i + 4, ro)
                }
                // 3+ 声道：其余声道原样保留（不动）
            } catch (_: Exception) {
                // 单帧异常：样本保持原样，继续下一帧
            }
            i += bytesPerFrame
        }
    }

    // ==================== DSP 核心 ====================

    /** 软限幅：|x| ≤ 0.95 直通，超过后指数饱和渐近 ±1（C1 连续，无方波边沿） */
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

    /** 双二阶 LPF 系数（RBJ cookbook，巴特沃斯 Q=1/√2）+ 延迟线/反射滤波初始化 */
    private fun designFilters(sampleRate: Int) {
        designedRate = sampleRate
        if (sampleRate <= 0) return
        val rate = sampleRate.toFloat()

        // LPF @200Hz（分子 (1−c)，零点在 Nyquist，通带增益 1）
        run {
            val w0 = 2f * PI.toFloat() * 200f / rate
            val c = cos(w0); val a = sin(w0) / (2f * 0.70710678f); val a0 = 1f + a
            lp200B0 = ((1f - c) / 2f) / a0
            lp200B1 = (1f - c) / a0
            lp200B2 = ((1f - c) / 2f) / a0
            lp200A1 = (-2f * c) / a0
            lp200A2 = (1f - a) / a0
        }
        // LPF @2000Hz
        run {
            val w0 = 2f * PI.toFloat() * 2000f / rate
            val c = cos(w0); val a = sin(w0) / (2f * 0.70710678f); val a0 = 1f + a
            lp2kB0 = ((1f - c) / 2f) / a0
            lp2kB1 = (1f - c) / a0
            lp2kB2 = ((1f - c) / 2f) / a0
            lp2kA1 = (-2f * c) / a0
            lp2kA2 = (1f - a) / a0
        }

        // 早期反射延迟线：抽头 ≈13ms / ≈19ms（非对称去相关），缓冲取两者之大者
        tap1 = (0.013f * rate).toInt().coerceAtLeast(1)
        tap2 = (0.019f * rate).toInt().coerceAtLeast(1)
        val size = (maxOf(tap1, tap2) + 1).coerceAtLeast(1)
        delayBuf = FloatArray(size)
        delayPos = 0
        reflLpL = 0f
        reflLpR = 0f
        // 反射抽头一阶低通系数：coef = 1 − e^(−2π·fc/fs)
        reflLpCoef = (1f - exp(-2f * PI.toFloat() * REFLECT_LP_HZ / rate)).coerceIn(0f, 1f)
    }
}
