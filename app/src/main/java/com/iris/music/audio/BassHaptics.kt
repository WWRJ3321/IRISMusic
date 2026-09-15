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

    /** 触发灵敏度 1-10：越大起拍阈值越低，越容易触发。默认 5（对应原 ONSET_DELTA 0.18） */
    @Volatile
    var sensitivity: Int = 5
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
    private var bgCount = 0
    private val bgWindow = FloatArray(BG_TICKS)
    private var bgIndex = 0

    fun init(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        enabled = prefs.getBoolean(KEY_ENABLED, false)
        intensity = prefs.getInt(KEY_INTENSITY, 1).coerceIn(0, 2)
        pulseMs = prefs.getInt(KEY_PULSE_MS, 20).coerceIn(1, 30)
        sensitivity = prefs.getInt(KEY_SENSITIVITY, 5).coerceIn(1, 10)
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
    private fun firePulse(v: Vibrator, level: Int = intensity) {
        // 幅度按档位：轻点 60 / 标准 150 / 重击 255
        val amp = when (level) {
            0 -> 60
            1 -> 150
            else -> 255
        }
        // 时长由用户滑条决定（1-30ms）
        val ms = pulseMs.toLong()
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

            // ---- 背景基线：2 秒滑动均值 ----
            // 满窗前用累积均值，避免开头的基线抖动
            if (bgCount < BG_TICKS) {
                bgWindow[bgCount] = level
                bgSum += level
                bgCount++
            } else {
                bgSum -= bgWindow[bgIndex]
                bgWindow[bgIndex] = level
                bgSum += level
                bgIndex = (bgIndex + 1) % BG_TICKS
            }
            val background = if (bgCount > 0) bgSum / bgCount else level

            // ---- 差分 + 起拍检测 ----
            // d：归一化差分。持续强低音时 background 被顶上去，d 归零不触发；
            // 鼓点起拍时 level 瞬时超出背景，d 突跳。
            val d = (level - background) / DYN_NORM
            val k = if (d > envelope) ENV_ATTACK_K else ENV_RELEASE_K
            envelope += (d - envelope) * k

            if (armed) {
                // 灵敏度 1-10 → 起拍阈值：10 级最灵敏（0.06），1 级最迟钝（0.34），5 级=0.18（原调优值）
                val onsetDelta = 0.34f - (sensitivity - 1) * 0.0311f
                if (level >= TRIGGER && (d - envelope) >= onsetDelta) {
                    val now = android.os.SystemClock.uptimeMillis()
                    if (now - lastPulseAt >= MIN_INTERVAL_MS) {
                        lastPulseAt = now
                        armed = false
                        // 系统预定义波形：与按钮点击同一套手感（详见 firePulse）
                        firePulse(v)
                    }
                }
            } else if (d <= envelope + REARM_DELTA) {
                armed = true // 鼓点衰减完毕，重新武装
            }
            handler.postDelayed(this, 20L)
        }
    }
}