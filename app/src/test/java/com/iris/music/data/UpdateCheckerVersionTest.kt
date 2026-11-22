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

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 版本比较回归：误报更新会骚扰用户，漏报会让用户停在旧版 */
class UpdateCheckerVersionTest {

    @Test
    fun `newer patch detected`() {
        assertTrue(UpdateChecker.isNewer("v4.9.6", "4.9.5"))
        assertTrue(UpdateChecker.isNewer("4.10.0", "4.9.99"))
    }

    @Test
    fun `same version not newer`() {
        assertFalse(UpdateChecker.isNewer("v4.9.5", "4.9.5"))
        assertFalse(UpdateChecker.isNewer("4.9.5", "v4.9.5"))
    }

    @Test
    fun `older remote not newer`() {
        assertFalse(UpdateChecker.isNewer("v4.8.1", "4.9.5"))
    }

    /** 数字段比较，不是字符串比较：9 < 10 必须成立 */
    @Test
    fun `numeric not lexicographic`() {
        assertTrue(UpdateChecker.isNewer("4.10", "4.9"))
        assertFalse(UpdateChecker.isNewer("4.2", "4.10"))
    }

    /** 版本段数不同：缺的段按 0 补齐比较 */
    @Test
    fun `missing segments padded with zero`() {
        assertTrue(UpdateChecker.isNewer("4.9.5.1", "4.9.5"))
        assertFalse(UpdateChecker.isNewer("4.9", "4.9.0"))
    }

    /** Beta 后缀：数字部分相同时不误报（Beta 不参与数字比较） */
    @Test
    fun `beta suffix does not false positive`() {
        assertFalse(UpdateChecker.isNewer("4.9.5", "4.9.5Beta"))
        assertFalse(UpdateChecker.isNewer("v4.9.5-Beta", "4.9.5"))
    }

    /** 乱输入不抛异常：坏了就当"没更新" */
    @Test
    fun `garbage input does not throw`() {
        assertFalse(UpdateChecker.isNewer("", "4.9.5"))
        assertFalse(UpdateChecker.isNewer("v", "4.9.5"))
        assertFalse(UpdateChecker.isNewer(" Garbage!@#", "4.9.5"))
    }
}