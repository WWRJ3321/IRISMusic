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
    val folderName: String,
    /**
     * 文件修改时间（扫描时从 MetaEntry.mtime 带入，秒级）。
     * 「最近添加」排序直接读它，不再对全库逐首 File.lastModified()（评审 P1-6：
     * 5000 首 × 每次筛选 = 5000 次 stat 系统调用跑在主线程）。
     * 文件移动/重命名后 ID 不变但路径会变，此值以最近一次扫描为准，足够作排序代理。
     */
    val modifiedAt: Long = 0L
)

data class MusicFolder(
    val path: String,
    val name: String,
    val songCount: Int
)

enum class RepeatMode { OFF, ALL, ONE }

/**
 * 曲库排序键。顺序即上栏左胶囊的点按循环顺序。
 * 枚举名会落盘（KEY_SORT_ORDER），只能追加/调整顺序，不能改名。
 */
enum class SortOrder {
    TITLE, ARTIST, ALBUM, DURATION, RECENT, PLAYS
}

/** 分类浏览维度：艺术家 / 专辑（与文件夹、歌单并列的第三种「看歌」方式） */
enum class BrowseKind { ARTIST, ALBUM }

/** 当前分类筛选：按某位艺术家或某张专辑看歌；null = 不按分类筛选 */
data class BrowseFilter(val kind: BrowseKind, val name: String)

/**
 * 拆分多艺术家字段："周杰伦/费玉清"、"A; B"、"A、B" → 各自独立计入。
 * 不按逗号和 & 拆：大量乐队名本身带这些符号（"Simon & Garfunkel"）。
 */
fun Song.artistNames(): List<String> =
    artist.split('/', ';', '；', '、', '\\')
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .ifEmpty { listOf(artist.trim()) }

/** 分组键：该歌在某个分类维度下属于哪几个组 */
fun Song.groupKeys(kind: BrowseKind): List<String> = when (kind) {
    BrowseKind.ARTIST -> artistNames()
    BrowseKind.ALBUM -> listOf(album.trim())
}
enum class SortDirection {
    ASCENDING, DESCENDING
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