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
import android.content.SharedPreferences
import android.media.audiofx.Equalizer
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.roundToInt

/** 调节模式 */
enum class EqEditMode { CURVE, SLIDER }

/**
 * 均衡器状态快照。
 * 增益单位为毫贝（millibel，1 dB = 100 mB），与系统 AudioEffect 一致。
 */
data class EqualizerState(
    /** 设备是否支持均衡器（不支持时 UI 显示占位提示） */
    val available: Boolean = false,
    /** 各频段中心频率（Hz） */
    val centerFreqsHz: List<Int> = emptyList(),
    /** 各频段当前增益（mB），只含用户曲线，不含等响补偿 */
    val levelsMb: List<Int> = emptyList(),
    val minLevelMb: Int = -1500,
    val maxLevelMb: Int = 1500,
    /** 当前选中的内置增强预设；null = 自定义曲线 */
    val boost: EqBoost? = null,
    /** 等响曲线补偿开关（小音量下把两端补回来） */
    val loudness: Boolean = false,
    /** 等响补偿强度 0-100（%），默认 100 = 完整补偿 */
    val loudnessStrength: Int = 100,
    /** 当前调节模式 */
    val editMode: EqEditMode = EqEditMode.CURVE,
    /** 曲线锚点（自由增删/移动），按频率升序 */
    val anchors: List<EqAnchor> = emptyList()
) {
    val bandCount: Int get() = centerFreqsHz.size

    /**
     * 是否有生效的增益。均衡器硬件常开（避免 effect 链切换爆音），
     * 所以"开没开"要看曲线是否非全平，不能看 Equalizer.enabled。
     * 等响补偿单独开着也算生效——它同样在改声音。
     */
    val active: Boolean get() = available && (levelsMb.any { it != 0 } || loudness)
}

/**
 * 系统均衡器封装（android.media.audiofx.Equalizer）。
 *
 * 两种调节模式：
 * - CURVE：用户自由摆放锚点，插值曲线后按各频段中心频率采样，写入系统均衡器
 * - SLIDER：直接按频段调整增益，锚点同步为各频段值
 *
 * 由 MusicService 在 ExoPlayer 就绪后调用 [attach] 绑定 audioSessionId；
 * 配置持久化到 SharedPreferences，重启后自动恢复。
 *
 * 性能说明：拖动时 UI 会高频调用 [moveAnchor]，状态更新是即时的（保证跟手），
 * 而写入硬件与落盘会被节流，避免 AudioEffect 的 JNI 调用拖慢手势。
 */
object EqualizerController {

    private const val PREFS = "iris_prefs"
    private const val KEY_LEVELS = "eq_levels"
    private const val KEY_MODE = "eq_edit_mode"
    private const val KEY_ANCHORS = "eq_anchors"
    private const val KEY_BOOST = "eq_boost"
    private const val KEY_LOUDNESS = "eq_loudness"
    private const val KEY_LOUDNESS_STRENGTH = "eq_loudness_strength"

    /** AudioEffect 优先级：0 为普通应用的常规取值 */
    private const val EFFECT_PRIORITY = 0

    /** 拖动过程中写硬件的最小间隔（毫秒） */
    private const val APPLY_THROTTLE_MS = 45L

    private var prefs: SharedPreferences? = null
    private var equalizer: Equalizer? = null
    private var attachedSessionId = 0

    private val _state = MutableStateFlow(EqualizerState())
    val state: StateFlow<EqualizerState> = _state.asStateFlow()

    private var lastApplyMs = 0L
    /** 被节流跳过的增益，抬手时补写一次，保证最终值不丢 */
    private var pendingLevels: List<Int>? = null

    /** 用于平滑渐变开关均衡器的主线程 Handler */
    private val handler = Handler(Looper.getMainLooper())
    /** 当前是否正在做开关渐变动画 */
    private var ramping = false

    fun init(context: Context) {
        if (prefs == null) {
            prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        }
    }

    /**
     * 绑定到指定音频会话。同一 session 重复调用会被忽略，
     * 设备不支持时把 available 置为 false 而不抛异常。
     */
    fun attach(sessionId: Int) {
        if (sessionId == 0 || (sessionId == attachedSessionId && equalizer != null)) return

        releaseEffect()

        val eq = runCatching { Equalizer(EFFECT_PRIORITY, sessionId) }.getOrNull()
        if (eq == null) {
            _state.value = EqualizerState(available = false)
            return
        }

        equalizer = eq
        attachedSessionId = sessionId

        val bandCount = runCatching { eq.numberOfBands.toInt() }.getOrDefault(0)
        if (bandCount <= 0) {
            releaseEffect()
            _state.value = EqualizerState(available = false)
            return
        }

        val range = runCatching { eq.bandLevelRange }.getOrNull()
        val minMb = range?.getOrNull(0)?.toInt() ?: -1500
        val maxMb = range?.getOrNull(1)?.toInt() ?: 1500

        val freqs = (0 until bandCount).map { band ->
            // getCenterFreq 返回毫赫兹
            runCatching { eq.getCenterFreq(band.toShort()) / 1000 }.getOrDefault(0)
        }

        // 均衡器始终保持 enabled，避免 effect 链路切换产生爆音
        // 增益曲线常开：没有开关概念，调整立即生效
        runCatching { eq.enabled = true }

        val savedMode = runCatching {
            EqEditMode.valueOf(prefs?.getString(KEY_MODE, EqEditMode.CURVE.name)!!)
        }.getOrDefault(EqEditMode.CURVE)
        val savedBoost = prefs?.getString(KEY_BOOST, null)
            ?.let { name -> runCatching { EqBoost.valueOf(name) }.getOrNull() }
        val savedLoudness = prefs?.getBoolean(KEY_LOUDNESS, false) ?: false
        val savedLoudnessStrength = prefs?.getInt(KEY_LOUDNESS_STRENGTH, 100)?.coerceIn(0, 100) ?: 100

        // 锚点：优先读存档，否则按频段生成一条平直曲线
        val anchors = readSavedAnchors()?.takeIf { it.size >= EQ_MIN_ANCHORS }
            ?: freqs.map { EqAnchor(it, 0) }

        // 增益不读硬件、以存档为准：硬件里可能还留着上次写入的「用户曲线 + 等响补偿」
        // 之和，读回来会被误当成用户曲线，等响开着时每次重启都会把补偿量再叠一层。
        val levels = readSavedLevels(bandCount)

        _state.value = EqualizerState(
            available = true,
            centerFreqsHz = freqs,
            levelsMb = levels,
            minLevelMb = minMb,
            maxLevelMb = maxMb,
            boost = savedBoost,
            loudness = savedLoudness,
            loudnessStrength = savedLoudnessStrength,
            editMode = savedMode,
            anchors = anchors
        )
        // 状态就绪后再写硬件，applyLevelsNow 要按 state 里的 loudness 叠补偿
        applyLevelsNow(levels)
    }

    /**
     * 在 [from] 和 [to] 之间线性插值，分 [steps] 步、每步间隔 [intervalMs] 写入硬件。
     * 渐变期间 UI 状态不变（[levelsMb] 保持目标值），只有硬件层在做平滑。
     */
    private fun rampLevels(
        from: List<Int>,
        to: List<Int>,
        steps: Int,
        intervalMs: Long,
        onDone: () -> Unit = {}
    ) {
        val eq = equalizer ?: run { onDone(); return }
        ramping = true
        // 先把硬件设到起点
        applyLevelsNow(from)

        var step = 0
        val callback = object : Runnable {
            override fun run() {
                step++
                if (step >= steps) {
                    applyLevelsNow(to)
                    ramping = false
                    onDone()
                    return
                }
                val t = step.toFloat() / steps
                val interpolated = from.mapIndexed { i, v ->
                    (v + (to.getOrElse(i) { 0 } - v) * t).roundToInt()
                }
                applyLevelsNow(interpolated)
                handler.postDelayed(this, intervalMs)
            }
        }
        handler.postDelayed(callback, intervalMs)
    }

    fun setEditMode(mode: EqEditMode) {
        prefs?.edit()?.putString(KEY_MODE, mode.name)?.apply()
        val s = _state.value
        // 切到曲线模式时，把当前频段增益作为锚点起点，两边视图不会跳变
        val anchors = if (mode == EqEditMode.CURVE) {
            s.centerFreqsHz.mapIndexed { i, f -> EqAnchor(f, s.levelsMb.getOrElse(i) { 0 }) }
        } else {
            s.anchors
        }
        _state.value = s.copy(editMode = mode, anchors = anchors)
        if (mode == EqEditMode.CURVE) saveAnchors(anchors)
    }

    /**
     * 应用内置增强预设（低音 / 中音 / 高音）。
     *
     * 和原来的 `usePreset` 不同，这里不调 `Equalizer.usePreset` —— 系统预设改完硬件后
     * 我们只能把值读回来，读回的曲线未必能用锚点表达，面板上画不出对应形状。
     * 换成自己的锚点曲线后，「面板上看到的」和「实际写进硬件的」是同一条线。
     *
     * 再次点击同一个预设即取消，回到自定义（此前手绘的曲线不会被恢复，
     * 因为预设已经把锚点顶掉了，取消 = 归零）。
     */
    fun selectBoost(boost: EqBoost?) {
        val s = _state.value
        if (!s.available) return

        val next = if (s.boost == boost) null else boost
        val anchors = (next?.anchors ?: s.centerFreqsHz.map { EqAnchor(it, 0) })
            .map { EqAnchor(it.freqHz, it.gainMb.coerceIn(s.minLevelMb, s.maxLevelMb)) }
            .sortedBy { it.freqHz }
        val levels = sampleLevels(anchors, s)

        _state.value = s.copy(boost = next, anchors = anchors, levelsMb = levels)
        applyLevelsNow(levels)
        saveAnchors(anchors)
        saveLevels(levels)
        prefs?.edit()?.putString(KEY_BOOST, next?.name)?.apply()
    }

    /**
     * 等响曲线补偿开关。
     *
     * 补偿量不进用户曲线：面板上画的始终是用户自己那条，补偿只在写硬件时叠加，
     * 关掉后曲线原样回来，不会把补偿"烧"进曲线里。
     */
    fun setLoudness(enabled: Boolean) {
        val s = _state.value
        if (s.loudness == enabled) return
        prefs?.edit()?.putBoolean(KEY_LOUDNESS, enabled)?.apply()
        _state.value = s.copy(loudness = enabled)
        // 用户曲线不变，只是硬件那份要重算
        applyLevelsNow(_state.value.levelsMb)
    }

    /** 等响补偿强度 0-100（%），改完即时重写硬件 */
    fun setLoudnessStrength(strength: Int) {
        val s = _state.value
        val v = strength.coerceIn(0, 100)
        if (s.loudnessStrength == v) return
        prefs?.edit()?.putInt(KEY_LOUDNESS_STRENGTH, v)?.apply()
        _state.value = s.copy(loudnessStrength = v)
        applyLevelsNow(_state.value.levelsMb)
    }

    /** 滑条模式：手动调节单个频段增益 */
    fun setBandLevel(band: Int, levelMb: Int) {
        val s = _state.value
        if (band !in 0 until s.bandCount) return

        val clamped = levelMb.coerceIn(s.minLevelMb, s.maxLevelMb)
        if (s.levelsMb.getOrNull(band) == clamped) return

        val levels = s.levelsMb.toMutableList().also { it[band] = clamped }
        val anchors = s.centerFreqsHz.mapIndexed { i, f -> EqAnchor(f, levels.getOrElse(i) { 0 }) }

        // 先更新状态（UI 立即跟手），硬件写入走节流
        _state.value = s.copy(levelsMb = levels, boost = null, anchors = anchors)
        clearSavedBoost()
        applyLevelsThrottled(levels)
    }

    // ==================== 曲线模式 ====================

    /** 移动锚点（同时改频率和增益）；移动后重新排序并把曲线写入硬件 */
    fun moveAnchor(index: Int, freqHz: Int, gainMb: Int) {
        val s = _state.value
        if (index !in s.anchors.indices) return

        val target = EqAnchor(
            freqHz.coerceIn(EQ_FREQ_MIN.toInt(), EQ_FREQ_MAX.toInt()),
            gainMb.coerceIn(s.minLevelMb, s.maxLevelMb)
        )
        if (s.anchors[index] == target) return

        val list = s.anchors.toMutableList()
        list[index] = target
        // 拖动中不排序：排序会让下标漂移，导致手指"丢点"
        updateAnchors(list, sort = false)
    }

    /** 拖动结束：把锚点按频率排序并做一次完整写入 */
    fun commitAnchors() {
        val s = _state.value
        val sorted = s.anchors.sortedBy { it.freqHz }
        val levels = sampleLevels(sorted, s)
        _state.value = s.copy(anchors = sorted, levelsMb = levels, boost = null)
        applyLevelsNow(levels)
        saveAnchors(sorted)
        saveLevels(levels)
        clearSavedBoost()
    }

    /** 在指定位置新增锚点；已达上限则忽略 */
    fun addAnchor(freqHz: Int, gainMb: Int) {
        val s = _state.value
        if (s.anchors.size >= EQ_MAX_ANCHORS) return

        val list = s.anchors + EqAnchor(
            freqHz.coerceIn(EQ_FREQ_MIN.toInt(), EQ_FREQ_MAX.toInt()),
            gainMb.coerceIn(s.minLevelMb, s.maxLevelMb)
        )
        updateAnchors(list, sort = true)
    }

    /** 删除锚点；低于下限则忽略 */
    fun removeAnchor(index: Int) {
        val s = _state.value
        if (s.anchors.size <= EQ_MIN_ANCHORS || index !in s.anchors.indices) return

        val list = s.anchors.toMutableList().also { it.removeAt(index) }
        updateAnchors(list, sort = true)
    }

    /** 重置：曲线归零，同时清掉增强预设选择（等响开关不动，它是独立维度） */
    fun reset() {
        val s = _state.value

        if (s.editMode == EqEditMode.CURVE) {
            val defaults = s.centerFreqsHz.map { EqAnchor(it, 0) }
                .ifEmpty { listOf(EqAnchor(60, 0), EqAnchor(1000, 0), EqAnchor(14000, 0)) }
            val sorted = defaults.sortedBy { it.freqHz }
            val levels = sampleLevels(sorted, s)
            _state.value = s.copy(anchors = sorted, levelsMb = levels, boost = null)
            applyLevelsNow(levels)
            // 落盘！否则重启后恢复的是旧曲线
            saveAnchors(sorted)
            saveLevels(levels)
            clearSavedBoost()
            return
        }

        val levels = List(s.bandCount) { 0 }
        val anchors = s.centerFreqsHz.map { EqAnchor(it, 0) }
        _state.value = s.copy(levelsMb = levels, boost = null, anchors = anchors)
        applyLevelsNow(levels)
        saveLevels(levels)
        saveAnchors(anchors)
        clearSavedBoost()
    }

    /** 播放器释放时调用 */
    fun release() {
        releaseEffect()
        _state.value = _state.value.copy(available = false)
    }

    // ==================== 内部实现 ====================

    /** 更新锚点并把曲线采样写入硬件（节流） */
    private fun updateAnchors(rawAnchors: List<EqAnchor>, sort: Boolean) {
        val s = _state.value
        val anchors = if (sort) rawAnchors.sortedBy { it.freqHz } else rawAnchors
        val levels = sampleLevels(anchors, s)

        // 状态先更新，UI 立刻响应
        _state.value = s.copy(anchors = anchors, levelsMb = levels, boost = null)
        applyLevelsThrottled(levels)
    }

    private fun clearSavedBoost() {
        prefs?.edit()?.remove(KEY_BOOST)?.apply()
    }

    /** 锚点插值 → 按各频段中心频率采样 */
    private fun sampleLevels(anchors: List<EqAnchor>, s: EqualizerState): List<Int> {
        val interp = EqCurveInterpolator(anchors.sortedBy { it.freqHz })
        return s.centerFreqsHz.map { freq ->
            interp.gainAtFreq(freq.toFloat()).roundToInt().coerceIn(s.minLevelMb, s.maxLevelMb)
        }
    }

    /**
     * 节流写硬件：拖动中每 [APPLY_THROTTLE_MS] 最多写一次。
     * setBandLevel 是 JNI + AudioFlinger 调用，每帧全频段写会明显掉帧。
     */
    private fun applyLevelsThrottled(levels: List<Int>) {
        val now = System.currentTimeMillis()
        if (now - lastApplyMs < APPLY_THROTTLE_MS) {
            pendingLevels = levels
            return
        }
        lastApplyMs = now
        pendingLevels = null
        applyLevelsNow(levels)
    }

    /**
     * 把用户曲线写进硬件。等响补偿在这一步叠加，不进 [EqualizerState.levelsMb]，
     * 因此面板画的永远是用户自己那条线，开关等响不会改动曲线本身。
     */
    private fun applyLevelsNow(levels: List<Int>) {
        val eq = equalizer ?: return
        val s = _state.value
        val loudness = s.loudness
        val loudAmount = s.loudnessStrength / 100f
        levels.forEachIndexed { band, mb ->
            val comp = if (loudness) loudnessCompensationMb(s.centerFreqsHz.getOrElse(band) { 0 }, loudAmount) else 0
            val out = (mb + comp).coerceIn(s.minLevelMb, s.maxLevelMb)
            runCatching { eq.setBandLevel(band.toShort(), out.toShort()) }
        }
    }

    private fun releaseEffect() {
        // 取消正在进行的渐变动画
        handler.removeCallbacksAndMessages(null)
        val wasRamping = ramping
        ramping = false
        // 渐变被中断时：直接写回目标曲线，避免中间值残留
        if (wasRamping && equalizer != null) {
            val s = _state.value
            val target = s.levelsMb
            if (target.isNotEmpty()) {
                runCatching {
                    target.forEachIndexed { band, mb ->
                        equalizer?.setBandLevel(band.toShort(), mb.toShort())
                    }
                }
            }
        }
        // 补写被节流跳过的最后一次
        pendingLevels?.let { applyLevelsNow(it) }
        pendingLevels = null
        equalizer?.let { eq -> runCatching { eq.release() } }
        equalizer = null
        attachedSessionId = 0
    }

    private fun readSavedLevels(bandCount: Int): List<Int> {
        val raw = prefs?.getString(KEY_LEVELS, null) ?: return List(bandCount) { 0 }
        val parsed = raw.split(',').mapNotNull { it.trim().toIntOrNull() }
        return List(bandCount) { parsed.getOrElse(it) { 0 } }
    }

    private fun saveLevels(levels: List<Int>) {
        prefs?.edit()?.putString(KEY_LEVELS, levels.joinToString(","))?.apply()
    }

    /** 锚点存储格式："freq:gain;freq:gain;..." */
    private fun readSavedAnchors(): List<EqAnchor>? {
        val raw = prefs?.getString(KEY_ANCHORS, null)?.takeIf { it.isNotBlank() } ?: return null
        return raw.split(';').mapNotNull { part ->
            val kv = part.split(':')
            val f = kv.getOrNull(0)?.trim()?.toIntOrNull() ?: return@mapNotNull null
            val g = kv.getOrNull(1)?.trim()?.toIntOrNull() ?: return@mapNotNull null
            EqAnchor(f, g)
        }.takeIf { it.isNotEmpty() }?.sortedBy { it.freqHz }
    }

    private fun saveAnchors(anchors: List<EqAnchor>) {
        prefs?.edit()
            ?.putString(KEY_ANCHORS, anchors.joinToString(";") { "${it.freqHz}:${it.gainMb}" })
            ?.apply()
    }
}