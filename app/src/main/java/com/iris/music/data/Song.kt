package com.iris.music.data

import android.net.Uri

data class Song(
    val id: Long,
    val title: String,
    val artist: String,
    val album: String,
    val durationMs: Long,
    val uri: Uri,
    val albumArtUri: Uri?,
    /** 音频文件绝对路径，例如 /storage/emulated/0/Music/Anime/song.mp3（用于查找同目录歌词） */
    val filePath: String = "",
    /** 所在文件夹绝对路径，例如 /storage/emulated/0/Music/Anime */
    val folderPath: String,
    /** 文件夹显示名 */
    val folderName: String
)

data class MusicFolder(
    val path: String,
    val name: String,
    val songCount: Int
)

enum class RepeatMode { OFF, ALL, ONE }

enum class SortOrder(val label: String) {
    TITLE("标题"),
    ARTIST("艺术家"),
    DURATION("时长"),
    RECENT("最近添加")
}

/**
 * 音频格式标签：由文件扩展名推导，例如 MP3 / FLAC / OGG。
 * 无扩展名或异常长度时回退为 AUDIO。
 */
val Song.formatLabel: String
    get() {
        val ext = filePath.substringAfterLast('.', "").trim()
        if (ext.isEmpty() || ext.length > 5 || ext.contains('/')) return "AUDIO"
        return when (val u = ext.uppercase()) {
            "M4A", "MP4" -> "AAC"
            "OGA" -> "OGG"
            else -> u
        }
    }