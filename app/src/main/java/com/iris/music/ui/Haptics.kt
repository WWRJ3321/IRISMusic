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
package com.iris.music.ui

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

/**
 * 触感反馈。用 Vibrator 直接发预设波形而不是 View.performHapticFeedback，
 * 因为后者会被系统"触摸反馈"总开关整体吞掉，App 内的开关就失去意义了。
 *
 * 三档强度对应三类操作，避免所有按键都是同一种"嗒"：
 * [click] 主控制、[tap] 次级控制/列表/开关、[tick] 滚动条刻度（带节流）。
 */
object Haptics {

    const val PREFS = "iris_prefs"
    const val KEY_ENABLED = "haptics_enabled"

    @Volatile
    var enabled: Boolean = true

    private var vibrator: Vibrator? = null
    private var lastTickAt = 0L

    fun init(context: Context) {
        enabled = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_ENABLED, true)
        vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)
                ?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
    }

    /** 主操作：播放/暂停/切歌 */
    fun click() = fire(VibrationEffect.EFFECT_CLICK, 14L, 120)

    /** 轻触：次级按钮、列表项、开关、徽章 */
    fun tap() = fire(VibrationEffect.EFFECT_TICK, 9L, 70)

    /** 滚动条刻度：连续触发，25ms 节流避免拖动时变成持续嗡鸣 */
    fun tick() {
        val now = android.os.SystemClock.uptimeMillis()
        if (now - lastTickAt < 25L) return
        lastTickAt = now
        fire(VibrationEffect.EFFECT_TICK, 6L, 50)
    }

    /**
     * @param predefined API 29+ 的系统预设波形（各厂商调过，手感比自己定时长好）
     * @param fallbackMs 低版本回退的振动时长
     * @param amplitude 低版本回退的强度 1..255
     */
    private fun fire(predefined: Int, fallbackMs: Long, amplitude: Int) {
        if (!enabled) return
        val v = vibrator ?: return
        if (!v.hasVibrator()) return
        runCatching {
            when {
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q ->
                    v.vibrate(VibrationEffect.createPredefined(predefined))
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.O ->
                    v.vibrate(VibrationEffect.createOneShot(fallbackMs, amplitude))
                else -> {
                    @Suppress("DEPRECATION")
                    v.vibrate(fallbackMs)
                }
            }
        }
    }
}
