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
import android.app.PendingIntent
import android.content.Intent
import com.iris.music.MainActivity
import com.iris.music.audio.BassHaptics
import com.iris.music.audio.EqualizerController
import com.iris.music.audio.EqProcessor
import com.iris.music.audio.FadeController
import com.iris.music.audio.RangeEnhancer
import com.iris.music.audio.SafeLimiter
import com.iris.music.audio.SilenceSkipper
import com.iris.music.audio.SpatialWide
import com.iris.music.audio.SpectrumAnalyzer
import com.iris.music.audio.TrackGain
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
    private var sleepUnsub: (() -> Unit)? = null

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
            // DSP 链顺序：曲间响度均衡 → 动态范围增强 → 虚拟环绕 → 宽场环绕 → 虚拟低音 → 十段均衡 → 防失真限幅 → 频谱旁路。
            // TrackGain 放最前：测原始信号，校正后电平让后级（含频谱/震动）看到真实输出。
            // RangeEnhancer 先抬响度会让 VirtualBass 的低频检测跟着抬高，
            // 两者对响度的影响会互相叠加；让 VirtualBass 看到原始低频
            // 更接近"补低音"而非"补已被增强的低音"。VirtualSurround 基于
            // 原始 L/R 差分做声场展宽，放在 VirtualBass 前（低音谐波是
            // 单声道注入，若先注入再展宽会把谐波也摊到两侧、破坏居中）。
            // SpatialWide 紧跟 VirtualSurround：多频段展宽 + 早期反射叠在展宽后的
            // 声场上，同样必须在 VirtualBass 之前——低音谐波居中注入才不被摊向两侧。
            // SafeLimiter 放最后一道处理：前级所有增强叠加后可能顶过 0dBFS，
            // 由它统一把峰值压回安全线，杜绝硬削波（撕裂/破音）。
            // 频谱放限幅之后，UI 显示与低音震动看到的都是处理后的实际输出。
            .setAudioProcessors(arrayOf<AudioProcessor>(
                TrackGain,
                RangeEnhancer,
                VirtualSurround,
                SpatialWide,
                VirtualBass,
                // EQ 必须在限幅之前：v4.6.8 及之前用的是系统 audiofx.Equalizer，
                // 挂在 AudioTrack 输出段、排在限幅之后，用户抬频段等于绕过限幅
                // 直接在系统混音层削波。现在 EQ 是链内 biquad，增益溢出先被
                // 自动预增益抵消、残余峰值再由限幅兜底。
                EqProcessor,
                SafeLimiter,
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
        // 锁屏播放期间持有唤醒锁（WAKE_LOCK 权限的消费方，否则 Doze 下 CPU 休眠会断音）
        player.setWakeMode(C.WAKE_MODE_LOCAL)

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
        SpatialWide.init(getSharedPreferences("iris_prefs", Context.MODE_PRIVATE))
        SafeLimiter.init(getSharedPreferences("iris_prefs", Context.MODE_PRIVATE))
        TrackGain.init(getSharedPreferences("iris_prefs", Context.MODE_PRIVATE))
        player.addListener(object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                // 自动切歌不经过 play()，需要在这里重置音量闸门，否则新曲目会继承上一首的渐出值。
                // soft：不直接跳满音量——首帧从 0 快速渐入，消除切歌爆音
                FadeController.resetVolume(soft = true)
                // 响度均衡：切歌后把当前曲路径交给链首处理器（查库/开始测量）。
                // path 必须与 Song.filePath 同一口径（extras 里的 file_path），
                // mediaId 是 stableId hash、content uri 的 path 都对不上 store key。
                val path = mediaItem?.mediaMetadata?.extras?.getString("file_path")
                    ?: mediaItem?.localConfiguration?.uri?.takeIf { it.scheme == "file" }?.path
                TrackGain.setCurrentSong(path)
            }
        })

        // 均衡器挂到播放器的音频会话上，并恢复上次的配置
        EqualizerController.init(this)
        EqualizerController.attach(player.audioSessionId)

        // 听歌时长统计：文件在服务进程里初始化（UI 进程那边也 init 一次，两边同路径）
        ListenStats.init(this)

        val sessionActivityPendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                // 复用已有实例回到前台，而不是新起一个 Activity
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        mediaSession = MediaSession.Builder(this, FadingPlayer(player))
            .setSessionActivity(sessionActivityPendingIntent)
            // 通知栏/锁屏/车机封面：内嵌封面无法按 URI 被系统解出，需主动提供 Bitmap
            .setBitmapLoader(ArtworkBitmapLoader.get(this))
            .build()

        // 睡眠定时器：计时权威在本进程，UI 销毁不影响；到期只暂停播放器
        SleepTimer.attach(this)
        sleepUnsub = SleepTimer.addExpireObserver { runCatching { player.pause() } }
        // 桌面悬浮歌词：常驻服务里初始化，UI 退到后台也能跟随
        FloatingLyric.init(this, player)
        // 封面加载器的磁盘缓存在服务侧也要就绪：媒体键起播时 UI 可能从未打开过
        com.iris.music.ui.ArtworkLoader.init(this)

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
        // 反注册到期观察者，避免旧 player 引用留在进程级单例里
        sleepUnsub?.let { it() }; sleepUnsub = null
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
