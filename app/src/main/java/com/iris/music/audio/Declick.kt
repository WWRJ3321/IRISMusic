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

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * 开关去爆音（v5.0 r4）。
 *
 * 链内效果器原本是硬开关：`if (!enabled) return` 直通，开/关落在一个缓冲中间，
 * 输出波形在那一个采样上瞬间跳变——耳机里就是"啪"一声；重新打开时滤波器状态也是旧的。
 * 这里在切换的那几块缓冲里同时保留干声与湿声，按 30ms 线性淡入/淡出混合，过渡完回到零开销。
 *
 * 用法（音频线程，单线程）：
 * ```
 * if (declick.transitioning(enabled)) { declick.snapshot(input); process(force = true); declick.blend(...) }
 * else process(force = false)
 * ```
 */
internal class Declick(private val rampMs: Float = 30f) {
    private var wet = -1f
    private var dry = ByteArray(0)

    /** 是否处在开/关过渡中（首次调用直接落位，不做过渡） */
    fun transitioning(enabled: Boolean): Boolean {
        val target = if (enabled) 1f else 0f
        if (wet < 0f) { wet = target; return false }
        return wet != target
    }

    /** 处理前保存干声（不移动 src 的 position） */
    fun snapshot(src: ByteBuffer) {
        val n = src.remaining()
        if (dry.size < n) dry = ByteArray(n)
        val p = src.position()
        src.get(dry, 0, n)
        src.position(p)
    }

    /** 处理后：out[0, n) 为湿声，按帧把 wet 推向目标并与干声混合 */
    fun blend(out: ByteBuffer, n: Int, enabled: Boolean, sampleRate: Int, channels: Int, is16: Boolean) {
        val target = if (enabled) 1f else 0f
        val step = 1000f / (rampMs * sampleRate.coerceAtLeast(8000))
        val d = ByteBuffer.wrap(dry, 0, n).order(ByteOrder.nativeOrder())
        val bpf = channels * (if (is16) 2 else 4)
        var i = 0
        while (i + bpf <= n) {
            wet = if (wet < target) minOf(target, wet + step) else maxOf(target, wet - step)
            for (c in 0 until channels) {
                if (is16) {
                    val o = i + c * 2
                    val x = d.getShort(o).toFloat(); val y = out.getShort(o).toFloat()
                    out.putShort(o, (x + wet * (y - x)).toInt().coerceIn(-32768, 32767).toShort())
                } else {
                    val o = i + c * 4
                    val x = d.getFloat(o); val y = out.getFloat(o)
                    out.putFloat(o, x + wet * (y - x))
                }
            }
            i += bpf
        }
        if (i < n) wet = target
    }
}
