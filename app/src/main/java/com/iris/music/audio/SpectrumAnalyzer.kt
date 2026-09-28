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

    /**
     * 帧移（hop）：每累积这么多新样本就做一次 FFT，窗口之间 50% 重叠。
     * 512 @44.1kHz ≈ 11.6ms 一帧（对比无重叠的 23ms），把鼓点 attack 的
     * 判定延迟砍半、跟拍更准；代价是 FFT 调用翻倍，但单次 FFT 很轻，可接受。
     */
    private const val HOP_SIZE = 512

    /** 输出频段数 */
    const val BANDS = 36

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

    // ---------- 三段频谱通量（供低音震动分层触发） ----------
    // 鼓点的本质是能量"突增"（瞬态），而非绝对高低。频谱通量 = 每帧相对上一帧
    // 的正向能量增量，天生只捕捉瞬态、无视持续铺底音——这是"精准跟鼓点"的关键。
    // 按频段拆三路，让底鼓/军鼓/踩镲能分别触发不同触感原语（脆 vs 闷）：
    // - low  (≤band lowMaxBand，截止频率可由用户设置)：底鼓 kick
    // - mid  (≤band MID_MAX_BAND，约 ≤2kHz)：军鼓 snare 主体
    // - high (其余，约 2k–16kHz)：踩镲 hi-hat / 军鼓脆响
    /** band 索引上界：低频段（约 ≤254Hz）。默认对应 [LOW_CUTOFF_DEFAULT_HZ]，
     *  可由 [setLowCutoffHz] 按用户设置的截止频率（Hz）动态重算，把多少 Hz 以下算作"低音"。 */
    @Volatile
    var lowMaxBand = 11
        private set
    /** band 索引上界：中频段（约 ≤2kHz） */
    private const val MID_MAX_BAND = 23

    /** 低音截止频率默认值（Hz）：把 40–250Hz 视为底鼓低音区 */
    const val LOW_CUTOFF_DEFAULT_HZ = 250

    /**
     * 按截止频率（Hz）设定低音段上界 band。对数分箱下 band b 的下边界频率
     * f(b) = FREQ_MIN × ratio^b，ratio = (FREQ_MAX/FREQ_MIN)^(1/BANDS)。
     * 反解 b = log(hz/FREQ_MIN)/log(ratio)，取整即"最高一个下边界 ≤ hz 的 band"。
     * 钳制在 [6, MID_MAX_BAND-2]，保证低/中两段都至少留几段、量纲归一化不塌。
     */
    fun setLowCutoffHz(hz: Int) {
        val ratio = (FREQ_MAX / FREQ_MIN).toDouble().pow(1.0 / BANDS)
        val idx = (kotlin.math.ln((hz / FREQ_MIN).toDouble()) / kotlin.math.ln(ratio)).toInt()
        lowMaxBand = idx.coerceIn(6, MID_MAX_BAND - 2)
    }

    @Volatile var lowFlux: Float = 0f
        private set
    @Volatile var midFlux: Float = 0f
        private set
    @Volatile var highFlux: Float = 0f
        private set

    /** 分析帧序号：每完成一次 FFT 自增。轮询方据此判断是否有新帧，避免重复处理同一帧 */
    @Volatile var analysisFrame: Long = 0L
        private set

    /** 上一帧各频段幅值（dB 归一化域），保留给可能的视觉用途 */
    private val prevBands = FloatArray(BANDS)

    /**
     * 检测专用的频段幅值：线性能量和 → 幂律压缩，与 UI 的 dB 峰值分开。
     *
     * 为什么不能复用 UI 那份：
     * 1. UI 取段内 peak，而"空气感打击"（拍手/brush/镲片/宽频瞬态）能量摊在
     *    整段的很多 bin 上，峰值并不突出——取峰会系统性低估它，再降阈值也捞不上来。
     *    这里改成能量求和，宽频打击的真实能量才体现得出来。
     * 2. dB 是对数域，衡量的是"相对变化"：响段落里的真打击(+6dB)和安静段的
     *    小抖动(+6dB)，归一化后增量一模一样，检测器根本分不开。改用线性幅值
     *    做 sqrt 幂律压缩（接近响度感知），保留"绝对能量增加"这个关键信息。
     */
    private val magBands = FloatArray(BANDS)
    private val prevMag = FloatArray(BANDS)

    /**
     * 上涨频段占比 0-1：本帧有多少比例的频段在同时正向突增。
     *
     * 这是区分"真打击"和"噪声波动"最有效的单一特征：鼓/拍手/镲是宽频瞬态，
     * 会让大片频段同时抬起来；而持续音的抖动、贝斯的包络起伏只有零星几段动。
     * 检测侧据此动态松紧阈值——宽频放行（空气感打击能震），窄带收紧（小波动不乱震）。
     */
    @Volatile
    var bandsRising: Float = 0f
        private set

    /** 判定某频段"在上涨"的最小增量，滤掉浮点噪声 */
    private const val RISE_EPS = 0.002f

    // ---------- 段落动态：相对音量 ----------
    // 检测侧的 bestStrength 是局部自适应的（相对近期背景算超出量），
    // 副歌的重击和主歌的轻击各自相对自己的局部背景都"同样突出"，
    // 归一化后输出一样的强度——段落级起伏传递不到触感上。
    // 用"当前段落音量 vs 全曲至今平均音量"补上这个盲区。

    /** 短时程 RMS EMA 系数：≈300ms 时间常数 @11.6ms/帧，代表"这一段期间"的音量 */
    private const val SHORT_RMS_K = 0.04f
    /** 长时程 RMS EMA 系数：≈30s 时间常数，代表"整首歌至今"的平均音量 */
    private const val LONG_RMS_K = 0.0004f
    /** 计入长时程均值的最小 RMS：静音/间奏不计，否则会把全曲均值拉低、
     *  导致间奏后的正常段落被误判成"超响" */
    private const val LOUDNESS_GATE = 1e-4f

    /** 短时程 RMS（≈300ms 窗）：当前段落的音量 */
    @Volatile
    var shortRms: Float = 0f
        private set

    /** 长时程 RMS（≈30s 窗）：整首歌至今的平均音量 */
    @Volatile
    var longRms: Float = 0f
        private set

    /**
     * 相对音量（dB）：当前段落比整首歌至今的平均音量高出多少。
     * 副歌/Drop 通常 +3~+8dB，主歌/间奏 -3~-8dB，钳制在 ±12dB。
     *
     * 流式播放拿不到"整首歌"的真实平均（后面还没解码），这里用长时程 EMA
     * 近似"至今为止的平均"——与 ReplayGain 的实时近似思路相同。
     */
    @Volatile
    var relativeLoudnessDb: Float = 0f
        private set

    private val EMPTY = FloatArray(BANDS)

    // ==================== AudioBufferSink ====================

    override fun flush(sampleRateHz: Int, channelCount: Int, encoding: Int) {
        sampleRate = if (sampleRateHz > 0) sampleRateHz else 44100
        channels = channelCount.coerceAtLeast(1)
        pcmEncoding = encoding
        writeIndex = 0
        published = FloatArray(BANDS)
        java.util.Arrays.fill(prevBands, 0f)
        // 检测支路的历史也必须清，否则切歌首帧会拿上一首的残留值算差分，
        // 得到一个巨大的假通量峰——又变回"切歌误震"。
        java.util.Arrays.fill(prevMag, 0f)
        java.util.Arrays.fill(magBands, 0f)
        bandsRising = 0f
        // 段落动态也要清零：否则新歌开头会拿上一首的平均音量当基准，
        // 安静的开场被判成"远低于平均"而整段不震，或反之全程满力。
        shortRms = 0f
        longRms = 0f
        relativeLoudnessDb = 0f
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

    /** 累积单声道样本；滑动窗：首次填满后每推进 [HOP_SIZE] 个新样本做一次 FFT（窗口 50% 重叠） */
    private fun push(sample: Float) {
        monoBuffer[writeIndex++] = sample
        if (writeIndex < FFT_SIZE) return
        analyze()
        // 保留窗口尾部 (FFT_SIZE - HOP_SIZE) 个样本，左移腾出 HOP_SIZE 空间给新样本。
        // 下一次再累积满 HOP_SIZE 个新样本就又触发一次 FFT——相邻窗口重叠 50%。
        System.arraycopy(monoBuffer, HOP_SIZE, monoBuffer, 0, FFT_SIZE - HOP_SIZE)
        writeIndex = FFT_SIZE - HOP_SIZE
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
        // 原本只算低频 12 段，但分层鼓点检测需要中高频通量（军鼓/踩镲），
        // 所以旁路时也算全 36 段。FFT 本身已经算完，这里只是多几十次分箱取峰，
        // 开销可忽略，换来"跟得准、分得清"。
        val bandCount = when {
            enabled -> BANDS
            sidechainEnabled -> BANDS
            else -> 0
        }

        // 新数组再发布，保持快照原子性：UI 线程读到的要么是旧一帧
        // 要么是新一帧，不会是半填充状态（复用 published 会破坏这一点）。
        val out = FloatArray(BANDS)
        for (b in 0 until bandCount) {
            var peak = 0f
            var energyAcc = 0f
            var i = binStart[b]
            val end = binEnd[b]
            while (i < end) {
                val mag = hypot(re[i], im[i])
                if (mag > peak) peak = mag
                // 能量和：宽频打击的能量摊在整段多个 bin 上，求和才抓得住；
                // 取峰只反映最强的单根谱线，会把"空气感"打击算漏。
                energyAcc += mag * mag
                i++
            }
            // 归一化：FFT 输出需除以 N/2，Hann 窗再补偿 2 倍增益
            val norm = peak / (FFT_SIZE / 4f)
            val db = 20f * log10(norm.coerceAtLeast(1e-6f))
            var v = ((db - DB_FLOOR) / -DB_FLOOR).coerceIn(0f, 1f)
            // 轻微提升低电平段的视觉存在感
            v = v.pow(0.85f)
            out[b] = v

            // 检测支路：线性幅值（能量和开方）再做 sqrt 幂律压缩。
            // 停在线性域而不转 dB，"绝对能量增加"才不会被对数压平。
            val lin = sqrt(energyAcc) / (FFT_SIZE / 4f)
            magBands[b] = sqrt(lin.coerceAtLeast(0f))
        }

        published = out
        lastPublishMs = System.currentTimeMillis()

        // 旁路电平：RMS 用加窗样本算；低频段跟随用户设定的低音截止频率，
        // 不再写死前 BASS_BANDS 段——否则把截止拉到 128Hz 时，能量门仍按
        // 295Hz 以下的能量判定，滑条调了却不起作用。
        var acc = 0f
        for (i in 0 until FFT_SIZE) {
            val x = monoBuffer[i] * window[i]
            acc += x * x
        }
        val rmsLin = sqrt(acc / FFT_SIZE)
        rms = rmsLin

        // 段落动态：短/长两条时程的 RMS EMA，相除得到"当前段落比全曲平均响多少"。
        // 长时程加门限，静音和间奏不计入——否则全曲均值被拉低，
        // 间奏结束后的正常段落会被误判成"超响"而全部满力震。
        shortRms += SHORT_RMS_K * (rmsLin - shortRms)
        if (rmsLin > LOUDNESS_GATE) {
            if (longRms <= 0f) {
                longRms = rmsLin   // 首帧直接落位，避免从 0 慢慢爬导致开头误判
            } else {
                longRms += LONG_RMS_K * (rmsLin - longRms)
            }
        }
        relativeLoudnessDb = if (longRms > LOUDNESS_GATE && shortRms > LOUDNESS_GATE) {
            (20f * log10(shortRms / longRms)).coerceIn(-12f, 12f)
        } else 0f

        val bassCount = (lowMaxBand + 1).coerceAtMost(bandCount)
        if (bassCount > 0) {
            var bAcc = 0f
            for (b in 0 until bassCount) bAcc += out[b]
            bassLevel = bAcc / bassCount
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

        // ---- 三段频谱通量：只取正向增量（能量突增=瞬态/鼓点），累加各段 ----
        // 关键：通量建立在 magBands（线性能量域）而非 out（dB 峰值域）之上。
        // dB 域差分衡量的是"相对变化"，响段的真打击和安静段的小抖动数值一样大，
        // 检测器分不开——这正是"空气感打击不震、小波动反而过敏"的根因。
        if (bandCount >= BANDS) {
            var lo = 0f; var mid = 0f; var hi = 0f
            var rising = 0
            val lowMax = lowMaxBand
            for (b in 0 until BANDS) {
                val diff = magBands[b] - prevMag[b]
                if (diff > 0f) {
                    when {
                        b <= lowMax -> lo += diff
                        b <= MID_MAX_BAND -> mid += diff
                        else -> hi += diff
                    }
                    if (diff > RISE_EPS) rising++
                }
                prevMag[b] = magBands[b]
                prevBands[b] = out[b]
            }
            // 归一化到各段频段数，使三路量纲可比（否则宽的高频段天然占优）
            lowFlux = lo / (lowMax + 1)
            midFlux = mid / (MID_MAX_BAND - lowMax).coerceAtLeast(1)
            highFlux = hi / (BANDS - 1 - MID_MAX_BAND)
            // 宽频一致性：同时上涨的频段占比，供检测侧动态松紧阈值
            bandsRising = rising.toFloat() / BANDS
        } else {
            lowFlux = 0f; midFlux = 0f; highFlux = 0f; bandsRising = 0f
        }
        analysisFrame++
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
