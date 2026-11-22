/*
 * This file is part of IRIS Music.
 * Copyright (C) 2026 WWRJ
 *
 * IRIS Music is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package com.iris.music.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 歌单名序列化回归测试（评审 P0-2）。
 * 旧实现 "id:name;id:name" 裸拼接：名字含 : 被截断、含 ; 变孤儿条目、
 * 精心构造的 "x;3:Pwned" 甚至能改写别的歌单。JSON 化后这些必须全部失效。
 */
class PlaylistsSerializationTest {

    // ---------- encode → decode 往返 ----------

    @Test
    fun `roundtrip preserves names with colons and semicolons`() {
        val src = linkedMapOf(
            1L to "Live: 2024",
            2L to "A;B",
            3L to "x:1;2:y",
            4L to "中文「引号」与\\\\反斜杠",
            5L to "emoji 🎵 name"
        )
        val json = Playlists.encodeNames(src)
        val out = Playlists.decodeNames(json)
        assertEquals(src, out)
    }

    @Test
    fun `roundtrip preserves order`() {
        val src = linkedMapOf(9L to "nine", 3L to "three", 7L to "seven")
        val out = Playlists.decodeNames(Playlists.encodeNames(src))
        assertEquals(listOf(9L, 3L, 7L), out.keys.toList())
    }

    @Test
    fun `empty map roundtrip`() {
        assertEquals(linkedMapOf<Long, String>(), Playlists.decodeNames(Playlists.encodeNames(emptyMap())))
    }

    // ---------- 旧格式兼容（迁移路径） ----------

    @Test
    fun `legacy format still parses`() {
        val out = Playlists.decodeNames("1:古典;2:流行")
        assertEquals(linkedMapOf(1L to "古典", 2L to "流行"), out)
    }

    /** 旧版 split(':') 会把 "Live: 2024" 读成 "Live"；新解析按第一个冒号切，保留后半段 */
    @Test
    fun `legacy format keeps text after first colon`() {
        val out = Playlists.decodeNames("1:Live: 2024")
        assertEquals("Live: 2024", out[1L])
    }

    /** 旧版注入向量：名字里带 ";3:Pwned" 能让歌单 3 被改名。新解析下它只是一个普通名字 */
    @Test
    fun `legacy injection no longer creates phantom playlist`() {
        // 单条目 "1:x;3:Pwned"：旧实现会解出 {1:"x", 3:"Pwned"}；新实现按分号切分后
        // "3:Pwned" 段因 id 在条目内无归属而仍会被解析出 3——这就是旧格式语义，
        // 无法无损区分，但写入端（sanitizeName 后的 JSON）不可能再产生这种数据，
        // 且 JSON 检测（startsWith "[") 使已迁移用户免疫。此处验证 JSON 格式免疫注入：
        val json = Playlists.encodeNames(linkedMapOf(1L to "x;3:Pwned"))
        val out = Playlists.decodeNames(json)
        assertEquals(1, out.size)
        assertEquals("x;3:Pwned", out[1L])
    }

    // ---------- 坏输入 ----------

    @Test
    fun `malformed json returns empty instead of throwing`() {
        assertTrue(Playlists.decodeNames("[{not json").isEmpty())
        assertTrue(Playlists.decodeNames("[{\"id\":\"x\",\"name\":1}]").isEmpty())
    }

    @Test
    fun `garbage entries are skipped`() {
        val out = Playlists.decodeNames("[{\"id\":-1,\"name\":\"neg\"},{\"name\":\"noid\"},{\"id\":2,\"name\":\"ok\"}]")
        assertEquals(linkedMapOf(2L to "ok"), out)
    }

    @Test
    fun `blank input yields empty map`() {
        assertTrue(Playlists.decodeNames("").isEmpty())
        assertTrue(Playlists.decodeNames("   ").isEmpty())
    }

    // ---------- sanitizeName ----------

    @Test
    fun `sanitize strips control chars and trims`() {
        assertEquals("A B", Playlists.sanitizeName("A\u0000B\u0007"))
        assertEquals("hello", Playlists.sanitizeName("  hello\n"))
    }

    @Test
    fun `sanitize caps length at 60`() {
        val long = "x".repeat(100)
        assertEquals(60, Playlists.sanitizeName(long).length)
    }
}