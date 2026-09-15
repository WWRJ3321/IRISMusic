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
import android.content.SharedPreferences

/**
 * 播放历史追踪器：记录每首歌的播放次数、跳过次数、最后播放时间。
 * 持久化到 SharedPreferences，格式简单紧凑。
 *
 * 存储格式：
 * - play_counts: "songId:count;songId:count;..."
 * - skip_counts: 同上
 * - last_played: "songId:timestamp;..."
 */
object PlayHistory {

    private const val PREFS = "iris_prefs"
    private const val KEY_PLAY_COUNTS = "play_counts"
    private const val KEY_SKIP_COUNTS = "skip_counts"
    private const val KEY_LAST_PLAYED = "last_played"
    private const val KEY_LIKES = "likes"
    private const val KEY_TOTAL_PLAYED_MS = "total_played_ms"
    private const val KEY_INCOMPLETE = "incomplete_counts"

    private lateinit var prefs: SharedPreferences

    /** 完播判定：播放进度不足 25% 视为未完播 */
    const val COMPLETE_RATIO = 0.25f

    // ===== 内存缓存：避免每次切歌读写 5 次 SharedPreferences =====
    @Volatile private var cachedPlayCounts: Map<Long, Int>? = null
    @Volatile private var cachedSkipCounts: Map<Long, Int>? = null
    @Volatile private var cachedLastPlayed: Map<Long, Long>? = null
    @Volatile private var cachedTotalPlayedMs: Map<Long, Long>? = null
    @Volatile private var cachedIncomplete: Map<Long, Int>? = null
    @Volatile private var cachedLikes: Set<Long>? = null

    private val writeLock = Any()

    fun init(context: Context) {
        if (!::prefs.isInitialized) {
            prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        }
    }

    // ==================== 公共 API ====================

    /**
     * 记录一次播放结束（切歌/停止时调用）。
     * @param songId 歌曲 ID
     * @param playedMs 实际播放时长（毫秒）
     * @param durationMs 歌曲总时长（毫秒），用于完播判定
     */
    fun recordPlay(songId: Long, playedMs: Long, durationMs: Long = 0L) {
        synchronized(writeLock) {
            val counts = (cachedPlayCounts ?: readCounts(KEY_PLAY_COUNTS)).toMutableMap()
            val skips = (cachedSkipCounts ?: readCounts(KEY_SKIP_COUNTS)).toMutableMap()
            val lastPlayed = (cachedLastPlayed ?: readLastPlayed()).toMutableMap()
            val totals = (cachedTotalPlayedMs
                ?: readCounts(KEY_TOTAL_PLAYED_MS).mapValues { it.value.toLong() }).toMutableMap()
            val incomplete = (cachedIncomplete ?: readCounts(KEY_INCOMPLETE)).toMutableMap()

            // 累计总播放时长
            totals[songId] = (totals[songId] ?: 0L) + playedMs.coerceAtLeast(0L)

            if (durationMs > 0 && playedMs < durationMs * COMPLETE_RATIO) {
                incomplete[songId] = (incomplete[songId] ?: 0) + 1
                skips[songId] = (skips[songId] ?: 0) + 1
            } else if (playedMs >= 30_000L) {
                counts[songId] = (counts[songId] ?: 0) + 1
            }

            lastPlayed[songId] = System.currentTimeMillis()

            // 批量落盘：一次 apply 替代五次
            val edit = prefs.edit()
            writeCountsTo(edit, KEY_PLAY_COUNTS, counts)
            writeCountsTo(edit, KEY_SKIP_COUNTS, skips)
            writeCountsTo(edit, KEY_TOTAL_PLAYED_MS, totals.mapValues { it.value.toInt() })
            writeCountsTo(edit, KEY_INCOMPLETE, incomplete)
            writeLastPlayedTo(edit, lastPlayed)
            edit.apply()

            // 更新缓存
            cachedPlayCounts = counts
            cachedSkipCounts = skips
            cachedLastPlayed = lastPlayed
            cachedTotalPlayedMs = totals
            cachedIncomplete = incomplete
        }
    }

    /** 获取所有播放计数 */
    fun getPlayCounts(): Map<Long, Int> = cachedPlayCounts ?: readCounts(KEY_PLAY_COUNTS).also { cachedPlayCounts = it }

    /** 获取所有跳过计数 */
    fun getSkipCounts(): Map<Long, Int> = cachedSkipCounts ?: readCounts(KEY_SKIP_COUNTS).also { cachedSkipCounts = it }

    /** 获取累计播放总时长（毫秒） */
    fun getTotalPlayedMs(): Map<Long, Long> = cachedTotalPlayedMs
        ?: readCounts(KEY_TOTAL_PLAYED_MS).mapValues { it.value.toLong() }.also { cachedTotalPlayedMs = it }

    /** 获取未完播计数（进度不足 25% 的播放次数） */
    fun getIncompleteCounts(): Map<Long, Int> = cachedIncomplete ?: readCounts(KEY_INCOMPLETE).also { cachedIncomplete = it }

    /** 获取所有最后播放时间 */
    fun getLastPlayed(): Map<Long, Long> = cachedLastPlayed ?: readLastPlayed().also { cachedLastPlayed = it }

    // ==================== 点赞 ====================

    /** 切换歌曲点赞状态，返回切换后的状态 */
    fun toggleLike(songId: Long): Boolean {
        synchronized(writeLock) {
            val likes = (cachedLikes ?: readLikes()).toMutableSet()
            val next = if (songId in likes) likes - songId else likes + songId
            prefs.edit().putString(KEY_LIKES, next.joinToString(";")).apply()
            cachedLikes = next
            return songId in next
        }
    }

    /** 获取所有点赞歌曲 ID */
    fun getLikes(): Set<Long> = cachedLikes ?: readLikes().also { cachedLikes = it }

    /** 当前歌曲是否已点赞 */
    fun isLiked(songId: Long): Boolean = songId in getLikes()

    private fun readLikes(): MutableSet<Long> {
        val raw = prefs.getString(KEY_LIKES, null) ?: return mutableSetOf()
        if (raw.isBlank()) return mutableSetOf()
        return raw.split(';').mapNotNull { it.toLongOrNull() }.toMutableSet()
    }

    // ==================== 内部实现 ====================

    private fun readCounts(key: String): MutableMap<Long, Int> {
        val raw = prefs.getString(key, null) ?: return mutableMapOf()
        return raw.split(';').mapNotNull { part ->
            val kv = part.split(':')
            val id = kv.getOrNull(0)?.trim()?.toLongOrNull() ?: return@mapNotNull null
            val count = kv.getOrNull(1)?.trim()?.toIntOrNull() ?: return@mapNotNull null
            id to count
        }.toMap().toMutableMap()
    }

    private fun writeCounts(key: String, counts: Map<Long, Int>) {
        writeCountsTo(prefs.edit(), key, counts).apply()
    }

    private fun writeCountsTo(edit: SharedPreferences.Editor, key: String, counts: Map<Long, Int>): SharedPreferences.Editor {
        val raw = counts.entries
            .filter { it.value > 0 }
            .sortedByDescending { it.value }
            .take(500)
            .joinToString(";") { "${it.key}:${it.value}" }
        return edit.putString(key, raw)
    }

    private fun readLastPlayed(): MutableMap<Long, Long> {
        val raw = prefs.getString(KEY_LAST_PLAYED, null) ?: return mutableMapOf()
        return raw.split(';').mapNotNull { part ->
            val kv = part.split(':')
            val id = kv.getOrNull(0)?.trim()?.toLongOrNull() ?: return@mapNotNull null
            val ts = kv.getOrNull(1)?.trim()?.toLongOrNull() ?: return@mapNotNull null
            id to ts
        }.toMap().toMutableMap()
    }

    private fun writeLastPlayed(map: Map<Long, Long>) {
        writeLastPlayedTo(prefs.edit(), map).apply()
    }

    private fun writeLastPlayedTo(edit: SharedPreferences.Editor, map: Map<Long, Long>): SharedPreferences.Editor {
        val raw = map.entries
            .sortedByDescending { it.value }
            .take(500)
            .joinToString(";") { "${it.key}:${it.value}" }
        return edit.putString(KEY_LAST_PLAYED, raw)
    }
}
