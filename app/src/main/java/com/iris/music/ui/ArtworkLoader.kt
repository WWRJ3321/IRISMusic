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

package com.iris.music.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaExtractor
import android.media.MediaMetadataRetriever
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.LinkedHashMap
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicLong

/**
 * 封面加载器：直接从音频文件读取内嵌封面。
 *
 * 三层加速，解决「进应用 / 切墙那一面封面加载慢」：
 *  1. 内存 LRU：同会话内命中，避免反复解析。
 *  2. 磁盘缓存：把解码后的缩小封面持久化到 cacheDir，进程重启后直接读图，
 *     不再重新打开音频文件解析内嵌封面（MediaMetadataRetriever 极慢）。
 *  3. 缩小到磁贴实际需要的尺寸（≤256px），大幅降低解码开销。
 *
 * 双路径解析：
 *  1. MediaMetadataRetriever.embeddedPicture —— MP3/FLAC/m4a 大多数情况 OK。
 *  2. MediaExtractor + METADATA_KEY_ALBUM_ART —— 回退路径，对 ogg 更可靠。
 */
object ArtworkLoader {

    private const val DISK_TARGET_PX = 256       // 磁贴显示尺寸，够用且解码快
    // 封面网格较多时，同屏唯一封面可达 150~250 张；旧值 192 会在
    // 滚动中把刚解析完的封面挤出 LRU，再滚动时重新走 MediaMetadataRetriever
    // ——"卡片瞬间加载"的直接来源。384 张 × 256px RGB_565 ≈ 50MB，可承受。
    private const val MEM_CACHE_MAX = 384

    /**
     * 磁盘缓存容量上限。缓存键带文件指纹（路径+长度+mtime），文件一旦改动
     * 就会产生新键的副本，旧副本若无回收会永久残留、缓存只增不减。这里给出
     * 硬上限：超出后按 lastModified 淘汰最旧的封面，缩到目标的 [DISK_TRIM_RATIO]。
     * 32MB 约可容纳数千张 256px webp，覆盖绝大多数曲库；系统也可随时整体清空
     * cacheDir，因此这只是主动控容，不影响正确性。
     */
    private const val DISK_MAX_BYTES = 32L * 1024 * 1024
    private const val DISK_TRIM_RATIO = 0.8      // 超限后删到 80%，留出余量避免频繁触发

    /** 磁盘缓存目录（延迟初始化，避免在类加载时触碰 Context） */
    @Volatile private var diskDir: File? = null

    /** 目录当前占用字节数。init 扫描一次、每次写盘累加，避免滚动时反复全目录扫描。 */
    private val diskBytes = AtomicLong(0L)
    @Volatile private var trimmedSinceOverLimit = false

    fun init(context: Context) {
        if (diskDir == null) {
            val dir = File(context.applicationContext.cacheDir, "artwork").apply { mkdirs() }
            diskDir = dir
            ioScope.launch {
                var total = 0L
                dir.listFiles()?.forEach { total += it.length() }
                diskBytes.set(total)
                if (total > DISK_MAX_BYTES) trimDisk()
            }
        }
    }

    /**
     * 磁盘缓存键：带文件指纹（长度 + mtime），文件内容变了键自然不同，
     * 不会读到过期封面。代价是三次文件 IO，所以只能在 IO 线程调用。
     */
    private fun keyOf(path: String): String {
        val f = java.io.File(path)
        val exists = f.exists()
        return if (exists) "$path|${f.length()}|${f.lastModified()}" else path
    }

    /**
     * 内存缓存键：纯路径，零 IO，可在主线程调用（见 [peek]）。
     *
     * 不带文件指纹是有意的：同一进程生命周期内用户几乎不会改动正在听的
     * 音频文件，而为了这个边缘情况在每次组合都做三次 stat 不划算。
     * 真发生了文件替换，[load] 走磁盘键时会发现指纹不符并重新解析。
     */
    private fun memKeyOf(path: String): String = path

    private val cache = object : LinkedHashMap<String, Bitmap>(128, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Bitmap>?): Boolean =
            size > MEM_CACHE_MAX
    }
    private val lock = Any()

    /** 解析失败的文件路径集合：同一会话内不再重试 */
    private val failedPaths = mutableSetOf<String>()

    /** 磁盘写入互斥：同一文件并发写保护，同时串行化容量计数与淘汰 */
    private val diskMutex = Mutex()

    /**
     * 进行中的加载：同一封面的并发请求（磁贴自加载 + 墙层预热 + 滚动中
     * 多个 prefetch 批次）合并到同一个 Deferred，只解析一次。
     * 没有它时，后到的请求会重复打开音频文件做 MediaMetadataRetriever 解析——
     * 那正是"新露出的卡片来不及加载"的排队源头。
     */
    private val inFlight = java.util.concurrent.ConcurrentHashMap<String, kotlinx.coroutines.CompletableDeferred<Bitmap?>>()

    /** 缓存落盘/淘汰共用的后台作用域 */
    private val ioScope = CoroutineScope(Dispatchers.IO)

    private fun sha1(path: String): String =
        MessageDigest.getInstance("SHA-1").digest(path.toByteArray())
            .joinToString("") { "%02x".format(it) }

    private fun diskFile(path: String): File? = diskDir?.let { File(it, sha1(keyOf(path)) + ".webp") }

    /**
     * 同步窥视：只查内存缓存，绝不碰磁盘。
     *
     * 这个函数在 Composable 组合期被调用（SongArtwork / AlbumArt 的初始值），
     * 也就是主线程。早期版本在这里做了两件危险的事：
     *  1. keyOf() 里的 File.exists()/length()/lastModified() —— 三次同步文件 IO；
     *  2. 未命中内存时 BitmapFactory.decodeFile() —— 整张图的同步磁盘解码。
     * 列表快速滚动时每个新出现的行都要付这笔钱，直接表现为掉帧。
     *
     * 现在磁盘读取全部交给 [load] 的 IO 协程；这里只做纯内存查表（O(1)、无 IO）。
     * 代价是磁盘命中的那一帧会先显示占位、下一帧换上真图，但不再阻塞主线程。
     */
    fun peek(filePath: String?): Bitmap? {
        if (filePath.isNullOrEmpty()) return null
        synchronized(lock) { return cache[memKeyOf(filePath)] }
    }

    suspend fun load(filePath: String?): Bitmap? = withContext(Dispatchers.IO) {
        if (filePath.isNullOrEmpty()) return@withContext null
        // 内存用纯路径键（与 peek 一致），磁盘用带指纹的键（防过期）
        val key = memKeyOf(filePath)
        synchronized(lock) {
            cache[key]?.let { return@withContext it }
            if (filePath in failedPaths) return@withContext null
        }

        // 请求合并：已有同路径解析在途时挂到它上面等结果，绝不重复解析。
        // putIfAbsent 返回非 null 即"别人已在做"。
        val mine = kotlinx.coroutines.CompletableDeferred<Bitmap?>(null)
        val existing = inFlight.putIfAbsent(key, mine)
        if (existing != null) return@withContext existing.await()

        try {
            val bmp = loadUncached(filePath, key)
            mine.complete(bmp)
            bmp
        } catch (e: Throwable) {
            mine.completeExceptionally(e)
            throw e
        } finally {
            inFlight.remove(key, mine)
        }
    }

    private fun loadUncached(filePath: String, key: String): Bitmap? {
        // 磁盘缓存命中：直接读图，跳过音频解析
        val df = diskFile(filePath)
        if (df != null && df.exists()) {
            val bmp = runCatching { BitmapFactory.decodeFile(df.absolutePath) }.getOrNull()
            if (bmp != null) {
                synchronized(lock) { cache[key] = bmp }
                return bmp
            }
        }

        val bytes = readPictureBytes(filePath)
        if (bytes == null) {
            synchronized(lock) { failedPaths.add(filePath) }
            return null
        }

        val bmp = decodeSampled(bytes)
        if (bmp != null) {
            synchronized(lock) { cache[key] = bmp }
            // 异步落盘（不阻塞返回，封面先显示，缓存后补）
            if (df != null) {
                ioScope.launch {
                    diskMutex.withLock {
                        runCatching {
                            val existed = df.length()
                            val out = java.io.FileOutputStream(df)
                            bmp.compress(Bitmap.CompressFormat.WEBP, 85, out)
                            out.flush(); out.close()
                            diskBytes.addAndGet(df.length() - existed)
                        }
                        // 写超了才淘汰；trim 后留余量，缩回阈值以下前不再重复扫描
                        if (diskBytes.get() > DISK_MAX_BYTES) trimDiskLocked() else trimmedSinceOverLimit = false
                    }
                }
            }
        }
        return bmp
    }

    /**
     * 容量淘汰：按 lastModified 从旧到新删除，直到占用缩到上限的 [DISK_TRIM_RATIO]。
     * 一次淘汰后立标志位，避免在重新涨回上限前每张图都全目录扫描排序。需持有 diskMutex。
     */
    private suspend fun trimDisk() = diskMutex.withLock { trimDiskLocked() }

    private fun trimDiskLocked() {
        val dir = diskDir ?: return
        if (diskBytes.get() <= DISK_MAX_BYTES) { trimmedSinceOverLimit = false; return }
        if (trimmedSinceOverLimit) return
        val files = dir.listFiles()?.sortedBy { it.lastModified() } ?: return
        val target = (DISK_MAX_BYTES * DISK_TRIM_RATIO).toLong()
        var used = diskBytes.get()
        for (f in files) {
            if (used <= target) break
            val len = f.length()
            if (f.delete()) used -= len
        }
        diskBytes.set(if (used < 0L) 0L else used)
        trimmedSinceOverLimit = diskBytes.get() > DISK_MAX_BYTES
    }

    private fun decodeSampled(bytes: ByteArray): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= DISK_TARGET_PX &&
            bounds.outHeight / (sample * 2) >= DISK_TARGET_PX
        ) {
            sample *= 2
        }
                // RGB_565：封面缩略图是 ≤256px 的小图，565 相对 ARGB_8888 内存减半，
        // 满屏几十张卡片的位图占用直接腰斩；肉眼在缩略尺寸下几乎看不出带状。
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.RGB_565
        }
        return runCatching {
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
        }.getOrNull()
    }

    /** 读取内嵌封面原始字节：先 MediaMetadataRetriever，失败换 MediaExtractor */
    private fun readPictureBytes(filePath: String): ByteArray? {
        // 路径 1：MediaMetadataRetriever
        runCatching {
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(filePath)
                retriever.embeddedPicture?.let { if (it.isNotEmpty()) return it }
            } finally {
                runCatching { retriever.release() }
            }
        }

        // 路径 2：MediaExtractor（ogg/opus 等场景回退）
        return runCatching {
            val extractor = MediaExtractor()
            try {
                extractor.setDataSource(filePath)
                (0 until extractor.trackCount).forEach { i ->
                    val format = extractor.getTrackFormat(i)
                    format.keys?.forEach { key ->
                        val value = format.getByteBuffer(key)
                        if (value != null && value.remaining() > 4) {
                            val arr = ByteArray(value.remaining())
                            value.get(arr)
                            // 封面特征：JPEG/PNG 魔数
                            if ((arr[0] == 0xFF.toByte() && arr[1] == 0xD8.toByte()) ||
                                (arr[0] == 0x89.toByte() && arr[1] == 0x50.toByte())
                            ) {
                                return arr
                            }
                        }
                    }
                }
                null
            } finally {
                runCatching { extractor.release() }
            }
        }.getOrNull()
    }
}
