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

import android.content.ContentUris
import android.content.Context
import android.media.MediaScannerConnection
import android.os.Environment
import android.net.Uri
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File

object MusicRepository {

    private const val ALBUM_ART_BASE = "content://media/external/audio/albumart"

    /** 扫描整个外部存储的音频（触发 MediaStore 重新索引），然后重新读库 */
    suspend fun rescanAndReload(context: Context): List<Song> {
        // 先扫描：让 MediaStore 索引新增/变动的音频文件
        val dirs = mutableSetOf<String>()
        runCatching {
            context.getExternalFilesDirs(null).forEach { f -> f?.parentFile?.let { dirs.add(it.absolutePath) } }
            dirs.add(Environment.getExternalStorageDirectory().absolutePath)
        }
        withContext(Dispatchers.IO) {
            dirs.forEach { dir ->
                runCatching {
                    MediaScannerConnection.scanFile(context, arrayOf(dir), arrayOf("audio/mpeg", "audio/flac", "audio/mp4", "audio/ogg", "audio/x-ms-wma"), null)
                }
            }
        }
        // 轮询直到 MediaStore 行数稳定即返回：扫描快的设备不必空等、慢的多给时间，
        // 取代原来固定 delay(1000)（最多等 ~3s 兜底，避免无界等待）。
        // query 是 IO，整段收进 IO 线程。
        withContext(Dispatchers.IO) {
            var lastCount = -1
            var stable = 0
            var waited = 0
            while (waited < 3000) {
                delay(400)
                waited += 400
                val count = runCatching { countSongs(context) }.getOrDefault(0)
                if (count != 0 && count == lastCount) {
                    if (++stable >= 2) break
                } else {
                    stable = 0
                }
                lastCount = count
            }
        }
        return loadSongs(context)
    }

    /** 仅统计当前 MediaStore 里符合条件的曲目数，供扫描后轮询稳定判断用。 */
    private fun countSongs(context: Context): Int {
        val selection =
            "${MediaStore.Audio.Media.IS_MUSIC} != 0 AND ${MediaStore.Audio.Media.DURATION} > 10000"
        return runCatching {
            context.contentResolver.query(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                arrayOf(MediaStore.Audio.Media._ID),
                selection, null, null
            )?.use { it.count } ?: 0
        }.getOrDefault(0)
    }

    suspend fun loadSongs(context: Context): List<Song> = withContext(Dispatchers.IO) {
        val songs = mutableListOf<Song>()

        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.ALBUM_ID,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.DATA,
            MediaStore.Audio.Media.DATE_ADDED
        )

        val selection =
            "${MediaStore.Audio.Media.IS_MUSIC} != 0 AND ${MediaStore.Audio.Media.DURATION} > 10000"
        val sortOrder = "${MediaStore.Audio.Media.DATE_ADDED} DESC"

        runCatching {
            context.contentResolver.query(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                projection,
                selection,
                null,
                sortOrder
            )?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                val titleCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
                val artistCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
                val albumCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
                val albumIdCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID)
                val durationCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
                val dataCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATA)

                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idCol)
                    val albumId = cursor.getLong(albumIdCol)
                    val path = cursor.getString(dataCol) ?: ""
                    val parent = if (path.isNotEmpty()) File(path).parent ?: "" else ""

                    songs += Song(
                        id = id,
                        title = cursor.getString(titleCol) ?: "未知曲目",
                        artist = cursor.getString(artistCol)?.takeIf { it != "<unknown>" }
                            ?: "未知艺术家",
                        album = cursor.getString(albumCol) ?: "",
                        durationMs = cursor.getLong(durationCol),
                        uri = ContentUris.withAppendedId(
                            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id
                        ),
                        albumArtUri = ContentUris.withAppendedId(
                            Uri.parse(ALBUM_ART_BASE), albumId
                        ),
                        filePath = path,
                        folderPath = parent,
                        folderName = parent.substringAfterLast('/', "根目录")
                            .ifEmpty { "根目录" }
                    )
                }
            }
        }

        songs
    }

    /** 由歌曲列表聚合出文件夹清单，按歌曲数降序 */
    fun buildFolders(songs: List<Song>): List<MusicFolder> =
        songs.groupBy { it.folderPath }
            .map { (path, list) ->
                MusicFolder(
                    path = path,
                    name = list.first().folderName,
                    songCount = list.size
                )
            }
            .sortedByDescending { it.songCount }
}