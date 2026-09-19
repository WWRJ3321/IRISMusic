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

    /** 触发地板：瞬时低频能量至少这么高，连背景低噪都不到就不值得震 */
    private const val TRIGGER = 0.22f

    /** 重新武装阈值：差分回落到包络 + 这么多以内，才允许下一次触发 */
    private const val REARM_DELTA = 0.08f

    /** 差分归一化系数：典型响度战争歌曲的平台波动约 ±0.13（beatLevel 标尺），除以它得到 0-1 动态 */
    private const val DYN_NORM = 0.13f

    /** 包络上升系数（每 tick）：快升，一拍之内就吸收 */
    private const val ENV_ATTACK_K = 0.5f

    /** 包络回落系数（每 tick）：慢降，密鼓序列里不会饿死 */
    private const val ENV_RELEASE_K = 0.12f

    /** 两次震动的最小间隔：八分音符密度（120BPM 下 250ms）以下全跟进 */
    private const val MIN_INTERVAL_MS = 110L

    private val handler = Handler(Looper.getMainLooper())
    private var vibrator: Vibrator? = null
    private var lastPulseAt = 0L

    // ---- 起拍检测状态 ----
    /** 快包络：差分信号的包络（在归一化标尺上），快升慢降 */
    private var envelope = 0f
    /** 是否已武装。触发一次后收起，直到差分回落到包络附近才重新武装 */
    private var armed = true

    // ---- 自适应背景基线（滑动均值） ----
    // 响度战争让现代歌曲的 beatLevel 整曲挤在 0.75-0.92 的窄平台，
    // 绝对 dB 标尺没有区分度。背景 = 最近 ~2 秒 beatLevel 的滑动均值，
    // 差分 = (当前 - 背景)/DYN_NORM，把歌曲自身的动态范围拉回 0-1。

    /** 背景窗口长度（tick 数）。轮询 20ms → 100 tick = 2 秒 */
    private const val BG_TICKS = 100

    private var bgSum = 0f
    private var bgSumSq = 0f
    private var bgCount = 0
    private val bgWindow = FloatArray(BG_TICKS)
    private var bgIndex = 0

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
        firePulse(v, level.coerceIn(0, 2))
    }

    /**
     * 档位 → 自拼波形（时长 + 幅度，三档明确区分）。
     * 不用系统预定义 EFFECT_*：部分厂商 ROM 对 TICK/CLICK/HEAVY_CLICK 的映射
     * 是非标准的（实测有轻档反而更重的机器），自己拼才保证档位与手感一致。
     */
    private fun firePulse(v: Vibrator, level: Int = intensity, durationMs: Int = pulseMs, ampFactor: Float = 1f) {
        // 档位基础幅度：轻点 60 / 标准 150 / 重击 255
        val base = when (level) {
            0 -> 60
            1 -> 150
            else -> 255
        }
        // 自适应下 ampFactor<1 让弱拍更轻、强拍保持档位满幅 → 震感跟随鼓点强弱
        val amp = (base * ampFactor.coerceIn(0f, 1f)).toInt().coerceAtLeast(40).coerceAtMost(255)
        // 时长：手动模式用 pulseMs；自适应模式由调用方传入实时映射后的值
        val ms = durationMs.coerceIn(1, 30).toLong()
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && v.hasAmplitudeControl()) {
                v.vibrate(VibrationEffect.createOneShot(ms, amp))
            } else {
                @Suppress("DEPRECATION")
                v.vibrate(ms)
            }
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
        envelope = 0f
        armed = true
        bgSum = 0f
        bgSumSq = 0f
        bgCount = 0
        bgIndex = 0
        java.util.Arrays.fill(bgWindow, 0f)
    }

    private val poll = object : Runnable {
        override fun run() {
            if (!enabled) { resetState(); return }
            val v = vibrator ?: return
            if (!v.hasVibrator()) return

            // 数据过期（没在播放）：整组检测状态归零，等下次播放从头建立背景
            if (!SpectrumAnalyzer.isFresh()) {
                resetState()
                handler.postDelayed(this, 20L)
                return
            }

            val level = SpectrumAnalyzer.beatLevel

            // ---- 背景基线：2 秒滑动均值 + 标准差 ----
            // 满窗前用累积均值，避免开头的基线抖动
            if (bgCount < BG_TICKS) {
                bgWindow[bgCount] = level
                bgSum += level
                bgSumSq += level * level
                bgCount++
            } else {
                val old = bgWindow[bgIndex]
                bgSum -= old
                bgSumSq -= old * old
                bgWindow[bgIndex] = level
                bgSum += level
                bgSumSq += level * level
                bgIndex = (bgIndex + 1) % BG_TICKS
            }
            val n = if (bgCount > 0) bgCount else 1
            val background = if (bgCount > 0) bgSum / bgCount else level
            // 背景标准差 = 歌曲近期起伏程度（鼓点/旋律的动态大小）
            val variance = (bgSumSq / n - background * background).coerceAtLeast(0f)
            val spread = kotlin.math.sqrt(variance)

            // ---- 差分 + 起拍检测 ----
            // d：归一化差分。持续强低音时 background 被顶上去，d 归零不触发；
            // 鼓点起拍时 level 瞬时超出背景，d 突跳。
            val d = (level - background) / DYN_NORM
            val k = if (d > envelope) ENV_ATTACK_K else ENV_RELEASE_K
            envelope += (d - envelope) * k

            if (armed) {
                // 灵敏度→起拍阈值：10 级最灵敏（0.06），1 级最迟钝（0.34），5 级=0.18（原调优值）
                var sens = sensitivity
                if (adaptive) {
                    // 自适应灵敏度：以用户档位为基准，按歌曲起伏（背景标准差）上下浮动。
                    // 不再单独由 spread 定档——beatLevel 是窄平台，spread 普遍很小，
                    // 旧公式把灵敏度压到 2-3，触发比手动还少 → "感觉没自适应"。
                    val bump = (spread / 0.03f).coerceIn(0f, 1f) * 2f
                    sens = (sensitivity - 1 + bump).toInt().coerceIn(1, 10)
                }
                val onsetDelta = 0.34f - (sens - 1) * 0.0311f
                if (level >= TRIGGER && (d - envelope) >= onsetDelta) {
                    val now = android.os.SystemClock.uptimeMillis()
                    if (now - lastPulseAt >= MIN_INTERVAL_MS) {
                        lastPulseAt = now
                        armed = false
                        if (adaptive) {
                            // 力度→tanh 软饱和：raw 越大越逼近 1 但永不硬顶，消除"到极限啪一下钉死"的撞墙感。
                            // raw=0→0，1→0.76，2→0.96，斜率渐趋 0，强拍收得柔顺。
                            val raw = (d - envelope - onsetDelta) / 0.20f
                            val strength = kotlin.math.tanh(raw)
                            // 时长 4..26ms：基准比旧版高，弱拍 4ms、满力 26ms
                            val dur = (4 + strength * 22f).toInt()
                            // 幅度系数 0.4..1：强拍满幅、弱拍明显更轻——这是最可感知的自适应
                            val amp = 0.4f + 0.6f * strength
                            firePulse(v, intensity, dur, amp)
                        } else {
                            firePulse(v)
                        }
                    }
                }
            } else if (d <= envelope + REARM_DELTA) {
                armed = true // 鼓点衰减完毕，重新武装
            }
            handler.postDelayed(this, 20L)
        }
    }
}