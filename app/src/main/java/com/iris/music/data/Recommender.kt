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

import kotlin.math.sqrt
import kotlin.random.Random

/**
 * 本地音乐推荐引擎（v2：全库独立打分，不再依赖种子歌曲相似度）。
 *
 * 每首歌独立计算偏好分，与"当前在放什么"无关——推荐结果在切歌时保持稳定，
 * 只有点赞、播放行为变化或手动刷新时才改变。
 *
 * 分数构成（基准 1.0）：
 * 正向：
 * - 点赞：×1.5 倍率（直接乘，保证点赞是单项最强信号）
 * - 播放次数排行：按名次开方衰减，第 1 名 +20% 递减到末名 +0%
 *   （开方衰减避免马太效应：前 25% 的歌瓜分一半权重，腰部歌仍有存在感）
 * - 播放总时长排行：同上 +20% 递减
 * - 作者被点赞：该作者的歌被点赞的比例 × 10%，上限 +10%
 *   （按比例而非绝对次数，避免高产作者天然霸榜）
 * 负向：
 * - 未完播排行（进度不足 25% 的播放次数）：按名次 -10% 递减到 -0%
 *
 * 随机档（0-1，UI 上 10 档）：偏好分与随机分按档位加权混合，最高档接近纯随机
 * （仍保留近听降权和未完播惩罚）。
 */
object Recommender {

    const val DEFAULT_LIMIT = 10
    private const val RECENT_WINDOW_MS = 3_600_000L

    /** 缓存上次的偏好分（key = allSongs 的 identityHashCode），避免同一次操作重复计算 */
    @Volatile private var cachedScores: Pair<Int, Map<Long, Float>>? = null

    /**
     * 让偏好分缓存失效。
     *
     * 缓存键只看 allSongs 的对象身份，而点赞/播放记录改变时这个列表对象并不会换新——
     * 不显式失效的话 [getOrCreateScores] 会一直命中旧分数，表现为"点赞了但推荐没变"。
     * 因此任何写入 [PlayHistory] 的行为之后都必须调用本函数。
     */
    fun invalidate() {
        cachedScores = null
    }

    /**
     * @param exploration 随机档位 0-1（0=纯偏好，1=纯随机）
     */
    fun recommend(
        allSongs: List<Song>,
        limit: Int = DEFAULT_LIMIT,
        exploration: Float = 0.3f
    ): List<Song> {
        if (allSongs.isEmpty()) return emptyList()

        val scores = getOrCreateScores(allSongs)

        val exploreW = exploration.coerceIn(0f, 1f)
        val preferW = 1f - exploreW

        val scored = allSongs.map { song ->
            val prefer = scores[song.id] ?: 1f
            val randomScore = Random.nextFloat() * 2f
            RecommendItem(song, prefer * preferW + randomScore * exploreW)
        }
            .sortedByDescending { it.score }
            .take(limit)

        return scored.map { it.song }
    }

    /**
     * 加权随机排列（Efraimidis-Spirakis）：权重高的歌更大概率靠前，但顺序仍随机。
     * 返回歌曲下标的排列，用于随机播放的 ShuffleOrder。
     * @param exploration 探索度 0-1：越高权重越趋近均匀（随机感越强），越低偏好权重越主导
     */
    fun weightedPermutation(songs: List<Song>, exploration: Float = 0f): List<Int> {
        if (songs.isEmpty()) return emptyList()
        val scores = getOrCreateScores(songs)
        val e = exploration.coerceIn(0f, 1f)
        return songs.indices
            .map { i ->
                val w = (scores[songs[i].id] ?: 1f) * (1f - e) + 1f * e
                i to Math.pow(Random.nextDouble(), 1.0 / w)
            }
            .sortedByDescending { it.second }
            .map { it.first }
    }

    /** 获取或创建偏好分缓存：同一批歌曲只计算一次 */
    private fun getOrCreateScores(allSongs: List<Song>): Map<Long, Float> {
        val key = System.identityHashCode(allSongs)
        val cached = cachedScores
        if (cached != null && cached.first == key) return cached.second
        val scores = preferenceScores(allSongs)
        cachedScores = key to scores
        return scores
    }

    /** 全库每首歌的偏好分（基准 1.0，含点赞倍率/排行加减/近听降权） */
    private fun preferenceScores(allSongs: List<Song>): Map<Long, Float> {
        val playCounts = PlayHistory.getPlayCounts()
        val totalPlayedMs = PlayHistory.getTotalPlayedMs()
        val incompleteCounts = PlayHistory.getIncompleteCounts()
        val lastPlayed = PlayHistory.getLastPlayed()
        val likes = PlayHistory.getLikes()
        val now = System.currentTimeMillis()

        // 预计算各维度排行加分/减分
        val playRankBoost = rankBoost(allSongs, playCounts)                 // 播放次数 → +0~20%
        val durationRankBoost = rankBoost(allSongs, totalPlayedMs)          // 播放时长 → +0~20%
        val incompleteRankPenalty = rankPenalty(allSongs, incompleteCounts) // 未完播 → -0~10%

        // 作者被点赞比例：该作者的歌中被点赞的比例（0-1），×10% 封顶
        val artistLikeRatio = buildArtistLikeRatio(allSongs, likes)

        return allSongs.associate { song ->
            var prefer = 1f
            if (song.id in likes) prefer *= 1.5f
            prefer += playRankBoost[song.id] ?: 0f
            prefer += durationRankBoost[song.id] ?: 0f
            prefer += (artistLikeRatio[song.artist] ?: 0f) * 0.10f
            prefer -= incompleteRankPenalty[song.id] ?: 0f
            // 近 1 小时听过的大幅降权（减少重复）
            val last = lastPlayed[song.id]
            if (last != null && now - last < RECENT_WINDOW_MS) prefer *= 0.1f
            song.id to prefer.coerceAtLeast(0.05f)
        }
    }

    /**
     * 排行加分：value 越大加分越高。
     * boost(rank) = maxBoost * (1 - sqrt(rank / total))
     * 开方使前段下降慢、后段下降快，前 25% 瓜分约一半增量。
     * 没有记录的歌不加分。
     */
    private fun rankBoost(songs: List<Song>, values: Map<Long, Number>): Map<Long, Float> {
        val ranked = songs.mapNotNull { s ->
            val v = values[s.id]?.toDouble() ?: return@mapNotNull null
            if (v <= 0.0) null else s.id to v
        }.sortedByDescending { it.second }
        if (ranked.isEmpty()) return emptyMap()

        val total = ranked.size.toFloat()
        val maxBoost = 0.20f
        return ranked.mapIndexed { idx, (id, _) ->
            id to maxBoost * (1f - sqrt(idx / total))
        }.toMap()
    }

    /** 排行减分：同 rankBoost 逻辑，第 1 名扣最多 */
    private fun rankPenalty(songs: List<Song>, values: Map<Long, Number>): Map<Long, Float> {
        val ranked = songs.mapNotNull { s ->
            val v = values[s.id]?.toDouble() ?: return@mapNotNull null
            if (v <= 0.0) null else s.id to v
        }.sortedByDescending { it.second }
        if (ranked.isEmpty()) return emptyMap()

        val total = ranked.size.toFloat()
        val maxPenalty = 0.10f
        return ranked.mapIndexed { idx, (id, _) ->
            id to maxPenalty * (1f - sqrt(idx / total))
        }.toMap()
    }

    /** 每个作者的歌中被点赞的比例（只统计有点赞的作者） */
    private fun buildArtistLikeRatio(songs: List<Song>, likes: Set<Long>): Map<String, Float> {
        if (likes.isEmpty()) return emptyMap()
        return songs.groupBy { it.artist }.mapNotNull { (artist, artistSongs) ->
            val liked = artistSongs.count { it.id in likes }
            if (liked == 0) null else artist to liked.toFloat() / artistSongs.size
        }.toMap()
    }

    // ==================== 可解释性 ====================

    /**
     * 拆出某首歌得分的构成，供"为什么推荐这首"卡片展示。
     *
     * 与 [preferenceScores] 用同一套输入和同样的算式——不另写一份近似逻辑，
     * 否则解释和真实排序迟早会对不上，那比没有解释更糟。
     */
    fun explain(song: Song, allSongs: List<Song>): Explanation {
        val playCounts = PlayHistory.getPlayCounts()
        val totalPlayedMs = PlayHistory.getTotalPlayedMs()
        val incompleteCounts = PlayHistory.getIncompleteCounts()
        val likes = PlayHistory.getLikes()
        val lastPlayed = PlayHistory.getLastPlayed()

        val factors = mutableListOf<Factor>()

        val liked = song.id in likes
        if (liked) factors.add(Factor("你点赞过这首", 0.5f))

        rankBoost(allSongs, playCounts)[song.id]?.takeIf { it > 0.001f }?.let {
            factors.add(Factor("播放次数排名靠前（${playCounts[song.id] ?: 0} 次）", it))
        }
        rankBoost(allSongs, totalPlayedMs)[song.id]?.takeIf { it > 0.001f }?.let {
            val minutes = ((totalPlayedMs[song.id] ?: 0L) / 60_000L).toInt()
            factors.add(Factor("累计听了较久（约 $minutes 分钟）", it))
        }
        buildArtistLikeRatio(allSongs, likes)[song.artist]?.takeIf { it > 0f }?.let {
            factors.add(Factor("你常点赞 ${song.artist} 的歌", it * 0.10f))
        }
        rankPenalty(allSongs, incompleteCounts)[song.id]?.takeIf { it > 0.001f }?.let {
            factors.add(Factor("经常没听完就切走（${incompleteCounts[song.id] ?: 0} 次）", -it))
        }

        val last = lastPlayed[song.id]
        val recent = last != null && System.currentTimeMillis() - last < RECENT_WINDOW_MS
        if (recent) factors.add(Factor("最近一小时刚听过，暂时降权", -0.9f))

        val isNew = song.id !in playCounts && song.id !in lastPlayed && !liked
        return Explanation(
            score = getOrCreateScores(allSongs)[song.id] ?: 1f,
            isColdStart = isNew,
            factors = factors.sortedByDescending { kotlin.math.abs(it.weight) }
        )
    }

    /** 单条贡献因子。weight 为正表示加分、负表示减分（已折算到最终分的增量口径） */
    data class Factor(val label: String, val weight: Float)

    /**
     * @param isColdStart 新导入且无任何行为记录：这类歌只拿基准分 1.0，
     *   既不会被历史压住，也不会凭空加分；提高探索度可以让它们更容易出现。
     */
    data class Explanation(
        val score: Float,
        val isColdStart: Boolean,
        val factors: List<Factor>
    )
}

data class RecommendItem(val song: Song, val score: Float)