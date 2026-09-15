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

package com.iris.music.playback

import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.TeeAudioProcessor
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.iris.music.audio.BassHaptics
import com.iris.music.audio.EqualizerController
import com.iris.music.audio.FadeController
import com.iris.music.audio.RangeEnhancer
import com.iris.music.audio.SilenceSkipper
import com.iris.music.audio.SpectrumAnalyzer
import com.iris.music.audio.VirtualBass
import com.iris.music.audio.VirtualSurround
import com.iris.music.data.ListenStats
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock

/**
 * 前台播放服务：持有 ExoPlayer 与 MediaSession。
 * UI 侧通过 MediaController 连接本服务进行控制，保证后台/通知栏播放。
 */
class MusicService : MediaSessionService() {

    private var mediaSession: MediaSession? = null

    /**
     * 听歌时长记账。
     *
     * 放在服务而不是 ViewModel 里：UI 划到后台、Activity 被销毁之后音乐还在放，
     * 那段时间的时长同样要算进报告。每 [TICK_MS] 结算一次而非按曲目结束一次性记账——
     * 跨零点的长曲那样会全记到前一天，进程被杀也会整段丢失。
     */
    private val tickHandler = Handler(Looper.getMainLooper())
    private var tickingSongId = -1L
    private var tickAnchorMs = 0L
    private val statsTick = object : Runnable {
        override fun run() {
            settleListenTime()
            tickHandler.postDelayed(this, TICK_MS)
        }
    }

    /**
     * 把"上次结算点到现在"这段计入当前曲目。
     *
     * 用 [SystemClock.elapsedRealtime] 而不是播放器位置差：拖动进度条会让位置跳变，
     * 用位置差算时长会把一次 seek 记成听了十分钟。
     */
    private fun settleListenTime() {
        val player = mediaSession?.player ?: return
        val playing = player.isPlaying
        val id = player.currentMediaItem?.mediaId?.toLongOrNull() ?: -1L
        val now = SystemClock.elapsedRealtime()

        if (tickingSongId >= 0 && tickAnchorMs > 0L) {
            val delta = now - tickAnchorMs
            // 上限一个结算周期的两倍：设备睡眠期间 Handler 不跑，醒来后第一次结算
            // 会拿到一段巨大的 delta，那段时间其实没有在放。
            if (delta in 1..(TICK_MS * 2)) ListenStats.add(tickingSongId, delta)
        }

        // 曲目变了或停了：先结清旧的（上面已做），再决定新的锚点
        tickingSongId = if (playing) id else -1L
        tickAnchorMs = if (playing) now else 0L
        // 停下来是个自然的落盘点（用户可能马上就去看报告）。异步写，本方法在主线程
        if (!playing) ListenStats.flushAsync()
    }

    /**
     * 在音频链末端插一个 TeeAudioProcessor，把解码后的 PCM 旁路给频谱分析器。
     * 走 AudioProcessor 而非 audiofx.Visualizer，因此不需要 RECORD_AUDIO 权限。
     * 分析器默认关闭，handleBuffer 会直接返回，对播放无额外开销。
     */
    @OptIn(UnstableApi::class)
    private inner class SpectrumRenderersFactory(context: Context) : DefaultRenderersFactory(context) {
        override fun buildAudioSink(
            context: Context,
            enableFloatOutput: Boolean,
            enableAudioTrackPlaybackParams: Boolean
        ): AudioSink = DefaultAudioSink.Builder(context)
            .setEnableFloatOutput(enableFloatOutput)
            .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
            // DSP 链顺序：动态范围增强 → 虚拟环绕 → 虚拟低音 → 频谱旁路。
            // RangeEnhancer 先抬响度会让 VirtualBass 的低频检测跟着抬高，
            // 两者对响度的影响会互相叠加；让 VirtualBass 看到原始低频
            // 更接近"补低音"而非"补已被增强的低音"。VirtualSurround 基于
            // 原始 L/R 差分做声场展宽，放在 VirtualBass 前（低音谐波是
            // 单声道注入，若先注入再展宽会把谐波也摊到两侧、破坏居中）。
            // 频谱放最后，UI 显示与低音震动看到的都是处理后的实际输出。
            .setAudioProcessors(arrayOf<AudioProcessor>(
                RangeEnhancer,
                VirtualSurround,
                VirtualBass,
                TeeAudioProcessor(SpectrumAnalyzer)
            ))
            .build()
    }

    /**
     * 拦截播放/暂停，交给 [FadeController] 做渐入渐出。
     * 包一层 ForwardingPlayer 而不是只改 UI 调用点，是为了让通知栏、媒体键、
     * 蓝牙耳机等所有入口都走同一条渐变路径。
     */
    @OptIn(UnstableApi::class)
    private class FadingPlayer(player: Player) : ForwardingPlayer(player) {
        override fun play() = FadeController.requestPlay()
        override fun pause() = FadeController.requestPause()
        override fun setPlayWhenReady(playWhenReady: Boolean) {
            if (playWhenReady) FadeController.requestPlay() else FadeController.requestPause()
        }
    }

    override fun onCreate() {
        super.onCreate()

        val player = ExoPlayer.Builder(this)
            .setRenderersFactory(SpectrumRenderersFactory(this))
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .setUsage(C.USAGE_MEDIA)
                    .build(),
                /* handleAudioFocus = */ true
            )
            // 拔耳机/断开蓝牙时自动暂停
            .setHandleAudioBecomingNoisy(true)
            .build()

        // 音量渐变：曲首渐入、曲尾渐出、暂停先渐出。
        // 关闭渐变时仍保留 150ms 防爆音渐入（原来写在这里的 fadeHandler 逻辑已收进控制器）。
        FadeController.init(this)
        FadeController.attach(player)

        // 无声略过 / 低音震动：旁路电平读 SpectrumAnalyzer 的 rms/bassLevel，
        // 两者的轮询都在服务进程内自转，与 UI 生命周期无关
        SilenceSkipper.init(this)
        SilenceSkipper.attach(player)
        BassHaptics.init(this)
        // V2.2 DSP 处理器：恢复上次的开关/强度（object 挂在音频链上，随时可改）
        VirtualBass.init(getSharedPreferences("iris_prefs", Context.MODE_PRIVATE))
        RangeEnhancer.init(getSharedPreferences("iris_prefs", Context.MODE_PRIVATE))
        VirtualSurround.init(getSharedPreferences("iris_prefs", Context.MODE_PRIVATE))

        player.addListener(object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                // 自动切歌不经过 play()，需要在这里重置音量闸门，否则新曲目会继承上一首的渐出值。
                // soft：不直接跳满音量——首帧从 0 快速渐入，消除切歌爆音
                FadeController.resetVolume(soft = true)
            }
        })

        // 均衡器挂到播放器的音频会话上，并恢复上次的配置
        EqualizerController.init(this)
        EqualizerController.attach(player.audioSessionId)

        // 听歌时长统计：文件在服务进程里初始化（UI 进程那边也 init 一次，两边同路径）
        ListenStats.init(this)

        mediaSession = MediaSession.Builder(this, FadingPlayer(player)).build()

        // 桌面悬浮歌词：常驻服务里初始化，UI 退到后台也能跟随
        FloatingLyric.init(this, player)

        // 播放/暂停与切歌都要立刻结算，否则一段最多 TICK_MS 的零头会记到下一首头上
        player.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) = settleListenTime()
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) = settleListenTime()
        })
        tickHandler.postDelayed(statsTick, TICK_MS)
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? =
        mediaSession

    override fun onDestroy() {
        // 服务结束前收起悬浮歌词窗
        FloatingLyric.release()
        // 服务结束前把最后一段听歌时长结清并落盘
        tickHandler.removeCallbacks(statsTick)
        settleListenTime()
        ListenStats.flush()
        EqualizerController.release()
        FadeController.release()
        SilenceSkipper.setEnabled(false)
        BassHaptics.setEnabled(false)
        mediaSession?.let { session ->
            session.player.release()
            session.release()
        }
        mediaSession = null
        super.onDestroy()
    }

    private companion object {
        /** 听歌时长结算周期。10 秒：足够细（丢失上限 10 秒）又不至于频繁唤醒 */
        const val TICK_MS = 10_000L
    }
}
