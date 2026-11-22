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
import org.junit.Test

/**
 * LyricParser.currentIndex 二分回归（评审 P1-12 的起点：纯函数先测起来）。
 * 唱词高亮/封面歌词多行都靠它定位当前句，错位 = 高亮乱跳。
 */
class LyricParserIndexTest {

    private fun lines(vararg times: Long) = times.map { LyricLine(it, "t@${it}") }

    @Test
    fun `empty list returns minus one`() {
        assertEquals(-1, LyricParser.currentIndex(emptyList(), 1000))
    }

    @Test
    fun `before first line returns minus one`() {
        val ls = lines(1000, 5000, 9000)
        assertEquals(-1, LyricParser.currentIndex(ls, 0))
        assertEquals(-1, LyricParser.currentIndex(ls, 999))
    }

    @Test
    fun `exact hit returns that line`() {
        val ls = lines(1000, 5000, 9000)
        assertEquals(0, LyricParser.currentIndex(ls, 1000))
        assertEquals(1, LyricParser.currentIndex(ls, 5000))
        assertEquals(2, LyricParser.currentIndex(ls, 9000))
    }

    @Test
    fun `between lines returns previous`() {
        val ls = lines(1000, 5000, 9000)
        assertEquals(0, LyricParser.currentIndex(ls, 4999))
        assertEquals(1, LyricParser.currentIndex(ls, 8999))
    }

    @Test
    fun `after last line returns last`() {
        val ls = lines(1000, 5000, 9000)
        assertEquals(2, LyricParser.currentIndex(ls, 60_000))
    }

    /** 无序输入（解析器不该产生，但防御）不抛异常即可，行为不保证 */
    @Test
    fun `unsorted input does not throw`() {
        val ls = lines(9000, 1000, 5000)
        LyricParser.currentIndex(ls, 4000) // 不抛即通过
    }

    /** 重复时间戳的二分语义：`<=` 一路右移 → 取最后一个满足项（同刻多行显示最后一句） */
    @Test
    fun `duplicate timestamps pick last`() {
        val ls = listOf(LyricLine(1000, "a"), LyricLine(1000, "b"))
        assertEquals(1, LyricParser.currentIndex(ls, 1000))
    }
}