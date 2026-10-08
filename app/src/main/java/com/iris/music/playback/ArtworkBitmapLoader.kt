package com.iris.music.playback

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.media3.session.BitmapLoader
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import com.iris.music.ui.ArtworkLoader
import java.util.concurrent.Executors
import kotlinx.coroutines.runBlocking

/**
 * 锁屏 / 通知栏 / 车机封面提供器。
 *
 * 背景：MediaItem 只带 artworkUri，通知栏/锁屏/Android Auto 需要 SystemUI 侧自行
 * 解出这张图；而我们的封面 URI 指向 MediaStore 索引，跨进程解码在部分 ROM 上
 * 不可用，导致封面常年空白。media3 的 MediaSession.Builder.setBitmapLoader
 * 允许 App 进程直接把 Bitmap 喂给 SystemUI，本类就是那座桥。
 *
 * 实现要点：
 * - 接口签名 loadBitmap(Uri): ListenableFuture，回调线程禁止阻塞；用后台线程池
 *   异步填充 SettableFuture。
 * - 键归一化：artworkUri 多为纯文件路径，直接命中 ArtworkLoader 的
 *   路径缓存（UI 看过的封面零成本）；content URI 才走 URI 解码回退。
 * - URI 回退优先取当前播放项的 filePath 换路径键，避免重复解码内嵌封面。
 */
class ArtworkBitmapLoader private constructor(private val appContext: Context) : BitmapLoader {

    companion object {
        @Volatile
        private var instance: ArtworkBitmapLoader? = null

        fun get(context: Context): ArtworkBitmapLoader =
            instance ?: synchronized(this) {
                instance ?: ArtworkBitmapLoader(context.applicationContext).also { instance = it }
            }
    }

    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "artwork-bitmap-loader").apply { isDaemon = true; priority = Thread.NORM_PRIORITY - 1 }
    }

    override fun supportsMimeType(mimeType: String): Boolean = true

    /** 系统偶尔会直接把封面字节交给我们解码——同步解完立即返回即可。 */
    override fun decodeBitmap(data: ByteArray): ListenableFuture<Bitmap> {
        val future = SettableFuture.create<Bitmap>()
        val bmp = runCatching { BitmapFactory.decodeByteArray(data, 0, data.size) }.getOrNull()
        if (bmp != null) future.set(bmp)
        else future.setException(IllegalStateException("bitmap decode failed"))
        return future
    }

    override fun loadBitmap(uri: Uri): ListenableFuture<Bitmap> {
        val future = SettableFuture.create<Bitmap>()
        val path = uri.takeIf { it.scheme == null || it.scheme == "file" }?.path
        if (path != null) {
            // 快路径：内存/磁盘缓存命中直接返回，不起线程
            ArtworkLoader.peek(path)?.let { future.set(it); return future }
        }
        executor.execute {
            val bmp = runCatching {
                path?.let { runBlocking { ArtworkLoader.load(it) } } ?: decodeByUri(uri)
            }.getOrNull()
            if (bmp != null) future.set(bmp)
            else future.setException(IllegalStateException("artwork unavailable: $uri"))
        }
        return future
    }

    /** content URI 回退：MediaStore 打开音频取内嵌封面字节。 */
    private fun decodeByUri(uri: Uri): Bitmap? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(appContext, uri)
            val bytes = retriever.embeddedPicture ?: return null
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        } catch (e: Throwable) {
            null
        } finally {
            runCatching { retriever.release() }
        }
    }
}