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

    /**
     * 探测冷却：一次 seek 之后要等音频管线真正解码出新位置的 PCM，
     * 才能再读 rms 做下一次判定。没有这个冷却的话，seek 后的几拍读到的
     * 还是旧缓冲（依然是静音），于是每 50ms 又跳 1 秒——一秒能跳出去 20 秒。
     */
    private const val PROBE_COOLDOWN_MS = 250L

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

    /** 探测 seek 之后的静默期终点（SystemClock.uptimeMillis 时间轴） */
    private var probeCooldownUntil = 0L

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
        probeCooldownUntil = 0L
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

            // 判定窗口：曲首只看前 MAX_PROTECT_MS，曲尾只看最后 TAIL_WINDOW_MS。
            // 两段都结束后本首歌已无事可做，降到 IDLE_POLL_MS 只为感知切歌。
            var busy = false

            if (player.isPlaying && item != null) {
                val now = android.os.SystemClock.uptimeMillis()
                val pos = player.currentPosition
                val dur = player.duration
                // 暂停/解码中断时 rms 会冻结在最后一帧，必须用 isFresh 过滤，
                // 否则停掉的旁路数据会被当成"一直静音"而触发误跳。
                val silent = SpectrumAnalyzer.isFresh() && SpectrumAnalyzer.rms < SILENCE_RMS

                // ---- 曲首：累计静音够长就步进探测，出声即停 ----
                if (!headDone) {
                    if (pos > MAX_PROTECT_MS) {
                        headDone = true // 出发晚了，放弃曲首判定
                    } else {
                        busy = true
                        if (now < probeCooldownUntil) {
                            // 冷却中：上一跳的 PCM 还没解出来，这一拍不判定
                        } else if (silent) {
                            silentMsAtHead += POLL_MS
                            if (silentMsAtHead >= MIN_HEAD_MS) {
                                player.seekTo((pos + PROBE_STEP_MS).coerceAtMost(MAX_PROTECT_MS))
                                probeCooldownUntil = now + PROBE_COOLDOWN_MS
                            }
                        } else {
                            headDone = true // 出声了：定格
                        }
                    }
                }

                // ---- 曲尾 ----
                if (!tailDone && dur > 0) {
                    val remaining = dur - pos
                    if (remaining <= TAIL_WINDOW_MS) {
                        busy = true
                        if (silent && remaining > TAIL_MARGIN_MS) {
                            tailDone = true
                            // 不能 seekToNextMediaItem()——它会绕过播放器的循环模式：
                            // 单曲循环下也会被切到下一首（用户反馈"循环播放没用"的根因）。
                            // 直接跳到本曲末尾，让 ExoPlayer 按 repeatMode 自行衔接：
                            // ONE → 重播本曲；ALL → 正常轮循环；OFF → 自动前进后被停止。
                            player.seekTo(player.currentMediaItemIndex, dur)
                        }
                    } else {
                        tailDone = false
                    }
                }
            }
            // 不在判定窗口内（歌曲中段/暂停）时空转没有意义，直接降频
            handler.postDelayed(this, if (busy) POLL_MS else IDLE_POLL_MS)
        }
    }

    /** 判定窗口内的轮询间隔：要压住 50ms 级的探测精度 */
    private const val POLL_MS = 50L

    /**
     * 窗口外的轮询间隔。歌曲中段既不判曲首也不判曲尾，
     * 50ms 空转纯属浪费主线程唤醒，降到 500ms 只用来感知切歌/进入曲尾窗口。
     */
    private const val IDLE_POLL_MS = 500L

    /** 曲首探测步长：每跳一步等一个冷却周期听一拍，出声即停 */
    private const val PROBE_STEP_MS = 1_000L
}
