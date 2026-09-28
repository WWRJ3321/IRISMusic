package com.iris.music.audio

import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

/**
 * 低音马达震动（beta · 多维听感）：低频能量超过阈值时让马达轻轻跟一下。
 *
 * 与 [com.iris.music.ui.Haptics] 的分工：
 * - Haptics 管 UI 触感（点击/滚动），走系统预设波形；
 * - 这里管"音乐触感"，幅度按低频电平连续调制，跟鼓点强弱有关。
 *
 * 节流：两次震动至少间隔 [MIN_INTERVAL_MS]，鼓密的歌曲也不会连成一片。
 * 幅度映射：bassLevel 从 [TRIGGER] 到 1 线性映射到 60..255。
 */
object BassHaptics {

    const val KEY_ENABLED = "bass_haptics_enabled"
    const val KEY_INTENSITY = "bass_haptics_intensity"
    /** 单次震动时长（ms），1-30，默认 20 */
    const val KEY_PULSE_MS = "bass_haptics_pulse_ms"
    /** 触发灵敏度 1-10，越大越容易触发 */
    const val KEY_SENSITIVITY = "bass_haptics_sensitivity"
    const val PREFS = "iris_prefs"

    @Volatile
    var enabled: Boolean = false
        private set

    /**
     * 震动强度档位：0=轻点 1=标准 2=重击。
     * 幅度直接指定（60/150/255），不用系统预定义 EFFECT_*——
     * 部分厂商 ROM 对预设波形映射非标准（实测有轻档反而重的机器）。
     */
    @Volatile
    var intensity: Int = 1
        private set

    /** 单次震动时长 ms，默认 20 */
    @Volatile
    var pulseMs: Int = 20
        private set

    /** 触发灵敏度 1-10：越大起拍阈值越低，越容易触发。默认 5（对应原 ONSET_DELTA 0.18）。自适应下作为基准 */
    @Volatile
    var sensitivity: Int = 5
        private set

    /** 自适应：开启后单次震动时长与触发灵敏度跟随音乐实时变化，忽略手动档位 */
    const val KEY_ADAPTIVE = "bass_haptics_adaptive"

    @Volatile
    var adaptive: Boolean = false
        private set

    /** 触发地板：某段通量对应的能量至少这么高才值得震（避免安静段的微小起伏乱触发） */
    private const val TRIGGER = 0.22f

    /** 两次震动的最小间隔：八分音符密度（120BPM 下 250ms）以下全跟进 */
    private const val MIN_INTERVAL_MS = 110L

    /** 踩镲等高频快击的最小间隔：允许更密（十六分音符），但不至于连成嗡鸣 */
    private const val MIN_INTERVAL_FAST_MS = 70L

    // ---- 通量起拍检测：per-band 自适应阈值 ----
    // 用"通量 > 近期均值 + k×标准差"判定起拍（谱通量法的标准做法），
    // 比固定阈值稳健：安静段阈值自动降低跟得上，吵闹段自动抬高不乱震。
    /** 通量背景 EMA 系数（慢跟，代表"最近平均有多少能量增量"） */
    private const val FLUX_BG_K = 0.05f
    /** 起拍判定倍数：均值之上多少个标准差算一次真正的 onset */
    private const val FLUX_STD_MULT = 2.6f
    /** 标准差地板：防止稳态段方差塌到 0、阈值退化成均值导致每帧狂震 */
    private const val FLUX_STD_FLOOR = 0.02f
    /** 通量绝对地板：低于此的增量一律视为噪声波动，不触发（安静/稳态段的救命阀） */
    private const val FLUX_MIN = 0.035f
    /** 迟滞回落比例：触发后须跌回阈值的这个比例以下才重新武装（防抖，避免贴着阈值反复触发） */
    private const val REARM_RATIO = 0.5f

    /** 鼓点类型：决定用哪种触感原语（脆/中/闷）与节流间隔 */
    private enum class Hit { LOW, MID, HIGH }

    private val handler = Handler(Looper.getMainLooper())
    private var vibrator: Vibrator? = null
    private var lastPulseAt = 0L

    // ---- 起拍检测状态 ----
    /** 上次处理到的分析帧序号，避免同一 FFT 帧被 20ms 轮询重复消费 */
    private var lastFrame = 0L

    // 三路通量的运行均值/方差（EMA），用于自适应阈值：阈值 = 均值 + k×标准差
    private var lowMean = 0f; private var lowVar = 0f
    private var midMean = 0f; private var midVar = 0f
    private var highMean = 0f; private var highVar = 0f

    // 三路各自的重新武装标志：一次 onset 后需等通量回落到阈值下才允许再次触发
    private var lowArmed = true
    private var midArmed = true
    private var highArmed = true

    // 各路上次触发时刻，配合按类型的最小间隔做节流
    private var lastLowAt = 0L
    private var lastMidAt = 0L
    private var lastHighAt = 0L

    // 预热帧计数：复位后的头若干帧只累积基线、不触发。
    // 否则均值/方差从 0 起步、阈值被标准差地板压到极低，起播/切歌瞬间的
    // 尾音或解码瞬态会轻松过阈值——表现为"没放歌也震一下"。
    private var warmup = 0
    /** 预热帧数：约 25 帧 × 23ms ≈ 0.6 秒，足够 EMA 建立起背景基线 */
    private const val WARMUP_FRAMES = 25

    fun init(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        enabled = prefs.getBoolean(KEY_ENABLED, false)
        intensity = prefs.getInt(KEY_INTENSITY, 1).coerceIn(0, 2)
        pulseMs = prefs.getInt(KEY_PULSE_MS, 20).coerceIn(1, 30)
        sensitivity = prefs.getInt(KEY_SENSITIVITY, 5).coerceIn(1, 10)
        adaptive = prefs.getBoolean(KEY_ADAPTIVE, false)
        vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)
                ?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
        refreshSidechain()
        if (enabled) startPolling()
    }

    fun setEnabled(value: Boolean) {
        enabled = value
        refreshSidechain()
        if (value) startPolling() else handler.removeCallbacks(poll)
    }

    fun setIntensity(level: Int) {
        intensity = level.coerceIn(0, 2)
    }

    /** 单次震动时长 ms，1-30 */
    fun setPulseMs(ms: Int) {
        pulseMs = ms.coerceIn(1, 30)
    }

    /** 触发灵敏度 1-10，越大越容易触发 */
    fun setSensitivity(level: Int) {
        sensitivity = level.coerceIn(1, 10)
    }

    /** 自适应开关：时长/灵敏度跟随音乐实时变化 */
    fun setAdaptive(value: Boolean) {
        adaptive = value
    }

    /** 选档预览：立刻用该档位波形震一下，让用户直接感受 */
    fun preview(level: Int) {
        val v = vibrator ?: return
        if (!v.hasVibrator()) return
        firePulse(v, Hit.LOW, level.coerceIn(0, 2))
    }

    /**
     * 发一次触感。全部走系统预设脆震波形（EFFECT_TICK/CLICK/HEAVY_CLICK），
     * 与 UI 按钮同款手感——干脆、一击即止，"鼓一下震一下"的关键。
     * 力度与鼓点类型共同决定用哪一档预设：
     * - 类型基准：踩镲=TICK（最脆最轻）、军鼓=CLICK（脆实）、底鼓=HEAVY_CLICK（重脆）
     * - 自适应力度：弱拍降一档更轻、强拍升一档更重
     * - 强度档位 intensity：作为整体轻重偏移，任何模式都生效
     */
    private fun firePulse(
        v: Vibrator,
        hit: Hit = Hit.LOW,
        level: Int = intensity,
        durationMs: Int = pulseMs,
        ampFactor: Float = 1f
    ) {
        // 底鼓(LOW)：略长的低频闷震，用带幅度的 oneShot——落地"沉"且有回弹感，
        // 这是脆震给不了的质感。军鼓/踩镲(MID/HIGH)：系统预设脆震，干脆利落。
        if (hit == Hit.LOW) {
            fireLowThump(v, level, durationMs, ampFactor)
            return
        }
        // 预设脆震：API 29+ 用 createPredefined，映射到马达的调优触感，最脆
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val effect = pickEffect(hit, level, ampFactor)
            val ok = runCatching {
                v.vibrate(VibrationEffect.createPredefined(effect)); true
            }.getOrDefault(false)
            if (ok) return
            // 极少数 ROM 抛异常：落回 oneShot
        }
        // 回退：短促 oneShot 模拟脆震（时长压到 3-9ms，避免"拖"，整体较上版更利落）
        val base = when (level) { 0 -> 80; 1 -> 170; else -> 255 }
        val amp = (base * ampFactor.coerceIn(0f, 1f)).toInt().coerceIn(40, 255)
        val ms = when (hit) { Hit.HIGH -> 3L; Hit.MID -> 6L; else -> 9L }
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && v.hasAmplitudeControl()) {
                v.vibrate(VibrationEffect.createOneShot(ms, amp))
            } else {
                @Suppress("DEPRECATION")
                v.vibrate(ms)
            }
        }
    }

    /**
     * 底鼓落地：略长的低频闷震。
     * 时长比脆震长（自适应用传入 dur，手动用"单次震动时长"档，范围拉到 14–40ms），
     * 幅度按档位与力度，做出"咚——"的下坠回沉感而非"哒"的一点。
     */
    private fun fireLowThump(v: Vibrator, level: Int, durationMs: Int, ampFactor: Float) {
        val base = when (level) { 0 -> 110; 1 -> 190; else -> 255 }
        val amp = (base * ampFactor.coerceIn(0f, 1f)).toInt().coerceIn(60, 255)
        // 底鼓时长 10–32ms：比脆震长出"沉"的质感，但整体较上一版收短一点，更利落
        val ms = durationMs.coerceIn(10, 32).toLong()
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && v.hasAmplitudeControl()) {
                v.vibrate(VibrationEffect.createOneShot(ms, amp))
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // 无幅度控制的机器：退而用最重的预设脆震，至少档位上区分开
                v.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_HEAVY_CLICK))
            } else {
                @Suppress("DEPRECATION")
                v.vibrate(ms)
            }
        }
    }

    /** 选预设波形档：0=TICK 1=CLICK 2=HEAVY_CLICK。仅用于军鼓/踩镲（底鼓走 oneShot 闷震）。 */
    private fun pickEffect(hit: Hit, level: Int, ampFactor: Float): Int {
        // 类型基准：踩镲最轻脆，军鼓中脆
        var lv = when (hit) { Hit.HIGH -> 0; else -> 1 }
        // 自适应：本拍力度上下浮动一档（弱拍更脆更轻，强拍更沉）
        if (adaptive) {
            lv += when {
                ampFactor < 0.55f -> -1
                ampFactor > 0.85f -> 1
                else -> 0
            }
        }
        // 强度档位作为整体轻重偏移（0 轻点整体降一档，2 重击整体升一档）
        lv += (level - 1)
        return when (lv.coerceIn(0, 2)) {
            0 -> VibrationEffect.EFFECT_TICK
            1 -> VibrationEffect.EFFECT_CLICK
            else -> VibrationEffect.EFFECT_HEAVY_CLICK
        }
    }

    private fun refreshSidechain() {
        SpectrumAnalyzer.sidechainEnabled = SilenceSkipper.enabled || enabled
    }

    private fun startPolling() {
        handler.removeCallbacks(poll)
        handler.post(poll)
    }

    private fun resetState() {
        lastFrame = 0L
        lowMean = 0f; lowVar = 0f
        midMean = 0f; midVar = 0f
        highMean = 0f; highVar = 0f
        lowArmed = true; midArmed = true; highArmed = true
        lastLowAt = 0L; lastMidAt = 0L; lastHighAt = 0L
        warmup = 0
    }

    private val poll = object : Runnable {
        override fun run() {
            if (!enabled) { resetState(); return }
            val v = vibrator ?: return
            if (!v.hasVibrator()) return

            // 数据过期（没在播放）：状态归零，等下次播放从头建立基线
            if (!SpectrumAnalyzer.isFresh()) {
                resetState()
                handler.postDelayed(this, 20L)
                return
            }

            // 只在有新 FFT 帧时处理，避免同一帧被 20ms 轮询重复消费（会虚增触发）
            val frame = SpectrumAnalyzer.analysisFrame
            if (frame == lastFrame) {
                handler.postDelayed(this, 15L)
                return
            }
            lastFrame = frame

            val low = SpectrumAnalyzer.lowFlux
            val mid = SpectrumAnalyzer.midFlux
            val high = SpectrumAnalyzer.highFlux
            val energy = SpectrumAnalyzer.beatLevel

            // 预热：起播/切歌后的头 ~0.6 秒只累积三路 EMA 基线，不触发。
            // 这几帧里均值/方差刚从 0 起步，阈值极低，起播瞬态极易误触——
            // "没放歌也震一下"多半就是这里。等基线建立起来再放行。
            if (warmup < WARMUP_FRAMES) {
                warmup++
                val dLow = low - lowMean; lowMean += FLUX_BG_K * dLow; lowVar += FLUX_BG_K * (dLow * dLow - lowVar)
                val dMid = mid - midMean; midMean += FLUX_BG_K * dMid; midVar += FLUX_BG_K * (dMid * dMid - midVar)
                val dHigh = high - highMean; highMean += FLUX_BG_K * dHigh; highVar += FLUX_BG_K * (dHigh * dHigh - highVar)
                handler.postDelayed(this, 15L)
                return
            }

            // ---- 谱通量起拍：三路各自"均值 + k×标准差"自适应阈值 ----
            // 灵敏度线性缩放阈值倍数：档位越高 mult 越小（越易触发）。
            // sensitivity 1→3.0，5→2.2，10→1.2，整体比旧版收紧，避免稳态段狂震。
            val mult = FLUX_STD_MULT + (5 - sensitivity) * 0.2f

            val now = android.os.SystemClock.uptimeMillis()

            // 依次判定三路；一帧内只发一次（取最强的一路），避免叠加成糊
            var fired = false
            var bestHit = Hit.LOW
            var bestStrength = 0f

            // LOW —— 底鼓
            run {
                val std = kotlin.math.sqrt(lowVar).coerceAtLeast(FLUX_STD_FLOOR)
                val thr = lowMean + mult * std
                if (lowArmed && low > thr && low >= FLUX_MIN && energy >= TRIGGER &&
                    now - lastLowAt >= MIN_INTERVAL_MS) {
                    val s = ((low - thr) / std).coerceIn(0f, 3f) / 3f
                    if (s > bestStrength) { bestStrength = s; bestHit = Hit.LOW; fired = true }
                    lowArmed = false; lastLowAt = now
                } else if (low < thr * REARM_RATIO) lowArmed = true
                // 背景 EMA（含方差）——只在未触发帧更新，避免鼓点本身把均值/方差顶飞
                val dLow = low - lowMean
                lowMean += FLUX_BG_K * dLow
                lowVar += FLUX_BG_K * (dLow * dLow - lowVar)
            }
            // MID —— 军鼓
            run {
                val std = kotlin.math.sqrt(midVar).coerceAtLeast(FLUX_STD_FLOOR)
                val thr = midMean + mult * std
                if (midArmed && mid > thr && mid >= FLUX_MIN && energy >= TRIGGER &&
                    now - lastMidAt >= MIN_INTERVAL_MS) {
                    val s = ((mid - thr) / std).coerceIn(0f, 3f) / 3f
                    if (s > bestStrength) { bestStrength = s; bestHit = Hit.MID; fired = true }
                    midArmed = false; lastMidAt = now
                } else if (mid < thr * REARM_RATIO) midArmed = true
                val dMid = mid - midMean
                midMean += FLUX_BG_K * dMid
                midVar += FLUX_BG_K * (dMid * dMid - midVar)
            }
            // HIGH —— 踩镲（允许更密的节流）
            run {
                val std = kotlin.math.sqrt(highVar).coerceAtLeast(FLUX_STD_FLOOR)
                val thr = highMean + mult * std
                if (highArmed && high > thr && high >= FLUX_MIN && energy >= TRIGGER &&
                    now - lastHighAt >= MIN_INTERVAL_FAST_MS) {
                    val s = ((high - thr) / std).coerceIn(0f, 3f) / 3f
                    // 高频只在没有更强低/中频击时才作为主击，避免踩镲盖过底鼓
                    if (s * 0.7f > bestStrength) { bestStrength = s * 0.7f; bestHit = Hit.HIGH; fired = true }
                    highArmed = false; lastHighAt = now
                } else if (high < thr * REARM_RATIO) highArmed = true
                val dHigh = high - highMean
                highMean += FLUX_BG_K * dHigh
                highVar += FLUX_BG_K * (dHigh * dHigh - highVar)
            }

            // 全局节流：任意两次震动至少间隔 MIN_INTERVAL_MS，从根上杜绝"连成一片"的糊震。
            // 高频踩镲想更密时，仍受此闸限制——宁可漏一两下踩镲，也不失禁。
            if (fired && now - lastPulseAt >= MIN_INTERVAL_MS) {
                lastPulseAt = now
                if (adaptive) {
                    // 强度跟力度：tanh 软饱和，弱拍轻、强拍满
                    val strength = kotlin.math.tanh(bestStrength * 2f)
                    val amp = 0.4f + 0.6f * strength
                    // 时长跟鼓点类型 + 力度：底鼓长、踩镲短，力度再微调（整体较上版略收短）
                    val baseDur = when (bestHit) { Hit.LOW -> 18f; Hit.MID -> 11f; Hit.HIGH -> 6f }
                    val dur = (baseDur * (0.7f + 0.3f * strength)).toInt().coerceIn(4, 32)
                    firePulse(v, bestHit, intensity, dur, amp)
                } else {
                    // 非自适应：固定档位 + 固定时长，但触发点仍由通量精准定位
                    firePulse(v, bestHit, intensity, pulseMs, 1f)
                }
            }
            handler.postDelayed(this, 15L)
        }
    }
}