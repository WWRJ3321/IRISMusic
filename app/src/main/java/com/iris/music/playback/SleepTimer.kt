package com.iris.music.playback

import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 睡眠定时器——权威计时在 Service 所在进程，UI 销毁不影响到期停播。
 *
 * 旧实现把 delay(totalMs) 放在 PlayerViewModel.viewModelScope：
 * ViewModel 随界面销毁取消协程，用户"设了定时、划掉界面"后定时器静默消失，
 * 音乐播一整夜。这里把计时挪到进程级单例（Service 与 ViewModel 同进程）。
 *
 * - endMs 落盘 SharedPreferences：进程被回收后服务被重新拉起时继续剩余计时。
 * - 到期广播给所有观察者：Service 暂停播放器，ViewModel 清 UI 状态。
 * - UI 倒计时展示走 endMs 墙钟，天然与这里同步。
 */
object SleepTimer {

    private const val PREFS = "sleep_timer"
    private const val KEY_END = "end_ms"
    private const val KEY_TOTAL = "total_ms"

    private val handler = Handler(Looper.getMainLooper())
    private var prefs: SharedPreferences? = null
    private var runnable: Runnable? = null

    /** 到期观察者：Service 注册"暂停播放器"，ViewModel 注册"清 UI 状态"。返回列表以便反注册。 */
    private val expireObservers = CopyOnWriteArrayList<() -> Unit>()

    fun addExpireObserver(obs: () -> Unit): () -> Unit {
        expireObservers.add(obs)
        return { expireObservers.remove(obs) }
    }

    /** 当前定时的结束墙钟毫秒；0 = 未设置。 */
    @Volatile
    var endMs: Long = 0L
        private set

    /** 本次定时的总时长（进度环用）。 */
    @Volatile
    var totalMs: Long = 0L
        private set

    /** Service onCreate：绑 prefs 并恢复未到期的定时。 */
    fun attach(context: Context) {
        if (prefs == null) {
            prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        }
        if (endMs == 0L) {
            val saved = prefs!!.getLong(KEY_END, 0L)
            if (saved > System.currentTimeMillis()) {
                totalMs = prefs!!.getLong(KEY_TOTAL, 0L)
                arm(saved)
            } else if (saved != 0L) clearPersisted()
        }
    }

    /** 设置定时（minutes<=0 视为取消），返回新的 endMs 墙钟。 */
    fun set(context: Context, minutes: Int): Long {
        if (prefs == null) {
            prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        }
        cancelAlarmOnly()
        if (minutes <= 0) {
            endMs = 0L
            totalMs = 0L
            clearPersisted()
            return 0L
        }
        totalMs = minutes * 60_000L
        val end = System.currentTimeMillis() + totalMs
        arm(end)
        prefs?.edit()?.putLong(KEY_END, end)?.putLong(KEY_TOTAL, totalMs)?.apply()
        return end
    }

    fun cancel() {
        cancelAlarmOnly()
        endMs = 0L
        totalMs = 0L
        clearPersisted()
    }

    private fun arm(end: Long) {
        endMs = end
        val r = Runnable {
            runnable = null
            endMs = 0L
            totalMs = 0L
            clearPersisted()
            expireObservers.forEach { runCatching { it() } }
        }
        runnable = r
        handler.postDelayed(r, (end - System.currentTimeMillis()).coerceAtLeast(0L))
    }

    private fun cancelAlarmOnly() {
        runnable?.let { handler.removeCallbacks(it) }
        runnable = null
    }

    private fun clearPersisted() {
        prefs?.edit()?.remove(KEY_END)?.remove(KEY_TOTAL)?.apply()
    }
}