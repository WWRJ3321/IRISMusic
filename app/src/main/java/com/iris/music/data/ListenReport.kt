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

import java.util.Calendar

/** 排行榜里的一行 */
data class SongStat(
    val songId: Long,
    val title: String,
    val artist: String,
    val filePath: String,
    val ms: Long,
    /** 估算播放次数：时长 ÷ 曲长。没有逐次记录，所以只能是约数 */
    val plays: Int
)

/** 热力图的一格 */
data class HeatCell(
    val day: Long,
    val ms: Long,
    /** 0 = 无记录，1..4 由浅到深 */
    val level: Int,
    /** 是否是未来的日期（当周尾部留白） */
    val future: Boolean
)

/** 热力图上方的月份标签：落在第 [weekIndex] 列 */
data class HeatMonthLabel(val weekIndex: Int, val label: String)

data class ListenReport(
    val range: StatRange = StatRange.WEEK,
    /** 选定范围内的总时长 */
    val totalMs: Long = 0L,
    /** 有记录的天数 */
    val activeDays: Int = 0,
    /** 听过的不同歌曲数 */
    val songCount: Int = 0,
    /** 日均（按有记录的天算，不含空白天） */
    val avgPerActiveDayMs: Long = 0L,
    /** 范围内最长的一天 */
    val peakDayMs: Long = 0L,
    val peakDayLabel: String = "",
    /** 单曲排行（按时长降序） */
    val topSongs: List<SongStat> = emptyList(),
    /** 热力图：按列（周）从旧到新，每列 7 格（周一→周日） */
    val heatWeeks: List<List<HeatCell>> = emptyList(),
    val heatMonths: List<HeatMonthLabel> = emptyList(),
    /** 全部历史累计（与范围无关，显示在标题下） */
    val allTimeMs: Long = 0L,
    val loading: Boolean = true
)

/**
 * 把 [ListenStats] 的明细聚合成报告。
 *
 * 纯函数，没有 IO：调用方在后台线程读完 [ListenStats.load] 再传进来，
 * 于是切换日/周/月/年只是内存里重算一遍，不用重新读文件。
 */
object ListenReportBuilder {

    /** 热力图跨度：52 周 + 当周 */
    const val HEAT_WEEKS = 53

    fun build(
        entries: List<ListenEntry>,
        songs: List<Song>,
        range: StatRange,
        today: Long = ListenStats.todayEpochDay()
    ): ListenReport {
        val start = ListenStats.rangeStart(range, today)

        var totalMs = 0L
        var allTimeMs = 0L
        val perDay = HashMap<Long, Long>()
        val perSong = HashMap<Long, Long>()
        val daysInRange = HashSet<Long>()

        entries.forEach { e ->
            allTimeMs += e.ms
            perDay[e.day] = (perDay[e.day] ?: 0L) + e.ms
            if (e.day >= start) {
                totalMs += e.ms
                perSong[e.songId] = (perSong[e.songId] ?: 0L) + e.ms
                daysInRange.add(e.day)
            }
        }

        val songById = songs.associateBy { it.id }
        val top = perSong.entries
            .sortedByDescending { it.value }
            .take(100)
            .map { (id, ms) ->
                val s = songById[id]
                SongStat(
                    songId = id,
                    title = s?.title ?: "已移除的歌曲",
                    artist = s?.artist ?: "未知",
                    filePath = s?.filePath ?: "",
                    ms = ms,
                    plays = if (s != null && s.durationMs > 0) {
                        (ms.toDouble() / s.durationMs).let { if (it < 1.0) 1 else Math.round(it).toInt() }
                    } else 0
                )
            }

        val peak = perDay.entries.filter { it.key >= start }.maxByOrNull { it.value }

        // 热力图：从"包含起点那周的周一"开始，铺满整周，最后一列是本周
        val heatStart = today - ListenStats.weekdayIndex(today) - (HEAT_WEEKS - 1) * 7L
        val heatCells = ArrayList<List<HeatCell>>(HEAT_WEEKS)
        // 分档参考值不能直接用最高值：偶尔一天听了 8 小时，会把其余每天半小时的日子
        // 全压到最浅一档，整张图看不出差别。取有记录日的 80 分位当"深色起点"，
        // 超过它的都算最深，图的层次由日常水平决定而不是一个离群点。
        val heatRef = run {
            val active = (0 until HEAT_WEEKS * 7)
                .mapNotNull { perDay[heatStart + it]?.takeIf { v -> v > 0L } }
                .sorted()
            if (active.isEmpty()) 0L
            else active[((active.size - 1) * 0.8f).toInt()]
        }

        for (w in 0 until HEAT_WEEKS) {
            val col = ArrayList<HeatCell>(7)
            for (d in 0 until 7) {
                val day = heatStart + w * 7L + d
                val ms = perDay[day] ?: 0L
                col.add(
                    HeatCell(
                        day = day,
                        ms = ms,
                        level = levelOf(ms, heatRef),
                        future = day > today
                    )
                )
            }
            heatCells.add(col)
        }

        return ListenReport(
            range = range,
            totalMs = totalMs,
            activeDays = daysInRange.size,
            songCount = perSong.size,
            avgPerActiveDayMs = if (daysInRange.isEmpty()) 0L else totalMs / daysInRange.size,
            peakDayMs = peak?.value ?: 0L,
            peakDayLabel = peak?.key?.let { dayLabel(it) } ?: "",
            topSongs = top,
            heatWeeks = heatCells,
            heatMonths = monthLabels(heatStart, HEAT_WEEKS),
            allTimeMs = allTimeMs,
            loading = false
        )
    }

    /**
     * 分档。按参考值的比例切四档而不是固定时长阈值：
     * 每天听 20 分钟的人和每天听 5 小时的人都该看到有层次的图，
     * 写死"1 小时 = 最深"会让前者整片浅、后者整片深。
     *
     * [refMs] 是"算作最深一档"的门槛（调用方给的是 80 分位），超过它一律最深。
     */
    private fun levelOf(ms: Long, refMs: Long): Int {
        if (ms <= 0L) return 0
        if (refMs <= 0L) return 1
        val r = ms.toFloat() / refMs
        return when {
            r <= 0.25f -> 1
            r <= 0.5f -> 2
            r <= 0.75f -> 3
            else -> 4
        }
    }

    /** 每个月第一次出现的那一列打一个标签；首列所在月若只剩几天就跳过，避免和第二个标签挤在一起 */
    private fun monthLabels(startDay: Long, weeks: Int): List<HeatMonthLabel> {
        val out = ArrayList<HeatMonthLabel>()
        var lastMonth = -1
        for (w in 0 until weeks) {
            val cal = ListenStats.calendarOf(startDay + w * 7L)
            val month = cal.get(Calendar.MONTH)
            if (month != lastMonth) {
                lastMonth = month
                // 该月的第一列若已经过了 20 号，说明这列几乎属于上个月，标签留到下一列
                if (cal.get(Calendar.DAY_OF_MONTH) <= 21) {
                    out.add(HeatMonthLabel(w, "${month + 1}月"))
                }
            }
        }
        return out
    }

    fun dayLabel(day: Long): String {
        val cal = ListenStats.calendarOf(day)
        return "${cal.get(Calendar.MONTH) + 1}月${cal.get(Calendar.DAY_OF_MONTH)}日"
    }
}

/** 时长友好格式：14分、2小时36分、48秒 */
fun formatListenDuration(ms: Long): String {
    if (ms <= 0L) return "0分"
    val totalMin = ms / 60_000L
    if (totalMin < 1L) return "${(ms / 1000L).coerceAtLeast(1L)}秒"
    val h = totalMin / 60
    val m = totalMin % 60
    return when {
        h <= 0L -> "${m}分"
        m == 0L -> "${h}小时"
        else -> "${h}小时${m}分"
    }
}

/** 紧凑格式：给排行榜右侧的小字用，最多 5 个字符 */
fun formatListenDurationShort(ms: Long): String {
    val totalMin = ms / 60_000L
    return when {
        totalMin < 1L -> "<1分"
        totalMin < 60L -> "${totalMin}分"
        else -> {
            val h = totalMin / 60f
            if (h < 10f) String.format("%.1fh", h) else "${(totalMin / 60)}h"
        }
    }
}
