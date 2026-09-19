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

/**
 * 无声略过（beta）：自动跳过歌曲开头/结尾没有声音的部分。
 *
 * 实现是"边播边听"，不是解码预扫：
 * - 播放中每 [POLL_MS] 读一次 [SpectrumAnalyzer.rms]
 * - 曲首静音：静音累计超过 [MIN_HEAD_MS] 后立刻向前探测式 seek——
 *   但探测需要时间，这里采用更简单可靠的策略：累计静音够了就直接
 *   seek 到 min(静音段估计终点, MAX_PROTECT_MS)。静音段终点在出声那拍
 *   已经过去，所以实际行为是：一直静音就每秒往前跳一大步，出声即停。
 * - 曲尾静音：总时长已知时，最后 [TAIL_WINDOW_MS] 若持续静音 → 提前切下一首。
 *
 * 为什么不做预扫：整曲解码一遍要几秒到十几秒（FLAC 更久），切歌时等不了；
 * 而且很多歌前奏静音不足 2 秒，本来也不值得跳。
 */
object SilenceSkipper {

    const val KEY_ENABLED = "silence_skip_enabled"

    @Volatile
    var enabled: Boolean = false
        private set

    /** rms 低于此值视为静音（16-bit 满幅归一化后的线性值，约 -46dB） */
    private const val SILENCE_RMS = 0.005f

    /** 曲首静音至少这么长才跳；不足时按"前奏留白"处理，属于编曲的一部分 */
    private const val MIN_HEAD_MS = 2_500L

    /** 曲尾检测窗口：最后这么久是静音就提前切 */
    private const val TAIL_WINDOW_MS = 3_000L

    /** 曲尾切换再留 400ms 余量，避免把最后一点混响尾掐掉 */
    private const val TAIL_MARGIN_MS = 400L

    /** 单首最长保护：超过 20 秒不再判静音（防止纯音乐/白噪音被误跳） */
    private const val MAX_PROTECT_MS = 20_000L

    fun init(context: Context) {
        enabled = context.getSharedPreferences("iris_prefs", Context.MODE_PRIVATE)
            .getBoolean(KEY_ENABLED, false)
        refreshSidechain()
    }

    fun setEnabled(value: Boolean) {
        enabled = value
        refreshSidechain()
        // 重新开启时恢复轮询（poll 里 enabled=false 会自杀退出）
        if (value) {
            handler.removeCallbacks(poll)
            resetState()
            handler.post(poll)
        }
    }

    private fun refreshSidechain() {
        SpectrumAnalyzer.sidechainEnabled = enabled || BassHaptics.enabled
    }

    private val handler = Handler(Looper.getMainLooper())

    // ---- 每首歌重置的运行态（主线程访问） ----
    private var lastItemId: String? = null
    private var silentMsAtHead = 0L
    private var headDone = false
    private var tailDone = false

    /** attach 的播放器引用（主线程） */
    private var playerRef: androidx.media3.common.Player? = null

    fun attach(player: androidx.media3.common.Player) {
        playerRef = player
        handler.removeCallbacks(poll)
        resetState()
        if (enabled) handler.post(poll)
    }

    private fun resetState() {
        silentMsAtHead = 0L
        headDone = false
        tailDone = false
    }

    private val poll = object : Runnable {
        override fun run() {
            val player = playerRef ?: return
            if (!enabled) { resetState(); return }

            val item = player.currentMediaItem
            if (item?.mediaId != lastItemId) {
                lastItemId = item?.mediaId
                resetState()
            }

            if (player.isPlaying && item != null) {
                val pos = player.currentPosition
                val dur = player.duration
                val rms = SpectrumAnalyzer.rms
                val silent = rms < SILENCE_RMS

                // ---- 曲首：累计静音够长就开始步进探测，出声即停 ----
                // 每次往前跳 1 秒：跳过头了（出声）就立刻停在那里，
                // 误差最多一个探测步长 + 解码起播延迟。
                if (!headDone) {
                    if (pos > MAX_PROTECT_MS) {
                        headDone = true // 出发晚了，放弃曲首判定
                    } else if (silent) {
                        silentMsAtHead += POLL_MS
                        if (silentMsAtHead >= MIN_HEAD_MS) {
                            player.seekTo(pos + PROBE_STEP_MS)
                        }
                    } else {
                        headDone = true // 出声了（含首次就出声/跳过头）：定格
                    }
                }

                // ---- 曲尾 ----
                if (!tailDone && dur > 0) {
                    val remaining = dur - pos
                    if (remaining < TAIL_WINDOW_MS && silent && remaining > TAIL_MARGIN_MS) {
                        tailDone = true
                        player.seekToNextMediaItem()
                    } else if (remaining > TAIL_WINDOW_MS) {
                        tailDone = false
                    }
                }
            }
            handler.postDelayed(this, POLL_MS)
        }
    }

    private const val POLL_MS = 50L

    /** 曲首探测步长：每跳一步停 200ms 听一拍，出声即停 */
    private const val PROBE_STEP_MS = 1_000L
}
