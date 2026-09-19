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
    fun buildJson(context: Context, songs: List<Song>): String {
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
            put("settings", collectSettings(context))
        }.toString(2)
    }

    /**
     * 设置导出：iris_prefs 整份键值打包（主题/材质/明暗/探索度/播放模式/均衡器等全部）。
     *
     * 每个键存成 {"t": 类型, "v": 值}。**必须带类型标记**：JSON 不区分 Int/Long，
     * 一个 Long 键若数值不大，读回来会退化成 Integer，用 putInt 写入后 app 端
     * getLong 会抛 ClassCastException 直接崩溃。带上 t 就能原样还原。
     *
     * 只排除 [EXCLUDED_SETTING_KEYS] 里的行为数据键——它们由 [import] 的取较大值合并路径处理。
     * 全量 dump 而不是白名单：以后新增设置项不需要记得来这里同步，自动被带走。
     */
    private fun collectSettings(context: Context): JSONObject {
        val prefs = context.getSharedPreferences("iris_prefs", Context.MODE_PRIVATE)
        val out = JSONObject()
        prefs.all.forEach { (key, value) ->
            if (key in EXCLUDED_SETTING_KEYS) return@forEach
            val entry = JSONObject()
            when (value) {
                null -> return@forEach
                is Boolean -> entry.put("t", "bool").put("v", value)
                is Int -> entry.put("t", "int").put("v", value)
                is Long -> entry.put("t", "long").put("v", value)
                is Float -> entry.put("t", "float").put("v", value.toDouble())
                is String -> entry.put("t", "string").put("v", value)
                is Set<*> -> entry.put("t", "set").put("v", JSONArray().apply { value.forEach { put(it) } })
                else -> return@forEach
            }
            out.put(key, entry)
        }
        return out
    }

    /**
     * 设置写回：只覆盖导出文件里出现的键，本机多出的键不动。
     * 按导出时记录的类型精确还原，避免 Int/Long 混淆导致 app 读取时崩溃。
     * 兼容旧格式（值直接是原始类型、无 t/v 包装）。
     */
    private fun applySettings(context: Context, settings: JSONObject) {
        val prefs = context.getSharedPreferences("iris_prefs", Context.MODE_PRIVATE)
        val edit = prefs.edit()
        settings.keys().forEach { key ->
            val raw = settings.opt(key)
            // 新格式：{t,v}
            if (raw is JSONObject && raw.has("t")) {
                when (raw.optString("t")) {
                    "bool" -> edit.putBoolean(key, raw.optBoolean("v"))
                    "int" -> edit.putInt(key, raw.optInt("v"))
                    "long" -> edit.putLong(key, raw.optLong("v"))
                    "float" -> edit.putFloat(key, raw.optDouble("v").toFloat())
                    "string" -> edit.putString(key, raw.optString("v"))
                    "set" -> {
                        val arr = raw.optJSONArray("v") ?: JSONArray()
                        edit.putStringSet(key, HashSet<String>().apply {
                            for (i in 0 until arr.length()) arr.optString(i, "").takeIf { it.isNotEmpty() }?.let { add(it) }
                        })
                    }
                }
                return@forEach
            }
            // 旧格式兼容：值直接是原始类型（无类型标记，尽力而为）
            when (raw) {
                null -> {}
                is Boolean -> edit.putBoolean(key, raw)
                // JSON 不分 Int/Long，已知的 Long 键强制 putLong，避免 app 端 getLong 崩溃
                is Int -> if (key in KNOWN_LONG_KEYS) edit.putLong(key, raw.toLong()) else edit.putInt(key, raw)
                is Long -> edit.putLong(key, raw)
                is Double -> edit.putFloat(key, raw.toFloat())
                is String -> edit.putString(key, raw)
                is JSONArray -> edit.putStringSet(key, HashSet<String>().apply {
                    for (i in 0 until raw.length()) raw.optString(i, "").takeIf { it.isNotEmpty() }?.let { add(it) }
                })
            }
        }
        edit.apply()
    }

    /** 行为数据键：导入时走 [PlayHistory.mergeImported] 精细合并，不走设置原样恢复 */
    private val EXCLUDED_SETTING_KEYS = setOf(
        "play_counts", "skip_counts", "last_played", "likes", "total_played_ms", "incomplete_counts"
    )

    /** 旧格式导入兼容：这些键在 app 里用 getLong 读，JSON 里退化成 Int 时要强制 putLong，否则崩溃 */
    private val KNOWN_LONG_KEYS = setOf(
        "fade_ms", "custom_primary", "custom_secondary",
        "active_playlist_id", "last_song_id", "last_position_ms"
    )

    /** 写出到用户选定的 Uri（SAF）。IO 线程执行。 */
    suspend fun export(context: Context, uri: Uri, songs: List<Song>): Boolean =
        withContext(Dispatchers.IO) {
            runCatching {
                // 落盘前先把内存缓冲刷进明细文件，否则最近几十秒的听歌会漏掉
                ListenStats.flush()
                val json = buildJson(context, songs)
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
     * - 设置原样恢复（iris_prefs 全量，行为数据键除外）
     *
     * 曲目匹配：先按 songId，命中不到时按 title+artist 在本机库里找同名曲重定向 ID。
     *
     * songs 传空库也安全：未匹配的曲目数据不丢，暂存到 orphan 区；等曲库加载完成后
     * 调用 [attachOrphans] 按 title+artist 二次匹配收编——新装应用导入时曲库往往
     * 还没扫完，allSongs 为空会让全部匹配失败，那正是"导入后什么都没有"的根源。
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
                val settingsJson = root.optJSONObject("settings")
                // 设置不依赖曲库，最先恢复——即匹配全空，主题/歌单等也已回来
                if (settingsJson != null) applySettings(context, settingsJson)

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
                val orphanSongs = HashMap<String, JSONObject>() // 未匹配 → 原始条目，曲库加载后再收编
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
                    val id = resolve(rawId, o.optString("title"), o.optString("artist"))
                    if (id == null) {
                        o.optString("title").takeIf { it.isNotBlank() }?.let { orphanSongs[nameKey(it, o.optString("artist"))] = o }
                        continue
                    }
                    idRemap[rawId] = id
                    if (o.optBoolean("liked")) likes.add(id)
                    o.optInt("playCount").let { if (it > 0) plays[id] = maxOf(plays[id] ?: 0, it) }
                    o.optInt("incompleteCount").let { if (it > 0) incompletes[id] = maxOf(incompletes[id] ?: 0, it) }
                    o.optLong("totalPlayedMs").let { if (it > 0) totals[id] = maxOf(totals[id] ?: 0L, it) }
                    o.optLong("lastPlayed").let { if (it > 0) lasts[id] = maxOf(lasts[id] ?: 0L, it) }
                }

                PlayHistory.mergeImported(likes, plays, incompletes, totals, lasts)

                // ---- 歌单（未匹配曲目的 songIds 按名称原样保留，attachOrphans 时重定向）----
                val rawPlaylists = ArrayList<Triple<String, String, List<Long>>>() // name, titleKey, rawIds
                var playlistCount = 0
                val playlistArray = root.optJSONArray("playlists") ?: JSONArray()
                for (i in 0 until playlistArray.length()) {
                    val o = playlistArray.optJSONObject(i) ?: continue
                    val name = o.optString("name").trim()
                    if (name.isEmpty()) continue
                    val idsJson = o.optJSONArray("songIds") ?: continue
                    val rawIds = ArrayList<Long>(idsJson.length())
                    for (j in 0 until idsJson.length()) rawIds.add(idsJson.optLong(j, -1L))
                    val mapped = rawIds.mapNotNull { idRemap[it] }
                    val target = Playlists.all().firstOrNull { it.name == name }?.id
                        ?: Playlists.create(name)
                    mapped.forEach { Playlists.addSong(target, it) }
                    playlistCount++
                    // 未匹配部分原样记下，等收编
                    val unmapped = rawIds.filter { it !in idRemap }
                    if (unmapped.isNotEmpty()) {
                        val titleKeys = unmapped.mapNotNull { raw ->
                            orphanSongs.entries.firstOrNull { it.value.optLong("id", -1L) == raw }?.key
                        }
                        rawPlaylists.add(Triple(name, titleKeys.joinToString("\u0002"), unmapped))
                    }
                }

                // ---- 听歌明细（未匹配曲目按 title 暂存孤儿区）----
                val rows = HashMap<Long, HashMap<Long, Long>>()
                val orphanListen = HashMap<String, HashMap<Long, Long>>() // titleKey → (day → ms)
                val listenArray = root.optJSONArray("listenStats") ?: JSONArray()
                for (i in 0 until listenArray.length()) {
                    val o = listenArray.optJSONObject(i) ?: continue
                    val day = o.optLong("day", -1L)
                    val rawSongId = o.optLong("songId", -1L)
                    val ms = o.optLong("ms", 0L)
                    if (day < 0L || ms <= 0L) continue
                    val songId = idRemap[rawSongId]
                    if (songId == null) {
                        val key = orphanSongs.entries.firstOrNull { it.value.optLong("id", -1L) == rawSongId }?.key
                            ?: continue
                        val byDay = orphanListen.getOrPut(key) { HashMap() }
                        byDay[day] = maxOf(byDay[day] ?: 0L, ms)
                        continue
                    }
                    val bySong = rows.getOrPut(day) { HashMap() }
                    bySong[songId] = maxOf(bySong[songId] ?: 0L, ms)
                }
                val listenDays = ListenStats.mergeImported(rows)

                persistOrphans(context, orphanSongs, orphanListen, rawPlaylists)
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

    // ==================== 孤儿区（空库导入的暂存）====================

    private const val ORPHAN_FILE = "import_orphans.json"

    /**
     * 暂存未匹配的曲目/歌单/明细。曲库加载完成后由 ViewModel 调 [attachOrphans] 收编；
     * 收编后清空。放在 filesDir，不联网、随应用数据走。
     */
    private fun persistOrphans(
        context: Context,
        songs: Map<String, JSONObject>,
        listen: Map<String, HashMap<Long, Long>>,
        playlists: List<Triple<String, String, List<Long>>>
    ) {
        if (songs.isEmpty() && listen.isEmpty() && playlists.isEmpty()) return
        runCatching {
            val root = JSONObject()
            val songArr = JSONArray()
            songs.forEach { (_, o) -> songArr.put(o) }
            root.put("songs", songArr)
            val listenArr = JSONArray()
            listen.forEach { (key, byDay) ->
                byDay.forEach { (day, ms) ->
                    listenArr.put(JSONObject().put("key", key).put("day", day).put("ms", ms))
                }
            }
            root.put("listen", listenArr)
            val plArr = JSONArray()
            playlists.forEach { (name, keys, _) -> plArr.put(JSONObject().put("name", name).put("keys", keys)) }
            root.put("playlists", plArr)
            context.filesDir.resolve(ORPHAN_FILE).writeText(root.toString())
        }
    }

    private fun readOrphans(context: Context): JSONObject? =
        runCatching {
            val f = context.filesDir.resolve(ORPHAN_FILE)
            if (!f.exists()) null else JSONObject(f.readText())
        }.getOrNull()

    private fun clearOrphans(context: Context) {
        runCatching { context.filesDir.resolve(ORPHAN_FILE).delete() }
    }

    /**
     * 曲库就绪后收编孤儿数据：按 title+artist 匹配本机 ID，把暂存的
     * 点赞/计数/明细/歌单曲目归位。空库导入的场景在这里补全。
     */
    fun attachOrphans(context: Context, songs: List<Song>) {
        if (songs.isEmpty()) return
        val root = readOrphans(context) ?: return
        runCatching {
            val byName = HashMap<String, Long>()
            songs.forEach { byName.putIfAbsent(nameKey(it.title, it.artist), it.id) }

            val likes = HashSet<Long>()
            val plays = HashMap<Long, Int>()
            val incompletes = HashMap<Long, Int>()
            val totals = HashMap<Long, Long>()
            val lasts = HashMap<Long, Long>()
            var matched = 0

            val songArr = root.optJSONArray("songs") ?: JSONArray()
            for (i in 0 until songArr.length()) {
                val o = songArr.optJSONObject(i) ?: continue
                val key = nameKey(o.optString("title"), o.optString("artist"))
                val id = byName[key] ?: continue
                matched++
                if (o.optBoolean("liked")) likes.add(id)
                o.optInt("playCount").let { if (it > 0) plays[id] = maxOf(plays[id] ?: 0, it) }
                o.optInt("incompleteCount").let { if (it > 0) incompletes[id] = maxOf(incompletes[id] ?: 0, it) }
                o.optLong("totalPlayedMs").let { if (it > 0) totals[id] = maxOf(totals[id] ?: 0L, it) }
                o.optLong("lastPlayed").let { if (it > 0) lasts[id] = maxOf(lasts[id] ?: 0L, it) }
            }

            // 明细
            val rows = HashMap<Long, HashMap<Long, Long>>()
            val listenArr = root.optJSONArray("listen") ?: JSONArray()
            for (i in 0 until listenArr.length()) {
                val o = listenArr.optJSONObject(i) ?: continue
                val id = byName[o.optString("key")] ?: continue
                val day = o.optLong("day", -1L)
                val ms = o.optLong("ms", 0L)
                if (day < 0L || ms <= 0L) continue
                rows.getOrPut(day) { HashMap() }[id] = maxOf(rows[day]?.get(id) ?: 0L, ms)
            }
            if (rows.isNotEmpty()) ListenStats.mergeImported(rows)

            // 歌单未收编曲目（keys 为 \u0002 分隔的 titleKey）
            val plArr = root.optJSONArray("playlists") ?: JSONArray()
            for (i in 0 until plArr.length()) {
                val o = plArr.optJSONObject(i) ?: continue
                val name = o.optString("name")
                val keys = o.optString("keys").split("\u0002").filter { it.isNotBlank() }
                val target = Playlists.all().firstOrNull { it.name == name }?.id ?: continue
                keys.forEach { key -> byName[key]?.let { Playlists.addSong(target, it) } }
            }

            PlayHistory.mergeImported(likes, plays, incompletes, totals, lasts)
            matched
            clearOrphans(context)
        }
    }

    /** 标题+作者归一化键：忽略大小写与首尾空白，跨设备匹配用 */
    private fun nameKey(title: String, artist: String): String =
        title.trim().lowercase() + "\u0001" + artist.trim().lowercase()
}
