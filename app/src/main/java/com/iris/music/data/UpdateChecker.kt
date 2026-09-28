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

import com.iris.music.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * 检查更新：GET GitHub Releases 最新版 tag，与当前版本比较。
 *
 * opt-in 设计：仅在设置页开关开启、且用户手动点「检查」时才发起这一次
 * 单向 HTTP GET（不上传任何数据、无遥测）；开关关闭时完全不碰网络。
 */
object UpdateChecker {

    private const val RELEASES_URL =
        "https://api.github.com/repos/WWRJ3321/IRISMusic/releases/latest"

    /** 开关持久化 key（设置页直接读写 iris_prefs，不进全局状态） */
    const val KEY_ENABLED = "update_check_enabled"

    sealed class Result {
        /** 已是最新（或远程不比本地新） */
        object UpToDate : Result()
        /** 远程有更新版本，[tag] 形如 "v3.6.7" */
        data class NewVersion(val tag: String) : Result()
        /** 网络错误 / 限流 / 解析失败 */
        object Failed : Result()
    }

    /**
     * 检查最新版本。HTTP 在 IO 线程执行，主线程可安全调用。
     */
    suspend fun check(): Result = withContext(Dispatchers.IO) {
        var conn: HttpURLConnection? = null
        try {
            conn = URL(RELEASES_URL).openConnection() as HttpURLConnection
            conn.connectTimeout = 8000
            conn.readTimeout = 8000
            // GitHub API 强制要求 User-Agent，缺了直接 403
            conn.setRequestProperty("User-Agent", "IRISMusic")
            conn.setRequestProperty("Accept", "application/vnd.github+json")
            if (conn.responseCode != 200) return@withContext Result.Failed
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            val tag = JSONObject(body).optString("tag_name")
            if (tag.isBlank()) return@withContext Result.Failed
            if (isNewer(tag, BuildConfig.VERSION_NAME)) Result.NewVersion(tag) else Result.UpToDate
        } catch (e: Exception) {
            Result.Failed
        } finally {
            conn?.disconnect()
        }
    }

    /** 语义化比较：去掉 v 前缀后按数字段逐段比较，3.10 > 3.9；相等视为不新。
     *  每段提取前导数字（"8Beta" → 8），本地 versionName 带 Beta 后缀也不会误判。 */
    fun isNewer(remote: String, local: String): Boolean {
        fun parts(s: String) = Regex("\\d+").findAll(s.removePrefix("v"))
            .map { it.value.toInt() }.toList()
        val r = parts(remote)
        val l = parts(local)
        for (i in 0 until maxOf(r.size, l.size)) {
            val rv = r.getOrElse(i) { 0 }
            val lv = l.getOrElse(i) { 0 }
            if (rv != lv) return rv > lv
        }
        return false
    }
}
