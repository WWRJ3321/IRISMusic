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

import android.content.Context
import android.os.Build
import android.os.Handler
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
    /** 低音截止频率（Hz）：多少 Hz 以下算作低音（走底鼓闷震），可调 120–320 */
    const val KEY_LOW_CUTOFF = "bass_haptics_low_cutoff"
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

    /** 低音截止频率（Hz）：多少 Hz 以下算作低音。默认 250，可调 120–320 */
    @Volatile
    var lowCutoffHz: Int = 250
        private set

    /** 自适应：开启后单次震动时长与触发灵敏度跟随音乐实时变化，忽略手动档位 */
    const val KEY_ADAPTIVE = "bass_haptics_adaptive"

    @Volatile
    var adaptive: Boolean = false
        private set

    /** 触发地板：某段通量对应的能量至少这么高才值得震（避免安静段的微小起伏乱触发） */
    private const val TRIGGER = 0.10f

    /** 两次震动的最小间隔：约 100ms（十六分音符 @150BPM），
     *  再密马达来不及回位就糊成一片，连击感变振麻 */
    private const val MIN_INTERVAL_MS = 100L

    /** 踩镲等高频快击的最小间隔：比主节流略短，但不再是极端的 55ms——
     *  那个值下密集踩镲会连成持续嗡鸣 */
    private const val MIN_INTERVAL_FAST_MS = 78L

    // ---- 通量起拍检测：per-band 自适应阈值 ----
    // 用"通量 > 近期均值 + k×标准差"判定起拍（谱通量法的标准做法），
    // 比固定阈值稳健：安静段阈值自动降低跟得上，吵闹段自动抬高不乱震。
    /** 通量背景 EMA 系数（慢跟，代表"最近平均有多少能量增量"） */
    private const val FLUX_BG_K = 0.05f
    /** 起拍判定倍数：均值之上多少个标准差算一次真正的 onset */
    private const val FLUX_STD_MULT = 1.8f
    /** 标准差地板：相对于当前通量均值的比例，而非固定绝对值。
     *  通量改走线性能量域后，不同歌曲/音量下的绝对量纲差异很大，
     *  固定地板在小音量歌曲里会高得永远触发不了。用相对地板自动缩放。 */
    private const val FLUX_STD_FLOOR_RATIO = 0.35f
    /** 标准差绝对下限：兜底防止完全静音段方差塌到 0 导致每帧狂震 */
    private const val FLUX_STD_FLOOR_ABS = 1e-5f
    /** 通量绝对地板：相对均值的倍数。同样改成相对量，适配线性域的量纲浮动 */
    private const val FLUX_MIN_RATIO = 0.6f
    /** 迟滞回落比例：触发后须跌回阈值的这个比例以下才重新武装（防抖，避免贴着阈值反复触发） */
    private const val REARM_RATIO = 0.5f

    // ---- 宽频一致性门控：区分"真打击"与"小波动" ----
    // 鼓/拍手/镲是宽频瞬态，大量频段同时上涨；持续音的抖动只有零星几段动。
    /** 认定为宽频打击的上涨频段占比：达到即放宽阈值，让空气感打击也能触发 */
    private const val WIDEBAND_RATIO = 0.30f
    /** 宽频打击的阈值折扣：越低越容易触发 */
    private const val WIDEBAND_RELAX = 0.62f
    /** 窄带波动（上涨频段很少）的阈值加成：抬高门槛，压掉过敏的小震动 */
    private const val NARROWBAND_PENALTY = 1.7f
    /** 低于这个上涨占比算窄带噪声波动 */
    private const val NARROWBAND_RATIO = 0.14f

    // ---- 听觉掩蔽：保住节奏骨架，避免"什么都震"糊成一片 ----
    // 真实鼓组里弱的碎击会被紧邻的强击掩盖，人耳自动忽略；全都照震就失去主次。
    /** 掩蔽窗口：强击之后这段时间内，后续击打需要足够强才放行 */
    private const val MASK_WINDOW_MS = 170L
    /** 掩蔽比例：窗口内的击打强度至少要达到上一击的这个比例，否则视为被掩蔽 */
    private const val MASK_RATIO = 0.8f

    /** 最弱击打的幅度系数：不能太低——马达推不动就成了漏震，
     *  真正的强弱对比由各触发路径自己的幅度下限兜底 */
    private const val AMP_MIN = 0.35f
    /** 力度曲线陡度：tanh 的输入缩放，越大越容易到满力 */
    private const val AMP_CURVE = 2.4f

    // ---- 段落动态：相对音量对幅度的加权 ----
    // bestStrength 是局部自适应的，主歌轻击和副歌重击各自相对自己的背景
    // 都"同样突出"，输出一样强——段落起伏完全传不到手上。用相对音量补偿。
    /** 每 dB 相对音量对应的幅度增减；±8dB 约对应 ±0.32 幅度偏移 */
    private const val LOUDNESS_GAIN_PER_DB = 0.04f
    /** 相对音量加权的上下限：防止极端段落把幅度推到饱和或完全消失 */
    private const val LOUDNESS_ADJ_MIN = -0.30f
    private const val LOUDNESS_ADJ_MAX = 0.28f

    /** 鼓点类型：决定用哪种触感原语（脆/中/闷）与节流间隔 */
    private enum class Hit { LOW, MID, HIGH }

    // v5.0 r4：检测 + 震动挪到独立线程。旧版在主线程 8ms 轮询一次并在主线程发 vibrate（Binder 调用），
    // 播放期间每秒 125 次挤占 UI 帧；界面一忙（切布局、滚列表）震动就跟着迟到、扎堆。
    private val handler: Handler by lazy {
        val t = android.os.HandlerThread("iris-haptics", android.os.Process.THREAD_PRIORITY_DISPLAY)
        t.start(); Handler(t.looper)
    }
    private val fireToken = Any()
    private var appContext: Context? = null
    private var btCheckedAt = 0L
    private var btOut = false

    /** 用户同步微调（ms，−250…+250，正值 = 震动更晚） */
    const val KEY_SYNC_OFFSET = "bass_haptics_sync_offset"
    @Volatile
    var syncOffsetMs: Int = 0
        private set
    fun setSyncOffsetMs(ms: Int) { syncOffsetMs = ms.coerceIn(-250, 250) }

    /**
     * 声画（声触）对齐：频谱旁路挂在音频链末端，PCM 在这里被分析时还要先进 AudioTrack 缓冲
     * （ExoPlayer 默认约 250ms）才真正出声，所以旧版的震动整体比鼓点早——手先到、耳后到。
     * 这里按输出延迟把每一下往后推：扬声器/有线约 320ms，蓝牙约 480ms（r4.1 实机反馈仍偏早，从 230/400 上调），叠加用户微调。
     */
    private fun outputDelayMs(): Long {
        val now = android.os.SystemClock.uptimeMillis()
        if (now - btCheckedAt > 3000L) {
            btCheckedAt = now
            btOut = runCatching {
                val am = appContext?.getSystemService(Context.AUDIO_SERVICE) as? android.media.AudioManager
                am?.getDevices(android.media.AudioManager.GET_DEVICES_OUTPUTS)?.any {
                    it.type == android.media.AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
                        it.type == 26 /* TYPE_BLE_HEADSET */ || it.type == 27 /* TYPE_BLE_SPEAKER */
                } ?: false
            }.getOrDefault(false)
        }
        val base = if (btOut) 480 else 320
        return (base + syncOffsetMs).coerceAtLeast(0).toLong()
    }

    private fun scheduleFire(block: () -> Unit) {
        handler.postAtTime(block, fireToken, android.os.SystemClock.uptimeMillis() + outputDelayMs())
    }
    private var vibrator: Vibrator? = null
    private var lastPulseAt = 0L
    /** 上一次实际触发的击打强度 0-1，用于听觉掩蔽判定 */
    private var lastPulseStrength = 0f

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
    /** 预热帧数：hop 后一帧 ~11.6ms，约 50 帧 ≈ 0.58 秒，足够 EMA 建立起背景基线 */
    private const val WARMUP_FRAMES = 50

    fun init(context: Context) {
        appContext = context.applicationContext
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        syncOffsetMs = prefs.getInt(KEY_SYNC_OFFSET, 0).coerceIn(-250, 250)
        enabled = prefs.getBoolean(KEY_ENABLED, false)
        intensity = prefs.getInt(KEY_INTENSITY, 1).coerceIn(0, 2)
        pulseMs = prefs.getInt(KEY_PULSE_MS, 20).coerceIn(1, 30)
        sensitivity = prefs.getInt(KEY_SENSITIVITY, 5).coerceIn(1, 10)
        adaptive = prefs.getBoolean(KEY_ADAPTIVE, false)
        lowCutoffHz = prefs.getInt(KEY_LOW_CUTOFF, SpectrumAnalyzer.LOW_CUTOFF_DEFAULT_HZ).coerceIn(120, 320)
        SpectrumAnalyzer.setLowCutoffHz(lowCutoffHz)
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
        if (value) startPolling() else {
            handler.removeCallbacks(poll)
            handler.removeCallbacksAndMessages(fireToken)
        }
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

    /** 低音截止频率（Hz）：120–320，多少 Hz 以下算作低音走底鼓闷震 */
    fun setLowCutoffHz(hz: Int) {
        lowCutoffHz = hz.coerceIn(120, 320)
        SpectrumAnalyzer.setLowCutoffHz(lowCutoffHz)
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
        // 军鼓/踩镲的脆震分三级降级，优先保"脆感"：
        //
        // 为什么不能用裸 oneShot：LRA 线性马达有质量惯性，需要约 10-20ms 才振得起来。
        // 上一版为了让时长档生效改走 oneShot，结果"短 4ms"档下马达还没动就结束了，
        // 脆震直接消失。厂商的 TICK/CLICK 之所以脆，是用了带主动刹车的整形波形。
        //
        // Composition 图元（API 30+）是唯一能同时做到"脆"和"有强弱"的 API：
        // 波形由厂商调校，scale 参数还能缩放力度。优先走它。
        val f = ampFactor.coerceIn(0f, 1f)
        val levelScale = when (level) { 0 -> 0.62f; 1 -> 0.84f; else -> 1f }
        val typeScale = if (hit == Hit.HIGH) 0.78f else 1f
        // 下限 0.4：再低马达推不动，等于没震——这正是上一版"感觉不到脆震"的原因
        val scale = (f * levelScale * typeScale).coerceIn(0.4f, 1f)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val primitive = if (hit == Hit.HIGH) {
                VibrationEffect.Composition.PRIMITIVE_TICK
            } else {
                VibrationEffect.Composition.PRIMITIVE_CLICK
            }
            val ok = runCatching {
                if (v.areAllPrimitivesSupported(primitive)) {
                    v.vibrate(
                        VibrationEffect.startComposition()
                            .addPrimitive(primitive, scale)
                            .compose()
                    )
                    true
                } else false
            }.getOrDefault(false)
            if (ok) return
        }
        // 预设脆震：没有图元支持的机器，脆感保住但没有力度层次
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val effect = pickEffect(hit, level, ampFactor)
            val ok = runCatching {
                v.vibrate(VibrationEffect.createPredefined(effect)); true
            }.getOrDefault(false)
            if (ok) return
        }
        // 兜底 oneShot：时长必须给够 12ms 以上，否则 LRA 根本推不动。
        // 这里不再使用用户的时长档——太短的值在这条路径上等于静音。
        val base = when (level) { 0 -> 110; 1 -> 190; else -> 255 }
        val amp = (base * f).toInt().coerceIn(60, 255)
        val ms = if (hit == Hit.HIGH) 12L else 16L
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
        val f = ampFactor.coerceIn(0f, 1f)
        val base = when (level) { 0 -> 110; 1 -> 190; else -> 255 }
        // 幅度下限 45：LRA 马达低于这个值基本推不动，设太低等于漏震。
        // 上一版放到 25 想拉开动态范围，结果弱击直接没感觉了。
        val amp = (base * f).toInt().coerceIn(45, 255)
        // 底鼓时长：短档也要保底 8ms。马达有惯性，4ms 还没振起来就结束了，
        // 强弱对比要靠幅度做，不能靠把时长压到马达响应不了的区间。
        val ms = durationMs.coerceIn(8, 60).toLong()
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && v.hasAmplitudeControl() && ms > 28) {
                // 长低音（808 拖尾）：前段满幅、后段半幅，做出"咚——"的下沉回落
                val head = (ms * 0.55f).toLong()
                v.vibrate(VibrationEffect.createWaveform(
                    longArrayOf(head, ms - head), intArrayOf(amp, (amp * 0.5f).toInt().coerceAtLeast(45)), -1))
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && v.hasAmplitudeControl()) {
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
        SpectrumAnalyzer.hapticBands = enabled
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
        lastPulseStrength = 0f
        warmup = 0
        tL.pk = 0f; tM.pk = 0f; tH.pk = 0f
        refTotal = -200f
        lowAt = -1_000_000L; anyAt = -1_000_000L; anyStrength = 0f
        pendingMh = null; pendingMhAt = -1_000_000L
        // 暂停/切歌：还排在输出延迟里的震动作废，不在静音后补震
        handler.removeCallbacksAndMessages(fireToken)
    }

    // ================= v5.0 r6 检测器：时域分频起拍 =================
    // 旧检测用 FFT 谱通量，其"低音"实际覆盖 43–560Hz（见 SpectrumAnalyzer 注释），
    // 人声/旋律起伏抢先触发中频轻震，紧随其后的底鼓又被 100ms 节流与掩蔽吞掉。
    // 用户两首测试曲离线回放（XZ 前奏 4–16s）：参考低音起拍 41 个，旧版低音震只命中 10 个，
    // 新版命中 30 个且全部落在 0.47s 的鼓点网格上。
    //
    // 规则：
    // 1. 三路各自取 dB 包络的 2 帧上升量（~23ms），阈值 = max(最小上升, 均值 + k×标准差)，且须为局部峰。
    // 2. 底鼓优先：底鼓不受中高频节流/掩蔽；底鼓到来时撤销 60ms 内排队的中高频震动。
    // 3. 力度 = 上升陡度 × 一半 + 低音占全频比重 × 一半——低音越"顶"的歌震得越实。
    // 4. 时长随低音拖尾：输出延迟期间回看拖尾长度，808 长尾给到最长 40ms 的"咚——"。
    // r8：按"这首歌的重拍"来判。旧做法（均值 + k×标准差）是相对阈值，任何歌都会被它
    // 自己找出一串"起拍"，安静段、前奏、尾奏也照震不误（模拟 春を連れて 全曲恒定 2.7 下/秒）。
    // 现在每路维护一个"近期大击打的典型上升量" pk（软上升、10s 衰减、单次计入封顶 26dB），
    // 只有上升量达到 pk 的 62% 以上才算重拍；整体响度低于近期峰值 12dB 视为安静段，不震。
    private class Track { var pk = 0f }
    private val tL = Track(); private val tM = Track(); private val tH = Track()
    private var refTotal = -200f
    private var lowAt = -1_000_000L
    private var anyAt = -1_000_000L
    private var anyStrength = 0f
    private var pendingMh: Runnable? = null
    private var pendingMhAt = -1_000_000L

    private fun ring(arr: FloatArray, f: Long): Float = arr[(f % SpectrumAnalyzer.RING).toInt()]

    /** 候选帧 f-1 的上升量；非局部峰返回 −1。顺带更新该路 pk。 */
    private fun riseAt(t: Track, arr: FloatArray, f: Long, pkDecay: Float): Float {
        t.pk *= pkDecay
        val r0 = ring(arr, f - 1) - ring(arr, f - 3)
        val rp = ring(arr, f - 2) - ring(arr, f - 4)
        val rn = ring(arr, f) - ring(arr, f - 2)
        if (r0 < rp || r0 < rn || r0 <= 0f) return -1f
        val rc = minOf(r0, 26f)
        if (rc > t.pk) t.pk += 0.3f * (rc - t.pk)
        return r0
    }

    /** 从 onset 帧起，kick 包络维持在峰值 −6dB 以内的帧数（上限 20 帧 ≈ 230ms） */
    private fun sustainFrames(onset: Long): Int {
        val cur = SpectrumAnalyzer.analysisFrame
        val pk = ring(SpectrumAnalyzer.kickDbRing, onset)
        var n = 0
        while (n < 20 && onset + n <= cur && cur - (onset + n) < SpectrumAnalyzer.RING - 1 &&
            ring(SpectrumAnalyzer.kickDbRing, onset + n) > pk - 6f) n++
        return n
    }

    private fun ampFor(strength: Float): Float {
        val st = kotlin.math.tanh(strength * AMP_CURVE)
        val loudAdj = (SpectrumAnalyzer.relativeLoudnessDb * LOUDNESS_GAIN_PER_DB)
            .coerceIn(LOUDNESS_ADJ_MIN, LOUDNESS_ADJ_MAX)
        return (AMP_MIN + (1f - AMP_MIN) * st + loudAdj).coerceIn(0.2f, 1f)
    }

    private fun processFrame(v: Vibrator, f: Long, cur: Long, now: Long) {
        val fm = SpectrumAnalyzer.frameMs
        val pkDecay = kotlin.math.exp(-fm / 10_000f)
        val live = warmup >= WARMUP_FRAMES
        if (!live) warmup++
        val onset = f - 1
        val kickDb = ring(SpectrumAnalyzer.kickDbRing, onset)
        val totDb = ring(SpectrumAnalyzer.totalDbRing, onset)
        refTotal = maxOf(refTotal - fm / 1000f, totDb)
        val rL = riseAt(tL, SpectrumAnalyzer.kickDbRing, f, pkDecay)
        val rM = riseAt(tM, SpectrumAnalyzer.midDbRing, f, pkDecay)
        val rH = riseAt(tH, SpectrumAnalyzer.highDbRing, f, pkDecay)
        if (!live || totDb < refTotal - 12f) return
        // 灵敏度：5 档 = 55%；10 档 = 40%（更多）；1 档 = 67%（更少）。r11 由 62% 下调：r9 实机"太少"、r6"太多"，取中
        val ratio = 0.55f + (5 - sensitivity) * 0.03f
        val onsetAt = now - ((cur - f + 1) * fm).toLong()
        val fireAt = onsetAt + outputDelayMs()

        // ---- 底鼓 ----
        if (rL >= maxOf(7f, ratio * tL.pk) && kickDb >= totDb - 15f && onsetAt - lowAt >= 170L) {
            val rel = ((rL / tL.pk.coerceAtLeast(1f) - ratio) / 0.6f).coerceIn(0f, 1f)
            val weight = ((kickDb - totDb + 12f) / 12f).coerceIn(0f, 1f)
            val strength = 0.5f * rel + 0.5f * weight
            if (pendingMh != null && onsetAt - pendingMhAt < 60L) {
                handler.removeCallbacks(pendingMh!!)
                pendingMh = null
            }
            lowAt = onsetAt; anyAt = onsetAt; anyStrength = strength
            val amp = ampFor(strength)
            val lv = intensity
            val base = if (adaptive) (18f * (0.7f + 0.3f * strength)).toInt() else pulseMs
            handler.postAtTime({
                val extra = ((sustainFrames(onset) * 2.5f - 10f) * 0.5f).coerceIn(0f, 15f).toInt()
                firePulse(v, Hit.LOW, lv, (base + extra).coerceIn(8, 40), amp)
            }, fireToken, fireAt)
            return
        }
        // ---- 敲击/军鼓/镲：底鼓后 300ms 起可补位（r9：600ms 把 永久の宴 这类底鼓被压、靠拍手/镲推动的电音全挡掉了）----
        if (onsetAt - lowAt < 200L || onsetAt - anyAt < 170L) return
        val mOk = rM >= maxOf(9f, 0.80f * tM.pk)
        val hOk = rH >= maxOf(9f, 0.80f * tH.pk)
        if (!mOk && !hOk) return
        val sM = if (mOk) rM / tM.pk.coerceAtLeast(1f) else 0f
        val sH = if (hOk) rH / tH.pk.coerceAtLeast(1f) else 0f
        val isMid = sM >= sH
        val sMh = (0.8f * ((if (isMid) sM else sH) - 0.80f) / 0.5f + 0.4f).coerceIn(0.3f, 1f)
        anyAt = onsetAt; anyStrength = sMh
        val hit = if (isMid) Hit.MID else Hit.HIGH
        val amp = ampFor(sMh)
        val lv = intensity
        val dur = if (adaptive) ((if (isMid) 11f else 6f) * (0.7f + 0.3f * sMh)).toInt() else pulseMs
        val r = Runnable { firePulse(v, hit, lv, dur, amp) }
        pendingMh = r; pendingMhAt = onsetAt
        handler.postAtTime(r, fireToken, fireAt)
    }

    private val poll = object : Runnable {
        override fun run() {
            if (!enabled) { resetState(); return }
            val v = vibrator ?: return
            if (!v.hasVibrator()) return
            if (!SpectrumAnalyzer.isFresh()) {
                resetState()
                handler.postDelayed(this, 500L)
                return
            }
            val cur = SpectrumAnalyzer.analysisFrame
            if (lastFrame == 0L || cur - lastFrame > SpectrumAnalyzer.RING / 2 || cur < lastFrame) {
                // 首帧或落后太多：从当前帧起步，不回放积压
                lastFrame = cur
            }
            if (cur > lastFrame) {
                val now = android.os.SystemClock.uptimeMillis()
                var f = lastFrame + 1
                while (f <= cur) { if (f >= 4) processFrame(v, f, cur, now); f++ }
                lastFrame = cur
            }
            // 一帧 ~11.6ms：按帧率轮询即可，旧版 8ms 空转一半是无用唤醒
            handler.postDelayed(this, 10L)
        }
    }
}
