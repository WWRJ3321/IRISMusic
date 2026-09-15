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

package com.iris.music.data

import android.content.Context
import java.io.File
import java.util.Calendar
import java.util.TimeZone
import java.util.concurrent.Executors

/** 一条记录：某天某首歌被听了多久 */
data class ListenEntry(val day: Long, val songId: Long, val ms: Long)

/**
 * 报告的时间范围。都是自然周期（本周 = 周一至今），不是"最近 7 天"，
 * 这样才和热力图里以周一起始的列对得上。
 */
enum class StatRange(val label: String, val title: String) {
    DAY("日", "今天"),
    WEEK("周", "本周"),
    MONTH("月", "本月"),
    YEAR("年", "今年"),
    ALL("全部", "累计")
}

/**
 * 听歌时间序列：谁、哪天、听了多久。
 *
 * 为什么不并进 [PlayHistory]：那边是"每首歌一个累计值"，一条 SharedPreferences
 * 字符串就够。报告要的是按天分桶的明细（热力图、日/月/周/年 汇总全从这儿来），
 * 一年下来上万条，每次切歌把整串重新序列化写回 prefs 不现实。
 * 所以改成追加式文本文件：写只在文件尾 append，读的时候再聚合。
 *
 * 行格式：`epochDay,songId,ms`
 */
object ListenStats {

    private const val FILE_NAME = "listen_stats.csv"

    /** 缓冲攒够这么多毫秒才落盘，避免每个计时周期都碰一次磁盘 */
    private const val FLUSH_THRESHOLD_MS = 30_000L

    /** 行数超过它就压缩一次：追加式日志攒久了会有大量同天同曲的碎行 */
    private const val COMPACT_LINES = 4000

    /** 保留天数，压缩时丢掉更早的记录（约两年，热力图最多也只画一年） */
    private const val KEEP_DAYS = 800

    private const val DAY_MS = 86_400_000L

    private var file: File? = null

    private val lock = Any()

    /**
     * 落盘线程。单线程：写入必须串行，否则两次 append 可能交错出半行。
     * 用它而不是协程作用域——本对象也被服务进程用，那边没有 viewModelScope。
     */
    private val ioExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "iris-listen-stats").apply { isDaemon = true }
    }

    /** 未落盘的缓冲：day -> songId -> ms */
    private val pending = HashMap<Long, HashMap<Long, Long>>()
    private var pendingMs = 0L

    fun init(context: Context) {
        if (file == null) {
            file = File(context.applicationContext.filesDir, FILE_NAME)
        }
    }

    /**
     * 一次性迁移：把旧版 [PlayHistory] 的累计时长折算成明细。
     *
     * 老版本只记了"每首歌总共听了多久"和"最后一次播放时间"，没有按天的分布。
     * 这里把整份累计时长记到该曲最后播放的那天——不精确，但比让升级用户
     * 打开报告看到一片空白要好，而且这些时长确实发生过。
     *
     * 用"明细文件还不存在"作为一次性的判据，所以只在升级后第一次调用时生效。
     */
    fun seedFromLegacy(totals: Map<Long, Long>, lastPlayed: Map<Long, Long>) {
        val f = file ?: return
        if (totals.isEmpty()) return
        synchronized(lock) {
            if (f.exists()) return
            val today = todayEpochDay()
            val sb = StringBuilder()
            totals.forEach { (id, ms) ->
                if (ms <= 0L) return@forEach
                val ts = lastPlayed[id] ?: return@forEach
                val day = epochDayOf(ts).coerceAtMost(today)
                sb.append(day).append(',').append(id).append(',').append(ms).append('\n')
            }
            if (sb.isNotEmpty()) runCatching { f.writeText(sb.toString()) }
        }
    }

    // ==================== 写入 ====================

    /**
     * 累计一段"确实在播放"的时长。由播放服务每几秒结算一次调用，
     * 不是按曲目结束一次性记账——那样跨零点的长曲会算错天，进程被杀也会整段丢失。
     */
    fun add(songId: Long, ms: Long) {
        if (songId < 0L || ms <= 0L || file == null) return
        var due = false
        synchronized(lock) {
            val bySong = pending.getOrPut(todayEpochDay()) { HashMap() }
            bySong[songId] = (bySong[songId] ?: 0L) + ms
            pendingMs += ms
            due = pendingMs >= FLUSH_THRESHOLD_MS
        }
        // 记账是在主线程的 Handler 里跑的，落盘绝不能在这里同步做。
        // 交给单线程执行器，顺序仍然有保证。
        if (due) ioExecutor.execute { flush() }
    }

    fun flush() {
        synchronized(lock) { flushLocked() }
    }

    /** 落盘（异步）。主线程调用点用这个，避免在 UI 线程上做文件 IO */
    fun flushAsync() {
        ioExecutor.execute { flush() }
    }

    private fun flushLocked() {
        val f = file ?: return
        if (pending.isEmpty()) return
        val sb = StringBuilder()
        pending.forEach { (day, bySong) ->
            bySong.forEach { (id, ms) ->
                sb.append(day).append(',').append(id).append(',').append(ms).append('\n')
            }
        }
        runCatching { f.appendText(sb.toString()) }
        pending.clear()
        pendingMs = 0L
    }

    // ==================== 读取 ====================

    /**
     * 读出全部记录（已按 天+歌 聚合）。有 IO 与解析，调用方请放到后台线程。
     *
     * 整段持锁：中途如果计时器又 append 了几行，压缩时的整文件重写会把它们吃掉。
     */
    fun load(): List<ListenEntry> {
        val f = file ?: return emptyList()
        synchronized(lock) {
            flushLocked()
            if (!f.exists()) return emptyList()

            val agg = HashMap<Long, HashMap<Long, Long>>()
            var lines = 0
            runCatching {
                f.forEachLine { line ->
                    lines++
                    val c1 = line.indexOf(',')
                    if (c1 <= 0) return@forEachLine
                    val c2 = line.indexOf(',', c1 + 1)
                    if (c2 <= c1) return@forEachLine
                    val day = line.substring(0, c1).toLongOrNull() ?: return@forEachLine
                    val id = line.substring(c1 + 1, c2).toLongOrNull() ?: return@forEachLine
                    val ms = line.substring(c2 + 1).trim().toLongOrNull() ?: return@forEachLine
                    if (ms <= 0L) return@forEachLine
                    val bySong = agg.getOrPut(day) { HashMap() }
                    bySong[id] = (bySong[id] ?: 0L) + ms
                }
            }

            val cutoff = todayEpochDay() - KEEP_DAYS
            val out = ArrayList<ListenEntry>(lines.coerceAtMost(4096))
            agg.forEach { (day, bySong) ->
                if (day < cutoff) return@forEach
                bySong.forEach { (id, ms) -> out.add(ListenEntry(day, id, ms)) }
            }

            if (lines > COMPACT_LINES && lines > out.size) {
                runCatching {
                    val sb = StringBuilder()
                    out.forEach {
                        sb.append(it.day).append(',').append(it.songId).append(',').append(it.ms).append('\n')
                    }
                    f.writeText(sb.toString())
                }
            }
            return out
        }
    }

    // ==================== 日期换算 ====================

    /** 今天是第几天（本地时区，1970-01-01 = 0） */
    fun todayEpochDay(): Long = epochDayOf(System.currentTimeMillis())

    /**
     * 时间戳 → 本地日期序号。
     * 不用 java.time：minSdk 24 拿不到，项目也没开脱糖。
     */
    fun epochDayOf(timeMillis: Long): Long {
        val offset = TimeZone.getDefault().getOffset(timeMillis).toLong()
        return floorDiv(timeMillis + offset, DAY_MS)
    }

    /**
     * 日期序号 → 日历。刻意用 UTC 日历读年月日：序号里已经算进本地偏移，
     * 再用本地日历解释一次会把日期整体平移（东八区会退回前一天）。
     */
    fun calendarOf(day: Long): Calendar =
        Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
            firstDayOfWeek = Calendar.MONDAY
            timeInMillis = day * DAY_MS
        }

    /** 周一 = 0 … 周日 = 6 */
    fun weekdayIndex(day: Long): Int = (calendarOf(day).get(Calendar.DAY_OF_WEEK) + 5) % 7

    /**
     * 范围的起始日（含）。所有范围都是"某天到今天"的连续区间，
     * 于是筛选只要一次区间比较，不用给每条记录都造一个 Calendar。
     */
    fun rangeStart(range: StatRange, today: Long): Long = when (range) {
        StatRange.DAY -> today
        StatRange.WEEK -> today - weekdayIndex(today)
        StatRange.MONTH -> today - (calendarOf(today).get(Calendar.DAY_OF_MONTH) - 1)
        StatRange.YEAR -> today - (calendarOf(today).get(Calendar.DAY_OF_YEAR) - 1)
        StatRange.ALL -> Long.MIN_VALUE
    }

    private fun floorDiv(a: Long, b: Long): Long {
        var q = a / b
        if (a % b != 0L && (a xor b) < 0L) q--
        return q
    }
}
