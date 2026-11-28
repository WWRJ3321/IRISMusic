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

import com.iris.music.ui.L

import android.content.Context
import android.content.SharedPreferences

/**
 * 自定义歌单：纯「歌曲 ID 集合」记录，非文件夹分类。
 *
 * 用户可以创建歌单、往歌单里加歌/移出歌、删除歌单。持久化到 SharedPreferences，
 * 只记歌单名 + 歌曲 ID 列表（有序，保持加入顺序）。
 *
 * 存储格式：
 * - playlist_names:   JSON 数组 [{"id":1,"name":"..."},...]（v4.9.6 起）。
 *   旧版是 "自增ID:名称;自增ID:名称;..." 裸拼接——名字里含 : 或 ; 会被截断，
 *   甚至能借导入改写别的歌单（评审 P0-2）。读取时自动识别旧格式并就地迁移。
 * - playlist_songs_<id>:  "songId,songId,songId,..."（纯数字，无注入面，保持不变）
 * - playlist_next_id: 下一个自增 ID
 */
object Playlists {

    private const val MAX_NAME_LEN = 60

    /**
     * 名字清洗：去控制字符、限长。JSON 存储本身不怕特殊字符，这里是为了
     * UI 整洁与导出文件干净；导入路径（DataTransfer）也走这一道。
     */
    internal fun sanitizeName(raw: String): String {
        val s = raw.replace(Regex("[\u0000-\u001f\u007f]"), " ").trim()
        return if (s.length > MAX_NAME_LEN) s.take(MAX_NAME_LEN).trimEnd() else s
    }

    /** 序列化：id→name 有序表 → JSON 数组文本。纯函数，供单测 */
    internal fun encodeNames(map: Map<Long, String>): String {
        val arr = org.json.JSONArray()
        for ((id, name) in map) arr.put(org.json.JSONObject().put("id", id).put("name", name))
        return arr.toString()
    }

    /**
     * 反序列化：同时接受新 JSON 格式与旧 "id:name;id:name" 格式（自动迁移）。
     * 纯函数，供单测。旧格式按【第一个】冒号切分——名字里后续的冒号保留，
     * 尽量少丢用户数据（旧版是 split(':') 取 [1]，冒号后全丢）。
     */
    internal fun decodeNames(raw: String): LinkedHashMap<Long, String> {
        val map = LinkedHashMap<Long, String>()
        val t = raw.trim()
        if (t.isEmpty()) return map
        if (t.startsWith("[")) {
            runCatching {
                val arr = org.json.JSONArray(t)
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val id = o.optLong("id", -1L)
                    val name = o.optString("name")
                    if (id >= 0 && name.isNotEmpty()) map[id] = name
                }
            }
            return map
        }
        for (part in t.split(';')) {
            val idx = part.indexOf(':')
            if (idx <= 0) continue
            val id = part.substring(0, idx).trim().toLongOrNull() ?: continue
            val name = part.substring(idx + 1)
            if (name.isNotEmpty()) map[id] = name
        }
        return map
    }

    private const val PREFS = "iris_playlists"
    private const val KEY_NAMES = "playlist_names"
    private const val KEY_NEXT_ID = "playlist_next_id"
    private const val KEY_SONGS_PREFIX = "playlist_songs_"

    private lateinit var prefs: SharedPreferences

    @Volatile private var cachedPlaylists: List<Playlist>? = null
    private val writeLock = Any()

    fun init(context: Context) {
        if (!::prefs.isInitialized) {
            prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        }
    }

    // ==================== 查询 ====================

    /** 所有歌单（按创建顺序，不含歌曲明细） */
    fun all(): List<Playlist> = cachedPlaylists ?: readAll().also { cachedPlaylists = it }

    /** 单个歌单的歌曲 ID 列表（有序） */
    fun songs(playlistId: Long): List<Long> {
        val raw = prefs.getString(KEY_SONGS_PREFIX + playlistId, "") ?: ""
        if (raw.isBlank()) return emptyList()
        return raw.split(',').mapNotNull { it.trim().toLongOrNull() }
    }

    // ==================== 增删改 ====================

    /** 创建歌单，返回新歌单 ID */
    fun create(name: String): Long = synchronized(writeLock) {
        val id = prefs.getLong(KEY_NEXT_ID, 1L)
        val cur = readNamesMap()
        cur[id] = sanitizeName(name).ifBlank { L.autoPlaylistName(id) }
        writeNames(cur)
        prefs.edit().putLong(KEY_NEXT_ID, id + 1).apply()
        invalidate()
        id
    }

    /** 重命名歌单 */
    fun rename(playlistId: Long, newName: String): Boolean = synchronized(writeLock) {
        val cur = readNamesMap()
        if (playlistId !in cur) return@synchronized false
        cur[playlistId] = sanitizeName(newName).ifBlank { L.autoPlaylistName(playlistId) }
        writeNames(cur)
        invalidate()
        true
    }

    /** 删除歌单 */
    fun delete(playlistId: Long): Boolean = synchronized(writeLock) {
        val cur = readNamesMap()
        if (playlistId !in cur) return@synchronized false
        cur.remove(playlistId)
        writeNames(cur)
        prefs.edit().remove(KEY_SONGS_PREFIX + playlistId).apply()
        invalidate()
        true
    }

    /** 往歌单追加一首歌，已存在则忽略 */
    fun addSong(playlistId: Long, songId: Long): Boolean = synchronized(writeLock) {
        if (playlistId !in readNamesMap()) return@synchronized false
        val list = songs(playlistId).toMutableList()
        if (songId in list) return@synchronized false
        list.add(songId)
        prefs.edit().putString(KEY_SONGS_PREFIX + playlistId, list.joinToString(",")).apply()
        invalidate()
        true
    }

    /** 从歌单移除一首歌 */
    fun removeSong(playlistId: Long, songId: Long): Boolean = synchronized(writeLock) {
        if (playlistId !in readNamesMap()) return@synchronized false
        val list = songs(playlistId).toMutableList()
        if (songId !in list) return@synchronized false
        list.remove(songId)
        prefs.edit().putString(KEY_SONGS_PREFIX + playlistId, list.joinToString(",")).apply()
        invalidate()
        true
    }

    /**
     * 「我喜欢」在歌单体系里的虚拟 ID。
     *
     * 红心（ liked ）本身仍存在 PlayHistory 里，这里是把同一份数据以歌单的形态
     * 暴露出来：歌单面板多一条恒存在的置顶条目，点开即只看红心的歌，
     * 长按任意歌曲也能像普通歌单那样把歌「加进我喜欢」。
     *
     * 取 -2：真实歌单 ID 从 1 递增（见 KEY_NEXT_ID），-1 已被「未选中歌单」占用。
     * 这个 ID 不写进 names JSON，因此永远不会与真实歌单冲突，也不会被删除。
     */
    const val LIKED_PLAYLIST_ID = -2L

    /** 歌曲是否在歌单中 */
    fun contains(playlistId: Long, songId: Long): Boolean = songId in songs(playlistId)

    /**
     * 歌曲所属的所有歌单名（按创建顺序）。
     *
     * 主页每一行都要调它：旧实现是"遍历所有歌单 → 读 prefs → split 解析"，
     * 一屏 10 行 × 10 个歌单 × 每单几百首 = 每次重组几万次字符串解析。
     * 现在建一次反向索引（歌单任何变动都会 [invalidate]），之后每行 O(1) 查表。
     */
    fun playlistsOfSong(songId: Long): List<String> =
        (membership ?: buildMembership()).getOrElse(songId) { emptyList() }

    @Volatile private var membership: Map<Long, List<String>>? = null

    private fun buildMembership(): Map<Long, List<String>> {
        val m = HashMap<Long, MutableList<String>>()
        for (p in all()) {
            for (id in songs(p.id)) {
                val names = m.getOrPut(id) { ArrayList(2) }
                if (p.name !in names) names.add(p.name)
            }
        }
        membership = m
        return m
    }

    /** 任何写操作后调用：歌单元数据与反向索引一起作废 */
    private fun invalidate() {
        cachedPlaylists = null
        membership = null
    }

    /**
     * 歌曲移动后把各歌单里的旧 ID 换成新 ID（原位替换，保持加入顺序）。
     * 新 ID 已在歌单里则丢弃旧条目去重。
     */
    fun remapIds(idMap: Map<Long, Long>): Unit = synchronized(writeLock) {
        if (idMap.isEmpty()) return@synchronized
        val edit = prefs.edit()
        var changed = false
        for (pid in readNamesMap().keys) {
            val key = KEY_SONGS_PREFIX + pid
            val list = songs(pid)
            val mapped = ArrayList<Long>(list.size)
            for (id in list) {
                val nid = idMap[id] ?: id
                if (nid !in mapped) mapped.add(nid)
            }
            if (mapped != list) {
                edit.putString(key, mapped.joinToString(","))
                changed = true
            }
        }
        invalidate()
        if (changed) edit.apply()
    }

    // ==================== 内部 ====================

    private fun readAll(): List<Playlist> {
        val names = readNamesMap()
        // 按创建顺序（ID 升序）
        return names.entries.sortedBy { it.key }.map { (id, name) ->
            Playlist(id = id, name = name, songCount = songs(id).size)
        }
    }

    private fun readNamesMap(): MutableMap<Long, String> {
        val raw = prefs.getString(KEY_NAMES, "") ?: ""
        val map = decodeNames(raw)
        // 旧格式读出后立即按新格式回写（就地迁移；幂等，重入无害）
        if (raw.isNotBlank() && !raw.trim().startsWith("[")) {
            runCatching { prefs.edit().putString(KEY_NAMES, encodeNames(map)).apply() }
        }
        return map
    }

    private fun writeNames(map: Map<Long, String>) {
        prefs.edit().putString(KEY_NAMES, encodeNames(map)).apply()
    }
}

/** 歌单元数据（不含歌曲明细，明细由 [Playlists.songs] 按需读取） */
data class Playlist(
    val id: Long,
    val name: String,
    val songCount: Int
)