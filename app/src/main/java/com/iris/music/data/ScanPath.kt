package com.iris.music.data

import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import java.io.File

/**
 * SAF 目录树 URI → 文件系统绝对路径。
 *
 * 「指定文件夹」用 OpenDocumentTree 选目录，系统返回 content://…/tree/…
 * 文档树 URI；而曲库扫描走的是直连文件系统（MusicRepository，见其类注释——
 * 不用 MediaStore 的原因），需要把树 URI 换算回真实磁盘路径。
 *
 * 只处理本地卷：
 *   primary:…  → /storage/emulated/0/…（内置存储）
 *   home:…     → /storage/emulated/0（文档选择器里的"主目录"）
 * 其余卷（第二张 SD 卡等）通过 getExternalFilesDirs 反查挂载点；
 * 拿不到或换算出的路径不是真实存在的目录时返回 null，调用方提示用户。
 */
object ScanPath {

    fun resolve(context: Context, uri: Uri): String? = runCatching {
        val docId = DocumentsContract.getTreeDocumentId(uri) ?: return null
        // 形如 "primary:Music/Pop" / "primary:" / "home:" / "0000-0000:DCIM"
        val vol = docId.substringBefore(':', "")
        val rest = docId.substringAfter(':', "")
            .replace('\\', '/')
            .split('/')
            .filter { it.isNotEmpty() && it != "." && it != ".." }
            .joinToString("/")

        val root = when (vol) {
            "primary", "home" -> Environment.getExternalStorageDirectory().absolutePath
            else -> externalVolumeRoot(context, vol) ?: return null
        }
        if (rest.isEmpty()) root else "$root/$rest"
    }.getOrNull()?.let { path ->
        // 换算结果必须是真实存在的目录，否则扫描会静默空库
        val f = File(path)
        if (f.isDirectory) path.trimEnd('/') else null
    }

    /** 通过应用专属目录反查第二存储卷挂载点：…/Android/data/<pkg>/files → 卷根 */
    private fun externalVolumeRoot(context: Context, volume: String): String? {
        for (f in context.getExternalFilesDirs(null)) {
            val p = f?.absolutePath ?: continue
            val idx = p.indexOf("/Android/data/")
            if (idx <= 0) continue
            val root = p.substring(0, idx)
            if (File(root).name == volume) return root
        }
        return null
    }
}
