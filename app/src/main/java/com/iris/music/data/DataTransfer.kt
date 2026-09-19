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
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * 听歌数据的导出与导入（JSON，全离线）。
 *
 * 为什么需要它：推荐算法的全部价值建立在长期行为数据上，而这些数据原先只存在
 * 本机的 SharedPreferences 和 filesDir 里——换机、刷机、卸载重装就清零，
 * 于是推荐永远只能给用户"三个月的体验"。导出让这份积累变成用户能带走的资产。
 *
 * 导出内容：点赞、播放次数、未完播次数、累计时长、最后播放时间、自定义歌单、
 * 听歌明细（按天分桶）。不含任何设备标识，纯本地文件读写，不联网。
 *
 * 曲目以 songId 为键，而 MediaStore 的 ID 换机后会变，所以每首歌额外记
 * title/artist 作为回退匹配依据，见 [import] 的二级匹配。
 */
object DataTransfer {

    /** 导出格式版本：导入时据此做兼容处理 */
    private const val FORMAT_VERSION = 1

    const val SUGGESTED_FILE_NAME = "irismusic-data.json"

    data class Summary(
        val likes: Int,
        val playedSongs: Int,
        val playlists: Int,
        val listenDays: Int
    )

    sealed interface ImportResult {
        data class Success(val summary: Summary) : ImportResult
        data object BadFormat : ImportResult
        data object Failed : ImportResult
    }

    // ==================== 导出 ====================

    /** 组装导出用的 JSON 文本。songs 传全库，用于写入 title/artist 便于跨设备匹配。 */
    fun buildJson(songs: List<Song>): String {
        val likes = PlayHistory.getLikes()
        val playCounts = PlayHistory.getPlayCounts()
        val incomplete = PlayHistory.getIncompleteCounts()
        val totals = PlayHistory.getTotalPlayedMs()
        val lastPlayed = PlayHistory.getLastPlayed()

        // 把所有出现过的 songId 汇总，再尽量补上歌名/作者
        val byId = songs.associateBy { it.id }
        val ids = LinkedHashSet<Long>().apply {
            addAll(likes)
            addAll(playCounts.keys)
            addAll(incomplete.keys)
            addAll(totals.keys)
            addAll(lastPlayed.keys)
        }

        val songArray = JSONArray()
        ids.forEach { id ->
            val s = byId[id]
            songArray.put(JSONObject().apply {
                put("id", id)
                // 跨设备回退匹配依据；库里已删除的曲目没有元数据，留空即可
                put("title", s?.title ?: "")
                put("artist", s?.artist ?: "")
                put("liked", id in likes)
                put("playCount", playCounts[id] ?: 0)
                put("incompleteCount", incomplete[id] ?: 0)
                put("totalPlayedMs", totals[id] ?: 0L)
                put("lastPlayed", lastPlayed[id] ?: 0L)
            })
        }

        val playlistArray = JSONArray()
        Playlists.all().forEach { pl ->
            playlistArray.put(JSONObject().apply {
                put("name", pl.name)
                put("songIds", JSONArray().apply { Playlists.songs(pl.id).forEach { put(it) } })
            })
        }

        val listenArray = JSONArray()
        ListenStats.exportRows().forEach { (day, bySong) ->
            bySong.forEach { (songId, ms) ->
                listenArray.put(JSONObject().apply {
                    put("day", day)
                    put("songId", songId)
                    put("ms", ms)
                })
            }
        }

        return JSONObject().apply {
            put("format", FORMAT_VERSION)
            put("app", "IRIS Music")
            put("exportedAt", System.currentTimeMillis())
            put("songs", songArray)
            put("playlists", playlistArray)
            put("listenStats", listenArray)
        }.toString(2)
    }

    /** 写出到用户选定的 Uri（SAF）。IO 线程执行。 */
    suspend fun export(context: Context, uri: Uri, songs: List<Song>): Boolean =
        withContext(Dispatchers.IO) {
            runCatching {
                // 落盘前先把内存缓冲刷进明细文件，否则最近几十秒的听歌会漏掉
                ListenStats.flush()
                val json = buildJson(songs)
                context.contentResolver.openOutputStream(uri, "wt")?.use { out ->
                    out.write(json.toByteArray())
                } ?: return@runCatching false
                true
            }.getOrDefault(false)
        }

    // ==================== 导入 ====================

    /**
     * 从 Uri 读入并合并（不是覆盖）：
     * - 点赞取并集；计数与时长取两边较大值（避免重复导入把次数翻倍）
     * - 歌单按名称合并，同名歌单合并曲目、不重复
     * - 听歌明细按 (天, 曲目) 取较大值后整体重写
     *
     * 曲目匹配：先按 songId，命中不到时按 title+artist 在本机库里找同名曲重定向 ID。
     */
    suspend fun import(context: Context, uri: Uri, songs: List<Song>): ImportResult =
        withContext(Dispatchers.IO) {
            val text = runCatching {
                context.contentResolver.openInputStream(uri)?.use { it.readBytes().decodeToString() }
            }.getOrNull() ?: return@withContext ImportResult.Failed

            val root = runCatching { JSONObject(text) }.getOrNull()
                ?: return@withContext ImportResult.BadFormat
            if (!root.has("songs") || !root.has("format")) return@withContext ImportResult.BadFormat

            runCatching {
                val localIds = songs.mapTo(HashSet()) { it.id }
                // 跨设备回退索引：标题+作者 → 本机 songId
                val byName = HashMap<String, Long>()
                songs.forEach { byName.putIfAbsent(nameKey(it.title, it.artist), it.id) }

                fun resolve(rawId: Long, title: String, artist: String): Long? = when {
                    rawId in localIds -> rawId
                    else -> byName[nameKey(title, artist)]
                }

                // ---- 曲目维度 ----
                val idRemap = HashMap<Long, Long>()
                val likes = HashSet<Long>()
                val plays = HashMap<Long, Int>()
                val incompletes = HashMap<Long, Int>()
                val totals = HashMap<Long, Long>()
                val lasts = HashMap<Long, Long>()

                val songArray = root.optJSONArray("songs") ?: JSONArray()
                for (i in 0 until songArray.length()) {
                    val o = songArray.optJSONObject(i) ?: continue
                    val rawId = o.optLong("id", -1L)
                    if (rawId < 0L) continue
                    val id = resolve(rawId, o.optString("title"), o.optString("artist")) ?: continue
                    idRemap[rawId] = id
                    if (o.optBoolean("liked")) likes.add(id)
                    o.optInt("playCount").let { if (it > 0) plays[id] = maxOf(plays[id] ?: 0, it) }
                    o.optInt("incompleteCount").let { if (it > 0) incompletes[id] = maxOf(incompletes[id] ?: 0, it) }
                    o.optLong("totalPlayedMs").let { if (it > 0) totals[id] = maxOf(totals[id] ?: 0L, it) }
                    o.optLong("lastPlayed").let { if (it > 0) lasts[id] = maxOf(lasts[id] ?: 0L, it) }
                }

                PlayHistory.mergeImported(likes, plays, incompletes, totals, lasts)

                // ---- 歌单 ----
                var playlistCount = 0
                val playlistArray = root.optJSONArray("playlists") ?: JSONArray()
                for (i in 0 until playlistArray.length()) {
                    val o = playlistArray.optJSONObject(i) ?: continue
                    val name = o.optString("name").trim()
                    if (name.isEmpty()) continue
                    val idsJson = o.optJSONArray("songIds") ?: continue
                    val mapped = ArrayList<Long>(idsJson.length())
                    for (j in 0 until idsJson.length()) {
                        idRemap[idsJson.optLong(j, -1L)]?.let { mapped.add(it) }
                    }
                    if (mapped.isEmpty()) continue
                    val target = Playlists.all().firstOrNull { it.name == name }?.id
                        ?: Playlists.create(name)
                    mapped.forEach { Playlists.addSong(target, it) }
                    playlistCount++
                }

                // ---- 听歌明细 ----
                val rows = HashMap<Long, HashMap<Long, Long>>()
                val listenArray = root.optJSONArray("listenStats") ?: JSONArray()
                for (i in 0 until listenArray.length()) {
                    val o = listenArray.optJSONObject(i) ?: continue
                    val day = o.optLong("day", -1L)
                    val songId = idRemap[o.optLong("songId", -1L)] ?: continue
                    val ms = o.optLong("ms", 0L)
                    if (day < 0L || ms <= 0L) continue
                    val bySong = rows.getOrPut(day) { HashMap() }
                    bySong[songId] = maxOf(bySong[songId] ?: 0L, ms)
                }
                val listenDays = ListenStats.mergeImported(rows)

                // 导入改写了行为数据，推荐分必须重算
                Recommender.invalidate()

                ImportResult.Success(
                    Summary(
                        likes = likes.size,
                        playedSongs = idRemap.size,
                        playlists = playlistCount,
                        listenDays = listenDays
                    )
                )
            }.getOrElse { ImportResult.Failed }
        }

    /** 标题+作者归一化键：忽略大小写与首尾空白，跨设备匹配用 */
    private fun nameKey(title: String, artist: String): String =
        title.trim().lowercase() + "\u0001" + artist.trim().lowercase()
}
