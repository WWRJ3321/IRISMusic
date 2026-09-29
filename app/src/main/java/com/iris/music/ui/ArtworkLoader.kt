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
    // 海报墙 6 格外扩余量下，同屏唯一封面可达 150~250 张；旧值 192 会在
    // 滚动中把刚解析完的封面挤出 LRU，再滚动时重新走 MediaMetadataRetriever
    // ——"卡片瞬间加载"的直接来源。256 张 × 256px RGB_565 ≈ 33MB，兼顾流畅与内存。
    private const val MEM_CACHE_MAX = 256

    /**
     * 磁盘缓存容量上限。缓存键带文件指纹（路径+长度+mtime），文件一旦改动
     * 就会产生新键的副本，旧副本若无回收会永久残留、缓存只增不减。这里给出
     * 硬上限：超出后按 lastModified 淘汰最旧的封面，缩到目标的 [DISK_TRIM_RATIO]。
     * 6MB 约可容纳上千张 256px webp，覆盖常规曲库首屏与常听歌；系统也可随时整体
     * 清空 cacheDir，因此这只是主动控容，不影响正确性。压缩用 WEBP q65 进一步省空间。
     */
    private const val DISK_MAX_BYTES = 6L * 1024 * 1024
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

    // ===== 高清层：仅供播放大卡 / 全屏等大图场景，先显缩略再无缝换高清 =====
    // 大图占内存（1080² ARGB_8888 ≈ 4.6MB），只留很小的 LRU；且绝不落磁盘——
    // 高清是"当前在看的这一张"的即时需求，不是要长期堆积的缓存，落磁盘会把
    // 刚压下去的存储又顶回来。切歌换图时旧的自然被挤出。
    private const val HIRES_TARGET_PX = 1080
    private const val HIRES_CACHE_MAX = 3
    private val hiResCache = object : LinkedHashMap<String, Bitmap>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Bitmap>?): Boolean =
            size > HIRES_CACHE_MAX
    }
    private val hiResLock = Any()
    private val hiResInFlight =
        java.util.concurrent.ConcurrentHashMap<String, kotlinx.coroutines.CompletableDeferred<Bitmap?>>()

    /**
     * 解析失败的文件路径集合：同一会话内不再重试。
     * 有界 LRU（access-order）：长会话 / 大曲库下自动淘汰最旧项，避免无上限增长。
     * 值不重要，只用键做存在性判断；所有读写都在 [lock] 下串行化。
     */
    private const val FAILED_MAX = 512
    private val failedPaths = object : LinkedHashMap<String, Unit>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Unit>?): Boolean =
            size > FAILED_MAX
    }

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

    /**
     * 高清封面：按需读取原图内嵌封面、解码到 [HIRES_TARGET_PX]（相对磁贴缓存的
     * 256px 大得多），只给播放大卡 / 全屏等大图场景。
     *
     * 用法配合：UI 先用 [peek]/[load] 拿到缩略图立即显示（不割裂），再异步调本函数
     * 拿高清并在就绪后无缝替换（清晰）。缩略图来自磁盘缓存，高清只在内存、绝不落盘。
     *
     * 找不到高清（无封面/解码失败）时返回 null，UI 应继续沿用缩略图。
     */
    suspend fun loadHiRes(filePath: String?): Bitmap? = withContext(Dispatchers.IO) {
        if (filePath.isNullOrEmpty()) return@withContext null
        synchronized(hiResLock) {
            hiResCache[filePath]?.let { return@withContext it }
        }
        if (filePath in failedPaths) return@withContext null

        val mine = kotlinx.coroutines.CompletableDeferred<Bitmap?>(null)
        val existing = hiResInFlight.putIfAbsent(filePath, mine)
        if (existing != null) return@withContext existing.await()

        try {
            val bytes = readPictureBytes(filePath)
            val bmp = if (bytes == null) null else decodeSampled(bytes, HIRES_TARGET_PX)
            if (bmp != null) synchronized(hiResLock) { hiResCache[filePath] = bmp }
            mine.complete(bmp)
            bmp
        } catch (e: Throwable) {
            mine.completeExceptionally(e)
            throw e
        } finally {
            hiResInFlight.remove(filePath, mine)
        }
    }

    /** 同步窥高清缓存：切歌前若这张已看过高清则首帧直接命中，避免再走一次缩略→高清过渡。 */
    fun peekHiRes(filePath: String?): Bitmap? {
        if (filePath.isNullOrEmpty()) return null
        synchronized(hiResLock) { return hiResCache[filePath] }
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
            synchronized(lock) { failedPaths[filePath] = Unit }
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
                            bmp.compress(Bitmap.CompressFormat.WEBP, 65, out)
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

    /**
     * 解码封面到目标边长。
     * @param targetPx 目标边长；小图（磁贴）走 RGB_565 省内存，大图（高清）走 ARGB_8888
     *   保色深避免渐变带状。
     */
    private fun decodeSampled(bytes: ByteArray, targetPx: Int = DISK_TARGET_PX): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= targetPx &&
            bounds.outHeight / (sample * 2) >= targetPx
        ) {
            sample *= 2
        }
        // 小图（≤256px 磁贴）用 RGB_565：内存减半，满屏几十张卡片占用腰斩，肉眼在缩略
        // 尺寸下几乎看不出带状；高清大图用 ARGB_8888 保色深，全屏观感更干净。
        val hiRes = targetPx > DISK_TARGET_PX
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = if (hiRes) Bitmap.Config.ARGB_8888 else Bitmap.Config.RGB_565
        }
        return runCatching {
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
        }.getOrNull()
    }

    /**
     * 读取内嵌封面原始字节，三条路径依次兜底：
     *  1. MediaMetadataRetriever —— 大多数规范文件最快、最省事。
     *  2. MediaExtractor —— ogg/opus 等场景回退。
     *  3. 自解析 ID3v2 APIC —— MMR 对部分来源（如 QQ 音乐带内嵌歌词 USLT +
     *     巨型 APIC 的 MP3）会解析中断、embeddedPicture 返回 null，MediaExtractor
     *     的 ALBUM_ART key 在 MP3 上也常拿不到。此时直接扫 ID3 标签取 APIC，
     *     覆盖这些前两路都失败的文件。
     */
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
        runCatching {
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
            } finally {
                runCatching { extractor.release() }
            }
        }

        // 路径 3：手动解析 ID3v2 的 APIC 帧
        return runCatching { parseId3v2Apic(filePath) }.getOrNull()
    }

    /**
     * 直接从 MP3 文件头部的 ID3v2 标签里抽取 APIC（内嵌封面）帧的图片字节。
     *
     * 只做够用的解析：ID3v2.2/2.3/2.4 帧遍历、tag 级 unsynchronisation 反转、
     * 扩展头跳过、APIC/PIC 帧的编码/MIME/描述字段跳过。找到第一张封面即返回。
     * 不依赖系统解析器，专治 MMR 读不到封面的文件。
     */
    private fun parseId3v2Apic(filePath: String): ByteArray? {
        val file = File(filePath)
        if (!file.exists() || file.length() < 10) return null

        java.io.RandomAccessFile(file, "r").use { raf ->
            val header = ByteArray(10)
            if (raf.read(header) != 10) return null
            if (header[0] != 'I'.code.toByte() ||
                header[1] != 'D'.code.toByte() ||
                header[2] != '3'.code.toByte()
            ) return null

            val major = header[3].toInt() and 0xFF
            val flags = header[5].toInt() and 0xFF
            val tagSize = synchsafe(header, 6)
            if (tagSize <= 0 || tagSize > file.length()) return null

            var tag = ByteArray(tagSize)
            if (raf.read(tag) != tagSize) return null

            // tag 级 unsynchronisation（v2.2/2.3 用 header flag 0x80）：还原 0xFF 0x00 -> 0xFF
            if (flags and 0x80 != 0) tag = deunsync(tag)

            var pos = 0
            // 扩展头
            if (flags and 0x40 != 0) {
                pos += if (major >= 4) {
                    synchsafe(tag, 0)
                } else {
                    beInt(tag, 0) + 4
                }
            }

            val idLen = if (major == 2) 3 else 4
            val sizeLen = if (major == 2) 3 else 4
            val frameFlagsLen = if (major == 2) 0 else 2

            while (pos + idLen + sizeLen + frameFlagsLen <= tag.size) {
                if (tag[pos].toInt() == 0) break // 到达填充区
                val id = String(tag, pos, idLen, Charsets.ISO_8859_1)
                val frameSize = when {
                    major == 2 -> ((tag[pos + 3].toInt() and 0xFF) shl 16) or
                        ((tag[pos + 4].toInt() and 0xFF) shl 8) or
                        (tag[pos + 5].toInt() and 0xFF)
                    major >= 4 -> synchsafe(tag, pos + 4)
                    else -> beInt(tag, pos + 4)
                }
                if (frameSize <= 0) break
                val bodyPos = pos + idLen + sizeLen + frameFlagsLen
                if (bodyPos + frameSize > tag.size) break

                if (id == "APIC" || id == "PIC") {
                    val pic = extractApicImage(tag, bodyPos, frameSize, id == "PIC")
                    if (pic != null && pic.size > 4) return pic
                }
                pos = bodyPos + frameSize
            }
        }
        return null
    }

    /** 从 APIC/PIC 帧体里跳过编码/MIME/图片类型/描述，返回图片原始字节。 */
    private fun extractApicImage(tag: ByteArray, start: Int, size: Int, isV22: Boolean): ByteArray? {
        var i = start
        val end = start + size
        if (i >= end) return null
        val enc = tag[i].toInt() and 0xFF; i++
        if (isV22) {
            i += 3 // v2.2：3 字节图片格式（如 "JPG"）
        } else {
            while (i < end && tag[i].toInt() != 0) i++ // MIME 以 0x00 结尾
            i++
        }
        if (i >= end) return null
        i++ // 图片类型 1 字节
        // 描述：编码 1/2（UTF-16）以双 0x00 结尾，0/3（Latin1/UTF-8）以单 0x00 结尾
        if (enc == 1 || enc == 2) {
            while (i + 1 < end && !(tag[i].toInt() == 0 && tag[i + 1].toInt() == 0)) i += 2
            i += 2
        } else {
            while (i < end && tag[i].toInt() != 0) i++
            i++
        }
        if (i >= end) return null
        return tag.copyOfRange(i, end)
    }

    /** ID3 synchsafe 整数（每字节仅低 7 位有效）。 */
    private fun synchsafe(b: ByteArray, off: Int): Int =
        ((b[off].toInt() and 0x7F) shl 21) or
            ((b[off + 1].toInt() and 0x7F) shl 14) or
            ((b[off + 2].toInt() and 0x7F) shl 7) or
            (b[off + 3].toInt() and 0x7F)

    /** 普通大端 32 位整数。 */
    private fun beInt(b: ByteArray, off: Int): Int =
        ((b[off].toInt() and 0xFF) shl 24) or
            ((b[off + 1].toInt() and 0xFF) shl 16) or
            ((b[off + 2].toInt() and 0xFF) shl 8) or
            (b[off + 3].toInt() and 0xFF)

    /** 反 unsynchronisation：把 0xFF 0x00 序列还原为 0xFF。 */
    private fun deunsync(src: ByteArray): ByteArray {
        val out = java.io.ByteArrayOutputStream(src.size)
        var i = 0
        while (i < src.size) {
            out.write(src[i].toInt())
            if (src[i] == 0xFF.toByte() && i + 1 < src.size && src[i + 1] == 0x00.toByte()) i++
            i++
        }
        return out.toByteArray()
    }
}
