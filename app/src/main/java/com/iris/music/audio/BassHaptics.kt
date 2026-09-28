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

    private val handler = Handler(Looper.getMainLooper())
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
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
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
        val ms = durationMs.coerceIn(8, 32).toLong()
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
        lastPulseStrength = 0f
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
                handler.postDelayed(this, 8L)
                return
            }
            lastFrame = frame

            val low = SpectrumAnalyzer.lowFlux
            val mid = SpectrumAnalyzer.midFlux
            val high = SpectrumAnalyzer.highFlux
            val energy = SpectrumAnalyzer.beatLevel
            val rising = SpectrumAnalyzer.bandsRising

            // 预热：起播/切歌后的头 ~0.6 秒只累积三路 EMA 基线，不触发。
            // 这几帧里均值/方差刚从 0 起步，阈值极低，起播瞬态极易误触——
            // "没放歌也震一下"多半就是这里。等基线建立起来再放行。
            if (warmup < WARMUP_FRAMES) {
                warmup++
                val dLow = low - lowMean; lowMean += FLUX_BG_K * dLow; lowVar += FLUX_BG_K * (dLow * dLow - lowVar)
                val dMid = mid - midMean; midMean += FLUX_BG_K * dMid; midVar += FLUX_BG_K * (dMid * dMid - midVar)
                val dHigh = high - highMean; highMean += FLUX_BG_K * dHigh; highVar += FLUX_BG_K * (dHigh * dHigh - highVar)
                handler.postDelayed(this, 8L)
                return
            }

            // ---- 谱通量起拍：三路各自"均值 + k×标准差"自适应阈值 ----
            // 灵敏度线性缩放阈值倍数：档位越高 mult 越小（越易触发）。
            // sensitivity 1→3.0，5→2.2，10→1.2，整体比旧版收紧，避免稳态段狂震。
            val mult = FLUX_STD_MULT + (5 - sensitivity) * 0.2f

            // 宽频一致性门控：本帧有多少比例的频段同时上涨。
            // 宽频（鼓/拍手/镲/带空气感的打击）→ 放宽阈值，让它们震得出来；
            // 窄带（持续音抖动、包络起伏）→ 抬高阈值，压掉过敏的小震动。
            // 这一个系数同时治"空气感打击不震"和"小波动太敏感"两头。
            val gate = when {
                rising >= WIDEBAND_RATIO -> WIDEBAND_RELAX
                rising <= NARROWBAND_RATIO -> NARROWBAND_PENALTY
                else -> {
                    // 两者之间线性过渡，避免占比在边界抖动时触发状态跳变
                    val t = (rising - NARROWBAND_RATIO) / (WIDEBAND_RATIO - NARROWBAND_RATIO)
                    NARROWBAND_PENALTY + t * (WIDEBAND_RELAX - NARROWBAND_PENALTY)
                }
            }
            val effMult = mult * gate

            val now = android.os.SystemClock.uptimeMillis()

            // 依次判定三路；一帧内只发一次（取最强的一路），避免叠加成糊
            var fired = false
            var bestHit = Hit.LOW
            var bestStrength = 0f

            // LOW —— 底鼓
            run {
                // 标准差地板取"相对均值的比例"与绝对兜底的大者：线性能量域下
                // 不同歌曲音量差异极大，固定地板会在小音量歌里高到永远触发不了。
                val floor = maxOf(lowMean * FLUX_STD_FLOOR_RATIO, FLUX_STD_FLOOR_ABS)
                val std = kotlin.math.sqrt(lowVar).coerceAtLeast(floor)
                val thr = lowMean + effMult * std
                val fluxMin = lowMean * FLUX_MIN_RATIO
                if (lowArmed && low > thr && low >= fluxMin && energy >= TRIGGER &&
                    now - lastLowAt >= MIN_INTERVAL_MS) {
                    val s = ((low - thr) / std).coerceIn(0f, 3f) / 3f
                    if (s > bestStrength) { bestStrength = s; bestHit = Hit.LOW; fired = true }
                    lowArmed = false; lastLowAt = now
                } else if (low < thr * REARM_RATIO || now - lastLowAt >= MIN_INTERVAL_MS) {
                    // 重新武装：通量跌回低点，或距上次触发已过最小间隔（时间兜底）。
                    // 少了时间兜底，连续鼓点段落里通量一直高、永远跌不到 0.5×阈值以下，
                    // 武装标志一关就再打不开——表现为"响一下后整段不再震"。
                    lowArmed = true
                }
                // 背景 EMA（含方差）——只在未触发帧更新，避免鼓点本身把均值/方差顶飞
                val dLow = low - lowMean
                lowMean += FLUX_BG_K * dLow
                lowVar += FLUX_BG_K * (dLow * dLow - lowVar)
            }
            // MID —— 军鼓
            run {
                val floor = maxOf(midMean * FLUX_STD_FLOOR_RATIO, FLUX_STD_FLOOR_ABS)
                val std = kotlin.math.sqrt(midVar).coerceAtLeast(floor)
                val thr = midMean + effMult * std
                val fluxMin = midMean * FLUX_MIN_RATIO
                if (midArmed && mid > thr && mid >= fluxMin && energy >= TRIGGER &&
                    now - lastMidAt >= MIN_INTERVAL_MS) {
                    val s = ((mid - thr) / std).coerceIn(0f, 3f) / 3f
                    if (s > bestStrength) { bestStrength = s; bestHit = Hit.MID; fired = true }
                    midArmed = false; lastMidAt = now
                } else if (mid < thr * REARM_RATIO || now - lastMidAt >= MIN_INTERVAL_MS) {
                    midArmed = true
                }
                val dMid = mid - midMean
                midMean += FLUX_BG_K * dMid
                midVar += FLUX_BG_K * (dMid * dMid - midVar)
            }
            // HIGH —— 踩镲（允许更密的节流）
            run {
                val floor = maxOf(highMean * FLUX_STD_FLOOR_RATIO, FLUX_STD_FLOOR_ABS)
                val std = kotlin.math.sqrt(highVar).coerceAtLeast(floor)
                val thr = highMean + effMult * std
                val fluxMin = highMean * FLUX_MIN_RATIO
                if (highArmed && high > thr && high >= fluxMin && energy >= TRIGGER &&
                    now - lastHighAt >= MIN_INTERVAL_FAST_MS) {
                    val s = ((high - thr) / std).coerceIn(0f, 3f) / 3f
                    // 高频只在没有更强低/中频击时才作为主击，避免踩镲盖过底鼓
                    if (s * 0.7f > bestStrength) { bestStrength = s * 0.7f; bestHit = Hit.HIGH; fired = true }
                    highArmed = false; lastHighAt = now
                } else if (high < thr * REARM_RATIO || now - lastHighAt >= MIN_INTERVAL_FAST_MS) {
                    highArmed = true
                }
                val dHigh = high - highMean
                highMean += FLUX_BG_K * dHigh
                highVar += FLUX_BG_K * (dHigh * dHigh - highVar)
            }

            // 全局节流：任意两次震动至少间隔一个下限，从根上杜绝"连成一片"的糊震。
            // 踩镲用更短的下限（MIN_INTERVAL_FAST_MS），否则单路判定里那个快节流
            // 会被这道全局闸完全盖掉、形同虚设，十六分音符的连续踩镲全被吞掉。
            val throttle = if (bestHit == Hit.HIGH) MIN_INTERVAL_FAST_MS else MIN_INTERVAL_MS
            var pass = fired && now - lastPulseAt >= throttle

            // 听觉掩蔽：强击之后的短窗口内，明显更弱的击打直接丢掉。
            // 这是"什么都震、手被震麻"的解药——保住节奏骨架（重拍），
            // 过滤掉夹在中间的碎击，让震动有主次而不是一串等距脉冲。
            if (pass && now - lastPulseAt < MASK_WINDOW_MS &&
                bestStrength < lastPulseStrength * MASK_RATIO) {
                pass = false
            }

            if (pass) {
                lastPulseAt = now
                lastPulseStrength = bestStrength
                // 力度→幅度：两种模式都按本击强弱调制。
                // 以前非自适应写死 1f（每下满力），所以听感僵硬、没有呼吸感；
                // 检测器明明算出了 bestStrength，却在最后一步被丢掉了。
                val strength = kotlin.math.tanh(bestStrength * AMP_CURVE)
                // 段落动态加权：本击的局部强度，再叠加"当前段落 vs 全曲平均"的响度差。
                // 副歌/Drop（+dB）整体推重，主歌/间奏（-dB）整体收轻，
                // 让段落起伏也能传到手上——这是局部自适应检测本身给不了的。
                // 只调幅度、不参与触发判定：安静段该震的还是震，只是震得轻。
                val loudAdj = (SpectrumAnalyzer.relativeLoudnessDb * LOUDNESS_GAIN_PER_DB)
                    .coerceIn(LOUDNESS_ADJ_MIN, LOUDNESS_ADJ_MAX)
                val amp = (AMP_MIN + (1f - AMP_MIN) * strength + loudAdj).coerceIn(0.2f, 1f)
                if (adaptive) {
                    // 自适应：时长也跟鼓点类型 + 力度走（底鼓长、踩镲短）
                    val baseDur = when (bestHit) { Hit.LOW -> 18f; Hit.MID -> 11f; Hit.HIGH -> 6f }
                    val dur = (baseDur * (0.7f + 0.3f * strength)).toInt().coerceIn(4, 32)
                    firePulse(v, bestHit, intensity, dur, amp)
                } else {
                    // 非自适应：时长固定用用户设的档，但幅度仍跟力度——
                    // 这样"档位可控"和"强弱有对比"两者兼得。
                    firePulse(v, bestHit, intensity, pulseMs, amp)
                }
            }
            handler.postDelayed(this, 8L)
        }
    }
}