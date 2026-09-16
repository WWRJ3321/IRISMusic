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

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.exponentialDecay
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculateCentroidSize
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.zIndex
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.iris.music.data.LyricLine
import com.iris.music.data.LyricParser
import com.iris.music.data.Song
import com.iris.music.player.PlayerUiState
import com.iris.music.ui.theme.GlassLevel
import com.iris.music.ui.theme.IrisColors
import com.iris.music.ui.theme.IrisShape
import com.iris.music.ui.theme.irisSurface
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** 一块磁贴：画布网格坐标 + 跨度（单位 = 1 个 cell） */
private class WallTile(
    val index: Int,
    val col: Int, val row: Int,
    val spanW: Int, val spanH: Int
)

/** 画布四周的空缓冲带（格数）：active 放大 2×2 时永远有空间，零钳制零跳位。 */
private const val WALL_GUARD = 2

/**
 * 四分方块装箱：画布划分为 cols×rows 的 2×2 方块网格，每个方块内按种子
 * 随机选择一种分裂方式：
 *   0) 整块 2×2（1 磁贴）
 *   1) 两个 2×1（上下）
 *   2) 两个 1×2（左右）
 *   3) 四个 1×1
 * 方块之间完全相邻、无间隙——画布 100% 填满（数学保证，无空洞无重叠），
 * 平铺出去永远不会出现空白带。布局与 activeIdx 完全无关：换歌零重排、
 * 视角零干扰。多余的方块重复利用：队列长度不足一屏时从起点循环填充。
 */
private fun packWall(count: Int, cols: Int, seed: Long, prefOf: (Int) -> Float = { 0f }): Triple<List<WallTile>, Int, Int> {
    if (count <= 0) return Triple(emptyList<WallTile>(), 0, 0)
    // 方块列数：小队列用窄画布（方块列少、方块行多），保证画布至少 2 行方块，
    // 让 active 展开成 2×2 时有足够纵向空间——之前极小队列会压成单行（rows=2），
    // 长条卡互相包围时根本展不开。
    val qcols = (cols / 2).coerceAtLeast(2)
        .coerceAtMost(ceil(sqrt(count.toDouble())).toInt().coerceAtLeast(2))
    // 方块行数：画布是 qcols×qrows 个完整 2×2 方块拼成的矩形（每方块恒 4 格，
    // 无缝平铺）。每方块放 1/2/4 张磁贴，故总磁贴数 ∈ [B, 4B]（B=qcols*qrows）。
    // 取 qrows 使 count 落在 [B,4B]，从而"每首歌恰好一张磁贴"且矩形完整——
    // 彻底消除旧版 idx%count 循环填充导致的"同首歌多副本 + 无限平铺首尾相接显双卡"。
    val minRows = ceil(count / (4.0 * qcols)).toInt().coerceAtLeast(1)
    val maxRows = (count / qcols).coerceAtLeast(1)
    val qrows = (count / (2.0 * qcols)).roundToInt().coerceAtLeast(1).coerceIn(minRows, maxRows)
    val B = qcols * qrows

    var rng = seed
    fun rnd(): Int {
        rng = rng xor (rng shl 13); rng = rng xor (rng shr 7); rng = rng xor (rng shl 17)
        return (rng and 0x7FFFFFFF).toInt()
    }

    // 第一步：给 B 个方块各分配磁贴数 kind ∈ {1,2,4}，且 sum(kind) == count。
    // 逐方块贪心：候选 k 必须让"剩余歌曲数 - k"仍落在剩余方块的可行区间
    // [blocksAfter*1, blocksAfter*4]，保证后续一定能恰好排完、不溢出也不欠。
    val kinds = IntArray(B)
    var remaining = count
    var firstIdx = 0
    for (b in 0 until B) {
        val blocksAfter = B - b - 1
        val lo = blocksAfter                 // 每方块至少 1
        val hi = blocksAfter * 4             // 每方块最多 4
        val cands = ArrayList<Int>(3)
        for (k in intArrayOf(1, 2, 4)) if (remaining - k in lo..hi) cands.add(k)
        val k = if (cands.isEmpty()) {
            if (blocksAfter == 0) remaining.coerceIn(1, 4) else remaining.coerceIn(lo, hi.coerceAtLeast(lo))
        } else if (cands.size > 1) {
            // 偏好驱动：越喜欢的歌，该方块越倾向大 kind（大卡）。bigProb 0.25→0.75。
            val pref = prefOf(firstIdx).coerceIn(0f, 1f)
            val bigProb = 0.25f + 0.5f * pref
            val maxK = cands.max()
            if ((rnd() % 1000) / 1000f < bigProb) maxK else cands[rnd() % cands.size]
        } else cands[0]
        kinds[b] = k
        firstIdx += k
        remaining -= k
    }

    // 第二步：按 kind 铺贴。idx 连续递增（构造保证 idx<count，绝不再取模产生副本）。
    // 每方块无论 kind 都铺满自身 4 格，故画布 100% 填满、零洞零重叠。
    val tiles = ArrayList<WallTile>(count)
    var idx = 0
    for (b in 0 until B) {
        val qrow = b / qcols
        val qcol = b % qcols
        val col = qcol * 2
        val row = qrow * 2
        when (kinds[b]) {
            1 -> {                                   // 整块 2×2
                tiles.add(WallTile(idx, col, row, 2, 2)); idx++
            }
            2 -> {                                   // 上下两个 2×1 或 左右两个 1×2
                if (rnd() and 1 == 0) {
                    tiles.add(WallTile(idx, col, row, 2, 1)); idx++
                    tiles.add(WallTile(idx, col, row + 1, 2, 1)); idx++
                } else {
                    tiles.add(WallTile(idx, col, row, 1, 2)); idx++
                    tiles.add(WallTile(idx, col + 1, row, 1, 2)); idx++
                }
            }
            else -> {                                // 四个 1×1
                tiles.add(WallTile(idx, col, row, 1, 1)); idx++
                tiles.add(WallTile(idx, col + 1, row, 1, 1)); idx++
                tiles.add(WallTile(idx, col, row + 1, 1, 1)); idx++
                tiles.add(WallTile(idx, col + 1, row + 1, 1, 1)); idx++
            }
        }
    }
    // 画布高度 = 方块行数 × 2，宽度 = qcols × 2
    return Triple(tiles, qrows * 2, qcols * 2)
}

/** 可见集合条目：磁贴 + 副本序号。不含逐帧坐标——集合只在跨界时变化。
 *  结构相等（tile + rx + ry）：visible 是 derivedStateOf 每帧重算的列表，若条目缺
 *  equals，Compose 结构相等比较永远判不等 → 每帧都当作“集合变了”，整墙重组 +
 *  LaunchedEffect(visible) 每帧重启封面加载——这是缩放“一闪一闪”的真正根因。
 *  加上 equals 后，只有真正跨界时才触发重组与封面加载。 */
private class VisibleTile(val tile: WallTile, val rx: Int, val ry: Int) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is VisibleTile) return false
        return tile === other.tile && rx == other.rx && ry == other.ry
    }
    override fun hashCode(): Int = System.identityHashCode(tile) * 31 * 31 + rx * 31 + ry
}

/** 求解后的布局：磁贴 index/位置/尺寸（画布网格坐标，可能被让位移动过）。
 *  [active] 标记"真正的播放卡"——同一首歌有多个副本时，只有被求解器放大的那个才是，
 *  其余副本保持普通尺寸，绝不允许屏幕上出现两个放大播放卡。 */
private class SolvedTile(
    val tile: WallTile,
    val col: Int, val row: Int,
    val spanW: Int, val spanH: Int,
    val active: Boolean = false
)

/**
 * 播放让位求解器（画布级、环形拓扑、零重叠）。
 *
 * 输入：基础布局 tiles（100% 填满 cols×rows，无重叠）+ 播放磁贴。
 * 输出：新布局——播放磁贴占满 2×2，其余磁贴全部有位置、互不重叠、不消失。
 *
 * 算法：
 *  1. active 选一个包含自身的 2×2 目标区（四个方向角候选，选让位代价最小的）。
 *  2. 目标区内的其他磁贴被"驱逐"，进入待安置队列。
 *  3. 安置策略（按序尝试）：
 *     a. 找相邻空格直接落位（1×1）；
 *     b. 缩小某个 >1×1 的邻居腾出格子（自适应缩放，锚点不动）；
 *     c. 递归：把更外层的磁贴也缩小/推走，深度上限 4 层。
 *  4. 画布是环形的（左右/上下相接，平铺后视觉连续），所以永远有外层可推，
 *     不存在"边界撞墙"——地图无限延展。
 *  5. 任何步骤失败即整体回滚为基础布局（宁可不放大，也绝不遮挡）。
 */
private fun solveWall(
    tiles: List<WallTile>,
    cols: Int,
    rows: Int,
    activeIndex: Int,
    /**
     * 用户本次点选的具体磁贴（同歌多副本场景）。传入时优先把它定为 active，
     * 让"点哪张展开哪张"——避免点小副本却展开远处的大副本、被点卡凭空消失留白。
     * 仅当该磁贴确属 activeIndex 且存在于 tiles 中时生效，否则回退"面积最大副本"。
     */
    preferred: WallTile? = null
): List<SolvedTile> {
    if (tiles.isEmpty() || cols <= 0 || rows <= 0) {
        return tiles.map { SolvedTile(it, it.col, it.row, it.spanW, it.spanH) }
    }
    // 环形归一化：画布左右/上下相接
    fun nc(c: Int) = ((c % cols) + cols) % cols
    fun nr(r: Int) = ((r % rows) + rows) % rows

    // 可变布局状态
    val n = tiles.size
    val pc = IntArray(n); val pr = IntArray(n)
    val pw = IntArray(n); val ph = IntArray(n)
    tiles.forEachIndexed { i, t -> pc[i] = t.col; pr[i] = t.row; pw[i] = t.spanW; ph[i] = t.spanH }
    // 占用网格：cell -> 磁贴序号（-1 空）
    val grid = IntArray(cols * rows) { -1 }
    fun gi(c: Int, r: Int) = nr(r) * cols + nc(c)
    fun stamp(i: Int, v: Int) {
        for (dc in 0 until pw[i]) for (dr in 0 until ph[i]) grid[gi(pc[i] + dc, pr[i] + dr)] = v
    }
    for (i in 0 until n) stamp(i, i)

    // 每首歌在画布上的实例总数（循环填充会产生副本）。让位失败时的兜底依据：
    // 某首歌若还有别的实例在画布上，则它的副本可以被安全丢弃（少一张重复卡），
    // 换来 active 正常展开——远好过"active 不展开 + 画面出现两个播放卡"。
    val counts = HashMap<Int, Int>()
    for (t in tiles) counts[t.index] = (counts[t.index] ?: 0) + 1

    // active 磁贴：同一首歌在画布上可能有多个副本（循环填充产生）。用 firstOrNull 会
    // 误取该歌的"第一个副本"——若它恰好是张 1×1 小卡，而用户点的却是同歌的某张 2×2
    // 大卡，就会出现"点 2×2 却展不开"（ai 落到小卡上，放大的是远处那张）。
    // 改为选该歌所有副本中面积最大的那个：点某首歌时，让它最大那张卡成为焦点展开。
    var ai = -1; var bestArea = -1
    // 优先：用户本次点选的具体磁贴（若确属该歌且在画布中）——"点哪张展开哪张"。
    if (preferred != null) {
        for (i in 0 until n) {
            if (tiles[i] === preferred && tiles[i].index == activeIndex) { ai = i; break }
        }
    }
    if (ai < 0) for (i in 0 until n) {
        if (tiles[i].index != activeIndex) continue
        val area = tiles[i].spanW * tiles[i].spanH
        if (area > bestArea) { bestArea = area; ai = i }
    }
    if (ai < 0) {
        // 无匹配磁贴：保持基础几何返回，但绝不标记任何 active（active 不存在）。
        return (0 until n).map {
            SolvedTile(tiles[it], tiles[it].col, tiles[it].row, tiles[it].spanW, tiles[it].spanH, false)
        }
    }
    // 放大目标尺寸 = 基础尺寸 + 1（每边），但硬封顶 2×2——所有展开卡统一偶数尺寸，
    // 彻底规避奇数(3)宽右侧与偶数周期画布的结构性错位缝。
    val MAX_SPAN = 2
    val TW = (pw[ai] + 1).coerceIn(2, minOf(cols, MAX_SPAN))
    val TH = (ph[ai] + 1).coerceIn(2, minOf(rows, MAX_SPAN))

    // 备份，失败回滚
    val bc = pc.copyOf(); val br = pr.copyOf(); val bw = pw.copyOf(); val bh = ph.copyOf()
    val bg = grid.copyOf()
    fun rollback() {
        bc.copyInto(pc); br.copyInto(pr); bw.copyInto(pw); bh.copyInto(ph); bg.copyInto(grid)
    }

    // 目标区候选：优先朝"邻居最薄/最空"的方向扩张，把新增格子摊到驱逐代价最小的一侧。
    // 关键修复右侧大缝：3×3 展开时旧代码按位移最小总选左上角(dc=0,dr=0)，
    // 新增的那一圈格子全堆到右侧+下侧，回填填不满就变成一条扎眼的竖缝。
    // 改为按"需要驱逐的邻居格子数(evict)"最小排序，让 active 朝薄邻居一侧生长、
    // 少毁卡少留洞；evict 相同再按"离原位居中"排，把多出的格子向四周平摊。
    data class Cand(val evict: Int, val center: Int, val c0: Int, val r0: Int)
    val candList = ArrayList<Cand>(4)
    for (dc in 0 downTo (pw[ai] - TW)) for (dr in 0 downTo (ph[ai] - TH)) {
        val c0 = pc[ai] + dc; val r0 = pr[ai] + dr
        if (pc[ai] >= c0 && pc[ai] + pw[ai] <= c0 + TW &&
            pr[ai] >= r0 && pr[ai] + ph[ai] <= r0 + TH &&
            c0 >= 0 && c0 + TW <= cols && r0 >= 0 && r0 + TH <= rows
        ) {
            var evict = 0
            for (ddc in 0 until TW) for (ddr in 0 until TH) {
                val o = grid[gi(c0 + ddc, r0 + ddr)]
                if (o >= 0 && o != ai) evict++
            }
            val center = abs(2 * c0 + TW - 2 * pc[ai] - pw[ai]) + abs(2 * r0 + TH - 2 * pr[ai] - ph[ai])
            candList.add(Cand(evict, center, c0, r0))
        }
    }
    candList.sortWith(compareBy({ it.evict }, { it.center }))
    val cands = candList.map { intArrayOf(it.c0, it.r0) }
    if (cands.isEmpty()) return tiles.map { SolvedTile(it, it.col, it.row, it.spanW, it.spanH) }

    // 让位安置：把磁贴 t 安置到某处（不与已占用冲突），允许递归推挤。
    // 候选格限制在画布范围内——环形归一化只用于索引计算，不允许卡片真的
    // 绕到画布另一侧（那会造成视觉上"消失又在远处出现"）。
    fun inCanvas(c: Int, r: Int) = c in 0 until cols && r in 0 until rows
    fun place(t: Int, depth: Int): Boolean {
        if (depth > 6) return false
        // 候选格：以原位置为中心按曼哈顿距离扩散（就近安置，视觉上"挪一格"）
        val oc = bc[t]; val or0 = br[t]
        val ow = bw[t]; val oh = bh[t]
        // 0) 原尺寸整体平移（优先：卡片不被缩小，只是挪走）
        val ringW = ArrayList<IntArray>(48)
        for (d in 1..5) for (dc in -d..d) for (dr in -d..d) {
            if (abs(dc) + abs(dr) != d) continue
            val cc = oc + dc; val rr = or0 + dr
            if (cc < 0 || cc + ow > cols || rr < 0 || rr + oh > rows) continue
            ringW.add(intArrayOf(cc, rr))
        }
        for (cc in ringW) {
            var free = true
            outer@ for (dc in 0 until ow) for (dr in 0 until oh) {
                val k = gi(cc[0] + dc, cc[1] + dr)
                if (grid[k] != -1 && grid[k] != t) { free = false; break@outer }
            }
            if (free) {
                pc[t] = nc(cc[0]); pr[t] = nr(cc[1])
                stamp(t, t); return true
            }
        }
        val ring = ArrayList<IntArray>(48)
        for (d in 1..5) for (dc in -d..d) for (dr in -d..d) {
            if (abs(dc) + abs(dr) != d) continue
            val cc = oc + dc; val rr = or0 + dr
            if (!inCanvas(cc, rr)) continue
            ring.add(intArrayOf(cc, rr))
        }
        // 1) 直接落空格
        for (cc in ring) {
            if (grid[gi(cc[0], cc[1])] == -1) {
                pc[t] = nc(cc[0]); pr[t] = nr(cc[1]); pw[t] = 1; ph[t] = 1
                stamp(t, t); return true
            }
        }
        // 2) 缩小占用者腾格（占用者 >1×1，且让出的不是它的锚点格）。
        // active 的其余副本绝不参与：不能被缩小、不能被推走。
        for (cc in ring) {
            val o = grid[gi(cc[0], cc[1])]
            if (o < 0 || o == t || o == ai || tiles[o].index == activeIndex) continue
            if (pw[o] * ph[o] <= 1) continue
            val keepC = pc[o]; val keepR = pr[o]
            if (nc(cc[0]) == nc(keepC) && nr(cc[1]) == nr(keepR)) continue
            stamp(o, -1)
            pw[o] = 1; ph[o] = 1; pc[o] = keepC; pr[o] = keepR
            stamp(o, o)
            if (grid[gi(cc[0], cc[1])] == -1) {
                pc[t] = nc(cc[0]); pr[t] = nr(cc[1]); pw[t] = 1; ph[t] = 1
                stamp(t, t); return true
            }
        }
        // 3) 递归：把占用者整体推走，自己占它的格。active 副本不可推。
        for (cc in ring) {
            val o = grid[gi(cc[0], cc[1])]
            if (o < 0 || o == t || o == ai || tiles[o].index == activeIndex) continue
            val sc = pc[o]; val sr = pr[o]; val sw = pw[o]; val sh = ph[o]
            stamp(o, -1)
            if (place(o, depth + 1)) {
                if (grid[gi(cc[0], cc[1])] == -1) {
                    pc[t] = nc(cc[0]); pr[t] = nr(cc[1]); pw[t] = 1; ph[t] = 1
                    stamp(t, t); return true
                }
                // 占用者走了但格子被它的安置过程占回 —— 撤销
                stamp(o, -1)
            }
            pc[o] = sc; pr[o] = sr; pw[o] = sw; ph[o] = sh
            stamp(o, o)
        }
        // 4) 远程收缩捐格：全画布找可缩小的非 active 卡，缩 1 格释放给 t（t 缩成 1×1）。
        // 满画布无处可去时的兜底——牺牲一张长条的面积换被驱逐者的容身之处。
        for (oo in 0 until n) {
            if (oo == t || oo == ai || tiles[oo].index == activeIndex) continue
            if (pw[oo] * ph[oo] <= 1) continue
            val dl = ArrayList<IntArray>(4)
            if (pw[oo] > 1) {
                dl.add(intArrayOf(pc[oo], pr[oo], pw[oo] - 1, ph[oo], pc[oo] + pw[oo] - 1, pr[oo]))
                dl.add(intArrayOf(pc[oo] + 1, pr[oo], pw[oo] - 1, ph[oo], pc[oo], pr[oo]))
            }
            if (ph[oo] > 1) {
                dl.add(intArrayOf(pc[oo], pr[oo], pw[oo], ph[oo] - 1, pc[oo], pr[oo] + ph[oo] - 1))
                dl.add(intArrayOf(pc[oo], pr[oo] + 1, pw[oo], ph[oo] - 1, pc[oo], pr[oo]))
            }
            for (q in dl) {
                val nc2 = q[0]; val nr2 = q[1]; val nw2 = q[2]; val nh2 = q[3]; val fc = q[4]; val fr = q[5]
                if (nc2 < 0 || nc2 + nw2 > cols || nr2 < 0 || nr2 + nh2 > rows) continue
                var ok2 = true
                outer2@ for (x in 0 until nw2) for (y in 0 until nh2) {
                    val k = gi(nc2 + x, nr2 + y)
                    if (grid[k] != -1 && grid[k] != oo) { ok2 = false; break@outer2 }
                }
                if (!ok2) continue
                val s0 = intArrayOf(pc[oo], pr[oo], pw[oo], ph[oo])
                stamp(oo, -1)
                pc[oo] = nc2; pr[oo] = nr2; pw[oo] = nw2; ph[oo] = nh2
                stamp(oo, oo)
                if (grid[gi(fc, fr)] == -1) {
                    pc[t] = nc(fc); pr[t] = nr(fr); pw[t] = 1; ph[t] = 1
                    stamp(t, t); return true
                }
                stamp(oo, -1)
                pc[oo] = s0[0]; pr[oo] = s0[1]; pw[oo] = s0[2]; ph[oo] = s0[3]
                stamp(oo, oo)
            }
        }
        return false
    }

    // 空洞回填：active 从 1 格撑到 4 格，多吃的 3 格由邻居缩小让出；
    // 缩小造成的面积差会留下窟窿，这里让窟窿旁边的卡片"长回去"填满
    // （1×1→2×1/1×2，2×1/1×2→2×2），迭代直到无洞。
    fun fillHoles() {
        // 全画布任意 1×1 兜底搬移（洞旁形状都不匹配时）
        fun tryMoveInto(o: Int, c: Int, r: Int): Boolean {
            if (grid[gi(c, r)] != -1) return false
            stamp(o, -1)
            pc[o] = nc(c); pr[o] = nr(r)
            stamp(o, o); return true
        }
        var moved = true; var guard = 0
        val seen = HashSet<String>()
        while (moved && guard++ < 64) {
            moved = false
            val sig = grid.joinToString(",")
            if (!seen.add(sig)) break   // 布局重复 = 洞在打转，跳出
            for (r in 0 until rows) for (c in 0 until cols) {
                if (grid[gi(c, r)] != -1) continue
                // 近邻优先：离洞最近的卡片先尝试（减少大范围搬动）
                val ranked = ArrayList<Triple<Int, Int, Int>>(4)
                for (b in arrayOf(intArrayOf(c - 1, r), intArrayOf(c + 1, r),
                                  intArrayOf(c, r - 1), intArrayOf(c, r + 1))) {
                    val o = grid[gi(b[0], b[1])]
                    // active 副本不参与回填（不能被搬走/拉长，否则屏幕上出现"假 1×2 播放卡"）
                    if (o >= 0 && tiles[o].index != activeIndex) ranked.add(Triple(abs(pc[o] - c) + abs(pr[o] - r), b[0], b[1]))
                }
                ranked.sortBy { it.first }
                var filledByNeighbor = false
                for (rk in ranked) {
                    val o = grid[gi(rk.second, rk.third)]
                    if (o < 0) continue
                    val oc = pc[o]; val or0 = pr[o]; val ow = pw[o]; val oh = ph[o]
                    fun trySet(ncc: Int, nrr: Int, nww: Int, nhh: Int): Boolean {
                        // 不允许长出画布（跨界会让卡片绕到另一侧）
                        if (ncc < 0 || ncc + nww > cols || nrr < 0 || nrr + nhh > rows) return false
                        for (dc in 0 until nww) for (dr in 0 until nhh) {
                            val k = gi(ncc + dc, nrr + dr)
                            if (grid[k] != -1 && grid[k] != o) return false
                        }
                        stamp(o, -1)
                        pc[o] = nc(ncc); pr[o] = nr(nrr); pw[o] = nww; ph[o] = nhh
                        stamp(o, o); return true
                    }
                    var done = false
                    if (ow == 1 && oh == 1) {
                        // 优先长大（真正消洞：面积+1 吸收洞格），搬移只是把洞挪到旧位
                        done = when {
                            nc(c) == nc(oc + 1) && nr(r) == nr(or0) -> trySet(oc, or0, 2, 1)
                            nc(c) == nc(oc - 1) && nr(r) == nr(or0) -> trySet(oc - 1, or0, 2, 1)
                            nr(r) == nr(or0 + 1) && nc(c) == nc(oc) -> trySet(oc, or0, 1, 2)
                            nr(r) == nr(or0 - 1) && nc(c) == nc(oc) -> trySet(oc, or0 - 1, 1, 2)
                            else -> false
                        }
                        if (!done) done = trySet(c, r, 1, 1)   // 级联搬移兜底
                    } else if (ow == 2 && oh == 1) {
                        done = when {
                            nr(r) == nr(or0 + 1) -> trySet(oc, or0, 2, 2)
                            nr(r) == nr(or0 - 1) -> trySet(oc, or0 - 1, 2, 2)
                            else -> false
                        }
                    } else if (ow == 1 && oh == 2) {
                        done = when {
                            nc(c) == nc(oc + 1) -> trySet(oc, or0, 2, 2)
                            nc(c) == nc(oc - 1) -> trySet(oc - 1, or0, 2, 2)
                            else -> false
                        }
                    }
                    if (done) { moved = true; filledByNeighbor = true; break }
                }
                // 邻居形状都不匹配：全画布找最近的 1×1 直接搬进洞（旧位变新洞继续迭代）
                if (!filledByNeighbor) {
                    var bestO = -1; var bestD = Int.MAX_VALUE
                    for (i in 0 until n) {
                        if (i == ai || tiles[i].index == activeIndex) continue
                        if (pw[i] != 1 || ph[i] != 1) continue
                        val d = abs(pc[i] - c) + abs(pr[i] - r)
                        if (d < bestD) { bestD = d; bestO = i }
                    }
                    if (bestO >= 0 && tryMoveInto(bestO, c, r)) moved = true
                }
            }
        }
    }

    // 吞洞消缝：让 active 焦点卡向四周吃掉紧邻的整列/整行空格，直到吃不下或封顶。
    // 这些格本来就是空洞（邻居回填失败留下的残缝），active 盖上去既无缝也绝不与任何
    // 真卡重叠（只吃 -1 空格），故 verifyPerfect 必过。面积封顶 12，杜绝 4×4 那种超大卡。
    fun activeSwallowHoles() {
        val AREA_CAP = 12
        var grew = true
        while (grew && pw[ai] * ph[ai] < AREA_CAP) {
            grew = false
            // 右：整列紧邻且全空
            if (pw[ai] * ph[ai] < AREA_CAP && pc[ai] + pw[ai] < cols) {
                var ok = true
                for (dr in 0 until ph[ai]) if (grid[gi(pc[ai] + pw[ai], pr[ai] + dr)] != -1) { ok = false; break }
                if (ok) { pw[ai]++; stamp(ai, ai); grew = true }
            }
            // 下：整行紧邻且全空
            if (pw[ai] * ph[ai] < AREA_CAP && pr[ai] + ph[ai] < rows) {
                var ok = true
                for (dc in 0 until pw[ai]) if (grid[gi(pc[ai] + dc, pr[ai] + ph[ai])] != -1) { ok = false; break }
                if (ok) { ph[ai]++; stamp(ai, ai); grew = true }
            }
            // 左：整列紧邻且全空（不绕出画布左边界）
            if (pw[ai] * ph[ai] < AREA_CAP && pc[ai] > 0) {
                var ok = true
                for (dr in 0 until ph[ai]) if (grid[gi(pc[ai] - 1, pr[ai] + dr)] != -1) { ok = false; break }
                if (ok) { pc[ai]--; stamp(ai, ai); grew = true }
            }
            // 上：整行紧邻且全空（不绕出画布上边界）
            if (pw[ai] * ph[ai] < AREA_CAP && pr[ai] > 0) {
                var ok = true
                for (dc in 0 until pw[ai]) if (grid[gi(pc[ai] + dc, pr[ai] - 1)] != -1) { ok = false; break }
                if (ok) { pr[ai]--; stamp(ai, ai); grew = true }
            }
        }
    }

    // 通用矩形膨胀回填：允许每张非主卡向紧邻的空格把自己的矩形 +1 格。
    // 只吃空格(-1)、不移动已有卡片、绝不碰主卡 → passA 保持无重叠、且不影响主卡展开。
    // 用来吃掉 fillHoles / activeSwallowHoles 处理不了的不规则残洞（主卡右侧的缝）。
    // 每轮只尝试四方向各 +1，多轮迭代直到没有可填的洞（guard 防死循环）。
    fun growFill() {
        var grew = true; var guard = 0
        while (grew && guard++ < 64) {
            grew = false
            for (i in 0 until n) {
                if (i == ai || tiles[i].index == activeIndex) continue   // 主卡与 active 副本不动
                if (pw[i] <= 0 || ph[i] <= 0) continue                   // 已丢弃
                fun tryGrow(nc2: Int, nr2: Int, nw: Int, nh: Int): Boolean {
                    if (nc2 < 0 || nc2 + nw > cols || nr2 < 0 || nr2 + nh > rows) return false
                    for (dc in 0 until nw) for (dr in 0 until nh) {
                        val k = gi(nc2 + dc, nr2 + dr)
                        if (grid[k] != -1 && grid[k] != i) return false
                    }
                    stamp(i, -1)
                    pc[i] = nc2; pr[i] = nr2; pw[i] = nw; ph[i] = nh
                    stamp(i, i); return true
                }
                // 右 / 下 / 左 / 上：优先把宽高超向有空格的一侧（越界检查在 tryGrow 内）
                var done = false
                if (pc[i] + pw[i] < cols && tryGrow(pc[i], pr[i], pw[i] + 1, ph[i])) done = true
                else if (pr[i] + ph[i] < rows && tryGrow(pc[i], pr[i], pw[i], ph[i] + 1)) done = true
                else if (pc[i] > 0 && tryGrow(pc[i] - 1, pr[i], pw[i] + 1, ph[i])) done = true
                else if (pr[i] > 0 && tryGrow(pc[i], pr[i] - 1, pw[i], ph[i] + 1)) done = true
                if (done) { grew = true; continue }
            }
        }
    }

    // 最终校验：必须零重叠。允许存在空洞（空白格=海报留白）——
    // 让位失败时丢弃副本卡会留下空白格，但换来 active 必展开，
    // 远比"卡死回滚 → active 不放大 + 画面出现两个播放卡"更好。
    fun verifyPerfect(): Boolean {
        val chk = IntArray(cols * rows) { -1 }
        for (i in 0 until n) {
            if (pw[i] <= 0 || ph[i] <= 0) continue   // 已被丢弃的卡不占格
            for (dc in 0 until pw[i]) for (dr in 0 until ph[i]) {
                val k = gi(pc[i] + dc, pr[i] + dr)
                if (chk[k] != -1) return false       // 重叠 = 失败
                chk[k] = i
            }
        }
        return true
    }

    // 原地收缩：磁贴只与目标区部分重叠时，只舍弃重叠部分、剩余部分原地保留。
    // 相比整卡驱逐，面积损失更小、产生的洞更少。
    fun shrinkInPlace(o: Int, tzC: Int, tzR: Int): Boolean {
        val oc = pc[o]; val or0 = pr[o]; val ow = pw[o]; val oh = ph[o]
        for (w in ow downTo 1) for (h in oh downTo 1) {
            if (w == ow && h == oh) continue
            val anchors = arrayOf(
                intArrayOf(oc, or0), intArrayOf(oc + ow - w, or0),
                intArrayOf(oc, or0 + oh - h), intArrayOf(oc + ow - w, or0 + oh - h)
            )
            for (a in anchors) {
                val cc = a[0]; val rr = a[1]
                if (cc < 0 || cc + w > cols || rr < 0 || rr + h > rows) continue
                var ok = true
                outerS@ for (dc in 0 until w) for (dr in 0 until h) {
                    val c = cc + dc; val r = rr + dr
                    if (c >= tzC && c < tzC + TW && r >= tzR && r < tzR + TH) { ok = false; break@outerS }
                    val k = gi(c, r)
                    if (grid[k] != -1 && grid[k] != o) { ok = false; break@outerS }
                }
                if (!ok) continue
                stamp(o, -1)
                pc[o] = nc(cc); pr[o] = nr(rr); pw[o] = w; ph[o] = h
                stamp(o, o); return true
            }
        }
        return false
    }

    // 副本整体平移（保尺寸）：目标区被 active 副本卡死的死局兜底——
    // 允许副本挪走（绝不缩小，只是平移，不会出现"假变形播放卡"）。
    // 落位必须完全避开目标区。
    fun relocateKeepSize(o: Int, depth: Int, tzC: Int, tzR: Int): Boolean {
        if (depth > 4) return false
        val oc = bc[o]; val or0 = br[o]; val ow = bw[o]; val oh = bh[o]
        fun hitTZ(cc: Int, rr: Int) = cc < tzC + TW && cc + ow > tzC && rr < tzR + TH && rr + oh > tzR
        for (d in 1..6) for (dc in -d..d) for (dr in -d..d) {
            if (abs(dc) + abs(dr) != d) continue
            val cc = oc + dc; val rr = or0 + dr
            if (cc < 0 || cc + ow > cols || rr < 0 || rr + oh > rows) continue
            if (hitTZ(cc, rr)) continue
            var free = true
            outerR@ for (x in 0 until ow) for (y in 0 until oh) {
                val k = gi(cc + x, rr + y)
                if (grid[k] != -1 && grid[k] != o) { free = false; break@outerR }
            }
            if (free) { pc[o] = cc; pr[o] = rr; stamp(o, o); return true }
        }
        // 推挤链：目标位置的占用者（非 active）用全套 place 手段赶走
        for (d in 1..3) for (dc in -d..d) for (dr in -d..d) {
            if (abs(dc) + abs(dr) != d) continue
            val cc = oc + dc; val rr = or0 + dr
            if (cc < 0 || cc + ow > cols || rr < 0 || rr + oh > rows) continue
            if (hitTZ(cc, rr)) continue
            val blockers = LinkedHashSet<Int>()
            for (x in 0 until ow) for (y in 0 until oh) {
                val b = grid[gi(cc + x, rr + y)]
                if (b != -1 && b != o) blockers.add(b)
            }
            if (blockers.isEmpty()) { pc[o] = cc; pr[o] = rr; stamp(o, o); return true }
            var allOk = true
            val saved = ArrayList<IntArray>()
            for (b in blockers) {
                saved.add(intArrayOf(b, pc[b], pr[b], pw[b], ph[b]))
                stamp(b, -1)
                if (!place(b, depth + 1)) { allOk = false; break }
            }
            if (allOk) {
                var free2 = true
                outerR2@ for (x in 0 until ow) for (y in 0 until oh) {
                    val k = gi(cc + x, rr + y)
                    if (grid[k] != -1 && grid[k] != o) { free2 = false; break@outerR2 }
                }
                if (free2) { pc[o] = cc; pr[o] = rr; stamp(o, o); return true }
            }
            for (q in saved.asReversed()) {
                val b = q[0]
                stamp(b, -1)
                pc[b] = q[1]; pr[b] = q[2]; pw[b] = q[3]; ph[b] = q[4]
                stamp(b, b)
            }
        }
        return false
    }

    for (cand in cands) {
        val c0 = cand[0]; val r0 = cand[1]
        // 收集目标区内需要驱逐的磁贴与卡死目标区的 active 副本。
        // active 的其余副本绝不缩小绝不覆盖——只允许整体平移让位。
        val evict0 = LinkedHashSet<Int>()
        val copies = LinkedHashSet<Int>()
        for (dc in 0 until TW) for (dr in 0 until TH) {
            val o = grid[gi(c0 + dc, r0 + dr)]
            if (o < 0 || o == ai) continue
            if (tiles[o].index == activeIndex) copies.add(o) else evict0.add(o)
        }
        val evict = ArrayList<Int>()
        for (o in evict0) { if (!shrinkInPlace(o, c0, r0)) evict.add(o) }
        // 副本整体平移让位；挪不动的副本直接丢弃（它和 ai 是同歌，ai 已经是真播放卡，
        // 副本留着只会造成"画面两个播放卡"，丢弃最干净）。
        for (o in copies) {
            stamp(o, -1)   // 先把副本从目标区摘掉
            if (!relocateKeepSize(o, 0, c0, r0)) { pw[o] = 0; ph[o] = 0 }  // 移不走就丢弃
        }
        // 清空目标区（active + 被驱逐者都先摘掉）
        stamp(ai, -1)
        for (o in evict) stamp(o, -1)
        // active 铺满目标区
        pc[ai] = nc(c0); pr[ai] = nr(r0); pw[ai] = TW; ph[ai] = TH
        stamp(ai, ai)
        var ok = true
        // 安置被驱逐的卡：放不下的副本卡直接丢弃（画布上同歌还有其他实例，
        // 少显示一张重复卡换 active 必展开）。非副本卡放不下才算失败。
        for (o in evict) {
            // 安置失败：直接丢弃这张驱逐者（画布允许留空白格）。
            // 旧逻辑只丢"有副本的卡"，无副本就判整体失败回滚 → 满铺布局里
            // 被 1×1/2×1 挤住的卡根本展不开。现在一律丢弃，保证 active 必展开。
            if (!place(o, 0)) { pw[o] = 0; ph[o] = 0 }
        }
        if (ok) {
            fillHoles()
            activeSwallowHoles()
            growFill()
            if (verifyPerfect()) {
                return (0 until n).map { SolvedTile(tiles[it], pc[it], pr[it], pw[it], ph[it], it == ai) }
            }
        }
        rollback()
    }
    // 最终硬兜底：极小画布（队列仅 1~4 首、且都是长条卡）时，环形让位可能
    // 残留重叠而整体回滚 → 播放卡展不开。这里选一个含 active 的 2×2 目标区，
    // 直接丢弃与之相交的所有非 active 磁贴（含同歌副本），强制 active 铺满。
    // 宁可在画布留空白格、少显示几张重复/邻居卡，也绝不允许播放卡不展开。
    // 正常队列（≥5 首）走不到这里——上面的常规驱逐路径已 100% 成功。
    for (cand in cands) {
        val c0 = cand[0]; val r0 = cand[1]
        stamp(ai, -1)
        val zone = HashSet<Int>()
        for (dc in 0 until TW) for (dr in 0 until TH) zone.add(gi(c0 + dc, r0 + dr))
        for (o in 0 until n) {
            if (o == ai) continue
            var inter = false
            for (dc in 0 until pw[o]) for (dr in 0 until ph[o]) {
                if (gi(pc[o] + dc, pr[o] + dr) in zone) inter = true
            }
            if (inter) { stamp(o, -1); pw[o] = 0; ph[o] = 0 }
        }
        pc[ai] = nc(c0); pr[ai] = nr(r0); pw[ai] = TW; ph[ai] = TH
        stamp(ai, ai)
        activeSwallowHoles()
        growFill()
        if (verifyPerfect()) {
            return (0 until n).map { SolvedTile(tiles[it], pc[it], pr[it], pw[it], ph[it], it == ai) }
        }
        rollback()
    }
    // 全部候选失败：保持基础布局（不放大，绝不遮挡、绝不留洞）。
    // 同样必须标出 active——宁可不放大，也不能让播放卡消失。
    return (0 until n).map {
        SolvedTile(tiles[it], tiles[it].col, tiles[it].row, tiles[it].spanW, tiles[it].spanH, it == ai)
    }
}

/** 播放磁贴放大后的 2×2 遮挡区（网格坐标）。用 data class 保证值相等时可复用 derivedStateOf。 */
private data class OcclRect(val c0: Int, val r0: Int, val w: Int, val h: Int)

/**
 * 无限海报墙：磁贴拼贴成大画布，四向平铺，拖动带惯性。
 *
 * 帧率要点：壳（本函数）是唯一读 positionMs/durationMs 的地方——进度每 500ms
 * 轮询一次，壳跟着重组，但它传给墙体的参数（队列/索引/点赞集/配色/回调）全部
 * 稳定，Compose skip 直接把墙体挡下。旧实现把这些读取写在墙体的组合期里，
 * 等于进度轮询每 500ms 让整面墙（几十张卡 + 封面 + 歌词子树）全量重组一次，
 * 就是拖动时"一顿一顿"的周期感来源。
 */
@Composable
internal fun DeckPosterWall(
    state: PlayerUiState,
    colors: IrisColors,
    onToggle: () -> Unit,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onSeek: (Float) -> Unit,
    onSelect: (Int) -> Unit,
    jelly: Boolean = false
) {
    // 全局快照刷新：普通 var 写入不触发任何重组，供歌词轮询/播放按钮读取。
    globalPositionMs = state.positionMs
    globalDurationMs = state.durationMs
    LaunchedEffect(state.isPlaying) { posterIsPlaying.value = state.isPlaying }

    // 回调稳定化必须在壳里做、墙体外面收：lambda 参数若每次传新实例，
    // 墙体 skip 判定永远失败，上面的拆分就白做了。
    val selRef = androidx.compose.runtime.rememberUpdatedState(onSelect)
    val togRef = androidx.compose.runtime.rememberUpdatedState(onToggle)
    val prevRef = androidx.compose.runtime.rememberUpdatedState(onPrev)
    val nextRef = androidx.compose.runtime.rememberUpdatedState(onNext)
    val seekRef = androidx.compose.runtime.rememberUpdatedState(onSeek)
    val stableSelect = remember { { i: Int -> selRef.value(i) } }
    val stableToggle = remember { { togRef.value() } }
    val stablePrev = remember { { prevRef.value() } }
    val stableNext = remember { { nextRef.value() } }
    val stableSeek = remember { { f: Float -> seekRef.value(f) } }

    PosterWallBody(
        queue = state.queue,
        currentIndex = state.currentIndex,
        likedIds = state.likedSongIds,
        colors = colors,
        jelly = jelly,
        onToggle = stableToggle,
        onPrev = stablePrev,
        onNext = stableNext,
        onSeek = stableSeek,
        onSelect = stableSelect
    )
}

@Composable
private fun PosterWallBody(
    queue: List<com.iris.music.data.Song>,
    currentIndex: Int,
    likedIds: Set<Long>,
    colors: IrisColors,
    jelly: Boolean,
    onToggle: () -> Unit,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onSeek: (Float) -> Unit,
    onSelect: (Int) -> Unit
) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val density = LocalDensity.current
// 无级缩放：连续缩放基准 cellPx（px），范围 74~140dp，默认 118dp。
        // 捏合实时按比例调整，整墙所有卡片位置/尺寸正比于 step，step 跟手即整墙跟手。
        val cellPx = remember { androidx.compose.runtime.mutableFloatStateOf(with(density) { POSTER_CELL_BASE.toPx() }) }
        val gap = with(density) { 5.dp.toPx() }
        // 卡片几何恒定：cellPx/step 全程不变，双指缩放只操作相机（视野位图），
        // 不改变任何卡片尺寸 → 零重排、零落盘、零"位图→原生"切换。
        val step = cellPx.floatValue + gap
        // 卡内内容等比系数：卡片尺寸不随缩放变化，内容系数恒为 1（默认档）。
        val contentScale = 1f
        val viewW = constraints.maxWidth.toFloat()
        val viewH = constraints.maxHeight.toFloat()

        // 基础布局：与 active 完全无关——换歌零重排、零跳位（布局只由队列+种子决定）。
        // 列数随队列规模自适应，让画布尽量接近方形：横向循环周期拉长，
        // 不再"左右滑几下就绕回原点"。
        // （currentIndex/likedIds 现在是 PosterWallBody 的稳定参数，不再读 state）
        // 布局随队列与"点赞集合"重建：点赞变化会让该歌重排到更大卡（有整墙淡入过渡掩盖）。
        // 播放次数用"进墙快照"，避免切歌写库导致整墙重排跳动。
        val (allTiles, rows, cols) = remember(queue, likedIds) {
            val n = queue.size
            val colsIn = ceil(sqrt(n.toDouble())).toInt().coerceIn(8, 16)
            val seed = queue.fold(1125899906842597L) { acc, s -> acc * 31 + s.id }
            // 偏好分：点赞 +2，播放次数按 log 增长，收藏集合非空则归一化。
            val pc = com.iris.music.data.PlayHistory.getPlayCounts()
            val maxLiked = if (likedIds.isNotEmpty()) 2f else 0f
            val maxPlay = (queue.maxOfOrNull { kotlin.math.ln(((pc[it.id] ?: 0) + 1).toDouble()).toFloat() } ?: 0f)
            fun prefOf(i: Int): Float {
                val song = queue.getOrNull(i) ?: return 0f
                val likeScore = if (song.id in likedIds) 2f else 0f
                val playScore = kotlin.math.ln(((pc[song.id] ?: 0) + 1).toDouble()).toFloat()
                val norm = (if (maxLiked > 0f) likeScore / maxLiked else 0f) * 0.6f +
                        (if (maxPlay > 0f) playScore / maxPlay else 0f) * 0.4f
                return norm
            }
            val (tiles, r, realCols) = packWall(n, colsIn, seed, ::prefOf)
            Triple(tiles, r, realCols)
        }
        val canvasW = cols * step
        val canvasH = if (rows > 0) rows * step else 0f

        // 让位求解：基础布局不变，只根据当前播放歌重算一份"让位后的几何"。
        // 换歌时只有 active 附近的少数卡片位置/尺寸变化，其余卡片坐标完全不动。
        // 用户本次点选的具体磁贴（同歌多副本时"点哪张展开哪张"）。
        // 渲染中点击非当前播放磁贴时写入；solveWall 据此把它定为 active，
        // 避免点小的却展开远处大的、被点卡被隐藏留白。
        val selectedTile = remember { mutableStateOf<WallTile?>(null) }
        val solvedMap = remember(allTiles, cols, rows, currentIndex, selectedTile.value) {
            val solved = solveWall(allTiles, cols, rows, currentIndex, selectedTile.value)
            val m = HashMap<WallTile, SolvedTile>(solved.size * 2)
            for (s in solved) m[s.tile] = s
            m
        }

        val offsetX = remember { androidx.compose.runtime.mutableFloatStateOf(0f) }
        val offsetY = remember { androidx.compose.runtime.mutableFloatStateOf(0f) }
        // 相机（视野）状态：双指缩放只改这三个 snapshot float，作用于 graphicsLayer 位图缩放，
        // 不改变任何卡片几何（cellPx/step/内容尺寸全恒定）→ 零重排、零落盘、零切换。
        // 缩放中心锚定在手势处理内实时完成（见 pointerInput 缩放分支）。
        val camScaleF = remember { androidx.compose.runtime.mutableFloatStateOf(1f) }
        val camPivotX = remember { androidx.compose.runtime.mutableFloatStateOf(0f) }
        val camPivotY = remember { androidx.compose.runtime.mutableFloatStateOf(0f) }

        // 初始视野对齐：只在首次进墙（队列建立）时执行一次，对准播放磁贴。
        // 之后换歌（currentIndex 变、queue 不变）这个 effect 完全不会重启——
        // 从根上保证换歌绝不碰视角，用户拖到哪就在哪。
        var alignedInitial = remember { false }
        LaunchedEffect(queue) {
            if (alignedInitial || allTiles.isEmpty()) return@LaunchedEffect
            alignedInitial = true
            val t = allTiles.firstOrNull { it.index == currentIndex } ?: return@LaunchedEffect
            val cx = (t.col + t.spanW / 2f) * step
            val cy = (t.row + t.spanH / 2f) * step
            if (canvasW > viewW) offsetX.floatValue = (viewW / 2f - cx).coerceIn(viewW - canvasW, 0f)
            else offsetX.floatValue = (viewW - canvasW) / 2f
            if (canvasH > viewH) offsetY.floatValue = (viewH / 2f - cy).coerceIn(viewH - canvasH, 0f)
            else offsetY.floatValue = (viewH - canvasH) / 2f
        }

        // 可见集合的驱动量：把相机位移量化到整格。
        //
        // 不量化的代价：derivedStateOf 直接读 offsetX，手指每动一帧它就把
        // allTiles × 副本数全遍历一遍（几百首歌 × 4~9 个副本 = 每帧上千次
        // HashMap 查找 + 浮点比较），还要新建整条列表和里面的条目对象——
        // 纯主线程开销 + GC 压力，拖动时的隐性掉帧就在这里。
        // 而可见集合最多每跨一格才需要变一次：裁剪窗口本身有 4 格外扩余量，
        // 1 格的量化误差绝不会漏卡，最坏只是边缘多渲染一格的卡片。
        val qox = remember { mutableFloatStateOf(0f) }
        val qoy = remember { mutableFloatStateOf(0f) }
        LaunchedEffect(step) {
            snapshotFlow {
                Offset(
                    kotlin.math.floor(offsetX.floatValue / step) * step,
                    kotlin.math.floor(offsetY.floatValue / step) * step
                )
            }.distinctUntilChanged().collect {
                qox.floatValue = it.x
                qoy.floatValue = it.y
            }
        }

        // 可见集合：只在跨界时变化，拖动帧零重组。
        //
        // 可见集合的驱动量之二：相机缩放（声明必须在使用它的 derivedStateOf 之前）。
        // derivedStateOf 直接读 camScaleF 的话，捏合期间每帧都全量遍历
        // allTiles × 副本重算可见集（几百上千次比较 + 整表分配），
        // 正是捏合时掉帧的来源。量化到 5% 一档：窗口边界每档最多挪 6%，
        // 远小于 4 格外扩余量，不会漏卡；可见集计算频率从每帧降到跨档瞬间。
        val qcam = remember { mutableFloatStateOf(1f) }
        val qpivX = remember { mutableFloatStateOf(0f) }
        val qpivY = remember { mutableFloatStateOf(0f) }
        LaunchedEffect(step) {
            snapshotFlow { Triple(camScaleF.floatValue, camPivotX.floatValue, camPivotY.floatValue) }
                .distinctUntilChanged()
                .collect {
                    // floor 而非 ceil：窗口必须"宁大勿小"。缩小视野时 s<1、
                    // 窗口 = view/s，若把 s 往大取（ceil），窗口比真实视野窄，
                    // 边缘卡片被裁掉——正是"边边没渲染"的根因之一。
                    qcam.floatValue = (floor(it.first / 0.05f) * 0.05f).coerceAtLeast(0.05f)
                    qpivX.floatValue = floor(it.second / step) * step
                    qpivY.floatValue = floor(it.third / step) * step
                }
        }

        // 关键：可见性必须按"求解后的实际几何"判断——让位会把卡片移到别的格子
        // （环形拓扑下甚至可能绕到画布另一侧），按基础坐标裁剪会导致卡片凭空消失。
        val visible by remember(allTiles, solvedMap, canvasW, canvasH, viewW, viewH) {
            androidx.compose.runtime.derivedStateOf {
                val ox = qox.floatValue
                val oy = qoy.floatValue
                // 相机（视野）缩放：graphicsLayer 以 camPivot 为中心缩放 s。屏幕点 p 对应
                // 渲染坐标 r = pivot + (p - pivot)/s。故可见渲染区间为：
                //   lo = pivot*(1-1/s)，hi = pivot + (view-pivot)/s
                // s=1 时退化为 [0,view]；s<1（缩小视野）时区间自动外扩，铺满屏幕不露白。
                // 读 camScale/camPivot 会每帧重算此窗口，但卡片几何恒定、VisibleTile.equals
                // 保证仅跨边界那帧才真正重组——无额外开销、无闪。
                // 读**量化后**的缩放/轴心（qcam/qpiv*）：捏合每帧不再触发本表全量重算。
                // 量化误差（≤5% 缩放档 / 1 格轴心）由下方 4 格外扩余量完全吞掉。
                val s = qcam.floatValue
                val invS = 1f / s.coerceAtLeast(0.01f)
                val px = qpivX.floatValue
                val py = qpivY.floatValue
                val loX = px * (1f - invS)
                val hiX = px + (viewW - px) * invS
                val loY = py * (1f - invS)
                val hiY = py + (viewH - py) * invS
                val out = ArrayList<VisibleTile>(48)
                if (allTiles.isNotEmpty() && canvasW > 0f && canvasH > 0f) {
// 外扩余量（世界格数）：量化误差 = 位移 qox floor 滞后 1 格 + 轴心 qpiv 1 格 ≈ 2 格，
                     // 取 3 格足矣。卡片自身跨度（active 展开最多 4 格）由每张卡 vis1/vis2
                     // 各自按实际矩形对视口判定，无需 margin 覆盖。
                     // 前几轮把它放大到 7~9*s 是单位误用：裁剪是二维的，每边 +7 格会让
                     // 可见候选卡片二维爆炸（窗口 3×7 → 约 17×21），每帧 measure/layout
                     // 绿色段爆表 = "绿色爆炸式"。且余量在世界坐标量化，与缩放无关，不需 *s。
                     val margin = step * 3f
                     // 副本数/起点覆盖放宽后的视口（margin=3 格，远小于画布，副本量可控）。
                     val effW = (hiX - loX) + 2f * margin
                     val effH = (hiY - loY) + 2f * margin
                     val replicasX = ceil(effW / canvasW).toInt() + 1
                     val replicasY = ceil(effH / canvasH).toInt() + 1
                     val baseX = floor((-ox + loX - margin) / canvasW).toInt()
                     val baseY = floor((-oy + loY - margin) / canvasH).toInt()
                     for (rx in baseX..baseX + replicasX) {
                        for (ry in baseY..baseY + replicasY) {
                            val cx0 = rx * canvasW + ox
                            val cy0 = ry * canvasH + oy
                            if (cx0 + canvasW <= loX - margin || cx0 >= hiX + margin ||
                                cy0 + canvasH <= loY - margin || cy0 >= hiY + margin
                            ) continue
                            for (t in allTiles) {
                                val g = solvedMap[t]
                                val gw = g?.spanW ?: t.spanW
                                val gh = g?.spanH ?: t.spanH
                                // 跳过被丢弃的磁贴（span=0）：它们虽然不占像素，但会覆盖在真正的 active 卡片上吞掉点击
                                if (gw <= 0 || gh <= 0) continue
                                val gc = g?.col ?: t.col
                                val gr = g?.row ?: t.row
                                // 求解位置与基础位置都参与判断：动画从旧位滑向新位，
                                // 两端任一在视野内就渲染，全程不闪。
                                val l1 = cx0 + gc * step
                                val t1 = cy0 + gr * step
                                val r1 = l1 + gw * step - gap
                                val b1 = t1 + gh * step - gap
                                val vis1 = r1 > loX - margin && l1 < hiX + margin &&
                                        b1 > loY - margin && t1 < hiY + margin
                                val l2 = cx0 + t.col * step
                                val t2 = cy0 + t.row * step
                                val r2 = l2 + t.spanW * step - gap
                                val b2 = t2 + t.spanH * step - gap
                                val vis2 = r2 > loX - margin && l2 < hiX + margin &&
                                        b2 > loY - margin && t2 < hiY + margin
                                if (vis1 || vis2) out.add(VisibleTile(t, rx, ry))
                            }
                        }
                    }
                }
                out
            }
        }

        // 当前屏封面即时加载。
        // 注意：不能再用 coroutineScope+awaitAll——那会让每次跨格重启本 effect 时
        // 取消所有在途解析，慢文件（MediaMetadataRetriever 打开一次要几百 ms）
        // 永远在"开始→被杀→重来"循环里，就是"卡片迟迟不出图"的根因。
        // 改为 fire-and-forget 到独立 scope：老任务跑完自然结束，
        // 重复请求由 ArtworkLoader 的 inFlight 合并，不会堆积。
        //
        // 关键：按"距屏幕中心距离"排序后并发加载——放大快速扫动时，新露出的
        // 边缘卡片若排在 prefetch 队列末尾，其封面要等前面几十张解析完才轮到自己，
        // 视觉就是"拉到位置才出图"。靠近中心的先加载、边缘后加载，扫动方向上的
        // 卡优先被解析。
        val prefetchScope = rememberCoroutineScope()
        LaunchedEffect(visible) {
            val centerX = (qox.floatValue + viewW * 0.5f)
            val centerY = (qoy.floatValue + viewH * 0.5f)
            // 只解析"尚未进内存缓存"的封面：已命中的 peek!=null，跳过——
            // 避免放大时同屏磁贴暴涨后，每次跨界对几百张全量 launch load
            // （即便大多已在 LRU，仍触发数百个协程 + 调度）造成主线程尖峰卡顿。
            val ordered = visible
                .mapNotNull { vt ->
                    val p = queue.getOrNull(vt.tile.index)?.filePath ?: return@mapNotNull null
                    if (ArtworkLoader.peek(p) != null) return@mapNotNull null
                    val g = solvedMap[vt.tile] ?: return@mapNotNull null
                    val cx = vt.rx * canvasW + (g.col + g.spanW / 2f) * step
                    val cy = vt.ry * canvasH + (g.row + g.spanH / 2f) * step
                    val dx = cx - centerX; val dy = cy - centerY
                    (dx * dx + dy * dy) to p
                }
                .sortedBy { it.first }
                .map { it.second }
                .distinct()
            val io = Dispatchers.IO.limitedParallelism(12)
            ordered.forEach { p -> prefetchScope.launch(io) { ArtworkLoader.load(p) } }
        }

        // 封面预热：进入海报墙即后台并发预载整个队列封面。
        // key 必须是 Unit：旧版 key=queue 会在每次换歌（viewModel 重建 queue 列表
        // 实例时）取消整个预热再来一遍，大库永远预热不完，边缘冷解析永远追不上
        // 滑动——"边边没渲染"的调度根因。
        //
        // 顺序关键：按"距初始播放磁贴的网格距离"从小到大解析，而非 queue 顺序。
        // 放大(s>1)时画面扫过世界的速度是手指的 s 倍，世界内容快速掠过；若预热
        // 按 queue 顺序，用户拖动方向上的歌排在队列末尾、最后才解析，就会出现
        // "拉到位置才出图"。空间就近优先保证朝任意方向拖，邻近区域都已就绪。
        // 并发 6→16：封面解析是 IO 密集（setDataSource+embeddedPicture 这类 seek 延迟），
        // 高并发能近线性隐藏延迟、大幅缩短全库预热耗时。
        LaunchedEffect(Unit) {
            val anchorTile = allTiles.firstOrNull { it.index == currentIndex }
            val ax = anchorTile?.let { it.col + it.spanW / 2f } ?: (cols / 2f)
            val ay = anchorTile?.let { it.row + it.spanH / 2f } ?: (rows / 2f)
            val byIdx = allTiles.associateBy { it.index }
            val ordered = queue.mapIndexedNotNull { idx, song ->
                val t = byIdx[idx] ?: return@mapIndexedNotNull null
                val p = song.filePath ?: return@mapIndexedNotNull null
                val dx = (t.col + t.spanW / 2f) - ax
                val dy = (t.row + t.spanH / 2f) - ay
                Triple(dx * dx + dy * dy, idx, p)
            }.sortedBy { it.first }.map { it.third }.distinct()
            val io = Dispatchers.IO.limitedParallelism(16)
            coroutineScope { ordered.map { async(io) { ArtworkLoader.load(it) } }.awaitAll() }
        }

        val scope = rememberCoroutineScope()

        // 回调 ref 快照：磁贴的 Modifier 被 remember 成稳定身份（见 forEach 内注释），
        // 点击发生的那一刻才从 ref 读最新回调，回调变化不参与磁贴重组判定。
        val selRef = androidx.compose.runtime.rememberUpdatedState(onSelect)
        val togTileRef = androidx.compose.runtime.rememberUpdatedState(onToggle)
        val prevRef = androidx.compose.runtime.rememberUpdatedState(onPrev)
        val nextRef = androidx.compose.runtime.rememberUpdatedState(onNext)
        val seekRef = androidx.compose.runtime.rememberUpdatedState(onSeek)
        // 恒等包装：PosterTile 收到的回调实例永远不变，进度轮询重组时才能被 skip。
        val stableToggle = remember { { togTileRef.value() } }
        val stablePrev = remember { { prevRef.value() } }
        val stableNext = remember { { nextRef.value() } }
        val stableSeek = remember { { f: Float -> seekRef.value(f) } }

        // 整墙过渡：基础布局重排（刷新/队列变化）时磁贴全部销毁重建，
        // 磁贴级动画无效——所以在墙层做整体淡入 + 轻微缩放，掩盖重建的硬切。
        // 首次进入不动画，只有 allTiles 引用变化才触发。
        val wallAnim = remember { Animatable(1f) }
        var wallInitialized by remember { mutableStateOf(false) }
        LaunchedEffect(allTiles) {
            if (!wallInitialized) { wallInitialized = true; return@LaunchedEffect }
            wallAnim.snapTo(0f)
            wallAnim.animateTo(
                targetValue = 1f,
                animationSpec = androidx.compose.animation.core.keyframes {
                    durationMillis = 360
                    0.0f at 0 using FastOutSlowInEasing
                    0.85f at 120 using FastOutSlowInEasing
                    1.0f at 360
                }
            )
        }

        Box(
            Modifier
                .fillMaxSize()
                .clipToBounds()
.graphicsLayer {
                    alpha = wallAnim.value
                    scaleX = 0.96f + 0.04f * wallAnim.value
                    scaleY = 0.96f + 0.04f * wallAnim.value
                }
                .pointerInput(queue) {
                    // 惯性用可取消 Job + 逐帧手动指数衰减，绕开 Animatable 的
                    // stop()/animateDecay 并发竞态。
                    var flingJob: kotlinx.coroutines.Job? = null
                    fun startFling(vx: Float, vy: Float) {
                        flingJob?.cancel()
                        // 速度过小的轻微滑动不给惯性
                        if (abs(vx) < 80f && abs(vy) < 80f) return
                        flingJob = scope.launch {
                            var vX = vx; var vY = vy
                            var lastFrame = 0L
                            while (true) {
                                // vsync 对齐：用真实帧间隔积分，杜绝 delay(16) 的调度抖动卡顿
                                val now = withFrameNanos { it }
                                if (lastFrame != 0L) {
                                    val dt = ((now - lastFrame) / 1_000_000_000f).coerceIn(0f, 0.05f)
                                    // offsetX 是世界坐标位移（渲染 translation = offsetX*s），
                                    // 惯性的 vX 是屏幕像素速度，必须除以 s 换算成世界速度，
                                    // 否则放大后惯性滑行速度是手指的 s 倍——画面狂飘、封面追不上。
                                    val cs = camScaleF.floatValue
                                    offsetX.floatValue += (vX * dt) / cs
                                    offsetY.floatValue += (vY * dt) / cs
                                    // 阻尼衰减常数 3.4/s：比旧值 2.3 更快收敛，滑行距离缩短约 1/3，
                                    // 甩动仍有短促余韵但不拖沓。
                                    val damp = kotlin.math.exp(-(dt) * 3.4).toFloat()
                                    vX *= damp; vY *= damp
                                    if (abs(vX) < 12f && abs(vY) < 12f) break
                                }
                                lastFrame = now
                            }
                        }
                    }
                    // 仿 detectTransformGestures 官方实现：未超过 touchSlop 前不消费事件，
                    // 因此纯 tap 事件完整透传给子级 clickable，点击不会被吞掉。
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false)
                        var pastTouchSlop = false
                        var gestureZooming = false
                        var pan = Offset.Zero
                        var zoom = 1f
                        // 自算释放速度：VelocityTracker 配 centroid 在纯 pan 下常返回≈0。
                        // 用"回看窗口位移/时间"估计——取最近 ~100ms 的净位移/耗时，
                        // 既反映真实释放速度，又不会被松手前最后一点停顿拉低。
                        val velWin = ArrayList<FloatArray>(8)
                        var posX = 0f; var posY = 0f
                        val touchSlop = viewConfiguration.touchSlop
                        while (true) {
                            val event = awaitPointerEvent()
                            val zoomChange = event.calculateZoom()
                            val panChange = event.calculatePan()
                            if (!pastTouchSlop) {
                                zoom *= zoomChange
                                pan += panChange
                                val centroidSize = event.calculateCentroidSize(useCurrent = false)
                                val zoomMotion = abs(1 - zoom) * centroidSize
                                val panMotion = pan.getDistance()
                                if (zoomMotion > touchSlop || panMotion > touchSlop) {
                                    pastTouchSlop = true
                                    gestureZooming = zoomMotion > touchSlop
                                }
                            }
                            if (pastTouchSlop) {
                                flingJob?.cancel()   // 新拖动立即终止上一段惯性
                                if (gestureZooming || event.changes.size > 1) {
                                    // 视野缩放：双指只缩放相机（graphicsLayer 位图），卡片尺寸恒定。
                                    // 相机缩放围绕双指中心 cen 锚定：camScale *= zoomChange，pivot 存当前中心。
                                    // 手势全程零重排、零落盘——松手也不需要把位图"写回"真实布局。
                                    val cen = event.calculateCentroid(useCurrent = true)
                                    val sNew = (camScaleF.floatValue * zoomChange).coerceIn(POSTER_CAM_MIN, POSTER_CAM_MAX)
                                    if (cen.x.isFinite() && cen.y.isFinite() && sNew.isFinite()) {
                                        camScaleF.floatValue = sNew
                                        camPivotX.floatValue = cen.x
                                        camPivotY.floatValue = cen.y
                                    }
                                } else {
                                    // 拖动：panChange 是屏幕像素，offsetX 是世界坐标位移
                                    // （渲染 translation = offsetX*s）。放大(s>1)时直接累加屏幕像素
                                    // 会让画面以 s 倍速度掠过——跟手 1:1 必须除以 s。这正是
                                    // "放大后才拉到哪哪才加载 + 一卡一卡"的根因。
                                    val cs = camScaleF.floatValue
                                    offsetX.floatValue += panChange.x / cs
                                    offsetY.floatValue += panChange.y / cs
                                }
                                event.changes.forEach {
                                    it.consume()
                                }
                                // 记录累计坐标+时间戳，维护 ~100ms 回看窗口，
                                // 释放时取 (最新点 - 窗口最老点) / 时间差 = 真实释放速度。
                                val now = event.changes.firstOrNull()?.uptimeMillis ?: System.nanoTime() / 1000000L
                                posX += panChange.x; posY += panChange.y
                                velWin.add(floatArrayOf(posX, posY, now.toFloat()))
                                while (velWin.size > 2 && now - velWin[0][2] > 100f) velWin.removeAt(0)
                            }
                            if (!event.changes.any { it.pressed }) break
                        }
                        // 视野缩放松手：不需要落盘（卡片几何从未变过），相机保持当前值即可。
                        // 是否需要回弹到 1.0 由产品决定；这里选择保持（跟手、无额外动画跳变）。
                        // 纯拖动松手才给惯性；捏合/缩放不触发惯性。
                        if (pastTouchSlop && !gestureZooming) {
                            // 从回看窗口算真实释放速度：窗口净位移 / 净耗时
                            if (velWin.size >= 2) {
                                val a = velWin[0]
                                val b = velWin[velWin.size - 1]
                                val dtS = ((b[2] - a[2]) / 1000f).coerceAtLeast(0.008f)
                                val maxV = 9000f
                                val vX = ((b[0] - a[0]) / dtS).coerceIn(-maxV, maxV)
                                val vY = ((b[1] - a[1]) / dtS).coerceIn(-maxV, maxV)
                                startFling(vX, vY)
                            }
                        }
                    }
                }
         ) {
// 相机层：缩放与平移都挂在这一个 graphicsLayer 上（纯绘制阶段）。
            //
            // 拖动位移**必须**放在这里，不能放在磁贴自己的 Modifier.offset{} 里：
            // offset 是布局阶段的 Modifier，手指每动一帧，可见的几十张磁贴全部
            // 重新 measure + place（每张内部还有封面 + 遮罩 + 文字的子树）；
            // 而 translationX/Y 只改 RenderNode 的矩阵，一帧只动一个对象。
            // 顺带解决"一卡一卡"的另一半：offset 里 roundToInt() 会把位置锁到整像素，
            // 慢速拖动时每帧不足 1px 的位移被抹平，视觉上就是停一帧再跳一格。
            // 层上的 float 位移是亚像素的，拖动自然就连贯了。
            //
            // 位移乘 s 是为了和旧语义严格等价：位移原本在缩放**内侧**，
            // 屏幕位移 = offsetX * s；合并到同一层后 translation 在缩放外侧，需自行乘 s。
            Box(
                Modifier.graphicsLayer {
                    val s = camScaleF.floatValue
                    scaleX = s
                    scaleY = s
                    translationX = offsetX.floatValue * s
                    translationY = offsetY.floatValue * s
                    transformOrigin = androidx.compose.ui.graphics.TransformOrigin(
                        camPivotX.floatValue / viewW.coerceAtLeast(1f),
                        camPivotY.floatValue / viewH.coerceAtLeast(1f)
                    )
                }
            ) {
            // 只把稳定值传给磁贴：song、active、几个回调、几何参数。
            // state 整体不进磁贴——进度轮询每秒多次，传进去整面墙跟着重组。
            //
            // 位置全部来自 solveWall 的求解结果（画布级、零重叠）：
            // 播放卡原地扩成 2×2，被挤的卡真正移动到别处，更外层递归缩放让位。
            // 渲染这里不再做任何临时几何修正，也没有叠层兜底。
            visible.forEach { vt ->
                val t = vt.tile
                val song = queue[t.index]
                val g = solvedMap[t] ?: return@forEach
                // 关键：播放卡判定必须用求解器的 active 标记，而非 index——
                // 同一首歌有多个副本（循环填充），只有被放大的那个副本是播放卡。
                val isActive = g.active
                // 同首歌的其它副本照常渲染（不再整张隐藏）。早期为规避"点小卡没反应"
                // 曾隐藏非 active 副本，但那会让被隐藏副本原占格无人回填、漏成空白格
                // （截图里 fairy stage 附近那格空白即此）。现在 active 已按 preferred
                // 精确命中、solveWall 又保护所有副本不被缩小/推走，副本保留既无"双播放卡"
                // 也不留白，与任意普通歌的多副本表现一致。
                // 传给磁贴的是画布网格坐标，不含相机位移：整面墙的平移由相机层的 graphicsLayer 承担。
                val left = vt.rx * canvasW + g.col * step
                val top = vt.ry * canvasH + g.row * step
                val w = g.spanW * step - gap
                val h = g.spanH * step - gap

                key(t.col, t.row, vt.rx, vt.ry) {
                    // Modifier 按磁贴身份 remember：每次墙层重组都新建 Modifier 实例的话，
                    // PosterTile 参数永远"不相等"，skip 失效——几十张卡在每次
                    // 进度轮询/跨界重组时全部跟着重组。点击发生时才经 ref 读最新回调。
                    val tileInteraction = remember { MutableInteractionSource() }
                    val tileModifier = remember(isActive, tileInteraction) {
                        Modifier
                            .zIndex(if (isActive) 1f else 0f)
                            .clickable(
                                interactionSource = tileInteraction,
                                indication = null
                            ) {
                                Haptics.tap()
                                if (!isActive) {
                                    selectedTile.value = t
                                    selRef.value(t.index)
                                } else togTileRef.value()
                            }
                    }
                    PosterTile(
                        song = song,
                        active = isActive,
                        colors = colors,
                        jelly = jelly,
                        onToggle = stableToggle,
                        onPrev = stablePrev,
                        onNext = stableNext,
                        onSeek = stableSeek,
                        baseLeft = left,
                        baseTop = top,
                        renderW = w,
                        renderH = h,
                        contentScale = contentScale,
                        zooming = false,
                        modifier = tileModifier
                    )
                }
            }

            } // end Box
            Text(
                "拖动探索 · ${queue.size} 首",
                color = colors.subText,
                fontSize = 11.sp,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(12.dp)
            )
        }
    }
}

/**
 * 单块磁贴。位置在 layout 阶段计算；播放中的磁贴视觉放大为 2×2
 * （居中扩展，画布占位不变）。
 */
@Composable
private fun PosterTile(
    song: Song,
    active: Boolean,
    colors: IrisColors,
    jelly: Boolean,
    onToggle: () -> Unit,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onSeek: (Float) -> Unit,
    baseLeft: Float,
    baseTop: Float,
    renderW: Float,
    renderH: Float,
    /**
     * 内容缩放系数 = 当前 cellPx / 基准 cellPx（118dp）。
     *
     * 磁贴外框由 renderW/renderH 直接驱动，但框里的歌词字号、按钮尺寸、进度条粗细
     * 原本全是死 dp/sp，捏合放大时卡片变大、内容却纹丝不动，相对屏幕看就是"内容变小了"。
     * 把这个系数乘到所有内容尺寸上，整张卡才是真正等比缩放。
     *
     * 注意不能用 graphicsLayer.scale 整体缩放内容：那样文字会被位图拉伸糊掉，
     * 且 clip 边界、触摸热区都会错位。这里走"尺寸参数等比放大"的路子，文字始终清晰。
     */
    contentScale: Float = 1f,
    zooming: Boolean = false,
    modifier: Modifier = Modifier
) {
    val density = LocalDensity.current
    // 切换编排动画：换歌/点卡重排时，不是四段独立直线平移，而是
    //   1) FastOutSlowIn 缓动——移动有加速减速的物理手感
    //   2) 次序（stagger）——active 卡先动，普通卡延迟 42ms 让位，形成涟漪推挤
    //   3) 缩放配合——尺寸变化时从 0.94 弹性长到 1.0，长大/缩小不再硬切
    //   4) 透明度——重排的卡轻微淡入，消除闪现感
    // jelly 模式：全部换成弹簧物理——位置 spring 回弹（果冻抖动感），
    // 尺寸变化带明显 squash & stretch 拉伸，落位时像果冻一样 wobble。
    val tileEasing = FastOutSlowInEasing
    // 间距统一：主卡长大与邻居让位必须同一时刻开始、同一时长结束，
    // 否则主卡先撑大、邻居还没挪开的瞬间右侧会露出渐现的缝（曾靠出血盖，现出血已关）。
    // 去掉 42ms 错峰，全部 delay 0 → 整片同步重排，瞬时缝消除。
    val moveDelay = if (active) 0 else 0
    val moveSpec: androidx.compose.animation.core.AnimationSpec<Float> = if (zooming) {
        androidx.compose.animation.core.snap()
    } else if (jelly) {
        androidx.compose.animation.core.spring(
            dampingRatio = 0.75f,
            stiffness = 320f
        )
    } else {
        androidx.compose.animation.core.tween(
            durationMillis = 300,
            delayMillis = moveDelay,
            easing = tileEasing
        )
    }
    val animLeft by androidx.compose.animation.core.animateFloatAsState(
        targetValue = baseLeft,
        animationSpec = moveSpec,
        label = "tileLeft"
    )
    val animTop by androidx.compose.animation.core.animateFloatAsState(
        targetValue = baseTop,
        animationSpec = moveSpec,
        label = "tileTop"
    )
    val sizeSpec: androidx.compose.animation.core.AnimationSpec<Float> = if (zooming) {
        androidx.compose.animation.core.snap()
    } else if (jelly) {
        androidx.compose.animation.core.spring(
            dampingRatio = 0.72f,
            stiffness = 280f
        )
    } else {
        androidx.compose.animation.core.tween(
            durationMillis = 300,
            easing = tileEasing
        )
    }
    val animW by androidx.compose.animation.core.animateFloatAsState(
        targetValue = renderW,
        animationSpec = sizeSpec,
        label = "tileW"
    )
    val animH by androidx.compose.animation.core.animateFloatAsState(
        targetValue = renderH,
        animationSpec = sizeSpec,
        label = "tileH"
    )
    // 生长/弹性缩放（scaleAnim）与整卡淡入（alphaAnim）此前都在这里各挂一个 Animatable，
    // 但下方 graphicsLayer 出于"任何情况下间距都统一"强制 scale = 1、alpha 也从未被驱动——
    // 两个动画只写不读，等于每张卡每次换歌/重排都白跑一个 280~300ms 的动画协程
    // （可见几十张同时跑），已移除。尺寸变化由 animW/animH 的 animateFloatAsState 表达。
    // 微出血抹缝：展开焦点卡放大到 1.04，让封面边缘溢出一点点，盖过 3×3 奇数
    // 宽与 2×2 方块周期错位留下的 sub-cell 窄缝（碎洞填不满）。1.04 克制到
    // 几乎看不出放大，只为填缝，不做浮起遮挡。
    // 果冻拉伸（squash & stretch）此前在这里逐帧记录 baseLeft - animLeft 速度，
    // 但因为最终 graphicsLayer 强制 scale = 1（保证间距统一），那两位从未被读取——
    // 组合期写 snapshot state 反而可能引发自我失效，已移除。
    // active 翻转不再做整卡 alpha 淡入——之前 snapTo(0.5f) 会让卡片在点卡播放瞬间
    // 明显暗一下再弹回（"闪一下"）。尺寸/描边/阴影动画已足够表达层级变化。
    val tileShape = IrisShape.item
        // 海报墙磁贴不做液态玻璃：一屏几十张卡 × 实时 RenderEffect 会掉帧，
        // 且封面盖住 90% 面积玻璃根本不可见。实色底 + 主色描边近似表达层级。
        val tileBg = if (active) colors.card.copy(alpha = 0.85f) else colors.card
        val tileBorder = if (active) colors.primary else Color.Transparent
        Box(
            Modifier
                // 只放画布网格坐标：整面墙的拖动位移在相机层的 graphicsLayer 上，
                // 这里的 offset 仅在换歌让位/点赞重排等布局变化时才变（不是每帧）。
                .offset {
                    IntOffset(animLeft.roundToInt(), animTop.roundToInt())
                }
                .size(
                    with(density) { animW.toDp() },
                    with(density) { animH.toDp() }
                )
                .then(modifier)
                .graphicsLayer {
                    // active 磁贴浮起：阴影 + 主色描边
                    shadowElevation = if (active) 8.dp.toPx() else 0f
                    shape = tileShape
                    // clip 必须为 true：封面图未必盖满整个 Box，去掉会让卡片四角变方
                    // （v3.2.12 试过关掉，圆角破露方角，已回退）。
                    clip = true
                    scaleX = 1f
                    scaleY = 1f
                    // 间距统一原则：果冻 squash & stretch 也会让卡片临时偏离网格框，
                    // 进而使卡间间隙忽大忽小。为"任何情况下间距都统一"，这里不再做任何
                    // scale 形变（jelly 的拉伸观感让位于间距一致性）。
                }
                .background(tileBg, tileShape)
                .border(
                    width = if (active) 1.5.dp else 0.dp,
                    color = tileBorder,
                    shape = tileShape
                )
        ) {
        SongArtwork(song.filePath, song.id, Modifier.fillMaxSize(), isDark = colors.isDark, noteIcon = true)
        Box(
            Modifier
                .fillMaxSize()
                // 性能关键（HWUI 实测红段=GPU overdraw）：普通卡这层全屏半透明黑
                // 遮罩底下没有任何可见内容（歌词/进度条都是 if(active) 才有），
                // 却每张都做一次全屏 alpha 混合——一屏几十张即是几十次 overdraw，
                // 静止也在付。alpha 置 0 让 Skia 直接跳过不画这片像素。
                // 注意不能改成"普通卡不渲染此 Box"：那会让 Box 的直角背景/裁切变化，
                // 之前 clip=active 那次方角回归正是直角黑底盖住圆角封面所致。
                // alpha=0 既消掉 overdraw，又保持 Box 仍在、不触发方角。
                .background(Color.Black.copy(alpha = if (active) 0.38f else 0f))
                .padding((8 * contentScale).dp)
        ) {
            // 封面底部小进度条：4dp、宽度 60% 居中。
            // active 卡支持按住拖动 seek（手势区间加高便于命中），读全局快照轮询。
            // 封面底部小进度条：只属于正在播放的卡（active）。
            // 普通卡不显示任何进度条——没在播就不该有进度。
            // 跟手优化：拖动期间进度条显示本地即时值（不等播放器回写），
            // 松手才提交 seek。
            if (active) {
            val dragPos = remember { androidx.compose.runtime.mutableFloatStateOf(-1f) }
            val progress by androidx.compose.runtime.produceState(
                initialValue = 0f,
                key1 = song.id, key2 = active
            ) {
                if (!active) return@produceState
                while (true) {
                    val d = globalDurationMs
                    value = if (d > 0) (globalPositionMs.toFloat() / d).coerceIn(0f, 1f) else 0f
                    kotlinx.coroutines.delay(250)
                }
            }
            val shownProgress = if (dragPos.floatValue >= 0f) dragPos.floatValue else progress
            Box(
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth(0.85f)
                    .height(16.dp) // 命中区：视觉 3dp，触摸放宽
                    .pointerInput(active, song.id) {
                        if (!active) return@pointerInput
                        fun frac(x: Float) = (x / size.width).coerceIn(0f, 1f)
                        // 点按直接跳；拖动期间实时预览（dragPos），松手提交
                        detectTapGestures { pos -> onSeek(frac(pos.x)) }
                    }
                    .pointerInput(active, song.id) {
                        if (!active) return@pointerInput
                        fun frac(x: Float) = (x / size.width).coerceIn(0f, 1f)
                        detectHorizontalDragGestures(
                            onHorizontalDrag = { change, _ ->
                                change.consume()
                                dragPos.floatValue = frac(change.position.x)
                            },
                            onDragStart = { dragPos.floatValue = frac(it.x) },
                            onDragEnd = {
                                onSeek(dragPos.floatValue)
                                dragPos.floatValue = -1f
                            },
                            onDragCancel = { dragPos.floatValue = -1f }
                        )
                    }
            ) {
                Box(
                    Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .height((3 * contentScale).dp)
                        .background(Color.White.copy(alpha = 0.15f), IrisShape.item)
                ) {
                    Box(
                        Modifier
                            .fillMaxWidth(shownProgress)
                            .height((3 * contentScale).dp)
                            .background(
                                if (active) Color.White.copy(alpha = 0.95f)
                                else Color.Transparent,
                                IrisShape.item
                            )
                    )
                }
            }
            }
            if (active) {
                // 播放磁贴：多行歌词（上句 + 当前句放大高亮 + 下句们）+ 左下角控制
                val lyricState by rememberPosterLyric(song)
                val curIdx = lyricState?.currentIdx ?: 0
                // 整列歌词的滚动动画：动画"当前行索引"本身（浮点连续变化），
                // 位移 = −(animIdx 的小数偏移) × 行高。
                //
                // 原实现是坏的：行窗口按整数 currentIdx 排布（idx 一变行立刻跳位），
                // 位移却写成 (currentIdx − animIdx) * 26f —— 符号与补偿方向相反，
                // 等于"先跳过去、再往回抖一下"，就是看到的跳变。
                // 正确做法：窗口锚点用 animIdx 取整，位移补掉它的小数部分，
                // 两者始终反相抵消，整列才是连续滑过去的。
                val animIdx = remember { androidx.compose.animation.core.Animatable(0f) }
                // 首次拿到歌词时直接落位，不从 0 滚过去（否则进卡瞬间歌词哗啦啦刷一片）
                var lyricPrimed by remember(song.id) { mutableStateOf(false) }
                LaunchedEffect(curIdx, lyricState != null) {
                    if (lyricState == null) return@LaunchedEffect
                    if (!lyricPrimed) {
                        lyricPrimed = true
                        animIdx.snapTo(curIdx.toFloat())
                        return@LaunchedEffect
                    }
                    animIdx.animateTo(
                        targetValue = curIdx.toFloat(),
                        animationSpec = androidx.compose.animation.core.spring(
                            dampingRatio = 1f,
                            stiffness = 130f
                        )
                    )
                }
                // 行高（含行距）随内容缩放，位移量必须用同一个值，否则滑动距离对不上行位
                val lyricFontSp = POSTER_LYRIC_FONT * contentScale
                val lyricRowH = (lyricFontSp + POSTER_LYRIC_LINE_GAP * contentScale)
                val rowHPx = with(density) { lyricRowH.dp.toPx() }
                Box(
                    Modifier
                        .fillMaxSize()
                        .padding(
                            start = (10 * contentScale).dp,
                            end = (10 * contentScale).dp,
                            top = (10 * contentScale).dp,
                            bottom = (44 * contentScale).dp
                        )
                        .clipToBounds()
                ) {
                    // 绝对定位滚动：每行锚在 Box 垂直中心，再按 (i − animIdx) × 行高 偏移。
                    // 关键：位置对连续 animIdx 严格线性，没有"取整窗口 + Center 重排"
                    // 那种离散跳变（旧实现 animIdx 每跨整数窗口跳一格、Center 又重对齐，
                    // 就是"间距突然变 / 最下一句突然出现"的根因）。当前句 (i==animIdx)
                    // 偏移 0 居中，其余行上下等距分布，整列随 animIdx 连续平滑滑动。
                    lyricState?.let { ls ->
                        val count = ls.lines.size
                        for (s in 0 until 9) {
                            val i = (kotlin.math.floor(animIdx.value).toInt()) - 2 + s
                            if (i < 0 || i >= count) continue
                            val dF = i - animIdx.value
                            val edgeFade = when {
                                dF > 2f -> 1f - (dF - 2f) / 3.6f
                                dF < -1f -> 1f + (dF + 1f) / 1.8f
                                else -> 1f
                            }.coerceIn(0f, 1f)
                            val focus = (1f - kotlin.math.abs(dF)).coerceIn(0f, 1f)
                            LyricRow(
                                text = ls.lines[i].text,
                                focus = focus,
                                edgeFade = edgeFade,
                                baseFontSp = lyricFontSp,
                                rowHeightDp = lyricRowH,
                                modifier = Modifier
                                    .align(Alignment.CenterStart)
                                    .offset { IntOffset(0, ((i - animIdx.value) * rowHPx).roundToInt()) }
                                    .zIndex(focus)   // 当前句置顶，放大时不被邻行盖住
                            )
                        }
                    } ?: Text(
                        song.title,
                        color = Color.White,
                        fontSize = (15 * contentScale).sp,
                        lineHeight = (20 * contentScale).sp,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Start,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .align(Alignment.CenterStart)
                            .fillMaxWidth()
                    )
                }
                Row(
                    Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = (2 * contentScale).dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // 海报墙只保留一个播放/暂停键：切歌靠拖动探索 + 点其它磁贴完成，
                    // 上一首/下一首在此布局下冗余，去掉后卡片更干净。
                    PlayPausePosterBtn(
                        isPlaying = posterIsPlaying.value,
                        onToggle = onToggle,
                        scale = contentScale
                    )
                }
            } else {
                Text(
                    song.title,
                    color = Color.White,
                    fontSize = (11 * contentScale).sp,
                    lineHeight = (13 * contentScale).sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.align(Alignment.BottomStart)
                )
            }
        }
        if (active) {
            Box(
                Modifier
                    .fillMaxSize()
                    .border(1.5.dp, colors.primary.copy(alpha = 0.9f), IrisShape.item)
            )
        }
    }
}

/** 海报墙播放状态快照（独立于整个 PlayerUiState，避免进度轮询引发全墙重组） */
private val posterIsPlaying = androidx.compose.runtime.mutableStateOf(false)

/**
 * 磁贴基准边长。cellPx 的默认值，同时是 contentScale 的分母：
 * contentScale = cellPx / POSTER_CELL_BASE，正好 1.0 对应默认缩放档。
 */
private val POSTER_CELL_BASE = 118.dp

/**
 * 相机（视野）缩放范围：双指缩放不改变卡片尺寸，只缩放"视野"（位图相机）。
 * 卡片几何（cellPx/step）恒定，相机范围 [CAM_MIN, CAM_MAX]；松手平滑回弹到 1。
 * 0.5→2.0 即视野可从"看全局"缩到"看局部"，全程零重排、零落盘。
 */
private const val POSTER_CAM_MIN = 0.8f
private const val POSTER_CAM_MAX = 1.6f

/**
 * 内容缩放的量化步进。contentScale 被吸附到该步进的整数倍，
 * 避免缩放时每帧改字号触发整墙文字重排（抖动+掉帧的根因）。
 * 0.05 → 在 0.63~1.19 的有效区间里约 12 档，跨档台阶肉眼几乎不可察。
 */
private const val POSTER_SCALE_STEP = 0.05f

@Composable
private fun PlayPausePosterBtn(
    isPlaying: Boolean,
    onToggle: () -> Unit,
    scale: Float = 1f
) {
    PosterIconBtn(if (isPlaying) PosterIcons.Pause else PosterIcons.Play, onToggle, scale = scale)
}

/** 图标按钮：无底、圆角矢量图标（与播放卡风格一致） */
@Composable
private fun PosterIconBtn(
    icon: ImageVector,
    onClick: () -> Unit,
    mirrored: Boolean = false,
    /** 内容缩放：热区与图标同时等比放大，保证放大后的卡上按钮不显小 */
    scale: Float = 1f
) {
    Box(
        Modifier
            .size((38 * scale).dp)
            .irisPressable(feedback = PressFeedback.TAP, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            icon, contentDescription = null, tint = Color.White,
            modifier = Modifier
                .size((26 * scale).dp)
                .then(if (mirrored) Modifier.graphicsLayer { scaleX = -1f } else Modifier)
        )
    }
}

/** 与播放卡同源的手写 Material Symbols 图标 */
private object PosterIcons {
    private fun icon(path: String): ImageVector = ImageVector.Builder(
        defaultWidth = 24.dp, defaultHeight = 24.dp,
        viewportWidth = 24f, viewportHeight = 24f
    ).apply {
        addPath(
            pathData = PathParser().parsePathString(path).toNodes(),
            fill = androidx.compose.ui.graphics.SolidColor(Color.Black)
        )
    }.build()

    val Play by lazy { icon("M8,5.14v13.72c0,0.79 0.87,1.27 1.54,0.84l10.79,-6.86c0.62,-0.39 0.62,-1.29 0,-1.69L9.54,4.29C8.87,3.87 8,4.34 8,5.14z") }
    val Pause by lazy { icon("M8,19c1.1,0 2,-0.9 2,-2V7c0,-1.1 -0.9,-2 -2,-2S6,5.9 6,7v10C6,18.1 6.9,19 8,19zM16,19c1.1,0 2,-0.9 2,-2V7c0,-1.1 -0.9,-2 -2,-2s-2,0.9 -2,2v10C14,18.1 14.9,19 16,19z") }
}

/**
 * 单行歌词：当前句加白放大，其他句半透明小字。
 *
 * @param focus 当前行权重 0..1（1 = 正好是当前句）。由连续的 animIdx 距离算出，
 *   所以高亮/字号是"随滚动渐变"的，不是到点硬切。这也是为什么这里不再用
 *   animateFloatAsState：值本身已经连续，再套一层动画只会拖慢并打架。
 * @param rowHeightDp 固定行高。必须恒定且与外层滚动位移用的行高一致，
 *   否则当前句放大时行高变化会顶动整列，滑动距离对不上行位置。
 */
@Composable
private fun LyricRow(
    text: String,
    focus: Float,
    edgeFade: Float = 1f,
    baseFontSp: Float = POSTER_LYRIC_FONT,
    rowHeightDp: Float = POSTER_LYRIC_FONT + POSTER_LYRIC_LINE_GAP,
    modifier: Modifier = Modifier
) {
    val effAlpha = (0.52f + 0.48f * focus) * edgeFade
    // 非当前句缩到 0.705，当前句 1.0，中间连续过渡
    val fontScale = 0.705f + 0.295f * focus
    Box(
        modifier
            .fillMaxWidth()
            .height(rowHeightDp.dp),
        contentAlignment = Alignment.CenterStart
    ) {
        Text(
            text,
            color = Color.White.copy(alpha = effAlpha),
            fontSize = (baseFontSp * fontScale).sp,
            // 行高交给外层 Box 固定，这里给足避免文字被自身 lineHeight 裁切
            lineHeight = (baseFontSp * fontScale * 1.15f).sp,
            // 字重随 focus 连续插值（Normal=400 → Bold=700）。旧写法是 focus>0.5 硬切
            // Bold/Normal，每行滚过焦点瞬间字重跳变、字宽突变 → 滚动"抽搐"的主因。
            fontWeight = androidx.compose.ui.text.font.FontWeight((400 + (300 * focus).roundToInt())),
            textAlign = TextAlign.Start,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

/** 海报墙歌词基准字号（sp），contentScale 会等比放大它 */
private const val POSTER_LYRIC_FONT = 17f

/** 歌词行间距（sp 当量）。行高 = 字号 + 该值，随 contentScale 等比缩放 */
private const val POSTER_LYRIC_LINE_GAP = 9f

// ==================== 歌词缓存 ====================

// 歌词全局缓存：key 为歌曲文件路径 */
private val posterLyricCache = java.util.concurrent.ConcurrentHashMap<String, List<LyricLine>>()

/** 数据类：歌词列表 + 当前高亮行下标 */
private class PosterLyricState(
    val lines: List<LyricLine>,
    val currentIdx: Int
)

/**
 * 当前播放歌曲的歌词状态（全文 + 当前高亮行下标）。
 * 独立 state：进度轮询只读全局快照，不触发磁贴重组。
 */
@Composable
private fun rememberPosterLyric(song: Song): androidx.compose.runtime.State<PosterLyricState?> {
    val path = song.filePath
    var state by remember(path) {
        mutableStateOf<PosterLyricState?>(null)
    }

    LaunchedEffect(path) {
        var lines = posterLyricCache[path]
        if (lines == null) {
            lines = withContext(Dispatchers.IO) { LyricParser.loadLyrics(path, globalDurationMs) }
            posterLyricCache[path] = lines
        }
        val allLines = lines
        while (true) {
            // 60ms 轮询：歌词行切换的时间精度直接决定"滑动"的起始时机。
            // 原来 250ms 一跳，最坏情况整整晚 1/4 秒才开始滑，观感上就是
            // "歌都唱下一句了字才动"，再平滑的动画也救不回来。
            // 这里只做一次整数比较 + 可能的一次赋值，60ms 的成本可以忽略。
            kotlinx.coroutines.delay(60)
            val idx = LyricParser.currentIndex(allLines, globalPositionMs)
            val next = if (idx >= 0 && idx < allLines.size) idx else 0
            // 只在行号真的变了才写 state，避免每 60ms 触发一次无意义重组
            if (state?.currentIdx != next && allLines.isNotEmpty()) {
                state = PosterLyricState(allLines, next)
            }
        }
    }
    return androidx.compose.runtime.rememberUpdatedState(state)
}

/** 全局快照：DeckPosterWall 进入组合时刷新，供歌词轮询读取 */
private var globalPositionMs: Long = 0
private var globalDurationMs: Long = 0