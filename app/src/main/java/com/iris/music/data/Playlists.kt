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
 * 自定义歌单：纯「歌曲 ID 集合」记录，非文件夹分类。
 *
 * 用户可以创建歌单、往歌单里加歌/移出歌、删除歌单。持久化到 SharedPreferences，
 * 只记歌单名 + 歌曲 ID 列表（有序，保持加入顺序）。
 *
 * 存储格式：
 * - playlist_names:   "自增ID:名称;自增ID:名称;..."
 * - playlist_songs_<id>:  "songId,songId,songId,..."
 * - playlist_next_id: 下一个自增 ID
 */
object Playlists {

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
        cur[id] = name.trim().ifBlank { "歌单 $id" }
        writeNames(cur)
        prefs.edit().putLong(KEY_NEXT_ID, id + 1).apply()
        cachedPlaylists = null
        id
    }

    /** 重命名歌单 */
    fun rename(playlistId: Long, newName: String): Boolean = synchronized(writeLock) {
        val cur = readNamesMap()
        if (playlistId !in cur) return@synchronized false
        cur[playlistId] = newName.trim().ifBlank { "歌单 $playlistId" }
        writeNames(cur)
        cachedPlaylists = null
        true
    }

    /** 删除歌单 */
    fun delete(playlistId: Long): Boolean = synchronized(writeLock) {
        val cur = readNamesMap()
        if (playlistId !in cur) return@synchronized false
        cur.remove(playlistId)
        writeNames(cur)
        prefs.edit().remove(KEY_SONGS_PREFIX + playlistId).apply()
        cachedPlaylists = null
        true
    }

    /** 往歌单追加一首歌，已存在则忽略 */
    fun addSong(playlistId: Long, songId: Long): Boolean = synchronized(writeLock) {
        if (playlistId !in readNamesMap()) return@synchronized false
        val list = songs(playlistId).toMutableList()
        if (songId in list) return@synchronized false
        list.add(songId)
        prefs.edit().putString(KEY_SONGS_PREFIX + playlistId, list.joinToString(",")).apply()
        cachedPlaylists = null
        true
    }

    /** 从歌单移除一首歌 */
    fun removeSong(playlistId: Long, songId: Long): Boolean = synchronized(writeLock) {
        if (playlistId !in readNamesMap()) return@synchronized false
        val list = songs(playlistId).toMutableList()
        if (songId !in list) return@synchronized false
        list.remove(songId)
        prefs.edit().putString(KEY_SONGS_PREFIX + playlistId, list.joinToString(",")).apply()
        cachedPlaylists = null
        true
    }

    /** 歌曲是否在歌单中 */
    fun contains(playlistId: Long, songId: Long): Boolean = songId in songs(playlistId)

    /** 歌曲所属的所有歌单名（按创建顺序） */
    fun playlistsOfSong(songId: Long): List<String> =
        all().filter { songId in songs(it.id) }.map { it.name }

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
        val map = linkedMapOf<Long, String>()
        if (raw.isBlank()) return map
        for (part in raw.split(';')) {
            val kv = part.split(':')
            val id = kv.getOrNull(0)?.trim()?.toLongOrNull() ?: continue
            val name = kv.getOrNull(1) ?: continue
            map[id] = name
        }
        return map
    }

    private fun writeNames(map: Map<Long, String>) {
        val raw = map.entries.joinToString(";") { "${it.key}:${it.value}" }
        prefs.edit().putString(KEY_NAMES, raw).apply()
    }
}

/** 歌单元数据（不含歌曲明细，明细由 [Playlists.songs] 按需读取） */
data class Playlist(
    val id: Long,
    val name: String,
    val songCount: Int
)