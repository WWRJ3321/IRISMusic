package com.iris.music.data

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Environment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 曲库仓库：直接扫描文件系统构建曲库，不依赖 MediaStore。
 *
 * 为什么不用 MediaStore：带内嵌 mjpeg 封面（视频编码）的 mp3 会被系统 MediaScanner
 * 判成 media_type=video/image，进不了 audio 表。实测某设备 QQMusic 目录 241 首歌
 * 在 audio 表里一首都没有，无论怎么触发扫描、传什么显式 mimeType 都塞不回去。
 * 直扫文件系统一举解决三件事：
 *   1. 路径就是磁盘真实路径 —— 天然没有大小写别名导致的"重复文件夹"问题；
 *   2. 不经过 audio 表 —— mjpeg 封面等被误判的文件照样收录；
 *   3. 不等系统异步扫描 —— 结果即时、可控。
 *
 * 元数据缓存：首次加载要用 MediaMetadataRetriever 逐个读元数据（慢）。加一层持久化
 * 缓存后，只有新增/改动过（size 或 mtime 变化）的文件才重读，其余命中缓存，
 * 二次启动/刷新基本秒开。缓存是紧凑 JSON 文本，几百首约几十 KB，占用可忽略。
 */
object MusicRepository {

    private val AUDIO_EXTS = setOf("mp3", "flac", "m4a", "aac", "ogg", "wav", "wma", "opus")
    private const val MIN_DURATION_MS = 10_000L
    private const val SCAN_CONCURRENCY = 16
    private const val DIR_BUDGET = 20_000  // 目录访问上限，防御异常深树
    private const val CACHE_FILE = "song_meta_cache.json"

    /** 单文件元数据缓存条目：以 path 为键，size+mtime 作有效性指纹 */
    private data class MetaEntry(
        val size: Long,
        val mtime: Long,
        val durationMs: Long,
        val title: String,
        val artist: String,
        val album: String
    )

    @Volatile private var cacheFile: File? = null

    fun init(context: Context) {
        if (cacheFile == null) cacheFile = File(context.applicationContext.filesDir, CACHE_FILE)
    }

    /** 刷新 = 重新扫盘建库（曲库可能有增删改，清空歌词缓存） */
    suspend fun rescanAndReload(context: Context): List<Song> {
        LyricParser.clearCache()
        return loadSongs(context)
    }

    /** 扫描文件系统收集所有音频文件的绝对路径（BFS） */
    private fun collectAudioFiles(context: Context): List<String> {
        val roots = mutableSetOf<String>()
        runCatching {
            context.getExternalFilesDirs(null).forEach { f -> f?.parentFile?.let { roots.add(it.absolutePath) } }
            roots.add(Environment.getExternalStorageDirectory().absolutePath)
        }
        val result = mutableListOf<String>()
        val queue = ArrayDeque<File>(roots.map { File(it) })
        var budget = DIR_BUDGET
        while (queue.isNotEmpty() && budget-- > 0) {
            val dir = queue.removeFirst()
            val entries = runCatching { dir.listFiles() }.getOrNull() ?: continue
            // 目录带 .nomedia 视为不希望被索引，跳过整棵子树（根目录本身除外）
            if (dir.absolutePath !in roots && File(dir, ".nomedia").exists()) continue
            for (e in entries) {
                if (e.isDirectory) {
                    // 隐藏目录（.开头，如 .minecraft）视为私有，不扫
                    if (e.name.startsWith(".")) continue
                    // 顶层 Android/data、Android/obb 是各 App 沙盒，动辄上万目录且几乎无
                    // 用户音乐，全量遍历它们正是"刷新一直转圈"的元凶——只进 media 等其余子目录。
                    if (e.name == "Android" && dir.absolutePath in roots) {
                        e.listFiles()?.forEach { sub ->
                            if (sub.isDirectory && sub.name != "data" && sub.name != "obb" &&
                                !sub.name.startsWith(".")
                            ) queue.add(sub)
                        }
                        continue
                    }
                    queue.add(e)
                } else if (e.extension.lowercase() in AUDIO_EXTS) {
                    result.add(e.absolutePath)
                }
            }
        }
        return result
    }

    /** 读磁盘缓存：path → MetaEntry。文件不存在/损坏时返回空表。 */
    private fun readCache(): MutableMap<String, MetaEntry> {
        val f = cacheFile ?: return HashMap()
        if (!f.exists()) return HashMap()
        return runCatching {
            val arr = JSONArray(f.readText())
            val map = HashMap<String, MetaEntry>(arr.length())
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                map[o.getString("p")] = MetaEntry(
                    size = o.getLong("s"),
                    mtime = o.getLong("m"),
                    durationMs = o.getLong("d"),
                    title = o.getString("t"),
                    artist = o.getString("a"),
                    album = o.optString("b", "")
                )
            }
            map
        }.getOrDefault(HashMap())
    }

    /** 写磁盘缓存（只保留本轮仍存在的文件，自动淘汰已删文件的残留条目） */
    private fun writeCache(map: Map<String, MetaEntry>) {
        val f = cacheFile ?: return
        runCatching {
            val arr = JSONArray()
            for ((path, e) in map) {
                arr.put(JSONObject().apply {
                    put("p", path); put("s", e.size); put("m", e.mtime)
                    put("d", e.durationMs); put("t", e.title); put("a", e.artist); put("b", e.album)
                })
            }
            f.writeText(arr.toString())
        }
    }

    /** 从缓存条目构建 Song */
    private fun MetaEntry.toSong(path: String): Song {
        val parent = path.substringBeforeLast('/', "")
        return Song(
            id = stableId(path),
            title = title,
            artist = artist,
            album = album,
            durationMs = durationMs,
            uri = Uri.fromFile(File(path)),
            albumArtUri = null,  // 封面由 ArtworkLoader 直接从文件读内嵌图
            filePath = path,
            folderPath = parent,
            folderName = parent.substringAfterLast('/', "根目录").ifEmpty { "根目录" }
        )
    }

    /**
     * 秒开路径：只读元数据缓存直接建库，不碰磁盘扫描。
     *
     * 冷启动/重进应用时先用它瞬时出歌（缓存是 filesDir 里的一份 JSON，读+解析毫秒级），
     * 避免每次都等全盘 BFS 遍历。返回空表示还没有缓存（首次安装/清过数据），
     * 调用方应回退到 [loadSongs] 做完整扫描。
     *
     * 注意：这里信任缓存、不校验文件是否还在（校验要 stat 每个文件，又变慢）。
     * 增删改的纠正交给随后后台跑的 [loadSongs]——它会重扫磁盘并回写缓存。
     */
    suspend fun loadFromCache(context: Context): List<Song> = withContext(Dispatchers.IO) {
        init(context)
        readCache().entries
            .sortedByDescending { it.value.mtime }  // 用缓存里的 mtime，不再 stat 文件
            .map { (path, meta) -> meta.toSong(path) }
    }

    /** 读单个音频文件元数据 → MetaEntry；太短/读取失败返回 null */
    private fun readMeta(file: File): MetaEntry? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.absolutePath)
            val durationMs = retriever
                .extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: 0L
            if (durationMs < MIN_DURATION_MS) return null
            val title = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)
                ?.takeIf { it.isNotBlank() } ?: file.nameWithoutExtension
            val artist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)
                ?.takeIf { it.isNotBlank() && it != "<unknown>" } ?: "未知艺术家"
            val album = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM)
                ?.takeIf { it.isNotBlank() } ?: ""
            MetaEntry(file.length(), file.lastModified(), durationMs, title, artist, album)
        } catch (_: Throwable) {
            null
        } finally {
            runCatching { retriever.release() }
        }
    }

    /** 路径 → 稳定正数 id（供喜欢/播放历史等按 id 索引） */
    private fun stableId(path: String): Long {
        var h = 1125899906842597L  // 大质数
        for (c in path) h = 31 * h + c.code
        return h and Long.MAX_VALUE
    }

    /**
     * 移动文件侦测与 ID 迁移。
     *
     * 歌曲 ID 是路径哈希（[stableId]），直接把文件挪个文件夹，旧 ID 就成了孤儿：
     * 点赞、播放历史、歌单、听歌明细全部对不上，排行榜显示"已移除的歌曲"。
     * 这里对比上一轮缓存与本轮扫描：旧路径消失 + 新路径的元数据指纹（标题+艺术家+
     * 曲长）完全一致 → 判定为同一文件被移动，产出 oldId→newId 映射，
     * 把各存储里按 ID 记账的记录整体搬家。
     *
     * 指纹要求在"消失组"和"新出现组"里都唯一才映射——两杯同秒同长的翻唱
     * 宁可漏迁也不错迁。纯删除（没有新路径认领）或纯新增都不触发。
     */
    private fun remapMovedSongs(oldCache: Map<String, MetaEntry>, current: Map<String, MetaEntry>) {
        // 消失的旧路径 → 元数据指纹 → 旧 ID
        val goneByKey = HashMap<String, MutableList<Long>>()
        for ((path, meta) in oldCache) {
            if (path in current) continue
            val key = moveKey(meta) ?: continue
            goneByKey.getOrPut(key) { ArrayList() }.add(stableId(path))
        }
        if (goneByKey.isEmpty()) return

        // 新出现的路径认领指纹：只在候选唯一时建立映射，认领后划走防止多对一
        val used = HashSet<Long>()
        val idMap = HashMap<Long, Long>()
        for ((path, meta) in current) {
            if (path in oldCache) continue
            val key = moveKey(meta) ?: continue
            val cands = goneByKey[key] ?: continue
            val cands2 = cands.filter { it !in used }
            if (cands2.size == 1) {
                idMap[cands2[0]] = stableId(path)
                used += cands2[0]
            }
        }
        if (idMap.isEmpty()) return

        PlayHistory.remapIds(idMap)
        Playlists.remapIds(idMap)
        ListenStats.remapIds(idMap)
    }

    /** 移动判定指纹：标题+艺术家+曲长。移动不改这些；缺标题则不参与匹配 */
    private fun moveKey(m: MetaEntry): String? {
        if (m.title.isBlank() || m.durationMs <= 0L) return null
        return m.title + ' ' + m.artist + ' ' + m.durationMs
    }

    /**
     * 扫盘 + 读元数据（命中缓存则跳过重读），构建完整曲库，
     * 按文件修改时间倒序（最近添加在前）。
     */
    suspend fun loadSongs(context: Context): List<Song> = withContext(Dispatchers.IO) {
        init(context)
        val paths = collectAudioFiles(context)
        val cache = readCache()
        val sem = Semaphore(SCAN_CONCURRENCY)

        val entries = coroutineScope {
            paths.map { path ->
                async {
                    sem.withPermit {
                        val file = File(path)
                        if (!file.exists()) return@withPermit null
                        val cached = cache[path]
                        // 指纹一致：直接用缓存，免开文件
                        val meta = if (cached != null &&
                            cached.size == file.length() && cached.mtime == file.lastModified()
                        ) cached else readMeta(file)
                        meta?.let { path to it }
                    }
                }
            }.awaitAll().filterNotNull()
        }

        val current = entries.toMap()
        // 回写缓存前先做移动侦测：旧缓存还在，才能认出"同一首歌换了路径"
        remapMovedSongs(cache, current)
        // 回写缓存：只含本轮存活文件，删掉的自然被淘汰
        writeCache(current)

        entries.map { (path, meta) -> meta.toSong(path) }
            .sortedByDescending { File(it.filePath).lastModified() }
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