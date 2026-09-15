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
import android.os.Handler
import android.os.Looper
import androidx.media3.exoplayer.ExoPlayer
import kotlin.math.abs

/**
 * 音量渐变：曲目开头渐入、结尾渐出、手动暂停先渐出再真正暂停。
 *
 * 音量按「当前播放位置」实时算出来，而不是自己维护一条动画时间轴——
 * seek、切歌、单曲循环、跳到中段播放都会自然落在正确的音量上，
 * 不需要额外的状态同步，也不会出现动画和播放位置打架的情况。
 *
 * 关闭渐变时仍保留曲首 150ms 的快速渐入，用于消除解码首帧的爆音
 * （原先写在 MusicService 里的那段 anti-pop 逻辑，统一收到这里）。
 */
object FadeController {

    const val PREFS = "iris_prefs"
    const val KEY_ENABLED = "fade_enabled"
    const val KEY_FADE_MS = "fade_ms"

    const val DEFAULT_FADE_MS = 2000L
    const val MIN_FADE_MS = 500L
    const val MAX_FADE_MS = 5000L

    /** 渐入渐出总开关（设置项驱动） */
    @Volatile
    var enabled: Boolean = false

    /** 单次渐变时长（毫秒） */
    @Volatile
    var fadeMs: Long = DEFAULT_FADE_MS

    /** 40ms ≈ 25Hz，足够平滑，开销可忽略 */
    private const val TICK_MS = 40L

    /** 空闲（没在播放、也没有渐变在进行）时降频轮询 */
    private const val IDLE_TICK_MS = 250L

    /** 关闭渐变时保留的防爆音渐入时长 */
    private const val ANTI_POP_MS = 150f

    /**
     * 手动暂停/恢复的渐变时长。故意比曲目渐变短很多：
     * 按下暂停要等 2 秒才真的停会显得按键没反应，350ms 既柔和又不迟滞。
     */
    private const val GATE_MS = 350f

    private val handler = Handler(Looper.getMainLooper())
    private var player: ExoPlayer? = null

    /** 手动暂停/恢复的软开关：0=静音，1=全量。与播放位置无关，可与位置渐变叠加 */
    private var gate = 1f
    private var gateRising = true

    /** 渐出到 0 之后才真正调用 pause() */
    private var pausePending = false

    /** 服务可能在 UI 之前创建（通知栏/媒体键唤起），所以配置从 prefs 直接读一份 */
    fun init(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        enabled = prefs.getBoolean(KEY_ENABLED, false)
        fadeMs = prefs.getLong(KEY_FADE_MS, DEFAULT_FADE_MS).coerceIn(MIN_FADE_MS, MAX_FADE_MS)
    }

    /** attach/播放恢复的时刻（elapsedRealtime）。此后 ANTI_POP_MS 内强制从 0 渐入，
     *  消除进程重建后恢复播放时新 AudioTrack 首帧非零起点导致的爆音 */
    private var playStartStamp = 0L

    fun attach(p: ExoPlayer) {
        player = p
        gate = 1f
        gateRising = true
        pausePending = false
        // 保险：服务重建后若 ExoPlayer 携带 playWhenReady=true 自动开播（MediaSession
        // 恢复/前台服务续播），首帧会以满音量爆出。无论当时状态如何，attach 即打
        // 防爆音渐入点并先静音——150ms 内由 ticker 升回，听感是快速淡入而非爆音
        playStartStamp = android.os.SystemClock.elapsedRealtime()
        if (p.playWhenReady) p.volume = 0f
        handler.removeCallbacks(ticker)
        handler.post(ticker)
    }

    fun release() {
        handler.removeCallbacks(ticker)
        player = null
    }

    /** 带渐入的播放；从暂停恢复时从静音起升 */
    fun requestPlay() {
        val p = player ?: return
        pausePending = false
        gateRising = true
        // 起播时刻打点：无论从哪个位置起播（含进程重建后 resume），都做防爆音快速渐入
        playStartStamp = android.os.SystemClock.elapsedRealtime()
        if (enabled) {
            // 已经在播放时不要把 gate 打回 0，否则会听到一次音量凹陷
            if (!p.isPlaying) {
                gate = 0f
                p.volume = 0f
            }
        } else {
            gate = 1f
            // 渐变关闭也防爆音：非播放态起播先静音，由 antiPop 曲线 150ms 内升起
            // （否则首个 tick 前 AudioTrack 会以满音量吐出首帧）
            if (!p.isPlaying) p.volume = 0f
        }
        p.play()
        kick()
    }

    /** 渐出后暂停；渐变关闭或本来就没播放时立即暂停 */
    fun requestPause() {
        val p = player ?: return
        if (!enabled || !p.isPlaying) {
            resetVolume()
            p.pause()
            return
        }
        gateRising = false
        pausePending = true
        kick()
    }

    fun resetVolume(soft: Boolean = false) {
        gate = 1f
        gateRising = true
        pausePending = false
        if (soft) {
            // 软重置：闸门归位但音量不直接跳满——重新打防爆音渐入点，
            // 新曲目首帧/服务重建恢复首帧以 0 起步、150ms 内升起，无爆音
            playStartStamp = android.os.SystemClock.elapsedRealtime()
            player?.volume = 0f
        } else {
            player?.volume = 1f
        }
    }

    private fun kick() {
        handler.removeCallbacks(ticker)
        handler.post(ticker)
    }

    private val ticker = object : Runnable {
        override fun run() {
            val p = player ?: return
            var busy = false

            if (!enabled) {
                // 关闭状态下 gate 不参与计算，但要复位，避免开关切换时残留静音
                gate = 1f
                gateRising = true
                if (pausePending) {
                    pausePending = false
                    p.pause()
                }
            } else {
                val step = TICK_MS / GATE_MS
                if (gateRising && gate < 1f) {
                    gate = (gate + step).coerceAtMost(1f)
                    busy = true
                } else if (!gateRising && gate > 0f) {
                    gate = (gate - step).coerceAtLeast(0f)
                    busy = true
                }
                if (pausePending && gate <= 0f) {
                    pausePending = false
                    p.pause()
                }
            }

            val target = targetVolume(p)
            if (abs(p.volume - target) > 0.004f) p.volume = target
            if (p.isPlaying) busy = true

            handler.postDelayed(this, if (busy) TICK_MS else IDLE_TICK_MS)
        }
    }

    private fun targetVolume(p: ExoPlayer): Float {
        val pos = p.currentPosition.coerceAtLeast(0L).toFloat()
        // 防爆音渐入：从起播时刻算（而非播放位置）——服务重建后 resume 的位置
        // 通常已过 150ms，按位置算会跳过渐入直接满音量，新 AudioTrack 首帧爆音
        val antiPop = ((android.os.SystemClock.elapsedRealtime() - playStartStamp) / ANTI_POP_MS)
            .coerceIn(0f, 1f)
        if (!enabled) return antiPop

        val dur = p.duration
        // 短曲目上 2s 渐入 + 2s 渐出会让音量永远到不了满值，按时长上限收窄
        val f = if (dur > 0) fadeMs.coerceAtMost(dur / 3).coerceAtLeast(200L).toFloat()
                else fadeMs.toFloat().coerceAtLeast(1f)

        val fadeIn = (pos / f).coerceIn(0f, 1f)
        val fadeOut = if (dur > 0) ((dur - pos) / f).coerceIn(0f, 1f) else 1f
        return minOf(fadeIn, fadeOut, antiPop) * gate
    }
}
