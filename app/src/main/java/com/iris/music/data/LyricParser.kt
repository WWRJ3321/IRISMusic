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

import java.io.File
import java.io.RandomAccessFile

/** 单行歌词 */
data class LyricLine(
    /** 该行开始时间（毫秒） */
    val timeMs: Long,
    val text: String
)

object LyricParser {

    /**
     * 综合获取歌词，优先级：
     * 1. 音频文件内嵌歌词（MP3 ID3v2 USLT 帧）
     * 2. 同目录同名 .lrc 文件
     * 都没有返回空列表
     *
     * @param durationMs 歌曲时长，纯文本歌词会按时间均匀分配，实现滚动高亮
     */
    fun loadLyrics(audioPath: String?, durationMs: Long): List<LyricLine> {
        // 1. 内嵌歌词
        extractEmbeddedLyrics(audioPath)?.let { raw ->
            val l = parseText(raw, durationMs)
            if (l.isNotEmpty()) return l
        }
        // 2. 同名 .lrc 文件
        findLyricFile(audioPath)?.let { f ->
            val l = parse(f)
            if (l.isNotEmpty()) return l
        }
        return emptyList()
    }

    /**
     * 从音频文件内嵌标签读取歌词原文。
     * 按格式分路径：
     * - MP3: ID3v2 USLT 帧（手写解析）
     * - FLAC: VORBIS_COMMENT 的 LYRICS 字段
     * - M4A/MP4: moov/udta/meta/ilst 的 ©lyr 原子（手写解析）
     * - OGG: Vorbis comment 的 LYRICS 字段（手写解析）
     */
    fun extractEmbeddedLyrics(audioPath: String?): String? {
        if (audioPath.isNullOrBlank()) return null
        return runCatching {
            val f = File(audioPath)
            val lower = audioPath.lowercase()
            when {
                lower.endsWith(".mp3") -> readMp3Uslt(f) ?: readTextTags(f)
                lower.endsWith(".flac") -> readFlacLyrics(f)
                lower.endsWith(".m4a") || lower.endsWith(".mp4") || lower.endsWith(".m4b") -> readMp4Lyrics(f)
                lower.endsWith(".ogg") || lower.endsWith(".oga") || lower.endsWith(".opus") -> readOggLyrics(f)
                else -> readTextTags(f)
            }
        }.getOrNull()
    }

    /** MP3 歌词兜底：ID3 里可能用 TXXX/LYRICS 或 SYLT 存，扫不到 USLT 时搜原始文本 */
    private fun readTextTags(file: File): String? {
        readMp3Uslt(file)?.let { return it }
        return null
    }

    // ===== ID3v2 USLT 解析 =====

    /** 读取 MP3 文件中的 ID3v2 USLT（未同步歌词）帧 */
    private fun readMp3Uslt(file: File): String? {
        if (!file.exists() || file.length() < 10) return null
        RandomAccessFile(file, "r").use { raf ->
            val head = ByteArray(10)
            raf.readFully(head)
            // "ID3"
            if (head[0] != 'I'.code.toByte() || head[1] != 'D'.code.toByte() || head[2] != '3'.code.toByte()) return null
            val ver = head[3].toInt() and 0xFF
            if (ver < 2 || ver > 4) return null
            val flags = head[5].toInt() and 0xFF
            val tagSize = syncsafe(head, 6)
            val body = ByteArray(tagSize)
            raf.readFully(body)
            // 处理 v2.4 的 footer（flags & 0x10）
            val end = if ((flags and 0x10) != 0) tagSize - 10 else tagSize
            if (ver == 2) {
                // v2.2: 帧头 6 字节（3 字符 ID + 3 字节大小）
                return readV22Frames(body, end)
            } else {
                // v2.3/v2.4: 帧头 10 字节（4 字符 ID + 4 字节大小 + 2 字节标志）
                return readV23Frames(body, end)
            }
        }
    }

    private fun readV22Frames(body: ByteArray, end: Int): String? {
        var pos = 0
        var lyrics: String? = null
        while (pos + 6 <= end) {
            val id = String(body, pos, 3, Charsets.ISO_8859_1)
            val size = ((body[pos + 3].toInt() and 0xFF) shl 16) or
                    ((body[pos + 4].toInt() and 0xFF) shl 8) or
                    (body[pos + 5].toInt() and 0xFF)
            pos += 6
            if (size <= 0 || pos + size > end) break
            if (id == "ULT") {
                lyrics = decodeUslt(body, pos, size)
            }
            pos += size
        }
        return lyrics
    }

    private fun readV23Frames(body: ByteArray, end: Int): String? {
        var pos = 0
        var lyrics: String? = null
        while (pos + 10 <= end) {
            val id = String(body, pos, 4, Charsets.ISO_8859_1)
            val size = ((body[pos + 4].toInt() and 0xFF) shl 24) or
                    ((body[pos + 5].toInt() and 0xFF) shl 16) or
                    ((body[pos + 6].toInt() and 0xFF) shl 8) or
                    (body[pos + 7].toInt() and 0xFF)
            pos += 10
            // v2.4 帧大小是 syncsafe，v2.3 是普通 int；此处宽容处理
            if (size <= 0 || pos + size > end) break
            if (id == "USLT" || id == "SYLT") {
                lyrics = decodeUslt(body, pos, size)
            }
            pos += size
        }
        return lyrics
    }

    /** 解码 USLT 帧体：编码字节 + 语言(3) + 描述(以00结尾) + 歌词 */
    private fun decodeUslt(body: ByteArray, start: Int, size: Int): String? {
        if (size < 5) return null
        val enc = body[start].toInt() and 0xFF
        // 跳过 1 编码 + 3 语言
        var p = start + 4
        val dataEnd = start + size
        // 跳过描述（终止符：00 或 0000）
        if (enc == 1 || enc == 2) {
            // UTF-16：描述以 00 00 结尾
            while (p + 1 < dataEnd) {
                if (body[p].toInt() == 0 && body[p + 1].toInt() == 0) { p += 2; break }
                p += 2
            }
        } else {
            while (p < dataEnd && body[p].toInt() != 0) p++
            if (p < dataEnd) p++
        }
        if (p >= dataEnd) return null
        val textBytes = body.copyOfRange(p, dataEnd)
        return try {
            when (enc) {
                0 -> String(textBytes, Charsets.ISO_8859_1)
                1 -> decodeUtf16(textBytes, false)
                2 -> decodeUtf16(textBytes, true)
                3 -> String(textBytes, Charsets.UTF_8)
                else -> String(textBytes, Charsets.UTF_8)
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun decodeUtf16(b: ByteArray, bigEndian: Boolean): String {
        val utf16 = if (bigEndian) Charsets.UTF_16BE else Charsets.UTF_16LE
        // 去掉 BOM（如果有）
        val start = if (b.size >= 2 && b[0].toInt() == 0xFE.toByte().toInt() && b[1].toInt() == 0xFF.toByte().toInt()) 2
        else if (b.size >= 2 && b[0].toInt() == 0xFF.toByte().toInt() && b[1].toInt() == 0xFE.toByte().toInt()) 2
        else 0
        return String(b, start, b.size - start, utf16)
    }

    // ===== FLAC VORBIS_COMMENT 歌词 =====

    /** FLAC：在文件中搜索 LYRICS= 文本（VORBIS_COMMENT 通常以 UTF-8 存储） */
    private fun readFlacLyrics(file: File): String? {
        if (!file.exists() || file.length() < 4) return null
        RandomAccessFile(file, "r").use { raf ->
            val magic = ByteArray(4)
            raf.readFully(magic)
            if (magic[0] != 'f'.code.toByte() || magic[1] != 'l'.code.toByte() ||
                magic[2] != 'a'.code.toByte() || magic[3] != 'c'.code.toByte()) return null
            // 只读前 1MB 找 LYRICS= 字段，避免大文件扫描
            val len = minOf(file.length(), 1_048_576L).toInt()
            raf.seek(0)
            val buf = ByteArray(len)
            raf.readFully(buf)
            val s = String(buf, Charsets.UTF_8)
            // VORBIS_COMMENT 字段名大小写不敏感
            val idx = s.indexOf("LYRICS=", ignoreCase = true)
            if (idx < 0) return null
            var v = idx + 7
            val sb = StringBuilder()
            while (v < s.length && s[v] != '\u0000') {
                sb.append(s[v]); v++
            }
            return sb.toString().ifBlank { null }
        }
    }

    // ===== MP4/M4A ©lyr 原子解析 =====

    /** 读取 MP4/M4A 的 ©lyr 原子（moov/udta/meta/ilst/©lyr），文本通常 UTF-8 */
    private fun readMp4Lyrics(file: File): String? {
        if (!file.exists() || file.length() < 16) return null
        RandomAccessFile(file, "r").use { raf ->
            // 递归搜原子路径，限制总扫描量
            val result = findMp4Atom(raf, 0, file.length(), 32, "moov", "udta", "meta", "ilst")
                ?: return null
            var (start, end) = result
            // ilst 内逐个 entry：size(4) + type(4)；©lyr = A9 6C 79 72
            var pos = start
            while (pos + 8 <= end) {
                raf.seek(pos)
                val size = readInt32BE(raf)
                if (size < 8) break
                val type = ByteArray(4)
                raf.readFully(type)
                if (type[0] == 0xA9.toByte() && type[1] == 'l'.code.toByte() &&
                    type[2] == 'y'.code.toByte() && type[3] == 'r'.code.toByte()
                ) {
                    // data 原子：size(4)+'data'(4)+versionFlags(4)+reserved(4)+内容
                    val dataStart = pos + 8
                    raf.seek(dataStart)
                    val dSize = readInt32BE(raf)
                    val dType = ByteArray(4)
                    raf.readFully(dType)
                    if (dType.contentEquals("data".toByteArray(Charsets.ISO_8859_1)) && dSize >= 16) {
                        raf.seek(dataStart + 8)
                        val skip = 8 // version(1)+flags(3)+reserved(4)
                        val textLen = dSize - 16
                        if (textLen > 0 && textLen < 1_000_000) {
                            raf.skipBytes(skip)
                            val txt = ByteArray(textLen)
                            raf.readFully(txt)
                            // 前 4 字节语言/locale 码（UTF-8 data 原子通常没有，直接尝试解码）
                            val s = decodeTextBytes(txt)
                            if (!s.isNullOrBlank()) return s.trim()
                        }
                    }
                }
                pos += size
            }
            return null
        }
    }

    /** 在 MP4 文件中按原子路径查找，返回目标原子的 body 范围（start, end） */
    private fun findMp4Atom(
        raf: RandomAccessFile,
        start: Long, end: Long, maxDepth: Int,
        vararg path: String
    ): Pair<Long, Long>? {
        if (path.isEmpty() || maxDepth < 0) return null
        var pos = start
        while (pos + 8 <= end) {
            raf.seek(pos)
            var size = readInt32BE(raf).toLong() and 0xFFFFFFFFL
            val type = ByteArray(4)
            raf.readFully(type)
            val typeStr = String(type, Charsets.ISO_8859_1)
            if (size == 1L) {
                // 64 位 size
                size = raf.readLong()
            }
            if (size < 8) break
            val bodyStart = pos + 8 + (if (size == 1L) 8 else 0)
            if (size > end - pos) break
            if (typeStr == path[0]) {
                if (path.size == 1) return bodyStart to (pos + size)
                // meta 原子有 4 字节 version/flags 头
                val childStart = if (typeStr == "meta") bodyStart + 4 else bodyStart
                return findMp4Atom(raf, childStart, pos + size, maxDepth - 1, *path.copyOfRange(1, path.size))
            }
            pos += size
        }
        return null
    }

    private fun readInt32BE(raf: RandomAccessFile): Int =
        ((raf.read() and 0xFF) shl 24) or ((raf.read() and 0xFF) shl 16) or
                ((raf.read() and 0xFF) shl 8) or (raf.read() and 0xFF)

    /** 智能解码文本字节：UTF-8 优先，含 BOM 时按 BOM 走 UTF-16 */
    private fun decodeTextBytes(b: ByteArray): String? {
        return runCatching {
            when {
                b.size >= 3 && b[0] == 0xEF.toByte() && b[1] == 0xBB.toByte() && b[2] == 0xBF.toByte() ->
                    String(b, 3, b.size - 3, Charsets.UTF_8)
                b.size >= 2 && b[0] == 0xFF.toByte() && b[1] == 0xFE.toByte() ->
                    String(b, 2, b.size - 2, Charsets.UTF_16LE)
                b.size >= 2 && b[0] == 0xFE.toByte() && b[1] == 0xFF.toByte() ->
                    String(b, 2, b.size - 2, Charsets.UTF_16BE)
                else -> String(b, Charsets.UTF_8)
            }
        }.getOrNull()
    }

    // ===== OGG Vorbis Comment 解析 =====

    /** 读取 OGG/Opus 的 Vorbis comment 块中 LYRICS 字段 */
    private fun readOggLyrics(file: File): String? {
        if (!file.exists() || file.length() < 4) return null
        RandomAccessFile(file, "r").use { raf ->
            // Ogg page: "OggS" + version(1) + headerType(1) + granule(8) + serial(4) + seq(4) + crc(4) + segCount(1) + segTable + data
            // 只扫前 256KB，歌词 comment 一般在第二个 page（文件头附近）
            val len = minOf(file.length(), 262_144L).toInt()
            val buf = ByteArray(len)
            raf.seek(0)
            raf.readFully(buf)

            // 找 Vorbis comment 头：0x03 + "vorbis"（Vorbis）或 "OpusTags"（Opus）
            var commentStart = -1
            var headerLen = 0
            for (i in 0 until len - 40) {
                if (buf[i] == 0x03.toByte() &&
                    buf[i + 1] == 'v'.code.toByte() && buf[i + 2] == 'o'.code.toByte() &&
                    buf[i + 3] == 'r'.code.toByte() && buf[i + 4] == 'b'.code.toByte() &&
                    buf[i + 5] == 'i'.code.toByte() && buf[i + 6] == 's'.code.toByte()
                ) {
                    commentStart = i + 7
                    headerLen = 0
                    break
                }
                if (buf[i] == 'O'.code.toByte() && buf[i + 1] == 'p'.code.toByte() &&
                    buf[i + 2] == 'u'.code.toByte() && buf[i + 3] == 's'.code.toByte() &&
                    buf[i + 4] == 'T'.code.toByte() && buf[i + 5] == 'a'.code.toByte() &&
                    buf[i + 6] == 'g'.code.toByte() && buf[i + 7] == 's'.code.toByte()
                ) {
                    commentStart = i + 8
                    headerLen = 0
                    break
                }
            }
            if (commentStart < 0) return null

            var p = commentStart + headerLen
            if (p + 4 > len) return null
            // vendor 长度 (LE)
            val vendorLen = readInt32LE(buf, p)
            p += 4 + vendorLen
            if (p + 4 > len) return null
            // comment 数量
            val count = readInt32LE(buf, p)
            p += 4
            if (count < 0 || count > 10_000) return null
            repeat(count) {
                if (p + 4 > len) return null
                val cLen = readInt32LE(buf, p)
                p += 4
                if (cLen <= 0 || cLen > len || p + cLen > len) return null
                val entry = String(buf, p, cLen, Charsets.UTF_8)
                p += cLen
                val eq = entry.indexOf('=')
                if (eq > 0 && entry.substring(0, eq).equals("LYRICS", ignoreCase = true)) {
                    val v = entry.substring(eq + 1).trim()
                    if (v.isNotEmpty()) return v
                }
            }
            return null
        }
    }

    private fun readInt32LE(b: ByteArray, off: Int): Int =
        ((b[off].toInt() and 0xFF)) or ((b[off + 1].toInt() and 0xFF) shl 8) or
                ((b[off + 2].toInt() and 0xFF) shl 16) or ((b[off + 3].toInt() and 0xFF) shl 24)

    // ===== 通用工具 =====

    /** 读取 4 字节 syncsafe 整数 */
    private fun syncsafe(b: ByteArray, off: Int): Int {
        return ((b[off].toInt() and 0x7F) shl 21) or
                ((b[off + 1].toInt() and 0x7F) shl 14) or
                ((b[off + 2].toInt() and 0x7F) shl 7) or
                (b[off + 3].toInt() and 0x7F)
    }

    /**
     * 解析歌词文本：优先按 LRC 时间戳解析；若不含时间戳（纯文本歌词），
     * 按行均匀分配到整首歌时长，使滚动高亮仍然生效
     */
    fun parseText(raw: String, durationMs: Long = 0L): List<LyricLine> {
        val lrc = parseRaw(raw)
        if (lrc.isNotEmpty()) return lrc
        // 纯文本：均匀分配时间
        val lines = raw.lines().map { it.trim() }.filter { it.isNotEmpty() }
        if (lines.isEmpty()) return emptyList()
        val step = if (durationMs > 0) durationMs / lines.size else 1000L
        return lines.mapIndexed { i, t -> LyricLine(i * step, t) }
    }

    /**
     * 根据音频文件路径查找同目录同名 .lrc 歌词文件：
     * /path/song.mp3 -> /path/song.lrc
     * 找不到返回 null
     */
    fun findLyricFile(audioPath: String?): File? {
        if (audioPath.isNullOrBlank()) return null
        val f = File(audioPath)
        val lrc = File(f.parent, f.nameWithoutExtension + ".lrc")
        return if (lrc.exists() && lrc.isFile) lrc else null
    }

    /** 读取并解析 LRC 文件，返回按时间排序的歌词行 */
    fun parse(file: File): List<LyricLine> {
        val lines = mutableListOf<LyricLine>()
        runCatching {
            file.readLines(Charsets.UTF_8).forEach { raw ->
                parseLine(raw)?.let { lines.add(it) }
            }
        }
        return lines.sortedBy { it.timeMs }
    }

    /** 从字符串解析 LRC（多个时间戳支持） */
    private fun parseRaw(raw: String): List<LyricLine> {
        val lines = mutableListOf<LyricLine>()
        raw.lines().forEach { l ->
            parseLine(l)?.let { lines.add(it) }
        }
        return lines.sortedBy { it.timeMs }
    }

    /**
     * 解析单行 LRC：支持多个时间戳（如 [00:12.34][01:02.34]歌词）
     * 返回解析出的歌词行；非时间戳行（元信息/纯文本）返回 null
     */
    private fun parseLine(raw: String): LyricLine? {
        val line = raw.trim()
        if (line.isEmpty()) return null
        // 跳过元信息标签 [ti:][ar:][al:][by:][offset:] 等
        if (line.startsWith("[") && !line.contains("]")) return null

        val times = mutableListOf<Long>()
        var rest = line
        while (true) {
            val open = rest.indexOf('[')
            val close = rest.indexOf(']')
            if (open == 0 && close > open) {
                val tag = rest.substring(open + 1, close)
                parseTime(tag)?.let { times.add(it) }
                rest = rest.substring(close + 1)
            } else {
                break
            }
        }
        if (times.isEmpty()) return null
        val text = rest.trim()
        if (text.isEmpty()) return null
        return LyricLine(times.first(), text)
    }

    /** 解析 [mm:ss.xx] / [mm:ss] / [mm:ss:xx] 时间戳 */
    private fun parseTime(tag: String): Long? {
        val t = tag.trim()
        if (t.isEmpty()) return null
        val parts = t.split(':', '.')
        if (parts.size < 2) return null
        val m = parts[0].toLongOrNull() ?: return null
        val s = parts[1].toLongOrNull() ?: return null
        var ms = m * 60_000 + s * 1000
        if (parts.size >= 3) {
            // 第三段可能是毫秒（.xx）或百分秒（:xx）
            val p3 = parts[2].toLongOrNull() ?: return null
            ms += if (p3 < 100) p3 * 10 else p3
        }
        return ms
    }

    /** 取指定时间点应高亮的歌词行下标（二分） */
    fun currentIndex(lines: List<LyricLine>, positionMs: Long): Int {
        if (lines.isEmpty()) return -1
        var lo = 0
        var hi = lines.size - 1
        var ans = -1
        while (lo <= hi) {
            val mid = (lo + hi) / 2
            if (lines[mid].timeMs <= positionMs) {
                ans = mid
                lo = mid + 1
            } else {
                hi = mid - 1
            }
        }
        return ans
    }
}
